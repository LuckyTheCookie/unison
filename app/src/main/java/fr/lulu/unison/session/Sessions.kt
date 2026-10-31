package fr.lulu.unison.session

import android.util.Log
import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.Member
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.net.ClientTransport
import fr.lulu.unison.net.Connection
import fr.lulu.unison.net.HostTransport
import fr.lulu.unison.net.TransportException
import fr.lulu.unison.sync.ClockSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

sealed interface LinkStatus {
    data object Connected : LinkStatus
    data class Reconnecting(val attempt: Int) : LinkStatus
    data class Lost(val reason: String) : LinkStatus
}

/** Ce que l'interface voit d'une session, qu'on en soit l'hôte ou un invité. */
interface SessionHandle {
    val me: Member
    val isHost: Boolean
    val joinInfo: JoinInfo
    val snapshot: StateFlow<SessionSnapshot>
    val status: StateFlow<LinkStatus>
    fun hostNow(): Long
    fun send(cmd: Cmd)
    suspend fun leave()
}

/** Hôte : source de vérité de la file, diffuse l'état à tous les invités. */
class HostSession(
    override val me: Member,
    override val joinInfo: JoinInfo,
    initial: SessionSnapshot,
    private val transport: HostTransport,
    private val logic: SessionLogic = SessionLogic(),
) : SessionHandle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow(initial.copy(members = listOf(me)))
    private val peers = ConcurrentHashMap<String, Connection>() // memberId -> lien
    override val isHost = true
    override val snapshot: StateFlow<SessionSnapshot> = state.asStateFlow()
    override val status: StateFlow<LinkStatus> = MutableStateFlow(LinkStatus.Connected)

    init {
        scope.launch { for (conn in transport.connections) launch { handle(conn) } }
        scope.launch {
            state.distinctUntilChangedBy { it.version }.collect { s ->
                val msg = Msg.State(s)
                peers.values.forEach { launch { it.send(msg) } }
            }
        }
        // Passage automatique au titre suivant.
        scope.launch {
            while (isActive) {
                delay(200)
                if (logic.isFinished(state.value)) mutate { logic.advance(it) }
            }
        }
    }

    override fun hostNow() = System.currentTimeMillis()

    override fun send(cmd: Cmd) = mutate { logic.apply(it, cmd, me.id) }

    @Synchronized
    private fun mutate(f: (SessionSnapshot) -> SessionSnapshot) = state.update(f)

    private suspend fun handle(conn: Connection) {
        val hello = try {
            withTimeout(10_000) { conn.incoming.receive() } as? Msg.Hello
        } catch (_: Throwable) { null }
        if (hello == null || hello.secret != joinInfo.secret) {
            conn.send(Msg.Rejected("Code de session invalide")); conn.close(); return
        }
        val member = Member(hello.deviceId, hello.name.take(24).ifBlank { "Invité" }, hello.color)
        mutate { s ->
            val others = s.members.filterNot { it.id == member.id }
            s.copy(members = others + member, version = s.version + 1)
        }
        // Le Welcome part avant toute diffusion d'état vers ce lien.
        conn.send(Msg.Welcome(member.id, state.value))
        peers.put(member.id, conn)?.let { old -> scope.launch { old.close() } }
        conn.send(Msg.State(state.value)) // rattrape un éventuel changement entre-temps
        try {
            for (msg in conn.incoming) when (msg) {
                is Msg.Command -> mutate { logic.apply(it, msg.cmd, member.id) }
                is Msg.Ping -> conn.send(Msg.Pong(msg.clientTime, System.currentTimeMillis()))
                Msg.Bye -> break
                else -> Unit
            }
        } finally {
            if (peers.remove(member.id, conn)) {
                mutate { s -> s.copy(members = s.members.filterNot { it.id == member.id }, version = s.version + 1) }
            }
            conn.close()
        }
    }

    override suspend fun leave() {
        withContext(NonCancellable) {
            peers.values.forEach { runCatching { it.send(Msg.Bye); it.close() } }
            peers.clear()
            transport.stop()
        }
        scope.cancel()
    }
}

/** Invité : reflète l'état de l'hôte et lui envoie ses demandes. */
class ClientSession private constructor(
    override val me: Member,
    override val joinInfo: JoinInfo,
    private val transport: ClientTransport,
    welcome: Msg.Welcome,
    private var conn: Connection,
) : SessionHandle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = ClockSync()
    private val state = MutableStateFlow(welcome.snapshot)
    private val _status = MutableStateFlow<LinkStatus>(LinkStatus.Connected)
    private var leaving = false
    override val isHost = false
    override val snapshot: StateFlow<SessionSnapshot> = state.asStateFlow()
    override val status: StateFlow<LinkStatus> = _status.asStateFlow()

    init {
        run(conn)
        scope.launch {
            repeat(8) { ping(); delay(150) }
            while (isActive) { delay(15_000); ping() }
        }
    }

    override fun hostNow() = clock.hostNow()

    private suspend fun ping() = runCatching { conn.send(Msg.Ping(System.currentTimeMillis())) }

    private fun run(c: Connection) = scope.launch {
        for (msg in c.incoming) when (msg) {
            is Msg.State -> state.value = msg.snapshot
            is Msg.Pong -> clock.addSample(msg.clientTime, msg.hostTime, System.currentTimeMillis())
            is Msg.Rejected -> { _status.value = LinkStatus.Lost(msg.reason); return@launch }
            Msg.Bye -> { _status.value = LinkStatus.Lost("${joinInfo.hostName} a terminé la session"); return@launch }
            else -> Unit
        }
        if (!leaving) reconnect()
    }

    private suspend fun reconnect() {
        for (attempt in 1..6) {
            _status.value = LinkStatus.Reconnecting(attempt)
            delay(1_000L * attempt)
            try {
                val (c, w) = handshake(transport, joinInfo, me)
                conn = c
                state.value = w.snapshot
                _status.value = LinkStatus.Connected
                run(c)
                repeat(4) { ping(); delay(150) }
                return
            } catch (t: Throwable) {
                Log.w("UnisonClient", "reconnexion $attempt", t)
            }
        }
        _status.value = LinkStatus.Lost("Connexion perdue avec ${joinInfo.hostName}")
    }

    override fun send(cmd: Cmd) {
        scope.launch { conn.send(Msg.Command(cmd)) }
    }

    override suspend fun leave() {
        leaving = true
        withContext(NonCancellable) {
            runCatching { conn.send(Msg.Bye); conn.close() }
            transport.shutdown()
        }
        scope.cancel()
    }

    companion object {
        private suspend fun handshake(t: ClientTransport, info: JoinInfo, me: Member): Pair<Connection, Msg.Welcome> {
            val c = t.connect(info, me.name)
            c.send(Msg.Hello(me.id, me.name, me.color, info.secret))
            val reply = try {
                withTimeout(10_000) {
                    var m = c.incoming.receive()
                    while (m !is Msg.Welcome && m !is Msg.Rejected) m = c.incoming.receive()
                    m
                }
            } catch (e: Throwable) {
                c.close(); throw TransportException("${info.hostName} ne répond pas", e)
            }
            return when (reply) {
                is Msg.Welcome -> c to reply
                is Msg.Rejected -> { c.close(); throw TransportException(reply.reason) }
                else -> { c.close(); throw TransportException("Réponse inattendue de l'hôte") }
            }
        }

        suspend fun join(transport: ClientTransport, info: JoinInfo, me: Member): ClientSession {
            val (c, w) = handshake(transport, info, me)
            return ClientSession(me.copy(id = w.memberId), info, transport, w, c)
        }
    }
}

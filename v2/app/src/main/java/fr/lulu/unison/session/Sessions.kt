package fr.lulu.unison.session

import android.util.Log
import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.Member
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.net.ClientTransport
import fr.lulu.unison.net.Connection
import fr.lulu.unison.net.HostTransport
import fr.lulu.unison.net.TransportException
import fr.lulu.unison.sync.ClockSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/** Ce que le reste de l'app voit du lecteur, qu'on écoute seul, qu'on invite ou qu'on soit invité. */
interface SessionHandle {
    val me: Member
    val isHost: Boolean
    /** Non nul quand d'autres téléphones peuvent rejoindre (ou qu'on a rejoint quelqu'un). */
    val joinInfo: StateFlow<JoinInfo?>
    val snapshot: StateFlow<SessionSnapshot>
    val status: StateFlow<LinkStatus>
    fun hostNow(): Long
    fun send(cmd: Cmd)
}

/**
 * La file de ce téléphone, dont il est la source de vérité. Elle existe toujours : seule par défaut,
 * elle s'ouvre aux invités avec [share] sans que la musique s'interrompe, et se referme avec [unshare].
 */
class HostSession(
    me: Member,
    initial: SessionSnapshot = SessionSnapshot(),
    private val logic: SessionLogic = SessionLogic(),
    /** Position réelle du lecteur local (uid du titre, position), pour repartir d'une ancre juste. */
    private val playerPosition: () -> Pair<String, Long>? = { null },
) : SessionHandle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow(initial.solo(me))
    private val peers = ConcurrentHashMap<String, Connection>() // memberId -> lien
    private val _joinInfo = MutableStateFlow<JoinInfo?>(null)
    private var transport: HostTransport? = null
    private var accepting: Job? = null

    @Volatile
    override var me: Member = me
        private set
    override val isHost = true
    override val joinInfo: StateFlow<JoinInfo?> = _joinInfo.asStateFlow()
    override val snapshot: StateFlow<SessionSnapshot> = state.asStateFlow()
    override val status: StateFlow<LinkStatus> = MutableStateFlow(LinkStatus.Connected)

    /** Faux quand ce téléphone ne joue pas le son lui-même : l'enchaînement se fait alors à la durée annoncée. */
    @Volatile
    var localPlayback: Boolean = true

    init {
        scope.launch {
            state.distinctUntilChangedBy { it.version }.collect { s ->
                if (peers.isEmpty()) return@collect
                val msg = Msg.State(s)
                peers.values.forEach { launch { runCatching { it.send(msg) } } }
            }
        }
        // Filet de sécurité : si le lecteur local ne signale pas la fin du titre, on enchaîne quand même.
        scope.launch {
            while (isActive) {
                delay(200)
                val margin = if (localPlayback) OVERDUE_WITH_PLAYER_MS else 250L
                if (logic.isOverdue(state.value, margin)) mutate { if (logic.isOverdue(it, margin)) logic.advance(it) else it }
            }
        }
    }

    override fun hostNow() = System.currentTimeMillis()

    override fun send(cmd: Cmd) = mutate { logic.apply(it, cmd, me.id) }

    /** Le lecteur de ce téléphone a terminé le titre [uid]. */
    fun trackEnded(uid: String) = mutate { logic.ended(it, uid) }

    /** Le titre [uid] est illisible : on passe au suivant. */
    fun trackFailed(uid: String) = mutate { if (it.anchor.item?.uid == uid) logic.advance(it, manual = true) else it }

    /** Recale l'ancre sur la position réelle du lecteur (sans effet à plusieurs). */
    fun reanchor(uid: String, positionMs: Long) = mutate { logic.reanchor(it, uid, positionMs) }

    fun updateMe(member: Member) {
        val old = me
        me = member.copy(isHost = true)
        mutate { s -> s.copy(members = s.members.map { if (it.id == old.id) me else it }, hostName = me.name, version = s.version + 1) }
    }

    @Synchronized
    private fun mutate(f: (SessionSnapshot) -> SessionSnapshot) = state.update(f)

    /** Ouvre la file aux invités. */
    suspend fun share(transport: HostTransport, info: JoinInfo) {
        unshare()
        this.transport = transport
        mutate { it.copy(sessionId = info.sessionId, hostName = info.hostName, transport = info.transport, version = it.version + 1) }
        _joinInfo.value = info
        accepting = scope.launch { for (conn in transport.connections) launch { handle(conn, info) } }
    }

    suspend fun unshare() {
        val t = transport ?: return
        transport = null
        accepting?.cancel(); accepting = null
        _joinInfo.value = null
        withContext(NonCancellable) {
            peers.values.forEach { runCatching { it.send(Msg.Bye); it.close() } }
            peers.clear()
            runCatching { t.stop() }
        }
        mutate { it.solo(me).copy(version = it.version + 1) }
    }

    private suspend fun handle(conn: Connection, info: JoinInfo) {
        val hello = try {
            withTimeout(10_000) { conn.incoming.receive() } as? Msg.Hello
        } catch (_: Throwable) { null }
        if (hello == null || hello.secret != info.secret) {
            runCatching { conn.send(Msg.Rejected("Code de session invalide")); conn.close() }; return
        }
        if (hello.v != JoinInfo.PROTOCOL) {
            runCatching { conn.send(Msg.Rejected("Mets Unison à jour pour rejoindre cette session")); conn.close() }; return
        }
        val member = Member(hello.deviceId, hello.name.take(24).ifBlank { "Invité" }, hello.color)
        // Tant qu'on était seul, l'ancre suivait le lecteur de loin : on la remet d'aplomb avant de la partager.
        playerPosition()?.let { (uid, pos) -> reanchor(uid, pos) }
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
            runCatching { conn.close() }
        }
    }

    suspend fun close() {
        unshare()
        scope.cancel()
    }

    private fun SessionSnapshot.solo(me: Member) =
        copy(sessionId = "", hostName = me.name, transport = TransportKind.LOCAL, members = listOf(me.copy(isHost = true)))

    private companion object {
        const val OVERDUE_WITH_PLAYER_MS = 4_000L
    }
}

/** Invité : reflète l'état de l'hôte et lui envoie ses demandes. */
class ClientSession private constructor(
    override val me: Member,
    private val info: JoinInfo,
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
    override val joinInfo: StateFlow<JoinInfo?> = MutableStateFlow(info)
    override val snapshot: StateFlow<SessionSnapshot> = state.asStateFlow()
    override val status: StateFlow<LinkStatus> = _status.asStateFlow()

    init {
        run(conn)
        scope.launch {
            repeat(8) { ping(); delay(150) }
            while (isActive) { delay(10_000); ping() }
        }
    }

    override fun hostNow() = clock.hostNow()

    private suspend fun ping() {
        try {
            conn.send(Msg.Ping(System.currentTimeMillis()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Lien en train de tomber : la boucle de réception s'occupe de la reconnexion.
        }
    }

    private fun run(c: Connection) = scope.launch {
        for (msg in c.incoming) when (msg) {
            is Msg.State -> state.value = msg.snapshot
            is Msg.Pong -> clock.addSample(msg.clientTime, msg.hostTime, System.currentTimeMillis())
            is Msg.Rejected -> { _status.value = LinkStatus.Lost(msg.reason); return@launch }
            Msg.Bye -> { _status.value = LinkStatus.Lost("${info.hostName} a terminé la session"); return@launch }
            else -> Unit
        }
        if (!leaving) reconnect()
    }

    private suspend fun reconnect() {
        for (attempt in 1..6) {
            _status.value = LinkStatus.Reconnecting(attempt)
            delay(1_000L * attempt)
            try {
                val (c, w) = handshake(transport, info, me)
                conn = c
                state.value = w.snapshot
                _status.value = LinkStatus.Connected
                run(c)
                repeat(4) { ping(); delay(150) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("UnisonClient", "reconnexion $attempt", t)
            }
        }
        _status.value = LinkStatus.Lost("Connexion perdue avec ${info.hostName}")
    }

    override fun send(cmd: Cmd) {
        scope.launch { runCatching { conn.send(Msg.Command(cmd)) } }
    }

    suspend fun leave() {
        leaving = true
        withContext(NonCancellable) {
            runCatching { conn.send(Msg.Bye); conn.close() }
            runCatching { transport.shutdown() }
        }
        scope.cancel()
    }

    companion object {
        private suspend fun handshake(t: ClientTransport, info: JoinInfo, me: Member): Pair<Connection, Msg.Welcome> {
            val c = t.connect(info, me.name)
            c.send(Msg.Hello(me.id, me.name, me.color, info.secret, JoinInfo.PROTOCOL))
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

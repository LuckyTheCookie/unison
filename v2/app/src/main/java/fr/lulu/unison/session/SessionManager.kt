package fr.lulu.unison.session

import android.content.Context
import android.content.Intent
import android.util.Log
import fr.lulu.unison.data.Prefs
import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.Member
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.net.LanClient
import fr.lulu.unison.net.LanHost
import fr.lulu.unison.net.NearbyClient
import fr.lulu.unison.net.NearbyHost
import fr.lulu.unison.net.nearbyEndpointName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * Le lecteur du point de vue « qui décide de la file » : la file locale ([local]), qu'on peut ouvrir
 * à d'autres téléphones, ou celle d'un hôte qu'on a rejoint ([guest]).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SessionManager(
    private val context: Context,
    private val prefs: Prefs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Mutex()
    private val queueFile = File(context.filesDir, "queue.json")

    /** Fournie par le lecteur : position réelle du titre en cours. */
    @Volatile
    var playerPosition: () -> Pair<String, Long>? = { null }

    val local = HostSession(
        me = Member("local", "Moi", 0, isHost = true),
        initial = restore(),
        playerPosition = { playerPosition() },
    )

    private val _guest = MutableStateFlow<ClientSession?>(null)
    val guest: StateFlow<ClientSession?> = _guest.asStateFlow()

    private val _active = MutableStateFlow<SessionHandle>(local)
    /** La session qui pilote le lecteur en ce moment. */
    val active: StateFlow<SessionHandle> = _active.asStateFlow()

    val snapshot: StateFlow<SessionSnapshot> =
        active.flatMapLatest { it.snapshot }.stateIn(scope, SharingStarted.Eagerly, local.snapshot.value)

    /** Invitation en cours (hôte) ou session rejointe (invité) ; nul quand on écoute seul. */
    val joinInfo: StateFlow<JoinInfo?> =
        active.flatMapLatest { it.joinInfo }.stateIn(scope, SharingStarted.Eagerly, null)

    val status: StateFlow<LinkStatus> =
        active.flatMapLatest { it.status }.stateIn(scope, SharingStarted.Eagerly, LinkStatus.Connected)

    private val _listenHere = MutableStateFlow(true)
    /** « Écouter sur ce téléphone » : si faux, on gère la file sans jouer le son ici. */
    val listenHere: StateFlow<Boolean> = _listenHere.asStateFlow()

    init {
        scope.launch {
            val p = prefs.current()
            local.updateMe(Member(p.deviceId, p.name, p.color, isHost = true))
            prefs.profile.collect { if (it.deviceId.isNotEmpty()) local.updateMe(Member(it.deviceId, it.name, it.color, isHost = true)) }
        }
        // La file locale survit au redémarrage de l'app.
        scope.launch {
            local.snapshot.debounce(1_500).collect { s -> withContext(Dispatchers.IO) { persist(s) } }
        }
        // Le service de premier plan garde le lien réseau en vie tant qu'on est à plusieurs.
        scope.launch {
            combine(joinInfo, guest) { info, g -> info != null || g != null }.distinctUntilChanged().collect { on ->
                val intent = Intent(context, SessionService::class.java)
                if (on) runCatching { context.startForegroundService(intent) }.onFailure { Log.w(TAG, "service", it) }
                else context.stopService(intent)
            }
        }
    }

    fun send(cmd: Cmd) = _active.value.send(cmd)

    fun hostNow(): Long = _active.value.hostNow()

    val isHost: Boolean get() = _active.value.isHost

    fun setListenHere(value: Boolean) {
        _listenHere.value = value
        local.localPlayback = value
    }

    /** Ouvre la file en cours à d'autres téléphones ; la musique continue. */
    suspend fun share(kind: TransportKind): JoinInfo = gate.withLock {
        leaveGuest()
        val p = prefs.current()
        val sessionId = randomToken(8)
        val transport = when (kind) {
            TransportKind.NEARBY -> NearbyHost(context, nearbyEndpointName(p.name, sessionId))
            else -> LanHost()
        }
        val (host, port) = transport.start()
        val info = JoinInfo(sessionId, randomToken(16), p.name, if (kind == TransportKind.NEARBY) kind else TransportKind.LAN, host, port)
        local.share(transport, info)
        // En développement seulement : permet de rejoindre depuis un émulateur sans scanner le QR code.
        if (fr.lulu.unison.BuildConfig.DEBUG) Log.d(TAG, "join=${info.toUri()}")
        info
    }

    suspend fun stopSharing() = gate.withLock { local.unshare() }

    /** Rejoint la session de quelqu'un : sa file remplace la nôtre le temps de la session. */
    suspend fun join(info: JoinInfo) = gate.withLock {
        leaveGuest()
        local.unshare()
        local.send(Cmd.Pause)
        val p = prefs.current()
        val transport = if (info.transport == TransportKind.NEARBY) NearbyClient(context) else LanClient()
        val session = ClientSession.join(transport, info, Member(p.deviceId, p.name, p.color))
        _guest.value = session
        _active.value = session
    }

    /** Quitte la session en cours (invité) ou referme l'invitation (hôte) ; on retrouve sa file. */
    suspend fun leave() = gate.withLock {
        leaveGuest()
        local.unshare()
    }

    private suspend fun leaveGuest() {
        val g = _guest.value ?: return
        _active.value = local
        _guest.value = null
        g.leave()
    }

    private fun restore(): SessionSnapshot = runCatching {
        if (!queueFile.exists()) return SessionSnapshot()
        val s = ProtocolJson.decodeFromString(SessionSnapshot.serializer(), queueFile.readText())
        // On reprend là où on s'était arrêté, en pause.
        val position = s.anchor.positionMs
        s.copy(anchor = s.anchor.copy(positionMs = position, hostTimeMs = 0, playing = false), members = emptyList())
    }.getOrElse { SessionSnapshot() }

    private fun persist(s: SessionSnapshot) {
        runCatching {
            val a = s.anchor
            val paused = s.copy(anchor = a.copy(positionMs = a.positionAt(System.currentTimeMillis()), hostTimeMs = 0, playing = false), members = emptyList())
            val tmp = File(queueFile.parentFile, queueFile.name + ".tmp")
            tmp.writeText(ProtocolJson.encodeToString(SessionSnapshot.serializer(), paused))
            tmp.renameTo(queueFile)
        }
    }

    private fun randomToken(len: Int): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..len).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
    }

    private companion object {
        const val TAG = "UnisonSession"
    }
}

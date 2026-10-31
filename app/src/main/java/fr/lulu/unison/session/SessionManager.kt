package fr.lulu.unison.session

import android.content.Context
import android.content.Intent
import fr.lulu.unison.data.ProfileStore
import fr.lulu.unison.deezer.DeezerController
import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.Member
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.net.LanClient
import fr.lulu.unison.net.LanHost
import fr.lulu.unison.net.NearbyClient
import fr.lulu.unison.net.NearbyHost
import fr.lulu.unison.net.nearbyEndpointName
import fr.lulu.unison.sync.PlaybackSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** Session en cours (une seule à la fois) et synchronisation de Deezer associée. */
class SessionManager(
    private val context: Context,
    private val profiles: ProfileStore,
    val deezer: DeezerController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _active = MutableStateFlow<SessionHandle?>(null)
    val active: StateFlow<SessionHandle?> = _active.asStateFlow()

    private var sync: PlaybackSync? = null
    private var syncJob: Job? = null

    private val _listenHere = MutableStateFlow(true)
    /** « Écouter sur ce téléphone » : si faux, on gère la file sans jouer localement. */
    val listenHere: StateFlow<Boolean> = _listenHere.asStateFlow()

    val syncStatus: StateFlow<PlaybackSync.Status>?
        get() = sync?.status

    suspend fun create(kind: TransportKind): SessionHandle {
        leave()
        android.util.Log.i("UnisonSession", "lecture du profil")
        val p = profiles.current()
        val me = Member(p.deviceId, p.name, p.color, isHost = true)
        val sessionId = randomToken(8)
        val secret = randomToken(16)
        val transport = when (kind) {
            TransportKind.LAN -> LanHost()
            TransportKind.NEARBY -> NearbyHost(context, nearbyEndpointName(p.name, sessionId))
        }
        android.util.Log.i("UnisonSession", "démarrage du transport $kind")
        val (host, port) = transport.start()
        android.util.Log.i("UnisonSession", "transport prêt sur $host:$port")
        val info = JoinInfo(sessionId, secret, p.name, kind, host, port)
        val handle = HostSession(me, info, SessionSnapshot(sessionId, p.name, kind), transport)
        activate(handle)
        android.util.Log.i("UnisonSession", "session active")
        if (fr.lulu.unison.BuildConfig.DEBUG) android.util.Log.d("UnisonSession", "join=${info.toUri()}")
        return handle
    }

    suspend fun join(info: JoinInfo): SessionHandle {
        leave()
        val p = profiles.current()
        val me = Member(p.deviceId, p.name, p.color)
        val transport = if (info.transport == TransportKind.LAN) LanClient() else NearbyClient(context)
        val handle = ClientSession.join(transport, info, me)
        activate(handle)
        return handle
    }

    private fun activate(handle: SessionHandle) {
        _active.value = handle
        val s = PlaybackSync(scope, deezer, handle::hostNow).also { it.enabled = _listenHere.value }
        sync = s
        s.start()
        syncJob = scope.launch {
            handle.snapshot.map { it.anchor }.distinctUntilChanged().collect { s.onAnchor(it) }
        }
        context.startForegroundService(Intent(context, SessionService::class.java))
    }

    fun setListenHere(value: Boolean) {
        _listenHere.value = value
        sync?.enabled = value
    }

    suspend fun leave() {
        val handle = _active.value ?: return
        _active.value = null
        syncJob?.cancel(); syncJob = null
        sync?.stop(); sync = null
        handle.leave()
        context.stopService(Intent(context, SessionService::class.java))
    }

    fun send(cmd: Cmd) = _active.value?.send(cmd)

    private fun randomToken(len: Int): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..len).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
    }
}

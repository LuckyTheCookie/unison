package fr.lulu.unison.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import fr.lulu.unison.data.Prefs
import fr.lulu.unison.data.Settings
import fr.lulu.unison.model.PlayAnchor
import fr.lulu.unison.model.QueueItem
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.session.SessionManager
import fr.lulu.unison.youtube.StreamResolver
import fr.lulu.unison.youtube.StreamUnavailableException
import fr.lulu.unison.youtube.YtParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import kotlin.math.abs
import kotlin.math.pow

/**
 * Le lecteur de ce téléphone. Il ne décide de rien : il fait suivre à ExoPlayer l'état de la session
 * (quel titre, à quelle position, lecture ou pause), que cette session soit la file locale ou celle d'un hôte.
 * Tout se passe sur le fil principal, comme l'exige ExoPlayer.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    private val context: Context,
    private val sessions: SessionManager,
    private val streams: StreamResolver,
    private val prefs: Prefs,
    private val okHttp: OkHttpClient,
) {
    data class State(
        val loadedUid: String? = null,
        val buffering: Boolean = false,
        val playing: Boolean = false,
        val error: String? = null,
        /** Écart avec l'ancre de la session, quand on écoute à plusieurs. */
        val driftMs: Long? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var settings = Settings()

    private var loaded: QueueItem? = null
    private var startJob: Job? = null
    private var graceUntil = 0L          // elapsedRealtime : pas de correction de dérive avant
    private var nudging = false
    private var seekLeadMs = 60L         // avance à donner à un saut pour retomber pile
    private var startLeadMs = 40L        // avance à donner à un départ programmé
    private var measureLead = LeadKind.NONE
    private var retries = 0
    private var failures = 0
    private val loudness = HashMap<String, Double?>()

    private enum class LeadKind { NONE, SEEK, START }

    val exo: ExoPlayer by lazy { buildPlayer() }

    /** Ce que voient la notification, le casque et l'écran verrouillé : leurs boutons agissent sur la session. */
    val sessionPlayer: Player by lazy { SessionPlayer(exo) }

    fun start() {
        sessions.playerPosition = {
            // Appelé hors du fil principal par la session : on ne lit que des valeurs publiées.
            published
        }
        scope.launch { prefs.settings.collect { settings = it; applyVolume() } }
        scope.launch {
            combine(sessions.snapshot, sessions.listenHere) { s, here -> s to here }.collect { (s, here) -> reconcile(s, here) }
        }
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                runCatching { tick() }.onFailure { Log.w(TAG, "tick", it) }
            }
        }
        // Lie le service média à l'application : c'est lui qui passe au premier plan pendant la lecture.
        runCatching {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }.onFailure { Log.w(TAG, "service média", it) }
    }

    @Volatile
    private var published: Pair<String, Long>? = null

    /** Position à afficher : celle du lecteur quand il joue ici, sinon celle que donne l'ancre. */
    fun positionMs(): Long {
        val anchor = sessions.snapshot.value.anchor
        val item = loaded
        return if (item != null && item.uid == anchor.item?.uid && exo.playbackState != Player.STATE_IDLE) {
            exo.currentPosition.coerceAtLeast(0)
        } else anchor.positionAt(sessions.hostNow())
    }

    /** Durée du titre en cours, connue du lecteur ou annoncée par le catalogue. */
    fun durationMs(): Long {
        val item = sessions.snapshot.value.anchor.item ?: return 0
        val d = if (loaded?.uid == item.uid) exo.duration else C.TIME_UNSET
        return if (d != C.TIME_UNSET && d > 0) d else item.track.durationMs
    }

    // --- Suivi de la session ---

    private fun reconcile(s: SessionSnapshot, listenHere: Boolean) {
        val a = s.anchor
        val item = a.item
        if (!listenHere || item == null) {
            if (loaded != null) unload()
            return
        }
        if (item.uid != loaded?.uid) load(item)
        follow(a, s.shared, anchorChanged = true)
        s.queue.firstOrNull()?.let { next -> scope.launch(Dispatchers.IO) { runCatching { streams.resolve(next.track.id) } } }
    }

    private fun unload() {
        startJob?.cancel(); startJob = null
        loaded = null
        published = null
        exo.stop()
        exo.clearMediaItems()
        _state.value = State()
    }

    private fun load(item: QueueItem) {
        startJob?.cancel(); startJob = null
        loaded = item
        retries = 0
        nudging = false
        val t = item.track
        val media = MediaItem.Builder()
            .setMediaId(item.uid)
            .setUri(Uri.parse("$SCHEME://track/${t.id}"))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album.ifBlank { null })
                    .setArtworkUri(t.cover.takeIf { it.isNotBlank() }?.let { Uri.parse(YtParser.resize(it, 544)) })
                    .build(),
            )
            .build()
        exo.playWhenReady = false
        exo.setPlaybackParameters(PlaybackParameters.DEFAULT)
        exo.setMediaItem(media)
        exo.prepare()
        applyVolume()
        _state.value = State(loadedUid = item.uid, buffering = true)
    }

    /** Aligne lecture/pause et position sur l'ancre. */
    private fun follow(a: PlayAnchor, shared: Boolean, anchorChanged: Boolean) {
        val now = sessions.hostNow()
        if (!a.playing) {
            startJob?.cancel(); startJob = null
            if (exo.playWhenReady) exo.pause()
            resetSpeed()
            if (abs(exo.currentPosition - a.positionMs) > PAUSED_TOLERANCE_MS) exo.seekTo(a.positionMs)
            return
        }
        if (a.hostTimeMs > now) {
            // Départ programmé : calé en pause, puis lancé à l'heure dite sur tous les téléphones.
            if (exo.playWhenReady) exo.pause()
            resetSpeed()
            if (abs(exo.currentPosition - a.positionMs) > 40) exo.seekTo(a.positionMs)
            startJob?.cancel()
            startJob = scope.launch {
                while (isActive) {
                    val wait = a.hostTimeMs - sessions.hostNow() - startLeadMs
                    if (wait <= 0) break
                    delay(wait.coerceAtMost(50))
                }
                if (sessions.snapshot.value.anchor == a && loaded?.uid == a.item?.uid) {
                    exo.play()
                    grace(START_GRACE_MS, LeadKind.START)
                }
            }
            return
        }
        startJob?.cancel(); startJob = null
        if (anchorChanged) {
            val drift = exo.currentPosition - a.positionAt(now)
            if (abs(drift) > (if (shared) SyncPolicy.NUDGE_BAND_MS else SOLO_TOLERANCE_MS)) jumpTo(a)
        }
        if (!exo.playWhenReady) exo.play()
    }

    private fun jumpTo(a: PlayAnchor) {
        resetSpeed()
        exo.seekTo((a.positionAt(sessions.hostNow()) + seekLeadMs).coerceAtLeast(0))
        grace(SEEK_GRACE_MS, LeadKind.SEEK)
    }

    private fun grace(ms: Long, kind: LeadKind) {
        graceUntil = SystemClock.elapsedRealtime() + ms
        measureLead = kind
    }

    private fun resetSpeed() {
        if (nudging) { exo.setPlaybackParameters(PlaybackParameters.DEFAULT); nudging = false }
    }

    /** Toutes les [TICK_MS] : seul, l'ancre suit le lecteur ; à plusieurs, le lecteur suit l'ancre. */
    private fun tick() {
        val item = loaded ?: return
        val s = sessions.snapshot.value
        val a = s.anchor
        published = if (exo.playbackState == Player.STATE_READY) item.uid to exo.currentPosition else null
        if (a.item?.uid != item.uid || !a.playing || startJob?.isActive == true) return
        if (!exo.isPlaying || SystemClock.elapsedRealtime() < graceUntil) return
        val drift = exo.currentPosition - a.positionAt(sessions.hostNow())

        if (!s.shared) {
            _state.update { it.copy(driftMs = null) }
            if (sessions.isHost && abs(drift) > REANCHOR_MS) sessions.local.reanchor(item.uid, exo.currentPosition)
            return
        }

        _state.update { it.copy(driftMs = drift) }
        // Première mesure après un saut ou un départ : on apprend de combien il faut anticiper la prochaine fois.
        when (measureLead) {
            LeadKind.SEEK -> seekLeadMs = (seekLeadMs - drift / 2).coerceIn(0, 800)
            LeadKind.START -> startLeadMs = (startLeadMs - drift / 2).coerceIn(0, 400)
            LeadKind.NONE -> Unit
        }
        measureLead = LeadKind.NONE
        when (val action = SyncPolicy.decide(drift, nudging)) {
            SyncAction.Hold -> resetSpeed()
            is SyncAction.Nudge -> {
                if (!nudging || exo.playbackParameters.speed != action.speed) {
                    exo.setPlaybackParameters(PlaybackParameters(action.speed))
                    nudging = true
                }
            }
            SyncAction.Jump -> jumpTo(a)
        }
    }

    // --- Lecteur ---

    private fun buildPlayer(): ExoPlayer {
        val cacheBytes = runBlocking(Dispatchers.IO) { prefs.settings.first() }.also { settings = it }.cacheMb * 1024L * 1024L
        val cache = SimpleCache(File(context.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(cacheBytes), StandaloneDatabaseProvider(context))
        val cached = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(okHttp))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // L'adresse réelle du flux n'est demandée à YouTube qu'au moment de lire (elle expire en quelques heures).
        val resolving = ResolvingDataSource.Factory(cached) { spec ->
            if (spec.uri.scheme != SCHEME) return@Factory spec
            val id = spec.uri.lastPathSegment ?: throw StreamUnavailableException("Titre invalide")
            val stream = runBlocking { streams.resolve(id) }
            main.post { loudness[id] = stream.loudnessDb; applyVolume() }
            spec.buildUpon()
                .setUri(Uri.parse(stream.url))
                .setKey("$id.${stream.itag}")
                .setHttpRequestHeaders(stream.headers)
                .build()
        }
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)
        return player
    }

    /** Ramène les titres trop forts au niveau de référence de YouTube (jamais d'amplification). */
    private fun applyVolume() {
        val id = loaded?.track?.id ?: return
        val db = loudness[id]
        exo.volume = if (settings.normalizeVolume && db != null && db > 0) 10.0.pow(-db / 20.0).toFloat().coerceIn(0.2f, 1f) else 1f
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
            if (playbackState == Player.STATE_ENDED) {
                val uid = loaded?.uid ?: return
                if (sessions.isHost) sessions.local.trackEnded(uid)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying, error = if (isPlaying) null else it.error) }
            if (isPlaying) { failures = 0; retries = 0 }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Une autre app a pris le son, ou le casque a été débranché : c'est une vraie pause.
            if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
            ) sessions.send(Cmd.Pause)
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = loaded ?: return
            val causes = generateSequence<Throwable>(error) { it.cause }.toList()
            val expired = causes.any { it is HttpDataSource.InvalidResponseCodeException && it.responseCode in EXPIRED_CODES }
            val unavailable = causes.any { it is StreamUnavailableException }
            Log.w(TAG, "lecture impossible (${error.errorCodeName})")
            if (!unavailable && retries < MAX_RETRIES) {
                retries++
                if (expired) streams.invalidate(item.track.id)
                scope.launch {
                    delay(400L * retries)
                    if (loaded?.uid == item.uid) exo.prepare()
                }
                return
            }
            _state.update { it.copy(error = if (unavailable) "Titre indisponible" else "Lecture impossible, vérifie ta connexion", buffering = false) }
            if (!sessions.isHost) return
            // Un titre illisible ne doit pas bloquer la file ; trois de suite, c'est sans doute le réseau.
            if (++failures < 3) scope.launch { delay(1_200); sessions.local.trackFailed(item.uid) }
            else { failures = 0; sessions.send(Cmd.Pause) }
        }
    }

    /** Traduit les commandes média du système en demandes à la session, pour que tout le monde reste d'accord. */
    private inner class SessionPlayer(player: Player) : ForwardingPlayer(player) {
        private val extra = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(*extra).build()

        override fun isCommandAvailable(command: Int): Boolean = command in extra || super.isCommandAvailable(command)

        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true

        override fun play() { sessions.send(Cmd.Resume) }
        override fun pause() { sessions.send(Cmd.Pause) }
        override fun stop() { sessions.send(Cmd.Pause) }
        override fun setPlayWhenReady(playWhenReady: Boolean) { sessions.send(if (playWhenReady) Cmd.Resume else Cmd.Pause) }
        override fun seekTo(positionMs: Long) { sessions.send(Cmd.Seek(positionMs)) }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) { sessions.send(Cmd.Seek(positionMs.coerceAtLeast(0))) }
        override fun seekToDefaultPosition() { sessions.send(Cmd.Seek(0)) }
        override fun seekBack() { sessions.send(Cmd.Seek((currentPosition - 10_000).coerceAtLeast(0))) }
        override fun seekForward() { sessions.send(Cmd.Seek(currentPosition + 10_000)) }
        override fun seekToNext() { sessions.send(Cmd.Skip) }
        override fun seekToNextMediaItem() { sessions.send(Cmd.Skip) }
        override fun seekToPrevious() { sessions.send(Cmd.Previous) }
        override fun seekToPreviousMediaItem() { sessions.send(Cmd.Previous) }
    }

    private companion object {
        const val TAG = "UnisonPlayer"
        const val SCHEME = "unison"
        const val TICK_MS = 250L
        const val PAUSED_TOLERANCE_MS = 400L
        const val SOLO_TOLERANCE_MS = 400L
        const val REANCHOR_MS = 150L
        const val SEEK_GRACE_MS = 1_000L
        const val START_GRACE_MS = 700L
        const val MAX_RETRIES = 2
        val EXPIRED_CODES = setOf(403, 410)
    }
}

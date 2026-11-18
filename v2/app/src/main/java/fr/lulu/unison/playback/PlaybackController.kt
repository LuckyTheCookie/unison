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
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import fr.lulu.unison.data.Prefs
import fr.lulu.unison.data.Settings
import fr.lulu.unison.model.PlayAnchor
import fr.lulu.unison.offline.Connectivity
import fr.lulu.unison.offline.Downloads
import fr.lulu.unison.offline.OfflineSkipper
import fr.lulu.unison.model.QueueItem
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.session.SessionManager
import fr.lulu.unison.youtube.ChunkedDataSource
import fr.lulu.unison.youtube.StreamResolver
import fr.lulu.unison.youtube.StreamUnavailableException
import fr.lulu.unison.youtube.YtParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
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
    private val databaseProvider: DatabaseProvider,
    private val downloads: Downloads,
    private val connectivity: Connectivity,
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

    /** Titre que joue ExoPlayer en ce moment (le premier de sa liste, ou le suivant après un enchaînement). */
    private var loaded: QueueItem? = null
    /** Titres présents dans la liste d'ExoPlayer, par uid : le titre en cours et, s'il est prêt, le suivant. */
    private val known = HashMap<String, QueueItem>()
    /** Dernière ancre appliquée : seule une ancre qui change peut faire sauter la tête de lecture en solo. */
    private var lastAnchor: PlayAnchor? = null
    /** À plusieurs, le lecteur s'arrête à la fin de chaque titre et attend le départ commun du suivant. */
    private var finishedUid: String? = null
    private var prefetchJob: Job? = null
    private var prefetchedUid: String? = null
    private var prefetchingUid: String? = null
    private var prefetchRetryAt = 0L
    /** Titres dont la résolution a échoué : ils ne sont pas préparés à l'avance. */
    private val unplayable = HashSet<String>()
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
        // Réseau perdu ou retrouvé : le titre préparé à l'avance doit rester jouable (téléchargé, hors ligne).
        // Le titre en cours, lui, continue sur ce qui est déjà en mémoire.
        scope.launch {
            connectivity.online.drop(1).collect { if (loaded != null) prepareNext(Gapless.upcoming(sessions.snapshot.value)) }
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
        if (skipIfOffline(s, item)) return
        // À plusieurs, chaque titre se termine en pause : le suivant part à l'heure commune, pas dès que ce téléphone a fini.
        if (exo.pauseAtEndOfMediaItems != s.shared) exo.pauseAtEndOfMediaItems = s.shared
        val moved = place(item, a)
        val anchorChanged = moved || a != lastAnchor
        lastAnchor = a
        follow(a, s.shared, anchorChanged)
        prepareNext(Gapless.upcoming(s))
    }

    /**
     * Hors ligne, la file saute les titres qui ne sont pas téléchargés (c'est l'hôte qui décide de la file).
     * Après un tour complet sans titre jouable, la lecture se met en pause.
     */
    private fun skipIfOffline(s: SessionSnapshot, item: QueueItem): Boolean {
        val decision = offlineSkipper.decide(item.uid, playableHere(item), sessions.isHost, s.queue.size + s.history.size + 1)
        if (decision == OfflineSkipper.Decision.PLAY) return false
        if (decision == OfflineSkipper.Decision.WAIT) return true
        if (exo.playWhenReady) exo.pause()
        _state.update { it.copy(error = OFFLINE_MISSING, buffering = false) }
        if (decision == OfflineSkipper.Decision.PAUSE) sessions.send(Cmd.Pause) else sessions.local.trackFailed(item.uid)
        return true
    }

    /** Le titre peut se lire maintenant : réseau présent ou titre entièrement téléchargé. */
    private fun playableHere(item: QueueItem) = connectivity.online.value || downloads.isComplete(item.track.id)

    private val offlineSkipper = OfflineSkipper()

    /** Amène le lecteur sur [item] : rien à faire, passage au titre déjà préparé, ou chargement complet. */
    private fun place(item: QueueItem, a: PlayAnchor): Boolean {
        val playlist = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it).mediaId }
        return when (val step = Gapless.step(playlist, exo.currentMediaItemIndex, item.uid)) {
            Gapless.Step.Keep -> {
                if (loaded?.uid != item.uid) select(item)
                false
            }
            is Gapless.Step.Jump -> {
                // Titre suivant déjà en mémoire (« suivant », ou départ commun à plusieurs) : pas d'arrêt ni de vidage.
                Log.d(TAG, "passage au titre préparé")
                startJob?.cancel(); startJob = null
                select(item)
                exo.seekTo(step.index, startPosition(a))
                true
            }
            Gapless.Step.Load -> { load(item, a); true }
        }
    }

    /** Position de départ d'une ancre : celle prévue pour un départ programmé, sinon celle du moment. */
    private fun startPosition(a: PlayAnchor): Long {
        val now = sessions.hostNow()
        return (if (a.hostTimeMs > now) a.positionMs else a.positionAt(now)).coerceAtLeast(0)
    }

    private fun select(item: QueueItem) {
        loaded = item
        known[item.uid] = item
        finishedUid = null
        retries = 0
        _state.update { it.copy(loadedUid = item.uid, error = null) }
        applyVolume()
    }

    private fun unload() {
        startJob?.cancel(); startJob = null
        prefetchJob?.cancel(); prefetchJob = null; prefetchedUid = null
        loaded = null
        lastAnchor = null
        finishedUid = null
        known.clear()
        published = null
        exo.stop()
        exo.clearMediaItems()
        _state.value = State()
    }

    private fun load(item: QueueItem, a: PlayAnchor) {
        startJob?.cancel(); startJob = null
        Log.d(TAG, "chargement complet")
        known.clear()
        nudging = false
        exo.playWhenReady = false
        exo.setPlaybackParameters(PlaybackParameters.DEFAULT)
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.setMediaItems(listOf(mediaItem(item)), 0, startPosition(a))
        exo.prepare()
        select(item)
        _state.value = State(loadedUid = item.uid, buffering = true)
    }

    private fun mediaItem(item: QueueItem): MediaItem {
        val t = item.track
        return MediaItem.Builder()
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
    }

    /**
     * Garde dans la liste d'ExoPlayer le titre en cours suivi de [next], et rien d'autre : ExoPlayer le prépare
     * pendant que le titre en cours joue, et passe de l'un à l'autre sans recréer la sortie audio.
     */
    private fun prepareNext(next: QueueItem?) {
        val current = loaded ?: return
        val index = exo.currentMediaItemIndex
        if (index > 0) exo.removeMediaItems(0, index) // titre précédent, déjà enchaîné
        val repeatOne = next?.uid == current.uid
        val playable = next != null && playableHere(next)
        val wanted = next?.takeIf { !repeatOne && playable && it.track.id !in unplayable }
        val mode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (exo.repeatMode != mode) exo.repeatMode = mode
        val count = exo.mediaItemCount
        val present = if (count > 1) exo.getMediaItemAt(1).mediaId else null
        if (present != wanted?.uid || count > 2) {
            if (count > 1) exo.removeMediaItems(1, count)
            known.keys.retainAll(setOf(current.uid))
            // Le suivant n'entre dans la liste qu'une fois son flux trouvé : un titre introuvable ne doit pas
            // faire échouer celui qui joue.
            if (wanted != null && prefetchedUid == wanted.uid) {
                known[wanted.uid] = wanted
                exo.addMediaItem(mediaItem(wanted))
            }
        }
        if (wanted != null && prefetchedUid != wanted.uid) prefetch(wanted)
    }

    /**
     * Prépare le titre suivant hors du fil de lecture : adresse du flux demandée à YouTube, puis début du fichier
     * mis en cache. Ainsi l'ouverture du suivant (enchaînement ou « suivant ») ne bloque plus sur le réseau.
     */
    private fun prefetch(next: QueueItem) {
        if (prefetchingUid == next.uid && (prefetchJob?.isActive == true || SystemClock.elapsedRealtime() < prefetchRetryAt)) return
        prefetchJob?.cancel()
        prefetchingUid = next.uid
        val id = next.track.id
        if (downloads.isComplete(id)) {
            // Déjà sur le téléphone : rien à préparer sur le réseau.
            loudness[id] = downloads.loudnessOf(id)
            prefetchedUid = next.uid
            prefetchJob = null
            scope.launch { if (loaded != null) prepareNext(Gapless.upcoming(sessions.snapshot.value)) }
            return
        }
        prefetchJob = scope.launch {
            val stream = runCatching { withContext(Dispatchers.IO) { streams.resolve(id) } }
                .onFailure {
                    if (it is CancellationException) return@onFailure
                    if (it is StreamUnavailableException) unplayable += id
                    // Hors ligne : on ne réessaie pas à chaque changement de la session.
                    prefetchRetryAt = SystemClock.elapsedRealtime() + PREFETCH_RETRY_MS
                }
                .getOrNull() ?: return@launch
            loudness[id] = stream.loudnessDb
            prefetchedUid = next.uid
            // La session a pu bouger pendant la résolution : on repart de son état actuel.
            if (loaded != null) prepareNext(Gapless.upcoming(sessions.snapshot.value))
            runCatching {
                runInterruptible(Dispatchers.IO) {
                    val spec = DataSpec.Builder()
                        .setUri(Uri.parse(stream.url))
                        .setKey(cacheKey(id, stream.itag))
                        .setHttpRequestHeaders(stream.headers)
                        .setLength(PREFETCH_BYTES)
                        .build()
                    CacheWriter(cacheFactory.createDataSource(), spec, null, null).cache()
                }
            }.onFailure { if (it !is CancellationException) Log.d(TAG, "pré-chargement interrompu (${it::class.simpleName})") }
        }
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
        // Seul, le lecteur fait foi : un simple changement de file ne doit jamais déplacer la tête de lecture
        // (sinon on saute le début d'un titre encore en chargement).
        val drift = exo.currentPosition - a.positionAt(now)
        if (Gapless.needsJump(shared, anchorChanged, drift, SOLO_TOLERANCE_MS, SyncPolicy.NUDGE_BAND_MS)) jumpTo(a)
        // Titre terminé à plusieurs : on attend le départ commun du suivant au lieu de relancer.
        if (!exo.playWhenReady && finishedUid != loaded?.uid) exo.play()
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
        if (SystemClock.elapsedRealtime() < graceUntil) return
        val drift = exo.currentPosition - a.positionAt(sessions.hostNow())

        if (!s.shared) {
            _state.update { it.copy(driftMs = null) }
            // Seul, l'ancre suit le lecteur, y compris pendant une mise en mémoire tampon : sinon elle file
            // en avant et une pause ou un changement de file ramènerait la lecture plus loin que le son entendu.
            val following = exo.isPlaying || (exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING)
            if (following && sessions.isHost && abs(drift) > REANCHOR_MS) sessions.local.reanchor(item.uid, exo.currentPosition)
            return
        }
        if (!exo.isPlaying) return

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
        // Titre téléchargé : lu dans le cache permanent, sans réseau ; sinon cache de lecture puis YouTube.
        val offline = CacheDataSource.Factory().setCache(downloads.cache).setCacheWriteDataSinkFactory(null)
        val cached = DataSource.Factory { OfflineFirstDataSource(offline.createDataSource(), cacheFactory.createDataSource()) }
        // L'adresse réelle du flux n'est demandée à YouTube qu'au moment de lire (elle expire en quelques heures).
        // Pour le titre suivant, elle a déjà été obtenue par [prefetch] : la résolution ci-dessous est immédiate.
        val resolving = ResolvingDataSource.Factory(cached) { spec ->
            if (spec.uri.scheme != SCHEME) return@Factory spec
            val id = spec.uri.lastPathSegment ?: throw StreamUnavailableException("Titre invalide")
            if (downloads.isComplete(id)) {
                main.post { loudness[id] = downloads.loudnessOf(id); applyVolume() }
                return@Factory spec.buildUpon().setKey(Downloads.cacheKey(id)).build()
            }
            val stream = runBlocking { streams.resolve(id) }
            main.post { loudness[id] = stream.loudnessDb; applyVolume() }
            spec.buildUpon()
                .setUri(Uri.parse(stream.url))
                .setKey(cacheKey(id, stream.itag))
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
        player.addAnalyticsListener(diagnostics)
        // Le titre suivant commence à se charger dès que le titre en cours est prêt, pas seulement à sa fin.
        player.preloadConfiguration = ExoPlayer.PreloadConfiguration(PRELOAD_US)
        return player
    }

    // Le cache est créé à la première lecture, hors du fil principal : lire les réglages de façon bloquante
    // sur le fil principal figerait l'app au démarrage.
    private val cacheFactory: CacheDataSource.Factory by lazy {
        val cacheBytes = runBlocking { prefs.settings.first() }.cacheMb * 1024L * 1024L
        val cache = SimpleCache(File(context.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(cacheBytes), databaseProvider)
        CacheDataSource.Factory()
            .setCache(cache)
            // Requêtes bornées : YouTube sert alors à pleine vitesse (le suivant et les sauts se chargent d'un coup).
            .setUpstreamDataSourceFactory(ChunkedDataSource.Factory(OkHttpDataSource.Factory(okHttp)))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun cacheKey(id: String, itag: Int) = "$id.$itag"

    /**
     * Journal de contrôle (adb logcat -s UnisonPlayer) : un enchaînement réussi ne recrée pas la sortie audio
     * et ne provoque ni sous-alimentation ni mise en mémoire tampon.
     */
    private val diagnostics = object : AnalyticsListener {
        private var endedAt = 0L

        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            Log.w(TAG, "sous-alimentation audio (tampon ${bufferSizeMs} ms, ${elapsedSinceLastFeedMs} ms sans données)")
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            val gap = if (endedAt > 0) " ${SystemClock.elapsedRealtime() - endedAt} ms après la fin du titre précédent" else ""
            Log.d(TAG, "sortie audio créée$gap")
            endedAt = 0
        }

        override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            Log.d(TAG, "sortie audio libérée")
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            if (state == Player.STATE_ENDED) endedAt = SystemClock.elapsedRealtime()
            if (state == Player.STATE_BUFFERING) Log.d(TAG, "mise en mémoire tampon")
        }

        override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            Log.d(TAG, "discontinuité ${discontinuityName(reason)} (${oldPosition.positionMs} → ${newPosition.positionMs} ms)")
        }
    }

    private fun discontinuityName(reason: Int) = when (reason) {
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "enchaînement"
        Player.DISCONTINUITY_REASON_SEEK -> "saut"
        Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "ajustement"
        Player.DISCONTINUITY_REASON_REMOVE -> "retrait"
        Player.DISCONTINUITY_REASON_SKIP -> "titre ignoré"
        else -> "interne"
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

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Seuls les enchaînements naturels nous intéressent : les sauts sont demandés par [place].
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            val previous = loaded ?: return
            val next = mediaItem?.mediaId?.let { known[it] } ?: return
            Log.i(TAG, if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) "titre répété sans coupure" else "enchaînement sans coupure")
            select(next)
            // L'hôte fait avancer la session ; l'ancre arrive sur le titre que le lecteur joue déjà.
            if (sessions.isHost) sessions.local.trackEnded(previous.uid)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying, error = if (isPlaying) null else it.error) }
            if (isPlaying) { failures = 0; retries = 0 }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                // À plusieurs : fin du titre, le lecteur attend en pause que l'hôte donne le départ du suivant.
                val uid = loaded?.uid ?: return
                finishedUid = uid
                if (sessions.isHost) sessions.local.trackEnded(uid)
                return
            }
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
            if (!playableHere(item)) {
                // Réseau perdu sur un titre non téléchargé : inutile de réessayer, la file passe aux titres téléchargés.
                _state.update { it.copy(error = OFFLINE_MISSING, buffering = false) }
                if (sessions.isHost) sessions.local.trackFailed(item.uid)
                return
            }
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
        /** Début du titre suivant mis en cache à l'avance (≈ 1 min en qualité élevée). */
        const val PREFETCH_BYTES = 1024L * 1024L
        /** Durée du titre suivant qu'ExoPlayer garde prête pendant le titre en cours. */
        const val PRELOAD_US = 20_000_000L
        const val PREFETCH_RETRY_MS = 15_000L
        const val OFFLINE_MISSING = "Hors ligne : titre non téléchargé"
        val EXPIRED_CODES = setOf(403, 410)
    }
}

/**
 * Lit un titre téléchargé dans le cache permanent (clé « dl: ») et tout le reste par le chemin habituel
 * (cache de lecture, puis réseau). Le choix se fait à l'ouverture, d'après la clé que donne la résolution.
 */
private class OfflineFirstDataSource(private val offline: DataSource, private val online: DataSource) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        offline.addTransferListener(transferListener)
        online.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.key?.startsWith(Downloads.KEY_PREFIX) == true) offline else online
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: throw java.io.IOException("Source fermée")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try { current?.close() } finally { current = null }
    }
}

package fr.lulu.unison

import android.app.Application
import fr.lulu.unison.data.Prefs
import fr.lulu.unison.data.Settings
import androidx.media3.database.StandaloneDatabaseProvider
import fr.lulu.unison.artists.Subscriptions
import fr.lulu.unison.importer.Importer
import fr.lulu.unison.library.Library
import fr.lulu.unison.offline.Connectivity
import fr.lulu.unison.offline.Downloads
import fr.lulu.unison.lyrics.LyricsRepository
import fr.lulu.unison.playback.AutoQueue
import fr.lulu.unison.playback.PlaybackController
import fr.lulu.unison.session.SessionManager
import fr.lulu.unison.source.ImportedSource
import fr.lulu.unison.source.ResolverSettings
import fr.lulu.unison.source.SourceHub
import fr.lulu.unison.source.SourcePolicy
import fr.lulu.unison.source.SourceRepository
import fr.lulu.unison.source.StreamGuard
import fr.lulu.unison.source.SourceResolver
import fr.lulu.unison.source.YouTubeSource
import fr.lulu.unison.update.UpdateManager
import fr.lulu.unison.youtube.MusicCatalog
import fr.lulu.unison.youtube.YouTubeCatalog
import fr.lulu.unison.youtube.YouTubeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    val prefs = Prefs(app)
    val settings: StateFlow<Settings> = prefs.settings.stateIn(scope, SharingStarted.Eagerly, Settings())
    val youtube = YouTubeEngine(
        okHttp = http,
        region = Locale.getDefault().country.ifBlank { "FR" },
        language = Locale.getDefault().language.ifBlank { "fr" },
        quality = { settings.value.quality },
    )
    val catalog: MusicCatalog = YouTubeCatalog(youtube)
    /** Sources importées par l'utilisateur (aucune par défaut). */
    val sources = SourceRepository(File(app.filesDir, "sources.json"))
    /** Garde des flux des sources importées, posée sur le client HTTP du lecteur et des téléchargements. */
    private val streamGuard = StreamGuard()
    val sourceHub = SourceHub(sources, SourcePolicy(debugLocal = BuildConfig.DEBUG), http, streamGuard, "Unison/${BuildConfig.VERSION_NAME}")
    private val mediaHttp: OkHttpClient = http.newBuilder().addNetworkInterceptor(streamGuard.interceptor).build()
    /** Résolution des flux : YouTube Music, ou la source importée choisie dans les réglages. */
    val resolver = SourceResolver(
        youtube = YouTubeSource(youtube, catalog),
        imported = { id -> sourceHub.source(id)?.let { ImportedSource(it, it.config.allowDownloads) } },
        settings = { settings.value.let { ResolverSettings(it.activeSource, it.fallbackToYouTube) } },
        shared = { sessions.snapshot.value.shared },
    )
    val library = Library(app)
    val lyrics = LyricsRepository(http, File(app.filesDir, "offline/lyrics"))
    val sessions = SessionManager(app, prefs)
    /** Base partagée par le cache de lecture et celui des téléchargements. */
    private val mediaDb = StandaloneDatabaseProvider(app)
    val connectivity = Connectivity(app)
    val downloads = Downloads(app, mediaHttp, resolver, library, lyrics, prefs, mediaDb, scope)
    val playback = PlaybackController(app, sessions, resolver, prefs, mediaHttp, mediaDb, downloads, connectivity)
    val updates = UpdateManager(app, http)
    val subscriptions = Subscriptions(library, catalog, scope)
    val importer = Importer(app, library, catalog, scope, followArtists = subscriptions::importArtists)
    private val autoQueue = AutoQueue(sessions, catalog, scope, subscriptions) { settings.value.preferMyArtists }

    fun start() {
        downloads.start()
        playback.start()
        autoQueue.start()
        // Historique : chaque titre réellement lancé sur ce téléphone. Un titre sauté aussitôt (illisible), ou
        // jamais chargé (hors ligne et pas téléchargé), n'y entre pas.
        scope.launch {
            sessions.snapshot.map { it.anchor.item }.distinctUntilChangedBy { it?.uid }.collectLatest { item ->
                if (item == null) return@collectLatest
                delay(HISTORY_DELAY_MS)
                val loaded = playback.state.value.loadedUid == item.uid
                if (loaded && sessions.listenHere.value) scope.launch(Dispatchers.IO) { library.recordPlay(item.track) }
            }
        }
    }

    private companion object {
        /** Temps passé sur un titre avant qu'il compte comme écouté. */
        const val HISTORY_DELAY_MS = 2_000L
    }
}

class UnisonApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
    }
}

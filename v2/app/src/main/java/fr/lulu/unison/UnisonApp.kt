package fr.lulu.unison

import android.app.Application
import fr.lulu.unison.data.Prefs
import fr.lulu.unison.data.Settings
import fr.lulu.unison.library.Library
import fr.lulu.unison.lyrics.LyricsRepository
import fr.lulu.unison.playback.AutoQueue
import fr.lulu.unison.playback.PlaybackController
import fr.lulu.unison.session.SessionManager
import fr.lulu.unison.update.UpdateManager
import fr.lulu.unison.youtube.MusicCatalog
import fr.lulu.unison.youtube.YouTubeCatalog
import fr.lulu.unison.youtube.YouTubeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
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
    val library = Library(app)
    val lyrics = LyricsRepository(http)
    val sessions = SessionManager(app, prefs)
    val playback = PlaybackController(app, sessions, youtube, prefs, http)
    val updates = UpdateManager(app, http)
    private val autoQueue = AutoQueue(sessions, catalog, scope)

    fun start() {
        playback.start()
        autoQueue.start()
        // Historique : chaque titre réellement lancé sur ce téléphone.
        scope.launch {
            sessions.snapshot.map { it.anchor.item }.distinctUntilChangedBy { it?.uid }.collect { item ->
                if (item != null && sessions.listenHere.value) launch(Dispatchers.IO) { library.recordPlay(item.track) }
            }
        }
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

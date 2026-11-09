package fr.lulu.unison

import android.app.Application
import fr.lulu.unison.data.ProfileStore
import fr.lulu.unison.deezer.DeezerApi
import fr.lulu.unison.deezer.DeezerController
import fr.lulu.unison.session.SessionManager

class AppGraph(app: Application) {
    val profiles = ProfileStore(app)
    val deezer = DeezerController(app)
    val api = DeezerApi()
    val bridge = fr.lulu.unison.deezer.DeezerBridge(app)
    val lyrics = fr.lulu.unison.lyrics.LyricsRepository(DeezerApi.defaultClient)
    val sessions = SessionManager(app, profiles, deezer)
    val updates = fr.lulu.unison.update.UpdateManager(app, DeezerApi.defaultClient)
}

class UnisonApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.deezer.refresh()
    }
}

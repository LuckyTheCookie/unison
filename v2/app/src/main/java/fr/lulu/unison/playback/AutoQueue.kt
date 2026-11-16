package fr.lulu.unison.playback

import android.util.Log
import fr.lulu.unison.model.RepeatMode
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.session.SessionManager
import fr.lulu.unison.youtube.MusicCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Radio automatique : quand la file locale arrive au bout, on la prolonge avec des titres proches
 * de celui qui joue. C'est l'hôte qui s'en charge, les invités reçoivent la file comme d'habitude.
 */
class AutoQueue(
    private val sessions: SessionManager,
    private val catalog: MusicCatalog,
    private val scope: CoroutineScope,
) {
    private var lastSeedUid: String? = null

    fun start() {
        scope.launch {
            sessions.local.snapshot.collectLatest { s ->
                val current = s.anchor.item ?: return@collectLatest
                if (!s.autoplay || s.queue.isNotEmpty() || s.repeat != RepeatMode.OFF) return@collectLatest
                if (current.uid == lastSeedUid) return@collectLatest
                lastSeedUid = current.uid
                val known = (s.history.map { it.track.id } + current.track.id).toSet()
                val tracks = runCatching { catalog.radio(current.track.id) }
                    .onFailure { Log.w("UnisonRadio", "radio indisponible (${it::class.simpleName})"); lastSeedUid = null }
                    .getOrNull().orEmpty()
                    .filter { it.id !in known }.take(BATCH)
                if (tracks.isNotEmpty()) sessions.local.send(Cmd.AddTracks(tracks, auto = true))
            }
        }
    }

    private companion object {
        const val BATCH = 20
    }
}

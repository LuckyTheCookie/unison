package fr.lulu.unison.playback

import android.util.Log
import fr.lulu.unison.artists.RadioMix
import fr.lulu.unison.artists.Subscriptions
import fr.lulu.unison.model.RepeatMode
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.model.Track
import fr.lulu.unison.session.SessionManager
import fr.lulu.unison.source.SourceTrackId
import fr.lulu.unison.source.TrackMatcher
import fr.lulu.unison.youtube.SearchFilter
import fr.lulu.unison.youtube.SongItem
import fr.lulu.unison.youtube.MusicCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Radio automatique : quand la file locale arrive au bout, on la prolonge avec des titres proches
 * de celui qui joue, et (si on le souhaite) environ un sur quatre d'un artiste suivi.
 * C'est l'hôte qui s'en charge, les invités reçoivent la file comme d'habitude.
 */
class AutoQueue(
    private val sessions: SessionManager,
    private val catalog: MusicCatalog,
    private val scope: CoroutineScope,
    private val subscriptions: Subscriptions? = null,
    /** « Privilégier mes artistes » (réglages). */
    private val preferMyArtists: () -> Boolean = { false },
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
                val radio = runCatching { catalog.radio(seedFor(current.track)) }
                    .onFailure { Log.w("UnisonRadio", "radio indisponible (${it::class.simpleName})"); lastSeedUid = null }
                    .getOrNull().orEmpty()
                val mine = if (subscriptions != null && preferMyArtists() && radio.isNotEmpty()) {
                    runCatching { subscriptions.radioPicks(current.track, radio, known, BATCH / MINE_EVERY) }.getOrDefault(emptyList())
                } else emptyList()
                val tracks = RadioMix.mix(radio, mine, known, BATCH, MINE_EVERY)
                if (tracks.isNotEmpty()) sessions.local.send(Cmd.AddTracks(tracks, auto = true))
            }
        }
    }

    /**
     * Identifiant YouTube Music d'où partir : le titre lui-même, ou pour un titre d'une source importée,
     * le même enregistrement retrouvé sur YouTube Music.
     */
    private suspend fun seedFor(track: Track): String {
        if (!SourceTrackId.isSource(track.id)) return track.id
        val target = TrackMatcher.Candidate(track.title, track.artist, track.durationMs)
        val found = catalog.search("${track.title} ${track.artist}", SearchFilter.SONGS).filterIsInstance<SongItem>().map { it.track }
        // À défaut du même enregistrement, le premier titre trouvé donne une radio proche.
        return (TrackMatcher.best(target, found) { TrackMatcher.Candidate(it.title, it.artist, it.durationMs) } ?: found.firstOrNull())?.id
            ?: error("Aucun titre YouTube Music pour lancer la radio")
    }

    private companion object {
        const val BATCH = 20
        /** Un titre sur quatre vient d'un artiste suivi. */
        const val MINE_EVERY = 4
    }
}

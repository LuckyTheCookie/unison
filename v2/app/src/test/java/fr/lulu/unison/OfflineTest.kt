package fr.lulu.unison

import fr.lulu.unison.offline.OfflineSkipper
import fr.lulu.unison.offline.OfflineSkipper.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineSkipperTest {
    @Test fun downloadedOrOnlineTracksPlay() {
        val s = OfflineSkipper()
        assertEquals(Decision.PLAY, s.decide("a", playable = true, host = true, round = 3))
    }

    @Test fun missingTracksAreSkippedOnce() {
        val s = OfflineSkipper()
        assertEquals(Decision.SKIP, s.decide("a", playable = false, host = true, round = 3))
        // La session n'a pas encore bougé (même titre) : on ne le saute pas deux fois.
        assertEquals(Decision.WAIT, s.decide("a", playable = false, host = true, round = 3))
        assertEquals(Decision.SKIP, s.decide("b", playable = false, host = true, round = 3))
        // Un titre téléchargé remet le compte à zéro.
        assertEquals(Decision.PLAY, s.decide("c", playable = true, host = true, round = 3))
        assertEquals(Decision.SKIP, s.decide("a", playable = false, host = true, round = 3))
    }

    @Test fun aFullRoundWithoutPlayableTrackPauses() {
        val s = OfflineSkipper()
        // File de trois titres, aucun téléchargé, répétition de la file : on s'arrête après un tour.
        val uids = listOf("a", "b", "c", "a")
        val decisions = uids.map { s.decide(it, playable = false, host = true, round = 3) }
        assertEquals(listOf(Decision.SKIP, Decision.SKIP, Decision.SKIP, Decision.PAUSE), decisions)
        // Après la pause, le compte repart de zéro (l'utilisateur relance, la file reprend son tour).
        assertEquals(Decision.SKIP, s.decide("b", playable = false, host = true, round = 3))
    }

    @Test fun guestsLeaveTheQueueToTheHost() {
        val s = OfflineSkipper()
        assertEquals(Decision.PLAY, s.decide("a", playable = false, host = false, round = 3))
    }
}

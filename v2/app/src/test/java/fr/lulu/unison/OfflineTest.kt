package fr.lulu.unison

import androidx.media3.exoplayer.offline.Download
import fr.lulu.unison.library.DownloadRow
import fr.lulu.unison.library.DownloadState
import fr.lulu.unison.library.toCols
import fr.lulu.unison.model.Track
import fr.lulu.unison.offline.DownloadStatus
import fr.lulu.unison.offline.Downloads
import fr.lulu.unison.offline.OfflineSkipper
import fr.lulu.unison.offline.OfflineSkipper.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateTest {
    private fun row(state: Int, bytes: Long = 0, total: Long = 0) =
        DownloadRow(Track("a", "Titre", "Artiste").toCols(), state, bytes, total, 0, null, 0)

    @Test fun media3StatesMapToWhatTheListsShow() {
        assertEquals(DownloadState.QUEUED, Downloads.stateOf(Download.STATE_QUEUED, Download.STOP_REASON_NONE))
        // Mis en pause par l'utilisateur : le gestionnaire le garde « en file » avec une raison d'arrêt.
        assertEquals(DownloadState.PAUSED, Downloads.stateOf(Download.STATE_QUEUED, 1))
        assertEquals(DownloadState.PAUSED, Downloads.stateOf(Download.STATE_STOPPED, 1))
        assertEquals(DownloadState.DOWNLOADING, Downloads.stateOf(Download.STATE_DOWNLOADING, Download.STOP_REASON_NONE))
        assertEquals(DownloadState.DONE, Downloads.stateOf(Download.STATE_COMPLETED, Download.STOP_REASON_NONE))
        assertEquals(DownloadState.FAILED, Downloads.stateOf(Download.STATE_FAILED, Download.STOP_REASON_NONE))
        assertNull(Downloads.stateOf(Download.STATE_REMOVING, Download.STOP_REASON_NONE))
        assertNull(Downloads.stateOf(Download.STATE_RESTARTING, Download.STOP_REASON_NONE))
    }

    @Test fun progressPrefersLiveValueThenBytes() {
        assertEquals(1f, DownloadStatus.of(row(DownloadState.DONE), null).progress, 0f)
        assertTrue(DownloadStatus.of(row(DownloadState.DONE), null).done)
        assertEquals(0.4f, DownloadStatus.of(row(DownloadState.DOWNLOADING, 10, 100), 0.4f).progress, 0.0001f)
        assertEquals(0.25f, DownloadStatus.of(row(DownloadState.PAUSED, 25, 100), null).progress, 0.0001f)
        assertEquals(0f, DownloadStatus.of(row(DownloadState.QUEUED), null).progress, 0f)
        // Valeur aberrante (taille annoncée trop petite) : bornée.
        assertEquals(1f, DownloadStatus.of(row(DownloadState.DOWNLOADING, 300, 100), null).progress, 0f)
    }
}

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

package fr.lulu.unison

import androidx.media3.exoplayer.offline.Download
import fr.lulu.unison.library.DownloadRow
import fr.lulu.unison.library.DownloadState
import fr.lulu.unison.library.toCols
import fr.lulu.unison.model.QueueItem
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
    private fun q(uid: String) = QueueItem(uid, Track(uid, "Titre $uid", "Artiste"), "me")
    private val a = q("a"); private val b = q("b"); private val c = q("c"); private val d = q("d")
    /** Hors ligne : seuls « c » et « d » sont téléchargés. */
    private val downloaded = setOf("c", "d")
    private val offline: (QueueItem) -> Boolean = { it.uid in downloaded }

    @Test fun downloadedOrOnlineTracksPlay() {
        val s = OfflineSkipper()
        assertEquals(Decision.Play, s.decide(c, listOf(a), playing = true, host = true, playable = offline))
        assertEquals(Decision.Play, s.decide(a, listOf(b), playing = true, host = true, playable = { true }))
    }

    @Test fun jumpsStraightToTheNextDownloadedTrack() {
        val s = OfflineSkipper()
        // « b » n'est pas téléchargé : on va directement à « c », sans vider la file en sautant un par un.
        assertEquals(Decision.JumpTo("c"), s.decide(a, listOf(b, c, d), playing = true, host = true, playable = offline))
        // La session n'a pas encore bougé (même titre) : on ne recommande rien.
        assertEquals(Decision.Wait, s.decide(a, listOf(b, c, d), playing = true, host = true, playable = offline))
        // Une fois sur un titre jouable, le compte repart de zéro.
        assertEquals(Decision.Play, s.decide(c, listOf(d, b), playing = true, host = true, playable = offline))
        assertEquals(Decision.JumpTo("d"), s.decide(a, listOf(b, d), playing = true, host = true, playable = offline))
    }

    @Test fun nothingDownloadedAheadPausesAndKeepsTheQueue() {
        val s = OfflineSkipper()
        assertEquals(Decision.Stop, s.decide(a, listOf(b), playing = true, host = true, playable = offline))
        // En pause, le titre n'est pas chargé et rien ne part tout seul.
        assertEquals(Decision.Hold, s.decide(a, listOf(b), playing = false, host = true, playable = offline))
        // Un nouvel appui sur lecture refait le choix (toujours rien : nouvelle pause).
        assertEquals(Decision.Stop, s.decide(a, listOf(b), playing = true, host = true, playable = offline))
    }

    @Test fun aPausedMissingTrackNeverStartsAnotherOne() {
        // Lancement de l'app hors ligne sur un titre non téléchargé en pause : pas de lecture surprise.
        assertEquals(Decision.Hold, OfflineSkipper().decide(a, listOf(c), playing = false, host = true, playable = offline))
    }

    @Test fun guestsLeaveTheQueueToTheHost() {
        assertEquals(Decision.Play, OfflineSkipper().decide(a, listOf(c), playing = true, host = false, playable = offline))
    }
}

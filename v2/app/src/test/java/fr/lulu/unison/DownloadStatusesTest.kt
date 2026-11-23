package fr.lulu.unison

import androidx.compose.runtime.snapshots.Snapshot
import fr.lulu.unison.library.DownloadState
import fr.lulu.unison.offline.DownloadStatus
import fr.lulu.unison.ui.DownloadStatuses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStatusesTest {
    private val running = DownloadStatus(DownloadState.QUEUED, 0.4f)
    private val done = DownloadStatus(DownloadState.DONE, 1f)

    @Test
    fun `un titre lu avant la première mise à jour la reçoit`() {
        val s = DownloadStatuses()
        assertNull(s["a"])
        s.update(mapOf("a" to running))
        assertEquals(running, s["a"])
    }

    @Test
    fun `un titre jamais lu prend l'état courant à sa première lecture`() {
        val s = DownloadStatuses()
        s.update(mapOf("b" to done))
        assertEquals(done, s["b"])
    }

    @Test
    fun `seul le titre qui change est signalé`() {
        val s = DownloadStatuses()
        s.update(mapOf("a" to running, "b" to done))
        s["a"]; s["b"]
        // État lu par la ligne du titre « b ».
        val readByB = mutableSetOf<Any>()
        val reader = Snapshot.takeSnapshot(readObserver = { readByB += it })
        try { reader.enter { s["b"] } } finally { reader.dispose() }

        val changed = mutableSetOf<Any>()
        val handle = Snapshot.registerApplyObserver { set, _ -> changed.addAll(set) }
        try {
            s.update(mapOf("a" to done, "b" to done))
            Snapshot.sendApplyNotifications()
        } finally {
            handle.dispose()
        }
        assertTrue(changed.isNotEmpty())
        assertTrue(changed.none { it in readByB })
        assertEquals(done, s["a"])
    }

    @Test
    fun `un titre retiré redevient nul`() {
        val s = DownloadStatuses()
        s.update(mapOf("a" to done))
        assertEquals(done, s["a"])
        s.update(emptyMap())
        assertNull(s["a"])
    }
}

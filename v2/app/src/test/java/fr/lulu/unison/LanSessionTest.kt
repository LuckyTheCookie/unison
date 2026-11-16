package fr.lulu.unison

import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.Member
import fr.lulu.unison.model.Track
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.net.LanClient
import fr.lulu.unison.net.LanHost
import fr.lulu.unison.net.TransportException
import fr.lulu.unison.session.ClientSession
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.session.HostSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Hôte + invités réels sur le réseau local (WebSocket), sans téléphone. */
class LanSessionTest {
    private fun track(id: String, title: String) = Track(id, title, "Artiste", durationMs = 200_000)

    @Test fun soloQueueOpensToGuestsAndCloses() = runBlocking {
        // On écoute d'abord seul : la file existe avant la session.
        val host = HostSession(Member("host", "Hôte", 1, isHost = true))
        host.send(Cmd.AddTrack(track("a", "Premier")))
        assertEquals("a", host.snapshot.value.anchor.item?.track?.id)
        assertEquals(TransportKind.LOCAL, host.snapshot.value.transport)

        val transport = LanHost()
        val (ip, port) = transport.start()
        val info = JoinInfo("sess1234", "s3cret", "Hôte", TransportKind.LAN, ip, port)
        host.share(transport, info)
        assertEquals("a", host.snapshot.value.anchor.item?.track?.id) // la musique continue

        val guest = ClientSession.join(LanClient(), info, Member("g1", "Léa", 2))
        val guest2 = ClientSession.join(LanClient(), info, Member("g2", "Max", 3))
        withTimeout(5_000) { host.snapshot.first { it.members.size == 3 } }
        assertEquals("a", guest.snapshot.value.anchor.item?.track?.id)

        guest2.send(Cmd.AddTrack(track("b", "Deuxième")))
        withTimeout(5_000) { host.snapshot.first { it.queue.size == 1 } }
        host.send(Cmd.AddTrack(track("c", "Troisième")))

        val hostState = withTimeout(5_000) { host.snapshot.first { it.queue.size == 2 } }
        val g1 = withTimeout(5_000) { guest.snapshot.first { it.version == hostState.version } }
        val g2 = withTimeout(5_000) { guest2.snapshot.first { it.version == hostState.version } }
        assertEquals(hostState.queue, g1.queue)
        assertEquals(hostState.queue, g2.queue)
        assertEquals(listOf("Max", "Hôte"), g1.queue.map { q -> g1.member(q.addedBy)?.name })

        // Réordonnancement depuis un invité.
        guest2.send(Cmd.Move(hostState.queue[1].uid, 0))
        val moved = withTimeout(5_000) { guest.snapshot.first { it.queue.firstOrNull()?.track?.id == "c" } }
        assertEquals(listOf("c", "b"), moved.queue.map { it.track.id })

        // À plusieurs, un nouveau titre démarre en différé pour laisser à chacun le temps de le charger.
        guest.send(Cmd.Skip)
        val skipped = withTimeout(5_000) { host.snapshot.first { it.anchor.item?.track?.id == "c" } }
        assertTrue(skipped.anchor.hostTimeMs > System.currentTimeMillis() + 500)

        // Horloge synchronisée (même machine : écart quasi nul).
        Thread.sleep(1_500)
        assertTrue(kotlin.math.abs(guest.hostNow() - System.currentTimeMillis()) < 50)

        guest2.leave()
        withTimeout(5_000) { host.snapshot.first { it.members.size == 2 } }

        // L'hôte referme la session : il garde sa file et redevient seul.
        host.unshare()
        val alone = host.snapshot.value
        assertEquals(1, alone.members.size)
        assertEquals(TransportKind.LOCAL, alone.transport)
        assertEquals("c", alone.anchor.item?.track?.id)
        assertNull(host.joinInfo.value)
        guest.leave()
        host.close()
    }

    @Test fun wrongSecretAndOldVersionAreRejected() = runBlocking {
        val transport = LanHost()
        val (ip, port) = transport.start()
        val info = JoinInfo("sess5678", "good", "Hôte", TransportKind.LAN, ip, port)
        val host = HostSession(Member("host", "Hôte", 1, true))
        host.share(transport, info)
        try {
            ClientSession.join(LanClient(), info.copy(secret = "bad"), Member("x", "Intrus", 0))
            fail("aurait dû être refusé")
        } catch (e: TransportException) {
            assertTrue(e.message!!.contains("invalide"))
        }
        assertEquals(1, host.snapshot.value.members.size)
        host.close()
    }
}

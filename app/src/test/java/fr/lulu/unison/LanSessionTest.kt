package fr.lulu.unison

import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.Member
import fr.lulu.unison.model.SessionSnapshot
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Hôte + invités réels sur le réseau local (WebSocket), sans téléphone. */
class LanSessionTest {
    @Test fun hostAndGuestsShareTheQueue() = runBlocking {
        val transport = LanHost()
        val (ip, port) = transport.start()
        val info = JoinInfo("sess1234", "s3cret", "Hôte", TransportKind.LAN, ip, port)
        val hostMe = Member("host", "Hôte", 1, isHost = true)
        val host = HostSession(hostMe, info, SessionSnapshot("sess1234", "Hôte", TransportKind.LAN), transport)

        val guest = ClientSession.join(LanClient(), info, Member("g1", "Léa", 2))
        val guest2 = ClientSession.join(LanClient(), info, Member("g2", "Max", 3))
        withTimeout(5_000) { host.snapshot.first { it.members.size == 3 } }

        guest.send(Cmd.AddTrack(Track(1, "Premier", "A", durationMs = 200_000)))
        withTimeout(5_000) { host.snapshot.first { it.anchor.item != null } }
        guest2.send(Cmd.AddTrack(Track(2, "Deuxième", "B", durationMs = 200_000)))
        withTimeout(5_000) { host.snapshot.first { it.queue.size == 1 } }
        host.send(Cmd.AddTrack(Track(3, "Troisième", "C", durationMs = 200_000)))

        val hostState = withTimeout(5_000) { host.snapshot.first { it.queue.size == 2 } }
        assertEquals("g1", hostState.anchor.item?.addedBy)
        // Les deux invités reçoivent le même état que l'hôte.
        val g1 = withTimeout(5_000) { guest.snapshot.first { it.version == hostState.version } }
        val g2 = withTimeout(5_000) { guest2.snapshot.first { it.version == hostState.version } }
        assertEquals(hostState.queue, g1.queue)
        assertEquals(hostState.queue, g2.queue)
        assertEquals(listOf("Max", "Hôte"), g1.queue.map { q -> g1.member(q.addedBy)?.name })

        // Réordonnancement depuis un invité.
        guest2.send(Cmd.Move(hostState.queue[1].uid, 0))
        val moved = withTimeout(5_000) { guest.snapshot.first { it.queue.firstOrNull()?.track?.id == 3L } }
        assertEquals(listOf(3L, 2L), moved.queue.map { it.track.id })

        // Horloge synchronisée (même machine : écart quasi nul).
        Thread.sleep(1_500)
        assertTrue(kotlin.math.abs(guest.hostNow() - System.currentTimeMillis()) < 50)

        guest2.leave()
        withTimeout(5_000) { host.snapshot.first { it.members.size == 2 } }
        guest.leave()
        host.leave()
    }

    @Test fun wrongSecretIsRejected() = runBlocking {
        val transport = LanHost()
        val (ip, port) = transport.start()
        val info = JoinInfo("sess5678", "good", "Hôte", TransportKind.LAN, ip, port)
        val host = HostSession(Member("host", "Hôte", 1, true), info, SessionSnapshot("sess5678", "Hôte", TransportKind.LAN), transport)
        try {
            ClientSession.join(LanClient(), info.copy(secret = "bad"), Member("x", "Intrus", 0))
            fail("aurait dû être refusé")
        } catch (e: TransportException) {
            assertTrue(e.message!!.contains("invalide"))
        }
        assertEquals(1, host.snapshot.value.members.size)
        host.leave()
    }
}

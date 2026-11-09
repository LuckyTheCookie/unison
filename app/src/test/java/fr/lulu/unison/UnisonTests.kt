package fr.lulu.unison

import fr.lulu.unison.deezer.DeezerLinkParser
import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.Member
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.model.Track
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.session.Msg
import fr.lulu.unison.session.SessionLogic
import fr.lulu.unison.session.decodeMsg
import fr.lulu.unison.session.encode
import fr.lulu.unison.sync.ClockSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {
    @Test fun directLinks() {
        assertEquals(3135556L, DeezerLinkParser.directTrackId("https://www.deezer.com/track/3135556"))
        assertEquals(3135556L, DeezerLinkParser.directTrackId("https://www.deezer.com/fr/track/3135556?utm=x"))
        assertEquals(42L, DeezerLinkParser.directTrackId("deezer://www.deezer.com/track/42"))
        assertNull(DeezerLinkParser.directTrackId("https://www.deezer.com/fr/album/302127"))
    }

    @Test fun sharedText() {
        val text = "Écoute Harder, Better, Faster, Stronger de Daft Punk sur #deezer https://link.deezer.com/s/31abcXYZ"
        val url = DeezerLinkParser.firstDeezerUrl(text)
        assertEquals("https://link.deezer.com/s/31abcXYZ", url)
        assertTrue(DeezerLinkParser.isShortLink(url!!))
        assertTrue(DeezerLinkParser.isShortLink("https://deezer.page.link/abc"))
        assertTrue(DeezerLinkParser.isShortLink("https://dzr.page.link/abc"))
        assertFalse(DeezerLinkParser.isShortLink("https://example.com/track/1"))
    }

    @Test fun collections() {
        assertEquals(DeezerLinkParser.Kind.Album to 302127L, DeezerLinkParser.directCollection("https://www.deezer.com/fr/album/302127?utm=x"))
        assertEquals(DeezerLinkParser.Kind.Playlist to 123L, DeezerLinkParser.directCollection("Écoute https://www.deezer.com/playlist/123"))
        assertEquals(DeezerLinkParser.Kind.Album to 9L, DeezerLinkParser.directCollection("deezer://www.deezer.com/album/9"))
        assertNull(DeezerLinkParser.directCollection("https://www.deezer.com/fr/track/3135556"))
        assertNull(DeezerLinkParser.directCollection("https://www.deezer.com/artist/27"))
    }

    @Test fun nonTrack() {
        assertTrue(DeezerLinkParser.isNonTrackDeezerUrl("https://www.deezer.com/fr/playlist/123"))
        assertFalse(DeezerLinkParser.isNonTrackDeezerUrl("https://www.deezer.com/fr/track/123"))
    }
}

class JoinInfoTest {
    @Test fun roundTripLan() {
        val info = JoinInfo("abcd2345", "secretsecret1234", "Léa & co", TransportKind.LAN, "192.168.1.20", 41234)
        assertEquals(info, JoinInfo.parse(info.toUri()))
    }

    @Test fun roundTripNearby() {
        val info = JoinInfo("abcd2345", "secretsecret1234", "Max", TransportKind.NEARBY)
        assertEquals(info, JoinInfo.parse(info.toUri()))
    }

    @Test fun rejectsGarbage() {
        assertNull(JoinInfo.parse("https://deezer.com"))
        assertNull(JoinInfo.parse("unison://join?s=a&t=lan"))
    }
}

class LogicTest {
    private var now = 1_000_000L
    private var n = 0
    private val logic = SessionLogic(now = { now }, newUid = { "u${n++}" })
    private fun t(id: Long, d: Long = 180_000) = Track(id, "T$id", "A", durationMs = d)
    private val base = SessionSnapshot("s", "Host", TransportKind.LAN, members = listOf(Member("h", "Host", 0, true)))

    @Test fun firstAddStartsPlaying() {
        val s = logic.apply(base, Cmd.AddTrack(t(1)), "h")
        assertEquals(1L, s.anchor.item?.track?.id)
        assertTrue(s.anchor.playing)
        assertTrue(s.queue.isEmpty())
    }

    @Test fun queueOrderMovePlayNext() {
        var s = logic.apply(base, Cmd.AddTrack(t(1)), "h")
        s = logic.apply(s, Cmd.AddTrack(t(2)), "h")
        s = logic.apply(s, Cmd.AddTrack(t(3)), "g")
        s = logic.apply(s, Cmd.AddTrack(t(4), playNext = true), "g")
        assertEquals(listOf(4L, 2L, 3L), s.queue.map { it.track.id })
        s = logic.apply(s, Cmd.Move(s.queue[2].uid, 0), "h")
        assertEquals(listOf(3L, 4L, 2L), s.queue.map { it.track.id })
        s = logic.apply(s, Cmd.Remove(s.queue[1].uid), "h")
        assertEquals(listOf(3L, 2L), s.queue.map { it.track.id })
    }

    @Test fun addManyOnEmptyStartsFirstThenQueues() {
        val s = logic.apply(base, Cmd.AddTracks(listOf(t(1), t(2), t(3))), "h")
        assertEquals(1L, s.anchor.item?.track?.id)
        assertTrue(s.anchor.playing)
        assertEquals(listOf(2L, 3L), s.queue.map { it.track.id })
    }

    @Test fun addManyAppendsOrPlaysNext() {
        var s = logic.apply(base, Cmd.AddTrack(t(1)), "h")
        s = logic.apply(s, Cmd.AddTrack(t(2)), "h")
        s = logic.apply(s, Cmd.AddTracks(listOf(t(3), t(4))), "g")
        assertEquals(listOf(2L, 3L, 4L), s.queue.map { it.track.id })
        s = logic.apply(s, Cmd.AddTracks(listOf(t(5), t(6)), playNext = true), "g")
        assertEquals(listOf(5L, 6L, 2L, 3L, 4L), s.queue.map { it.track.id })
        assertEquals("g", s.queue.first().addedBy)
    }

    @Test fun addManyIsCappedAndIgnoresEmpty() {
        assertEquals(base, logic.apply(base, Cmd.AddTracks(emptyList()), "h"))
        val s = logic.apply(base, Cmd.AddTracks((1L..300L).map { t(it) }), "h")
        assertEquals(SessionLogic.MAX_BATCH - 1, s.queue.size)
    }

    @Test fun addManyRoundTrips() {
        val msg = Msg.Command(Cmd.AddTracks(listOf(t(1), t(2)), playNext = true))
        assertEquals(msg, decodeMsg(msg.encode()))
    }

    @Test fun advanceAtEnd() {
        var s = logic.apply(base, Cmd.AddTrack(t(1, 10_000)), "h")
        s = logic.apply(s, Cmd.AddTrack(t(2)), "h")
        now += SessionLogic.START_DELAY_MS + 5_000
        assertFalse(logic.isFinished(s))
        now += 4_800
        assertTrue(logic.isFinished(s))
        s = logic.advance(s)
        assertEquals(2L, s.anchor.item?.track?.id)
        assertEquals(1L, s.history.last().track.id)
        s = logic.advance(s)
        assertNull(s.anchor.item)
        assertFalse(s.anchor.playing)
    }

    @Test fun pauseResumeSeekPrevious() {
        var s = logic.apply(base, Cmd.AddTrack(t(1)), "h")
        now += SessionLogic.START_DELAY_MS + 30_000
        s = logic.apply(s, Cmd.Pause, "h")
        assertEquals(30_000L, s.anchor.positionMs)
        now += 60_000
        assertEquals(30_000L, s.anchor.positionAt(now))
        s = logic.apply(s, Cmd.Resume, "h")
        now += 1_000
        assertEquals(31_000L, s.anchor.positionAt(now))
        s = logic.apply(s, Cmd.Seek(120_000), "h")
        assertEquals(120_000L, s.anchor.positionAt(now))
        s = logic.apply(s, Cmd.AddTrack(t(2)), "h")
        s = logic.apply(s, Cmd.Skip, "h")
        assertEquals(2L, s.anchor.item?.track?.id)
        s = logic.apply(s, Cmd.Previous, "h")
        assertEquals(1L, s.anchor.item?.track?.id)
        assertEquals(listOf(2L), s.queue.map { it.track.id })
    }

    @Test fun versionIncrements() {
        val s1 = logic.apply(base, Cmd.AddTrack(t(1)), "h")
        val s2 = logic.apply(s1, Cmd.Remove("absent"), "h")
        assertTrue(s1.version > base.version)
        assertEquals(s1.version, s2.version)
    }
}

class ProtocolTest {
    @Test fun roundTrip() {
        val msgs = listOf(
            Msg.Hello("dev", "Lulu", 0xFF00FF, "k"),
            Msg.Command(Cmd.AddTrack(Track(1, "a", "b"))),
            Msg.Command(Cmd.Skip),
            Msg.Ping(5), Msg.Pong(5, 9), Msg.Bye,
            Msg.State(SessionSnapshot("s", "h", TransportKind.NEARBY)),
        )
        msgs.forEach { assertEquals(it, decodeMsg(it.encode())) }
        assertNotNull(decodeMsg("""{"t":"ping","clientTime":3,"future":true}"""))
        assertNull(decodeMsg("pas du json"))
    }
}

class ClockSyncTest {
    @Test fun picksLowestRtt() {
        val c = ClockSync()
        // Hôte en avance de 500 ms. Échantillon bruité (RTT 300) puis propre (RTT 20).
        c.addSample(t0 = 1_000, hostTime = 1_000 + 500 + 250, t1 = 1_300)
        c.addSample(t0 = 2_000, hostTime = 2_000 + 500 + 10, t1 = 2_020)
        assertEquals(500L, c.offsetMs)
        assertEquals(20L, c.rttMs)
        assertEquals(10_500L, c.hostNow(10_000))
    }
}

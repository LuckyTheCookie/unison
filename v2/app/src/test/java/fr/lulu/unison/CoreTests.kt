package fr.lulu.unison

import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.Member
import fr.lulu.unison.model.QueueItem
import fr.lulu.unison.model.RepeatMode
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.model.Track
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.playback.Gapless
import fr.lulu.unison.playback.SyncAction
import fr.lulu.unison.playback.SyncPolicy
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.session.Msg
import fr.lulu.unison.session.SessionLogic
import fr.lulu.unison.session.decodeMsg
import fr.lulu.unison.session.encode
import fr.lulu.unison.sync.ClockSync
import fr.lulu.unison.youtube.YtLinks
import fr.lulu.unison.youtube.YtParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtLinksTest {
    @Test fun recognisesSharedLinks() {
        assertEquals("khnokW3Mw24", YtLinks.videoId("https://music.youtube.com/watch?v=khnokW3Mw24&si=abc"))
        assertEquals("khnokW3Mw24", YtLinks.videoId("Écoute ça https://youtu.be/khnokW3Mw24?t=3"))
        assertEquals("khnokW3Mw24", YtLinks.videoId("https://www.youtube.com/watch?list=PL1&v=khnokW3Mw24"))
        assertEquals("khnokW3Mw24", YtLinks.videoId("https://youtube.com/shorts/khnokW3Mw24"))
        assertNull(YtLinks.videoId("https://music.youtube.com/playlist?list=PL123"))
        assertNull(YtLinks.videoId("https://www.deezer.com/track/3135556"))
    }

    @Test fun resizesOnlyGoogleArtwork() {
        assertEquals("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj", YtParser.resize("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", 120))
        val video = "https://i.ytimg.com/vi/x/hqdefault.jpg?sqp=abc"
        assertEquals(video, YtParser.resize(video, 120))
    }
}

class JoinInfoTest {
    @Test fun roundTrip() {
        val lan = JoinInfo("abcd2345", "secretsecret1234", "Léa & co", TransportKind.LAN, "192.168.1.20", 41234)
        assertEquals(lan, JoinInfo.parse(lan.toUri()))
        val nearby = JoinInfo("abcd2345", "k", "Max", TransportKind.NEARBY)
        assertEquals(nearby, JoinInfo.parse(nearby.toUri()))
    }

    @Test fun rejectsOldAndBrokenLinks() {
        // Un QR de la v1 (sans numéro de version) n'est pas compatible.
        assertEquals(JoinInfo.Companion.Parsed.Outdated, JoinInfo.inspect("unison://join?s=abc&k=def&n=H&t=lan&h=1.2.3.4&p=1"))
        assertEquals(JoinInfo.Companion.Parsed.Invalid, JoinInfo.inspect("https://example.com"))
        assertEquals(JoinInfo.Companion.Parsed.Invalid, JoinInfo.inspect("unison://join?v=3&s=abc&k=def&t=lan"))
        // Un QR de la 2.0/2.1 (protocole 2, sans l'aléatoire partagé) est refusé proprement.
        assertEquals(JoinInfo.Companion.Parsed.Outdated, JoinInfo.inspect("unison://join?v=2&s=abc&k=def&n=H&t=lan&h=1.2.3.4&p=1"))
        assertTrue(JoinInfo("s", "k", "H", TransportKind.NEARBY).toUri().startsWith("unison://join?v=${JoinInfo.PROTOCOL}&"))
        assertNull(JoinInfo.parse("unison://join?s=abc&k=def&n=H&t=nearby"))
    }
}

class SessionLogicTest {
    private var clock = 1_000_000L
    private var n = 0
    private val logic = SessionLogic(now = { clock }, newUid = { "u${++n}" }, shuffler = { it.reversed() }, random = { it / 2 })
    private fun t(id: String, ms: Long = 180_000) = Track(id, "Titre $id", "Artiste", durationMs = ms)
    private val solo = SessionSnapshot(members = listOf(Member("me", "Moi", 0, true)))
    private val shared = solo.copy(members = solo.members + Member("g", "Léa", 1))
    private fun SessionSnapshot.run(vararg cmds: Cmd, by: String = "me") = cmds.fold(this) { s, c -> logic.apply(s, c, by) }
    private fun SessionSnapshot.ids() = queue.map { it.track.id }

    @Test fun firstTrackStartsImmediatelyWhenAlone() {
        val s = solo.run(Cmd.AddTrack(t("a")), Cmd.AddTrack(t("b")), Cmd.AddTrack(t("c"), playNext = true))
        assertEquals("a", s.anchor.item?.track?.id)
        assertTrue(s.anchor.playing)
        assertEquals(clock, s.anchor.hostTimeMs)
        assertEquals(listOf("c", "b"), s.ids())
    }

    @Test fun startIsDelayedWhenShared() {
        val s = shared.run(Cmd.AddTrack(t("a")))
        assertEquals(clock + SessionLogic.START_DELAY_MS, s.anchor.hostTimeMs)
        assertEquals(0, s.anchor.positionAt(clock + 500))
        assertEquals(1_000, s.anchor.positionAt(clock + SessionLogic.START_DELAY_MS + 1_000))
    }

    @Test fun playTracksReplacesQueue() {
        val s = solo.run(Cmd.AddTrack(t("x")), Cmd.AddTrack(t("y")), Cmd.PlayTracks(listOf(t("a"), t("b"), t("c")), 1))
        assertEquals("b", s.anchor.item?.track?.id)
        assertEquals(listOf("c"), s.ids())
        assertEquals(listOf("x"), s.history.map { it.track.id })
    }

    @Test fun skipPreviousAndHistory() {
        var s = solo.run(Cmd.AddTracks(listOf(t("a"), t("b"), t("c"))), Cmd.Skip)
        assertEquals("b", s.anchor.item?.track?.id)
        clock += 2_000
        s = s.run(Cmd.Previous)
        assertEquals("a", s.anchor.item?.track?.id)
        assertEquals(listOf("b", "c"), s.ids())
        clock += 8_000
        s = s.run(Cmd.Previous) // au-delà de 5 s : on recommence le titre
        assertEquals("a", s.anchor.item?.track?.id)
        assertEquals(0, s.anchor.positionAt(clock))
    }

    @Test fun pauseResumeSeek() {
        var s = solo.run(Cmd.AddTrack(t("a")))
        clock += 10_000
        s = s.run(Cmd.Pause)
        assertFalse(s.anchor.playing)
        clock += 60_000
        assertEquals(10_000, s.anchor.positionAt(clock))
        s = s.run(Cmd.Resume, Cmd.Seek(90_000))
        clock += 1_000
        assertEquals(91_000, s.anchor.positionAt(clock))
    }

    @Test fun playNowKeepsSkippedTracks() {
        val s0 = solo.run(Cmd.AddTracks(listOf(t("a"), t("b"), t("c"), t("d"))))
        val s = s0.run(Cmd.PlayNow(s0.queue[1].uid))
        assertEquals("c", s.anchor.item?.track?.id)
        assertEquals(listOf("d", "b"), s.ids())
    }

    @Test fun moveRemoveShuffleClear() {
        var s = solo.run(Cmd.AddTracks(listOf(t("a"), t("b"), t("c"), t("d"))))
        s = s.run(Cmd.Move(s.queue[2].uid, 0))
        assertEquals(listOf("d", "b", "c"), s.ids())
        s = s.run(Cmd.Remove(s.queue[1].uid))
        assertEquals(listOf("d", "c"), s.ids())
        assertTrue(s.run(Cmd.Clear).queue.isEmpty())
        assertEquals("a", s.run(Cmd.Clear).anchor.item?.track?.id)
    }

    @Test fun shuffleOnThenOffRestoresTheOriginalOrder() {
        var s = solo.run(Cmd.AddTracks(listOf(t("a"), t("b"), t("c"), t("d"), t("e"))), Cmd.SetShuffle(true))
        assertTrue(s.shuffle)
        assertEquals(listOf("e", "d", "c", "b"), s.ids())
        assertEquals("a", s.anchor.item?.track?.id) // le titre en cours continue
        s = s.run(Cmd.Skip)
        assertEquals("e", s.anchor.item?.track?.id)
        s = s.run(Cmd.SetShuffle(false))
        assertFalse(s.shuffle)
        assertTrue(s.queueOrder.isEmpty())
        assertEquals(listOf("b", "c", "d"), s.ids())
        // Sans effet si l'état ne change pas.
        assertEquals(s, s.run(Cmd.SetShuffle(false)))
    }

    @Test fun shuffleOffResumesAfterTheCurrentTrack() {
        var s = solo.run(Cmd.AddTracks(listOf(t("a"), t("b"), t("c"), t("d"), t("e"))), Cmd.SetShuffle(true))
        s = s.run(Cmd.PlayNow(s.queue.first { it.track.id == "c" }.uid))
        assertEquals("c", s.anchor.item?.track?.id)
        // L'ordre d'origine reprend après le titre en cours (d, e), puis ce qui le précédait (b).
        assertEquals(listOf("d", "e", "b"), s.run(Cmd.SetShuffle(false)).ids())
    }

    @Test fun additionsWhileShuffled() {
        var s = solo.run(Cmd.AddTracks(listOf(t("a"), t("b"), t("c"), t("d"), t("e"))), Cmd.SetShuffle(true))
        // « Ajouter à la file » : à une place au hasard ; « lire ensuite » : en tête.
        s = s.run(Cmd.AddTrack(t("x")))
        assertEquals(listOf("e", "d", "x", "c", "b"), s.ids())
        s = s.run(Cmd.AddTrack(t("y"), playNext = true))
        assertEquals(listOf("y", "e", "d", "x", "c", "b"), s.ids())
        // À la désactivation : « lire ensuite » reste en tête, l'ajout passe à la fin.
        assertEquals(listOf("y", "b", "c", "d", "e", "x"), s.run(Cmd.SetShuffle(false)).ids())
    }

    @Test fun shuffleButtonStartsAtRandomAndKeepsTheListOrder() {
        val tracks = listOf(t("a"), t("b"), t("c"), t("d"), t("e"))
        val s = solo.run(Cmd.PlayTracks(tracks, 0, shuffle = true))
        assertTrue(s.shuffle)
        assertEquals("c", s.anchor.item?.track?.id) // random(5) = 2
        assertEquals(listOf("e", "d", "b", "a"), s.ids())
        assertEquals(listOf("d", "e", "a", "b"), s.run(Cmd.SetShuffle(false)).ids())
        // Aléatoire déjà actif : le titre touché joue d'abord, le reste de la liste suit, mélangé.
        val touched = s.run(Cmd.PlayTracks(tracks, 1))
        assertEquals("b", touched.anchor.item?.track?.id)
        assertEquals(listOf("e", "d", "c", "a"), touched.ids())
        // Aléatoire coupé : comportement habituel.
        val plain = s.run(Cmd.SetShuffle(false), Cmd.PlayTracks(tracks, 1))
        assertEquals(listOf("c", "d", "e"), plain.ids())
    }

    @Test fun repeatAllReshufflesEachRound() {
        var s = solo.run(Cmd.AddTracks(listOf(t("a"), t("b"), t("c"))), Cmd.SetRepeat(RepeatMode.ALL), Cmd.SetShuffle(true))
        assertEquals(listOf("c", "b"), s.ids())
        s = s.run(Cmd.Skip, Cmd.Skip)
        assertEquals("b", s.anchor.item?.track?.id)
        assertTrue(s.queue.isEmpty())
        // Le prochain tour n'est pas connu d'avance : rien n'est préparé sans coupure.
        assertNull(Gapless.upcoming(s))
        val looped = logic.ended(s, s.anchor.item!!.uid)
        // Tour remélangé (ici inversé : b, c, a), sans rejouer tout de suite le titre qui vient de finir.
        assertEquals("c", looped.anchor.item?.track?.id)
        assertEquals(listOf("a", "b"), looped.ids())
        assertTrue(looped.history.isEmpty())
        assertTrue(looped.shuffle)
        // L'ordre d'origine reste connu : couper l'aléatoire au tour suivant le retrouve.
        assertEquals(listOf("a", "b"), looped.run(Cmd.SetShuffle(false)).ids())
    }

    @Test fun shuffleOrderStaysBounded() {
        var s = solo.run(Cmd.AddTracks(listOf(t("a"), t("b"), t("c"))), Cmd.SetShuffle(true), Cmd.Clear)
        assertEquals(listOf("a"), s.queueOrder.map { uid -> listOf("u1" to "a", "u2" to "b", "u3" to "c").toMap()[uid] })
        s = s.run(Cmd.SetShuffle(false))
        assertTrue(s.queueOrder.isEmpty())
    }

    @Test fun endOfTrackFollowsRepeatMode() {
        val base = solo.run(Cmd.AddTracks(listOf(t("a"), t("b"))))
        val uid = base.anchor.item!!.uid
        assertEquals("b", logic.ended(base, uid).anchor.item?.track?.id)
        // Une fin signalée pour un titre déjà quitté est ignorée.
        assertEquals(base, logic.ended(base, "autre"))

        val one = base.run(Cmd.SetRepeat(RepeatMode.ONE))
        assertEquals("a", logic.ended(one, uid).anchor.item?.track?.id)
        assertEquals("b", one.run(Cmd.Skip).anchor.item?.track?.id) // « suivant » passe quand même

        val all = base.run(Cmd.SetRepeat(RepeatMode.ALL), Cmd.Skip)
        val looped = logic.ended(all, all.anchor.item!!.uid)
        assertEquals("a", looped.anchor.item?.track?.id)
        assertEquals(listOf("b"), looped.ids())

        val end = base.run(Cmd.Skip)
        val stopped = logic.ended(end, end.anchor.item!!.uid)
        assertNull(stopped.anchor.item)
        assertEquals(listOf("a", "b"), stopped.history.map { it.track.id })
    }

    @Test fun autoTracksAreMarked() {
        val s = solo.run(Cmd.AddTrack(t("a")), Cmd.AddTracks(listOf(t("b")), auto = true))
        assertEquals(QueueItem.AUTO, s.queue.single().addedBy)
    }

    @Test fun overdueOnlyAfterAnnouncedDuration() {
        val s = solo.run(Cmd.AddTrack(t("a", ms = 10_000)))
        clock += 9_000
        assertFalse(logic.isOverdue(s))
        clock += 2_000
        assertTrue(logic.isOverdue(s))
        assertFalse(logic.isOverdue(s.run(Cmd.Pause)))
    }

    @Test fun reanchorOnlyWhenAlone() {
        val s = solo.run(Cmd.AddTrack(t("a")))
        clock += 5_000
        val r = logic.reanchor(s, s.anchor.item!!.uid, 3_200)
        assertEquals(3_200, r.anchor.positionAt(clock))
        val sh = shared.run(Cmd.AddTrack(t("a")))
        assertEquals(sh, logic.reanchor(sh, sh.anchor.item!!.uid, 3_200))
    }
}

class SyncPolicyTest {
    @Test fun smallDriftIsLeftAlone() {
        assertEquals(SyncAction.Hold, SyncPolicy.decide(0, nudging = false))
        assertEquals(SyncAction.Hold, SyncPolicy.decide(-25, nudging = false))
    }

    @Test fun mediumDriftIsNudged() {
        assertEquals(SyncAction.Nudge(SyncPolicy.SLOW), SyncPolicy.decide(80, nudging = false)) // en avance : on ralentit
        assertEquals(SyncAction.Nudge(SyncPolicy.FAST), SyncPolicy.decide(-80, nudging = false))
    }

    @Test fun nudgeContinuesUntilSettled() {
        assertEquals(SyncAction.Nudge(SyncPolicy.SLOW), SyncPolicy.decide(15, nudging = true))
        assertEquals(SyncAction.Hold, SyncPolicy.decide(6, nudging = true))
    }

    @Test fun largeDriftJumps() {
        assertEquals(SyncAction.Jump, SyncPolicy.decide(151, nudging = false))
        assertEquals(SyncAction.Jump, SyncPolicy.decide(-4_000, nudging = true))
    }
}

class ProtocolTest {
    @Test fun messagesRoundTrip() {
        val track = Track("khnokW3Mw24", "Instant Crush", "Daft Punk", "RAM", "https://x/y=w544-h544-l90-rj", 338_000, explicit = true)
        val messages = listOf(
            Msg.Hello("d1", "Léa", 3, "secret", JoinInfo.PROTOCOL),
            Msg.Command(Cmd.PlayTracks(listOf(track), 0)),
            Msg.Command(Cmd.AddTracks(listOf(track), auto = true)),
            Msg.Command(Cmd.SetRepeat(RepeatMode.ONE)),
            Msg.Command(Cmd.SetShuffle(true)),
            Msg.Command(Cmd.PlayTracks(listOf(track), 0, shuffle = true)),
            Msg.State(SessionSnapshot("s", "Hôte", TransportKind.LAN, repeat = RepeatMode.ALL, autoplay = false, shuffle = true, queueOrder = listOf("u1", "u2"))),
            Msg.Ping(42), Msg.Pong(42, 43), Msg.Bye,
        )
        for (m in messages) assertEquals(m, decodeMsg(m.encode()))
        assertNull(decodeMsg("pas du json"))
        // Un Hello de la v1 n'annonce pas de version : il vaut 1 et sera refusé par l'hôte.
        assertEquals(1, (decodeMsg("""{"t":"hello","deviceId":"d","name":"n","color":1,"secret":"s"}""") as Msg.Hello).v)
    }
}

class ClockSyncTest {
    @Test fun keepsTheSampleWithShortestRoundTrip() {
        val c = ClockSync()
        c.addSample(t0 = 1_000, hostTime = 6_100, t1 = 1_200) // aller-retour 200 ms
        c.addSample(t0 = 2_000, hostTime = 7_010, t1 = 2_020) // aller-retour 20 ms : le plus fiable
        assertEquals(5_000, c.offsetMs)
        assertEquals(15_000, c.hostNow(10_000))
    }
}

class GaplessTest {
    private fun t(id: String) = Track(id, "Titre $id", "Artiste")
    private fun q(uid: String) = QueueItem(uid, t(uid), "me")
    private val a = q("a"); private val b = q("b"); private val c = q("c")
    private fun snap(current: QueueItem?, queue: List<QueueItem> = emptyList(), history: List<QueueItem> = emptyList(), repeat: RepeatMode = RepeatMode.OFF) =
        SessionSnapshot(anchor = fr.lulu.unison.model.PlayAnchor(current, 0, 0, true), queue = queue, history = history, repeat = repeat)

    @Test fun upcomingFollowsTheSessionRules() {
        assertEquals(b, Gapless.upcoming(snap(a, listOf(b, c))))
        assertNull(Gapless.upcoming(snap(a)))
        assertNull(Gapless.upcoming(snap(null, listOf(b))))
        assertEquals(a, Gapless.upcoming(snap(a, listOf(b), repeat = RepeatMode.ONE)))
        // Répéter la file : au bout, on repart du premier titre joué.
        assertEquals(c, Gapless.upcoming(snap(a, history = listOf(c, b), repeat = RepeatMode.ALL)))
        assertEquals(a, Gapless.upcoming(snap(a, repeat = RepeatMode.ALL)))
    }

    @Test fun upcomingMatchesWhatTheSessionPlaysNext() {
        // L'enchaînement préparé doit être exactement le titre que la session choisira à la fin.
        val logic = SessionLogic(now = { 0L })
        val cases = listOf(
            snap(a, listOf(b, c)), snap(a), snap(a, listOf(b), repeat = RepeatMode.ONE),
            snap(a, history = listOf(c, b), repeat = RepeatMode.ALL), snap(a, repeat = RepeatMode.ALL),
        )
        for (s in cases) assertEquals(logic.advance(s).anchor.item?.uid, Gapless.upcoming(s)?.uid)
    }

    @Test fun stepReusesThePreparedTrack() {
        assertEquals(Gapless.Step.Keep, Gapless.step(listOf("a", "b"), 0, "a"))
        // Après un enchaînement, le lecteur est déjà sur le suivant : rien à recharger.
        assertEquals(Gapless.Step.Keep, Gapless.step(listOf("a", "b"), 1, "b"))
        assertEquals(Gapless.Step.Jump(1), Gapless.step(listOf("a", "b"), 0, "b"))
        assertEquals(Gapless.Step.Load, Gapless.step(listOf("a", "b"), 0, "c"))
        assertEquals(Gapless.Step.Load, Gapless.step(emptyList(), 0, "a"))
    }

    @Test fun soloNeverJumpsOnAQueueChange() {
        // Seul, titre encore en chargement (2 s de retard sur l'ancre) et la radio ajoute des titres : on ne saute pas.
        assertFalse(Gapless.needsJump(shared = false, anchorChanged = false, driftMs = -2_000, soloToleranceMs = 400, sharedToleranceMs = 150))
        // Un « aller à » demandé déplace bien la lecture.
        assertTrue(Gapless.needsJump(shared = false, anchorChanged = true, driftMs = -60_000, soloToleranceMs = 400, sharedToleranceMs = 150))
        assertFalse(Gapless.needsJump(shared = false, anchorChanged = true, driftMs = 120, soloToleranceMs = 400, sharedToleranceMs = 150))
        // À plusieurs, le lecteur suit l'ancre quoi qu'il arrive.
        assertTrue(Gapless.needsJump(shared = true, anchorChanged = false, driftMs = 300, soloToleranceMs = 400, sharedToleranceMs = 150))
    }
}

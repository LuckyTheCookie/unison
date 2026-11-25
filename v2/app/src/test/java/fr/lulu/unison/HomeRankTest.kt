package fr.lulu.unison

import fr.lulu.unison.library.ArtistRank
import fr.lulu.unison.library.ContextKind
import fr.lulu.unison.library.Frecency
import fr.lulu.unison.library.HistoryRow
import fr.lulu.unison.library.HomeRank
import fr.lulu.unison.library.PlayContext
import fr.lulu.unison.library.PlayContextRow
import fr.lulu.unison.library.RewindItem
import fr.lulu.unison.library.SubscriptionRow
import fr.lulu.unison.library.TrackCols
import fr.lulu.unison.library.toContext
import fr.lulu.unison.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HomeRankTest {
    private val day = 24 * 3_600_000L
    private val now = 100 * day

    private fun row(key: String, kind: String, lastAt: Long, heat: Double, heatAt: Long = lastAt) =
        PlayContextRow(key, kind, key, "", "", lastAt, heat, heatAt)

    private fun playlist(pid: Long, lastAt: Long, heat: Double) = row("p:$pid", ContextKind.PLAYLIST, lastAt, heat)
    private fun album(id: String, lastAt: Long, heat: Double) = row("c:$id", ContextKind.ALBUM, lastAt, heat)

    private fun live(vararg pids: Long) = pids.map { PlayContext.playlist(it, "Playlist $it") }.associateBy { it.key }

    private fun history(id: String, at: Long, heat: Double = 1.0, ctx: String? = null, artist: String = "Artiste") =
        HistoryRow(TrackCols(id, "Titre $id", artist, "", "", 0, false), at, 1, heat, at, ctx)

    @Test fun heatHalvesEveryWeek() {
        assertEquals(4.0, Frecency.at(8.0, now - 7 * day, now), 1e-9)
        assertEquals(2.0, Frecency.at(8.0, now - 14 * day, now), 1e-9)
        assertEquals(0.0, Frecency.at(0.0, 0, now), 0.0)
        assertEquals(5.0, Frecency.bump(8.0, now - 7 * day, now), 1e-9)
    }

    @Test fun aPlaylistPlayedStraightThroughCountsOnce() {
        val ctx = PlayContext.playlist(1, "Soirée")
        var r = Frecency.touch(null, ctx, now)
        assertEquals(1.0, r.heat, 0.0)
        // Vingt titres enchaînés : la date avance, la chaleur non.
        repeat(20) { i -> r = Frecency.touch(r, ctx, now + (i + 1) * 3 * 60_000L) }
        assertEquals(1.0, r.heat, 1e-6)
        assertEquals(now + 60 * 60_000L, r.lastAt)
        // Une nouvelle séance, plus tard : un point de plus.
        r = Frecency.touch(r, ctx, r.lastAt + Frecency.SESSION_GAP_MS)
        assertTrue(r.heat > 1.9)
    }

    @Test fun shortcutsKeepTheLatestAndTheHottestNewestFirst() {
        val rows = listOf(
            playlist(1, now - 1 * 3_600_000L, 1.0), // écoutée à l'instant, une fois
            playlist(2, now - 2 * day, 12.0), // écoutée tout le temps
            playlist(3, now - 3 * day, 6.0),
            playlist(4, now - 30 * day, 1.0), // oubliée
            album("A", now - 5 * 3_600_000L, 1.0),
        )
        val got = HomeRank.shortcuts(rows, live(1, 2, 3, 4), cold = emptyList(), now = now, slots = 4)
        assertEquals(listOf("p:1", "c:A", "p:2", "p:3"), got.map { it.key })
    }

    @Test fun deletedPlaylistsDisappearButUnsavedAlbumsStay() {
        val rows = listOf(playlist(9, now, 3.0), album("B", now - 1, 1.0))
        val got = HomeRank.shortcuts(rows, live(), cold = emptyList(), now = now)
        assertEquals(listOf("c:B"), got.map { it.key })
    }

    @Test fun coldStartFillsWithTheLibrary() {
        val cold = listOf(PlayContext.Favorites, PlayContext.playlist(5, "Import"), PlayContext.playlist(6, "Autre"))
        val got = HomeRank.shortcuts(listOf(playlist(6, now, 1.0)), cold.associateBy { it.key }, cold, now, slots = 3)
        assertEquals(listOf("p:6", "fav", "p:5"), got.map { it.key })
    }

    @Test fun liveDataWinsOverWhatWasStored() {
        val rows = listOf(playlist(1, now, 1.0))
        val renamed = mapOf("p:1" to PlayContext.playlist(1, "Nouveau nom", subtitle = "12 titres"))
        assertEquals("Nouveau nom", HomeRank.shortcuts(rows, renamed, emptyList(), now).single().title)
    }

    @Test fun keepOrderHoldsPlacesAndFillsGaps() {
        val a = PlayContext.playlist(1, "A"); val b = PlayContext.playlist(2, "B"); val c = PlayContext.playlist(3, "C")
        val d = PlayContext.playlist(4, "D")
        assertEquals(listOf(a, b, c), HomeRank.keepOrder(null, listOf(a, b, c)))
        // B vient d'être lancé et passe en tête : il garde sa place à l'écran.
        assertEquals(listOf(a, b, c), HomeRank.keepOrder(listOf("p:1", "p:2", "p:3"), listOf(b, a, c)))
        // C disparaît, D arrive à sa place.
        assertEquals(listOf(a, b, d), HomeRank.keepOrder(listOf("p:1", "p:2", "p:3"), listOf(d, b, a)))
    }

    @Test fun rewindMixesPlacesAndLoneSongsOnly() {
        val rows = listOf(album("A", now - 10, 1.0), playlist(1, now - 30, 1.0), playlist(2, now - 5, 1.0))
        val hist = listOf(
            history("x", now - 1, ctx = "p:2"), // joué depuis une playlist : pas dans « Réécouter »
            history("r", now - 2, ctx = PlayContext.RADIO), // radio : non plus
            history("s", now - 20), // lancé seul
        )
        val got = HomeRank.rewind(rows, live(1, 2), hist, exclude = setOf("p:2"))
        assertEquals(listOf("c:A", "t:s", "p:1"), got.map { it.key })
        assertTrue(got[1] is RewindItem.Song)
    }

    @Test fun seedsPreferWhatIsHotNow() {
        val hist = listOf(
            history("old", now - 60 * day, heat = 40.0), // très écouté, il y a longtemps
            history("a", now - day, heat = 3.0),
            history("b", now - 2 * day, heat = 2.0),
            history("c", now - day, heat = 1.5),
            history("p", now - day, heat = 1.2, ctx = "p:1"),
        )
        val ctx = listOf(playlist(1, now - day, 4.0))
        val fav = Track("f", "Fav", "Artiste", "", "", 0)
        val got = HomeRank.seeds(hist, ctx, listOf(fav), now).map { it.id }
        assertEquals(listOf("a", "b", "c", "p", "f"), got)
    }

    @Test fun artistsAreRankedByWhatYouListenTo() {
        val subs = listOf(
            SubscriptionRow("UC1", "Angèle", "", 3),
            SubscriptionRow("UC2", "Stromae", "", 2),
            SubscriptionRow("UC3", "Orelsan", "", 1),
        )
        val hist = listOf(
            history("1", now, heat = 2.0, artist = "Stromae"),
            history("2", now, heat = 1.0, artist = "Orelsan & Stromae"),
            history("3", now, heat = 0.5, artist = "Orelsan"),
        )
        val scores = ArtistRank.scores(subs, hist, listOf(row("a:UC3", ContextKind.ARTIST, now, 5.0)), now)
        assertEquals(listOf("UC3", "UC2", "UC1"), ArtistRank.order(subs, scores).map { it.browseId })
        // Sans écoute, les plus récemment suivis d'abord, comme avant.
        assertEquals("UC1", ArtistRank.pick(subs, emptyMap(), max = 3, random = Random(1)).first().browseId)
        assertEquals(listOf("UC3", "UC2"), ArtistRank.pick(subs, scores, max = 3, top = 2, random = Random(1)).take(2).map { it.browseId })
    }

    @Test fun storedRowsRebuildTheirContext() {
        val r = Frecency.touch(null, PlayContext.artist("UC9", "Nom", "img"), now)
        assertEquals(PlayContext.artist("UC9", "Nom", "img"), r.toContext())
    }
}

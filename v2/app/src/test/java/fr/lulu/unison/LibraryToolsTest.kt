package fr.lulu.unison

import fr.lulu.unison.library.LibraryEntry
import fr.lulu.unison.library.LibraryMix
import fr.lulu.unison.library.LibrarySearch
import fr.lulu.unison.library.LibrarySource
import fr.lulu.unison.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LibraryToolsTest {
    private fun t(id: String, title: String, artist: String = "Artiste", album: String = "") = Track(id, title, artist, album)

    private val entries = listOf(
        LibraryEntry(t("1", "Été indien", "Joe Dassin"), LibrarySource.Favorite),
        LibraryEntry(t("1", "Été indien", "Joe Dassin"), LibrarySource.Playlist("Soirée")),
        LibraryEntry(t("2", "One More Time", "Daft Punk", "Discovery"), LibrarySource.Playlist("Soirée")),
        LibraryEntry(t("3", "Time", "Pink Floyd"), LibrarySource.History(4)),
        LibraryEntry(t("4", "Harder Better", "Daft Punk", "Discovery"), LibrarySource.Download),
    )

    @Test
    fun `recherche dans une liste, positions d'origine, dans l'ordre, doublons compris`() {
        val list = listOf(
            t("a", "Été indien", "Joe Dassin"),
            t("b", "One More Time", "Daft Punk", "Discovery"),
            t("c", "Time", "Pink Floyd"),
            t("a", "Été indien", "Joe Dassin"),
        )
        assertEquals(listOf(1, 2), LibrarySearch.within("time", list))
        assertEquals(listOf(0, 3), LibrarySearch.within("ete dassin", list))
        assertEquals(listOf(1), LibrarySearch.within("discov", list))
        assertEquals(emptyList<Int>(), LibrarySearch.within("zzz", list))
        assertEquals(listOf(0, 1, 2, 3), LibrarySearch.within("  ", list))
    }

    @Test
    fun `la recherche ignore accents et casse et regroupe les apparitions`() {
        val hits = LibrarySearch.search("ete INDIEN", entries)
        assertEquals(listOf("1"), hits.map { it.track.id })
        assertEquals("Favoris · Soirée", hits.single().where)
    }

    @Test
    fun `un titre qui commence par la recherche passe devant`() {
        val hits = LibrarySearch.search("time", entries)
        assertEquals(listOf("3", "2"), hits.map { it.track.id })
    }

    @Test
    fun `chaque mot doit se trouver quelque part, y compris dans l'album ou la playlist`() {
        assertEquals(setOf("2", "4"), LibrarySearch.search("daft discovery", entries).map { it.track.id }.toSet())
        assertEquals(setOf("1", "2"), LibrarySearch.search("soiree", entries).map { it.track.id }.toSet())
        assertTrue(LibrarySearch.search("daft floyd", entries).isEmpty())
        assertTrue(LibrarySearch.search("   ", entries).isEmpty())
    }

    @Test
    fun `le mix prend chaque titre une fois, sans l'historique, et respecte la limite`() {
        val mix = LibraryMix.build(entries, Random(1))
        // « Time » n'est que dans l'historique : il n'entre pas dans le mix.
        assertEquals(setOf("1", "2", "4"), mix.map { it.id }.toSet())
        assertEquals(mix.map { it.id }.toSet().size, mix.size)
        assertEquals(2, LibraryMix.build(entries, Random(1), size = 2).size)
    }

    @Test
    fun `hors ligne, le mix se limite aux titres donnés`() {
        assertEquals(listOf("4"), LibraryMix.build(entries, Random(3), onlyIds = setOf("4")).map { it.id })
        assertTrue(LibraryMix.build(entries, Random(3), onlyIds = emptySet()).isEmpty())
    }

    @Test
    fun `les titres aimés sortent plus souvent en tête`() {
        val many = (1..40).map { LibraryEntry(t("h$it", "Écoute $it", "A$it"), LibrarySource.Download) } +
            LibraryEntry(t("fav", "Aimé", "Z"), LibrarySource.Favorite) +
            LibraryEntry(t("fav", "Aimé", "Z"), LibrarySource.Playlist("P"))
        val inTop10 = (0 until 300).count { seed -> LibraryMix.build(many, Random(seed)).take(10).any { it.id == "fav" } }
        // Uniforme : environ 10/41 ≈ 24 %. Pondéré ×5 : nettement plus.
        assertTrue("fav en tête $inTop10 fois sur 300", inTop10 > 120)
    }

    @Test
    fun `albums enregistrés dans le mix, historique seul ignoré`() {
        val saved = listOf(LibraryEntry(t("s1", "Digital Love", "Daft Punk"), LibrarySource.Saved("Discovery")))
        assertEquals(listOf("s1"), LibraryMix.build(saved, Random(0)).map { it.id })
        assertTrue(LibraryMix.build(listOf(LibraryEntry(t("h", "Vu", "X"), LibrarySource.History(9))), Random(0)).isEmpty())
        assertEquals(listOf("s1"), LibrarySearch.search("discovery", saved).map { it.track.id })
    }

    @Test
    fun `deux titres du même artiste ne se suivent pas quand c'est évitable`() {
        val tracks = listOf(t("a", "1", "X"), t("b", "2", "X"), t("c", "3", "Y"), t("d", "4", "X"), t("e", "5", "Z"))
        val spread = LibraryMix.spreadArtists(tracks)
        assertEquals(tracks.size, spread.size)
        spread.zipWithNext().forEach { (p, n) -> assertTrue("${p.id} puis ${n.id}", p.artist != n.artist) }
    }
}

class ForYouTest {
    private fun t(id: String, title: String = "T$id", artist: String = "A$id") = Track(id, title, artist)

    @Test
    fun `chaque radio à tour de rôle, sans titres connus ni doublons`() {
        val r1 = listOf(t("known"), t("a"), t("b"), t("c"))
        val r2 = listOf(t("a"), t("x"), t("y"))
        // Même titre et artiste que « a », autre identifiant.
        val r3 = listOf(t("z", "Ta", "Aa"))
        val picks = fr.lulu.unison.library.ForYou.pick(listOf(r1, r2, r3), known = setOf("known"))
        assertEquals(listOf("a", "x", "b", "y", "c"), picks.map { it.id })
        assertEquals(2, fr.lulu.unison.library.ForYou.pick(listOf(r1, r2), setOf("known"), limit = 2).size)
    }
}

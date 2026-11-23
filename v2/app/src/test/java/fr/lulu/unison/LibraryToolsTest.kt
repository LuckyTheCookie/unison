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
    fun `le mix prend chaque titre une fois et respecte la limite`() {
        val mix = LibraryMix.build(entries, Random(1))
        assertEquals(4, mix.size)
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
        val many = (1..40).map { LibraryEntry(t("h$it", "Écoute $it", "A$it"), LibrarySource.History(0)) } +
            LibraryEntry(t("fav", "Aimé", "Z"), LibrarySource.Favorite) +
            LibraryEntry(t("fav", "Aimé", "Z"), LibrarySource.Playlist("P"))
        val inTop10 = (0 until 300).count { seed -> LibraryMix.build(many, Random(seed)).take(10).any { it.id == "fav" } }
        // Uniforme : environ 10/41 ≈ 24 %. Pondéré ×5 : nettement plus.
        assertTrue("fav en tête $inTop10 fois sur 300", inTop10 > 150)
    }

    @Test
    fun `deux titres du même artiste ne se suivent pas quand c'est évitable`() {
        val tracks = listOf(t("a", "1", "X"), t("b", "2", "X"), t("c", "3", "Y"), t("d", "4", "X"), t("e", "5", "Z"))
        val spread = LibraryMix.spreadArtists(tracks)
        assertEquals(tracks.size, spread.size)
        spread.zipWithNext().forEach { (p, n) -> assertTrue("${p.id} puis ${n.id}", p.artist != n.artist) }
    }
}

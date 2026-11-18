package fr.lulu.unison

import fr.lulu.unison.artists.RadioMix
import fr.lulu.unison.importer.ImportMatch
import fr.lulu.unison.model.Track
import fr.lulu.unison.youtube.ArtistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioMixTest {
    private fun t(id: String, artist: String = "Radio") = Track(id, "Titre $id", artist)
    private val radio = (1..30).map { t("r$it") }
    private val mine = (1..10).map { t("m$it", "Suivi") }

    @Test fun aboutOneInFourComesFromFollowedArtists() {
        val out = RadioMix.mix(radio, mine, exclude = emptySet(), batch = 20)
        assertEquals(20, out.size)
        assertEquals(5, out.count { it.artist == "Suivi" })
        // Placés régulièrement : 4e, 8e, 12e…
        assertEquals(listOf(3, 7, 11, 15, 19), out.withIndex().filter { it.value.artist == "Suivi" }.map { it.index })
        assertEquals(out.size, out.map { it.id }.distinct().size)
    }

    @Test fun neverRepeatsHistoryOrRadio() {
        val history = setOf("r1", "m1", "m2")
        val dup = listOf(t("r2", "Suivi")) + mine
        val out = RadioMix.mix(radio, dup, exclude = history, batch = 20)
        assertTrue(out.none { it.id in history })
        assertEquals(out.size, out.map { it.id }.distinct().size)
    }

    @Test fun withoutFollowedArtistsItIsPlainRadio() {
        assertEquals(radio.take(20), RadioMix.mix(radio, emptyList(), emptySet(), 20))
        // Radio courte : on complète avec les artistes suivis.
        val out = RadioMix.mix(radio.take(2), mine, emptySet(), 20)
        assertEquals(7, out.size) // 2 de la radio + 5 (le quart de 20) des artistes suivis
    }

    @Test fun interleaveTakesOneFromEach() {
        assertEquals(listOf(1, 10, 2, 20, 3), RadioMix.interleave(listOf(listOf(1, 2, 3), listOf(10, 20))))
    }
}

class ArtistMatchTest {
    private fun a(id: String, name: String) = ArtistItem(id, name, "Artiste", "")

    @Test fun findsTheImportedArtist() {
        val results = listOf(a("x", "Thea Gilmore"), a("ok", "Théa"), a("y", "Theatre of Tragedy"))
        assertEquals("ok", ImportMatch.bestArtist("THÉA", results)?.browseId)
        assertEquals("g", ImportMatch.bestArtist("GASPXR", listOf(a("g", "gaspxr"), a("z", "Gasp")))?.browseId)
        assertNull(ImportMatch.bestArtist("Grommet", listOf(a("z", "Gromit Fan Club Orchestra"))))
    }
}

/** Artistes d'un import retrouvés « en vrai » : UNISON_LIVE=1 (UNISON_IMPORT_CSV pour un fichier complet). */
class LiveArtistImportTest {
    @Test fun findsImportedArtists() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("UNISON_LIVE") == "1")
        val path = System.getenv("UNISON_IMPORT_CSV")?.takeIf { it.isNotBlank() }
        val text = if (path != null) java.io.File(path).readText() else javaClass.classLoader!!.getResource("csv/deezer_tunemymusic.csv")!!.readText()
        val artists = fr.lulu.unison.importer.LibraryCsv.plan(text).artists
        val catalog = fr.lulu.unison.youtube.YouTubeCatalog(fr.lulu.unison.youtube.YouTubeEngine(okhttp3.OkHttpClient()))
        var found = 0
        var failed = 0
        for (a in artists) {
            kotlinx.coroutines.delay(400)
            val results = runCatching { catalog.search(a.name, fr.lulu.unison.youtube.SearchFilter.ARTISTS).filterIsInstance<ArtistItem>() }
                .onFailure { failed++; println("  artiste : ${a.name} → échec réseau (${it::class.simpleName})") }
                .getOrNull() ?: continue
            val best = ImportMatch.bestArtist(a.name, results)
            if (best != null) found++
            println("  artiste : ${a.name} → ${best?.title} (${best?.browseId})")
        }
        println("Artistes : $found/${artists.size} ($failed échecs réseau)")
        assertTrue(found >= (artists.size - failed) * 0.7)
    }
}

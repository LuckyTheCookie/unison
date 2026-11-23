package fr.lulu.unison

import fr.lulu.unison.lyrics.LyricsQuery
import fr.lulu.unison.lyrics.LyricsRepository
import fr.lulu.unison.model.Track
import fr.lulu.unison.youtube.YtParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LyricsQueryTest {
    private fun title(t: String, a: String) = LyricsQuery.title(Track("x", t, a))

    @Test
    fun `titres YouTube nettoyés pour les services de paroles`() {
        assertEquals("Animal", title("KATSEYE (캣츠아이) \"Animal\" Official MV", "KATSEYE"))
        assertEquals("One More Time", title("Daft Punk - One More Time (Official Video)", "Daft Punk"))
        assertEquals("One More Time", title("One More Time (Radio Edit)", "Daft Punk"))
        assertEquals("Get Lucky", title("Get Lucky (feat. Pharrell Williams)", "Daft Punk"))
        assertEquals("Hitachi", title("Hitachi", "ADÉLA"))
        assertEquals("Blinding Lights", title("Blinding Lights [Lyrics]", "The Weeknd"))
        // Les versions qui changent le chant restent.
        assertEquals("Animal (Remix)", title("Animal (Remix)", "KATSEYE"))
        assertEquals("KATSEYE", LyricsQuery.artist(Track("x", "t", "KATSEYE (캣츠아이)")))
        assertEquals("", LyricsQuery.artist(Track("x", "t", "1,8 M lectures")))
    }

    @Test
    fun `un compteur n'est pas un artiste`() {
        listOf("1,8 M lectures", "859 M plays", "1,2 Md lectures", "12 k vues", "4 M lectures", "3.4M views").forEach {
            assertTrue(it, YtParser.isCount(it))
        }
        listOf("Daft Punk", "M83", "50 Cent", "Lectures", "2Pac").forEach { assertFalse(it, YtParser.isCount(it)) }
    }

    /** En vrai, sur le réseau : UNISON_LIVE=1 ./gradlew testDebugUnitTest --tests '*LyricsQueryTest*' */
    @Test
    fun live() = runBlocking {
        assumeTrue(System.getenv("UNISON_LIVE") == "1")
        val repo = LyricsRepository(OkHttpClient())
        listOf(
            Track("a", "Hitachi", "ADÉLA"),
            Track("b", "KATSEYE (캣츠아이) \"Animal\" Official MV", "KATSEYE", durationMs = 190_000),
            Track("c", "One More Time (Radio Edit)", "Daft Punk", durationMs = 321_000),
            Track("d", "Taste", "Sabrina Carpenter", durationMs = 157_000),
            Track("e", "Marijuana", "ADÉLA", durationMs = 0),
        ).forEach { t ->
            val start = System.currentTimeMillis()
            val l = repo.lyricsFor(t)
            println("${t.title} → ${l?.source} word=${l?.wordSynced} synced=${l?.synced} lines=${l?.lines?.size} en ${System.currentTimeMillis() - start} ms")
        }
    }
}

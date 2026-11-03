package fr.lulu.unison

import fr.lulu.unison.deezer.DeezerApi
import fr.lulu.unison.lyrics.Lrc
import fr.lulu.unison.lyrics.LyricsRepository
import fr.lulu.unison.lyrics.LrclibProvider
import fr.lulu.unison.lyrics.NeteaseProvider
import fr.lulu.unison.lyrics.normTitle
import fr.lulu.unison.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {
    @Test fun parsesTimestampsAndOffsets() {
        val lines = Lrc.parse(
            """
            [ar:Daft Punk]
            [offset:+100]
            [00:30.75] One more time
            [00:33.18]
            [01:04.9][02:10.123] We're gonna <01:05.20>celebrate
            """.trimIndent()
        )
        assertEquals(listOf(30_650L, 33_080L, 64_800L, 130_023L), lines.map { it.timeMs })
        assertEquals("One more time", lines[0].text)
        assertEquals("", lines[1].text)
        assertEquals("We're gonna celebrate", lines[2].text)
    }

    @Test fun indexAt() {
        val l = fr.lulu.unison.lyrics.Lyrics(Lrc.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c"), true, "t")
        assertEquals(-1, l.indexAt(500))
        assertEquals(0, l.indexAt(1_000))
        assertEquals(1, l.indexAt(8_999))
        assertEquals(2, l.indexAt(60_000))
    }

    @Test fun credits() {
        assertTrue(Lrc.isCredit("作曲 : Pharrell Williams"))
        assertTrue(Lrc.isCredit("吉他：Nile Rodgers"))
        assertFalse(Lrc.isCredit("We're up all night: to get lucky"))
    }

    @Test fun titles() {
        assertEquals(normTitle("Get Lucky"), normTitle("Get Lucky (Radio Edit - feat. Pharrell Williams)"))
        assertEquals(normTitle("One More Time"), normTitle("One More Time - Remastered 2021"))
        assertEquals("cafedelmar", normTitle("Café del Mar"))
    }
}

/** Appels réels aux fournisseurs (nécessite Internet). */
class LyricsProvidersLiveTest {
    private val track = Track(3135556, "Harder, Better, Faster, Stronger", "Daft Punk", "Discovery", durationMs = 224_000)
    private val getLucky = Track(67238735, "Get Lucky", "Daft Punk", "Random Access Memories", durationMs = 369_000)

    @Test fun lrclib() = runBlocking {
        val l = LrclibProvider(DeezerApi.defaultClient).find(track)
        assertNotNull(l); assertTrue(l!!.synced); assertTrue(l.lines.size > 10)
    }

    @Test fun netease() = runBlocking {
        val l = NeteaseProvider(DeezerApi.defaultClient).find(getLucky)
        assertNotNull(l); assertTrue(l!!.synced)
        assertTrue(l.lines.none { Lrc.isCredit(it.text) })
        assertTrue(l.lines.any { it.text.contains("lucky", ignoreCase = true) })
    }

    @Test fun repositoryCaches() = runBlocking {
        val repo = LyricsRepository(DeezerApi.defaultClient)
        val first = repo.lyricsFor(getLucky)
        assertNotNull(first)
        assertTrue(first === repo.lyricsFor(getLucky))
    }
}

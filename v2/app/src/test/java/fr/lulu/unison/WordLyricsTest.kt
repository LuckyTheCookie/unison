package fr.lulu.unison

import fr.lulu.unison.lyrics.LyricsPlusProvider
import fr.lulu.unison.lyrics.Ttml
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordLyricsTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource("lyrics/$name")!!.readText()

    @Test
    fun `les temps TTML sous toutes leurs formes`() {
        assertEquals(30_437L, Ttml.time("30.437"))
        assertEquals(63_554L, Ttml.time("1:03.554"))
        assertEquals(3_723_400L, Ttml.time("01:02:03.4"))
        assertEquals(12_300L, Ttml.time("12.3s"))
        assertEquals(null, Ttml.time(""))
    }

    @Test
    fun `TTML d'Apple Music de BetterLyrics, minuté au mot`() {
        val ttml = Json.parseToJsonElement(resource("betterlyrics-one-more-time.json")).jsonObject["ttml"]!!.jsonPrimitive.content
        val lines = Ttml.parse(ttml)
        assertTrue(lines.size > 20)
        val first = lines.first()
        assertEquals(30_437L, first.timeMs)
        assertEquals("One more time", first.text)
        val words = assertNotNull(first.words).let { first.words!! }
        assertEquals(listOf("One ", "more ", "time"), words.map { it.text })
        assertEquals(30_949L, words[1].startMs)
        assertEquals(32_126L, words[2].endMs)
        // Le texte de chaque ligne est exactement la suite de ses mots.
        lines.filter { it.words != null }.forEach { l -> assertEquals(l.text, l.words!!.joinToString("") { it.text }) }
    }

    @Test
    fun `chœurs ignorés et syllabes d'un même mot recollées`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
            <p begin="1.0" end="3.0"><span begin="1.0" end="1.4">E</span><span begin="1.4" end="1.9">nough</span> <span begin="2.0" end="3.0">now</span><span ttm:role="x-bg"><span begin="2.1" end="2.5">(oh)</span></span></p>
            </div></body></tt>"""
        val line = Ttml.parse(ttml).single()
        assertEquals("Enough now", line.text)
        assertEquals(listOf("E", "nough ", "now"), line.words!!.map { it.text })
    }

    @Test
    fun `LyricsPlus, minuté à la syllabe`() {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString(LyricsPlusProvider.Response.serializer(), resource("lyricsplus-one-more-time.json"))
        val lines = LyricsPlusProvider.parse(response)
        assertTrue(lines.size > 20)
        assertEquals("One more time", lines.first().text)
        assertEquals(listOf("One ", "more ", "time"), lines.first().words!!.map { it.text })
        assertEquals(31_046L, lines.first().words!![1].startMs)
    }
}

class InterludeTest {
    private fun w(start: Long, end: Long, text: String) = fr.lulu.unison.lyrics.LyricWord(start, end, text)

    @Test
    fun `points dans l'intro et les longs passages instrumentaux, une seule fois`() {
        val lyrics = fr.lulu.unison.lyrics.Lyrics(
            listOf(
                fr.lulu.unison.lyrics.LyricLine(8_000, "Un deux", listOf(w(8_000, 8_500, "Un "), w(8_500, 9_000, "deux"))),
                fr.lulu.unison.lyrics.LyricLine(10_000, "Trois", listOf(w(10_000, 11_000, "Trois"))),
                fr.lulu.unison.lyrics.LyricLine(20_000, "Quatre", listOf(w(20_000, 21_000, "Quatre"))),
            ),
            synced = true, source = "test", wordSynced = true,
        )
        val with = fr.lulu.unison.lyrics.Karaoke.withInterludes(lyrics)
        assertEquals(listOf("", "Un deux", "Trois", "", "Quatre"), with.lines.map { it.text })
        assertEquals(11_250L, with.lines[3].timeMs)
        assertEquals(with, fr.lulu.unison.lyrics.Karaoke.withInterludes(with))
    }
}

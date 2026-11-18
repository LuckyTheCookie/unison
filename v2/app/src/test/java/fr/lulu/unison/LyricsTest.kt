package fr.lulu.unison

import okhttp3.OkHttpClient
import fr.lulu.unison.lyrics.Karaoke
import fr.lulu.unison.lyrics.Lrc
import fr.lulu.unison.lyrics.LyricWord
import fr.lulu.unison.lyrics.Lyrics
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
    private val track = Track("3135556", "Harder, Better, Faster, Stronger", "Daft Punk", "Discovery", durationMs = 224_000)
    private val getLucky = Track("67238735", "Get Lucky", "Daft Punk", "Random Access Memories", durationMs = 369_000)

    @Test fun lrclib() = runBlocking {
        val l = LrclibProvider(OkHttpClient()).find(track)
        assertNotNull(l); assertTrue(l!!.synced); assertTrue(l.lines.size > 10)
    }

    @Test fun netease() = runBlocking {
        val l = NeteaseProvider(OkHttpClient()).find(getLucky)
        assertNotNull(l); assertTrue(l!!.synced)
        assertTrue(l.lines.none { Lrc.isCredit(it.text) })
        assertTrue(l.lines.any { it.text.contains("lucky", ignoreCase = true) })
        // NetEase donne ici le minutage mot à mot (YRC).
        assertTrue(l.wordSynced)
        assertTrue(l.lines.filter { it.text.isNotBlank() }.all { it.words != null })
    }

    @Test fun repositoryCaches() = runBlocking {
        val repo = LyricsRepository(OkHttpClient())
        val first = repo.lyricsFor(getLucky)
        assertNotNull(first)
        assertTrue(first === repo.lyricsFor(getLucky))
    }
}

class KaraokeTest {
    @Test fun parsesEnhancedLrcWords() {
        val line = Lrc.parse("[00:12.00]<00:12.00>Un <00:12.40>mot <00:12.90>chanté<00:14.00>").single()
        assertEquals("Un mot chanté", line.text)
        val words = line.words!!
        assertEquals(listOf("Un ", "mot ", "chanté"), words.map { it.text })
        assertEquals(listOf(12_000L, 12_400L, 12_900L), words.map { it.startMs })
        // La dernière balise, sans texte derrière, marque la fin du dernier mot.
        assertEquals(14_000L, words.last().endMs)
        assertEquals(line.text, words.joinToString("") { it.text })
    }

    @Test fun repeatedEnhancedLineIsShifted() {
        val lines = Lrc.parse("[00:10.00][01:10.00]<00:10.00>Encore <00:10.50>une fois")
        assertEquals(listOf(10_000L, 70_000L), lines.map { it.words!!.first().startMs })
        assertEquals(70_500L, lines[1].words!![1].startMs)
    }

    @Test fun parsesNeteaseYrc() {
        val yrc = """
            {"t":0,"c":[{"tx":"作曲: "},{"tx":"Pharrell Williams"}]}
            [31420,2670](31420,390,0)Like (31810,150,0)the (31960,630,0)legend (32590,150,0)of (32740,120,0)the (32860,1230,0)Phoenix
            [35980,2250](35980,300,0)All (36280,360,0)ends (36640,210,0)with (36850,1380,0)beginnings
        """.trimIndent()
        val lines = Lrc.parseYrc(yrc)
        assertEquals(2, lines.size)
        assertEquals("Like the legend of the Phoenix", lines[0].text)
        assertEquals(31_420L, lines[0].timeMs)
        assertEquals(LyricWord(31_960, 32_590, "legend "), lines[0].words!![2])
        assertEquals("Phoenix", lines[0].words!!.last().text)
    }

    @Test fun breathsDoNotDoubleSpaces() {
        // Cas réel (NetEase, « Billie Jean ») : des « mots » faits d'une espace marquent les respirations.
        val line = Lrc.parseYrc("[63150,3000](63150,180,0)Who (63330,120,0)will (63450,600,0)dance (64050,180,0) (64230,150,0) on (64380,90,0)the (64470,330,0)floor (64800,540,0) (65340,300,0)round").single()
        assertEquals("Who will dance on the floor round", line.text)
        assertEquals(line.text, line.words!!.joinToString("") { it.text })
        // La respiration après « dance » est retirée : le remplissage s'arrête simplement en attendant « on ».
        assertEquals(listOf(63_450L, 64_230L), line.words!!.filter { it.text.trim() in setOf("dance", "on") }.map { it.startMs })
        val lrc = Lrc.parse("[00:01.00]<00:01.00>Un  <00:01.50> <00:02.00>deux<00:03.00>").single()
        assertEquals("Un deux", lrc.text)
        assertEquals(lrc.text, lrc.words!!.joinToString("") { it.text })
    }

    @Test fun progressFillsCharactersInTime() {
        val line = Lrc.parseYrc("[1000,2000](1000,1000,0)ab(2000,1000,0)cd").single()
        assertEquals(0f, Karaoke.progress(line, 500), 0f)
        assertEquals(1f, Karaoke.progress(line, 1_500), 0.001f) // moitié de « ab »
        assertEquals(2f, Karaoke.progress(line, 2_000), 0.001f)
        assertEquals(3f, Karaoke.progress(line, 2_500), 0.001f)
        assertEquals(4f, Karaoke.progress(line, 9_000), 0f)
    }

    @Test fun estimatesWordsUntilTheNextLine() {
        val raw = Lyrics(Lrc.parse("[00:01.00]Hello world\n[00:03.00]Next line here\n[00:05.00]"), synced = true, source = "t")
        val l = Karaoke.complete(raw)
        val first = l.lines[0].words!!
        assertEquals(listOf("Hello ", "world"), first.map { it.text })
        assertEquals(1_000L, first.first().startMs)
        // « Hello » et « world » pèsent autant : le temps est partagé, et la ligne finit avant la suivante.
        assertTrue(kotlin.math.abs((first[0].endMs - first[0].startMs) - (first[1].endMs - first[1].startMs)) <= 2)
        assertTrue(first.last().endMs <= 3_000)
        assertEquals(l.lines[0].text, first.joinToString("") { it.text })
        // Ligne vide : pas de mots.
        assertEquals(null, l.lines[2].words)
    }

    @Test fun estimateDoesNotDragOverInstrumentalBreaks() {
        val l = Karaoke.complete(Lyrics(Lrc.parse("[00:01.00]Oh\n[01:00.00]Back"), synced = true, source = "t"))
        assertTrue(l.lines[0].words!!.last().endMs < 5_000)
    }

    @Test fun ideogramsWeighMoreThanLetters() {
        assertTrue(Karaoke.weight("爱") > Karaoke.weight("a"))
        assertEquals(1f, Karaoke.weight("ab,") - Karaoke.weight("a,"), 0.001f)
    }
}

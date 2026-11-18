package fr.lulu.unison

import fr.lulu.unison.importer.Csv
import fr.lulu.unison.importer.ImportEntry
import fr.lulu.unison.importer.ImportMatch
import fr.lulu.unison.importer.ImportTarget
import fr.lulu.unison.importer.LibraryCsv
import fr.lulu.unison.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvTest {
    @Test fun rfc4180() {
        val rows = Csv.parse("\uFEFFa,b,c\r\n\"x, y\",\"il dit \"\"oui\"\"\",\"deux\nlignes\"\r\n\r\nz,,🎵 émoji\n")
        assertEquals(listOf("a", "b", "c"), rows[0])
        assertEquals(listOf("x, y", "il dit \"oui\"", "deux\nlignes"), rows[1])
        // Ligne vide ignorée, champ vide gardé, émojis intacts.
        assertEquals(listOf("z", "", "🎵 émoji"), rows[2])
        assertEquals(3, rows.size)
    }

    @Test fun lastLineWithoutNewline() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), Csv.parse("a,b\n1,2"))
    }

    @Test fun semicolonSeparatedFile() {
        assertEquals(listOf(listOf("Titre", "Artiste"), listOf("Un, deux", "Moi")), Csv.parse("Titre;Artiste\n\"Un, deux\";Moi\n"))
    }
}

class LibraryCsvTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("csv/$name")!!.readText()

    @Test fun tuneMyMusicDeezerExport() {
        val plan = LibraryCsv.plan(fixture("deezer_tunemymusic.csv"))
        assertEquals(5, plan.favorites)
        assertEquals(mapOf("Liked songs" to 4, "(\u2060ʘ\u2060言\u2060ʘ\u2060╬\u2060)" to 3), plan.playlists)
        assertEquals(listOf("GASPXR", "Grommet", "THÉA"), plan.artists.map { it.name })
        assertEquals(0, plan.skipped)
        val second = plan.songs[1]
        assertEquals("someday, someone", second.title)
        assertEquals("Kenzie Cait", second.artist)
        assertEquals("good luck, goodbye (over nostalgic edition)", second.album)
        assertEquals(ImportTarget.Favorites, second.target)
        assertEquals("someday, someone Kenzie Cait", second.query)
    }

    @Test fun exportifyStyleHeaders() {
        val csv = "Track URI,Track Name,Artist Name(s),Album Name,Duration (ms)\n" +
            "spotify:track:1,Get Lucky,\"Daft Punk, Pharrell Williams\",Random Access Memories,369626\n"
        val plan = LibraryCsv.plan(csv, fallbackPlaylist = "Ma playlist")
        val song = plan.songs.single()
        assertEquals("Get Lucky", song.title)
        assertEquals(369_626L, song.durationMs)
        assertEquals(ImportTarget.Playlist("Ma playlist"), song.target)
    }

    @Test fun unknownFileGivesNothing() {
        assertTrue(LibraryCsv.plan("foo,bar\n1,2\n").isEmpty)
        assertTrue(LibraryCsv.plan("").isEmpty)
    }
}

class ImportMatchTest {
    private fun song(title: String, artist: String, album: String = "", ms: Long = 0) =
        ImportEntry.Song(1, title, artist, album, ms, ImportTarget.Favorites)
    private fun t(id: String, title: String, artist: String, album: String = "", ms: Long = 0) = Track(id, title, artist, album, durationMs = ms)

    @Test fun picksTheRightVersion() {
        val e = song("Closer", "Nine Inch Nails", "The Downward Spiral")
        val results = listOf(
            t("a", "Closer", "The Chainsmokers"),
            t("b", "Closer (Remastered)", "Nine Inch Nails", "The Downward Spiral (Deluxe Edition)"),
            t("c", "Closer to God", "Nine Inch Nails"),
        )
        assertEquals("b", ImportMatch.best(e, results)?.id)
    }

    @Test fun rejectsWrongArtistOrTitle() {
        assertNull(ImportMatch.best(song("Souvenir", "Selena Gomez"), listOf(t("x", "Souvenir", "Orchestral Manoeuvres in the Dark"))))
        assertNull(ImportMatch.best(song("Bodies", "Drowning Pool"), listOf(t("x", "Let the Bodies Hit the Floor Remix Tutorial", "Random"))))
    }

    @Test fun featuringAndAccents() {
        assertNotNull(ImportMatch.best(song("Get Lucky (feat. Pharrell Williams)", "Daft Punk"), listOf(t("x", "Get Lucky", "Daft Punk & Pharrell Williams"))))
        assertNotNull(ImportMatch.best(song("Café", "THÉA"), listOf(t("x", "Cafe", "Thea"))))
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), ImportMatch.artists("Daft Punk feat. Pharrell Williams"))
        assertEquals(listOf("Featherstone"), ImportMatch.artists("Featherstone"))
    }

    @Test fun nonLatinTitlesAreCompared() {
        // Deux titres en coréen différents ne doivent pas se confondre (aucune lettre latine).
        assertNull(ImportMatch.best(song("사랑", "아이유"), listOf(t("x", "이별", "아이유"))))
        assertNotNull(ImportMatch.best(song("사랑", "아이유"), listOf(t("x", "사랑", "IU (아이유)"))))
    }

    @Test fun durationBreaksTies() {
        val e = song("Intro", "The xx", ms = 127_000)
        val results = listOf(t("long", "Intro", "The xx", ms = 300_000), t("ok", "Intro", "The xx", ms = 128_000))
        assertEquals("ok", ImportMatch.best(e, results)?.id)
    }
}

/** Catalogue factice : répond aux recherches de titres d'après une table, et compte les appels. */
private class FakeCatalog(private val answers: Map<String, List<Track>>, private val failing: Set<String> = emptySet()) : fr.lulu.unison.youtube.MusicCatalog {
    val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
    var inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    var maxInFlight = 0

    override suspend fun search(query: String, filter: fr.lulu.unison.youtube.SearchFilter): List<fr.lulu.unison.youtube.YtItem> {
        queries += query
        val n = inFlight.incrementAndGet()
        synchronized(this) { maxInFlight = maxOf(maxInFlight, n) }
        try {
            kotlinx.coroutines.delay(5)
            if (query in failing) throw java.io.IOException("réseau")
            return answers[query].orEmpty().map { fr.lulu.unison.youtube.SongItem(it) }
        } finally { inFlight.decrementAndGet() }
    }
    override suspend fun suggestions(input: String) = emptyList<String>()
    override suspend fun home() = emptyList<fr.lulu.unison.youtube.Section>()
    override suspend fun collection(browseId: String) = error("inutile")
    override suspend fun artist(browseId: String) = error("inutile")
    override suspend fun browse(browseId: String, params: String?) = emptyList<fr.lulu.unison.youtube.Section>()
    override suspend fun radio(videoId: String) = emptyList<Track>()
    override suspend fun track(videoId: String): Track? = null
}

class ImportMatcherTest {
    private fun song(line: Int, title: String, artist: String, target: ImportTarget = ImportTarget.Favorites) = ImportEntry.Song(line, title, artist, "", 0, target)

    @Test fun searchesOncePerSongWithLimitedConcurrency() = kotlinx.coroutines.runBlocking {
        val answers = (1..12).associate { "Titre $it Artiste" to listOf(Track("id$it", "Titre $it", "Artiste")) }
        val catalog = FakeCatalog(answers)
        val entries = (1..12).map { song(it, "Titre $it", "Artiste") } +
            song(13, "Titre 1", "Artiste", ImportTarget.Playlist("P")) // même titre dans une playlist
        val results = fr.lulu.unison.importer.ImportMatcher(catalog, parallelism = 3).matchAll(entries, emptyMap()) { _, _ -> }
        assertEquals(12, catalog.queries.size)
        assertTrue(catalog.maxInFlight <= 3)
        assertEquals(12, results.values.count { it is fr.lulu.unison.importer.MatchResult.Found })
    }

    @Test fun fallsBackToTitleAndKeepsFailuresRetryable() = kotlinx.coroutines.runBlocking {
        val catalog = FakeCatalog(
            mapOf("Cure" to listOf(Track("c", "Cure", "Anna Marx"))),
            failing = setOf("Offline Song Someone"),
        )
        val m = fr.lulu.unison.importer.ImportMatcher(catalog, retryDelayMs = 1)
        // L'artiste du fichier est écrit autrement : la recherche « titre seul » le retrouve.
        assertEquals(fr.lulu.unison.importer.MatchResult.Found(Track("c", "Cure", "Anna Marx")), m.find(song(1, "Cure", "Anna Marx feat. X")))
        assertEquals(fr.lulu.unison.importer.MatchResult.Failed, m.find(song(2, "Offline Song", "Someone")))
        assertEquals(fr.lulu.unison.importer.MatchResult.NotFound, m.find(song(3, "Inconnu", "Personne")))
    }

    @Test fun knownMatchesAreNotSearchedAgain() = kotlinx.coroutines.runBlocking {
        val catalog = FakeCatalog(emptyMap())
        val e = song(1, "Souvenir", "Selena Gomez")
        val results = fr.lulu.unison.importer.ImportMatcher(catalog).matchAll(listOf(e), mapOf(e.key to Track("s", "Souvenir", "Selena Gomez"))) { _, _ -> }
        assertTrue(catalog.queries.isEmpty())
        assertTrue(results[e.key] is fr.lulu.unison.importer.MatchResult.Found)
    }
}

/**
 * Import « en vrai » contre YouTube Music : UNISON_LIVE=1 ./gradlew testDebugUnitTest --tests '*LiveImport*'
 * UNISON_IMPORT_CSV=/chemin/fichier.csv mesure le taux de correspondance sur un fichier complet (lu comme données).
 */
class LiveImportTest {
    @Test fun matchesFixtureOnYouTubeMusic() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("UNISON_LIVE") == "1")
        val path = System.getenv("UNISON_IMPORT_CSV")?.takeIf { it.isNotBlank() }
        val text = if (path != null) java.io.File(path).readText() else javaClass.classLoader!!.getResource("csv/deezer_tunemymusic.csv")!!.readText()
        val plan = LibraryCsv.plan(text)
        val catalog = fr.lulu.unison.youtube.YouTubeCatalog(fr.lulu.unison.youtube.YouTubeEngine(okhttp3.OkHttpClient()))
        val t0 = System.currentTimeMillis()
        val results = fr.lulu.unison.importer.ImportMatcher(catalog).matchAll(plan.songs, emptyMap()) { _, _ -> }
        val unique = plan.songs.distinctBy { it.key }
        val found = unique.count { results[it.key] is fr.lulu.unison.importer.MatchResult.Found }
        val failed = unique.count { results[it.key] is fr.lulu.unison.importer.MatchResult.Failed }
        println("Import : ${plan.songs.size} lignes, ${unique.size} titres distincts, $found retrouvés, $failed échecs réseau, en ${(System.currentTimeMillis() - t0) / 1000} s")
        unique.filter { results[it.key] !is fr.lulu.unison.importer.MatchResult.Found }.take(40).forEach { println("  non trouvé : ${it.title} — ${it.artist}") }
        // Correspondances approchées (titre ou artiste écrits autrement), à vérifier à l'œil.
        unique.mapNotNull { e -> (results[e.key] as? fr.lulu.unison.importer.MatchResult.Found)?.track?.let { e to it } }
            .filter { (e, t) -> fr.lulu.unison.importer.ImportMatch.norm(e.title) != fr.lulu.unison.importer.ImportMatch.norm(t.title) }
            .take(60).forEach { (e, t) -> println("  approché : ${e.title} — ${e.artist}  ⇒  ${t.title} — ${t.artist}") }
        if (path == null) unique.forEach { e -> println("  ${e.title} — ${e.artist} → ${(results[e.key] as? fr.lulu.unison.importer.MatchResult.Found)?.track?.let { "${it.title} — ${it.artist} (${it.id})" }}") }
        assertTrue("trop peu de titres retrouvés : $found/${unique.size}", found >= unique.size * 0.7)
    }
}

class ImportMatchVersionsTest {
    private fun song(title: String, artist: String) = ImportEntry.Song(1, title, artist, "", 0, ImportTarget.Favorites)
    private fun t(id: String, title: String, artist: String) = Track(id, title, artist)

    @Test fun coreTitleMustMatch() {
        // Cas réel : même mention « (HARDTEKK) (Slowed) », mais un autre morceau.
        assertNull(ImportMatch.best(song("MIMIMI (HARDTEKK) (Slowed)", "AvantTEKK"), listOf(t("x", "Neoperreo (HARDTEKK) (Slowed)", "AvantTEKK"))))
    }

    @Test fun frenchGuestMentionIsIgnored() {
        assertEquals(ImportMatch.norm("Kiss Me More (feat. SZA)"), ImportMatch.norm("Kiss Me More (avec SZA)"))
        assertEquals(listOf("Purple Disco Machine", "Kungs"), ImportMatch.artists("Purple Disco Machine et Kungs"))
    }

    @Test fun prefersTheSameVersion() {
        val e = song("BONITO ROUBO (Slowed)", "Scythermane")
        assertEquals("slow", ImportMatch.best(e, listOf(t("orig", "BONITO ROUBO", "Scythermane"), t("slow", "BONITO ROUBO (Slowed)", "Scythermane")))?.id)
        // Sans la bonne version, l'original reste une correspondance acceptable.
        assertEquals("orig", ImportMatch.best(e, listOf(t("orig", "BONITO ROUBO", "Scythermane")))?.id)
    }
}

class ImportMatchAlbumTest {
    @Test fun versionOutweighsAlbum() {
        // Cas réel : l'album du fichier est celui des versions alternatives, mais le titre est l'original.
        val e = ImportEntry.Song(1, "Tell Ur Girlfriend", "Lay Bankz", "Tell Ur Girlfriend (Alternate Versions)", 0, ImportTarget.Favorites)
        val results = listOf(
            Track("slow", "Tell Ur Girlfriend (Slowed)", "Lay Bankz", "Tell Ur Girlfriend (Alternate Versions)"),
            Track("orig", "Tell Ur Girlfriend", "Lay Bankz", "After 7"),
        )
        assertEquals("orig", ImportMatch.best(e, results)?.id)
    }
}

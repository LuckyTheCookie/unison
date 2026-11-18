package fr.lulu.unison

import fr.lulu.unison.artists.RadioMix
import fr.lulu.unison.artists.Subscriptions
import fr.lulu.unison.importer.ImportEntry
import fr.lulu.unison.importer.ImportMatch
import fr.lulu.unison.library.SubscriptionRow
import fr.lulu.unison.library.SubscriptionStore
import fr.lulu.unison.model.Track
import fr.lulu.unison.youtube.AlbumItem
import fr.lulu.unison.youtube.ArtistItem
import fr.lulu.unison.youtube.ArtistPage
import fr.lulu.unison.youtube.MusicCatalog
import fr.lulu.unison.youtube.SearchFilter
import fr.lulu.unison.youtube.Section
import fr.lulu.unison.youtube.SongItem
import fr.lulu.unison.youtube.YtItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
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

/** Artistes suivis gardés en mémoire, avec les mêmes règles que la base (le premier devient le plus récent). */
private class MemoryStore : SubscriptionStore {
    private var clock = 1_000L
    override val subscriptions = MutableStateFlow<List<SubscriptionRow>>(emptyList())
    override suspend fun allSubscriptions() = subscriptions.value.sortedByDescending { it.addedAt }
    override suspend fun subscribe(rows: List<SubscriptionRow>) {
        val t = clock++ * 100
        val known = subscriptions.value.map { it.browseId }.toSet()
        val fresh = rows.distinctBy { it.browseId }.mapIndexed { i, r -> r.copy(addedAt = t - i) }.filter { it.browseId !in known }
        subscriptions.value = subscriptions.value + fresh
    }
    override suspend fun unsubscribe(browseId: String) { subscriptions.value = subscriptions.value.filterNot { it.browseId == browseId } }
}

/** Catalogue factice : artistes trouvables par nom, pages d'artistes (titres populaires et sorties). */
private class ArtistCatalog(
    private val artists: Map<String, List<ArtistItem>>,
    private val pages: Map<String, ArtistPage>,
) : MusicCatalog {
    val searches = java.util.Collections.synchronizedList(mutableListOf<String>())
    val pageLoads = java.util.Collections.synchronizedList(mutableListOf<String>())

    override suspend fun search(query: String, filter: SearchFilter): List<YtItem> {
        searches += query
        return if (filter == SearchFilter.ARTISTS) artists[query].orEmpty() else emptyList()
    }
    override suspend fun artist(browseId: String): ArtistPage {
        pageLoads += browseId
        return pages[browseId] ?: throw java.io.IOException("page indisponible")
    }
    override suspend fun suggestions(input: String) = emptyList<String>()
    override suspend fun home() = emptyList<Section>()
    override suspend fun collection(browseId: String) = error("inutile")
    override suspend fun browse(browseId: String, params: String?) = emptyList<Section>()
    override suspend fun radio(videoId: String) = emptyList<Track>()
    override suspend fun track(videoId: String): Track? = null
}

class SubscriptionsTest {
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    @After fun tearDown() = scope.cancel()

    private fun artist(id: String, name: String) = ArtistItem(id, name, "Artiste", "https://img/$id")
    private fun songs(prefix: String, artist: String, n: Int = 4) = (1..n).map { Track("$prefix$it", "Titre $prefix$it", artist) }
    private fun page(name: String, tracks: List<Track>, releases: List<AlbumItem> = emptyList()) =
        ArtistPage(name, "", "", "", listOf(Section("Titres", tracks.map { SongItem(it) }), Section("Albums", releases)))

    @Test fun importFollowsFoundArtistsOnce() = runBlocking {
        val catalog = ArtistCatalog(
            artists = mapOf(
                "THÉA" to listOf(artist("x", "Thea Gilmore"), artist("thea", "Théa")),
                "Theá" to listOf(artist("thea", "Théa")),
                "GASPXR" to listOf(artist("g", "gaspxr")),
                "Inconnu" to listOf(artist("z", "Quelqu'un d'autre")),
            ),
            pages = emptyMap(),
        )
        val store = MemoryStore()
        val subs = Subscriptions(store, catalog, scope)
        val (followed, missing) = subs.importArtists(
            listOf(ImportEntry.Artist(1, "THÉA"), ImportEntry.Artist(2, "Theá"), ImportEntry.Artist(3, "GASPXR"), ImportEntry.Artist(4, "Inconnu")),
        )
        // « THÉA » et « Theá » ne font qu'un artiste, recherché une seule fois.
        assertEquals(listOf("THÉA", "GASPXR", "Inconnu"), catalog.searches)
        assertEquals(2, followed)
        assertEquals(listOf("Inconnu"), missing)
        assertEquals(listOf("thea", "g"), store.allSubscriptions().map { it.browseId })
        assertEquals("https://img/thea", store.allSubscriptions().first().thumbnail)

        // Un second import ne change ni la liste ni l'ordre.
        subs.importArtists(listOf(ImportEntry.Artist(1, "GASPXR")))
        assertEquals(listOf("thea", "g"), store.allSubscriptions().map { it.browseId })
    }

    @Test fun radioPicksPreferArtistsHeardAndSkipKnownTracks() = runBlocking {
        val store = MemoryStore()
        store.subscribe((1..5).map { SubscriptionRow("a$it", "Artiste $it", "", 0) })
        val pages = (1..5).associate { "a$it" to page("Artiste $it", songs("a$it-", "Artiste $it")) }
        val subs = Subscriptions(store, ArtistCatalog(emptyMap(), pages), scope)
        val current = Track("cur", "En cours", "Artiste 5 & Autre")
        val radio = listOf(Track("r1", "Radio", "Artiste 4"))
        val exclude = setOf("a5-1", "a4-2")
        repeat(10) {
            val picks = subs.radioPicks(current, radio, exclude, count = 5)
            assertEquals(5, picks.size)
            assertTrue(picks.none { it.id in exclude })
            assertEquals(picks.size, picks.map { it.id }.distinct().size)
            // Trois artistes au plus, dont toujours ceux qu'on entend déjà (le titre en cours, la radio).
            val from = picks.map { it.artist }.toSet()
            assertTrue(from.size <= 3)
        }
        val heard = (1..20).flatMap { subs.radioPicks(current, radio, exclude, count = 12) }.map { it.artist }.toSet()
        assertTrue("Artiste 5" in heard && "Artiste 4" in heard)
        assertEquals(emptyList<Track>(), subs.radioPicks(current, radio, exclude, count = 0))
    }

    @Test fun noFollowedArtistMeansNoPicksNorFeed() = runBlocking {
        val subs = Subscriptions(MemoryStore(), ArtistCatalog(emptyMap(), emptyMap()), scope)
        assertEquals(emptyList<Track>(), subs.radioPicks(Track("c", "C", "X"), emptyList(), emptySet(), 5))
        assertTrue(subs.feed().artists.isEmpty())
    }

    @Test fun feedInterleavesArtistsAndKeepsTheirLatestRelease() = runBlocking {
        val store = MemoryStore()
        store.subscribe(listOf(SubscriptionRow("b", "B", "", 0), SubscriptionRow("a", "A", "", 0), SubscriptionRow("down", "Hors ligne", "", 0)))
        val catalog = ArtistCatalog(
            emptyMap(),
            mapOf(
                "a" to page("A", songs("a", "A"), listOf(AlbumItem("a-old", "Vieux", "Album • 2019", ""), AlbumItem("a-new", "Nouveau", "Single • 2025", ""))),
                "b" to page("B", songs("b", "B"), listOf(AlbumItem("b-1", "Premier", "Album", ""))),
            ),
        )
        val subs = Subscriptions(store, catalog, scope)
        val feed = subs.feed()
        assertEquals(3, feed.artists.size)
        // Deux titres par artiste, à tour de rôle ; l'artiste dont la page ne répond pas est simplement absent.
        assertEquals(listOf("b1", "a1", "b2", "a2"), feed.popular.map { it.id })
        assertEquals(setOf("a-new", "b-1"), feed.releases.map { (it as AlbumItem).browseId }.toSet())
        // Les pages sont gardées un moment : un second fil ne les recharge pas.
        val loads = catalog.pageLoads.size
        subs.feed()
        assertEquals(loads + 1, catalog.pageLoads.size) // seule la page en échec est redemandée
    }
}

class LatestReleaseTest {
    private fun a(id: String, subtitle: String) = AlbumItem(id, id, subtitle, "")

    @Test fun picksTheMostRecentYear() {
        assertEquals("s", RadioMix.latest(listOf(a("x", "Album • 2021"), a("s", "Single • 2024"), a("y", "EP • 2023")))?.browseId)
        // Même année, ou aucune année : l'ordre de la page décide.
        assertEquals("x", RadioMix.latest(listOf(a("x", "Album • 2024"), a("s", "Single • 2024")))?.browseId)
        assertEquals("x", RadioMix.latest(listOf(a("x", "Album"), a("s", "Single")))?.browseId)
        assertNull(RadioMix.latest(emptyList()))
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

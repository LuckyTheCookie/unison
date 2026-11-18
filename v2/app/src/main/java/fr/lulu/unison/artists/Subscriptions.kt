package fr.lulu.unison.artists

import android.util.Log
import fr.lulu.unison.importer.ImportEntry
import fr.lulu.unison.importer.ImportMatch
import fr.lulu.unison.library.SubscriptionStore
import fr.lulu.unison.library.SubscriptionRow
import fr.lulu.unison.model.Track
import fr.lulu.unison.youtube.AlbumItem
import fr.lulu.unison.youtube.ArtistItem
import fr.lulu.unison.youtube.MusicCatalog
import fr.lulu.unison.youtube.SearchFilter
import fr.lulu.unison.youtube.SongItem
import fr.lulu.unison.youtube.YtItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** Ce que l'accueil montre des artistes suivis. */
data class ArtistFeed(
    val artists: List<SubscriptionRow>,
    /** Titres populaires des artistes suivis, mélangés. */
    val popular: List<Track>,
    /** Dernière sortie (album ou single) de chaque artiste. */
    val releases: List<YtItem>,
)

/**
 * Artistes suivis : abonnement depuis la page d'un artiste ou un import, titres populaires et sorties
 * pour l'accueil, et titres à glisser dans la radio automatique.
 */
class Subscriptions(
    private val library: SubscriptionStore,
    private val catalog: MusicCatalog,
    scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    val list: Flow<List<SubscriptionRow>> = library.subscriptions
    val ids: StateFlow<Set<String>> = list.map { rows -> rows.map { it.browseId }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private class Cached(val at: Long, val tracks: List<Track>, val releases: List<AlbumItem>)
    private val pages = ConcurrentHashMap<String, Cached>()

    suspend fun subscribe(browseId: String, name: String, thumbnail: String) =
        library.subscribe(listOf(SubscriptionRow(browseId, name, thumbnail, 0)))

    suspend fun unsubscribe(browseId: String) = library.unsubscribe(browseId)

    /**
     * Suit les artistes d'un import, retrouvés par une recherche d'artiste.
     * Renvoie le nombre d'artistes suivis et les noms restés introuvables.
     */
    suspend fun importArtists(entries: List<ImportEntry.Artist>): Pair<Int, List<String>> = coroutineScope {
        if (entries.isEmpty()) return@coroutineScope 0 to emptyList()
        val gate = Semaphore(3)
        val found = entries.distinctBy { ImportMatch.norm(it.name) }.map { e ->
            async {
                gate.withPermit { e to ImportMatch.bestArtist(e.name, searchArtists(e.name)) }
            }
        }.awaitAll()
        val rows = found.mapNotNull { (_, a) -> a?.let { SubscriptionRow(it.browseId, it.title, it.thumbnail, 0) } }
        library.subscribe(rows)
        rows.size to found.filter { it.second == null }.map { it.first.name }
    }

    /** Recherche d'artistes, retentée deux fois : YouTube refuse parfois quelques requêtes d'affilée. */
    private suspend fun searchArtists(name: String): List<ArtistItem> {
        repeat(3) { attempt ->
            try {
                return catalog.search(name, SearchFilter.ARTISTS).filterIsInstance<ArtistItem>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("UnisonArtists", "recherche d'artiste impossible (${e::class.simpleName}), essai ${attempt + 1}")
                delay(2_000L * (attempt + 1))
            }
        }
        return emptyList()
    }

    /** Titres populaires et sorties d'un artiste, gardés une demi-heure. */
    private suspend fun page(browseId: String): Cached {
        pages[browseId]?.takeIf { nowMs() - it.at < CACHE_MS }?.let { return it }
        val page = catalog.artist(browseId)
        val tracks = page.sections.flatMap { it.items }.filterIsInstance<SongItem>().map { it.track }.distinctBy { it.id }
        val releases = page.sections.flatMap { it.items }.filterIsInstance<AlbumItem>()
        return Cached(nowMs(), tracks, releases).also { pages[browseId] = it }
    }

    /** Fil de l'accueil : quelques artistes suivis (les plus récents d'abord, puis au hasard). */
    suspend fun feed(maxArtists: Int = 8): ArtistFeed = coroutineScope {
        val all = library.allSubscriptions()
        if (all.isEmpty()) return@coroutineScope ArtistFeed(emptyList(), emptyList(), emptyList())
        val chosen = (all.take(3) + all.drop(3).shuffled()).take(maxArtists)
        val gate = Semaphore(3)
        val pages = chosen.map { a -> async { gate.withPermit { runCatching { a to page(a.browseId) }.getOrNull() } } }.awaitAll().filterNotNull()
        // Deux titres populaires par artiste, à tour de rôle.
        val popular = RadioMix.interleave(pages.map { (_, p) -> p.tracks.take(2) }).take(20)
        val releases = pages.mapNotNull { (_, p) -> RadioMix.latest(p.releases) }
        ArtistFeed(all, popular, releases)
    }

    /**
     * Titres d'artistes suivis à glisser dans la radio : d'abord ceux qu'on entend déjà dans la radio ou
     * le titre en cours, puis d'autres au hasard. Jamais un titre de [exclude].
     */
    suspend fun radioPicks(current: Track, radio: List<Track>, exclude: Set<String>, count: Int): List<Track> {
        val all = library.allSubscriptions()
        if (all.isEmpty() || count <= 0) return emptyList()
        val heard = (radio.map { it.artist } + current.artist).flatMap { ImportMatch.artists(it) }.map { ImportMatch.norm(it) }.toSet()
        val (close, others) = all.partition { ImportMatch.norm(it.name) in heard }
        val artists = (close.shuffled() + others.shuffled()).take(3)
        val pool = artists.flatMap { a ->
            runCatching { page(a.browseId).tracks.take(10) }
                .onFailure { if (it is CancellationException) throw it; Log.w("UnisonRadio", "artiste suivi indisponible") }
                .getOrDefault(emptyList())
        }
        return pool.filter { it.id !in exclude }.distinctBy { it.id }.shuffled().take(count)
    }

    private companion object {
        const val CACHE_MS = 30 * 60_000L
    }
}

/** Choix sans réseau ni base : mélange de la radio avec les artistes suivis, sorties à mettre en avant. */
object RadioMix {
    /**
     * Prend [batch] titres de [radio] en glissant [mine] environ une place sur [every],
     * sans doublon ni titre de [exclude].
     */
    fun mix(radio: List<Track>, mine: List<Track>, exclude: Set<String>, batch: Int, every: Int = 4): List<Track> {
        val r = radio.filter { it.id !in exclude }.distinctBy { it.id }
        val radioIds = r.map { it.id }.toSet()
        val m = mine.filter { it.id !in exclude && it.id !in radioIds }.distinctBy { it.id }.take(batch / every)
        val out = ArrayList<Track>(batch)
        var ri = 0
        var mi = 0
        while (out.size < batch && (ri < r.size || mi < m.size)) {
            val mineTurn = (out.size + 1) % every == 0
            when {
                mineTurn && mi < m.size -> out += m[mi++]
                ri < r.size -> out += r[ri++]
                else -> out += m[mi++]
            }
        }
        return out
    }

    private val year = Regex("""\b(19|20)\d{2}\b""")

    /**
     * Sortie la plus récente d'après l'année du sous-titre (« Single • 2025 ») : la page d'un artiste range
     * ses albums avant ses singles, souvent plus récents. Sans année connue, la première de la page.
     */
    fun latest(releases: List<AlbumItem>): AlbumItem? =
        releases.withIndex().maxWithOrNull(
            compareBy<IndexedValue<AlbumItem>> { r -> year.findAll(r.value.subtitle).maxOfOrNull { it.value.toInt() } ?: 0 }
                .thenByDescending { it.index },
        )?.value

    /** Un élément de chaque liste à tour de rôle. */
    fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
        return out
    }
}

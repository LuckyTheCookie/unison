package fr.lulu.unison.youtube

import fr.lulu.unison.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

enum class SearchFilter(val label: String, internal val params: String) {
    SONGS("Titres", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"),
    ALBUMS("Albums", "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"),
    ARTISTS("Artistes", "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"),
    PLAYLISTS("Playlists", "EgeKAQQoAEABagoQAxAEEAoQCRAF"),
}

/** Le catalogue vu par l'application. */
interface MusicCatalog {
    suspend fun search(query: String, filter: SearchFilter): List<YtItem>
    suspend fun suggestions(input: String): List<String>
    suspend fun home(): List<Section>
    suspend fun collection(browseId: String): CollectionPage
    suspend fun artist(browseId: String): ArtistPage
    suspend fun browse(browseId: String, params: String?): List<Section>
    /** Titres proches de [videoId], pour prolonger la file. */
    suspend fun radio(videoId: String): List<Track>
    /** Un titre seul à partir de son identifiant (lien partagé). */
    suspend fun track(videoId: String): Track?
}

/**
 * Catalogue YouTube Music. Le JSON est déjà décodé hors du fil principal ; sa lecture par [YtParser] (des
 * centaines de titres pour une grosse playlist) l'est aussi, pour ne pas saccader l'écran qui attend la page.
 */
class YouTubeCatalog(private val yt: YouTubeEngine) : MusicCatalog {
    private suspend fun <T> parse(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    override suspend fun search(query: String, filter: SearchFilter): List<YtItem> =
        yt.searchRaw(query, filter.params).let { parse { YtParser.searchItems(it) } }

    override suspend fun suggestions(input: String): List<String> = yt.suggestionsRaw(input).let { parse { YtParser.suggestions(it) } }

    override suspend fun home(): List<Section> = coroutineScope {
        // Les classements ne dépendent pas de l'accueil : on les demande en même temps.
        val charts = async {
            runCatching { yt.browseRaw("FEmusic_charts").let { parse { YtParser.sections(it) } }.filter { it.items.isNotEmpty() && it.title.isNotBlank() } }
                .getOrDefault(emptyList())
        }
        val out = mutableListOf<Section>()
        var page = yt.browseRaw("FEmusic_home").let { parse { YtParser.sectionsPage(it) } }
        out += page.sections
        // L'accueil arrive par morceaux : deux suites suffisent à le remplir.
        repeat(2) {
            val next = page.continuation ?: return@repeat
            page = runCatching { yt.browseContinuationRaw(next).let { parse { YtParser.sectionsPage(it) } } }.getOrNull() ?: return@repeat
            out += page.sections
        }
        out += charts.await()
        out.filter { it.title.isNotBlank() }.distinctBy { it.title }
    }

    override suspend fun collection(browseId: String): CollectionPage = yt.browseRaw(browseId).let { parse { YtParser.collection(it, browseId) } }

    override suspend fun artist(browseId: String): ArtistPage = yt.browseRaw(browseId).let { parse { YtParser.artist(it) } }

    override suspend fun browse(browseId: String, params: String?): List<Section> =
        yt.browseRaw(browseId, params).let { parse { YtParser.sections(it) } }.filter { it.items.isNotEmpty() }

    override suspend fun radio(videoId: String): List<Track> = yt.nextRaw(videoId, "RDAMVM$videoId").let { parse { YtParser.radio(it, videoId) } }

    override suspend fun track(videoId: String): Track? =
        yt.nextRaw(videoId).let { parse { YtParser.seed(it, videoId) } }
}

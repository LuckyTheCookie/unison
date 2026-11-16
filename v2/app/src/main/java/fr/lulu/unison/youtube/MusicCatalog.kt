package fr.lulu.unison.youtube

import fr.lulu.unison.model.Track

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

class YouTubeCatalog(private val yt: YouTubeEngine) : MusicCatalog {
    override suspend fun search(query: String, filter: SearchFilter): List<YtItem> =
        YtParser.searchItems(yt.searchRaw(query, filter.params))

    override suspend fun suggestions(input: String): List<String> = YtParser.suggestions(yt.suggestionsRaw(input))

    override suspend fun home(): List<Section> {
        val out = mutableListOf<Section>()
        var page = YtParser.sectionsPage(yt.browseRaw("FEmusic_home"))
        out += page.sections
        // L'accueil arrive par morceaux : deux suites suffisent à le remplir.
        repeat(2) {
            val next = page.continuation ?: return@repeat
            page = runCatching { YtParser.sectionsPage(yt.browseContinuationRaw(next)) }.getOrNull() ?: return@repeat
            out += page.sections
        }
        runCatching { out += YtParser.sections(yt.browseRaw("FEmusic_charts")).filter { it.items.isNotEmpty() && it.title.isNotBlank() } }
        return out.filter { it.title.isNotBlank() }.distinctBy { it.title }
    }

    override suspend fun collection(browseId: String): CollectionPage = YtParser.collection(yt.browseRaw(browseId), browseId)

    override suspend fun artist(browseId: String): ArtistPage = YtParser.artist(yt.browseRaw(browseId))

    override suspend fun browse(browseId: String, params: String?): List<Section> =
        YtParser.sections(yt.browseRaw(browseId, params)).filter { it.items.isNotEmpty() }

    override suspend fun radio(videoId: String): List<Track> = YtParser.radio(yt.nextRaw(videoId, "RDAMVM$videoId"), videoId)

    override suspend fun track(videoId: String): Track? =
        YtParser.seed(yt.nextRaw(videoId), videoId)
}

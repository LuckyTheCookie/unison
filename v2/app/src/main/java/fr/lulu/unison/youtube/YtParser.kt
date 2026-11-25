package fr.lulu.unison.youtube

import fr.lulu.unison.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Un élément du catalogue : titre, album, artiste ou playlist. */
@Serializable
sealed interface YtItem {
    val key: String
    val title: String
    val subtitle: String
    val thumbnail: String
}

@Serializable
@SerialName("song")
data class SongItem(val track: Track) : YtItem {
    override val key get() = "s:${track.id}"
    override val title get() = track.title
    override val subtitle get() = track.artist
    override val thumbnail get() = track.cover
}

@Serializable
@SerialName("album")
data class AlbumItem(
    val browseId: String,
    override val title: String,
    override val subtitle: String,
    override val thumbnail: String,
) : YtItem {
    override val key get() = "a:$browseId"
}

@Serializable
@SerialName("artist")
data class ArtistItem(
    val browseId: String,
    override val title: String,
    override val subtitle: String,
    override val thumbnail: String,
) : YtItem {
    override val key get() = "r:$browseId"
}

@Serializable
@SerialName("playlist")
data class PlaylistItem(
    val browseId: String,
    override val title: String,
    override val subtitle: String,
    override val thumbnail: String,
) : YtItem {
    override val key get() = "p:$browseId"
}

/** Lien « Tout voir » d'une section. */
@Serializable
data class BrowseRef(val browseId: String, val params: String?, val pageType: String)

@Serializable
data class Section(val title: String, val items: List<YtItem>, val more: BrowseRef? = null)

/** Page d'un album ou d'une playlist. */
data class CollectionPage(
    val title: String,
    val subtitle: String,
    val info: String,
    val thumbnail: String,
    val artist: String,
    val artistId: String?,
    val isAlbum: Boolean,
    val tracks: List<Track>,
    val related: List<Section>,
)

data class ArtistPage(
    val name: String,
    val thumbnail: String,
    val listeners: String,
    val description: String,
    val sections: List<Section>,
)

data class SectionsPage(val sections: List<Section>, val continuation: String?)

/**
 * Interprète les réponses de l'API interne de YouTube Music. On ne suit pas des chemins figés :
 * on cherche les blocs connus où qu'ils soient, ce qui résiste aux remaniements de la page.
 */
object YtParser {
    private const val SHELF = "musicShelfRenderer"
    private const val CAROUSEL = "musicCarouselShelfRenderer"
    private const val PLAYLIST_SHELF = "musicPlaylistShelfRenderer"
    private const val GRID = "gridRenderer"
    private const val ROW = "musicResponsiveListItemRenderer"
    private const val TWO_ROW = "musicTwoRowItemRenderer"
    private val sectionKinds = setOf(SHELF, CAROUSEL, PLAYLIST_SHELF, GRID)
    private val timeRegex = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
    private val sizeRegex = Regex("""=w\d+-h\d+[^/]*$""")
    private const val SEPARATOR = " • "

    // --- Résultats ---

    fun searchItems(root: JsonElement): List<YtItem> = sections(root).flatMap { it.items }

    fun suggestions(root: JsonElement): List<String> =
        root.findAll("searchSuggestionRenderer").mapNotNull { it["suggestion"].text().ifBlank { null } }.distinct()

    fun sectionsPage(root: JsonElement): SectionsPage = SectionsPage(
        sections = sections(root).filter { it.items.isNotEmpty() },
        continuation = root.findAll("nextContinuationData").firstNotNullOfOrNull { it.string("continuation") },
    )

    fun sections(root: JsonElement): List<Section> {
        val out = mutableListOf<Section>()
        root.walk { key, value ->
            if (key in sectionKinds && value is JsonObject) {
                out += section(value)
                false // inutile de descendre : une section n'en contient pas d'autre
            } else true
        }
        return out
    }

    private fun section(s: JsonObject): Section {
        val header = s["header"]?.let { h ->
            (h as? JsonObject)?.values?.firstOrNull() as? JsonObject
        }
        val titleNode = s["title"] ?: header?.get("title")
        val items = (s["contents"] ?: s["items"]).array().mapNotNull { c ->
            val o = c as? JsonObject ?: return@mapNotNull null
            o[ROW]?.let { row(it as JsonObject) } ?: o[TWO_ROW]?.let { twoRow(it as JsonObject) }
        }
        val more = titleNode.runs().firstNotNullOfOrNull { browseRef(it["navigationEndpoint"]) }
        return Section(titleNode.text(), items, more)
    }

    fun radio(root: JsonElement, seedId: String): List<Track> =
        root.findAll("playlistPanelVideoRenderer").mapNotNull { panelTrack(it) }
            .filter { it.id != seedId }.distinctBy { it.id }

    /** Page « Similaires » d'un titre (onglet de sa file de lecture), à demander ensuite comme une page. */
    fun relatedId(root: JsonElement): String? =
        root.findAll("tabRenderer").firstNotNullOfOrNull { tab ->
            tab["endpoint"]["browseEndpoint"].string("browseId")?.takeIf { it.startsWith("MPTR") }
        }

    /** Le titre [videoId] lui-même, tel que décrit dans sa file de lecture. */
    fun seed(root: JsonElement, videoId: String): Track? =
        root.findAll("playlistPanelVideoRenderer").mapNotNull { panelTrack(it) }.firstOrNull { it.id == videoId }

    private fun panelTrack(r: JsonObject): Track? {
        val id = r.string("videoId") ?: return null
        val groups = r["longBylineText"].runs().groups()
        return Track(
            id = id,
            title = r["title"].text().ifBlank { return null },
            artist = artistOf(groups).ifBlank { r["shortBylineText"].text() },
            album = albumOf(groups),
            cover = thumbnail(r["thumbnail"]),
            durationMs = parseDuration(r["lengthText"].text()),
            explicit = explicit(r),
        )
    }

    // --- Pages ---

    fun collection(root: JsonElement, browseId: String): CollectionPage {
        val header = root.findAll("musicResponsiveHeaderRenderer").firstOrNull()
            ?: root.findAll("musicDetailHeaderRenderer").firstOrNull()
        val cover = thumbnail(header?.get("thumbnail"))
        val title = header?.get("title").text()
        val strap = header?.get("straplineTextOne")
        val artist = strap.text()
        val artistId = strap.runs().firstNotNullOfOrNull { browseRef(it["navigationEndpoint"])?.browseId }
        val isAlbum = !browseId.startsWith("VL")
        val all = sections(root)
        val main = all.firstOrNull { s -> s.items.any { it is SongItem } }
        val tracks = main?.items.orEmpty().filterIsInstance<SongItem>().map { item ->
            val t = item.track
            t.copy(
                artist = t.artist.ifBlank { artist },
                album = t.album.ifBlank { if (isAlbum) title else "" },
                cover = t.cover.ifBlank { cover },
            )
        }
        return CollectionPage(
            title = title,
            subtitle = header?.get("subtitle").text(),
            info = header?.get("secondSubtitle").text(),
            thumbnail = cover,
            artist = artist,
            artistId = artistId,
            isAlbum = isAlbum,
            tracks = tracks,
            related = all.filter { it !== main && it.items.isNotEmpty() && it.title.isNotBlank() },
        )
    }

    fun artist(root: JsonElement): ArtistPage {
        val header = root.findAll("musicImmersiveHeaderRenderer").firstOrNull()
            ?: root.findAll("musicVisualHeaderRenderer").firstOrNull()
            ?: root.findAll("musicResponsiveHeaderRenderer").firstOrNull()
        return ArtistPage(
            name = header?.get("title").text(),
            thumbnail = thumbnail(header?.get("thumbnail") ?: header?.get("foregroundThumbnail"), size = 1080),
            listeners = header?.get("monthlyListenerCount").text(),
            description = header?.get("description").text(),
            sections = sections(root).filter { it.items.isNotEmpty() },
        )
    }

    // --- Éléments ---

    private fun row(r: JsonObject): YtItem? {
        if (r.string("musicItemRendererDisplayPolicy")?.contains("GREY_OUT") == true) return null
        val columns = r["flexColumns"].array().map { it.firstValue()?.get("text") }
        val title = columns.firstOrNull().text().ifBlank { return null }
        val detail = columns.drop(1).flatMap { it.runs() + SEPARATOR_RUN }
        val groups = detail.groups()
        val thumb = thumbnail(r["thumbnail"])

        browseRef(r["navigationEndpoint"])?.let { ref ->
            val subtitle = groups.joinToString(SEPARATOR) { g -> g.joinToString("") { it.string("text").orEmpty() } }
            return when {
                ref.isAlbum -> AlbumItem(ref.browseId, title, subtitle, thumb)
                ref.isArtist -> ArtistItem(ref.browseId, title, subtitle, thumb)
                ref.isPlaylist -> PlaylistItem(ref.browseId, title, subtitle, thumb)
                else -> null
            }
        }

        val videoId = r["playlistItemData"].string("videoId")
            ?: columns.firstOrNull().runs().firstNotNullOfOrNull { videoIdOf(it["navigationEndpoint"]) }
            ?: r["overlay"]?.findAll("watchEndpoint")?.firstNotNullOfOrNull { it.string("videoId") }
            ?: return null
        val fixed = r["fixedColumns"].array().map { it.firstValue()?.get("text").text() }
        val duration = (fixed + groups.map { g -> g.joinToString("") { it.string("text").orEmpty() } })
            .firstOrNull { timeRegex.matches(it.trim()) }
        return SongItem(
            Track(
                id = videoId,
                title = title,
                artist = artistOf(groups),
                album = albumOf(groups),
                cover = thumb,
                durationMs = parseDuration(duration.orEmpty()),
                explicit = explicit(r),
            ),
        )
    }

    private fun twoRow(r: JsonObject): YtItem? {
        val title = r["title"].text().ifBlank { return null }
        val groups = r["subtitle"].runs().groups()
        val subtitle = r["subtitle"].text()
        val thumb = thumbnail(r["thumbnailRenderer"])
        val nav = r["navigationEndpoint"]
        browseRef(nav)?.let { ref ->
            return when {
                ref.isAlbum -> AlbumItem(ref.browseId, title, subtitle, thumb)
                ref.isArtist -> ArtistItem(ref.browseId, title, subtitle, thumb)
                ref.isPlaylist -> PlaylistItem(ref.browseId, title, subtitle, thumb)
                else -> null
            }
        }
        val videoId = videoIdOf(nav) ?: return null
        return SongItem(Track(id = videoId, title = title, artist = artistOf(groups), cover = thumb, explicit = explicit(r)))
    }

    /**
     * « 1,8 M lectures », « 859 M plays », « 12 k vues » : un compteur, jamais un nom d'artiste. Les pages d'album
     * n'ont pas de colonne artiste, seulement ce compteur, qui passait sinon pour l'artiste.
     */
    private val countRegex = Regex("""^[\d.,\s\u00a0\u202f]+(k|m|md|mrd|b|mio|millions?|milliards?)?\.?[\s\u00a0\u202f]*(de[\s\u00a0\u202f]+)?(lectures?|plays?|vues?|views?|écoutes?|streams?|auditeurs?|listeners?|abonnés?|subscribers?)$""", RegexOption.IGNORE_CASE)

    fun isCount(text: String): Boolean = countRegex.matches(text.trim())

    /** Les artistes : le premier groupe (entre deux « • ») qui pointe vers une page d'artiste. */
    private fun artistOf(groups: List<List<JsonObject>>): String {
        val g = groups.firstOrNull { g -> g.any { browseRef(it["navigationEndpoint"])?.isArtist == true } }
            ?: groups.firstOrNull { g ->
                val text = g.joinToString("") { it.string("text").orEmpty() }.trim()
                text.isNotEmpty() && !timeRegex.matches(text) && !isCount(text) &&
                    g.none { browseRef(it["navigationEndpoint"])?.isAlbum == true }
            }
            ?: return ""
        return g.joinToString("") { it.string("text").orEmpty() }.trim()
    }

    private fun albumOf(groups: List<List<JsonObject>>): String =
        groups.flatten().firstOrNull { browseRef(it["navigationEndpoint"])?.isAlbum == true }?.string("text").orEmpty()

    private fun explicit(r: JsonObject): Boolean =
        r["badges"]?.findAll("icon")?.any { it.string("iconType") == "MUSIC_EXPLICIT_BADGE" } == true

    private val BrowseRef.isAlbum get() = pageType == "MUSIC_PAGE_TYPE_ALBUM" || pageType == "MUSIC_PAGE_TYPE_AUDIOBOOK"
    private val BrowseRef.isArtist get() = pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL"
    private val BrowseRef.isPlaylist get() = pageType == "MUSIC_PAGE_TYPE_PLAYLIST"

    private fun browseRef(nav: JsonElement?): BrowseRef? {
        val b = nav["browseEndpoint"] as? JsonObject ?: return null
        val id = b.string("browseId") ?: return null
        val type = b["browseEndpointContextSupportedConfigs"]["browseEndpointContextMusicConfig"].string("pageType").orEmpty()
        return BrowseRef(id, b.string("params"), type)
    }

    private fun videoIdOf(nav: JsonElement?): String? = nav["watchEndpoint"].string("videoId")

    /** Plus grande vignette disponible ; les pochettes Google sont redimensionnées à [size] px. */
    fun thumbnail(node: JsonElement?, size: Int = 544): String {
        var best: String? = null
        node?.walk { key, value ->
            if (key == "thumbnails" && value is JsonArray) {
                best = value.lastOrNull().string("url") ?: best
                false
            } else true
        }
        val url = best ?: return ""
        return resize(url, size)
    }

    /** Adapte la taille d'une pochette hébergée par Google ; laisse les autres URL intactes. */
    fun resize(url: String, size: Int): String =
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            if (sizeRegex.containsMatchIn(url)) url.replace(sizeRegex, "=w$size-h$size-l90-rj") else url
        } else url

    fun parseDuration(text: String): Long {
        val t = text.trim()
        if (!timeRegex.matches(t)) return 0
        return t.split(':').fold(0L) { acc, part -> acc * 60 + part.toLong() } * 1000
    }

    // --- Outils JSON ---

    private val SEPARATOR_RUN = JsonObject(mapOf("text" to JsonPrimitive(SEPARATOR)))

    /** Découpe une suite de fragments de texte aux séparateurs « • ». */
    private fun List<JsonObject>.groups(): List<List<JsonObject>> {
        val out = mutableListOf<List<JsonObject>>()
        var current = mutableListOf<JsonObject>()
        for (run in this) {
            if (run.string("text") == SEPARATOR) {
                if (current.isNotEmpty()) out += current
                current = mutableListOf()
            } else current += run
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

    private fun JsonElement?.string(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray) ?: emptyList()

    private fun JsonElement?.firstValue(): JsonObject? = (this as? JsonObject)?.values?.firstOrNull() as? JsonObject

    private fun JsonElement?.runs(): List<JsonObject> = this["runs"].array().filterIsInstance<JsonObject>()

    private fun JsonElement?.text(): String =
        this.string("simpleText") ?: runs().joinToString("") { it.string("text").orEmpty() }

    /** Parcours en profondeur ; [visit] renvoie faux pour ne pas descendre dans la valeur. */
    private fun JsonElement.walk(visit: (key: String, value: JsonElement) -> Boolean) {
        when (this) {
            is JsonObject -> for ((k, v) in this) if (visit(k, v)) v.walk(visit)
            is JsonArray -> for (v in this) v.walk(visit)
            else -> Unit
        }
    }

    private fun JsonElement.findAll(key: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        walk { k, v ->
            if (k == key && v is JsonObject) out += v
            true
        }
        return out
    }
}

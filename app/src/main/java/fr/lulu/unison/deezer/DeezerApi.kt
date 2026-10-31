package fr.lulu.unison.deezer

import fr.lulu.unison.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** API publique de Deezer (sans clé) : recherche et métadonnées. */
class DeezerApi(private val http: OkHttpClient = defaultClient) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class ApiArtist(val name: String = "")
    @Serializable private data class ApiAlbum(
        val title: String = "",
        val cover_medium: String = "",
        val cover_xl: String = "",
        val cover_big: String = "",
    )
    @Serializable private data class ApiTrack(
        val id: Long = 0,
        val title: String = "",
        val title_short: String = "",
        val duration: Long = 0,
        val readable: Boolean = true,
        val artist: ApiArtist = ApiArtist(),
        val album: ApiAlbum = ApiAlbum(),
    )
    @Serializable private data class ApiError(val message: String = "")
    @Serializable private data class SearchResult(val data: List<ApiTrack> = emptyList())
    @Serializable private data class TrackResult(
        val id: Long = 0,
        val title: String = "",
        val title_short: String = "",
        val duration: Long = 0,
        val artist: ApiArtist = ApiArtist(),
        val album: ApiAlbum = ApiAlbum(),
        val error: ApiError? = null,
    )

    suspend fun search(query: String, limit: Int = 30): List<Track> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://api.deezer.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("limit", limit.toString())
            .build()
        val body = get(url.toString())
        json.decodeFromString(SearchResult.serializer(), body).data.filter { it.readable && it.id > 0 }.map { it.toTrack() }
    }

    suspend fun track(id: Long): Track = withContext(Dispatchers.IO) {
        val r = json.decodeFromString(TrackResult.serializer(), get("https://api.deezer.com/track/$id"))
        if (r.error != null || r.id == 0L) throw IllegalStateException("Titre introuvable")
        ApiTrack(r.id, r.title, r.title_short, r.duration, true, r.artist, r.album).toTrack()
    }

    /** Transforme un texte partagé par Deezer en titre (suit les liens courts). */
    suspend fun resolveShared(text: String): Track = withContext(Dispatchers.IO) {
        DeezerLinkParser.directTrackId(text)?.let { return@withContext track(it) }
        val url = DeezerLinkParser.firstDeezerUrl(text) ?: throw IllegalArgumentException("Aucun lien Deezer dans le partage")
        if (DeezerLinkParser.isNonTrackDeezerUrl(url)) throw IllegalArgumentException("Seuls les titres peuvent être ajoutés")
        val finalUrl = followRedirects(url)
        val id = DeezerLinkParser.directTrackId(finalUrl)
            ?: throw IllegalArgumentException(
                if (DeezerLinkParser.isNonTrackDeezerUrl(finalUrl)) "Seuls les titres peuvent être ajoutés" else "Lien Deezer non reconnu"
            )
        track(id)
    }

    private fun followRedirects(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            val final = resp.request.url.toString()
            if (DeezerLinkParser.directTrackId(final) != null) return final
            // Certains liens courts redirigent via une page HTML : on cherche le lien dans le contenu.
            val body = resp.body.string()
            return Regex("""https?://www\.deezer\.com/[^\s"'<>]*track/\d+""").find(body)?.value ?: final
        }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Deezer a répondu ${resp.code}")
            return resp.body.string()
        }
    }

    private fun ApiTrack.toTrack() = Track(
        id = id,
        title = title_short.ifBlank { title },
        artist = artist.name,
        album = album.title,
        cover = album.cover_xl.ifBlank { album.cover_big },
        coverSmall = album.cover_medium,
        durationMs = duration * 1000,
    )

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 16) Unison/1.0"
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

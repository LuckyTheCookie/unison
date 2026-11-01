package fr.lulu.unison.lyrics

import android.util.Log
import fr.lulu.unison.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Un fournisseur de paroles gratuit, sans compte. */
interface LyricsProvider {
    val name: String
    suspend fun find(track: Track): Lyrics?
}

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
private const val UA = "Unison/1.0 (https://github.com/ ; lecteur de paroles)"

private fun OkHttpClient.getText(url: String, ua: String = UA): String? {
    val req = Request.Builder().url(url).header("User-Agent", ua).build()
    return newCall(req).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
}

/** LRCLIB : base ouverte et communautaire de paroles synchronisées. */
class LrclibProvider(private val http: OkHttpClient) : LyricsProvider {
    override val name = "LRCLIB"

    @Serializable private data class Item(
        val trackName: String? = null,
        val artistName: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )

    var lastPlain: Lyrics? = null
        private set

    override suspend fun find(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        lastPlain = null
        val seconds = track.durationMs / 1000
        val exact = http.getText(
            "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", track.artist)
                .addQueryParameter("track_name", track.title)
                .apply { if (track.album.isNotBlank()) addQueryParameter("album_name", track.album) }
                .apply { if (seconds > 0) addQueryParameter("duration", seconds.toString()) }
                .build().toString()
        )?.let { runCatching { json.decodeFromString(Item.serializer(), it) }.getOrNull() }

        val candidates = buildList {
            exact?.let { add(it) }
            if (exact?.syncedLyrics.isNullOrBlank()) {
                val body = http.getText(
                    "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                        .addQueryParameter("track_name", track.title)
                        .addQueryParameter("artist_name", track.artist)
                        .build().toString()
                )
                body?.let { b -> runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Item.serializer()), b) }.getOrNull() }
                    ?.let { addAll(it) }
            }
        }
        val want = normTitle(track.title)
        val ranked = candidates
            .filter { normTitle(it.trackName ?: "").let { n -> n == want || n.startsWith(want) || want.startsWith(n) } }
            .sortedBy { c -> if (seconds > 0 && c.duration != null) abs(c.duration - seconds) else 0.0 }
            .filter { c -> seconds <= 0 || c.duration == null || abs(c.duration - seconds) <= 8 }

        ranked.firstOrNull { !it.syncedLyrics.isNullOrBlank() }?.let { item ->
            val lines = Lrc.parse(item.syncedLyrics!!)
            if (lines.isNotEmpty()) return@withContext Lyrics(lines, synced = true, source = name)
        }
        ranked.firstOrNull { !it.plainLyrics.isNullOrBlank() }?.let { item ->
            lastPlain = Lyrics(Lrc.plain(item.plainLyrics!!), synced = false, source = name)
        }
        if (ranked.any { it.instrumental }) lastPlain = Lyrics(emptyList(), synced = false, source = name)
        null
    }
}

/** NetEase Cloud Music : très grand catalogue de paroles synchronisées, API publique. */
class NeteaseProvider(private val http: OkHttpClient) : LyricsProvider {
    override val name = "NetEase"

    @Serializable private data class Artist(val name: String = "")
    @Serializable private data class Song(val id: Long = 0, val name: String = "", val artists: List<Artist> = emptyList(), val duration: Long = 0)
    @Serializable private data class Result(val songs: List<Song> = emptyList())
    @Serializable private data class Search(val result: Result? = null)
    @Serializable private data class Lrc(val lyric: String? = null)
    @Serializable private data class LyricResp(val lrc: Lrc? = null)

    override suspend fun find(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        val browser = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
        val body = http.getText(
            "https://music.163.com/api/search/get".toHttpUrl().newBuilder()
                .addQueryParameter("s", "${track.title} ${track.artist}")
                .addQueryParameter("type", "1")
                .addQueryParameter("limit", "15")
                .build().toString(),
            browser,
        ) ?: return@withContext null
        val songs = runCatching { json.decodeFromString(Search.serializer(), body).result?.songs }.getOrNull().orEmpty()
        val want = normTitle(track.title)
        val artist = normTitle(track.artist)
        val best = songs
            .filter { s -> normTitle(s.name) == want }
            .filter { s -> artist.isEmpty() || s.artists.any { a -> normTitle(a.name).let { it.contains(artist) || artist.contains(it) } } }
            .sortedBy { s -> if (track.durationMs > 0) abs(s.duration - track.durationMs) else 0L }
            .take(3)
        for (song in best) {
            if (track.durationMs > 0 && abs(song.duration - track.durationMs) > 10_000) continue
            val lrc = http.getText("https://music.163.com/api/song/lyric?id=${song.id}&lv=1", browser)
                ?.let { runCatching { json.decodeFromString(LyricResp.serializer(), it).lrc?.lyric }.getOrNull() }
            if (lrc.isNullOrBlank()) continue
            val lines = fr.lulu.unison.lyrics.Lrc.parse(lrc).filterNot { fr.lulu.unison.lyrics.Lrc.isCredit(it.text) }
            if (lines.count { it.text.isNotBlank() } >= 3) return@withContext Lyrics(lines, synced = true, source = name)
        }
        null
    }
}

/**
 * Cherche les paroles chez plusieurs fournisseurs, synchronisées d'abord :
 * LRCLIB → NetEase → paroles simples de LRCLIB.
 */
class LyricsRepository(http: OkHttpClient) {
    private val lrclib = LrclibProvider(http)
    private val providers: List<LyricsProvider> = listOf(lrclib, NeteaseProvider(http))
    private val cache = ConcurrentHashMap<Long, Lyrics>()
    private val missing = ConcurrentHashMap.newKeySet<Long>()

    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun lyricsFor(track: Track): Lyrics? = mutex.withLock { lookup(track) }

    private suspend fun lookup(track: Track): Lyrics? {
        cache[track.id]?.let { return it }
        if (track.id in missing) return null
        var plain: Lyrics? = null
        for (p in providers) {
            val found = runCatching { p.find(track) }.onFailure { Log.w("UnisonLyrics", "${p.name}", it) }.getOrNull()
            if (p === lrclib) plain = lrclib.lastPlain
            if (found != null) { cache[track.id] = found; return found }
        }
        return plain?.also { cache[track.id] = it } ?: run { missing += track.id; null }
    }
}

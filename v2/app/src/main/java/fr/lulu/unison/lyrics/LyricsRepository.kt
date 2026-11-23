package fr.lulu.unison.lyrics

import android.util.Log
import fr.lulu.unison.model.Track
import fr.lulu.unison.source.SourceTrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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
            if (lines.isNotEmpty()) return@withContext Lyrics(lines, synced = true, source = name, wordSynced = lines.any { it.words != null })
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
    @Serializable private data class LyricResp(val lrc: Lrc? = null, val yrc: Lrc? = null)

    /** Paroles synchronisées à la ligne trouvées lors de la dernière recherche, quand il n'y avait pas de YRC. */
    var lastLineSynced: Lyrics? = null
        private set

    /** Cherche d'abord les paroles mot à mot (YRC) ; à défaut, garde les paroles à la ligne dans [lastLineSynced]. */
    override suspend fun find(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        lastLineSynced = null
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
            val resp = http.getText("https://music.163.com/api/song/lyric/v1?id=${song.id}&lv=1&yv=1", browser)
                ?.let { runCatching { json.decodeFromString(LyricResp.serializer(), it) }.getOrNull() } ?: continue
            resp.yrc?.lyric?.takeIf { it.isNotBlank() }?.let { yrc ->
                val lines = fr.lulu.unison.lyrics.Lrc.parseYrc(yrc).filterNot { fr.lulu.unison.lyrics.Lrc.isCredit(it.text) }
                if (lines.count { it.text.isNotBlank() } >= 3) return@withContext Lyrics(lines, synced = true, source = name, wordSynced = true)
            }
            val lrc = resp.lrc?.lyric
            if (lrc.isNullOrBlank() || lastLineSynced != null) continue
            val lines = fr.lulu.unison.lyrics.Lrc.parse(lrc).filterNot { fr.lulu.unison.lyrics.Lrc.isCredit(it.text) }
            if (lines.count { it.text.isNotBlank() } >= 3) lastLineSynced = Lyrics(lines, synced = true, source = name)
        }
        null
    }
}

/**
 * Cherche les paroles chez tous les fournisseurs à la fois et garde les plus précises : minutées au mot
 * (BetterLyrics, LyricsPlus, NetEase YRC, LRC enrichi de LRCLIB) avant celles minutées à la ligne, puis les
 * paroles simples. Les paroles synchronisées reçoivent toujours un minutage par mot, estimé quand la source
 * n'en donne pas.
 */
class LyricsRepository(
    http: OkHttpClient,
    /** Dossier des paroles gardées avec les titres téléchargés (null : rien n'est gardé). */
    private val offlineDir: File? = null,
) {
    private val better = BetterLyricsProvider(http)
    private val plus = LyricsPlusProvider(http)
    private val lrclib = LrclibProvider(http)
    private val netease = NeteaseProvider(http)
    /** Par ordre de préférence, à précision égale. */
    private val providers: List<LyricsProvider> = listOf(better, plus, netease, lrclib)
    private val cache = ConcurrentHashMap<String, Lyrics>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun lyricsFor(track: Track): Lyrics? = mutex.withLock { lookup(track) }

    private suspend fun lookup(track: Track): Lyrics? {
        cache[track.id]?.let { return it }
        readOffline(track.id)?.let { cache[track.id] = it; return it }
        if (track.id in missing) return null
        var failed = false
        val found = coroutineScope {
            val jobs = providers.map { p ->
                async {
                    try {
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { p.find(track) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w("UnisonLyrics", p.name, t)
                        failed = true
                        null
                    }
                }
            }
            val answers = mutableListOf<Lyrics>()
            for (job in jobs) {
                val lyrics = job.await() ?: continue
                // La plus précise arrive : inutile d'attendre les suivantes.
                if (lyrics.wordSynced) { jobs.forEach { it.cancel() }; return@coroutineScope lyrics }
                answers += lyrics
            }
            answers.firstOrNull { it.synced } ?: netease.lastLineSynced ?: lrclib.lastPlain
        }
        val best = found?.let { if (it.synced) Karaoke.withInterludes(Karaoke.complete(it)) else it }
        // Sans réseau, « introuvable » n'est pas définitif : on réessaiera plus tard.
        return best?.also { cache[track.id] = it } ?: run { if (!failed) missing += track.id; null }
    }

    private companion object {
        const val PROVIDER_TIMEOUT_MS = 9_000L
    }

    private fun offlineFile(id: String) = offlineDir?.let { File(it, "${SourceTrackId.fileName(id)}.json") }

    private suspend fun readOffline(id: String): Lyrics? = withContext(Dispatchers.IO) {
        val f = offlineFile(id)?.takeIf { it.exists() } ?: return@withContext null
        runCatching { Karaoke.withInterludes(Karaoke.cleaned(json.decodeFromString(Lyrics.serializer(), f.readText()))) }.getOrNull()
    }

    /** Garde les paroles de [track] sur le téléphone, pour les afficher hors ligne. */
    suspend fun keepOffline(track: Track) {
        val dir = offlineDir ?: return
        val l = lyricsFor(track) ?: return
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val f = File(dir, "${SourceTrackId.fileName(track.id)}.json")
            val tmp = File(dir, "${SourceTrackId.fileName(track.id)}.json.tmp")
            tmp.writeText(json.encodeToString(Lyrics.serializer(), l))
            tmp.renameTo(f)
        }
    }

    fun forgetOffline(id: String) {
        offlineFile(id)?.delete()
    }
}

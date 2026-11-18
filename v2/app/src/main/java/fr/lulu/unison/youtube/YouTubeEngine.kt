package fr.lulu.unison.youtube

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.YouTubeLocale
import fr.lulu.unison.source.StreamUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.HttpResponse
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** Un flux audio prêt à être lu. L'URL est signée et temporaire : ne jamais la journaliser. */
class ResolvedStream(
    val videoId: String,
    val url: String,
    val headers: Map<String, String>,
    val itag: Int,
    val mimeType: String?,
    val expiresAtMs: Long,
    val loudnessDb: Double?,
    val contentLength: Long?,
) {
    override fun toString() = "ResolvedStream($videoId, itag=$itag, $mimeType)"
}

enum class Quality(val label: String) { HIGH("Élevée"), LOW("Économie de données") }

/**
 * Accès à YouTube Music par son API interne (InnerTubeX, le moteur de Metrolist) :
 * requêtes brutes pour le catalogue et résolution des flux audio. Sans compte.
 * Pour le lecteur, c'est [fr.lulu.unison.source.YouTubeSource] qui l'expose comme une source parmi d'autres.
 */
class YouTubeEngine(
    okHttp: OkHttpClient,
    region: String = "FR",
    language: String = "fr",
    private val quality: () -> Quality = { Quality.HIGH },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val http = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
        }
        install(ContentEncoding) { gzip(); deflate() }
    }
    private val tube = InnerTube(httpClient = http).apply { locale = YouTubeLocale(gl = region, hl = language) }
    private val extractor: InnerTubeExtractor by lazy {
        val store = RemotePlayerConfigStore(httpClient = http, repository = PlayerConfigRepository.disabled())
        val cipher = YouTubeCipherService(http, store)
        InnerTubeExtractor(
            configParser = YtConfigParserImpl(http, tube, store, cipherService = cipher),
            cipherService = cipher,
            innerTube = tube,
        )
    }

    private val streams = ConcurrentHashMap<String, ResolvedStream>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun resolve(videoId: String): ResolvedStream {
        cached(videoId)?.let { return it }
        return locks.getOrPut(videoId) { Mutex() }.withLock {
            cached(videoId) ?: extract(videoId).also {
                streams[videoId] = it
                if (streams.size > 64) streams.values.removeIf { s -> s.expiresAtMs < nowMs() }
            }
        }
    }

    private fun cached(videoId: String) = streams[videoId]?.takeIf { it.expiresAtMs - nowMs() > EXPIRY_MARGIN_MS }

    fun invalidate(videoId: String) {
        streams.remove(videoId)
    }

    private suspend fun extract(videoId: String): ResolvedStream = withContext(Dispatchers.IO) {
        // Lecture directe uniquement : ExoPlayer ne sait lire ni le SABR ni les plages imposées de YouTube.
        val hints = ContentHints(wantVideo = false)
            .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = false)
        val s = try {
            extractor.extract(
                videoId = videoId,
                hints = hints,
                audioQuality = if (quality() == Quality.LOW) AudioQuality.LOW else AudioQuality.HIGH,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw StreamUnavailableException("Titre indisponible", e)
        } ?: throw StreamUnavailableException("Titre indisponible")
        if (!s.audioUrl.startsWith("https://")) throw StreamUnavailableException("Format de flux non pris en charge")
        ResolvedStream(
            videoId = videoId,
            url = s.audioUrl,
            headers = s.headers,
            itag = s.itag,
            mimeType = s.mimeType,
            expiresAtMs = s.expiresAt?.toEpochMilliseconds() ?: (nowMs() + DEFAULT_LIFETIME_MS),
            loudnessDb = s.loudnessDb,
            contentLength = s.contentLengthBytes,
        )
    }

    // --- Catalogue : réponses JSON brutes, interprétées par YtParser ---

    suspend fun searchRaw(query: String, params: String? = null): JsonObject =
        tube.search(YouTubeClient.WEB_REMIX, query = query, params = params, setLogin = false).json()

    suspend fun suggestionsRaw(input: String): JsonObject =
        tube.getSearchSuggestions(YouTubeClient.WEB_REMIX, input).json()

    suspend fun browseRaw(browseId: String, params: String? = null): JsonObject =
        tube.browse(YouTubeClient.WEB_REMIX, browseId = browseId, params = params).json()

    suspend fun browseContinuationRaw(continuation: String): JsonObject =
        tube.browse(YouTubeClient.WEB_REMIX, continuation = continuation).json()

    suspend fun nextRaw(videoId: String, playlistId: String? = null): JsonObject =
        tube.next(YouTubeClient.WEB_REMIX, videoId = videoId, playlistId = playlistId).json()

    private suspend fun HttpResponse.json(): JsonObject = withContext(Dispatchers.Default) {
        RawJson.parseToJsonElement(bodyAsTextLimited(MAX_RESPONSE_BYTES)) as JsonObject
    }

    fun close() {
        tube.close(); http.close()
    }

    private companion object {
        const val EXPIRY_MARGIN_MS = 10 * 60_000L
        const val DEFAULT_LIFETIME_MS = 5 * 3_600_000L
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        val RawJson = Json { ignoreUnknownKeys = true }
    }
}

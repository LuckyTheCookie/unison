package fr.lulu.unison

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Vérifie « en vrai » qu'on obtient un flux audio lisible depuis YouTube Music.
 * Ignoré par défaut : UNISON_LIVE=1 ./gradlew testDebugUnitTest --tests '*Live*'
 */
class LiveExtractionTest {
    @Test
    fun audioStreamIsPlayable() = runBlocking {
        assumeTrue(System.getenv("UNISON_LIVE") == "1")
        val http = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
            }
            install(ContentEncoding) { gzip(); deflate() }
        }
        val log = InnerTubeLogger { e -> if (e.level != com.metrolist.innertubex.InnerTubeLogLevel.DEBUG) println("  [${e.level}] ${e.tag}: ${e.message} ${e.details}") }
        val client = InnerTube(httpClient = http, logger = log)
        val store = RemotePlayerConfigStore(httpClient = http, repository = PlayerConfigRepository.disabled())
        val cipher = YouTubeCipherService(http, store, log)
        val extractor = InnerTubeExtractor(
            configParser = YtConfigParserImpl(http, client, store, log, cipherService = cipher),
            cipherService = cipher,
            innerTube = client,
            logger = log,
        )
        // UNISON_IDS=id1,id2 pour essayer des titres précis.
        val ids = System.getenv("UNISON_IDS")?.split(",")?.filter { it.isNotBlank() } ?: listOf("dQw4w9WgXcQ", "kJQP7kiw5Fk", "fJ9rUzIMcZQ")
        var direct = 0
        for (id in ids) {
            // UNISON_CLIENTS=A,B : essaie aussi chaque client imposé, en flux direct seulement.
            val forced = System.getenv("UNISON_CLIENTS")?.split(",")?.filter { it.isNotBlank() }.orEmpty().map { c ->
                c to ContentHints(wantVideo = false, playbackClientOverrideId = c).withStreamCapabilities(allowHls = false, allowSabr = false)
            }
            for (hints in listOf(
                "défaut" to ContentHints(wantVideo = false),
                "sans SABR/HLS" to ContentHints(wantVideo = false).withStreamCapabilities(allowHls = false, allowSabr = false),
            ) + forced) {
                val t0 = System.currentTimeMillis()
                val s = runCatching { extractor.extract(id, hints.second, audioQuality = AudioQuality.HIGH) }
                    .onFailure {
                        println("$id [${hints.first}] ERREUR ${it::class.simpleName}: ${it.message}")
                        (it as? com.metrolist.innertubex.extraction.StreamResolveException)?.let { e ->
                            println("    raison=${e.reason} essais=" + e.diagnostics?.attempts?.joinToString { a -> "${a.clientName}:${a.outcome}" })
                        }
                    }
                    .getOrNull()
                val ms = System.currentTimeMillis() - t0
                if (s == null) { println("$id [${hints.first}] aucun flux (${ms} ms)"); continue }
                val kind = when {
                    s.audioUrl.startsWith("sabr://") -> "SABR"
                    s.audioUrl.contains(".m3u8") || s.itag == 96 -> "HLS"
                    else -> "direct"
                }
                println(
                    "$id [${hints.first}] $kind client=${s.clientName} itag=${s.itag} mime=${s.mimeType} codecs=${s.codecs} " +
                        "bitrate=${s.bitrate} taille=${s.contentLengthBytes} expire=${s.expiresAt} " +
                        "plages=${s.requireBoundedRange}/${s.useRangeChunks}/${s.rangeChunkSizeBytes} en ${ms} ms",
                )
                if (kind != "direct") continue
                val req = Request.Builder().url(s.audioUrl).apply {
                    s.headers.forEach { (k, v) -> header(k, v) }
                    header("Range", "bytes=0-65535")
                }.build()
                OkHttpClient().newCall(req).execute().use { r ->
                    val n = r.body.bytes().size
                    println("    GET plage -> HTTP ${r.code}, $n octets, type=${r.header("Content-Type")}")
                    if (r.code in 200..299 && n > 1000) direct++
                }
            }
        }
        client.close(); http.close()
        assertTrue("aucun flux direct lisible", direct > 0)
    }
}

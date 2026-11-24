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
import com.metrolist.innertubex.extraction.StreamResolveException
import fr.lulu.unison.source.AgeRestrictedException
import fr.lulu.unison.source.StreamUnavailableException
import kotlinx.coroutines.withTimeoutOrNull
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
import android.util.Log
import okhttp3.OkHttpClient
import java.io.File
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
    /** Dossier où garder le lecteur YouTube déjà préparé d'un lancement à l'autre ; null : rien n'est gardé. */
    private val cacheDir: File? = null,
    /** Cookies de la session YouTube de l'utilisateur, s'il l'a connectée : seulement pour les titres réservés aux adultes. */
    private val signedInCookie: () -> String? = { null },
) {
    private val http = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
        }
        install(ContentEncoding) { gzip(); deflate() }
    }
    private val tube = InnerTube(httpClient = http).apply { locale = YouTubeLocale(gl = region, hl = language) }
    private val extractor: InnerTubeExtractor by lazy { extractorFor(tube) }

    private fun extractorFor(tube: InnerTube): InnerTubeExtractor {
        val store = RemotePlayerConfigStore(httpClient = http, repository = PlayerConfigRepository.disabled())
        val cipher = YouTubeCipherService(http, store)
        return InnerTubeExtractor(
            configParser = YtConfigParserImpl(http, tube, store, cipherService = cipher),
            cipherService = cipher,
            innerTube = tube,
        )
    }

    /**
     * Le même moteur, mais connecté au compte de l'utilisateur. À part : le catalogue et les autres titres
     * restent anonymes (rien n'entre dans l'historique YouTube, l'accueil n'est pas personnalisé).
     */
    private class SignedIn(val cookie: String, val tube: InnerTube, val extractor: InnerTubeExtractor)
    @Volatile private var signedIn: SignedIn? = null

    private fun signedInFor(cookie: String): SignedIn = synchronized(this) {
        signedIn?.takeIf { it.cookie == cookie } ?: run {
            signedIn?.tube?.close()
            val tube = InnerTube(httpClient = http).apply {
                locale = this@YouTubeEngine.tube.locale
                this.cookie = cookie
            }
            SignedIn(cookie, tube, extractorFor(tube)).also { signedIn = it }
        }
    }

    private val streams = ConcurrentHashMap<String, ResolvedStream>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Titres réservés aux adultes : connus, ils échouent tout de suite au lieu de repasser par tous les clients
     * (une dizaine de secondes). Gardés sur le disque d'un lancement à l'autre.
     */
    private val ageGated: MutableSet<String> by lazy {
        ConcurrentHashMap.newKeySet<String>().apply {
            ageFile?.readOrNull()?.lineSequence()?.map { it.trim() }?.filter { it.length == 11 }?.forEach { add(it) }
        }
    }
    private val ageFile get() = cacheDir?.let { File(it, "age-restricted.txt") }

    fun isAgeRestricted(videoId: String) = videoId in ageGated

    private fun rememberAgeGated(videoId: String) {
        if (!ageGated.add(videoId)) return
        runCatching { ageFile?.let { f -> f.parentFile?.mkdirs(); f.writeAtomically(ageGated.joinToString("\n")) } }
    }

    suspend fun resolve(videoId: String): ResolvedStream {
        cached(videoId)?.let { return it }
        if (videoId in ageGated && signedInCookie() == null) throw AgeRestrictedException()
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

    private val cachesLock = Mutex()
    @Volatile private var cachesSet = false

    /**
     * Branche le cache disque d'InnerTubeX pour l'amorçage du lecteur YouTube : sans lui, le premier titre de
     * chaque lancement redemande tout au lecteur YouTube avant de pouvoir partir.
     */
    private suspend fun ensureCaches() {
        val dir = cacheDir ?: return
        if (cachesSet) return
        cachesLock.withLock {
            if (cachesSet) return
            runCatching {
                dir.mkdirs()
                val bootstrap = File(dir, "player-bootstrap.json")
                extractor.setPlayerBootstrapCache({ bootstrap.readOrNull() }, { bootstrap.writeAtomically(it) })
            }.onFailure { Log.w(TAG, "cache du lecteur YouTube", it) }
            cachesSet = true
        }
    }

    /**
     * L'échec vient-il de la limite d'âge ? En flux direct seul, InnerTubeX ne le dit pas (« indisponible ») :
     * on redemande alors le titre sans cette contrainte, ce qui donne la vraie raison. Seulement après un échec.
     */
    private suspend fun isAgeGate(videoId: String, e: Throwable): Boolean {
        val resolve = e as? StreamResolveException ?: return false
        if (resolve.reason == StreamResolveException.Reason.AGE_RESTRICTED) return true
        if (resolve.reason == StreamResolveException.Reason.NETWORK) return false
        return try {
            withTimeoutOrNull(AGE_CHECK_MS) {
                extractor.extract(videoId = videoId, hints = ContentHints(wantVideo = false), audioQuality = AudioQuality.LOW)
            }
            false
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            (t as? StreamResolveException)?.reason == StreamResolveException.Reason.AGE_RESTRICTED
        }
    }

    /** Prépare l'extraction avant le premier titre (lecteur YouTube, chiffrement), en tâche de fond. */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        ensureCaches()
        runCatching { extractor.prewarm() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "préchauffage", it) }
        Unit
    }

    /** Lecture directe uniquement : ExoPlayer ne sait lire ni le SABR ni les plages imposées de YouTube. */
    private fun directHints(ageRestricted: Boolean? = null) = ContentHints(isAgeRestricted = ageRestricted, wantVideo = false)
        .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = false)

    private fun audioQuality() = if (quality() == Quality.LOW) AudioQuality.LOW else AudioQuality.HIGH

    private suspend fun extract(videoId: String): ResolvedStream = withContext(Dispatchers.IO) {
        ensureCaches()
        // Déjà connu comme réservé aux adultes : directement par le compte, sans repasser par les clients anonymes.
        if (videoId in ageGated) {
            val cookie = signedInCookie() ?: throw AgeRestrictedException()
            return@withContext extractSignedIn(videoId, cookie)
        }
        val s = try {
            extractor.extract(videoId = videoId, hints = directHints(), audioQuality = audioQuality())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Certains titres (les comptines « conçues pour les enfants », par exemple) n'ont plus de flux direct
            // par le choix automatique d'InnerTubeX, mais un client TV en donne encore un.
            if ((e as? StreamResolveException)?.reason != StreamResolveException.Reason.NETWORK) {
                forcedClients(videoId)?.let { return@withContext it }
            }
            if (isAgeGate(videoId, e)) {
                rememberAgeGated(videoId)
                val cookie = signedInCookie() ?: throw AgeRestrictedException(cause = e)
                return@withContext extractSignedIn(videoId, cookie)
            }
            throw StreamUnavailableException("Titre indisponible", e)
        } ?: throw StreamUnavailableException("Titre indisponible")
        s.toResolved(videoId)
    }

    /** Dernier recours sans compte : les clients qui donnent encore un flux direct quand le choix automatique échoue. */
    private suspend fun forcedClients(videoId: String): ResolvedStream? {
        for (client in ANONYMOUS_FALLBACK_CLIENTS) {
            val hints = ContentHints(wantVideo = false, playbackClientOverrideId = client)
                .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = false)
            try {
                extractor.extract(videoId = videoId, hints = hints, audioQuality = audioQuality())?.let { return it.toResolved(videoId) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.d(TAG, "$client : ${(e as? StreamResolveException)?.reason}")
            }
        }
        return null
    }

    /**
     * Titre réservé aux adultes, demandé avec la session YouTube de l'utilisateur. Laissé à lui-même, InnerTubeX
     * choisit le client web de YouTube Music, qui ne donne alors que du SABR (le flux direct exige un jeton
     * anti-robot) : on essaie d'abord les clients qui donnent un flux direct à un compte connecté.
     */
    private suspend fun extractSignedIn(videoId: String, cookie: String): ResolvedStream {
        val extractor = signedInFor(cookie).extractor
        var last: Throwable? = null
        for (client in SIGNED_IN_CLIENTS) {
            val hints = ContentHints(isAgeRestricted = true, wantVideo = false, playbackClientOverrideId = client)
                .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = false)
            try {
                extractor.extract(videoId = videoId, hints = hints, audioQuality = audioQuality())?.let { return it.toResolved(videoId) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: StreamUnavailableException) {
                last = e
            } catch (e: Throwable) {
                last = e
                val resolve = e as? StreamResolveException
                Log.w(TAG, "titre réservé aux adultes, $client : ${resolve?.reason} " +
                    resolve?.diagnostics?.attempts?.joinToString { "${it.clientName}=${it.outcome}" })
                // Sans réseau ou refusé au compte lui-même : les autres clients n'y changeront rien.
                if (resolve?.reason == StreamResolveException.Reason.NETWORK || resolve?.reason == StreamResolveException.Reason.AGE_RESTRICTED) break
            }
        }
        throw AgeRestrictedException(
            when ((last as? StreamResolveException)?.reason) {
                StreamResolveException.Reason.AGE_RESTRICTED -> AGE_UNVERIFIED_MESSAGE
                StreamResolveException.Reason.NETWORK -> "Pas de réseau"
                else -> SIGNED_IN_FAILED_MESSAGE
            },
            last,
        )
    }

    private fun com.metrolist.innertubex.extraction.ExtractedStream.toResolved(videoId: String): ResolvedStream {
        if (!audioUrl.startsWith("https://")) throw StreamUnavailableException("Format de flux non pris en charge")
        val s = this
        return ResolvedStream(
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
        signedIn?.tube?.close(); tube.close(); http.close()
    }

    private companion object {
        const val TAG = "UnisonYouTube"
        const val EXPIRY_MARGIN_MS = 10 * 60_000L
        /** Le temps de demander à YouTube la vraie raison d'un échec. */
        const val AGE_CHECK_MS = 6_000L
        /** Sans compte, quand le choix automatique ne trouve aucun flux direct (vérifié en novembre 2026). */
        val ANONYMOUS_FALLBACK_CLIENTS = listOf("TVHTML5_SAMSUNG")
        /** Clients qui donnent un flux direct à un compte connecté (vérifié en novembre 2026). */
        val SIGNED_IN_CLIENTS = listOf("TVHTML5_DOWNGRADED", "TVHTML5_SAMSUNG", "WEB_CREATOR")
        const val AGE_UNVERIFIED_MESSAGE = "Ton compte YouTube n'a pas confirmé que tu es majeur"
        const val SIGNED_IN_FAILED_MESSAGE = "YouTube refuse ce titre même avec ton compte : reconnecte-le dans les réglages"
        const val DEFAULT_LIFETIME_MS = 5 * 3_600_000L
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        val RawJson = Json { ignoreUnknownKeys = true }
    }
}

private fun File.readOrNull(): String? = runCatching { takeIf { it.isFile }?.readText() }.getOrNull()

/** Écrit dans un fichier temporaire puis renomme : un fichier à moitié écrit n'est jamais relu. */
private fun File.writeAtomically(text: String) {
    runCatching {
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(this)
    }
}

package fr.lulu.unison.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Échec d'une requête à une source. */
sealed class AddonException(message: String) : IOException(message) {
    /** 404 : la source n'a pas ce titre (ou pas de manifest à cette adresse). Ce n'est pas une panne. */
    class NotFound : AddonException("Introuvable dans la source")
    /** Panne, délai dépassé, 5xx, 429 persistant : passager. */
    class Unavailable(reason: String) : AddonException(reason)
    /** La source refuse (4xx), répond hors format ou hors limites. */
    class Rejected(reason: String) : AddonException(reason)
}

/**
 * Client HTTP d'une source : GET uniquement, réponses JSON de taille bornée, délais courts, pas de cookie,
 * redirections vérifiées par [SourcePolicy] à chaque étape (jamais de HTTPS vers HTTP, jamais vers le réseau
 * local pour une source distante). Les adresses ne sont jamais journalisées.
 */
class AddonClient(
    base: OkHttpClient,
    private val rule: SourcePolicy.HostRule,
    private val userAgent: String,
) {
    private val http: OkHttpClient = base.newBuilder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .cache(null)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .addNetworkInterceptor(StreamGuard.interceptorFor(rule))
        .build()

    /**
     * GET de [url] et corps de la réponse (au plus [maxBytes] octets). Une 429 ou une 503 est retentée au plus
     * [retries] fois, après l'attente demandée par la source (bornée à [maxWaitMs]).
     */
    suspend fun get(url: HttpUrl, maxBytes: Long, retries: Int = 1, maxWaitMs: Long = 3_000, timeoutMs: Long = 15_000): String {
        if (!rule.allows(url)) throw AddonException.Rejected("Adresse refusée par la sécurité des sources")
        var attempt = 0
        while (true) {
            val request = Request.Builder().url(url).get()
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .build()
            val call = http.newCall(request)
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            val response = try {
                call.await()
            } catch (e: SourceRefusedException) {
                throw AddonException.Rejected(e.message ?: "Adresse refusée")
            } catch (e: IOException) {
                throw AddonException.Unavailable("Source injoignable")
            }
            val wait: Long = response.use { r ->
                when {
                    r.code == 429 || r.code == 503 -> {
                        if (attempt >= retries) throw AddonException.Unavailable("Source surchargée, réessaie plus tard")
                        attempt++
                        retryAfterMs(r.header("Retry-After"))?.coerceIn(250, maxWaitMs) ?: (500L shl (attempt - 1)).coerceAtMost(maxWaitMs)
                    }
                    r.code == 404 -> throw AddonException.NotFound()
                    r.code in 500..599 -> throw AddonException.Unavailable("La source est en panne (${r.code})")
                    r.code in 300..399 -> throw AddonException.Rejected("Redirection refusée")
                    r.code == 401 || r.code == 403 -> throw AddonException.Rejected("Accès refusé par la source (${r.code})")
                    !r.isSuccessful -> throw AddonException.Rejected("La source refuse la requête (${r.code})")
                    else -> return readLimited(r, maxBytes)
                }
            }
            delay(wait)
        }
    }

    private fun readLimited(r: Response, maxBytes: Long): String {
        val body = r.body
        if (body.contentLength() > maxBytes) throw AddonException.Rejected("Réponse trop volumineuse")
        val source = body.source()
        try {
            if (source.request(maxBytes + 1)) throw AddonException.Rejected("Réponse trop volumineuse")
            return source.buffer.readUtf8()
        } catch (e: AddonException) {
            throw e
        } catch (e: IOException) {
            throw AddonException.Unavailable("Réponse interrompue")
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        })
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 64L * 1024
        const val MAX_SEARCH_BYTES = 2L * 1024 * 1024
        const val MAX_STREAM_BYTES = 64L * 1024

        /** « Retry-After » en secondes (la forme date n'est pas lue). */
        fun retryAfterMs(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)
    }
}

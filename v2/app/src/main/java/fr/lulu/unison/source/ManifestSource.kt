package fr.lulu.unison.source

import android.util.Log
import fr.lulu.unison.model.Track
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Une source importée par l'adresse de son manifest (protocole décrit dans [AddonFormat]).
 * Les réponses sont gardées quelques minutes en mémoire ; les flux sont vérifiés par [SourcePolicy]
 * puis annoncés à [guard], qui contrôle chaque connexion du lecteur vers eux.
 */
class ManifestSource(
    val config: SourceConfig,
    private val policy: SourcePolicy,
    http: OkHttpClient,
    private val guard: StreamGuard,
    userAgent: String,
    private val now: () -> Long = System::currentTimeMillis,
    /** Appelé quand un manifest relu change ce que la source autorise (téléchargements, qualités). */
    private val onManifest: (AddonFormat.Manifest) -> Unit = {},
) : MusicSource {
    override val id = config.id
    override val displayName = config.name

    private val rule = policy.ruleFor(config.baseUrl) ?: error("Adresse de source refusée")
    private val base: HttpUrl = config.baseUrl.toHttpUrlOrNull() ?: error("Adresse de source invalide")
    private val client = AddonClient(http, rule, userAgent)

    private class Entry<T>(val value: T, val until: Long)
    private val searches = ConcurrentHashMap<String, Entry<List<AddonFormat.Row>>>()
    private val streams = ConcurrentHashMap<String, Entry<StreamInfo?>>()
    /** Flux portés directement par les lignes de recherche (sources sans `/stream`). */
    private val rowStreams = ConcurrentHashMap<String, AddonFormat.Row>()

    /** Qualité demandée : celle choisie, sinon celle par défaut de la source. */
    private val quality: String? get() = config.pickedQuality ?: config.defaults["quality"]

    private fun HttpUrl.Builder.withSettings(): HttpUrl.Builder = apply {
        config.defaults.forEach { (k, v) -> if (k != "quality") addQueryParameter(k, v) }
        quality?.let { addQueryParameter("quality", it) }
    }

    override suspend fun health(): SourceHealth = try {
        val text = client.get(base.newBuilder().addPathSegment("manifest.json").build(), AddonClient.MAX_MANIFEST_BYTES)
        when (val m = AddonFormat.parseManifest(text)) {
            is AddonFormat.Parsed.Invalid -> SourceHealth.Rejected(m.reason)
            is AddonFormat.Parsed.Ok -> {
                onManifest(m.value)
                if (m.value.proprietaryAuth) SourceHealth.Rejected(NOT_COMPATIBLE) else SourceHealth.Ok
            }
        }
    } catch (e: AddonException.NotFound) {
        SourceHealth.Rejected("Plus de manifest à cette adresse")
    } catch (e: AddonException.Rejected) {
        SourceHealth.Rejected(e.message ?: "Refusée")
    } catch (e: AddonException) {
        SourceHealth.Unreachable(e.message ?: "Injoignable")
    }

    override suspend fun search(query: String, limit: Int): List<Track> = rows(query).take(limit).map { it.track }

    /** Lignes de résultat de [query] (avec l'identifiant propre à la source). */
    suspend fun rows(query: String): List<AddonFormat.Row> {
        val q = query.trim().take(MAX_QUERY)
        if (q.isEmpty()) return emptyList()
        val key = "$q|$quality"
        searches[key]?.takeIf { it.until > now() }?.let { return it.value }
        val url = base.newBuilder().addPathSegment("search").addQueryParameter("q", q).withSettings().build()
        val rows = try {
            when (val r = AddonFormat.parseSearch(client.get(url, AddonClient.MAX_SEARCH_BYTES), config.id)) {
                is AddonFormat.Parsed.Ok -> r.value
                is AddonFormat.Parsed.Invalid -> throw AddonException.Rejected(r.reason)
            }
        } catch (e: AddonException.NotFound) {
            emptyList()
        }
        rows.forEach { if (it.streamUrl != null) rowStreams[it.trackId] = it }
        trim(rowStreams, 500)
        searches[key] = Entry(rows, now() + SEARCH_TTL_MS)
        trim(searches, 64)
        return rows
    }

    /**
     * Flux du titre [trackId] ; null quand la source ne l'a pas ou qu'il est refusé (chiffré, manifeste HLS/DASH,
     * adresse non sûre). Une panne lève [AddonException] : l'appelant passe alors à YouTube Music.
     */
    override suspend fun stream(trackId: String, request: StreamRequest): StreamInfo? {
        if (!AddonFormat.validTrackId(trackId)) return null
        val key = "$trackId|$quality"
        streams[key]?.takeIf { it.until > now() }?.let { return it.value }
        val parsed = if (config.streamEndpoint) {
            val url = base.newBuilder().addPathSegment("stream").addPathSegment(trackId).withSettings().build()
            try {
                when (val r = AddonFormat.parseStream(client.get(url, AddonClient.MAX_STREAM_BYTES, timeoutMs = STREAM_TIMEOUT_MS))) {
                    is AddonFormat.Parsed.Ok -> r.value
                    is AddonFormat.Parsed.Invalid -> throw AddonException.Rejected(r.reason)
                }
            } catch (e: AddonException.NotFound) {
                null
            }
        } else rowStreams[trackId]?.let { AddonFormat.stream(it.stream, it.streamUrl) }
        val info = parsed?.let { describe(trackId, it) }
        streams[key] = Entry(info, now() + if (info != null) STREAM_TTL_MS else MISS_TTL_MS)
        trim(streams, 128)
        return info
    }

    /** Vérifie la réponse de la source et la traduit en [StreamInfo]. */
    internal fun describe(trackId: String, s: AddonFormat.Stream): StreamInfo? {
        if (s.url == null) {
            if (s.error != null) Log.i(TAG, "la source n'a pas de flux pour ce titre")
            return null
        }
        if (s.encrypted) { Log.w(TAG, "flux chiffré (DRM) refusé"); return null }
        if (s.transport != Transport.PROGRESSIVE) { Log.i(TAG, "flux ${s.transport} non pris en charge"); return null }
        val url = policy.checkStream(s.url, rule) ?: run { Log.w(TAG, "adresse de flux refusée par la sécurité des sources"); return null }
        guard.allow(url.host, rule)
        val t = now()
        return StreamInfo(
            url = url.toString(),
            headers = s.headers,
            expiresAtMs = (s.expiresAtMs ?: (t + DEFAULT_LIFETIME_MS)).coerceIn(t, t + MAX_LIFETIME_MS),
            mimeType = s.mimeType,
            codec = s.codec,
            container = s.container,
            sampleRateHz = s.sampleRateHz,
            bitDepth = s.bitDepth,
            bitrateKbps = s.bitrateKbps,
            transport = s.transport,
            sourceId = config.id,
            cacheKey = "src:${config.id}:${quality.orEmpty()}:$trackId",
        )
    }

    override fun invalidate(trackId: String) {
        streams.keys.removeIf { it.startsWith("$trackId|") }
    }

    private fun <V> trim(map: ConcurrentHashMap<String, V>, max: Int) {
        if (map.size > max) map.keys.take(map.size - max).forEach { map.remove(it) }
    }

    companion object {
        private const val TAG = "UnisonSources"
        private const val MAX_QUERY = 200
        private const val SEARCH_TTL_MS = 10 * 60_000L
        private const val STREAM_TTL_MS = 5 * 60_000L
        private const val MISS_TTL_MS = 2 * 60_000L
        private const val DEFAULT_LIFETIME_MS = 30 * 60_000L
        private const val MAX_LIFETIME_MS = 6 * 3_600_000L
        private const val STREAM_TIMEOUT_MS = 10_000L
        const val NOT_COMPATIBLE = "Cette source exige l'authentification propre à une autre application : elle n'est pas compatible avec Unison"

        /** Ce que montre l'écran de confirmation avant l'ajout d'une source. */
        data class Inspection(
            val config: SourceConfig,
            val manifest: AddonFormat.Manifest,
            /** Résultat du test de santé (manifest lu, puis une recherche d'essai). */
            val health: SourceHealth,
        )

        sealed interface InspectResult {
            data class Ready(val inspection: Inspection) : InspectResult
            data class Refused(val reason: String) : InspectResult
        }

        /**
         * Lit le manifest à l'adresse [input], le valide et fait un test de santé (une recherche d'essai).
         * Rien n'est enregistré : c'est à l'utilisateur de confirmer.
         */
        suspend fun inspect(
            input: String,
            policy: SourcePolicy,
            http: OkHttpClient,
            guard: StreamGuard,
            userAgent: String,
            now: () -> Long = System::currentTimeMillis,
        ): InspectResult {
            val check = policy.checkBase(input)
            if (check is SourcePolicy.BaseCheck.Refused) return InspectResult.Refused(check.reason)
            check as SourcePolicy.BaseCheck.Ok
            val baseUrl = SourcePolicy.baseString(check.url)
            val client = AddonClient(http, check.rule, userAgent)
            val text = try {
                client.get(check.url.newBuilder().addPathSegment("manifest.json").build(), AddonClient.MAX_MANIFEST_BYTES)
            } catch (e: AddonException.NotFound) {
                return InspectResult.Refused("Aucun manifest à cette adresse (« /manifest.json » introuvable)")
            } catch (e: AddonException) {
                return InspectResult.Refused(e.message ?: "Source injoignable")
            }
            val manifest = when (val m = AddonFormat.parseManifest(text)) {
                is AddonFormat.Parsed.Invalid -> return InspectResult.Refused(m.reason)
                is AddonFormat.Parsed.Ok -> m.value
            }
            if (manifest.proprietaryAuth) return InspectResult.Refused(NOT_COMPATIBLE)
            val config = SourceConfig(
                id = SourceConfig.idFor(baseUrl),
                manifestId = manifest.id,
                name = manifest.name,
                version = manifest.version,
                baseUrl = baseUrl,
                allowDownloads = manifest.allowDownloads,
                qualityOptions = manifest.qualityOptions,
                defaults = manifest.defaults,
                streamEndpoint = manifest.hasStream,
                addedAt = now(),
            )
            val source = ManifestSource(config, policy, http, guard, userAgent, now)
            val health = try {
                source.rows(PROBE_QUERY)
                SourceHealth.Ok
            } catch (e: AddonException.Rejected) {
                SourceHealth.Rejected(e.message ?: "Recherche refusée")
            } catch (e: AddonException) {
                SourceHealth.Unreachable(e.message ?: "Recherche impossible")
            }
            return InspectResult.Ready(Inspection(config, manifest, health))
        }

        /** Recherche du test de santé : un mot courant, sans rien de personnel. */
        private const val PROBE_QUERY = "music"
    }
}

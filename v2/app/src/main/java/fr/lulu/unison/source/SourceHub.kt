package fr.lulu.unison.source

import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Les sources importées en état de marche : une [ManifestSource] par configuration enregistrée, recréée quand
 * la configuration change. Sert aussi l'écran d'ajout (inspection d'une adresse avant confirmation).
 */
class SourceHub(
    private val repository: SourceRepository,
    private val policy: SourcePolicy,
    private val http: OkHttpClient,
    val guard: StreamGuard,
    private val userAgent: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val instances = ConcurrentHashMap<String, ManifestSource>()

    /** La source [id] prête à l'emploi, ou null si elle n'existe plus (retirée, adresse devenue invalide). */
    fun source(id: String): ManifestSource? {
        val config = repository.get(id) ?: run { instances.remove(id); return null }
        instances[id]?.takeIf { it.config == config }?.let { return it }
        val created = runCatching {
            ManifestSource(config, policy, http, guard, userAgent, now) { m -> refresh(id, m) }
        }.getOrNull() ?: return null
        instances[id] = created
        return created
    }

    /** Un manifest relu : la politique de la source (téléchargements, qualités) suit ce qu'il annonce. */
    private fun refresh(id: String, m: AddonFormat.Manifest) {
        val current = repository.get(id) ?: return
        val picked = current.pickedQuality?.takeIf { p -> m.qualityOptions.any { it.value == p } }
        val updated = current.copy(
            name = m.name, version = m.version, allowDownloads = m.allowDownloads,
            qualityOptions = m.qualityOptions, defaults = m.defaults, pickedQuality = picked, streamEndpoint = m.hasStream,
        )
        if (updated != current) repository.update(id) { updated }
    }

    suspend fun health(id: String): SourceHealth = source(id)?.health() ?: SourceHealth.Rejected("Source inconnue")

    suspend fun inspect(input: String): ManifestSource.Companion.InspectResult =
        ManifestSource.inspect(input, policy, http, guard, userAgent, now)

    fun add(config: SourceConfig) = repository.put(config)

    fun remove(id: String) {
        repository.remove(id)
        instances.remove(id)
    }

    fun setQuality(id: String, value: String?) = repository.update(id) { it.copy(pickedQuality = value) }
}

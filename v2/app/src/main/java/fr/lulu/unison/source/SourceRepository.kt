package fr.lulu.unison.source

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Un choix de qualité proposé par la source (réglage « quality » de son manifest). */
@Serializable
data class QualityOption(val value: String, val label: String)

/**
 * Une source importée par l'utilisateur. Rien d'autre que des données : son adresse, ce que dit son manifest
 * et les réglages choisis. Aucun jeton ni identifiant n'est gardé (aucune authentification n'est prise en charge).
 */
@Serializable
data class SourceConfig(
    /** Identifiant stable, dérivé de l'adresse : la même source ajoutée sur deux téléphones a le même. */
    val id: String,
    /** Identifiant annoncé par le manifest (« org.exemple.source »). */
    val manifestId: String,
    val name: String,
    val version: String = "",
    /** Adresse de base, sans « /manifest.json » ni « / » final. */
    val baseUrl: String,
    /** La source accepte qu'on garde ses titres sur le téléphone. */
    val allowDownloads: Boolean = true,
    val qualityOptions: List<QualityOption> = emptyList(),
    /** Qualité choisie parmi [qualityOptions] ; null : celle par défaut de la source. */
    val pickedQuality: String? = null,
    /** Valeurs par défaut des réglages du manifest, renvoyées en paramètres de requête. */
    val defaults: Map<String, String> = emptyMap(),
    /** La source a une adresse `/stream` ; sinon les flux viennent des lignes de recherche. */
    val streamEndpoint: Boolean = true,
    val addedAt: Long = 0,
) {
    companion object {
        /** Identifiant de configuration : « s » + 10 caractères hexadécimaux de l'empreinte de l'adresse. */
        fun idFor(baseUrl: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(baseUrl.lowercase().toByteArray())
            return "s" + digest.take(5).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Sources importées, gardées dans un fichier JSON privé de l'application (pas de sauvegarde Android :
 * `allowBackup` est désactivé). Ne contient aucun secret.
 */
class SourceRepository(private val file: File?) {
    private val _sources = MutableStateFlow(load())
    val sources: StateFlow<List<SourceConfig>> = _sources.asStateFlow()

    fun get(id: String): SourceConfig? = _sources.value.firstOrNull { it.id == id }

    /** Ajoute la source, ou remplace celle de même identifiant (même adresse). */
    fun put(config: SourceConfig) {
        _sources.update { list -> list.filterNot { it.id == config.id } + config }
        save()
    }

    fun remove(id: String) {
        _sources.update { list -> list.filterNot { it.id == id } }
        save()
    }

    fun update(id: String, change: (SourceConfig) -> SourceConfig) {
        _sources.update { list -> list.map { if (it.id == id) change(it) else it } }
        save()
    }

    private fun load(): List<SourceConfig> {
        val f = file?.takeIf { it.exists() } ?: return emptyList()
        return runCatching { Store.decodeFromString(ListSerializer(SourceConfig.serializer()), f.readText()) }
            .onFailure { Log.w(TAG, "sources illisibles (${it::class.simpleName})") }
            .getOrDefault(emptyList())
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(Store.encodeToString(ListSerializer(SourceConfig.serializer()), _sources.value))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "sources non enregistrées (${it::class.simpleName})") }
    }

    private companion object {
        const val TAG = "UnisonSources"
        val Store = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

package fr.lulu.unison.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Dernier accueil reçu, gardé sur le téléphone : au lancement, il s'affiche tout de suite pendant que
 * YouTube Music envoie le nouveau (trois ou quatre allers-retours réseau sinon, devant un écran vide).
 */
class HomeCache(private val file: File) {
    @Serializable
    private data class Stored(val version: Int, val sections: List<Section>)

    suspend fun read(): List<Section>? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.isFile) return@runCatching null
            Store.decodeFromString(Stored.serializer(), file.readText()).takeIf { it.version == VERSION }?.sections
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    suspend fun write(sections: List<Section>) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(Store.encodeToString(Stored.serializer(), Stored(VERSION, sections)))
            tmp.renameTo(file)
        }
        Unit
    }

    private companion object {
        /** À changer quand le format des sections change : l'ancien fichier est alors ignoré. */
        const val VERSION = 1
        val Store = Json { ignoreUnknownKeys = true }
    }
}

/** Sections gardées avec leur date et ce dont elles ont été tirées (« À découvrir »). */
class SectionsCache(private val file: File) {
    @Serializable
    private data class Stored(val savedAt: Long, val tag: String, val sections: List<Section>, val version: Int = 0)

    data class Cached(val savedAt: Long, val tag: String, val sections: List<Section>)

    suspend fun read(): Cached? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.isFile) return@runCatching null
            Json { ignoreUnknownKeys = true }.decodeFromString(Stored.serializer(), file.readText())
                .takeIf { it.version == VERSION }?.let { Cached(it.savedAt, it.tag, it.sections) }
        }.getOrNull()
    }

    suspend fun write(sections: List<Section>, tag: String, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(Json.encodeToString(Stored.serializer(), Stored(now, tag, sections, VERSION)))
            tmp.renameTo(file)
        }
        Unit
    }

    private companion object {
        /** À changer quand le contenu change (2 : sans « Playlists pour toi ») : l'ancien fichier est ignoré. */
        const val VERSION = 2
    }
}

/** Liste de titres gardée avec sa date (la sélection « Pour toi », recalculée deux fois par jour au plus). */
class TrackListCache(private val file: File) {
    @Serializable
    private data class Stored(val savedAt: Long, val tracks: List<fr.lulu.unison.model.Track>, val tag: String = "")

    /** Liste gardée, sa date et l'étiquette donnée à l'écriture (ce dont elle a été tirée). */
    data class Cached(val savedAt: Long, val tracks: List<fr.lulu.unison.model.Track>, val tag: String)

    suspend fun read(): Cached? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.isFile) return@runCatching null
            Json { ignoreUnknownKeys = true }.decodeFromString(Stored.serializer(), file.readText()).let { Cached(it.savedAt, it.tracks, it.tag) }
        }.getOrNull()
    }

    suspend fun write(tracks: List<fr.lulu.unison.model.Track>, now: Long = System.currentTimeMillis(), tag: String = "") = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(Json.encodeToString(Stored.serializer(), Stored(now, tracks, tag)))
            tmp.renameTo(file)
        }
        Unit
    }
}

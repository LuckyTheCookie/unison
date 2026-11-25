package fr.lulu.unison.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * D'où vient la file lancée sur ce téléphone (une playlist, un album…) et quels titres elle contenait : un titre
 * joué ensuite est rattaché à ce contexte s'il en fait partie. Gardé sur disque pour une file restaurée.
 */
class NowPlayingContext(private val file: File) {
    @Serializable
    private data class Stored(val key: String, val kind: String, val title: String, val subtitle: String, val thumbnail: String, val ids: List<String>)

    @Volatile private var context: PlayContext? = null
    @Volatile private var ids: Set<String> = emptySet()
    @Volatile private var loaded = false

    /** Contexte du titre [trackId], s'il vient de la file lancée depuis un contexte. */
    suspend fun contextOf(trackId: String): PlayContext? {
        if (!loaded) load()
        return context?.takeIf { trackId in ids }
    }

    /** Nouvelle file : [ctx] est null pour une file lancée sans contexte (recherche, titre seul, mix). */
    suspend fun set(ctx: PlayContext?, trackIds: Collection<String>) {
        context = ctx
        ids = if (ctx == null) emptySet() else trackIds.toHashSet()
        loaded = true
        withContext(Dispatchers.IO) {
            runCatching {
                if (ctx == null) { file.delete(); return@runCatching }
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(Json.encodeToString(Stored.serializer(), Stored(ctx.key, ctx.kind, ctx.title, ctx.subtitle, ctx.thumbnail, ids.toList())))
                tmp.renameTo(file)
            }
        }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        runCatching {
            if (loaded || !file.isFile) return@runCatching
            val s = Json { ignoreUnknownKeys = true }.decodeFromString(Stored.serializer(), file.readText())
            context = PlayContext(s.key, s.kind, s.title, s.subtitle, s.thumbnail)
            ids = s.ids.toHashSet()
        }
        loaded = true
    }
}

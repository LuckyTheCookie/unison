package fr.lulu.unison.source

/** Le titre n'a pas de flux lisible (ni chez la source choisie, ni sur YouTube Music). */
class StreamUnavailableException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/** Manière dont le flux est servi. Seul le fichier progressif (une seule ressource HTTP) est lu pour l'instant. */
enum class Transport { PROGRESSIVE, HLS, DASH }

/** Ce que l'on demande à une source : sa meilleure qualité, ou un débit plafonné (économie de données). */
sealed interface StreamRequest {
    data object Best : StreamRequest
    data class Capped(val maxKbps: Int) : StreamRequest
}

/**
 * Un flux audio prêt à être lu, quelle que soit la source. L'URL est souvent signée et temporaire :
 * ne jamais la journaliser ([toString] la masque).
 *
 * Les champs de format ([codec], [sampleRateHz], [bitDepth], [bitrateKbps]) sont ce que la source annonce ;
 * le libellé de qualité du lecteur s'appuie d'abord sur ce que le décodeur reçoit réellement.
 */
class StreamInfo(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val expiresAtMs: Long,
    val mimeType: String? = null,
    /** flac, alac, opus, aac, mp3… en minuscules. */
    val codec: String? = null,
    /** webm, mp4, flac, ogg… en minuscules. */
    val container: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
    val transport: Transport = Transport.PROGRESSIVE,
    val loudnessDb: Double? = null,
    val contentLength: Long? = null,
    /** [SourceIds.YOUTUBE] ou l'identifiant d'une source importée. */
    val sourceId: String,
    /** Clé du cache de lecture : elle distingue la source et le format, pour ne jamais mêler deux fichiers. */
    val cacheKey: String,
    /** Format YouTube (itag), 0 pour une autre source. */
    val itag: Int = 0,
) {
    override fun toString() = "StreamInfo($sourceId, ${codec ?: mimeType}, ${redact(url)})"

    companion object {
        /** Adresse sans chemin ni paramètres, pour les journaux : seuls le schéma et l'hôte restent. */
        fun redact(url: String): String {
            val scheme = url.substringBefore("://", "")
            val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringAfter('@')
            return if (scheme.isEmpty() || host.isEmpty()) "***" else "$scheme://$host/***"
        }
    }
}

object SourceIds {
    /** YouTube Music : la source par défaut, toujours présente et jamais supprimable. */
    const val YOUTUBE = "yt"
}

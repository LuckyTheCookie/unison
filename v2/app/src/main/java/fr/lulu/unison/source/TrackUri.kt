package fr.lulu.unison.source

import fr.lulu.unison.model.Track
import java.net.URLDecoder
import java.net.URLEncoder

/** Ce que la résolution d'un flux sait d'un titre : son identifiant, et de quoi le retrouver ailleurs. */
data class TrackRef(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
) {
    companion object {
        fun of(t: Track) = TrackRef(t.id, t.title, t.artist, t.album, t.durationMs)
    }
}

/**
 * Adresse interne d'un titre, lue par la résolution au moment de lire ou de télécharger :
 * `unison://track?id=…&ti=…&ar=…&al=…&d=…`. Tout est encodé : un identifiant contenant « / », « ? » ou « :: »
 * (titres d'une source importée) ne peut pas casser l'adresse. L'ancienne forme `unison://track/<id>`,
 * gardée par les téléchargements en attente, reste comprise.
 */
object TrackUri {
    const val SCHEME = "unison"
    private const val PREFIX = "$SCHEME://track"

    fun build(t: Track): String = build(TrackRef.of(t))

    fun build(ref: TrackRef): String = buildString {
        append(PREFIX).append("?id=").append(enc(ref.id))
        if (ref.title.isNotEmpty()) append("&ti=").append(enc(ref.title.take(MAX_TEXT)))
        if (ref.artist.isNotEmpty()) append("&ar=").append(enc(ref.artist.take(MAX_TEXT)))
        if (ref.album.isNotEmpty()) append("&al=").append(enc(ref.album.take(MAX_TEXT)))
        if (ref.durationMs > 0) append("&d=").append(ref.durationMs)
    }

    /** Le titre désigné par [uri], ou null si ce n'est pas une adresse de titre. */
    fun parse(uri: String): TrackRef? {
        if (!uri.startsWith(PREFIX)) return null
        val rest = uri.substring(PREFIX.length)
        if (rest.startsWith("/")) {
            // Ancienne forme : l'identifiant YouTube en dernier segment du chemin.
            val id = rest.trim('/').substringBefore('?').substringAfterLast('/')
            return if (id.isEmpty()) null else TrackRef(dec(id))
        }
        if (!rest.startsWith("?")) return null
        val params = rest.substring(1).split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to dec(it.substring(i + 1))
        }.toMap()
        val id = params["id"]?.takeIf { it.isNotEmpty() } ?: return null
        return TrackRef(
            id = id,
            title = params["ti"].orEmpty(),
            artist = params["ar"].orEmpty(),
            album = params["al"].orEmpty(),
            durationMs = params["d"]?.toLongOrNull() ?: 0,
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private const val MAX_TEXT = 200
}

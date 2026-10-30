package fr.lulu.unison.model

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String = "",
    val cover: String = "",
    val coverSmall: String = "",
    val durationMs: Long = 0,
)

@Serializable
data class Member(
    val id: String,
    val name: String,
    val color: Int,
    val isHost: Boolean = false,
)

@Serializable
data class QueueItem(
    val uid: String,
    val track: Track,
    val addedBy: String,
)

/**
 * Où en est la lecture, exprimé dans l'horloge de l'hôte :
 * à l'instant [hostTimeMs], le titre [item] était à [positionMs].
 */
@Serializable
data class PlayAnchor(
    val item: QueueItem? = null,
    val positionMs: Long = 0,
    val hostTimeMs: Long = 0,
    val playing: Boolean = false,
) {
    fun positionAt(hostNow: Long): Long =
        if (playing) positionMs + (hostNow - hostTimeMs).coerceAtLeast(0) else positionMs
}

@Serializable
enum class TransportKind { LAN, NEARBY }

@Serializable
data class SessionSnapshot(
    val sessionId: String,
    val hostName: String,
    val transport: TransportKind,
    val members: List<Member> = emptyList(),
    val queue: List<QueueItem> = emptyList(),
    val history: List<QueueItem> = emptyList(),
    val anchor: PlayAnchor = PlayAnchor(),
    val version: Long = 0,
) {
    fun member(id: String): Member? = members.firstOrNull { it.id == id }
}

/** Contenu du QR code : unison://join?... */
data class JoinInfo(
    val sessionId: String,
    val secret: String,
    val hostName: String,
    val transport: TransportKind,
    val host: String? = null,
    val port: Int? = null,
) {
    fun toUri(): String = buildString {
        append("unison://join?s=").append(sessionId)
        append("&k=").append(secret)
        append("&n=").append(java.net.URLEncoder.encode(hostName, "UTF-8"))
        append("&t=").append(if (transport == TransportKind.LAN) "lan" else "nearby")
        if (host != null) append("&h=").append(host)
        if (port != null) append("&p=").append(port)
    }

    companion object {
        fun parse(raw: String): JoinInfo? {
            val text = raw.trim()
            if (!text.startsWith("unison://join?")) return null
            val params = text.substringAfter('?').split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val s = params["s"] ?: return null
            val k = params["k"] ?: return null
            val t = if (params["t"] == "nearby") TransportKind.NEARBY else TransportKind.LAN
            val h = params["h"]
            val p = params["p"]?.toIntOrNull()
            if (t == TransportKind.LAN && (h == null || p == null)) return null
            return JoinInfo(s, k, params["n"] ?: "Hôte", t, h, p)
        }
    }
}

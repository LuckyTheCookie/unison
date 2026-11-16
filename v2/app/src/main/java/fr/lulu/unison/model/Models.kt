package fr.lulu.unison.model

import kotlinx.serialization.Serializable

/** Un titre YouTube Music ; [id] est l'identifiant de la vidéo. */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val cover: String = "",
    val durationMs: Long = 0,
    val explicit: Boolean = false,
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
    /** Identifiant du membre, ou [AUTO] pour la radio automatique. */
    val addedBy: String,
) {
    companion object {
        const val AUTO = "auto"
    }
}

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
enum class TransportKind { LOCAL, LAN, NEARBY }

@Serializable
enum class RepeatMode { OFF, ALL, ONE }

/**
 * État complet du lecteur : `history` = titres déjà joués, `anchor.item` = titre en cours,
 * `queue` = titres à venir. Le même état sert seul et à plusieurs.
 */
@Serializable
data class SessionSnapshot(
    val sessionId: String = "",
    val hostName: String = "",
    val transport: TransportKind = TransportKind.LOCAL,
    val members: List<Member> = emptyList(),
    val queue: List<QueueItem> = emptyList(),
    val history: List<QueueItem> = emptyList(),
    val anchor: PlayAnchor = PlayAnchor(),
    val repeat: RepeatMode = RepeatMode.OFF,
    /** Radio automatique : la file se prolonge toute seule avec des titres proches. */
    val autoplay: Boolean = true,
    val version: Long = 0,
) {
    fun member(id: String): Member? = members.firstOrNull { it.id == id }

    /** Plusieurs téléphones écoutent : la lecture doit rester calée sur l'ancre. */
    val shared: Boolean get() = members.size > 1
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
        append("unison://join?v=2&s=").append(sessionId)
        append("&k=").append(secret)
        append("&n=").append(java.net.URLEncoder.encode(hostName, "UTF-8"))
        append("&t=").append(if (transport == TransportKind.LAN) "lan" else "nearby")
        if (host != null) append("&h=").append(host)
        if (port != null) append("&p=").append(port)
    }

    companion object {
        /** Version du protocole : une v1 (Deezer) et une v2 ne peuvent pas partager une session. */
        const val PROTOCOL = 2

        sealed interface Parsed {
            data class Ok(val info: JoinInfo) : Parsed
            data object Outdated : Parsed
            data object Invalid : Parsed
        }

        fun parse(raw: String): JoinInfo? = (inspect(raw) as? Parsed.Ok)?.info

        fun inspect(raw: String): Parsed {
            val text = raw.trim()
            if (!text.startsWith("unison://join?")) return Parsed.Invalid
            val params = text.substringAfter('?').split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val s = params["s"] ?: return Parsed.Invalid
            val k = params["k"] ?: return Parsed.Invalid
            if ((params["v"]?.toIntOrNull() ?: 1) != PROTOCOL) return Parsed.Outdated
            val t = if (params["t"] == "nearby") TransportKind.NEARBY else TransportKind.LAN
            val h = params["h"]
            val p = params["p"]?.toIntOrNull()
            if (t == TransportKind.LAN && (h == null || p == null)) return Parsed.Invalid
            return Parsed.Ok(JoinInfo(s, k, params["n"] ?: "Hôte", t, h, p))
        }
    }
}

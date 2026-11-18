package fr.lulu.unison.source

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Règles de sécurité des sources importées : une source n'est que des données HTTP, et ce qu'elle renvoie
 * (adresses de flux, en-têtes) est traité comme non fiable.
 *
 * - Adresse de la source et des flux en HTTPS. Seule exception : dans une version de développement
 *   ([debugLocal]), une source sur ce téléphone ou sur la machine hôte de l'émulateur (10.0.2.2) peut être en HTTP.
 * - Une source « distante » (nom de domaine ou adresse publique) ne peut pas faire ouvrir au lecteur une adresse
 *   privée, de bouclage ou locale au lien (protection contre la falsification de requêtes vers le réseau local).
 *   Une source « locale » (adresse IP privée écrite telle quelle) ne sert que des flux de ce même hôte.
 */
class SourcePolicy(private val debugLocal: Boolean) {

    /** Règle appliquée aux requêtes d'une source et de ses flux. */
    data class HostRule(
        /** Hôte de la source. */
        val host: String,
        /** La source est sur le réseau local (adresse IP privée écrite telle quelle, ou hôte de développement). */
        val local: Boolean,
        /** HTTP accepté (développement uniquement, et seulement vers [host]). */
        val allowHttp: Boolean,
    ) {
        /** L'adresse [url] peut être ouverte pour cette source (schéma et hôte ; l'adresse IP réelle est vérifiée à la connexion). */
        fun allows(url: HttpUrl): Boolean {
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
            val sameHost = url.host.equals(host, ignoreCase = true)
            when (url.scheme) {
                "https" -> Unit
                "http" -> if (!(allowHttp && sameHost)) return false
                else -> return false
            }
            if (local) return sameHost
            return !isPrivateHost(url.host)
        }

        /** L'adresse IP à laquelle on vient de se connecter est acceptable pour cette source. */
        fun allowsAddress(address: InetAddress, connectedHost: String): Boolean =
            if (local) connectedHost.equals(host, ignoreCase = true) else !isPrivateAddress(address)
    }

    sealed interface BaseCheck {
        data class Ok(val url: HttpUrl, val rule: HostRule) : BaseCheck
        data class Refused(val reason: String) : BaseCheck
    }

    /** Valide l'adresse saisie par l'utilisateur et la ramène à l'adresse de base de la source. */
    fun checkBase(input: String): BaseCheck {
        var text = input.trim()
        if (text.isEmpty()) return BaseCheck.Refused("Adresse vide")
        if (text.length > MAX_URL) return BaseCheck.Refused("Adresse trop longue")
        if (!text.contains("://")) text = "https://$text"
        val url = text.toHttpUrlOrNull() ?: return BaseCheck.Refused("Adresse invalide")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return BaseCheck.Refused("Adresse invalide : identifiants interdits dans l'adresse")
        val devHost = debugLocal && isDevHost(url.host)
        if (url.scheme != "https" && !devHost) return BaseCheck.Refused("La source doit être en HTTPS")
        if (isLoopbackOrLinkLocal(url.host) && !devHost) return BaseCheck.Refused("Adresse locale refusée")
        val base = normalize(url)
        val local = devHost || (isIpLiteral(url.host) && isPrivateHost(url.host))
        return BaseCheck.Ok(base, HostRule(base.host, local = local, allowHttp = devHost))
    }

    /** Règle d'une source déjà enregistrée. */
    fun ruleFor(baseUrl: String): HostRule? = (checkBase(baseUrl) as? BaseCheck.Ok)?.rule

    /** Valide l'adresse d'un flux renvoyée par la source ; null si elle est refusée. */
    fun checkStream(raw: String, rule: HostRule): HttpUrl? {
        if (raw.length > MAX_STREAM_URL) return null
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        return url.takeIf { rule.allows(it) }
    }

    companion object {
        const val MAX_URL = 300
        const val MAX_STREAM_URL = 4_096

        /** Retire « /manifest.json », la requête, le fragment et le « / » final. */
        fun normalize(url: HttpUrl): HttpUrl {
            val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
            if (segments.lastOrNull()?.equals("manifest.json", ignoreCase = true) == true) segments.removeAt(segments.size - 1)
            val b = url.newBuilder().query(null).fragment(null).encodedPath("/")
            segments.forEach { b.addPathSegment(it) }
            val built = b.build()
            return built
        }

        /** Adresse de base sous forme de texte, sans « / » final. */
        fun baseString(url: HttpUrl): String = url.toString().trimEnd('/')

        /** Hôtes de développement : ce téléphone, ou la machine qui fait tourner l'émulateur. */
        fun isDevHost(host: String) = host.equals("localhost", true) || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2"

        fun isIpLiteral(host: String): Boolean = ipv4(host) != null || host.contains(':')

        /** Hôte qui désigne le réseau local : adresse privée, de bouclage, locale au lien, ou nom réservé. */
        fun isPrivateHost(host: String): Boolean {
            val h = host.lowercase().trimEnd('.')
            if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
            ipv4(h)?.let { return isPrivateV4(it) }
            if (h.contains(':')) return runCatching { isPrivateAddress(InetAddress.getByName(h.trim('[', ']'))) }.getOrDefault(true)
            return false
        }

        private fun isLoopbackOrLinkLocal(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h.endsWith(".localhost")) return true
            ipv4(h)?.let { b -> return b[0] == 127 || (b[0] == 169 && b[1] == 254) || b[0] == 0 }
            if (h.contains(':')) return runCatching {
                val a = InetAddress.getByName(h.trim('[', ']'))
                a.isLoopbackAddress || a.isLinkLocalAddress || a.isAnyLocalAddress
            }.getOrDefault(true)
            return false
        }

        /** Adresse IP qui ne doit pas être visée par une source distante. */
        fun isPrivateAddress(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return true
            return when (a) {
                is Inet4Address -> isPrivateV4(a.address.map { it.toInt() and 0xFF })
                is Inet6Address -> {
                    val b = a.address.map { it.toInt() and 0xFF }
                    // fc00::/7 (adresses uniques locales) ; ::ffff:a.b.c.d (IPv4 embarquée).
                    (b[0] and 0xFE) == 0xFC ||
                        (b.subList(0, 10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF && isPrivateV4(b.subList(12, 16)))
                }
                else -> true
            }
        }

        private fun isPrivateV4(b: List<Int>): Boolean =
            b[0] == 10 || b[0] == 127 || b[0] == 0 ||
                (b[0] == 172 && b[1] in 16..31) ||
                (b[0] == 192 && b[1] == 168) ||
                (b[0] == 169 && b[1] == 254) ||
                (b[0] == 100 && b[1] in 64..127) ||
                b[0] >= 224

        private fun ipv4(host: String): List<Int>? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val nums = parts.map { p -> p.toIntOrNull()?.takeIf { p.isNotEmpty() && p.length <= 3 && it in 0..255 } ?: return null }
            return nums
        }
    }
}

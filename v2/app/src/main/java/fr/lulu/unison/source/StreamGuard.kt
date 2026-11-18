package fr.lulu.unison.source

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** Requête refusée par les règles de sécurité des sources. */
class SourceRefusedException(message: String) : IOException(message)

/**
 * Garde posée sur le client HTTP du lecteur et des téléchargements. Les flux YouTube passent sans changement ;
 * pour un flux donné par une source importée (hôte enregistré par [allow]), chaque étape de la requête,
 * redirections comprises, est vérifiée : schéma, hôte, puis adresse IP réellement jointe (ce qui couvre aussi
 * un nom de domaine qui pointerait vers le réseau local).
 */
class StreamGuard {
    private val rules = ConcurrentHashMap<String, SourcePolicy.HostRule>()

    /** Les flux de l'hôte [host] suivront [rule]. */
    fun allow(host: String, rule: SourcePolicy.HostRule) {
        rules[host.lowercase()] = rule
    }

    /** Intercepteur réseau : il voit chaque redirection et la connexion établie. */
    val interceptor = Interceptor { chain ->
        val origin = chain.call().request().url
        val rule = rules[origin.host.lowercase()] ?: return@Interceptor chain.proceed(chain.request())
        check(chain, rule)
    }

    companion object {
        /** Intercepteur réseau qui applique toujours [rule] (client d'une source). */
        fun interceptorFor(rule: SourcePolicy.HostRule) = Interceptor { chain -> check(chain, rule) }

        private fun check(chain: Interceptor.Chain, rule: SourcePolicy.HostRule): Response {
            val url = chain.request().url
            if (!rule.allows(url)) throw SourceRefusedException("Adresse refusée par la sécurité des sources")
            val address = (chain.connection()?.route()?.socketAddress as? InetSocketAddress)?.address
            if (address != null && !rule.allowsAddress(address, url.host)) {
                throw SourceRefusedException("Adresse du réseau local refusée")
            }
            return chain.proceed(chain.request())
        }
    }
}

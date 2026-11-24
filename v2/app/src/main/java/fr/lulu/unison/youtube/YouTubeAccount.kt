package fr.lulu.unison.youtube

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLEncoder

/**
 * Le compte YouTube de l'utilisateur, utilisé seulement pour les titres réservés aux adultes.
 * [cookie] ouvre une session sur YouTube : il reste sur ce téléphone (dossier exclu des sauvegardes) et
 * n'est jamais journalisé.
 */
@Serializable
data class YouTubeLogin(val account: String, val cookie: String, val savedAtMs: Long)

class YouTubeAccountStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(read())
    val login: StateFlow<YouTubeLogin?> = state.asStateFlow()

    private fun read(): YouTubeLogin? = runCatching {
        file.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(YouTubeLogin.serializer(), it) }
    }.getOrNull()?.takeIf { it.cookie.contains("SAPISID=") }

    suspend fun save(login: YouTubeLogin) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(YouTubeLogin.serializer(), login))
        tmp.renameTo(file)
        state.value = login
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        state.value = null
    }
}

/**
 * Connexion au compte Google par microG RE (le microG des applis patchées Morphe et ReVanced), sans que
 * Unison voie jamais le mot de passe : microG, déjà connecté, donne une adresse de connexion à usage unique
 * (« weblogin »), qui ouvre une session sur music.youtube.com.
 *
 * Unison se présente à microG comme YouTube Music (voir les `SPOOFED_PACKAGE_*` du manifeste), comme le font
 * les applis patchées : c'est ce qui autorise la connexion à YouTube. microG demande d'abord l'accord de
 * l'utilisateur.
 */
object MicroG {
    /** Type de compte de microG RE et de GmsCore (ReVanced). */
    const val ACCOUNT_TYPE = "app.revanced"
    private const val CONTINUE = "https://music.youtube.com/"
    private val SERVICE = "weblogin:continue=" + URLEncoder.encode(CONTINUE, "UTF-8")

    fun installed(context: Context): Boolean =
        runCatching { AccountManager.get(context).authenticatorTypes.any { it.type == ACCOUNT_TYPE } }.getOrDefault(false)

    /** Choix du compte (ou ajout d'un compte dans microG s'il n'y en a pas). */
    fun chooseAccountIntent(): Intent =
        AccountManager.newChooseAccountIntent(null, null, arrayOf(ACCOUNT_TYPE), null, null, null, null)

    /**
     * L'adresse de connexion à usage unique pour [account]. microG peut d'abord afficher sa demande
     * d'autorisation : elle s'ouvre par-dessus [activity].
     */
    suspend fun signInUrl(activity: Activity, account: Account): String = withContext(Dispatchers.IO) {
        val manager = AccountManager.get(activity)
        val result = manager.getAuthToken(account, SERVICE, Bundle(), activity, null, null).result
        val url = result.getString(AccountManager.KEY_AUTHTOKEN)
            ?: throw IllegalStateException(result.getString(AccountManager.KEY_ERROR_MESSAGE) ?: "microG n'a pas répondu")
        // L'adresse ne sert qu'une fois : on ne la laisse pas en cache.
        runCatching { manager.invalidateAuthToken(account.type, url) }
        if (!url.startsWith("https://") || url.contains("WILL_NOT_SIGN_IN")) throw IllegalStateException("Google a refusé la connexion")
        url
    }

    /** La session YouTube est-elle ouverte dans ces cookies ? */
    fun isSignedIn(cookie: String?): Boolean = cookie != null && cookie.contains("SAPISID=")
}

package fr.lulu.unison.ui.screens

import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.YouTubeSignIn
import fr.lulu.unison.ui.components.GlassButton
import fr.lulu.unison.ui.components.GlassPanel
import fr.lulu.unison.ui.components.Spinner
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type
import fr.lulu.unison.youtube.MicroG
import kotlinx.coroutines.delay

/** Réglages : le compte YouTube, connecté par microG RE, pour les titres réservés aux adultes. */
@Composable
fun YouTubeAccountPanel(vm: AppViewModel) {
    val context = LocalContext.current
    val login by vm.youtubeLogin.collectAsStateWithLifecycle()
    val signIn by vm.youtubeSignIn.collectAsStateWithLifecycle()
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val name = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: return@rememberLauncherForActivityResult
        val type = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) ?: MicroG.ACCOUNT_TYPE
        context.findActivity()?.let { vm.startYouTubeSignIn(it, Account(name, type)) }
    }

    GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        val current = login
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(current?.account ?: "Titres réservés aux adultes", style = Type.Row, color = Ink.Primary)
                Text(
                    when {
                        current != null -> "Connecté : sert seulement aux titres que YouTube réserve aux adultes"
                        else -> "YouTube ne les donne qu'à un compte connecté et majeur. La connexion passe par microG RE : " +
                            "Unison ne voit jamais ton mot de passe."
                    },
                    style = Type.Caption, color = Ink.Tertiary,
                )
            }
            if (signIn != YouTubeSignIn.Idle) {
                Spacer(Modifier.width(12.dp))
                Spinner(size = 22.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            current != null -> GlassButton("Déconnecter", vm::signOutYouTube, Modifier.fillMaxWidth())
            !vm.microGInstalled() -> Text(
                "Installe microG RE (celui des applis Morphe) et connecte-y ton compte Google pour activer cette option.",
                style = Type.Caption, color = Ink.Secondary,
            )
            else -> GlassButton(
                "Se connecter avec microG",
                { runCatching { chooser.launch(MicroG.chooseAccountIntent()) } },
                Modifier.fillMaxWidth(),
                enabled = signIn == YouTubeSignIn.Idle,
            )
        }
    }

    (signIn as? YouTubeSignIn.OpenSession)?.let { open ->
        SessionOpener(open.url) { cookie -> vm.finishYouTubeSignIn(open.account, cookie) }
    }
}

/**
 * Ouvre l'adresse de connexion donnée par microG dans une page invisible : Google la redirige vers
 * music.youtube.com, qui y dépose les cookies de session. On les garde dès qu'ils sont là ; null si rien
 * n'arrive à temps.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SessionOpener(url: String, onDone: (String?) -> Unit) {
    val done by rememberUpdatedState(onDone)
    val cookies = CookieManager.getInstance()
    LaunchedEffect(url) {
        val deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(400)
            val cookie = cookies.getCookie("https://music.youtube.com")
            if (MicroG.isSignedIn(cookie)) {
                // Le temps que les derniers cookies de la redirection soient écrits.
                delay(800)
                cookies.flush()
                return@LaunchedEffect done(cookies.getCookie("https://music.youtube.com"))
            }
        }
        done(null)
    }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                cookies.setAcceptCookie(true)
                cookies.setAcceptThirdPartyCookies(this, true)
                // Partir d'une page vierge : pas de reste d'un ancien compte.
                cookies.removeAllCookies { loadUrl(url) }
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
        modifier = Modifier.size(1.dp).alpha(0f),
    )
}

private const val SESSION_TIMEOUT_MS = 45_000L

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

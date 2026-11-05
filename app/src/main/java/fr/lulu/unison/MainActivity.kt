package fr.lulu.unison

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.session.LinkStatus
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.Busy
import fr.lulu.unison.ui.components.AmbientBackground
import fr.lulu.unison.ui.components.DefaultAmbient
import fr.lulu.unison.ui.components.UnisonLogo
import fr.lulu.unison.ui.components.rememberArtworkColors
import fr.lulu.unison.ui.glass.GlassButton
import fr.lulu.unison.ui.glass.GlassStyle
import fr.lulu.unison.ui.glass.GlassSurface
import fr.lulu.unison.ui.glass.PrimaryButton
import fr.lulu.unison.ui.glass.ProvideBackdrop
import fr.lulu.unison.ui.screens.HomeScreen
import fr.lulu.unison.ui.screens.InviteScreen
import fr.lulu.unison.ui.screens.OnboardingScreen
import fr.lulu.unison.ui.screens.ScannerScreen
import fr.lulu.unison.ui.screens.SearchScreen
import fr.lulu.unison.ui.screens.SessionScreen
import fr.lulu.unison.ui.screens.UpdateCard
import fr.lulu.unison.ui.screens.isActionable
import fr.lulu.unison.update.UpdateState
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type
import fr.lulu.unison.ui.theme.UnisonTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val pendingJoin = MutableStateFlow<JoinInfo?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val theme by vm.theme.collectAsState()
            UnisonTheme(theme) {
                val join by pendingJoin.collectAsState()
                AppRoot(vm, join, onJoinConsumed = { pendingJoin.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        JoinInfo.parse(data)?.let { pendingJoin.value = it }
    }
}

private enum class Overlay { None, Search, Invite, Scanner, Profile, Lyrics, Settings }

private val nearbyPermissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.NEARBY_WIFI_DEVICES,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

@Composable
private fun AppRoot(vm: AppViewModel, pendingJoin: JoinInfo?, onJoinConsumed: () -> Unit) {
    val profile by vm.profile.collectAsState()
    val active by vm.active.collectAsState()
    val deezer by vm.deezerState.collectAsState()
    val busy by vm.busy.collectAsState()
    val granted by vm.listenerGranted.collectAsState()
    val listenHere by vm.listenHere.collectAsState()
    var overlay by rememberSaveable { mutableStateOf(Overlay.None) }
    val update by vm.updateState.collectAsState()
    var updateDismissed by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    // Autorisations : notifications au démarrage, Nearby à la demande.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    var afterNearby by remember { mutableStateOf<(() -> Unit)?>(null) }
    val nearbyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterNearby?.invoke(); afterNearby = null
    }
    fun withTransportPermissions(kind: TransportKind, action: () -> Unit) {
        when {
            kind == TransportKind.NEARBY -> { afterNearby = action; nearbyLauncher.launch(nearbyPermissions) }
            android.os.Build.VERSION.SDK_INT >= 37 -> { afterNearby = action; nearbyLauncher.launch(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK)) }
            else -> action()
        }
    }

    LaunchedEffect(pendingJoin, profile) {
        val info = pendingJoin ?: return@LaunchedEffect
        if (profile?.isComplete != true) return@LaunchedEffect
        onJoinConsumed()
        overlay = Overlay.None
        withTransportPermissions(info.transport) { vm.join(info) }
    }

    val session = active
    val snapshot = session?.snapshot?.collectAsState()?.value
    val status = session?.status?.collectAsState()?.value
    val colors = rememberArtworkColors(snapshot?.anchor?.item?.track?.cover)

    Box(Modifier.fillMaxSize().background(Ink.Bg)) {
        val p = profile
        val screen = when {
            p == null -> "loading"
            !p.isComplete || overlay == Overlay.Profile -> "onboarding"
            session != null && snapshot != null -> "session"
            else -> "home"
        }
        AnimatedContent(
            screen, label = "screen",
            transitionSpec = { (fadeIn(tween(450)) + scaleIn(initialScale = 0.96f)) togetherWith (fadeOut(tween(250)) + scaleOut(targetScale = 1.02f)) },
        ) { s ->
            when (s) {
                "loading" -> Box(Modifier.fillMaxSize())
                "onboarding" -> Backdropped(DefaultAmbient, null) {
                    OnboardingScreen(p) { name, color -> vm.saveProfile(name, color); overlay = Overlay.None }
                }
                "home" -> Backdropped(DefaultAmbient, null) {
                    if (p != null) HomeScreen(
                        profile = p, deezer = deezer, listenerGranted = granted,
                        onCreate = { kind -> withTransportPermissions(kind) { vm.create(kind) } },
                        onScan = { overlay = Overlay.Scanner },
                        onEditProfile = { overlay = Overlay.Profile },
                        onSettings = { overlay = Overlay.Settings },
                    )
                }
                "session" -> if (session != null && snapshot != null) SessionScreen(
                    handle = session, snapshot = snapshot, status = status ?: LinkStatus.Connected,
                    deezer = deezer, listenHere = listenHere, sync = vm.sessions.syncStatus?.collectAsState()?.value,
                    onSearch = { vm.resetSearch(); overlay = Overlay.Search },
                    onInvite = { overlay = Overlay.Invite },
                    onOpenDeezer = { vm.deezer.openDeezer() },
                    onToggleListen = vm::setListenHere,
                    onLeave = { vm.leave() },
                    onLyrics = { overlay = Overlay.Lyrics },
                    onSettings = { overlay = Overlay.Settings },
                )
            }
        }

        // Feuilles plein écran
        AnimatedVisibility(
            overlay == Overlay.Search && session != null,
            enter = slideInVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { it / 3 } + fadeIn(),
            exit = slideOutVertically { it / 3 } + fadeOut(),
        ) {
            BackHandler { overlay = Overlay.None }
            val query by vm.query.collectAsState()
            val results by vm.results.collectAsState()
            val searching by vm.searching.collectAsState()
            val err by vm.searchError.collectAsState()
            val added by vm.added.collectAsState()
            Backdropped(colors, snapshot?.anchor?.item?.track?.cover, dim = 0.55f) {
                SearchScreen(query, results, searching, err, added, vm::setQuery, { t, next -> vm.addTrack(t, next) }) { overlay = Overlay.None }
            }
        }
        AnimatedVisibility(
            overlay == Overlay.Invite && session != null && snapshot != null,
            enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut(),
        ) {
            BackHandler { overlay = Overlay.None }
            if (session != null && snapshot != null) Backdropped(colors, snapshot.anchor.item?.track?.cover, dim = 0.35f) {
                InviteScreen(session, snapshot) { overlay = Overlay.None }
            }
        }
        AnimatedVisibility(
            overlay == Overlay.Lyrics && session != null && snapshot != null,
            enter = slideInVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            BackHandler { overlay = Overlay.None }
            val lyrics by vm.lyrics.collectAsState()
            if (session != null && snapshot != null) Backdropped(colors, snapshot.anchor.item?.track?.cover, dim = 0.30f) {
                fr.lulu.unison.ui.screens.LyricsScreen(session, snapshot, lyrics) { overlay = Overlay.None }
            }
        }
        AnimatedVisibility(
            overlay == Overlay.Settings && profile != null,
            enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut(),
        ) {
            BackHandler { overlay = Overlay.None }
            val theme by vm.theme.collectAsState()
            profile?.let { p ->
                Backdropped(colors, snapshot?.anchor?.item?.track?.cover, dim = 0.35f) {
                    fr.lulu.unison.ui.screens.SettingsScreen(
                        style = theme, profile = p, onStyle = vm::setTheme,
                        onEditProfile = { overlay = Overlay.Profile },
                        update = update, onCheckUpdate = { updateDismissed = false; vm.updates.check(manual = true) },
                        onClose = { overlay = Overlay.None },
                    )
                }
            }
        }
        AnimatedVisibility(overlay == Overlay.Scanner, enter = fadeIn(), exit = fadeOut()) {
            BackHandler { overlay = Overlay.None }
            // L'aperçu caméra ne peut pas être réfracté : verre givré sans backdrop
            // (et surtout pas un backdrop qui contiendrait le verre lui-même).
            ProvideBackdrop(emptyBackdrop()) {
                ScannerScreen(
                    onResult = { info -> overlay = Overlay.None; withTransportPermissions(info.transport) { vm.join(info) } },
                    onClose = { overlay = Overlay.None },
                )
            }
        }
        if (overlay == Overlay.Profile) BackHandler { overlay = Overlay.None }

        // Mise à jour : jamais en pleine session, sauf si on l'a demandée depuis les réglages
        AnimatedVisibility(
            update.isActionable() && (!updateDismissed || update !is UpdateState.Available) && (session == null || overlay == Overlay.Settings),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Backdropped(DefaultAmbient, null, dim = 0.5f) {
                UpdateCard(
                    state = update,
                    onDownload = vm.updates::download,
                    onGrantPermission = { context.startActivity(vm.updates.permissionIntent()) },
                    onLater = { updateDismissed = true; vm.updates.dismiss() },
                    onCancel = vm.updates::cancelDownload,
                )
            }
        }

        // Connexion / création en cours, ou erreur
        AnimatedVisibility(busy !is Busy.Idle, enter = fadeIn(), exit = fadeOut()) {
            Backdropped(DefaultAmbient, null, dim = 0.5f) { BusyCard(busy, onDismiss = vm::dismissBusy) }
        }
    }
}

/** Fond ambiant + backdrop pour le verre des écrans qui n'en ont pas déjà. */
@Composable
private fun Backdropped(colors: List<Color>, art: String?, dim: Float = 0f, content: @Composable () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            AmbientBackground(colors, art, Modifier.fillMaxSize())
            if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        }
        ProvideBackdrop(backdrop, content)
    }
}

@Composable
private fun BusyCard(busy: Busy, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedRectangle(34.dp), style = GlassStyle.Thick) {
            Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (busy) {
                    is Busy.Failed -> {
                        Text("Oups", style = Type.Title, color = Ink.Primary)
                        Spacer(Modifier.height(8.dp))
                        Text(busy.message, style = Type.Body, color = Ink.Secondary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(22.dp))
                        Row {
                            GlassButton(onClick = onDismiss, style = GlassStyle.Clear) { Text("Fermer", style = Type.Headline, color = Ink.Primary) }
                            if (busy.retry != null) {
                                Spacer(Modifier.width(12.dp))
                                PrimaryButton("Réessayer", onClick = { busy.retry.invoke() })
                            }
                        }
                    }
                    else -> {
                        UnisonLogo(84.dp)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            when (busy) {
                                is Busy.Creating -> if (busy.kind == TransportKind.LAN) "Création de la session…" else "Ouverture du mode sans Wi-Fi…"
                                is Busy.Joining -> "Connexion à ${busy.hostName}…"
                                else -> ""
                            },
                            style = Type.Headline, color = Ink.Primary, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        fr.lulu.unison.ui.components.LoadingSpinner(28.dp)
                        Spacer(Modifier.height(16.dp))
                        GlassButton(onClick = onDismiss, style = GlassStyle.Clear) { Text("Annuler", style = Type.Callout, color = Ink.Secondary) }
                    }
                }
            }
        }
    }
}

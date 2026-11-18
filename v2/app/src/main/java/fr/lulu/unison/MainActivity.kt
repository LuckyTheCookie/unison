package fr.lulu.unison

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.Track
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.LocalBottomInset
import fr.lulu.unison.ui.LocalDownloads
import fr.lulu.unison.ui.LocalNav
import fr.lulu.unison.ui.Nav
import fr.lulu.unison.ui.PredictiveSheet
import fr.lulu.unison.ui.components.Cover
import fr.lulu.unison.ui.components.Glyph
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.Spinner
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.kawarp.KawarpBackground
import fr.lulu.unison.ui.screens.ArtistScreen
import fr.lulu.unison.ui.screens.BrowseScreen
import fr.lulu.unison.ui.screens.CollectionScreen
import fr.lulu.unison.ui.screens.DownloadsScreen
import fr.lulu.unison.ui.screens.FavoritesScreen
import fr.lulu.unison.ui.screens.HistoryScreen
import fr.lulu.unison.ui.screens.HomeScreen
import fr.lulu.unison.ui.screens.ImportScreen
import fr.lulu.unison.ui.screens.LibraryScreen
import fr.lulu.unison.ui.screens.PlayerPage
import fr.lulu.unison.ui.screens.PlayerScreen
import fr.lulu.unison.ui.screens.PlaylistScreen
import fr.lulu.unison.ui.screens.ScannerScreen
import fr.lulu.unison.ui.screens.SearchScreen
import fr.lulu.unison.ui.screens.SettingsScreen
import fr.lulu.unison.ui.screens.TogetherScreen
import fr.lulu.unison.ui.screens.TrackMenu
import fr.lulu.unison.ui.screens.UpdateCard
import fr.lulu.unison.ui.screens.isActionable
import fr.lulu.unison.ui.screens.rememberPosition
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Motion
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type
import fr.lulu.unison.ui.theme.UnisonTheme
import fr.lulu.unison.youtube.BrowseRef

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** Lien reçu de l'extérieur (QR, `unison://`, partage), en attente de traitement. */
    private var incoming by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) read(intent)
        setContent { UnisonTheme { App(vm, incoming) { incoming = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    private fun read(intent: Intent?) {
        incoming = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
    }
}

private val nearbyPermissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.NEARBY_WIFI_DEVICES,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private enum class Tab(val route: String, val label: String, val line: Int, val fill: Int) {
    Home("home", "Accueil", R.drawable.ic_home_line, R.drawable.ic_home_fill),
    Search("search", "Recherche", R.drawable.ic_search_line, R.drawable.ic_search_fill),
    Library("library", "Bibliothèque", R.drawable.ic_library_line, R.drawable.ic_library_fill),
}

/** Verre posé sur le contenu qui défile : flou de ce qu'il y a derrière, voile sombre, liseré. */
private fun Modifier.glassBar(backdrop: Backdrop, shape: Shape): Modifier = this
    .drawPlainBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = { vibrancy(); blur(24.dp.toPx()) },
        onDrawSurface = { drawRect(Ink.Glass) },
    )
    .border(0.5.dp, Ink.Hairline, shape)

@Composable
private fun App(vm: AppViewModel, incoming: String?, consumeIncoming: () -> Unit) {
    val navController = rememberNavController()
    val snap by vm.snapshot.collectAsState()
    val settings by vm.settings.collectAsState()
    val update by vm.updateState.collectAsState()
    val message by vm.message.collectAsState()
    val downloads by vm.downloadStatus.collectAsState()
    val current = snap.anchor.item

    var playerOpen by remember { mutableStateOf(false) }
    var playerPage by remember { mutableStateOf(PlayerPage.Art) }
    var togetherOpen by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var browseTarget by remember { mutableStateOf<Pair<BrowseRef, String>?>(null) }

    // Autorisations : la notification de lecture au premier lancement, le réseau local au moment d'écouter ensemble.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    var afterPermissions by remember { mutableStateOf<(() -> Unit)?>(null) }
    val transportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermissions?.invoke(); afterPermissions = null
    }
    fun withTransportPermissions(kind: TransportKind, action: () -> Unit) {
        when {
            kind == TransportKind.NEARBY -> { afterPermissions = action; transportLauncher.launch(nearbyPermissions) }
            Build.VERSION.SDK_INT >= 37 -> { afterPermissions = action; transportLauncher.launch(arrayOf("android.permission.ACCESS_LOCAL_NETWORK")) }
            else -> action()
        }
    }
    fun join(raw: String): Boolean {
        val info = JoinInfo.parse(raw) ?: return false
        scanning = false; togetherOpen = true
        withTransportPermissions(info.transport) { vm.join(info) }
        return true
    }

    LaunchedEffect(incoming) {
        val text = incoming ?: return@LaunchedEffect
        consumeIncoming()
        if (!join(text) && !vm.handleIncoming(text)) vm.toast("Lien non reconnu")
        else if (JoinInfo.inspect(text) is JoinInfo.Companion.Parsed.Outdated) togetherOpen = true
    }

    val nav = remember(navController) {
        Nav(
            back = { navController.popBackStack() },
            collection = { id -> navController.navigate("collection/${Uri.encode(id)}") },
            artist = { id -> navController.navigate("artist/${Uri.encode(id)}") },
            browse = { ref, title -> browseTarget = ref to title; navController.navigate("browse") },
            favorites = { navController.navigate("favorites") },
            history = { navController.navigate("history") },
            playlist = { pid -> navController.navigate("playlist/$pid") },
            settings = { navController.navigate("settings") },
            downloads = { navController.navigate("downloads") },
            import = { navController.navigate("import") },
            together = { togetherOpen = true },
            trackMenu = { menuTrack = it },
        )
    }

    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = navBar + 76.dp + if (current != null) 72.dp else 0.dp
    val backdrop = rememberLayerBackdrop()

    CompositionLocalProvider(LocalDownloads provides downloads) {
        Box(Modifier.fillMaxSize().background(Ink.Canvas)) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                KawarpBackground(current?.track?.cover, Modifier.fillMaxSize(), dim = 0.6f, animated = settings.animatedBackground && !playerOpen)
                CompositionLocalProvider(LocalNav provides nav, LocalBottomInset provides bottomInset) {
                    NavHost(
                        navController, startDestination = Tab.Home.route, modifier = Modifier.fillMaxSize(),
                        enterTransition = { fadeIn(tween(Motion.Page, easing = Motion.Ease)) + slideInVertically(tween(Motion.Page, easing = Motion.Ease)) { 24 } },
                        exitTransition = { fadeOut(tween(Motion.Fast)) },
                        // Retour (y compris prédictif, que le geste fait avancer) : la page quitte en rétrécissant,
                        // celle d'en dessous revient en grandissant légèrement.
                        popEnterTransition = { fadeIn(tween(Motion.Page, easing = Motion.Ease)) + scaleIn(tween(Motion.Page, easing = Motion.Ease), initialScale = 0.96f) },
                        popExitTransition = { fadeOut(tween(Motion.Page)) + scaleOut(tween(Motion.Page), targetScale = 0.9f) },
                    ) {
                        composable(Tab.Home.route) { HomeScreen(vm) }
                        composable(Tab.Search.route) { SearchScreen(vm) }
                        composable(Tab.Library.route) { LibraryScreen(vm) }
                        composable("collection/{id}") { entry -> CollectionScreen(entry.arguments?.getString("id").orEmpty(), vm) }
                        composable("artist/{id}") { entry -> ArtistScreen(entry.arguments?.getString("id").orEmpty(), vm) }
                        composable("browse") { browseTarget?.let { (ref, title) -> BrowseScreen(ref, title, vm) } }
                        composable("favorites") { FavoritesScreen(vm) }
                        composable("history") { HistoryScreen(vm) }
                        composable("playlist/{pid}") { entry -> PlaylistScreen(entry.arguments?.getString("pid")?.toLongOrNull() ?: 0, vm) }
                        composable("settings") { SettingsScreen(vm) }
                        composable("downloads") { DownloadsScreen(vm) }
                        composable("import") { ImportScreen(vm) }
                    }
                }
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp).padding(bottom = 8.dp)) {
                AnimatedVisibility(message != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            message.orEmpty(), style = Type.Label, color = Ink.ButtonText,
                            modifier = Modifier.clip(Radius.Pill).background(Ink.ButtonBg).padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                if (current != null) {
                    MiniPlayer(vm, current.track, snap.anchor.playing, backdrop, onOpen = { playerPage = PlayerPage.Art; playerOpen = true })
                    Spacer(Modifier.height(8.dp))
                }
                BottomBar(navController, backdrop)
            }

            CompositionLocalProvider(LocalNav provides nav) {
                // Le geste de retour ferme le lecteur depuis la pochette ; depuis les paroles ou la file, il y ramène.
                PredictiveSheet(
                    playerOpen && current != null,
                    backEnabled = playerPage == PlayerPage.Art && !togetherOpen && !scanning,
                    onBack = { playerOpen = false },
                ) { gesturing ->
                    PlayerScreen(vm, playerPage, onPage = { playerPage = it }, onClose = { playerOpen = false }, gesturing = gesturing)
                }
                BackHandler(playerOpen && playerPage != PlayerPage.Art && !togetherOpen && !scanning) { playerPage = PlayerPage.Art }
                PredictiveSheet(togetherOpen, backEnabled = !scanning, onBack = { togetherOpen = false }) {
                    TogetherScreen(
                        vm,
                        onShare = { kind -> withTransportPermissions(kind) { vm.share(kind) } },
                        onScan = { scanning = true },
                        onClose = { togetherOpen = false },
                    )
                }
            }
            if (scanning) ScannerScreen(onResult = { raw -> join(raw) }, onClose = { scanning = false })
            menuTrack?.let { TrackMenu(it, vm, onDismiss = { menuTrack = null }) }

            if (update.isActionable()) {
                val context = androidx.compose.ui.platform.LocalContext.current
                UpdateCard(
                    update,
                    onDownload = vm.updates::download,
                    onGrantPermission = { context.startActivity(vm.updates.permissionIntent()) },
                    onLater = vm.updates::dismiss,
                    onCancel = vm.updates::cancelDownload,
                )
            }
        }
    }

    BackHandler(scanning) { scanning = false }
    LaunchedEffect(current == null) { if (current == null) playerOpen = false }
}

@Composable
private fun MiniPlayer(vm: AppViewModel, track: Track, playing: Boolean, backdrop: Backdrop, onOpen: () -> Unit) {
    val player by vm.player.collectAsState()
    val position = rememberPosition(vm, 500)
    val duration = vm.durationMs()
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(Radius.Art).glassBar(backdrop, Radius.Art)
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total < -60f) onOpen() }) { _, dy -> total += dy }
            }
            .pressable(scale = 0.985f, onClick = onOpen),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 9.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(track.cover, 46.dp, shape = Radius.Control)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = Type.Row, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(player.error ?: track.artist, style = Type.Body, color = if (player.error != null) Ink.Danger else Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (player.buffering && playing) Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) { Spinner(size = 22.dp) }
            else IconBtn(if (playing) R.drawable.ic_pause_fill else R.drawable.ic_play_fill, if (playing) "Pause" else "Lecture", vm::togglePlay, iconSize = 26.dp)
            IconBtn(R.drawable.ic_next_fill, "Suivant", { vm.send(Cmd.Skip) }, iconSize = 24.dp)
        }
        if (duration > 0) {
            Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp).fillMaxWidth().height(2.dp).background(Ink.FillStrong)) {
                Box(Modifier.fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f)).height(2.dp).background(Ink.Primary))
            }
        }
    }
}

@Composable
private fun BottomBar(navController: NavHostController, backdrop: Backdrop) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Row(Modifier.fillMaxWidth().height(60.dp).clip(Radius.Panel).glassBar(backdrop, Radius.Panel), verticalAlignment = Alignment.CenterVertically) {
        Tab.entries.forEach { tab ->
            val selected = route == tab.route
            Column(
                Modifier.weight(1f).pressable(scale = 0.92f) {
                    if (!selected) navController.navigate(tab.route) {
                        popUpTo(Tab.Home.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    } else Unit
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Glyph(if (selected) tab.fill else tab.line, tint = if (selected) Ink.Primary else Ink.Tertiary, size = 24.dp)
                Text(tab.label, style = Type.Caption, color = if (selected) Ink.Primary else Ink.Tertiary, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

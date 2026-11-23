package fr.lulu.unison

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import fr.lulu.unison.ui.components.PlayPauseButton
import kotlinx.coroutines.launch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import fr.lulu.unison.model.JoinInfo
import kotlinx.coroutines.flow.first
import fr.lulu.unison.model.Track
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.DownloadStatuses
import fr.lulu.unison.ui.LocalBottomInset
import fr.lulu.unison.ui.LocalDownloads
import fr.lulu.unison.ui.LocalNav
import fr.lulu.unison.ui.LocalReduceMotion
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
import fr.lulu.unison.ui.screens.rememberDuration
import fr.lulu.unison.ui.screens.rememberPositionState
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
    val current by vm.currentItem.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val downloads = remember { DownloadStatuses() }
    LaunchedEffect(downloads) { vm.downloadStatus.collect(downloads::update) }

    // Gardés à la rotation et au retour après la mort du processus : on retrouve le lecteur là où on l'a laissé.
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var playerPage by rememberSaveable { mutableStateOf(PlayerPage.Art) }
    var togetherOpen by rememberSaveable { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }

    // Autorisations : la notification au premier titre lancé (c'est là qu'elle sert), le réseau local au moment
    // d'écouter ensemble.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.playing.first { it }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
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
            // Tout passe par la route : la page se reconstruit seule après la mort du processus.
            browse = { ref, title ->
                navController.navigate(
                    "browse/${Uri.encode(ref.browseId)}?params=${Uri.encode(ref.params.orEmpty())}" +
                        "&type=${Uri.encode(ref.pageType)}&title=${Uri.encode(title)}",
                )
            },
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

    CompositionLocalProvider(LocalDownloads provides downloads, LocalReduceMotion provides settings.reduceMotion) {
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
                        composable(
                            "browse/{id}?params={params}&type={type}&title={title}",
                            arguments = listOf(
                                navArgument("params") { defaultValue = "" },
                                navArgument("type") { defaultValue = "" },
                                navArgument("title") { defaultValue = "" },
                            ),
                        ) { entry ->
                            val args = entry.arguments
                            val ref = BrowseRef(
                                browseId = args?.getString("id").orEmpty(),
                                params = args?.getString("params")?.ifEmpty { null },
                                pageType = args?.getString("type").orEmpty(),
                            )
                            BrowseScreen(ref, args?.getString("title").orEmpty(), vm)
                        }
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
                Toast(message, settings.reduceMotion, onDismiss = vm::dismissMessage)
                current?.let { item ->
                    MiniPlayer(vm, item.track, backdrop, onOpen = { playerPage = PlayerPage.Art; playerOpen = true })
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
private fun MiniPlayer(vm: AppViewModel, track: Track, backdrop: Backdrop, onOpen: () -> Unit) {
    val player by vm.player.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val position = rememberPositionState(vm, 250)
    val duration = rememberDuration(vm)
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(Radius.Art).glassBar(backdrop, Radius.Art)
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total < -60f) onOpen() }) { _, dy -> total += dy }
            }
            .pressable(scale = 0.985f, onClick = onOpen),
    ) {
        val reduce = LocalReduceMotion.current
        Row(Modifier.fillMaxSize().padding(start = 9.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Au changement de titre, pochette et texte glissent : on voit que la musique a changé.
            AnimatedContent(
                track,
                contentKey = { it.id },
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    if (reduce) EnterTransition.None togetherWith ExitTransition.None
                    else (slideInHorizontally(tween(Motion.Page, easing = Motion.Ease)) { it / 3 } + fadeIn(tween(Motion.Page))) togetherWith
                        (slideOutHorizontally(tween(Motion.Page, easing = Motion.Ease)) { -it / 3 } + fadeOut(tween(Motion.Fast)))
                },
                label = "titre",
            ) { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(t.cover, 46.dp, shape = Radius.Control)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = Type.Row, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (player.error == null) fr.lulu.unison.ui.components.DownloadBadge(LocalDownloads.current[t.id])
                            Text(player.error ?: t.artist, style = Type.Body, color = if (player.error != null) Ink.Danger else Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            PlayPauseButton(playing, buffering = player.buffering && playing, onClick = vm::togglePlay, modifier = Modifier.size(44.dp), iconSize = 26.dp)
            IconBtn(R.drawable.ic_next_fill, "Suivant", { vm.send(Cmd.Skip) }, iconSize = 24.dp)
        }
        if (duration > 0) {
            // Calque à part : l'avancement redessine ces 2 dp, pas le verre flouté du mini-lecteur.
            Box(
                Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp).fillMaxWidth().height(2.dp).graphicsLayer()
                    .drawBehind {
                        drawRect(Ink.FillStrong)
                        drawRect(Ink.Primary, size = size.copy(width = size.width * (position.value.toFloat() / duration).coerceIn(0f, 1f)))
                    },
            )
        }
    }
}

/**
 * Message bref : il entre en ressort par le bas et se balaie vers le bas. Le dernier texte reste affiché
 * pendant sa sortie, au lieu de disparaître avant elle.
 */
@Composable
private fun Toast(message: String?, reduce: Boolean, onDismiss: () -> Unit) {
    val last = remember { arrayOf("") }
    message?.let { last[0] = it }
    val pop = spring<Float>(dampingRatio = 0.7f, stiffness = 420f)
    AnimatedVisibility(
        message != null,
        enter = if (reduce) fadeIn() else slideInVertically(spring(dampingRatio = 0.7f, stiffness = 420f)) { it } + scaleIn(pop, initialScale = 0.9f) + fadeIn(tween(120)),
        exit = if (reduce) fadeOut() else fadeOut(tween(Motion.Fast)) + slideOutVertically(tween(Motion.Page)) { it / 2 } + scaleOut(tween(Motion.Page), targetScale = 0.95f),
    ) {
        val drag = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                last[0], style = Type.Label, color = Ink.ButtonText,
                modifier = Modifier
                    .graphicsLayer {
                        translationY = drag.value
                        alpha = 1f - (drag.value / 240f).coerceIn(0f, 0.7f)
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (drag.value > 48f) onDismiss() else scope.launch { drag.animateTo(0f, pop) } },
                            onDragCancel = { scope.launch { drag.animateTo(0f, pop) } },
                        ) { change, dy ->
                            change.consume()
                            scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) }
                        }
                    }
                    .clip(Radius.Pill).background(Ink.ButtonBg).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** Barre d'onglets : une pastille glisse en ressort sous l'onglet choisi, dont l'icône rebondit. */
@Composable
private fun BottomBar(navController: NavHostController, backdrop: Backdrop) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val selected = Tab.entries.indexOfFirst { it.route == route }
    // Hors des onglets (album, réglages…), la pastille s'efface sur place.
    val lastSelected = remember { intArrayOf(0) }
    if (selected >= 0) lastSelected[0] = selected
    val reduce = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    val pillAlpha by animateFloatAsState(if (selected >= 0) 1f else 0f, tween(Motion.Fast), label = "pastille")
    BoxWithConstraints(Modifier.fillMaxWidth().height(60.dp).clip(Radius.Panel).glassBar(backdrop, Radius.Panel)) {
        val tabWidth = maxWidth / Tab.entries.size
        val x by animateDpAsState(
            tabWidth * lastSelected[0],
            if (reduce) snap() else spring(dampingRatio = 0.72f, stiffness = 380f),
            label = "onglet",
        )
        Box(
            Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(tabWidth).fillMaxHeight()
                .padding(horizontal = 14.dp, vertical = 7.dp)
                .graphicsLayer { alpha = pillAlpha }
                .clip(Radius.Item).background(Ink.Fill),
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { tab ->
                val isSelected = route == tab.route
                val bounce = remember { Animatable(1f) }
                val was = remember { booleanArrayOf(isSelected) }
                LaunchedEffect(isSelected) {
                    if (isSelected && !was[0] && !reduce) {
                        bounce.snapTo(0.82f)
                        bounce.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 520f))
                    }
                    was[0] = isSelected
                }
                Column(
                    Modifier.weight(1f).pressable(scale = 0.92f) {
                        if (!isSelected) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            navController.navigate(tab.route) {
                                popUpTo(Tab.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Glyph(
                        if (isSelected) tab.fill else tab.line,
                        Modifier.graphicsLayer { scaleX = bounce.value; scaleY = bounce.value },
                        tint = if (isSelected) Ink.Primary else Ink.Tertiary, size = 24.dp,
                    )
                    Text(tab.label, style = Type.Caption, color = if (isSelected) Ink.Primary else Ink.Tertiary, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

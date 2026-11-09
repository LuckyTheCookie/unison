package fr.lulu.unison.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.toShape
import fr.lulu.unison.ui.theme.isExpressive
import androidx.compose.animation.core.animateFloat
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import fr.lulu.unison.deezer.DeezerState
import fr.lulu.unison.model.QueueItem
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.session.LinkStatus
import fr.lulu.unison.session.SessionHandle
import fr.lulu.unison.sync.PlaybackSync
import fr.lulu.unison.ui.components.AmbientBackground
import fr.lulu.unison.ui.components.Artwork
import fr.lulu.unison.ui.components.Avatar
import fr.lulu.unison.ui.components.AvatarStack
import fr.lulu.unison.ui.components.formatMs
import fr.lulu.unison.ui.components.rememberArtworkColors
import fr.lulu.unison.ui.glass.GlassButton
import fr.lulu.unison.ui.glass.GlassIconButton
import fr.lulu.unison.ui.glass.GlassStyle
import fr.lulu.unison.ui.glass.GlassSurface
import fr.lulu.unison.ui.glass.ProvideBackdrop
import fr.lulu.unison.ui.glass.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun SessionScreen(
    handle: SessionHandle,
    snapshot: SessionSnapshot,
    status: LinkStatus,
    deezer: DeezerState,
    listenHere: Boolean,
    sync: PlaybackSync.Status?,
    onSearch: () -> Unit,
    onInvite: () -> Unit,
    onOpenDeezer: () -> Unit,
    onToggleListen: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onLyrics: () -> Unit,
    onSettings: () -> Unit,
) {
    val current = snapshot.anchor.item
    val colors = rememberArtworkColors(current?.track?.cover)
    val bgBackdrop = rememberLayerBackdrop()
    val contentBackdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize()) {
        // Tout ce qui est dessiné ici peut être réfracté par la barre flottante.
        Box(Modifier.fillMaxSize().layerBackdrop(contentBackdrop)) {
            AmbientBackground(colors, current?.track?.cover, Modifier.fillMaxSize().layerBackdrop(bgBackdrop))
            ProvideBackdrop(bgBackdrop) {
                SessionContent(handle, snapshot, status, deezer, listenHere, sync, onInvite, onOpenDeezer, onToggleListen, onLeave, onLyrics, onSettings)
            }
            // Fondu sous la barre d'état pour que le contenu défilé reste lisible.
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Box(
                Modifier.fillMaxWidth().height(top + 28.dp).background(
                    Brush.verticalGradient(listOf(if (isExpressive) Ink.Bg.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.55f), Color.Transparent))
                )
            )
        }
        ProvideBackdrop(contentBackdrop) {
            BottomBar(
                transport = snapshot.transport,
                onSearch = onSearch,
                onOpenDeezer = onOpenDeezer,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun SessionContent(
    handle: SessionHandle,
    s: SessionSnapshot,
    status: LinkStatus,
    deezer: DeezerState,
    listenHere: Boolean,
    sync: PlaybackSync.Status?,
    onInvite: () -> Unit,
    onOpenDeezer: () -> Unit,
    onToggleListen: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onLyrics: () -> Unit,
    onSettings: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    // Copie locale pour un glisser-déposer fluide ; resynchronisée avec l'hôte hors glissement.
    var local by remember { mutableStateOf(s.queue) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(s.queue) { if (!dragging) local = s.queue }
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val fromIdx = local.indexOfFirst { it.uid == from.key }
        val toIdx = local.indexOfFirst { it.uid == to.key }
        if (fromIdx >= 0 && toIdx >= 0) {
            local = local.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    var expanded by remember { mutableStateOf<String?>(null) }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = top + 8.dp, bottom = bottom + 120.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Header(handle, s, status, onInvite, onLeave, onSettings)
        }
        item(key = "player") {
            NowPlaying(handle, s, deezer, listenHere, sync, onOpenDeezer, onToggleListen, onLyrics)
        }
        item(key = "upnext") {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                Text("À suivre", style = Type.Title2, color = Ink.Primary, modifier = Modifier.weight(1f))
                if (local.isNotEmpty()) Text("${local.size} titre${if (local.size > 1) "s" else ""} · ${formatMs(local.sumOf { it.track.durationMs })}", style = Type.Footnote, color = Ink.Secondary)
            }
        }
        if (local.isEmpty()) {
            item(key = "empty") { EmptyQueue() }
        }
        items(local, key = { it.uid }) { item ->
            ReorderableItem(reorder, key = item.uid) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 1.dp else 0.dp, label = "drag")
                val scale by animateFloatAsState(if (isDragging) 1.03f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "dragScale")
                QueueRow(
                    item = item,
                    s = s,
                    expanded = expanded == item.uid,
                    onTap = { expanded = if (expanded == item.uid) null else item.uid },
                    onCmd = { handle.send(it); expanded = null },
                    handleModifier = Modifier.draggableHandle(
                        onDragStarted = { dragging = true; haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                        onDragStopped = {
                            dragging = false
                            val idx = local.indexOfFirst { it.uid == item.uid }
                            if (idx >= 0 && s.queue.indexOfFirst { it.uid == item.uid } != idx) handle.send(Cmd.Move(item.uid, idx))
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                        },
                    ),
                    modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = elevation.toPx() * 20 },
                )
            }
        }
        if (s.history.isNotEmpty()) {
            item(key = "historyTitle") {
                Text("Déjà écoutés", style = Type.Caption, color = Ink.Tertiary, modifier = Modifier.padding(start = 24.dp, top = 28.dp, bottom = 8.dp))
            }
            items(s.history.takeLast(6).reversed(), key = { "h" + it.uid }) { h ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).graphicsLayer { alpha = 0.55f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(h.track.coverSmall, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.track.title, style = Type.Callout, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(h.track.artist, style = Type.Footnote, color = Ink.Secondary, maxLines = 1)
                    }
                    Avatar(s.member(h.addedBy), 20.dp)
                }
            }
        }
    }
}

@Composable
private fun Header(handle: SessionHandle, s: SessionSnapshot, status: LinkStatus, onInvite: () -> Unit, onLeave: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Rounded.Close, "Quitter", onLeave, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (handle.isHost) "Ta session" else "Session de ${s.hostName}",
                    style = Type.Headline, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (s.transport == TransportKind.LAN) Icons.Rounded.Wifi else Icons.Rounded.Bluetooth, null,
                        tint = Ink.Secondary, modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when (status) {
                            LinkStatus.Connected -> "${s.members.size} à l'écoute"
                            is LinkStatus.Reconnecting -> "Reconnexion…"
                            is LinkStatus.Lost -> status.reason
                        },
                        style = Type.Footnote,
                        color = if (status is LinkStatus.Connected) Ink.Secondary else Color(0xFFFFB443),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.pressable(onClick = onInvite)) { AvatarStack(s.members, 28.dp) }
            Spacer(Modifier.width(10.dp))
            GlassIconButton(Icons.Rounded.QrCode2, "Inviter", onInvite, size = 40.dp)
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.Rounded.Settings, "Réglages", onSettings, size = 40.dp)
        }
    }
}

@Composable
private fun NowPlaying(
    handle: SessionHandle,
    s: SessionSnapshot,
    deezer: DeezerState,
    listenHere: Boolean,
    sync: PlaybackSync.Status?,
    onOpenDeezer: () -> Unit,
    onToggleListen: (Boolean) -> Unit,
    onLyrics: () -> Unit,
) {
    val item = s.anchor.item
    // Position animée à chaque image, calculée sur l'horloge de l'hôte.
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(s.anchor) {
        while (true) withFrameMillis { pos = s.anchor.positionAt(handle.hostNow()) }
    }
    val duration = item?.track?.durationMs ?: 0L

    Column(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val artScale by animateFloatAsState(
            if (s.anchor.playing || item == null) 1f else 0.86f,
            spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow), label = "art",
        )
        AnimatedContent(
            item, label = "cover",
            contentKey = { it?.uid },
            transitionSpec = { (fadeIn(tween(500)) + scaleIn(initialScale = 0.92f)) togetherWith (fadeOut(tween(300)) + scaleOut(targetScale = 1.04f)) },
        ) { it ->
            if (it == null) {
                GlassSurface(Modifier.size(300.dp), shape = RoundedRectangle(30.dp), style = GlassStyle.Clear, contentAlignment = Alignment.Center) {
                    fr.lulu.unison.ui.components.UnisonLogo(130.dp)
                }
            } else Box(
                Modifier.graphicsLayer {
                    scaleX = artScale; scaleY = artScale
                    shadowElevation = 40.dp.toPx(); shape = RoundedRectangle(30.dp); clip = true
                    ambientShadowColor = Color.Black; spotShadowColor = Color.Black
                },
            ) { Artwork(it.track.cover, 300.dp, 30.dp) }
        }
        Spacer(Modifier.height(28.dp))

        AnimatedContent(item, label = "titles", contentKey = { it?.uid }, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) }) { it ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    it?.track?.title ?: "Rien pour l'instant",
                    style = Type.Title, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    it?.track?.artist ?: "Ajoute un titre pour lancer la session",
                    style = Type.Body, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (it != null) {
                    val by = s.member(it.addedBy)
                    Row(Modifier.padding(top = 10.dp).clip(Capsule()).background(Ink.Fill).padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(by, 20.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Ajouté par ${by?.name ?: "un ancien membre"}", style = Type.Footnote, color = Ink.Secondary)
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Scrubber(
            position = pos, duration = duration, enabled = item != null, playing = s.anchor.playing,
            onSeek = { handle.send(Cmd.Seek(it)) },
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            GlassIconButton(Icons.Rounded.SkipPrevious, "Précédent", { handle.send(Cmd.Previous) }, size = 62.dp, iconSize = 30.dp, style = GlassStyle.Clear)
            if (isExpressive) ExpressivePlayButton(s.anchor.playing) { handle.send(if (s.anchor.playing) Cmd.Pause else Cmd.Resume) }
            else GlassIconButton(
                if (s.anchor.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (s.anchor.playing) "Pause" else "Lecture",
                { handle.send(if (s.anchor.playing) Cmd.Pause else Cmd.Resume) },
                size = 84.dp, iconSize = 42.dp, style = GlassStyle.Clear,
                tint = Color.White.copy(alpha = 0.10f),
            )
            GlassIconButton(Icons.Rounded.SkipNext, "Suivant", { handle.send(Cmd.Skip) }, size = 62.dp, iconSize = 30.dp, style = GlassStyle.Clear)
        }

        Spacer(Modifier.height(18.dp))
        AnimatedVisibility(item != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GlassButton(onClick = onLyrics, style = GlassStyle.Regular, modifier = Modifier.padding(bottom = 12.dp)) {
                    Icon(Icons.Rounded.Lyrics, null, tint = Ink.Primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Paroles", style = Type.Callout, color = Ink.Primary)
                }
                var picking by remember { mutableStateOf(false) }
                AddToPlaylistButton(Modifier.padding(bottom = 12.dp), picking) { picking = it }
            }
        }
        // Écoute locale + état de synchro
        val needsDeezer = listenHere && item != null && !deezer.connected
        AnimatedContent(needsDeezer, label = "deezerHint") { needs ->
            if (needs) {
                GlassButton(onClick = onOpenDeezer, style = GlassStyle.Regular, tint = Color(0x33FFB443)) {
                    Text("Ouvre Deezer une fois pour le connecter", style = Type.Footnote, color = Ink.Primary)
                }
            } else {
                Row(
                    Modifier.clip(Capsule()).background(Ink.Fill).pressable { onToggleListen(!listenHere) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (listenHere) Icons.Rounded.Headphones else Icons.Rounded.HeadsetOff, null, tint = Ink.Secondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    val drift = sync?.driftMs
                    Text(
                        when {
                            !listenHere -> "Écoute coupée sur ce téléphone"
                            sync?.lastError != null -> sync.lastError
                            drift != null && item != null && s.anchor.playing -> "Synchronisé · ${if (kotlin.math.abs(drift) < 1000) "${drift} ms" else formatMs(kotlin.math.abs(drift))}"
                            else -> "Écoute sur ce téléphone"
                        },
                        style = Type.Footnote, color = Ink.Secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Scrubber(position: Long, duration: Long, enabled: Boolean, playing: Boolean, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var width by remember { mutableFloatStateOf(1f) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val thickness by animateDpAsState(if (dragFraction != null) 12.dp else 7.dp, spring(Spring.DampingRatioMediumBouncy), label = "thick")

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(enabled, duration) {
                    if (!enabled || duration <= 0) return@pointerInput
                    detectTapGestures { o -> onSeek(((o.x / width).coerceIn(0f, 1f) * duration).toLong()) }
                }
                .pointerInput(enabled, duration) {
                    if (!enabled || duration <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / width).coerceIn(0f, 1f) },
                        onDragEnd = { dragFraction?.let { onSeek((it * duration).toLong()) }; dragFraction = null },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ -> dragFraction = (change.position.x / width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (isExpressive) {
                // Progression ondulée M3 Expressive : la vague s'aplatit à la pause.
                val amp by animateFloatAsState(if (playing && dragFraction == null) 1f else 0f, tween(500), label = "amp")
                LinearWavyProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(if (dragFraction != null) 16.dp else 12.dp),
                    amplitude = { amp },
                )
            } else {
                Box(Modifier.fillMaxWidth().height(thickness).clip(Capsule()).background(Color.White.copy(alpha = 0.18f)))
                Box(
                    Modifier.fillMaxWidth(fraction).height(thickness).clip(Capsule())
                        .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White)))
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatMs(if (dragFraction != null) (fraction * duration).toLong() else position), style = Type.Mono, color = Ink.Tertiary)
            Spacer(Modifier.weight(1f))
            Text("-" + formatMs(duration - (fraction * duration).toLong()), style = Type.Mono, color = Ink.Tertiary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueRow(
    item: QueueItem,
    s: SessionSnapshot,
    expanded: Boolean,
    onTap: () -> Unit,
    onCmd: (Cmd) -> Unit,
    handleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val dismiss = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) onCmd(Cmd.Remove(item.uid))
    }
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 5.dp),
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedRectangle(24.dp)).background(Brush.horizontalGradient(listOf(Color.Transparent, Ink.Danger.copy(alpha = 0.85f)))),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Rounded.DeleteOutline, "Retirer", tint = Color.White, modifier = Modifier.padding(end = 24.dp)) }
        },
    ) {
        GlassSurface(Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)), shape = RoundedRectangle(24.dp), refraction = false) {
            Column {
                Row(
                    Modifier.fillMaxWidth().pressable(haptic = false, onClick = onTap).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(item.track.coverSmall, 54.dp, 14.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.track.title, style = Type.Headline, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(s.member(item.addedBy), 16.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(item.track.artist, style = Type.Footnote, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(handleModifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.DragHandle, "Déplacer", tint = Ink.Tertiary)
                    }
                }
                AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionPill("Lire maintenant", Icons.Rounded.PlayArrow, Modifier.weight(1.2f)) { onCmd(Cmd.PlayNow(item.uid)) }
                        ActionPill("Ensuite", Icons.AutoMirrored.Rounded.PlaylistAdd, Modifier.weight(1f)) { onCmd(Cmd.Move(item.uid, 0)) }
                        ActionPill("Retirer", Icons.Rounded.DeleteOutline, Modifier.weight(1f), danger = true) { onCmd(Cmd.Remove(item.uid)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.clip(Capsule()).background(if (danger) Ink.Danger.copy(alpha = 0.22f) else Ink.Fill)
            .pressable(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) Color(0xFFFF8A8D) else Ink.Primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Type.Footnote, color = if (danger) Color(0xFFFF8A8D) else Ink.Primary, maxLines = 1)
    }
}

@Composable
private fun EmptyQueue() {
    GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedRectangle(26.dp), refraction = false) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = Ink.Secondary, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(10.dp))
            Text("La file est vide", style = Type.Headline, color = Ink.Primary)
            Text(
                "Cherche un titre ci-dessous, ou partage-le depuis Deezer avec « Ajouter à Unison ».",
                style = Type.Footnote, color = Ink.Secondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun BottomBar(transport: TransportKind, onSearch: () -> Unit, onOpenDeezer: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassButton(
            onClick = onSearch,
            modifier = Modifier.weight(1f).height(58.dp),
            style = GlassStyle.Regular,
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            Icon(Icons.Rounded.Search, null, tint = Ink.Primary)
            Spacer(Modifier.width(10.dp))
            Text("Rechercher un titre", style = Type.Callout, color = Ink.Secondary)
        }
        Spacer(Modifier.width(10.dp))
        GlassButton(
            onClick = onOpenDeezer,
            modifier = Modifier.height(58.dp),
            contentPadding = PaddingValues(horizontal = 18.dp),
        ) {
            DeezerMark()
            Spacer(Modifier.width(8.dp))
            Text("Deezer", style = Type.Callout, color = Ink.Primary)
        }
    }
}

/** Petit égaliseur, rappel visuel de Deezer. */
@Composable
private fun DeezerMark() {
    Row(Modifier.height(16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(0.45f, 0.8f, 1f, 0.65f).forEachIndexed { i, h ->
            Box(
                Modifier.width(3.dp).fillMaxHeight(h).clip(Capsule())
                    .background(listOf(Color(0xFFA238FF), Color(0xFFEF5466), Color(0xFFFF8A00), Color(0xFFFFC300))[i])
            )
        }
    }
}

@Suppress("unused")
private fun Modifier.noop() = this.pointerInput(Unit) { detectTapGestures { } }


/**
 * Bouton lecture M3 Expressive : forme « cookie » (MaterialShapes) qui tourne
 * pendant la lecture et s'arrondit en cercle à la pause.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressivePlayButton(playing: Boolean, onClick: () -> Unit) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "cookie")
    val spin by t.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(tween(14_000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "spin",
    )
    val cookie = androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape()
    val circle = androidx.compose.material3.MaterialShapes.Circle.toShape()
    val size by animateDpAsState(if (playing) 96.dp else 88.dp, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "size")
    Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(playing, label = "shape", transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) }) { p ->
            Box(
                Modifier.size(size)
                    .graphicsLayer { rotationZ = if (p) spin else 0f }
                    .clip(if (p) cookie else circle)
                    .background(cs.primary)
                    .pressable(onClick = onClick),
            )
        }
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (playing) "Pause" else "Lecture",
            tint = cs.onPrimary, modifier = Modifier.size(44.dp),
        )
    }
}

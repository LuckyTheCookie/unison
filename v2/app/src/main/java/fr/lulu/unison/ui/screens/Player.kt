package fr.lulu.unison.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.lulu.unison.R
import fr.lulu.unison.lyrics.Lyrics
import fr.lulu.unison.model.QueueItem
import fr.lulu.unison.model.RepeatMode
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.LocalNav
import fr.lulu.unison.ui.LyricsUi
import fr.lulu.unison.ui.components.Cover
import fr.lulu.unison.ui.components.ExplicitBadge
import fr.lulu.unison.ui.components.Glyph
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.Notice
import fr.lulu.unison.ui.components.Spinner
import fr.lulu.unison.ui.components.formatTime
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.kawarp.KawarpBackground
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Motion
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

enum class PlayerPage { Art, Lyrics, Queue }

/** Position de lecture, rafraîchie assez souvent pour une barre fluide et des paroles à l'heure. */
@Composable
fun rememberPosition(vm: AppViewModel, periodMs: Long = 120): Long {
    val position by produceState(vm.positionMs()) {
        while (true) { value = vm.positionMs(); delay(periodMs) }
    }
    return position
}

/** Lecteur plein écran : pochette, paroles ou file, avec les commandes toujours sous le pouce. */
@Composable
fun PlayerScreen(vm: AppViewModel, page: PlayerPage, onPage: (PlayerPage) -> Unit, onClose: () -> Unit) {
    val nav = LocalNav.current
    val snap by vm.snapshot.collectAsState()
    val settings by vm.settings.collectAsState()
    val item = snap.anchor.item
    val track = item?.track

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total > 140f) onClose() }) { _, dy -> total += dy }
        },
    ) {
        KawarpBackground(track?.cover, Modifier.fillMaxSize(), dim = if (page == PlayerPage.Art) 0.22f else 0.42f, animated = settings.animatedBackground)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(R.drawable.ic_down_line, "Réduire", onClose)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (page) { PlayerPage.Art -> if (snap.shared) "Écoute à ${snap.members.size}" else "En écoute"; PlayerPage.Lyrics -> "Paroles"; PlayerPage.Queue -> "File d'attente" },
                        style = Type.Label, color = Ink.Secondary,
                    )
                }
                IconBtn(R.drawable.ic_more_line, "Plus d'options", { track?.let(nav.trackMenu) }, enabled = track != null)
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    page, transitionSpec = { fadeIn(tween(Motion.Page, easing = Motion.Ease)) togetherWith fadeOut(tween(Motion.Fast)) }, label = "page",
                ) { p ->
                    when (p) {
                        PlayerPage.Art -> ArtPage(track?.cover, snap.anchor.playing)
                        PlayerPage.Lyrics -> LyricsPage(vm)
                        PlayerPage.Queue -> QueuePage(vm, snap)
                    }
                }
            }

            if (track != null) {
                val favorites by vm.favoriteIds.collectAsState()
                Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = Type.PlayerTitle, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (track.explicit) ExplicitBadge()
                            Text(track.artist, style = Type.PlayerArtist, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val fav = track.id in favorites
                    IconBtn(
                        if (fav) R.drawable.ic_heart_fill else R.drawable.ic_heart_line, "Favori", { vm.toggleFavorite(track) },
                        tint = if (fav) Ink.Accent else Ink.Primary, background = Ink.FillHover,
                    )
                }
            }
            SeekBar(vm, Modifier.padding(horizontal = 28.dp).padding(top = 14.dp))
            Transport(vm, snap)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PageToggle(R.drawable.ic_lyrics_line, R.drawable.ic_lyrics_fill, "Paroles", page == PlayerPage.Lyrics) { onPage(if (page == PlayerPage.Lyrics) PlayerPage.Art else PlayerPage.Lyrics) }
                PageToggle(R.drawable.ic_group_line, R.drawable.ic_group_fill, if (snap.shared) "${snap.members.size}" else "Ensemble", snap.shared, onClick = nav.together)
                PageToggle(R.drawable.ic_queue_line, R.drawable.ic_queue_line, "File", page == PlayerPage.Queue) { onPage(if (page == PlayerPage.Queue) PlayerPage.Art else PlayerPage.Queue) }
            }
        }
    }
}

@Composable
private fun PageToggle(iconOff: Int, iconOn: Int, label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(Radius.Pill).background(if (active) Ink.FillStrong else Color.Transparent).pressable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(if (active) iconOn else iconOff, tint = if (active) Ink.Primary else Ink.Secondary, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = Type.Label, color = if (active) Ink.Primary else Ink.Secondary)
    }
}

@Composable
private fun ArtPage(cover: String?, playing: Boolean) {
    val scale by animateFloatAsState(if (playing) 1f else 0.9f, spring(dampingRatio = 0.72f, stiffness = 280f), label = "art")
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Cover(cover, side, Modifier.graphicsLayer { scaleX = scale; scaleY = scale }, shape = Radius.Art)
    }
}

/** Barre de progression : on peut la toucher ou la faire glisser pour se déplacer dans le titre. */
@Composable
fun SeekBar(vm: AppViewModel, modifier: Modifier = Modifier) {
    val position = rememberPosition(vm, 200)
    val duration = vm.durationMs()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) dragFraction else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val thickness by animateDpAsState(if (dragging) 8.dp else 4.dp, label = "bar")
    val enabled = duration > 0

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { o -> vm.send(Cmd.Seek(((o.x / size.width).coerceIn(0f, 1f) * vm.durationMs()).toLong())) }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = true; dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { vm.send(Cmd.Seek((dragFraction * vm.durationMs()).toLong())); dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(thickness)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(Color.White.copy(alpha = 0.22f), cornerRadius = r)
                drawRoundRect(Color.White, size = Size(size.width * fraction, size.height), cornerRadius = r)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(if (dragging) (dragFraction * duration).toLong() else position.coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE)), style = Type.Time, color = Ink.Secondary)
            Text(if (duration > 0) formatTime(duration) else "–:––", style = Type.Time, color = Ink.Secondary)
        }
    }
}

@Composable
private fun Transport(vm: AppViewModel, snap: SessionSnapshot) {
    val player by vm.player.collectAsState()
    val has = snap.anchor.item != null
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(R.drawable.ic_shuffle_line, "Mélanger la file", { vm.send(Cmd.Shuffle); vm.toast("File mélangée") }, tint = Ink.Secondary, enabled = snap.queue.size > 1)
        IconBtn(R.drawable.ic_prev_fill, "Précédent", { vm.send(Cmd.Previous) }, size = 56.dp, iconSize = 30.dp, enabled = has)
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(Ink.ButtonBg).pressable(enabled = has || snap.queue.isNotEmpty(), scale = 0.92f, onClick = vm::togglePlay),
            contentAlignment = Alignment.Center,
        ) {
            if (player.buffering && snap.anchor.playing) Spinner(size = 28.dp, color = Ink.ButtonText)
            else Glyph(if (snap.anchor.playing) R.drawable.ic_pause_fill else R.drawable.ic_play_fill, tint = Ink.ButtonText, size = 32.dp)
        }
        IconBtn(R.drawable.ic_next_fill, "Suivant", { vm.send(Cmd.Skip) }, size = 56.dp, iconSize = 30.dp, enabled = has)
        val (icon, tint) = when (snap.repeat) {
            RepeatMode.OFF -> R.drawable.ic_repeat_line to Ink.Secondary
            RepeatMode.ALL -> R.drawable.ic_repeat_line to Ink.Accent
            RepeatMode.ONE -> R.drawable.ic_repeat_one_line to Ink.Accent
        }
        IconBtn(icon, "Répéter", {
            val next = RepeatMode.entries[(snap.repeat.ordinal + 1) % RepeatMode.entries.size]
            vm.send(Cmd.SetRepeat(next))
            vm.toast(when (next) { RepeatMode.OFF -> "Répétition désactivée"; RepeatMode.ALL -> "Répéter la file"; RepeatMode.ONE -> "Répéter ce titre" })
        }, tint = tint)
    }
}

// --- Paroles ---

@Composable
private fun LyricsPage(vm: AppViewModel) {
    val ui by vm.lyrics.collectAsState()
    when (val l = ui) {
        LyricsUi.Idle -> Notice("Rien ne joue", icon = R.drawable.ic_lyrics_line)
        is LyricsUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
        is LyricsUi.Missing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Notice("Pas de paroles pour ce titre", detail = "Cherchées sur LRCLIB et NetEase.", icon = R.drawable.ic_lyrics_line)
        }
        is LyricsUi.Ready -> if (l.lyrics.synced) SyncedLyrics(l.lyrics, vm) else PlainLyrics(l.lyrics)
    }
}

/** Fondu en haut et en bas de la liste, pour que les lignes entrent et sortent en douceur. */
private fun Modifier.fadeEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun SyncedLyrics(lyrics: Lyrics, vm: AppViewModel) {
    val position = rememberPosition(vm, 100)
    val active = lyrics.indexAt(position + 150)
    val state = rememberLazyListState()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().fadeEdges()) {
        val pad = maxHeight * 0.38f
        LaunchedEffect(active) {
            if (active >= 0 && !state.isScrollInProgress) state.animateScrollToItem(active, scrollOffset = with(density) { -12.dp.roundToPx() })
        }
        LazyColumn(state = state, contentPadding = PaddingValues(top = 24.dp, bottom = pad), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lyrics.lines) { i, line ->
                val isActive = i == active
                val alpha by animateFloatAsState(if (isActive) 1f else if (i < active) 0.3f else 0.42f, tween(320, easing = Motion.Ease), label = "a")
                val scale by animateFloatAsState(if (isActive) 1.03f else 0.96f, tween(320, easing = Motion.Ease), label = "s")
                Text(
                    line.text.ifBlank { "♪" },
                    style = Type.Lyric, color = Ink.Primary,
                    modifier = Modifier.fillMaxWidth()
                        .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                        .pressable(scale = 0.99f) { vm.send(Cmd.Seek(line.timeMs)) }
                        .padding(horizontal = 28.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics) {
    Column(Modifier.fillMaxSize().fadeEdges().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text("Paroles non synchronisées", style = Type.Caption, color = Ink.Tertiary, modifier = Modifier.padding(bottom = 14.dp))
        lyrics.lines.forEach { line ->
            Text(line.text, style = Type.Lyric.copy(fontSize = Type.Lyric.fontSize * 0.78f, lineHeight = Type.Lyric.lineHeight * 0.8f), color = Ink.Primary.copy(alpha = 0.86f), modifier = Modifier.padding(vertical = 5.dp))
        }
        Spacer(Modifier.height(40.dp))
    }
}

// --- File d'attente ---

@Composable
private fun QueuePage(vm: AppViewModel, snap: SessionSnapshot) {
    var local by remember(snap.queue) { mutableStateOf(snap.queue) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = local.indexOfFirst { it.uid == from.key }
        val b = local.indexOfFirst { it.uid == to.key }
        if (a >= 0 && b >= 0) local = local.toMutableList().apply { add(b, removeAt(a)) }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().fadeEdges(), contentPadding = PaddingValues(top = 18.dp, bottom = 32.dp)) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("À suivre", style = Type.Heading, color = Ink.Primary)
                    Text(if (local.isEmpty()) "La file est vide" else "${local.size} titre${if (local.size > 1) "s" else ""}", style = Type.Body, color = Ink.Secondary)
                }
                if (local.isNotEmpty()) IconBtn(R.drawable.ic_delete_line, "Vider la file", { vm.send(Cmd.Clear) }, tint = Ink.Secondary)
            }
        }
        item(key = "auto") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(Radius.Item).background(Ink.Fill)
                    .pressable(scale = 0.99f) { vm.send(Cmd.SetAutoplay(!snap.autoplay)) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(if (snap.autoplay) R.drawable.ic_sparkles_fill else R.drawable.ic_sparkles_line, tint = if (snap.autoplay) Ink.Accent else Ink.Secondary, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Radio automatique", style = Type.Row, color = Ink.Primary)
                    Text("Prolonge la file avec des titres proches", style = Type.Caption, color = Ink.Tertiary)
                }
                Toggle(snap.autoplay)
            }
        }
        items(local, key = { it.uid }) { item ->
            ReorderableItem(reorder, key = item.uid) { dragging ->
                QueueRow(
                    item, snap, dragging,
                    onPlay = { vm.send(Cmd.PlayNow(item.uid)) },
                    onRemove = { vm.send(Cmd.Remove(item.uid)) },
                    handle = Modifier.draggableHandle(onDragStopped = {
                        val to = local.indexOfFirst { it.uid == item.uid }
                        if (to >= 0 && snap.queue.getOrNull(to)?.uid != item.uid) vm.send(Cmd.Move(item.uid, to))
                    }),
                )
            }
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem, snap: SessionSnapshot, dragging: Boolean, onPlay: () -> Unit, onRemove: () -> Unit, handle: Modifier) {
    val by = when {
        item.addedBy == QueueItem.AUTO -> "Radio"
        snap.shared -> snap.member(item.addedBy)?.name
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(if (dragging) Ink.FillStrong else Color.Transparent).pressable(scale = 0.99f, onClick = onPlay).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(item.track.cover, 46.dp, shape = Radius.Control)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.track.title, style = Type.Row, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(item.track.artist, by).joinToString(" • "), style = Type.Body, color = Ink.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconBtn(R.drawable.ic_close_line, "Retirer", onRemove, size = 40.dp, iconSize = 18.dp, tint = Ink.Tertiary)
        Box(handle.size(44.dp), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_handle_line, tint = Ink.Tertiary, size = 20.dp) }
    }
}

/** Interrupteur discret. */
@Composable
fun Toggle(on: Boolean, modifier: Modifier = Modifier) {
    val x by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 500f), label = "toggle")
    Box(modifier.size(width = 44.dp, height = 26.dp).clip(Radius.Pill).background(if (on) Ink.Accent else Ink.FillStrong)) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.height / 2 - 3.dp.toPx()
            drawCircle(if (on) Color.Black else Color.White, r, Offset(size.height / 2 + (size.width - size.height) * x, size.height / 2))
        }
    }
}

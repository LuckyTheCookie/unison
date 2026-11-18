package fr.lulu.unison.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.lulu.unison.R
import fr.lulu.unison.lyrics.Karaoke
import fr.lulu.unison.lyrics.LyricLine
import fr.lulu.unison.lyrics.Lyrics
import fr.lulu.unison.model.QueueItem
import fr.lulu.unison.model.RepeatMode
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.LocalNav
import fr.lulu.unison.ui.LyricsUi
import fr.lulu.unison.ui.LocalDownloads
import fr.lulu.unison.ui.components.Cover
import fr.lulu.unison.ui.components.DownloadBadge
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
import kotlinx.coroutines.launch
import kotlin.math.abs
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
fun PlayerScreen(vm: AppViewModel, page: PlayerPage, onPage: (PlayerPage) -> Unit, onClose: () -> Unit, gesturing: Boolean = false) {
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
        // Pendant un geste de retour, le fond se fige : chaque image du geste reste fluide.
        KawarpBackground(track?.cover, Modifier.fillMaxSize(), dim = if (page == PlayerPage.Art) 0.22f else 0.42f, animated = settings.animatedBackground && !gesturing)
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
                        PlayerPage.Art -> ArtPage(
                            cover = track?.cover,
                            playing = snap.anchor.playing,
                            nextCover = nextCover(snap),
                            // « Précédent » recommence le titre après 5 s : la pochette voisine est alors la même.
                            previousCover = { if (vm.positionMs() > 5_000) track?.cover else snap.history.lastOrNull()?.track?.cover ?: track?.cover },
                            onNext = { vm.send(Cmd.Skip) },
                            onPrevious = { vm.send(Cmd.Previous) },
                        )
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
                            DownloadBadge(LocalDownloads.current[track.id], size = 16.dp)
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

/** Pochette du titre que « suivant » lancerait, ou null s'il n'y en a pas. */
private fun nextCover(snap: SessionSnapshot): String? = when {
    snap.queue.isNotEmpty() -> snap.queue.first().track.cover
    snap.repeat == RepeatMode.ALL && snap.history.isNotEmpty() -> snap.history.first().track.cover
    else -> null
}

/**
 * Pochette qu'on fait glisser pour changer de titre : vers la gauche le suivant, vers la droite le précédent.
 * Elle suit le doigt (avec un léger basculement), part au-delà d'un seuil ou d'un geste vif, et revient en
 * ressort sinon. Le glissement vertical reste à l'écran parent, qui ferme le lecteur.
 */
@Composable
private fun ArtPage(
    cover: String?,
    playing: Boolean,
    nextCover: String?,
    previousCover: () -> String?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val scale by animateFloatAsState(if (playing) 1f else 0.9f, spring(dampingRatio = 0.72f, stiffness = 280f), label = "art")
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var previous by remember { mutableStateOf<String?>(null) }
    // Pochette qui sortait quand le geste a abouti : tant que la session n'a pas changé de titre, on la garde hors champ.
    var leaving by remember { mutableStateOf<String?>(null) }
    var leavingSince by remember { mutableLongStateOf(0L) }
    val currentCover by rememberUpdatedState(cover)
    val canNext by rememberUpdatedState(nextCover != null)

    // Le nouveau titre est arrivé : la pochette voisine, déjà au centre, devient la pochette courante sans saut.
    LaunchedEffect(cover) {
        if (leaving != null && leaving != cover) { offset.snapTo(0f); leaving = null }
    }
    // Garde-fou : si la session ne suit pas (titre identique, commande perdue), la pochette revient.
    LaunchedEffect(leavingSince) {
        if (leaving == null) return@LaunchedEffect
        delay(1_500)
        if (leaving != null) { leaving = null; offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 300f)) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        // Distance entre deux pochettes : la voisine entre par le bord de l'écran.
        val step = width + with(LocalDensity.current) { 56.dp.toPx() }
        val tracker = remember { VelocityTracker() }
        var crossed by remember { mutableStateOf(false) }

        fun settle(velocity: Float) {
            val x = offset.value
            val threshold = width * 0.28f
            val fling = abs(velocity) > 1_100f
            val direction = when {
                (x < -threshold || (fling && velocity < 0 && x < 0)) && canNext -> -1
                x > threshold || (fling && velocity > 0 && x > 0) -> 1
                else -> 0
            }
            scope.launch {
                if (direction == 0) {
                    offset.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 380f), initialVelocity = velocity)
                    return@launch
                }
                val outgoing = currentCover
                val sameCover = direction > 0 && previous == outgoing
                if (crossed.not()) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                offset.animateTo(direction * step, tween(Motion.Page, easing = Motion.Ease), initialVelocity = velocity)
                if (direction < 0) onNext() else onPrevious()
                // « Précédent » qui recommence le titre : même pochette, on la remet simplement en place.
                if (sameCover) offset.snapTo(0f)
                else { leaving = outgoing; leavingSince = System.nanoTime() }
            }
        }

        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        crossed = false
                        previous = previousCover()
                        scope.launch { offset.stop() }
                    },
                    onDragEnd = { settle(tracker.calculateVelocity().x) },
                    onDragCancel = { settle(0f) },
                ) { change, dx ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val x = offset.value
                    // Sans titre suivant, la pochette résiste et revient.
                    val resist = if (x + dx < 0 && !canNext) 0.3f else 1f
                    val nx = x + dx * resist
                    val over = abs(nx) > width * 0.28f && (nx > 0 || canNext)
                    if (over && !crossed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    crossed = over
                    scope.launch { offset.snapTo(nx) }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            val shown = leaving == null || leaving == cover
            // Voisines : la suivante à droite, la précédente à gauche, qui grandissent en approchant du centre.
            if (nextCover != null) NeighbourCover(nextCover, side, { if (shown) offset.value + step else step }, step)
            NeighbourCover(previous ?: cover, side, { if (shown) offset.value - step else -step }, step)
            Cover(
                cover, side,
                Modifier.graphicsLayer {
                    val x = if (shown) offset.value else 0f
                    val f = (x / width).coerceIn(-1.5f, 1.5f)
                    translationX = x
                    rotationZ = f * 7f
                    alpha = (1f - abs(f) * 0.55f).coerceIn(0f, 1f)
                    scaleX = scale; scaleY = scale
                },
                shape = Radius.Art,
            )
        }
    }
}

/** Pochette voisine : placée à [x] du centre, invisible tant qu'elle est loin. */
@Composable
private fun NeighbourCover(url: String?, side: Dp, x: () -> Float, step: Float) {
    Cover(
        url, side,
        Modifier.graphicsLayer {
            val pos = x()
            val near = (1f - abs(pos) / step).coerceIn(0f, 1f)
            translationX = pos
            alpha = near
            val s = 0.86f + 0.14f * near
            scaleX = s; scaleY = s
        },
        shape = Radius.Art,
    )
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
    // Position lue à chaque image : seul le dessin de la ligne en cours en dépend, pas la composition.
    val clock = remember { mutableLongStateOf(vm.positionMs()) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { clock.longValue = vm.positionMs() }
    }
    val active by remember(lyrics) { derivedStateOf { lyrics.indexAt(clock.longValue + 150) } }
    val state = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize().fadeEdges()) {
        // La ligne en cours se pose au tiers haut de la zone, hors du fondu des bords.
        val top = maxHeight * 0.26f
        LaunchedEffect(active) {
            if (active >= 0 && !state.isScrollInProgress) state.animateScrollToItem(active)
        }
        LazyColumn(state = state, contentPadding = PaddingValues(top = top, bottom = maxHeight * 0.6f), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lyrics.lines) { i, line ->
                val isActive = i == active
                // Lignes passées : pleines ; ligne en cours : remplie au fil du chant ; à venir : estompées.
                val alpha by animateFloatAsState(if (isActive) 1f else if (i < active) 0.5f else 0.85f, tween(320, easing = Motion.Ease), label = "a")
                val scale by animateFloatAsState(if (isActive) 1.03f else 0.96f, tween(320, easing = Motion.Ease), label = "s")
                KaraokeLine(
                    line,
                    filled = {
                        when {
                            i < active -> Float.MAX_VALUE
                            i > active -> 0f
                            line.text.isBlank() -> Float.MAX_VALUE
                            else -> Karaoke.progress(line, clock.longValue)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                        .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                        .pressable(scale = 0.99f) { vm.send(Cmd.Seek(line.timeMs)) }
                        .padding(horizontal = 28.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * Une ligne de paroles dont les [filled] premiers caractères (fraction comprise) sont pleinement éclairés,
 * le reste estompé. Le remplissage suit chaque ligne visuelle quand le texte passe à la ligne, et n'est lu
 * qu'au dessin : il avance à chaque image sans recomposer.
 */
@Composable
private fun KaraokeLine(line: LyricLine, filled: () -> Float, modifier: Modifier = Modifier) {
    val text = line.text.ifBlank { "♪" }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text, style = Type.Lyric, color = Color.Transparent,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            val l = layout ?: return@drawWithContent
            drawText(l, color = Ink.Primary.copy(alpha = 0.32f))
            val progress = filled().coerceIn(0f, text.length.toFloat())
            if (progress <= 0f) return@drawWithContent
            val whole = progress.toInt()
            for (row in 0 until l.lineCount) {
                val start = l.getLineStart(row)
                val end = l.getLineEnd(row, visibleEnd = true)
                if (progress <= start) break
                if (start >= end) continue
                val right = when {
                    progress >= end -> l.getLineRight(row)
                    else -> {
                        // Bord du remplissage : début du caractère en cours plus la fraction déjà chantée.
                        val box = l.getBoundingBox(whole.coerceIn(start, end - 1))
                        box.left + box.width * (progress - whole)
                    }
                }
                val top = l.getLineTop(row)
                val bottom = l.getLineBottom(row)
                clipRect(left = l.getLineLeft(row), top = top, right = right, bottom = bottom) {
                    drawText(l, color = Ink.Primary)
                }
                if (progress < end) break
            }
        },
    )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                DownloadBadge(LocalDownloads.current[item.track.id])
                Text(listOfNotNull(item.track.artist, by).joinToString(" • "), style = Type.Body, color = Ink.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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

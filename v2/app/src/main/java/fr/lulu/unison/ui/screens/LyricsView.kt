package fr.lulu.unison.ui.screens

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.lulu.unison.lyrics.Karaoke
import fr.lulu.unison.lyrics.LyricClock
import fr.lulu.unison.lyrics.LyricLine
import fr.lulu.unison.lyrics.Lyrics
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.LocalReduceMotion
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Motion
import fr.lulu.unison.ui.theme.Type
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/*
 * Paroles mot à mot, dans l'esprit de l'écran de paroles d'Apple Music et de BitChord
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0) : remplissage au bord adouci, mots qui se soulèvent
 * quand ils sont chantés, halo léger sur les notes tenues, lignes lointaines floutées.
 */

/** La ligne suivante s'allume un peu avant son premier mot, le temps que l'œil y arrive. */
private const val LEAD_MS = 120L

/** Une note tenue au moins aussi longtemps reçoit un halo. */
private const val HELD_MS = 650L

/**
 * Position de lecture pour les paroles, avancée à chaque image par [LyricClock] (jamais saccadée ni à
 * reculons). C'est un état : lu dans un `draw`, seul ce dessin suit chaque image.
 */
@Composable
internal fun rememberLyricClock(vm: AppViewModel): State<Long> {
    val clock = remember { mutableLongStateOf(vm.positionMs()) }
    val engine = remember { LyricClock(clock.longValue) }
    val playing by vm.playing.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val moving = playing && !player.buffering
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(moving) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!moving) {
                // À l'arrêt, inutile de redessiner à chaque image : on suit seulement une recherche éventuelle.
                while (true) {
                    clock.longValue = engine.frame(SystemClock.uptimeMillis().toDouble(), vm.positionMs(), playing = false, jumpKey = vm.snapshot.value.anchor)
                    delay(150)
                }
            } else {
                while (true) {
                    withFrameNanos { nanos ->
                        clock.longValue = engine.frame(nanos / 1_000_000.0, vm.positionMs(), playing = true, jumpKey = vm.snapshot.value.anchor)
                    }
                }
            }
        }
    }
    return clock
}

/** Paroles synchronisées plein écran : la ligne chantée au tiers haut, les autres estompées et floutées. */
@Composable
internal fun SyncedLyrics(lyrics: Lyrics, vm: AppViewModel) {
    val clock = rememberLyricClock(vm)
    val active by remember(lyrics) { derivedStateOf { lyrics.indexAt(clock.value + LEAD_MS) } }
    val state = rememberLazyListState()
    val reduce = LocalReduceMotion.current
    // Défiler à la main suspend le suivi (et le flou) quelques secondes, pour lire tranquillement.
    val dragged by state.interactionSource.collectIsDraggedAsState()
    var browsing by remember { mutableStateOf(false) }
    LaunchedEffect(dragged) {
        if (dragged) browsing = true
        else if (browsing) { delay(2_500); browsing = false }
    }
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().fadeEdges()) {
        val top = maxHeight * 0.26f
        LaunchedEffect(active, browsing) {
            if (active < 0 || browsing) return@LaunchedEffect
            val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
            if (item != null && !reduce) {
                state.animateScrollBy(item.offset.toFloat(), tween(700, easing = Motion.Ease))
            } else {
                state.scrollToItem(active)
            }
        }
        LazyColumn(state = state, contentPadding = PaddingValues(top = top, bottom = maxHeight * 0.6f), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lyrics.lines) { i, line ->
                val isActive = i == active
                val past = i < active
                val distance = if (active < 0) i + 1 else abs(i - active)
                val alpha by animateFloatAsState(
                    when {
                        isActive -> 1f
                        browsing -> 0.75f
                        past -> 0.42f
                        else -> 0.58f
                    },
                    if (reduce) snap() else tween(420, easing = Motion.Ease), label = "a",
                )
                val scale by animateFloatAsState(
                    if (isActive) 1f else 0.94f,
                    if (reduce) snap() else spring(dampingRatio = 0.85f, stiffness = 180f), label = "s",
                )
                val blur by animateDpAsState(
                    if (reduce || browsing || isActive) 0.dp else (min(distance, 4) * 1.1f).dp,
                    if (reduce) snap() else tween(500), label = "flou",
                )
                KaraokeLine(
                    line = line,
                    clock = clock,
                    active = isActive,
                    past = past,
                    nextTimeMs = lyrics.lines.getOrNull(i + 1)?.timeMs,
                    style = Type.Lyric,
                    glow = !reduce,
                    rise = !reduce,
                    modifier = Modifier.fillMaxWidth()
                        .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0.5f) }
                        .then(if (blur > 0.05.dp) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded) else Modifier)
                        .pressable(scale = 0.99f) { vm.send(Cmd.Seek(line.timeMs)) }
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/**
 * Bandeau d'une ligne au-dessus de la barre : la ligne chantée glisse vers le haut quand la suivante arrive,
 * et se remplit mot à mot. Hauteur fixe : la pochette ne bouge pas. Toucher ouvre les paroles complètes.
 */
@Composable
internal fun LyricStrip(lyrics: Lyrics, vm: AppViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val clock = rememberLyricClock(vm)
    val index by remember(lyrics) { derivedStateOf { lyrics.indexAt(clock.value + LEAD_MS) } }
    Box(
        modifier.fillMaxWidth().height(STRIP_HEIGHT).clipToBounds().pressable(scale = 0.98f, onClick = onOpen),
        contentAlignment = Alignment.CenterStart,
    ) {
        AnimatedContent(
            index,
            transitionSpec = {
                if (reduce) EnterTransition.None togetherWith ExitTransition.None
                else (slideInVertically(tween(340, easing = FastOutSlowInEasing)) { (it * 0.35f).toInt() } + fadeIn(tween(340, easing = FastOutSlowInEasing))) togetherWith
                    (slideOutVertically(tween(340, easing = FastOutSlowInEasing)) { -(it * 0.35f).toInt() } + fadeOut(tween(340, easing = FastOutSlowInEasing)))
            },
            label = "ligne",
        ) { i ->
            val line = lyrics.lines.getOrNull(i)
            if (line == null || line.text.isBlank()) {
                Text("♪", style = StripStyle, color = Ink.Primary.copy(alpha = 0.5f))
            } else {
                KaraokeLine(
                    line = line, clock = clock, active = true, past = false,
                    nextTimeMs = lyrics.lines.getOrNull(i + 1)?.timeMs,
                    style = StripStyle, glow = false, rise = false, dimAlpha = 0.42f, maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private val STRIP_HEIGHT = 24.dp
private val StripStyle = Type.PlayerArtist.copy(fontWeight = FontWeight(600), lineHeight = 20.sp)

/** Un mot de la ligne : ses caractères [from, to) dans le texte et le moment où il est chanté. */
private class WordSpan(val from: Int, val to: Int, val startMs: Long, val endMs: Long) {
    val held get() = endMs - startMs >= HELD_MS
}

private fun spansOf(line: LyricLine): List<WordSpan> {
    val words = line.words ?: return emptyList()
    var at = 0
    return words.map { w ->
        val span = WordSpan(at, at + w.text.length, w.startMs, w.endMs.coerceAtLeast(w.startMs + 1))
        at += w.text.length
        span
    }
}

/** Montée d'un mot : il se soulève pendant qu'il est chanté et reste levé jusqu'à la fin de la ligne. */
private fun liftOf(span: WordSpan, positionMs: Long): Float {
    val p = ((positionMs - span.startMs).toFloat() / (span.endMs - span.startMs).coerceAtLeast(220)).coerceIn(0f, 1f)
    val inv = 1f - p
    return 1f - inv * inv * inv
}

/** Éclat d'une note tenue : monte avec elle, puis s'éteint en 300 ms une fois la note finie. */
private fun glowOf(span: WordSpan, positionMs: Long): Float {
    if (!span.held || positionMs < span.startMs) return 0f
    val p = ((positionMs - span.startMs).toFloat() / (span.endMs - span.startMs)).coerceIn(0f, 1f)
    val after = ((positionMs - span.endMs).toFloat() / 300f).coerceIn(0f, 1f)
    return sin(p * PI.toFloat() / 2) * (1f - after)
}

/**
 * Une ligne de paroles dessinée à la main sur sa mise en page : copie estompée, copie lumineuse effacée au-delà
 * du mot chanté (avec un bord adouci), chaque mot soulevé à son tour, et un halo flouté derrière les notes tenues.
 * Seule la ligne en cours lit l'horloge : les autres ne se redessinent pas pendant la lecture.
 */
@Composable
private fun KaraokeLine(
    line: LyricLine,
    clock: State<Long>,
    active: Boolean,
    past: Boolean,
    nextTimeMs: Long?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    glow: Boolean = true,
    rise: Boolean = true,
    dimAlpha: Float = 0.34f,
    maxLines: Int = Int.MAX_VALUE,
) {
    if (line.text.isBlank()) {
        InterludeDots(line.timeMs, nextTimeMs, clock, active, modifier)
        return
    }
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val spans = remember(line) { spansOf(line) }
    val density = LocalDensity.current
    val risePx = if (rise) with(density) { 3.dp.toPx() } else 0f
    val featherPx = with(density) { 22.dp.toPx() }
    // Les mots retombent en douceur quand la ligne n'est plus chantée.
    val liftScale by animateFloatAsState(if (active) 1f else 0f, tween(450, easing = Motion.Ease), label = "montée")
    val time: () -> Long = {
        when {
            active -> clock.value
            past -> Long.MAX_VALUE / 4
            else -> Long.MIN_VALUE / 4
        }
    }

    Box(modifier) {
        if (glow && active && spans.any { it.held }) {
            Text(
                line.text, style = style, color = Color.Transparent, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.blur(10.dp, BlurredEdgeTreatment.Unbounded).drawWithContent {
                    val l = layout ?: return@drawWithContent
                    val t = time()
                    for (span in spans) {
                        val g = glowOf(span, t)
                        if (g <= 0.01f) continue
                        drawSpan(l, span, -risePx * liftScale * liftOf(span, t), Ink.Primary.copy(alpha = 0.85f * g))
                    }
                },
            )
        }
        Text(
            line.text, style = style, color = Color.Transparent, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout = it },
            modifier = Modifier.drawWithContent {
                val l = layout ?: return@drawWithContent
                val t = time()
                val dim = Ink.Primary.copy(alpha = dimAlpha)
                when {
                    // Ligne déjà chantée ou à venir, sans mot levé : un seul dessin, rien ne bouge.
                    !active && liftScale <= 0.001f -> drawText(l, if (past) Ink.Primary else dim)
                    spans.isEmpty() -> drawText(l, if (t >= line.timeMs) Ink.Primary else dim)
                    else -> drawSung(l, line, spans, t, risePx * liftScale, featherPx, dim)
                }
            },
        )
    }
}

/** Dessine la ligne mot par mot (soulevés), puis la copie lumineuse effacée au-delà de ce qui est chanté. */
private fun ContentDrawScope.drawSung(l: TextLayoutResult, line: LyricLine, spans: List<WordSpan>, t: Long, rise: Float, feather: Float, dim: Color) {
    for (span in spans) drawSpan(l, span, -rise * liftOf(span, t), dim)
    if (t <= line.timeMs) return
    val sung = Karaoke.progress(line, t)
    val bounds = Rect(-feather, -rise - 4f, size.width + feather, size.height + 4f)
    drawIntoCanvas { canvas ->
        canvas.saveLayer(bounds, Paint())
        for (span in spans) drawSpan(l, span, -rise * liftOf(span, t), Ink.Primary)
        if (sung < line.text.length) eraseUnsung(l, sung, rise, feather)
        canvas.restore()
    }
}

/** Le mot [span] seul (découpé ligne visuelle par ligne visuelle), décalé verticalement de [dy]. */
private fun DrawScope.drawSpan(l: TextLayoutResult, span: WordSpan, dy: Float, color: Color) {
    val textEnd = l.layoutInput.text.length
    for (row in 0 until l.lineCount) {
        val rowStart = l.getLineStart(row)
        val rowEnd = l.getLineEnd(row, visibleEnd = true)
        val from = maxOf(span.from, rowStart)
        val to = minOf(span.to, rowEnd)
        if (from >= to) continue
        val left = if (from <= rowStart) l.getLineLeft(row) else l.getHorizontalPosition(from, usePrimaryDirection = true)
        val right = if (to >= rowEnd || to >= textEnd) l.getLineRight(row) else l.getHorizontalPosition(to, usePrimaryDirection = true)
        // Les jambages qui dépassent (j, g, italique) restent dessinés : on n'étend que vers le bas et le haut.
        clipRect(left, l.getLineTop(row) - 8f, right + 1f, l.getLineBottom(row) + 8f) {
            translate(top = dy) { drawText(l, color) }
        }
    }
}

/** Efface, dans la copie lumineuse, tout ce qui n'est pas encore chanté ; le bord est un dégradé de [feather]. */
private fun DrawScope.eraseUnsung(l: TextLayoutResult, sung: Float, rise: Float, feather: Float) {
    val whole = sung.toInt()
    for (row in 0 until l.lineCount) {
        val rowStart = l.getLineStart(row)
        val rowEnd = l.getLineEnd(row, visibleEnd = true)
        val top = l.getLineTop(row) - rise - 8f
        val bottom = l.getLineBottom(row) + 8f
        when {
            sung >= rowEnd -> continue
            sung <= rowStart -> drawRect(Color.Black, Offset(0f, top), androidx.compose.ui.geometry.Size(size.width + feather, bottom - top), blendMode = BlendMode.DstOut)
            else -> {
                val box = l.getBoundingBox(whole.coerceIn(rowStart, (rowEnd - 1).coerceAtLeast(rowStart)))
                val edge = box.left + box.width * (sung - whole)
                val from = edge - feather / 2
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = from, endX = edge + feather / 2),
                    topLeft = Offset(from, top),
                    size = androidx.compose.ui.geometry.Size(size.width + feather - from, bottom - top),
                    blendMode = BlendMode.DstOut,
                )
            }
        }
    }
}

/** Passage instrumental : trois points qui s'allument l'un après l'autre en respirant, jusqu'à la ligne suivante. */
@Composable
private fun InterludeDots(startMs: Long, nextMs: Long?, clock: State<Long>, active: Boolean, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    val dot: Dp = 9.dp
    Box(modifier.height(34.dp).drawWithContent {
        val r = dot.toPx() / 2
        val gap = r * 3.4f
        val t = if (active) clock.value else Long.MIN_VALUE / 4
        val length = ((nextMs ?: (startMs + 4_000)) - startMs).coerceAtLeast(1)
        val p = ((t - startMs).toFloat() / length).coerceIn(0f, 1f)
        val breathe = if (active && !reduce) 1f + 0.12f * sin((t % 1_600) / 1_600f * 2 * PI.toFloat()) else 1f
        // Juste avant la ligne suivante, les points se résorbent.
        val fadeOut = if (active) (1f - ((p - 0.9f) / 0.1f).coerceIn(0f, 1f)) else 1f
        repeat(3) { k ->
            val lit = ((p * 3f) - k).coerceIn(0f, 1f)
            val c = Offset(r + k * gap, size.height / 2)
            drawCircle(Ink.Primary.copy(alpha = (0.28f + 0.72f * lit) * fadeOut), r * breathe * fadeOut.coerceAtLeast(0.6f), c)
        }
    })
}

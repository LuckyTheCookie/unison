package fr.lulu.unison.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import fr.lulu.unison.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Où en est le lecteur entre le mini-lecteur (0) et le plein écran (1). Le doigt le fait avancer pas à pas ;
 * relâché, il finit sa course du côté où il allait (ou de celui où on l'a lancé).
 */
@Stable
class PlayerSheetState(open: Boolean, private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(if (open) 1f else 0f)
        private set

    /** Vers où il va (ou où il est) : ouvert ou réduit. */
    var target by mutableStateOf(open)
        private set

    /** Place du mini-lecteur à l'écran : le lecteur en part et y revient. */
    var collapsed by mutableStateOf(Rect.Zero)

    /** Course du haut du lecteur, du mini-lecteur au haut de l'écran. */
    val travel: Float get() = collapsed.top.coerceAtLeast(1f)

    private var job: Job? = null

    fun drag(dy: Float) {
        job?.cancel()
        progress = (progress - dy / travel).coerceIn(0f, 1f)
    }

    fun snap(p: Float) {
        job?.cancel()
        progress = p.coerceIn(0f, 1f)
    }

    fun jumpTo(open: Boolean) {
        job?.cancel()
        target = open
        progress = if (open) 1f else 0f
    }

    /** [velocity] en px/s, positive vers le bas. */
    fun animateTo(open: Boolean, velocity: Float = 0f, reduce: Boolean = false) {
        job?.cancel()
        target = open
        val to = if (open) 1f else 0f
        if (progress == to) return
        job = scope.launch {
            animate(
                progress, to,
                initialVelocity = -velocity / travel,
                // Amorti sans rebond : il ne dépasse pas le plein écran.
                animationSpec = if (reduce) tween(Motion.Fast) else spring(dampingRatio = 1f, stiffness = 360f),
            ) { v, _ -> progress = v.coerceIn(0f, 1f) }
        }
    }

    /** Fin d'un geste : un geste vif décide, sinon le côté le plus proche. Rend l'état choisi. */
    fun settle(velocity: Float, reduce: Boolean): Boolean {
        val open = when {
            velocity < -FLING -> true
            velocity > FLING -> false
            else -> progress > 0.5f
        }
        animateTo(open, velocity, reduce)
        return open
    }

    private companion object {
        const val FLING = 900f
    }
}

@Composable
fun rememberPlayerSheetState(open: Boolean): PlayerSheetState {
    val scope = rememberCoroutineScope()
    return remember { PlayerSheetState(open, scope) }
}

/**
 * Le lecteur, qui naît du mini-lecteur : réduit, il en a la place et l'allure (verre, titre, commandes) ;
 * en grandissant, il remonte jusqu'au haut de l'écran et le lecteur plein écran apparaît. On le tire avec le
 * doigt dans les deux sens, y compris depuis le haut d'une liste (paroles, file), et le geste de retour
 * l'abaisse un peu avant de le refermer.
 *
 * [glass] habille le mini-lecteur ; [mini] en est le contenu ; [content] reçoit vrai tant que le lecteur bouge,
 * pour alléger ce qui coûte cher à dessiner.
 */
@Composable
fun PlayerSheet(
    state: PlayerSheetState,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    backEnabled: Boolean,
    glass: Modifier,
    mini: @Composable () -> Unit,
    content: @Composable (moving: Boolean) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val currentOpen by rememberUpdatedState(open)
    val change by rememberUpdatedState(onOpenChange)
    val reduceNow by rememberUpdatedState(reduce)

    LaunchedEffect(open) { if (state.target != open) state.animateTo(open, reduce = reduce) }

    fun settle(velocity: Float) {
        val o = state.settle(velocity, reduceNow)
        if (o != currentOpen) change(o)
    }

    PredictiveBackHandler(enabled = open && backEnabled) { events ->
        try {
            events.collect { e -> state.snap(1f - 0.22f * Motion.Ease.transform(e.progress)) }
            state.animateTo(false, reduce = reduceNow)
            change(false)
        } catch (e: CancellationException) {
            state.animateTo(true, reduce = reduceNow)
            throw e
        }
    }

    val rect = state.collapsed
    val showPlayer by remember { derivedStateOf { state.progress > 0f } }
    val showMini by remember { derivedStateOf { state.progress < 0.3f } }
    val moving by remember { derivedStateOf { state.progress < 1f } }
    if (rect == Rect.Zero && state.progress < 1f) return

    val density = LocalDensity.current
    val corner = with(density) { 16.dp.toPx() }
    // Le haut d'une liste tiré vers le bas abaisse le lecteur ; remonté, il le rouvre avant de défiler.
    val nested = remember(state) {
        object : NestedScrollConnection {
            var pulling = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y >= 0f || state.progress >= 1f) return Offset.Zero
                pulling = true
                state.drag(available.y)
                return Offset(0f, available.y)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                pulling = true
                state.drag(available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!pulling) return Velocity.Zero
                pulling = false
                settle(available.y)
                return available
            }
        }
    }

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val p = state.progress
                translationY = rect.top * (1f - p)
                shape = SheetShape(
                    left = lerp(rect.left, 0f, p),
                    right = lerp(rect.right, size.width, p),
                    height = lerp(rect.height, size.height, p),
                    corner = corner * (1f - p),
                )
                clip = true
            }
            .pointerInput(state) {
                val tracker = VelocityTracker()
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking(); total = 0f },
                    onDragEnd = { settle(tracker.calculateVelocity().y) },
                    onDragCancel = { settle(0f) },
                ) { change, dy ->
                    change.consume()
                    // Les positions bougent avec le lecteur : on suit le chemin parcouru par le doigt.
                    total += dy
                    tracker.addPosition(change.uptimeMillis, Offset(0f, total))
                    state.drag(dy)
                }
            }
            .nestedScroll(nested),
    ) {
        if (showMini) {
            with(density) {
                Box(
                    Modifier.offset { IntOffset(rect.left.roundToInt(), 0) }.size(rect.width.toDp(), rect.height.toDp())
                        .graphicsLayer { alpha = (1f - state.progress / 0.15f).coerceIn(0f, 1f) }
                        .then(glass),
                )
            }
        }
        if (showPlayer) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = (state.progress / 0.15f).coerceIn(0f, 1f) }.background(Color.Black))
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((state.progress - 0.08f) / 0.42f).coerceIn(0f, 1f) }) {
                content(moving)
            }
        }
        if (showMini) {
            with(density) {
                Box(
                    Modifier.offset { IntOffset(rect.left.roundToInt(), 0) }.size(rect.width.toDp(), rect.height.toDp())
                        .graphicsLayer { alpha = (1f - state.progress / 0.25f).coerceIn(0f, 1f) },
                ) { mini() }
            }
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Rectangle arrondi calé en haut du lecteur : du mini-lecteur au plein écran. */
private class SheetShape(val left: Float, val right: Float, val height: Float, val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(left, 0f, right, height, CornerRadius(corner)))
}

package fr.lulu.unison.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import fr.lulu.unison.ui.theme.Motion
import kotlinx.coroutines.CancellationException

/**
 * Panneau plein écran (lecteur, « Ensemble ») qui suit le geste de retour prédictif : il rétrécit, s'arrondit
 * et glisse du côté du geste ; relâché, il se ferme, annulé, il revient en ressort.
 * [content] reçoit vrai pendant le geste, pour alléger ce qui coûte cher à dessiner.
 */
@Composable
fun PredictiveSheet(
    visible: Boolean,
    backEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (gesturing: Boolean) -> Unit,
) {
    AnimatedVisibility(
        visible,
        modifier = modifier,
        enter = slideInVertically(tween(Motion.Slow, easing = Motion.Ease)) { it } + fadeIn(tween(Motion.Fast)),
        exit = slideOutVertically(tween(Motion.Page)) { it } + fadeOut(tween(Motion.Page)),
    ) {
        val progress = remember { Animatable(0f) }
        var edge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
        var gesturing by remember { mutableStateOf(false) }

        PredictiveBackHandler(enabled = backEnabled && visible) { events ->
            gesturing = true
            try {
                events.collect { e ->
                    edge = e.swipeEdge
                    progress.snapTo(Motion.Ease.transform(e.progress))
                }
                gesturing = false
                onBack()
            } catch (e: CancellationException) {
                gesturing = false
                progress.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 420f))
                throw e
            }
        }

        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val p = progress.value
                val s = 1f - 0.1f * p
                scaleX = s; scaleY = s
                // Le panneau s'éloigne du bord d'où part le geste.
                val side = if (edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                translationX = side * p * 10.dp.toPx()
                transformOrigin = TransformOrigin(if (edge == BackEventCompat.EDGE_RIGHT) 0.2f else 0.8f, 0.5f)
                shape = RoundedCornerShape(32.dp * p)
                clip = p > 0f
            },
        ) { content(gesturing) }
    }
}

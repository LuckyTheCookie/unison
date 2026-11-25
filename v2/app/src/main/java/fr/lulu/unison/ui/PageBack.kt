package fr.lulu.unison.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import fr.lulu.unison.ui.theme.Motion
import kotlinx.coroutines.CancellationException

/**
 * Geste de retour entre les pages, sur le modèle du lecteur. Les pages n'ont pas de fond (le fond animé est
 * commun) : deux pages visibles en même temps se mélangent. Pendant le geste, seule la page qu'on quitte bouge
 * (elle rétrécit et s'écarte du bord) ; au relâché, elle s'efface, puis seulement la page d'en dessous entre.
 */
@Stable
class PageBack {
    /** Page qui suit le geste (identifiant de son entrée), ou null. */
    var entryId by mutableStateOf<String?>(null)
    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)

    /** Avancement du geste, déjà adouci. */
    val progress = Animatable(0f)

    /** Sortie après le relâché : 0 visible, 1 effacée. */
    val exit = Animatable(0f)
}

val LocalPageBack = staticCompositionLocalOf<PageBack?> { null }

/**
 * Prend en charge le geste de retour de la navigation. À placer juste après le NavHost : enregistré après le
 * sien, il passe avant lui ; les retours enregistrés plus tard (lecteur, recherche, sélection) passent avant.
 */
@Composable
fun PageBackHandler(navController: NavHostController, back: PageBack) {
    val current by navController.currentBackStackEntryAsState()
    val canPop = current != null && navController.previousBackStackEntry != null
    PredictiveBackHandler(enabled = canPop) { events ->
        val entry = navController.currentBackStackEntry
        back.exit.snapTo(0f)
        back.progress.snapTo(0f)
        back.entryId = entry?.id
        try {
            events.collect { e ->
                back.edge = e.swipeEdge
                back.progress.snapTo(Motion.Ease.transform(e.progress))
            }
            back.exit.animateTo(1f, tween(Motion.Fast))
            if (navController.currentBackStackEntry?.id == entry?.id) navController.popBackStack()
        } catch (e: CancellationException) {
            back.progress.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 420f))
            back.entryId = null
            throw e
        }
    }
}

/** Cadre d'une page : la fait suivre le geste de retour quand c'est elle qu'on quitte. */
@Composable
fun PageFrame(entry: NavBackStackEntry, content: @Composable () -> Unit) {
    val back = LocalPageBack.current
    val id = remember(entry) { entry.id }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            if (back == null || back.entryId != id) return@graphicsLayer
            val p = back.progress.value
            val x = back.exit.value
            val side = if (back.edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
            val s = (1f - 0.1f * p) * (1f - 0.06f * x)
            scaleX = s; scaleY = s
            translationX = side * p * 12.dp.toPx()
            transformOrigin = TransformOrigin(if (side < 0) 0.2f else 0.8f, 0.5f)
            alpha = 1f - x
        },
    ) { content() }
}

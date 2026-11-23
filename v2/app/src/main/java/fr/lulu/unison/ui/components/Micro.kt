package fr.lulu.unison.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.lulu.unison.R
import fr.lulu.unison.ui.LocalReduceMotion
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Motion
import fr.lulu.unison.ui.theme.Radius
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Favori : le cœur se remplit avec un rebond et une petite gerbe de points, avec un retour haptique.
 * À appeler dans un `key(track.id)` : changer de titre ne doit pas rejouer l'animation.
 */
@Composable
fun HeartButton(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    val previous = remember { booleanArrayOf(on) }
    LaunchedEffect(on) {
        if (on == previous[0]) return@LaunchedEffect
        previous[0] = on
        if (reduce) return@LaunchedEffect
        if (on) {
            burst.snapTo(0f)
            pop.snapTo(0.6f)
            coroutineScope {
                launch { burst.animateTo(1f, tween(480, easing = Motion.Ease)) }
                pop.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 380f))
            }
        } else {
            pop.snapTo(0.82f)
            pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 520f))
        }
    }
    Box(
        modifier.size(44.dp).drawBehind {
            val p = burst.value
            if (p >= 1f) return@drawBehind
            // Six points qui s'éloignent du cœur en s'éteignant.
            val from = size.minDimension * 0.42f
            val to = size.minDimension * 0.78f
            val r = from + (to - from) * p
            val dot = 2.6.dp.toPx() * (1f - p * 0.6f)
            repeat(6) { i ->
                val a = (i * 60f - 90f) * (PI / 180f).toFloat()
                drawCircle(Ink.Accent.copy(alpha = 1f - p), dot, Offset(center.x + r * cos(a), center.y + r * sin(a)))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.matchParentSize().clip(CircleShape).background(Ink.FillHover)
                .semantics { contentDescription = if (on) "Retirer des favoris" else "Ajouter aux favoris"; role = Role.Button }
                .pressable(scale = 0.88f) {
                    haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.Confirm)
                    onToggle()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (on) R.drawable.ic_heart_fill else R.drawable.ic_heart_line), contentDescription = null,
                tint = if (on) Ink.Accent else Ink.Primary,
                modifier = Modifier.size(24.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            )
        }
    }
}

/** Icône lecture / pause / chargement qui se transforme au lieu de sauter d'un état à l'autre. */
@Composable
fun PlayPauseIcon(playing: Boolean, buffering: Boolean, tint: Color, size: Dp, spinnerColor: Color = tint) {
    val reduce = LocalReduceMotion.current
    val state = when {
        buffering -> 2
        playing -> 1
        else -> 0
    }
    AnimatedContent(
        state,
        transitionSpec = {
            if (reduce) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(tween(Motion.Fast)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = 600f), initialScale = 0.55f)) togetherWith
                (fadeOut(tween(110)) + scaleOut(tween(Motion.Fast), targetScale = 0.55f))
        },
        contentAlignment = Alignment.Center,
        label = "lecture",
    ) { s ->
        when (s) {
            2 -> Spinner(size = size * 0.9f, color = spinnerColor)
            1 -> Glyph(R.drawable.ic_pause_fill, tint = tint, size = size)
            else -> Glyph(R.drawable.ic_play_fill, tint = tint, size = size)
        }
    }
}

/** Bouton lecture / pause rond, avec son libellé pour l'accessibilité. */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    buffering: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp,
    tint: Color = Ink.Primary,
    enabled: Boolean = true,
) {
    Box(
        modifier.clip(CircleShape)
            .semantics { contentDescription = if (playing) "Pause" else "Lecture"; role = Role.Button }
            .pressable(enabled, scale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PlayPauseIcon(playing, buffering, tint, iconSize, spinnerColor = if (tint == Ink.Primary) Ink.Secondary else tint)
    }
}

/**
 * Texte dont les changements roulent verticalement (compteur de membres…) : vers le haut quand le nombre
 * qu'il contient augmente, vers le bas quand il baisse.
 */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        text,
        modifier = modifier,
        transitionSpec = {
            if (reduce) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
            val up = (leadingNumber(targetState) ?: 0) >= (leadingNumber(initialState) ?: 0)
            val sign = if (up) 1 else -1
            (slideInVertically(tween(Motion.Page, easing = Motion.Ease)) { sign * it / 2 } + fadeIn(tween(Motion.Page))) togetherWith
                (slideOutVertically(tween(Motion.Page, easing = Motion.Ease)) { -sign * it / 2 } + fadeOut(tween(Motion.Fast)))
        },
        label = "texte",
    ) { t -> Text(t, style = style, color = color, maxLines = maxLines) }
}

private fun leadingNumber(text: String): Int? = Regex("\\d+").find(text)?.value?.toIntOrNull()

/** Reflet qui balaie un bloc de chargement ; lu au dessin, il ne recompose rien. */
@Composable
fun Modifier.shimmer(): Modifier {
    if (LocalReduceMotion.current) return this
    val t = rememberInfiniteTransition(label = "chargement")
    val phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_300, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val band = size.width * 0.5f
            val x = -band + (size.width + 2 * band) * phase.value
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent), startX = x, endX = x + band),
                blendMode = BlendMode.SrcAtop,
            )
        }
}

/** Section en cours de chargement : titre et trois cartes, à la place d'un simple indicateur. */
@Composable
fun SkeletonSection(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 26.dp).shimmer()) {
        Box(Modifier.padding(start = 20.dp, bottom = 14.dp).width(180.dp).height(22.dp).clip(Radius.Control).background(Ink.Fill))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) {
                Column {
                    Box(Modifier.size(150.dp).clip(Radius.Item).background(Ink.Fill))
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.width(120.dp).height(14.dp).clip(Radius.Control).background(Ink.Fill))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(80.dp).height(12.dp).clip(Radius.Control).background(Ink.FillSubtle))
                }
            }
        }
    }
}

/** Liste de titres en cours de chargement. */
@Composable
fun SkeletonRows(count: Int = 8, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp).shimmer()) {
        repeat(count) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(Radius.Control).background(Ink.Fill))
                Spacer(Modifier.width(12.dp))
                Column {
                    Box(Modifier.width(190.dp).height(14.dp).clip(Radius.Control).background(Ink.Fill))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(120.dp).height(12.dp).clip(Radius.Control).background(Ink.FillSubtle))
                }
            }
        }
    }
}

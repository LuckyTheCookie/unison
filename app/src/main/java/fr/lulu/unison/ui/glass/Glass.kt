package fr.lulu.unison.ui.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type
import fr.lulu.unison.ui.theme.isExpressive

/** Ce que le verre réfracte (le fond de l'écran courant). */
val LocalBackdrop = compositionLocalOf<Backdrop> { emptyBackdrop() }

@Composable
fun ProvideBackdrop(backdrop: Backdrop, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalBackdrop provides backdrop, content = content)

enum class GlassStyle { Clear, Regular, Thick }

/** Équivalent Material d'une forme « verre » (coins continus → coins arrondis M3). */
fun Shape.toMaterial(): Shape = when (this) {
    is Capsule -> CircleShape
    is RoundedRectangle -> RoundedCornerShape(cornerRadius + 4.dp)
    else -> this
}

/**
 * Surface en verre liquide : réfraction sur les bords (lentille), flou,
 * vibrance, reflet spéculaire et ombre douce.
 */
fun Modifier.glass(
    backdrop: Backdrop,
    shape: Shape,
    style: GlassStyle = GlassStyle.Regular,
    tint: Color = Color.Unspecified,
    refraction: Boolean = true,
    shadow: Boolean = true,
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        when (style) {
            GlassStyle.Clear -> blur(1.dp.toPx())
            GlassStyle.Regular -> blur(6.dp.toPx())
            GlassStyle.Thick -> blur(18.dp.toPx())
        }
        if (refraction) lens(
            refractionHeight = 14.dp.toPx(),
            refractionAmount = if (style == GlassStyle.Clear) 28.dp.toPx() else 22.dp.toPx(),
            depthEffect = true,
            chromaticAberration = style == GlassStyle.Clear,
        )
    },
    highlight = {
        Highlight(
            width = 0.75.dp,
            alpha = 1f,
            style = if (shape is Capsule) HighlightStyle.Default(color = Color.White.copy(alpha = 0.55f)) else HighlightStyle.Plain(Color.White.copy(alpha = 0.18f)),
        )
    },
    shadow = if (shadow) ({ Shadow(radius = 28.dp, color = Color.Black.copy(alpha = 0.28f)) }) else null,
    onDrawSurface = {
        val base = when (style) {
            GlassStyle.Clear -> Color.White.copy(alpha = 0.04f)
            GlassStyle.Regular -> Color.White.copy(alpha = 0.08f)
            GlassStyle.Thick -> Color(0xFF1B1B24).copy(alpha = 0.55f)
        }
        drawRect(base)
        if (tint != Color.Unspecified) drawRect(tint)
    },
)

/** Conteneur : verre liquide, ou surface tonale Material 3. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedRectangle(28.dp),
    style: GlassStyle = GlassStyle.Regular,
    tint: Color = Color.Unspecified,
    refraction: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    if (isExpressive) {
        val cs = MaterialTheme.colorScheme
        Surface(
            modifier = modifier,
            shape = shape.toMaterial(),
            color = when (style) {
                GlassStyle.Clear -> cs.surfaceContainerLow
                GlassStyle.Regular -> cs.surfaceContainer
                GlassStyle.Thick -> cs.surfaceContainerHigh
            },
        ) { Box(contentAlignment = contentAlignment, content = content) }
    } else {
        Box(
            modifier.glass(LocalBackdrop.current, shape, style, tint, refraction),
            contentAlignment = contentAlignment,
            content = content,
        )
    }
}

/** Modificateur « bouton » : léger rebond au toucher + vibration. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, haptic: Boolean = true, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val haptics = LocalHapticFeedback.current
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
            if (haptic) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassStyle.Regular,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit,
) {
    if (isExpressive) {
        // Bouton tonal dont la forme se resserre au toucher (morphing M3 Expressive).
        FilledTonalButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            modifier = modifier.heightIn(min = 52.dp),
            enabled = enabled,
            colors = if (tint != Color.Unspecified) ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) else ButtonDefaults.filledTonalButtonColors(),
            contentPadding = contentPadding,
            content = content,
        )
        return
    }
    val backdrop = LocalBackdrop.current
    Row(
        modifier
            .pressable(enabled, onClick = onClick)
            .glass(backdrop, Capsule(), style, tint)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = size * 0.46f,
    style: GlassStyle = GlassStyle.Regular,
    tint: Color = Color.Unspecified,
    iconTint: Color = Ink.Primary,
) {
    if (isExpressive) {
        FilledTonalIconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            modifier = modifier.size(size),
        ) { Icon(icon, contentDescription, Modifier.size(iconSize)) }
        return
    }
    Box(
        modifier
            .size(size)
            .pressable(onClick = onClick)
            .glass(LocalBackdrop.current, Capsule(), style, tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = iconTint)
    }
}

/** Bouton principal plein, pour l'action la plus importante d'un écran. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = Color.White,
    textColor: Color = Color.Black,
    style: TextStyle = Type.Headline,
) {
    if (isExpressive) {
        Button(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            modifier = modifier.heightIn(min = 60.dp),
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(22.dp))
                Spacer(Modifier.size(10.dp))
            }
            Text(text, style = MaterialTheme.typography.titleMediumEmphasized)
        }
        return
    }
    Row(
        modifier
            .pressable(enabled, onClick = onClick)
            .drawBackdrop(
                backdrop = emptyBackdrop(),
                shape = { Capsule() },
                effects = {},
                highlight = { Highlight(width = 0.75.dp, style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.9f))) },
                shadow = { Shadow(radius = 24.dp, color = color.copy(alpha = 0.35f)) },
                onDrawSurface = { drawRect(color.copy(alpha = if (enabled) 1f else 0.4f)) },
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = textColor)
            Spacer(Modifier.size(8.dp))
        }
        Text(text, style = style, color = textColor)
    }
}

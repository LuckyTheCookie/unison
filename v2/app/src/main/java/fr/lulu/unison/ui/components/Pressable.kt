package fr.lulu.unison.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Retour tactile sobre : l'élément se contracte légèrement sous le doigt, sans onde Material.
 * Un nœud de modificateur (pas de `composed`) : rien n'est alloué à la composition, ce qui compte dans les listes.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    scale: Float = 0.97f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = this
    .then(PressScaleElement(scale, enabled))
    .combinedClickable(interactionSource = null, indication = null, enabled = enabled, onLongClick = onLongClick, onClick = onClick)

private class PressScaleElement(val scale: Float, val enabled: Boolean) : ModifierNodeElement<PressScaleNode>() {
    override fun create() = PressScaleNode(scale, enabled)
    override fun update(node: PressScaleNode) {
        node.pressedScale = scale
        node.enabled = enabled
    }
    override fun equals(other: Any?) = other is PressScaleElement && other.scale == scale && other.enabled == enabled
    override fun hashCode() = 31 * scale.hashCode() + enabled.hashCode()
}

private class PressScaleNode(var pressedScale: Float, enabled: Boolean) : Modifier.Node(), PointerInputModifierNode, LayoutModifierNode {
    var enabled by mutableStateOf(enabled)
    private val current = Animatable(1f)
    private var job: Job? = null
    private var down = false
    private var downAt = 0L

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        // Passe finale : on sait alors si un défilement a pris le geste.
        if (pass != PointerEventPass.Final) return
        val pressed = pointerEvent.changes.any { it.pressed }
        val scrolled = pointerEvent.changes.any { it.isConsumed && it.positionChanged() }
        when {
            !down && pressed && enabled && pointerEvent.type == PointerEventType.Press -> {
                down = true
                downAt = pointerEvent.changes.first().uptimeMillis
                press()
            }
            down && scrolled -> { down = false; settle(tap = false) }
            down && !pressed -> { down = false; settle(tap = pointerEvent.changes.first().uptimeMillis - downAt < PRESS_DELAY_MS) }
        }
    }

    override fun onCancelPointerInput() {
        if (down) { down = false; settle(tap = false) }
    }

    /** Petit délai avant de se contracter : un doigt qui commence à faire défiler la liste ne fait rien bouger. */
    private fun press() {
        job?.cancel()
        job = coroutineScope.launch {
            delay(PRESS_DELAY_MS)
            current.animateTo(pressedScale, PressSpring)
        }
    }

    /** Un appui trop bref pour s'être vu se contracte quand même, le temps d'un battement. */
    private fun settle(tap: Boolean) {
        job?.cancel()
        job = coroutineScope.launch {
            if (tap && current.value == 1f) current.animateTo(pressedScale, tween(TAP_MS))
            current.animateTo(1f, PressSpring)
        }
    }

    private val layer: GraphicsLayerScope.() -> Unit = {
        val s = current.value
        scaleX = s; scaleY = s
        alpha = if (this@PressScaleNode.enabled) 1f else 0.4f
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.placeWithLayer(0, 0, layerBlock = layer) }
    }

    private companion object {
        const val PRESS_DELAY_MS = 40L
        const val TAP_MS = 70
        val PressSpring = spring<Float>(dampingRatio = 0.7f, stiffness = 520f)
    }
}

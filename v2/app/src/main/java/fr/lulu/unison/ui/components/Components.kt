package fr.lulu.unison.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.lulu.unison.R
import fr.lulu.unison.library.DownloadState
import fr.lulu.unison.model.Track
import fr.lulu.unison.offline.DownloadStatus
import fr.lulu.unison.offline.OfflineArt
import fr.lulu.unison.ui.LocalDownloads
import fr.lulu.unison.ui.LocalReduceMotion
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type
import fr.lulu.unison.youtube.YtParser

fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Icône Mingcute (ressource vectorielle), teintée. */
@Composable
fun Glyph(@DrawableRes res: Int, modifier: Modifier = Modifier, tint: Color = Ink.Primary, size: Dp = 24.dp) {
    Icon(painterResource(res), contentDescription = null, modifier = modifier.size(size), tint = tint)
}

@Composable
fun IconBtn(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    tint: Color = Ink.Primary,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(background).pressable(enabled, scale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(iconSize), tint = tint)
    }
}

/** Pochette ; les images Google sont demandées à la bonne taille. */
@Composable
fun Cover(url: String?, size: Dp, modifier: Modifier = Modifier, shape: Shape = Radius.Item) {
    val px = when {
        size <= 64.dp -> 120
        size <= 180.dp -> 360
        else -> 720
    }
    Box(modifier.size(size).clip(shape).background(Ink.Fill), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) Glyph(R.drawable.ic_music_fill, tint = Ink.Tertiary, size = size * 0.4f)
        else AsyncImage(
            // Pochette d'un titre téléchargé : le fichier local, qui s'affiche aussi sans réseau.
            model = OfflineArt[url] ?: YtParser.resize(url, px),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Repère « téléchargé » : flèche cerclée quand le titre est sur le téléphone, anneau d'avancement pendant
 * le téléchargement, rien sinon.
 */
@Composable
fun DownloadBadge(status: DownloadStatus?, size: Dp = 14.dp, modifier: Modifier = Modifier) {
    // L'anneau qui se change en coche fait un petit « pop » ; une ligne déjà téléchargée n'en fait pas.
    val wasRunning = remember { booleanArrayOf(false) }
    val pop = remember { Animatable(1f) }
    val reduce = LocalReduceMotion.current
    val done = status?.done == true
    LaunchedEffect(done) {
        if (done && wasRunning[0] && !reduce) {
            pop.snapTo(0.3f)
            pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
        }
    }
    when {
        status == null || status.state == DownloadState.FAILED -> Unit
        done -> Glyph(R.drawable.ic_downloaded_fill, modifier.padding(end = 6.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }, tint = Ink.Accent, size = size)
        else -> {
            wasRunning[0] = true
            androidx.compose.foundation.Canvas(modifier.padding(end = 6.dp).size(size)) {
                val stroke = this.size.minDimension * 0.16f
                val inset = stroke / 2
                val arc = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                val style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawArc(Ink.FillStrong, 0f, 360f, false, topLeft, arc, style = style)
                val paused = status.state == DownloadState.PAUSED
                drawArc(if (paused) Ink.Tertiary else Ink.Accent, -90f, 360f * status.progress.coerceAtLeast(0.04f), false, topLeft, arc, style = style)
            }
        }
    }
}

@Composable
fun ExplicitBadge() {
    Box(
        Modifier.padding(end = 6.dp).size(15.dp).clip(RoundedCornerShape(4.dp)).background(Ink.FillStrong),
        contentAlignment = Alignment.Center,
    ) { Text("E", style = Type.Caption.copy(fontSize = 9.sp, lineHeight = 10.sp), color = Ink.Secondary) }
}

/** Barres animées sur le titre en cours de lecture ; lues au dessin, elles ne recomposent ni ne remesurent rien. */
@Composable
fun PlayingBars(active: Boolean, modifier: Modifier = Modifier, color: Color = Ink.Primary) {
    val t = rememberInfiniteTransition(label = "bars")
    val bars = listOf(520, 380, 640).map { period ->
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(period), RepeatMode.Reverse), label = "bar")
    }
    androidx.compose.foundation.Canvas(modifier.size(18.dp)) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * 2) / 3
        val r = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
        bars.forEachIndexed { i, bar ->
            val h = size.height * (if (active) bar.value else 0.3f)
            drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(i * (w + gap), size.height - h), size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = r)
        }
    }
}

/**
 * Ligne de titre : 64 dp, pochette, titre et artiste, durée, menu. Avec [onSwipe], la ligne se glisse vers la
 * droite pour « Lire ensuite » (un cran haptique au seuil) ; à ne pas utiliser dans une liste horizontale.
 */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showCover: Boolean = true,
    current: Boolean = false,
    playing: Boolean = false,
    subtitle: String = track.artist,
    onMore: (() -> Unit)? = null,
    onSwipe: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val offset = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    Box(modifier.fillMaxWidth().height(64.dp)) {
        if (onSwipe != null) {
            Box(
                Modifier.matchParentSize().padding(start = 24.dp).graphicsLayer {
                    val p = (offset.value / threshold).coerceIn(0f, 1f)
                    alpha = p
                    scaleX = 0.6f + 0.4f * p; scaleY = scaleX
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                },
                contentAlignment = Alignment.CenterStart,
            ) { Glyph(R.drawable.ic_queue_line, tint = Ink.Accent, size = 24.dp) }
        }
        Row(
            Modifier.fillMaxSize()
                .then(if (onSwipe != null) Modifier.swipeToAct(offset, threshold, "Lire ensuite", onSwipe) else Modifier)
                .pressable(scale = 0.985f, onLongClick = onMore, onClick = onClick).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) {
                Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                    if (current) PlayingBars(playing) else Text("$index", style = Type.Label, color = Ink.Tertiary)
                }
            }
            if (showCover) {
                Box(contentAlignment = Alignment.Center) {
                    Cover(track.cover, 46.dp, shape = RoundedCornerShape(8.dp))
                    if (current && index == null) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            PlayingBars(playing)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(track.title, style = Type.Row, color = if (current) Ink.Accent else Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadBadge(LocalDownloads.current[track.id])
                    if (track.explicit) ExplicitBadge()
                    Text(subtitle, style = Type.Body, color = Ink.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) trailing()
            else {
                if (track.durationMs > 0) Text(formatTime(track.durationMs), style = Type.Time, color = Ink.Tertiary, modifier = Modifier.padding(start = 10.dp))
                if (onMore != null) IconBtn(R.drawable.ic_more_line, "Plus d'options", onMore, size = 40.dp, iconSize = 20.dp, tint = Ink.Secondary)
            }
        }
    }
}

private val SWIPE_THRESHOLD = 80.dp

/**
 * Glisser vers la droite : la ligne suit le doigt, résiste passé [threshold] (avec un cran haptique), puis
 * revient en ressort ; l'action part si le geste finit au-delà du seuil. Aussi proposée à TalkBack.
 */
@Composable
private fun Modifier.swipeToAct(offset: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, threshold: Float, label: String, onAct: () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val act by rememberUpdatedState(onAct)
    val back = spring<Float>(dampingRatio = 0.7f, stiffness = 420f)
    return this
        .semantics { customActions = listOf(CustomAccessibilityAction(label) { act(); true }) }
        .graphicsLayer { translationX = offset.value }
        .pointerInput(threshold) {
            var crossed = false
            detectHorizontalDragGestures(
                onDragStart = { crossed = false },
                onDragEnd = {
                    scope.launch {
                        if (offset.value >= threshold) act()
                        offset.animateTo(0f, back)
                    }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f, back) } },
            ) { change, dx ->
                val x = offset.value
                val next = (x + dx * if (x > threshold) 0.3f else 1f).coerceAtLeast(0f)
                if (next > 0f || dx > 0f) change.consume()
                scope.launch { offset.snapTo(next) }
                val over = next >= threshold
                if (over != crossed) {
                    crossed = over
                    if (over) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                }
            }
        }
}

/** Panneau de verre : remplissage blanc à faible opacité, liseré discret. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = Radius.Panel,
    padding: PaddingValues = PaddingValues(vertical = 6.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.clip(shape).background(Ink.Fill).border(0.5.dp, Ink.Hairline, shape).padding(padding),
        content = content,
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(36.dp).clip(Radius.Control)
            .background(if (selected) Ink.ButtonBg else Ink.Fill)
            .pressable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.Label, color = if (selected) Ink.ButtonText else Ink.Primary) }
}

/** Petite étiquette d'information (année, nombre de titres…). */
@Composable
fun MetaChip(text: String, @DrawableRes icon: Int? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(Ink.Fill).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Glyph(icon, tint = Ink.Secondary, size = 14.dp); Spacer(Modifier.width(6.dp)) }
        Text(text, style = Type.Caption, color = Ink.Secondary, maxLines = 1)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier.heightIn(min = 46.dp).clip(Radius.Control).background(Ink.ButtonBg).pressable(enabled, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Glyph(icon, tint = Ink.ButtonText, size = 20.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.Button, color = Ink.ButtonText, maxLines = 1)
    }
}

@Composable
fun GlassButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    tint: Color = Ink.Primary,
    enabled: Boolean = true,
) {
    Row(
        modifier.heightIn(min = 46.dp).clip(Radius.Control).background(Ink.FillHover).pressable(enabled, onClick = onClick)
            .padding(horizontal = if (text == null) 13.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Glyph(icon, tint = tint, size = 20.dp)
        if (icon != null && text != null) Spacer(Modifier.width(8.dp))
        if (text != null) Text(text, style = Type.Button, color = tint, maxLines = 1)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.Section, color = Ink.Primary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) {
            Text(
                action, style = Type.Label, color = Ink.Secondary,
                modifier = Modifier.clip(Radius.Control).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 26.dp, color: Color = Ink.Secondary) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "a")
    androidx.compose.foundation.Canvas(modifier.size(size).graphicsLayer { rotationZ = angle }) {
        drawArc(
            color = color, startAngle = 0f, sweepAngle = 270f, useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = this.size.minDimension * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

@Composable
fun CenterBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.fillMaxWidth().padding(vertical = 56.dp), contentAlignment = Alignment.Center, content = content)

/** État vide ou erreur, avec une action facultative. */
@Composable
fun Notice(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    @DrawableRes icon: Int? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) { Glyph(icon, tint = Ink.Tertiary, size = 40.dp); Spacer(Modifier.height(14.dp)) }
        Text(title, style = Type.Title, color = Ink.Primary, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(detail, style = Type.Body, color = Ink.Secondary, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            GlassButton(action, onAction)
        }
    }
}

/** En-tête d'écran avec bouton retour. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconBtn(R.drawable.ic_left_line, "Retour", onBack)
        else Spacer(Modifier.width(14.dp))
        Text(title, style = Type.Title, color = Ink.Primary, modifier = Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** Pastille d'un membre de la session : initiale sur sa couleur. */
@Composable
fun Avatar(name: String, color: Int, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val bg = Color(color)
    val dark = (0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue) > 0.6f
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            style = Type.Button.copy(fontSize = (size.value * 0.42f).sp),
            color = if (dark) Color.Black else Color.White,
        )
    }
}

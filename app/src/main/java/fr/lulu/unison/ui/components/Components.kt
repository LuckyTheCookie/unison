package fr.lulu.unison.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.kyant.shapes.RoundedRectangle
import fr.lulu.unison.model.Member
import fr.lulu.unison.ui.theme.Ink
import kotlin.math.cos
import kotlin.math.sin

/** Avatar rond : initiale sur dégradé de la couleur du membre. */
@Composable
fun Avatar(member: Member?, size: Dp = 32.dp, ring: Color? = null, modifier: Modifier = Modifier) {
    val c = Color(member?.color ?: 0xFF888888.toInt())
    Box(
        modifier
            .size(size)
            .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(c, lerpColor(c, Color.Black, 0.35f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (member?.name?.firstOrNull()?.uppercase() ?: "?"),
            color = if (c.luminance() > 0.6f) Color.Black.copy(alpha = 0.8f) else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.44f).sp,
        )
    }
}

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue
private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f
)

/** Pochette arrondie (coins continus). */
@Composable
fun Artwork(url: String?, size: Dp, radius: Dp = size * 0.14f, modifier: Modifier = Modifier) {
    val shape = RoundedRectangle(radius)
    Box(
        modifier.size(size).clip(shape).background(Ink.Fill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = Ink.Tertiary, modifier = Modifier.size(size * 0.4f))
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Trois couleurs extraites de la pochette, animées quand le titre change. */
@Composable
fun rememberArtworkColors(url: String?): List<Color> {
    val context = LocalContext.current
    var colors by remember { mutableStateOf(DefaultAmbient) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) { colors = DefaultAmbient; return@LaunchedEffect }
        val req = ImageRequest.Builder(context).data(url).allowHardware(false).size(160).build()
        val result = context.imageLoader.execute(req) as? SuccessResult ?: return@LaunchedEffect
        val bmp: Bitmap = result.image.toBitmap()
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val fallback = DefaultAmbient
        colors = listOf(
            p.vibrantSwatch?.rgb ?: p.dominantSwatch?.rgb ?: fallback[0].toArgb(),
            p.darkVibrantSwatch?.rgb ?: p.mutedSwatch?.rgb ?: fallback[1].toArgb(),
            p.lightVibrantSwatch?.rgb ?: p.darkMutedSwatch?.rgb ?: fallback[2].toArgb(),
        ).map { Color(it) }
    }
    val a by animateColorAsState(colors[0], tween(1200), label = "c0")
    val b by animateColorAsState(colors[1], tween(1200), label = "c1")
    val c by animateColorAsState(colors[2], tween(1200), label = "c2")
    return listOf(a, b, c)
}

val DefaultAmbient = listOf(Color(0xFFB04CFF), Color(0xFF1E2B8F), Color(0xFFFF4F8B))

/**
 * Fond vivant : taches de couleur qui dérivent lentement (dégradé « mesh »),
 * pochette géante floutée par-dessus, puis voile sombre pour la lisibilité.
 */
@Composable
fun AmbientBackground(colors: List<Color>, artworkUrl: String?, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "ambient")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "phase")
    val breathe by t.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(7_000), RepeatMode.Reverse), label = "breathe")

    if (fr.lulu.unison.ui.theme.isExpressive) {
        ExpressiveBackground(colors, modifier)
        return
    }
    Box(modifier.background(Ink.Bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val blobs = listOf(
                Triple(colors[0], Offset(w * (0.25f + 0.18f * cos(phase)), h * (0.18f + 0.08f * sin(phase * 2))), w * 0.95f),
                Triple(colors[1], Offset(w * (0.85f + 0.12f * sin(phase)), h * (0.45f + 0.12f * cos(phase))), w * 1.05f),
                Triple(colors[2], Offset(w * (0.30f + 0.20f * sin(phase + 1.3f)), h * (0.82f + 0.06f * cos(phase * 1.5f))), w * 0.9f),
            )
            blobs.forEach { (c, center, r) ->
                drawCircle(
                    Brush.radialGradient(listOf(c.copy(alpha = 0.75f), c.copy(alpha = 0f)), center, r * breathe),
                    radius = r * breathe, center = center,
                )
            }
        }
        Crossfade(artworkUrl, animationSpec = tween(900), label = "art") { url ->
            if (!url.isNullOrBlank()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = 1.4f; scaleY = 1.4f; alpha = 0.55f }
                        .blur(90.dp),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.10f),
                    0.45f to Color.Black.copy(alpha = 0.25f),
                    1f to Color.Black.copy(alpha = 0.70f),
                )
            )
        )
    }
}

/** Logo : deux anneaux qui s'entrelacent et respirent. */
@Composable
fun UnisonLogo(size: Dp = 96.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "logo")
    val d by t.animateFloat(0.16f, 0.24f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "d")
    val expressive = fr.lulu.unison.ui.theme.isExpressive
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val a = if (expressive) listOf(cs.primary, cs.tertiary) else listOf(Color(0xFFFF6FB5), Color(0xFFB36BFF))
    val b = if (expressive) listOf(cs.secondary, cs.primary) else listOf(Color(0xFF7FD8FF), Color.White)
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension * 0.3f
        val cy = this.size.height / 2
        val cx = this.size.width / 2
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = this.size.minDimension * 0.07f)
        drawCircle(
            Brush.linearGradient(a),
            radius = r, center = Offset(cx - this.size.width * d / 1.4f, cy), style = stroke,
        )
        drawCircle(
            Brush.linearGradient(b),
            radius = r, center = Offset(cx + this.size.width * d / 1.4f, cy), style = stroke,
        )
    }
}

/**
 * Fond Material 3 Expressive : surface Material You, légèrement teintée par
 * la pochette en cours (dégradé doux, sans le flou du thème verre).
 */
@Composable
private fun ExpressiveBackground(colors: List<Color>, modifier: Modifier) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "m3bg")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(12_000), RepeatMode.Reverse), label = "drift")
    Box(modifier.background(cs.surface)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawCircle(
                Brush.radialGradient(listOf(cs.primaryContainer.copy(alpha = 0.55f), Color.Transparent), Offset(w * (0.2f + 0.2f * drift), h * 0.08f), w * 1.1f),
                radius = w * 1.1f, center = Offset(w * (0.2f + 0.2f * drift), h * 0.08f),
            )
            drawCircle(
                Brush.radialGradient(listOf(colors[0].copy(alpha = 0.16f), Color.Transparent), Offset(w * 0.9f, h * (0.55f - 0.1f * drift)), w),
                radius = w, center = Offset(w * 0.9f, h * (0.55f - 0.1f * drift)),
            )
            drawCircle(
                Brush.radialGradient(listOf(cs.tertiaryContainer.copy(alpha = 0.35f), Color.Transparent), Offset(w * 0.1f, h * 0.95f), w * 0.9f),
                radius = w * 0.9f, center = Offset(w * 0.1f, h * 0.95f),
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingSpinner(size: Dp = 24.dp, modifier: Modifier = Modifier) {
    if (fr.lulu.unison.ui.theme.isExpressive) {
        // Indicateur M3 Expressive : formes qui se transforment en tournant.
        androidx.compose.material3.LoadingIndicator(modifier.size(size * 1.4f))
        return
    }
    androidx.compose.material3.CircularProgressIndicator(
        modifier.size(size), color = Ink.Secondary, strokeWidth = (size.value / 12f).coerceAtLeast(2f).dp,
    )
}

fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun AvatarStack(members: List<Member>, size: Dp = 30.dp, max: Int = 4, modifier: Modifier = Modifier) {
    Box(modifier) {
        val shown = members.take(max)
        shown.forEachIndexed { i, m ->
            Avatar(m, size, ring = Color.Black.copy(alpha = 0.5f), modifier = Modifier.offset(x = (size * 0.62f) * i))
        }
        if (members.size > max) {
            Box(
                Modifier.offset(x = (size * 0.62f) * max).size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Text("+${members.size - max}", color = Color.White, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold) }
        }
        Box(Modifier.size(width = (size * 0.62f) * (minOf(members.size, max + 1) - 1) + size, height = size))
    }
}

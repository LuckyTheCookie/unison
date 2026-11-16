/*
 * Portage Android de Kawarp (https://github.com/better-lyrics/kawarp)
 * Copyright (c) 2026 Better Lyrics — licence MIT (voir THIRD_PARTY_NOTICES.md).
 * Bruit simplex : Ian McEwan, Ashima Arts — licence MIT.
 */
package fr.lulu.unison.ui.kawarp

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos

/**
 * Seconde moitié du pipeline : à chaque image, fondu entre l'ancienne et la nouvelle pochette floutée,
 * déformation de domaine par bruit simplex, puis vignette, saturation et tramage.
 * Les deux passes de l'original (déformation puis sortie) sont réunies en un seul shader.
 */
private const val KAWARP_AGSL = """
uniform shader current;
uniform shader next;
uniform float2 resolution;
uniform float texSize;
uniform float mixT;
uniform float time;
uniform float intensity;
uniform float saturation;
uniform float dithering;
uniform float scale;

float3 mod289_3(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float2 mod289_2(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float3 permute(float3 x) { return mod289_3(((x * 34.0) + 1.0) * x); }

float snoise(float2 v) {
    const float4 C = float4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
    float2 i = floor(v + dot(v, C.yy));
    float2 x0 = v - i + dot(i, C.xx);
    float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod289_2(i);
    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));
    float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m; m = m * m;
    float3 x = 2.0 * fract(p * C.www) - 1.0;
    float3 h = abs(x) - 0.5;
    float3 ox = floor(x + 0.5);
    float3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    float3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
}

float hash(float3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

half4 main(float2 fragCoord) {
    float2 screen = fragCoord / resolution;
    float2 uv = clamp((screen - 0.5) / scale + 0.5, 0.0, 1.0);
    float t = time * 0.05;

    float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(uv - 0.5));

    // Grands mouvements lents, puis un détail plus fin par-dessus.
    float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));
    float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0, 50.0));
    float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));
    float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));
    float2 warp = float2(n1 * 0.65 + n3 * 0.35, n2 * 0.65 + n4 * 0.35) * centerWeight;

    float2 warped = clamp(uv + warp * intensity, 0.0, 1.0) * texSize;
    float3 color = mix(current.eval(warped).rgb, next.eval(warped).rgb, mixT);

    float2 center = screen - 0.5;
    color *= 1.0 - dot(center, center) * 0.3;

    float gray = dot(color, float3(0.299, 0.587, 0.114));
    color = mix(float3(gray), color, saturation);

    float noise = hash(float3(floor(fragCoord), floor(time * 60.0)));
    color += (noise - 0.5) * dithering;

    return half4(clamp(color, 0.0, 1.0), 1.0);
}
"""

private fun Bitmap.asShader(): BitmapShader =
    BitmapShader(this, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }

/**
 * Fond animé tiré de la pochette [artworkUrl]. [dim] assombrit l'ensemble pour laisser le texte lisible ;
 * [animated] à faux fige l'image (économie d'énergie).
 */
@Composable
fun KawarpBackground(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    dim: Float = 0f,
    animated: Boolean = true,
) {
    val context = LocalContext.current
    val loader = remember { ImageLoader(context) }
    val placeholder = remember { KawarpBlur.placeholder().asShader() }
    val shader = remember { RuntimeShader(KAWARP_AGSL) }

    var current by remember { mutableStateOf(placeholder) }
    var next by remember { mutableStateOf(placeholder) }
    val transition = remember { Animatable(1f) }
    val time = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(artworkUrl) {
        val target = artworkUrl?.takeIf { it.isNotBlank() }?.let { url ->
            withContext(Dispatchers.Default) {
                runCatching {
                    val request = ImageRequest.Builder(context).data(url).size(KawarpBlur.SIZE).allowHardware(false).build()
                    val image = (loader.execute(request) as? SuccessResult)?.image ?: return@runCatching null
                    KawarpBlur.process(image.toBitmap()).asShader()
                }.getOrNull()
            }
        } ?: placeholder
        // Si un fondu était en cours, on repart de ce qui était le plus visible.
        if (transition.value >= 0.5f) current = next
        next = target
        transition.snapTo(0f)
        transition.animateTo(1f, tween(durationMillis = 1000, easing = { 0.5f - 0.5f * cos(it * PI.toFloat()) }))
        current = target
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        // Le temps n'avance que lorsque l'écran est visible.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                time.floatValue += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }

    Canvas(modifier) {
        shader.setInputShader("current", current)
        shader.setInputShader("next", next)
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("texSize", KawarpBlur.SIZE.toFloat())
        shader.setFloatUniform("mixT", transition.value)
        shader.setFloatUniform("time", time.floatValue)
        shader.setFloatUniform("intensity", 1.0f)
        shader.setFloatUniform("saturation", 1.5f)
        shader.setFloatUniform("dithering", 0.008f)
        shader.setFloatUniform("scale", 1.0f)
        drawRect(ShaderBrush(shader))
        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
    }
}

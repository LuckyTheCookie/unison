/*
 * Portage Android de Kawarp (https://github.com/better-lyrics/kawarp)
 * Copyright (c) 2026 Better Lyrics — licence MIT (voir THIRD_PARTY_NOTICES.md).
 */
package fr.lulu.unison.ui.kawarp

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Première moitié du pipeline de Kawarp, faite une seule fois par pochette :
 * réduction à 128×128, teinte des zones sombres, puis flou de Kawase.
 * L'original fait ces passes sur le GPU ; à cette taille le processeur les fait en quelques millisecondes,
 * et il ne reste au shader que la déformation animée.
 */
object KawarpBlur {
    const val SIZE = 128

    // Réglages par défaut de Kawarp.
    private const val BLUR_PASSES = 8
    private const val TINT_INTENSITY = 0.15f
    private val TINT = floatArrayOf(0.157f, 0.157f, 0.235f)

    fun process(source: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(source, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== source) scaled.recycle()

        var a = FloatArray(SIZE * SIZE * 3)
        var b = FloatArray(SIZE * SIZE * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            tint(((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f, a, i * 3)
        }
        repeat(BLUR_PASSES) { pass ->
            kawase(a, b, pass)
            val swap = a; a = b; b = swap
        }
        for (i in pixels.indices) {
            val r = (a[i * 3] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (a[i * 3 + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bl = (a[i * 3 + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    /** Tire les zones sombres vers la couleur de teinte, pour que le noir ne reste pas plat. */
    private fun tint(r: Float, g: Float, b: Float, out: FloatArray, o: Int) {
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        val t = (luma / 0.5f).coerceIn(0f, 1f)
        val darkMask = 1f - t * t * (3f - 2f * t) // 1 - smoothstep(0, 0.5, luma)
        val k = darkMask * TINT_INTENSITY
        out[o] = r + (TINT[0] - r) * k
        out[o + 1] = g + (TINT[1] - g) * k
        out[o + 2] = b + (TINT[2] - b) * k
    }

    /**
     * Une passe de Kawase : moyenne de quatre prises en diagonale, à (pass + 0,5) texels du centre.
     * Chaque prise tombe pile entre quatre texels, que le filtrage bilinéaire du GPU moyennerait : on fait de même.
     */
    private fun kawase(src: FloatArray, dst: FloatArray, pass: Int) {
        val last = SIZE - 1
        for (y in 0 until SIZE) {
            val yLoA = max(y - pass - 1, 0); val yLoB = max(y - pass, 0)
            val yHiA = min(y + pass, last); val yHiB = min(y + pass + 1, last)
            for (x in 0 until SIZE) {
                val xLoA = max(x - pass - 1, 0); val xLoB = max(x - pass, 0)
                val xHiA = min(x + pass, last); val xHiB = min(x + pass + 1, last)
                val o = (y * SIZE + x) * 3
                for (c in 0 until 3) {
                    var sum = 0f
                    sum += src[(yLoA * SIZE + xLoA) * 3 + c] + src[(yLoA * SIZE + xLoB) * 3 + c] + src[(yLoB * SIZE + xLoA) * 3 + c] + src[(yLoB * SIZE + xLoB) * 3 + c]
                    sum += src[(yLoA * SIZE + xHiA) * 3 + c] + src[(yLoA * SIZE + xHiB) * 3 + c] + src[(yLoB * SIZE + xHiA) * 3 + c] + src[(yLoB * SIZE + xHiB) * 3 + c]
                    sum += src[(yHiA * SIZE + xLoA) * 3 + c] + src[(yHiA * SIZE + xLoB) * 3 + c] + src[(yHiB * SIZE + xLoA) * 3 + c] + src[(yHiB * SIZE + xLoB) * 3 + c]
                    sum += src[(yHiA * SIZE + xHiA) * 3 + c] + src[(yHiA * SIZE + xHiB) * 3 + c] + src[(yHiB * SIZE + xHiA) * 3 + c] + src[(yHiB * SIZE + xHiB) * 3 + c]
                    dst[o + c] = sum / 16f
                }
            }
        }
    }

    /** Fond par défaut, calculé une seule fois par processus (préchauffé au démarrage, hors du fil principal). */
    val placeholderBitmap: Bitmap by lazy { placeholder() }

    /** Fond par défaut quand rien ne joue : quelques halos sombres, passés dans le même pipeline. */
    private fun placeholder(): Bitmap {
        val pixels = IntArray(SIZE * SIZE)
        val blobs = arrayOf(
            floatArrayOf(0.25f, 0.30f, 0.36f, 0.24f, 0.20f, 0.52f),
            floatArrayOf(0.78f, 0.25f, 0.30f, 0.10f, 0.32f, 0.42f),
            floatArrayOf(0.60f, 0.80f, 0.40f, 0.34f, 0.14f, 0.38f),
        )
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            var r = 0.04f; var g = 0.04f; var b = 0.06f
            val u = x / (SIZE - 1f); val v = y / (SIZE - 1f)
            for (blob in blobs) {
                val d = ((u - blob[0]) * (u - blob[0]) + (v - blob[1]) * (v - blob[1])) / (blob[2] * blob[2])
                val w = (1f - d).coerceIn(0f, 1f)
                r += blob[3] * w; g += blob[4] * w; b += blob[5] * w
            }
            pixels[y * SIZE + x] = (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or
                ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()
        }
        val raw = Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        return process(raw).also { raw.recycle() }
    }
}

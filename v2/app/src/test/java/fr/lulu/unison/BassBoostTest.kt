package fr.lulu.unison

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import fr.lulu.unison.playback.BassBoostDsp
import fr.lulu.unison.playback.BassBoostProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Réponse du renfort des graves mesurée sur des sinusoïdes synthétiques. */
class BassBoostTest {
    private val rate = 44_100

    private fun sine(hz: Double, seconds: Double, amp: Double, channels: Int = 2): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * channels) { i -> (amp * sin(2 * PI * hz * (i / channels) / rate)).toFloat() }
    }

    private fun rms(buf: FloatArray, from: Int) = sqrt(buf.drop(from).sumOf { it.toDouble() * it } / (buf.size - from))

    /** Gain mesuré en dB après 1 s de mise en route (le niveau rejoint sa cible en douceur). */
    private fun gainDb(level: Int, hz: Double, amp: Double = 0.05): Double {
        val dsp = BassBoostDsp(rate, 2).apply { this.level = level }
        val input = sine(hz, 2.0, amp)
        val out = input.copyOf()
        dsp.process(out, out.size / 2)
        val from = input.size / 2
        return 20 * log10(rms(out, from) / rms(input, from))
    }

    @Test fun bassIsBoostedAndTrebleKeepsItsShape() {
        for (level in 1..5) {
            val shelf = BassBoostDsp.gainFor(level)
            val pre = -shelf * BassBoostDsp.PREAMP_SHARE
            val low = gainDb(level, 30.0)
            val high = gainDb(level, 5_000.0)
            println("niveau $level : graves %+.2f dB, aigus %+.2f dB (attendu %+.1f / %+.1f)".format(low, high, shelf + pre, pre))
            // Aigus : seulement le gain de prudence, la courbe est plate.
            assertEquals(pre, high, 0.2)
            // Graves : le renfort du niveau (à 30 Hz, l'étagère est presque entièrement atteinte).
            assertEquals(shelf, low - high, 0.8)
        }
        // Les niveaux sont bien croissants.
        val lows = (1..5).map { gainDb(it, 40.0) - gainDb(it, 5_000.0) }
        assertTrue(lows.zipWithNext().all { (a, b) -> b > a + 1.5 })
    }

    @Test fun offLeavesTheSignalUntouched() {
        val input = sine(60.0, 0.5, 0.9) + sine(3_000.0, 0.5, 0.9)
        val out = input.copyOf()
        BassBoostDsp(rate, 2).process(out, out.size / 2)
        assertArrayEquals(input, out, 1e-6f)
    }

    @Test fun neverExceedsFullScale() {
        // Sinusoïde grave à pleine échelle, niveau maximal : sans limiteur, elle dépasserait de plusieurs dB.
        val dsp = BassBoostDsp(rate, 2).apply { level = 5 }
        val buf = sine(50.0, 3.0, 1.0)
        dsp.process(buf, buf.size / 2)
        val peak = buf.maxOf { abs(it) }
        println("crête à pleine échelle, niveau 5 : %.4f".format(peak))
        assertTrue(peak <= 1.0f)
        assertTrue(peak > 0.8f) // le limiteur ne réduit pas tout au silence
    }

    @Test fun processorHandles16BitAudio() {
        val p = BassBoostProcessor()
        p.level = 5
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        assertTrue(p.isActive)
        val frames = rate // 1 s
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (32767 * sin(2 * PI * 50.0 * i / rate)).toInt().toShort()
            input.putShort(v); input.putShort(v)
        }
        input.flip()
        p.queueInput(input)
        val out = p.output
        assertEquals(frames * 4, out.remaining())
        var peak = 0
        while (out.hasRemaining()) peak = maxOf(peak, abs(out.short.toInt()))
        assertTrue(peak in 26_000..32_767) // ni écrêté (repli), ni écrasé
    }
}

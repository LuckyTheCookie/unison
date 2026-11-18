package fr.lulu.unison.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renfort des graves, sans dépendre de l'effet `BassBoost` d'Android (qui varie d'un constructeur à l'autre) :
 * un filtre en étagère grave (biquad, recette RBJ) précédé d'un gain de prudence et suivi d'un limiteur
 * qui garde le signal sous 0 dBFS. Calcul en double, échantillons entrelacés en flottants (-1..1).
 */
class BassBoostDsp(private val sampleRate: Int, private val channels: Int) {
    /** Niveau voulu, 0 (coupé) à 5. */
    @Volatile var level: Int = 0

    /** Renfort appliqué, en dB : il rejoint celui du niveau voulu en douceur (pas de clic au changement). */
    private var levelDb = 0.0
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)
    private var envelope = 0.0
    private val release = exp(-1.0 / (RELEASE_S * sampleRate))
    private val stepDb = RAMP_DB_PER_S * BLOCK / sampleRate

    init { design(0.0) }

    /** Traite [frames] trames de [buf] sur place. */
    fun process(buf: FloatArray, frames: Int) {
        var f = 0
        while (f < frames) {
            val n = minOf(BLOCK, frames - f)
            rampTowardsTarget()
            // Rien à faire : on laisse le signal intact (bit pour bit une fois rendu).
            if (levelDb == 0.0) {
                // L'état du filtre suit quand même, pour reprendre sans clic.
                for (i in f * channels until (f + n) * channels) track(i % channels, buf[i].toDouble())
                f += n; continue
            }
            val pre = 10.0.pow(-levelDb * PREAMP_SHARE / 20.0)
            for (frame in f until f + n) {
                val base = frame * channels
                var peak = 0.0
                for (c in 0 until channels) {
                    val y = filter(c, buf[base + c] * pre)
                    buf[base + c] = y.toFloat()
                    peak = max(peak, abs(y))
                }
                // Limiteur à attaque immédiate : l'enveloppe couvre toujours le pic, la sortie ne dépasse pas le seuil.
                envelope = if (peak > envelope) peak else envelope * release + peak * (1 - release)
                if (envelope > CEILING) {
                    val g = CEILING / envelope
                    for (c in 0 until channels) buf[base + c] = (buf[base + c] * g).toFloat()
                }
            }
            f += n
        }
    }

    fun reset() {
        z1.fill(0.0); z2.fill(0.0); envelope = 0.0
    }

    private fun rampTowardsTarget() {
        val want = gainFor(level)
        if (want == levelDb) return
        levelDb = if (abs(want - levelDb) <= stepDb) want else levelDb + stepDb * kotlin.math.sign(want - levelDb)
        design(levelDb)
    }

    /** Coefficients de l'étagère grave (Audio EQ Cookbook, pente S = 1). */
    private fun design(gainDb: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * Math.PI * CUTOFF_HZ / sampleRate
        val cw = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0)
        val sa = 2 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cw + sa
        b0 = a * ((a + 1) - (a - 1) * cw + sa) / a0
        b1 = 2 * a * ((a - 1) - (a + 1) * cw) / a0
        b2 = a * ((a + 1) - (a - 1) * cw - sa) / a0
        a1 = -2 * ((a - 1) + (a + 1) * cw) / a0
        a2 = ((a + 1) + (a - 1) * cw - sa) / a0
    }

    /** Forme directe II transposée. */
    private fun filter(c: Int, x: Double): Double {
        val y = b0 * x + z1[c]
        z1[c] = b1 * x - a1 * y + z2[c]
        z2[c] = b2 * x - a2 * y
        return y
    }

    /** Filtre neutre : sortie = entrée, mais l'état avance comme si le filtre tournait. */
    private fun track(c: Int, x: Double) { filter(c, x) }

    companion object {
        const val CUTOFF_HZ = 100.0
        /** Gain de l'étagère pour chaque niveau, en dB. */
        val LEVEL_DB = doubleArrayOf(0.0, 3.0, 5.0, 7.0, 9.0, 11.0)
        /** Part du renfort retirée en entrée par prudence (le limiteur fait le reste). */
        const val PREAMP_SHARE = 0.5
        const val CEILING = 0.989 // -0,1 dBFS
        const val RELEASE_S = 0.12
        const val RAMP_DB_PER_S = 60.0
        const val BLOCK = 64

        fun gainFor(level: Int): Double = LEVEL_DB[level.coerceIn(0, LEVEL_DB.size - 1)]
    }
}

/**
 * [BassBoostDsp] branché dans la chaîne audio d'ExoPlayer. Il reste toujours actif (même niveau 0, il laisse
 * alors passer le son intact) pour que changer de niveau s'entende tout de suite, sans recharger le titre.
 */
class BassBoostProcessor : BaseAudioProcessor() {
    @Volatile var level: Int = 0
        set(value) { field = value; dsp?.level = value }

    private var dsp: BassBoostDsp? = null
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val size = inputBuffer.remaining()
        if (size == 0) return
        val channels = format.channelCount
        val d = dsp ?: BassBoostDsp(format.sampleRate, channels).also { dsp = it; it.level = level }
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        val samples = size / (if (float) 4 else 2)
        if (scratch.size < samples) scratch = FloatArray(samples)
        val order = inputBuffer.order()
        val src = inputBuffer.slice().order(order)
        if (float) for (i in 0 until samples) scratch[i] = src.getFloat(i * 4)
        else for (i in 0 until samples) scratch[i] = src.getShort(i * 2) / 32768f
        d.process(scratch, samples / channels)
        val out = replaceOutputBuffer(size)
        if (float) for (i in 0 until samples) out.putFloat(scratch[i])
        else for (i in 0 until samples) out.putShort((scratch[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // Nouveau format ou saut dans le titre : on repart d'un filtre au repos.
        val f = inputAudioFormat
        if (dsp == null || f.sampleRate != configuredRate || f.channelCount != configuredChannels) {
            dsp = if (f.sampleRate > 0) BassBoostDsp(f.sampleRate, f.channelCount).also { it.level = level } else null
            configuredRate = f.sampleRate; configuredChannels = f.channelCount
        } else dsp?.reset()
    }

    override fun onReset() {
        dsp = null; scratch = FloatArray(0); configuredRate = 0; configuredChannels = 0
    }

    private var configuredRate = 0
    private var configuredChannels = 0
}

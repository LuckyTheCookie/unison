package fr.lulu.unison.lyrics

import kotlin.math.abs

/**
 * Tête de lecture des paroles : elle avance d'elle-même à chaque image et la position du lecteur ne fait que
 * la *corriger*. Lue telle quelle, cette position avance par à-coups (elle vient de la sortie audio, mise à jour
 * par paquets) : le remplissage des mots tressautait. Ici :
 *
 * - en retard, la tête accélère un peu (25 % au plus) ; en avance, elle ralentit (jamais en dessous de la
 *   moitié) : elle ne recule jamais sans saut annoncé, et ne s'arrête jamais ;
 * - un saut annoncé ([jumpKey] change : recherche, titre suivant) est rejoint en 260 ms s'il est proche, d'un
 *   coup sinon ; un écart de plus de 3 s sans annonce aussi.
 *
 * Idée et réglages repris du `LyricClock` de BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0),
 * en plus simple : ici la position du lecteur est lue à chaque image, pas deux fois par seconde.
 * Temps en millisecondes ; aucun Compose, pour être testé image par image.
 */
class LyricClock(startMs: Long) {
    var displayedMs: Double = startMs.toDouble()
        private set

    private var lastNowMs = Double.NaN
    private var lastJumpKey: Any? = NO_KEY
    private var glideStartMs = Double.NaN
    private var glideOffsetMs = 0.0

    fun frame(nowMs: Double, reportedMs: Long, playing: Boolean, jumpKey: Any?): Long {
        val jumped = lastJumpKey !== NO_KEY && jumpKey != lastJumpKey
        lastJumpKey = jumpKey
        val reported = reportedMs.toDouble()
        if (!playing) {
            // À l'arrêt, la position du lecteur est exacte et immobile.
            displayedMs = reported
            lastNowMs = Double.NaN
            glideStartMs = Double.NaN
            return displayedMs.toLong()
        }
        if (lastNowMs.isNaN()) {
            lastNowMs = nowMs
            displayedMs = reported
            return displayedMs.toLong()
        }
        // Une image très longue (appli revenue au premier plan) ne doit pas faire sauter les mots en avant.
        val dt = (nowMs - lastNowMs).coerceIn(0.0, MAX_FRAME_MS)
        lastNowMs = nowMs

        if (jumped) {
            val offset = displayedMs - reported
            if (abs(offset) <= GLIDE_MAX_MS) {
                glideStartMs = nowMs
                glideOffsetMs = offset
            } else {
                glideStartMs = Double.NaN
                displayedMs = reported
                return displayedMs.toLong()
            }
        }
        if (!glideStartMs.isNaN()) {
            val progress = (nowMs - glideStartMs) / GLIDE_MS
            if (progress >= 1.0) {
                glideStartMs = Double.NaN
                displayedMs = reported
            } else {
                displayedMs = reported + glideOffsetMs * (1.0 - smoothstep(progress))
            }
            return displayedMs.toLong()
        }

        val predicted = displayedMs + dt
        val error = reported - predicted
        displayedMs = if (abs(error) >= LOST_MS) {
            reported
        } else {
            val closing = error * (dt / (dt + CORRECTION_MS))
            predicted + closing.coerceIn(-MAX_SLOW * dt, MAX_SLEW * dt)
        }
        return displayedMs.toLong()
    }

    private companion object {
        val NO_KEY = Any()
        const val MAX_FRAME_MS = 250.0
        const val CORRECTION_MS = 600.0
        const val MAX_SLEW = 0.25
        const val MAX_SLOW = 0.5
        const val GLIDE_MS = 260.0
        const val GLIDE_MAX_MS = 2_000.0
        const val LOST_MS = 3_000.0

        fun smoothstep(fraction: Double): Double {
            val t = fraction.coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }
    }
}

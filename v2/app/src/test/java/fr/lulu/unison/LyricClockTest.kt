package fr.lulu.unison

import fr.lulu.unison.lyrics.LyricClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LyricClockTest {
    /** Une image toutes les 16 ms ; le lecteur donne sa position par paquets de 40 ms, avec un peu de bruit. */
    private fun run(clock: LyricClock, fromMs: Long, frames: Int, jumpKey: Any = 1, report: (Double) -> Long = { (it - it % 40).toLong() }): List<Long> {
        val out = mutableListOf<Long>()
        for (i in 0 until frames) {
            val now = fromMs + i * 16.0
            out += clock.frame(now, report(now), playing = true, jumpKey = jumpKey)
        }
        return out
    }

    @Test
    fun `les mots avancent régulièrement malgré une position par paquets`() {
        val clock = LyricClock(0)
        val shown = run(clock, 0, 300)
        val steps = shown.zipWithNext { a, b -> b - a }.drop(10)
        // Jamais en arrière, jamais figé, et à peu près une image de chanson par image d'écran.
        assertTrue(steps.all { it >= 0 })
        assertTrue("pas trop irréguliers : $steps", steps.count { it == 0L } <= 5 && steps.all { it <= 30 })
        assertTrue(abs(shown.last() - 299 * 16) < 60)
    }

    @Test
    fun `sans annonce, une position en arrière ralentit les mots sans les faire reculer`() {
        val clock = LyricClock(0)
        run(clock, 0, 60)
        val before = clock.displayedMs
        val shown = run(clock, 60 * 16L, 30) { (it - 400).toLong() }
        assertTrue(shown.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(shown.first() >= before.toLong())
    }

    @Test
    fun `un saut annoncé proche est rejoint en douceur, un saut lointain d'un coup`() {
        val clock = LyricClock(0)
        run(clock, 0, 30)
        // Recherche à 1,5 s plus loin : on y arrive en environ 260 ms, sans saut brutal.
        val near = run(clock, 30 * 16L, 25, jumpKey = 2) { it.toLong() + 1_500 }
        assertTrue(near.zipWithNext().all { (a, b) -> b - a < 400 })
        assertTrue(abs(near.last() - ((30 + 24) * 16 + 1_500)) < 20)
        // Titre suivant, 2 min plus loin : tout de suite.
        val far = clock.frame(60 * 16.0, 120_000, playing = true, jumpKey = 3)
        assertEquals(120_000L, far)
    }

    @Test
    fun `à l'arrêt la position du lecteur fait foi`() {
        val clock = LyricClock(0)
        run(clock, 0, 20)
        assertEquals(5_000L, clock.frame(400.0, 5_000, playing = false, jumpKey = 1))
        assertEquals(5_000L, clock.frame(10_000.0, 5_000, playing = true, jumpKey = 1))
    }
}

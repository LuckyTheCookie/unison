package fr.lulu.unison.sync

/**
 * Estimation de l'écart d'horloge avec l'hôte (méthode type NTP) :
 * on garde l'échantillon dont l'aller-retour est le plus court.
 */
class ClockSync {
    data class Sample(val offset: Long, val rtt: Long)

    private val samples = ArrayDeque<Sample>()

    @Volatile
    var offsetMs: Long = 0
        private set

    @Volatile
    var rttMs: Long = -1
        private set

    /** [t0] envoi du ping, [hostTime] heure de l'hôte, [t1] réception du pong (heure locale). */
    @Synchronized
    fun addSample(t0: Long, hostTime: Long, t1: Long) {
        val rtt = (t1 - t0).coerceAtLeast(0)
        val offset = hostTime - (t0 + t1) / 2
        samples.addLast(Sample(offset, rtt))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        val best = samples.minBy { it.rtt }
        offsetMs = best.offset
        rttMs = best.rtt
    }

    fun hostNow(localNow: Long = System.currentTimeMillis()): Long = localNow + offsetMs

    companion object {
        const val MAX_SAMPLES = 16
    }
}

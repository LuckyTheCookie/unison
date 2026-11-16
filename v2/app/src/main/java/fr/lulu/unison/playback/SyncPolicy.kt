package fr.lulu.unison.playback

import kotlin.math.abs

/** Ce qu'il faut faire pour rester calé sur l'ancre de la session. */
sealed interface SyncAction {
    /** Dans les clous : vitesse normale. */
    data object Hold : SyncAction

    /** Petit écart : on le rattrape en jouant imperceptiblement plus vite ou plus lentement. */
    data class Nudge(val speed: Float) : SyncAction

    /** Gros écart : on saute directement à la bonne position. */
    data object Jump : SyncAction
}

/**
 * Règle de calage, à partir de la dérive mesurée (position réelle − position attendue, en ms).
 * [nudging] : un rattrapage par la vitesse est déjà en cours ; on le poursuit alors jusqu'à être
 * presque pile, pour ne pas osciller autour du seuil.
 */
object SyncPolicy {
    const val DEAD_BAND_MS = 25L
    const val SETTLED_MS = 8L
    const val NUDGE_BAND_MS = 150L
    const val FAST = 1.03f
    const val SLOW = 0.97f

    fun decide(driftMs: Long, nudging: Boolean): SyncAction {
        val d = abs(driftMs)
        return when {
            d > NUDGE_BAND_MS -> SyncAction.Jump
            d <= (if (nudging) SETTLED_MS else DEAD_BAND_MS) -> SyncAction.Hold
            else -> SyncAction.Nudge(if (driftMs > 0) SLOW else FAST)
        }
    }
}

package fr.lulu.unison.playback

import fr.lulu.unison.model.QueueItem
import fr.lulu.unison.model.RepeatMode
import fr.lulu.unison.model.SessionSnapshot

/**
 * Règles de l'enchaînement sans coupure, sans effet de bord : ExoPlayer garde en mémoire le titre en cours
 * et celui qui suit, déjà préparé, pour passer de l'un à l'autre sans recréer la sortie audio.
 */
object Gapless {
    /**
     * Titre qui démarrera quand celui en cours arrivera à sa fin naturelle,
     * selon les mêmes règles que [fr.lulu.unison.session.SessionLogic.advance].
     */
    fun upcoming(s: SessionSnapshot): QueueItem? {
        val current = s.anchor.item ?: return null
        return when {
            s.repeat == RepeatMode.ONE -> current
            s.queue.isNotEmpty() -> s.queue.first()
            s.repeat == RepeatMode.ALL -> s.history.firstOrNull() ?: current
            else -> null
        }
    }

    /** Ce qu'il faut faire pour que le lecteur joue le titre voulu. */
    sealed interface Step {
        /** C'est déjà le titre du lecteur. */
        data object Keep : Step

        /** Le titre est déjà préparé dans la liste du lecteur : on y passe sans rien recharger. */
        data class Jump(val index: Int) : Step

        /** Le titre est inconnu du lecteur : chargement complet. */
        data object Load : Step
    }

    /** [playlist] : identifiants (uid) des titres chargés dans le lecteur ; [currentIndex] : celui qu'il joue. */
    fun step(playlist: List<String>, currentIndex: Int, want: String): Step {
        if (playlist.getOrNull(currentIndex) == want) return Step.Keep
        val i = playlist.indexOf(want)
        return if (i >= 0) Step.Jump(i) else Step.Load
    }

    /**
     * Faut-il déplacer la tête de lecture ? Seul, l'ancre suit le lecteur : on ne saute que si elle a vraiment
     * changé (un « aller à » demandé) et qu'elle s'écarte nettement. À plusieurs, le lecteur suit l'ancre.
     */
    fun needsJump(shared: Boolean, anchorChanged: Boolean, driftMs: Long, soloToleranceMs: Long, sharedToleranceMs: Long): Boolean =
        when {
            shared -> kotlin.math.abs(driftMs) > sharedToleranceMs
            else -> anchorChanged && kotlin.math.abs(driftMs) > soloToleranceMs
        }
}

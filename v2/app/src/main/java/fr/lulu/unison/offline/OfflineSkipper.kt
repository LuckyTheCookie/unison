package fr.lulu.unison.offline

import fr.lulu.unison.model.QueueItem

/**
 * Hors ligne, la file saute les titres qui ne sont pas téléchargés : elle passe directement au prochain titre
 * téléchargé, et les titres sautés restent dans la file pour plus tard (comme un « lire maintenant »).
 * S'il n'y en a aucun, la lecture se met en pause au lieu de vider la file. Sans Android, pour être testé.
 */
class OfflineSkipper {
    sealed interface Decision {
        /** Le titre se joue (téléchargé, réseau présent, ou décision laissée à l'hôte). */
        data object Play : Decision

        /** Titre déjà traité, la session n'a pas encore bougé : rien de plus à faire. */
        data object Wait : Decision

        /** Titre en pause : on ne le charge pas, mais on ne lance rien d'autre à la place de l'utilisateur. */
        data object Hold : Decision

        /** Passer directement à ce titre de la file, le premier qui se joue sans réseau. */
        data class JumpTo(val uid: String) : Decision

        /** Aucun titre téléchargé dans la file : pause. */
        data object Stop : Decision
    }

    private var handled: String? = null

    /**
     * [playing] : la session joue (et ne fait pas qu'afficher un titre en pause) ; [host] : ce téléphone décide
     * de la file ; [playable] : le titre est téléchargé ou le réseau est là.
     */
    fun decide(current: QueueItem, queue: List<QueueItem>, playing: Boolean, host: Boolean, playable: (QueueItem) -> Boolean): Decision {
        if (!host || playable(current)) {
            handled = null
            return Decision.Play
        }
        if (!playing) {
            // Chaque nouvel appui sur lecture refait le choix.
            handled = null
            return Decision.Hold
        }
        if (current.uid == handled) return Decision.Wait
        handled = current.uid
        val next = queue.firstOrNull(playable)
        return if (next != null) Decision.JumpTo(next.uid) else Decision.Stop
    }
}

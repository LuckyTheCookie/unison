package fr.lulu.unison.offline

/**
 * Hors ligne, la file saute les titres qui ne sont pas téléchargés. Après un tour complet de la file sans
 * titre jouable, la lecture s'arrête au lieu de tourner en rond. Sans Android, pour pouvoir le tester.
 */
class OfflineSkipper {
    enum class Decision {
        /** Le titre se joue (téléchargé, réseau présent, ou décision laissée à l'hôte). */
        PLAY,

        /** Titre à sauter : on passe au suivant. */
        SKIP,

        /** Titre déjà sauté, la session n'a pas encore bougé : rien de plus à faire. */
        WAIT,

        /** Un tour complet sans titre jouable : la lecture se met en pause. */
        PAUSE,
    }

    private var skipped: String? = null
    private var skips = 0

    /**
     * [playable] : le titre [uid] est téléchargé ou le réseau est là ; [host] : ce téléphone décide de la file ;
     * [round] : nombre de titres de la session (en cours, file et historique), soit un tour complet.
     */
    fun decide(uid: String, playable: Boolean, host: Boolean, round: Int): Decision {
        if (playable || !host) {
            skips = 0; skipped = null
            return Decision.PLAY
        }
        if (uid == skipped) return Decision.WAIT
        skipped = uid
        if (++skips > round) {
            skips = 0
            return Decision.PAUSE
        }
        return Decision.SKIP
    }
}

package fr.lulu.unison.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import fr.lulu.unison.offline.DownloadStatus

/**
 * État de téléchargement de chaque titre, avec un état Compose par titre : quand un téléchargement avance,
 * seule la ligne de ce titre se redessine, pas toutes les lignes visibles. Lu et mis à jour sur le fil principal.
 */
@Stable
class DownloadStatuses {
    private val states = HashMap<String, MutableState<DownloadStatus?>>()
    private var latest: Map<String, DownloadStatus> = emptyMap()

    operator fun get(id: String): DownloadStatus? = states.getOrPut(id) { mutableStateOf(latest[id]) }.value

    /** Applique le nouvel état ; seuls les titres déjà affichés et dont le statut change sont touchés. */
    fun update(next: Map<String, DownloadStatus>) {
        latest = next
        for ((id, state) in states) {
            val value = next[id]
            if (state.value != value) state.value = value
        }
    }
}

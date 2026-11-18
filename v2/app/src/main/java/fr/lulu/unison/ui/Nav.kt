package fr.lulu.unison.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.lulu.unison.model.Track
import fr.lulu.unison.offline.DownloadStatus
import fr.lulu.unison.youtube.AlbumItem
import fr.lulu.unison.youtube.ArtistItem
import fr.lulu.unison.youtube.BrowseRef
import fr.lulu.unison.youtube.PlaylistItem
import fr.lulu.unison.youtube.SongItem
import fr.lulu.unison.youtube.YtItem

/** Déplacements possibles depuis n'importe quel écran. */
class Nav(
    val back: () -> Unit,
    val collection: (browseId: String) -> Unit,
    val artist: (browseId: String) -> Unit,
    val browse: (ref: BrowseRef, title: String) -> Unit,
    val favorites: () -> Unit,
    val history: () -> Unit,
    val playlist: (pid: Long) -> Unit,
    val settings: () -> Unit,
    val downloads: () -> Unit,
    val import: () -> Unit,
    val together: () -> Unit,
    /** Ouvre le menu d'un titre (lire ensuite, favoris, playlist…). */
    val trackMenu: (Track) -> Unit,
) {
    /** Ouvre un élément du catalogue ; un titre se joue, avec [siblings] comme suite. */
    fun open(item: YtItem, vm: AppViewModel, siblings: List<YtItem> = emptyList()) {
        when (item) {
            is SongItem -> {
                val tracks = siblings.filterIsInstance<SongItem>().map { it.track }
                val index = tracks.indexOfFirst { it.id == item.track.id }
                if (index >= 0) vm.play(tracks, index) else vm.play(listOf(item.track))
            }
            is AlbumItem -> collection(item.browseId)
            is PlaylistItem -> collection(item.browseId)
            is ArtistItem -> artist(item.browseId)
        }
    }

    fun more(ref: BrowseRef, title: String) {
        if (ref.pageType == "MUSIC_PAGE_TYPE_PLAYLIST") collection(ref.browseId) else browse(ref, title)
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav absent") }

/** Hauteur occupée en bas par la barre d'onglets et le mini-lecteur : les listes la laissent libre. */
val LocalBottomInset = staticCompositionLocalOf<Dp> { 0.dp }

/** « Réduire les animations » (réglages) : les animations du lecteur passent sans transition. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** État des téléchargements par identifiant de titre : chaque ligne de titre affiche le sien. */
val LocalDownloads = compositionLocalOf<Map<String, DownloadStatus>> { emptyMap() }

package fr.lulu.unison.ui.screens

import fr.lulu.unison.library.PlayContext
import fr.lulu.unison.library.LibrarySearch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.lulu.unison.R
import fr.lulu.unison.model.Track
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.LocalBottomInset
import fr.lulu.unison.ui.LocalNav
import fr.lulu.unison.ui.components.Cover
import fr.lulu.unison.ui.components.GlassButton
import fr.lulu.unison.ui.components.Glyph
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.Notice
import fr.lulu.unison.ui.components.SectionHeader
import fr.lulu.unison.ui.components.TopBar
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type
import fr.lulu.unison.youtube.ArtistItem

/** Pochette générée pour les listes locales : un dégradé sombre et un pictogramme. */
@Composable
fun GeneratedCover(icon: Int, colors: List<Color>, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(if (size > 100.dp) Radius.Art else Radius.Control).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, tint = Color.White, size = size * 0.42f) }
}

val FavoritesColors = listOf(Color(0xFF7A4DFF), Color(0xFF2A1B5E))
val HistoryColors = listOf(Color(0xFF1FA39A), Color(0xFF0E3B46))
val PlaylistColors = listOf(Color(0xFF4A4F6A), Color(0xFF1B1D2A))

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val nav = LocalNav.current
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val downloads by vm.downloadRows.collectAsStateWithLifecycle()
    val artists by vm.subscribed.collectAsStateWithLifecycle()
    val mixing by vm.mixing.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bibliothèque", style = Type.Hero, color = Ink.Primary, modifier = Modifier.weight(1f))
                IconBtn(R.drawable.ic_search_line, "Chercher dans la bibliothèque", nav.librarySearch, background = Ink.Fill, size = 44.dp, iconSize = 22.dp)
            }
        }
        item(key = "mix") { MixCard(mixing, onPlay = vm::playMix) }
        item {
            LibraryRow("Favoris", plural(favorites.size, "titre"), onClick = nav.favorites) { GeneratedCover(R.drawable.ic_heart_fill, FavoritesColors, 54.dp) }
            LibraryRow("Historique", plural(history.size, "titre"), onClick = nav.history) { GeneratedCover(R.drawable.ic_history_line, HistoryColors, 54.dp) }
            val downloaded = downloads.count { it.state == fr.lulu.unison.library.DownloadState.DONE }
            val pending = downloads.size - downloaded
            LibraryRow(
                "Téléchargements",
                plural(downloaded, "titre") + if (pending > 0) " · $pending en cours" else "",
                onClick = nav.downloads,
            ) { GeneratedCover(R.drawable.ic_downloaded_fill, DownloadsColors, 54.dp) }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Playlists", Modifier.weight(1f))
                IconBtn(R.drawable.ic_import_line, "Importer une bibliothèque", nav.import, Modifier.padding(top = 16.dp, end = 8.dp), background = Ink.Fill, size = 40.dp, iconSize = 20.dp)
                IconBtn(R.drawable.ic_add_line, "Nouvelle playlist", { creating = true }, Modifier.padding(top = 16.dp), background = Ink.Fill, size = 40.dp, iconSize = 20.dp)
            }
        }
        if (playlists.isEmpty()) {
            item { Text("Crée une playlist pour ranger tes titres. Elle reste sur ce téléphone.", style = Type.Body, color = Ink.Tertiary, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        items(playlists, key = { "p${it.pid}" }) { p ->
            LibraryRow(p.name, plural(p.count, "titre"), onClick = { nav.playlist(p.pid) }) {
                if (p.cover.isNullOrBlank()) GeneratedCover(R.drawable.ic_queue_line, PlaylistColors, 54.dp) else Cover(p.cover, 54.dp, shape = Radius.Control)
            }
        }
        if (artists.isNotEmpty()) {
            item {
                SectionHeader("Artistes")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(artists, key = { it.browseId }) { a ->
                        ItemCard(ArtistItem(a.browseId, a.name, "", a.thumbnail), onClick = { nav.artist(a.browseId) }, width = 96.dp)
                    }
                }
            }
        }
        if (saved.isNotEmpty()) {
            item { SectionHeader("Albums et playlists enregistrés") }
            items(saved, key = { "s${it.browseId}" }) { s ->
                LibraryRow(s.title, s.subtitle.ifBlank { if (s.isAlbum) "Album" else "Playlist" }, onClick = { nav.collection(s.browseId) }) {
                    Cover(s.thumbnail, 54.dp, shape = Radius.Control)
                }
            }
        }
    }
    if (creating) TextPrompt("Nouvelle playlist", "Nom de la playlist", confirm = "Créer", onDismiss = { creating = false }) { name ->
        creating = false; vm.createPlaylist(name)
    }
}

private fun plural(n: Int, word: String) = when (n) {
    0 -> "Aucun $word"
    1 -> "1 $word"
    else -> "$n ${word}s"
}

@Composable
private fun LibraryRow(title: String, subtitle: String, onClick: () -> Unit, cover: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).pressable(scale = 0.985f, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cover()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Row, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Type.Body, color = Ink.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Glyph(R.drawable.ic_right_line, tint = Ink.Tertiary, size = 18.dp)
    }
}

/** Liste locale de titres : favoris, historique ou playlist. */
@Composable
private fun LocalList(
    title: String,
    cover: @Composable () -> Unit,
    tracks: List<Track>,
    vm: AppViewModel,
    emptyTitle: String,
    emptyDetail: String,
    actions: @Composable () -> Unit = {},
    downloadable: Boolean = false,
    /** Retirer les titres choisis (par position) : playlist de l'utilisateur seulement. */
    onRemoveSelected: ((List<Int>) -> Unit)? = null,
    /** D'où vient la liste, retenu pour l'accueil ; null pour l'historique. */
    from: PlayContext? = null,
    onMore: (index: Int, Track) -> Unit,
) {
    val nav = LocalNav.current
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    var query by rememberSaveable(title) { mutableStateOf<String?>(null) }
    val shown = remember(tracks, query) { query?.let { LibrarySearch.within(it, tracks) } }
    val selection = rememberTrackSelection()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (selection.active) SelectionTopBar(selection, shown ?: tracks.indices.toList())
            else SearchableTopBar(query, { query = it }, "Dans cette liste…", nav.back, canSearch = tracks.size > 1) { actions() }
            LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + if (selection.active) 110.dp else 24.dp)) {
                if (shown != null) {
                    if (shown.isEmpty()) item { Notice("Aucun titre", detail = "Rien ne correspond à « $query » ici.", icon = R.drawable.ic_search_line) }
                    trackPanel(tracks, vm, snap.anchor.item?.track?.id, snap.anchor.playing, numbered = false, onMore = { t -> onMore(tracks.indexOf(t), t) }, shown = shown, selection = selection, from = from)
                    return@LazyColumn
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        cover()
                        Spacer(Modifier.height(18.dp))
                        Text(title, style = Type.Hero, color = Ink.Primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(plural(tracks.size, "titre"), style = Type.Label, color = Ink.Secondary, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 18.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            fr.lulu.unison.ui.components.PrimaryButton("Tout lire", { vm.playAll(tracks, from = from) }, icon = R.drawable.ic_play_fill, enabled = tracks.isNotEmpty())
                            GlassButton(null, { vm.playAll(tracks, shuffle = true, from = from) }, icon = R.drawable.ic_shuffle_line, enabled = tracks.isNotEmpty())
                            GlassButton(null, { vm.addAllToQueue(tracks) }, icon = R.drawable.ic_queue_line, enabled = tracks.isNotEmpty())
                            if (downloadable) DownloadAllButton(tracks, vm)
                        }
                    }
                }
                if (tracks.isEmpty()) item { Notice(emptyTitle, detail = emptyDetail, icon = R.drawable.ic_music_line) }
                trackPanel(tracks, vm, snap.anchor.item?.track?.id, snap.anchor.playing, numbered = false, onMore = { t -> onMore(tracks.indexOf(t), t) }, selection = selection, from = from)
            }
        }
        SelectionActions(selection, tracks, vm, onRemoveSelected)
    }
}

@Composable
fun FavoritesScreen(vm: AppViewModel) {
    val nav = LocalNav.current
    val tracks by vm.favorites.collectAsStateWithLifecycle()
    LocalList(
        "Favoris", { GeneratedCover(R.drawable.ic_heart_fill, FavoritesColors, 180.dp) }, tracks, vm,
        emptyTitle = "Pas encore de favoris", emptyDetail = "Touche le cœur d'un titre pour le retrouver ici.",
        downloadable = true,
        from = PlayContext.Favorites,
        onMore = { _, t -> nav.trackMenu(t) },
    )
}

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val nav = LocalNav.current
    val tracks by vm.history.collectAsStateWithLifecycle()
    LocalList(
        "Historique", { GeneratedCover(R.drawable.ic_history_line, HistoryColors, 180.dp) }, tracks, vm,
        emptyTitle = "Rien pour l'instant", emptyDetail = "Les titres que tu écoutes apparaîtront ici.",
        actions = { if (tracks.isNotEmpty()) IconBtn(R.drawable.ic_delete_line, "Effacer l'historique", { vm.clearHistory() }, tint = Ink.Secondary) },
        onMore = { _, t -> nav.trackMenu(t) },
    )
}

@Composable
fun PlaylistScreen(pid: Long, vm: AppViewModel) {
    val nav = LocalNav.current
    val rows by remember(pid) { vm.playlistTracks(pid) }.collectAsStateWithLifecycle(emptyList())
    val playlist by remember(pid) { vm.playlistRow(pid) }.collectAsStateWithLifecycle(null)
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Pair<Long, Track>?>(null) }
    val tracks = rows.map { it.track.toTrack() }
    LocalList(
        playlist?.name ?: "Playlist",
        {
            val cover = tracks.firstOrNull()?.cover
            if (cover.isNullOrBlank()) GeneratedCover(R.drawable.ic_queue_line, PlaylistColors, 180.dp) else Cover(cover, 180.dp, shape = Radius.Art)
        },
        tracks, vm,
        emptyTitle = "Playlist vide", emptyDetail = "Ajoute des titres depuis leur menu « ⋮ ».",
        downloadable = true,
        onRemoveSelected = { positions -> vm.removeFromPlaylist(positions.mapNotNull { rows.getOrNull(it)?.rowId }) },
        from = PlayContext.playlist(pid, playlist?.name ?: "Playlist", tracks.firstOrNull()?.cover),
        actions = {
            IconBtn(R.drawable.ic_edit_line, "Renommer", { renaming = true }, tint = Ink.Secondary)
            IconBtn(R.drawable.ic_delete_line, "Supprimer", { deleting = true }, tint = Ink.Secondary)
        },
        onMore = { i, t -> rows.getOrNull(i)?.let { removing = it.rowId to t } },
    )
    if (renaming) TextPrompt("Renommer la playlist", "Nom de la playlist", initial = playlist?.name.orEmpty(), confirm = "Renommer", onDismiss = { renaming = false }) {
        renaming = false; vm.renamePlaylist(pid, it)
    }
    if (deleting) Confirm("Supprimer cette playlist ?", "Les titres restent disponibles ailleurs dans l'app.", "Supprimer", onDismiss = { deleting = false }) {
        deleting = false; vm.deletePlaylist(pid); nav.back()
    }
    removing?.let { (rowId, track) ->
        TrackMenu(track, vm, onDismiss = { removing = null }, removeLabel = "Retirer de la playlist", onRemove = { vm.removeFromPlaylist(rowId) })
    }
}

/** « Tout télécharger » : icône pleine quand toute la liste est déjà sur le téléphone. */
@Composable
fun DownloadAllButton(tracks: List<Track>, vm: AppViewModel) {
    val status by vm.downloadStatus.collectAsStateWithLifecycle()
    val all = tracks.isNotEmpty() && tracks.all { status[it.id]?.done == true }
    GlassButton(
        null, { vm.download(tracks) },
        icon = if (all) R.drawable.ic_downloaded_fill else R.drawable.ic_download_line,
        tint = if (all) Ink.Accent else Ink.Primary,
        enabled = tracks.isNotEmpty(),
    )
}

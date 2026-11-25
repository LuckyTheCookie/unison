package fr.lulu.unison.ui.screens

import fr.lulu.unison.library.PlayContext
import androidx.compose.runtime.mutableStateOf
import fr.lulu.unison.library.LibrarySearch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fr.lulu.unison.R
import fr.lulu.unison.model.Track
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.Load
import fr.lulu.unison.ui.LocalBottomInset
import fr.lulu.unison.ui.LocalNav
import fr.lulu.unison.ui.components.SkeletonRows
import fr.lulu.unison.ui.components.CenterBox
import fr.lulu.unison.ui.components.Cover
import fr.lulu.unison.ui.components.GlassButton
import fr.lulu.unison.ui.components.GlassPanel
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.MetaChip
import fr.lulu.unison.ui.components.Notice
import fr.lulu.unison.ui.components.PrimaryButton
import fr.lulu.unison.ui.components.SectionHeader
import fr.lulu.unison.ui.components.Spinner
import fr.lulu.unison.ui.components.TopBar
import fr.lulu.unison.ui.components.TrackRow
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type
import fr.lulu.unison.youtube.ArtistItem
import fr.lulu.unison.youtube.BrowseRef
import fr.lulu.unison.youtube.Section
import fr.lulu.unison.youtube.SongItem
import fr.lulu.unison.youtube.YtItem

/** Carte d'un album, d'une playlist, d'un artiste ou d'un clip dans un carrousel. */
@Composable
fun ItemCard(item: YtItem, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 148.dp) {
    Column(
        modifier.width(width).pressable(onClick = onClick),
        horizontalAlignment = if (item is ArtistItem) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Cover(item.thumbnail, width, shape = if (item is ArtistItem) CircleShape else Radius.Item)
        Spacer(Modifier.height(10.dp))
        Text(
            item.title, style = Type.Label, color = Ink.Primary, maxLines = if (item is ArtistItem) 1 else 2,
            overflow = TextOverflow.Ellipsis, textAlign = if (item is ArtistItem) TextAlign.Center else TextAlign.Start,
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                item.subtitle, style = Type.Caption, color = Ink.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Une section du catalogue : les titres en colonnes de quatre lignes, le reste en carrousel de cartes. */
@Composable
fun SectionBlock(section: Section, vm: AppViewModel) {
    val nav = LocalNav.current
    val current by vm.snapshot.collectAsStateWithLifecycle()
    val currentId = current.anchor.item?.track?.id
    val more = section.more
    SectionHeader(
        section.title,
        action = if (more != null) "Tout voir" else null,
        onAction = more?.let { ref -> { nav.more(ref, section.title) } },
    )
    val songs = section.items.filterIsInstance<SongItem>()
    if (songs.size >= 3 && songs.size == section.items.size) {
        val rows = if (songs.size >= 8) 4 else songs.size.coerceAtMost(4)
        LazyHorizontalGrid(
            rows = GridCells.Fixed(rows),
            modifier = Modifier.fillMaxWidth().height(64.dp * rows),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            items(songs, key = { it.key }) { item ->
                TrackRow(
                    item.track,
                    onClick = { nav.open(item, vm, songs) },
                    modifier = Modifier.width(320.dp),
                    current = item.track.id == currentId,
                    playing = current.anchor.playing,
                    onMore = { nav.trackMenu(item.track) },
                    trailing = { IconBtn(R.drawable.ic_more_line, "Plus d'options", { nav.trackMenu(item.track) }, size = 40.dp, iconSize = 20.dp, tint = Ink.Secondary) },
                )
            }
        }
    } else {
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(section.items, key = { it.key }) { item -> ItemCard(item, onClick = { nav.open(item, vm, section.items) }) }
        }
    }
}

@Composable
fun <T> LoadBox(load: Load<T>?, onRetry: (() -> Unit)? = null, content: @Composable (T) -> Unit) {
    when (load) {
        null, Load.Loading -> SkeletonRows(10, Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp))
        is Load.Failed -> Notice("Impossible de charger", detail = load.message, icon = R.drawable.ic_wifi_line, action = onRetry?.let { "Réessayer" }, onAction = onRetry)
        is Load.Ready -> content(load.value)
    }
}

/** En-tête commun aux albums, playlists et listes locales : pochette, titre, étiquettes, actions. */
@Composable
fun CollectionHeader(
    title: String,
    subtitle: String,
    cover: String?,
    chips: List<String>,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSubtitleClick: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(cover, 200.dp, shape = Radius.Art)
        Spacer(Modifier.height(20.dp))
        Text(title, style = Type.Hero, color = Ink.Primary, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(
                subtitle, style = Type.Title, color = Ink.Secondary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).then(if (onSubtitleClick != null) Modifier.pressable(onClick = onSubtitleClick) else Modifier),
            )
        }
        if (chips.isNotEmpty()) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEachIndexed { i, c -> MetaChip(c, icon = if (i == chips.lastIndex && chips.size > 1) R.drawable.ic_music_line else null) }
            }
        }
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Tout lire", onPlay, icon = R.drawable.ic_play_fill, enabled = canPlay)
            GlassButton(null, onShuffle, icon = R.drawable.ic_shuffle_line, enabled = canPlay)
            extra()
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Liste de titres dans un panneau de verre. */
fun LazyListScope.trackPanel(
    tracks: List<Track>,
    vm: AppViewModel,
    currentId: String?,
    playing: Boolean,
    numbered: Boolean,
    onMore: (Track) -> Unit,
    subtitleOf: (Track) -> String = { it.artist },
    /** Positions à montrer (recherche dans la liste) ; un titre touché lance quand même toute la liste. */
    shown: List<Int>? = null,
    /** Sélection multiple : un appui long l'ouvre (au lieu du menu du titre). */
    selection: TrackSelection? = null,
    /** D'où vient la liste (playlist, album, artiste…), retenu pour l'accueil. */
    from: PlayContext? = null,
) {
    val rows = shown ?: tracks.indices.toList()
    itemsIndexed(rows, key = { _, i -> "$i:${tracks[i].id}" }) { pos, i ->
        val track = tracks[i]
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        val selecting = selection?.active == true
        val shape = when {
            rows.size == 1 -> Radius.Panel
            pos == 0 -> androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            pos == rows.lastIndex -> androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
            else -> androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
        }
        Box(Modifier.animateItem().padding(horizontal = 12.dp).clip(shape).background(Ink.Fill)) {
            TrackRow(
                track,
                onClick = { if (selecting) selection!!.toggle(i) else vm.play(tracks, i, from) },
                onLongClick = if (selection == null) ({ onMore(track) }) else ({
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    selection.toggle(i)
                }),
                selected = if (selecting) i in selection!!.positions else null,
                onSwipe = { vm.playNext(track) },
                index = if (numbered) i + 1 else null,
                showCover = !numbered,
                current = track.id == currentId,
                playing = playing,
                subtitle = subtitleOf(track),
                onMore = { onMore(track) },
            )
        }
    }
}

@Composable
fun CollectionScreen(browseId: String, vm: AppViewModel) {
    val nav = LocalNav.current
    var attempt by remember { mutableIntStateOf(0) }
    val load by produceState<Load<fr.lulu.unison.youtube.CollectionPage>>(Load.Loading, browseId, attempt) {
        value = Load.Loading
        value = vm.collection(browseId)
    }
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    var query by rememberSaveable(browseId) { mutableStateOf<String?>(null) }
    val tracks = (load as? Load.Ready)?.value?.tracks.orEmpty()
    val shown = remember(tracks, query) { query?.let { LibrarySearch.within(it, tracks) } }
    val selection = rememberTrackSelection()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (selection.active) SelectionTopBar(selection, shown ?: tracks.indices.toList())
            else SearchableTopBar(query, { query = it }, "Dans cette liste…", nav.back, canSearch = tracks.size > 1)
            LoadBox(load, onRetry = { attempt++ }) { page ->
                val from = PlayContext.collection(browseId, page.title, page.artist.ifBlank { page.subtitle }, page.thumbnail, page.isAlbum)
                LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + if (selection.active) 110.dp else 24.dp)) {
                    if (shown != null) {
                        if (shown.isEmpty()) item { Notice("Aucun titre", detail = "Rien ne correspond à « $query » ici.", icon = R.drawable.ic_search_line) }
                        trackPanel(page.tracks, vm, snap.anchor.item?.track?.id, snap.anchor.playing, numbered = page.isAlbum, onMore = nav.trackMenu, shown = shown, selection = selection, from = from)
                        return@LazyColumn
                    }
                    item {
                        CollectionHeader(
                            title = page.title,
                            subtitle = page.artist,
                            cover = page.thumbnail,
                            chips = listOfNotNull(page.subtitle.ifBlank { null }, page.info.ifBlank { null }),
                            canPlay = page.tracks.isNotEmpty(),
                            onPlay = { vm.playAll(page.tracks, from = from) },
                            onShuffle = { vm.playAll(page.tracks, shuffle = true, from = from) },
                            onSubtitleClick = page.artistId?.let { id -> { nav.artist(id) } },
                        ) {
                            val isSaved = saved.any { it.browseId == browseId }
                            GlassButton(
                                null, { vm.toggleSaved(browseId, page) },
                                icon = if (isSaved) R.drawable.ic_library_fill else R.drawable.ic_library_line,
                                tint = if (isSaved) Ink.Accent else Ink.Primary,
                            )
                            GlassButton(null, { vm.addAllToQueue(page.tracks) }, icon = R.drawable.ic_queue_line, enabled = page.tracks.isNotEmpty())
                            DownloadAllButton(page.tracks, vm)
                        }
                    }
                    if (page.tracks.isEmpty()) item { Notice("Aucun titre lisible ici", icon = R.drawable.ic_music_line) }
                    trackPanel(page.tracks, vm, snap.anchor.item?.track?.id, snap.anchor.playing, numbered = page.isAlbum, onMore = nav.trackMenu, selection = selection, from = from)
                    items(page.related, key = { "rel:" + it.title }) { section -> SectionBlock(section, vm) }
                }
            }
        }
        SelectionActions(selection, tracks, vm)
    }
}

@Composable
fun ArtistScreen(browseId: String, vm: AppViewModel) {
    val nav = LocalNav.current
    var attempt by remember { mutableIntStateOf(0) }
    val load by produceState<Load<fr.lulu.unison.youtube.ArtistPage>>(Load.Loading, browseId, attempt) {
        value = Load.Loading
        value = vm.artist(browseId)
    }
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val subscribed by vm.subscribedIds.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        LoadBox(load, onRetry = { attempt++ }) { page ->
            val from = PlayContext.artist(browseId, page.name, page.thumbnail)
            LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
                item {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.15f)) {
                        AsyncImage(page.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
                        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                            Text(page.name, style = Type.Hero.copy(fontSize = Type.Hero.fontSize * 1.25f, lineHeight = Type.Hero.lineHeight * 1.2f), color = Ink.Primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (page.listeners.isNotBlank()) Text(page.listeners, style = Type.Label, color = Ink.Secondary, modifier = Modifier.padding(top = 4.dp))
                            val following = browseId in subscribed
                            Spacer(Modifier.height(12.dp))
                            if (following) GlassButton("Abonné", { vm.toggleSubscription(browseId, page.name, page.thumbnail) }, icon = R.drawable.ic_check_line, tint = Ink.Accent)
                            else PrimaryButton("S'abonner", { vm.toggleSubscription(browseId, page.name, page.thumbnail) }, icon = R.drawable.ic_user_add_line)
                        }
                    }
                }
                page.sections.forEach { section ->
                    val songs = section.items.filterIsInstance<SongItem>()
                    if (songs.isNotEmpty() && songs.size == section.items.size && songs.all { it.track.album.isNotBlank() || it.track.durationMs > 0 }) {
                        item(key = "h:" + section.title) {
                            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PrimaryButton("Lire", { vm.playAll(songs.map { it.track }, from = from) }, icon = R.drawable.ic_play_fill)
                                GlassButton("Radio", { vm.startRadio(songs.first().track) }, icon = R.drawable.ic_radio_line)
                                section.more?.let { ref -> GlassButton("Tous les titres", { nav.more(ref, section.title) }) }
                            }
                        }
                        trackPanel(songs.map { it.track }, vm, snap.anchor.item?.track?.id, snap.anchor.playing, numbered = false, onMore = nav.trackMenu, subtitleOf = { it.album.ifBlank { it.artist } }, from = from)
                    } else {
                        item(key = "s:" + section.title) { SectionBlock(section, vm) }
                    }
                }
                if (page.description.isNotBlank()) {
                    item {
                        SectionHeader("À propos")
                        GlassPanel(Modifier.padding(horizontal = 12.dp), padding = PaddingValues(16.dp)) {
                            Text(page.description, style = Type.Body, color = Ink.Secondary, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        IconBtn(R.drawable.ic_left_line, "Retour", nav.back, Modifier.statusBarsPadding().padding(6.dp), background = Color.Black.copy(alpha = 0.35f))
    }
}

/** Page générique du catalogue (discographie complète, « tout voir »…). */
@Composable
fun BrowseScreen(ref: BrowseRef, title: String, vm: AppViewModel) {
    val nav = LocalNav.current
    var attempt by remember { mutableIntStateOf(0) }
    val load by produceState<Load<List<Section>>>(Load.Loading, ref, attempt) {
        value = Load.Loading
        value = vm.browse(ref.browseId, ref.params)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar(title, onBack = nav.back)
        LoadBox(load, onRetry = { attempt++ }) { sections ->
            val items = sections.flatMap { it.items }
            if (items.isEmpty()) Notice("Rien à afficher", icon = R.drawable.ic_music_line)
            else LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = LocalBottomInset.current + 24.dp)) {
                items(items.chunked(2), key = { row -> row.first().key }) { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { item -> ItemCard(item, onClick = { nav.open(item, vm, items) }, width = 160.dp) }
                        if (row.size == 1) Spacer(Modifier.width(160.dp))
                    }
                }
            }
        }
    }
}

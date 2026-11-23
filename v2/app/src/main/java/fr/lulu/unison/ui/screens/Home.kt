package fr.lulu.unison.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.lulu.unison.R
import fr.lulu.unison.library.DownloadState
import fr.lulu.unison.model.Track
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.Load
import fr.lulu.unison.ui.LocalBottomInset
import fr.lulu.unison.ui.LocalNav
import fr.lulu.unison.ui.components.Avatar
import fr.lulu.unison.ui.components.Cover
import fr.lulu.unison.ui.components.Glyph
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.Notice
import fr.lulu.unison.ui.components.RollingText
import fr.lulu.unison.ui.components.SectionHeader
import fr.lulu.unison.ui.components.SkeletonSection
import fr.lulu.unison.ui.components.Spinner
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type
import fr.lulu.unison.youtube.ArtistItem
import fr.lulu.unison.youtube.Section
import fr.lulu.unison.youtube.SongItem
import java.util.Calendar

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Bonjour"
    in 12..17 -> "Bon après-midi"
    else -> "Bonsoir"
}

/** Raccourci de l'accueil : une pochette, un titre, et ce qu'il fait au toucher. */
private class Shortcut(
    val key: String,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
    val cover: @Composable () -> Unit,
)

/**
 * Accueil centré sur l'utilisateur : ses raccourcis (Mix, plus écoutés, favoris, playlists, albums), ce qu'il
 * réécoute, une sélection « Pour toi » tirée de ses goûts, ses artistes. Les recommandations génériques de
 * YouTube Music ne viennent qu'à la fin, deux sections au plus, sauf pour une bibliothèque encore vide.
 */
@Composable
fun HomeScreen(vm: AppViewModel) {
    val nav = LocalNav.current
    val home by vm.home.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val mostPlayed by vm.mostPlayed.collectAsStateWithLifecycle()
    val forYou by vm.forYou.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val downloads by vm.downloadRows.collectAsStateWithLifecycle()
    val mixing by vm.mixing.collectAsStateWithLifecycle()
    val session by vm.sessionSummary.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val artistFeed by vm.artistFeed.collectAsStateWithLifecycle()
    val hello = remember { greeting() }
    val haptics = LocalHapticFeedback.current

    val downloaded = downloads.count { it.state == DownloadState.DONE }
    val shortcuts = buildList {
        add(Shortcut("mix", "Mix", if (mixing) "Préparation…" else "Ta bibliothèque mélangée", onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            vm.playMix()
        }) { MixCover(mixing) })
        if (mostPlayed.size >= 5) add(Shortcut("most", "Les plus écoutés", plural(mostPlayed.size, "titre"), onClick = { vm.playAll(mostPlayed) }) {
            GeneratedCover(R.drawable.ic_sparkles_fill, HistoryColors, TILE)
        })
        if (favorites.isNotEmpty()) add(Shortcut("fav", "Favoris", plural(favorites.size, "titre"), onClick = nav.favorites) {
            GeneratedCover(R.drawable.ic_heart_fill, FavoritesColors, TILE)
        })
        if (downloaded > 0) add(Shortcut("dl", "Téléchargements", plural(downloaded, "titre"), onClick = nav.downloads) {
            GeneratedCover(R.drawable.ic_downloaded_fill, DownloadsColors, TILE)
        })
        playlists.take(4).forEach { p ->
            add(Shortcut("p${p.pid}", p.name, plural(p.count, "titre"), onClick = { nav.playlist(p.pid) }) {
                if (p.cover.isNullOrBlank()) GeneratedCover(R.drawable.ic_queue_line, PlaylistColors, TILE) else Cover(p.cover, TILE, shape = Radius.Control)
            })
        }
        saved.forEach { s ->
            add(Shortcut("s${s.browseId}", s.title, s.subtitle.ifBlank { if (s.isAlbum) "Album" else "Playlist" }, onClick = { nav.collection(s.browseId) }) {
                Cover(s.thumbnail, TILE, shape = Radius.Control)
            })
        }
    }.take(8).let { if (it.size % 2 == 1 && it.size > 1) it.dropLast(1) else it }
    // Bibliothèque déjà fournie : les suggestions génériques passent au second plan.
    val personal = history.size >= 5 || favorites.size >= 3 || playlists.isNotEmpty()

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
        item(key = "head", contentType = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                profile?.let { Avatar(it.name, it.color, 42.dp, Modifier.pressable(scale = 0.92f, onClick = nav.settings)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(hello + ",", style = Type.Label, color = Ink.Secondary)
                    Text(profile?.name.orEmpty(), style = Type.Heading, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(contentAlignment = Alignment.TopEnd) {
                    IconBtn(
                        if (session.shared) R.drawable.ic_group_fill else R.drawable.ic_group_line, "Écouter ensemble", nav.together,
                        tint = if (session.shared) Ink.Accent else Ink.Secondary,
                    )
                    if (session.shared) Box(Modifier.padding(top = 9.dp, end = 9.dp).size(8.dp).clip(CircleShape).background(Ink.Accent))
                }
                IconBtn(R.drawable.ic_settings_line, "Réglages", nav.settings, tint = Ink.Secondary)
            }
        }
        if (session.shared) {
            item(key = "session", contentType = "session") { SessionPill(session.members, session.hostName, vm.isHost, nav.together) }
        }
        if (shortcuts.isNotEmpty()) {
            item(key = "shortcuts", contentType = "shortcuts") {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shortcuts.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { s -> ShortcutTile(s, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            item(key = "recent", contentType = "row") {
                SectionHeader("Réécouter", action = "Historique", onAction = nav.history)
                val recent = history.take(12)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(recent, key = { it.id }) { track -> RecentCard(track, onClick = { vm.play(recent, recent.indexOf(track)) }, onLong = { nav.trackMenu(track) }) }
                }
            }
        }
        if (forYou.size >= 3) {
            item(key = "for-you", contentType = "section") { SectionBlock(Section("Pour toi", forYou.map { SongItem(it) }), vm) }
        }

        artistFeed?.let { feed ->
            item(key = "mine", contentType = "row") {
                SectionHeader("Tes artistes")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(feed.artists, key = { it.browseId }) { a ->
                        ItemCard(ArtistItem(a.browseId, a.name, "", a.thumbnail), onClick = { nav.artist(a.browseId) }, width = 92.dp)
                    }
                }
            }
            if (feed.releases.isNotEmpty()) item(key = "mine-new", contentType = "section") { SectionBlock(Section("Sorties de tes artistes", feed.releases), vm) }
            if (feed.popular.size >= 3) item(key = "mine-songs", contentType = "section") { SectionBlock(Section("Titres de tes artistes", feed.popular.map { SongItem(it) }), vm) }
        }

        when (val h = home) {
            Load.Loading -> if (!personal) item(key = "loading") { Column { repeat(3) { SkeletonSection() } } }
            is Load.Failed -> if (!personal) item(key = "failed") {
                Notice("YouTube Music ne répond pas", detail = h.message, icon = R.drawable.ic_wifi_line, action = "Réessayer", onAction = vm::loadHome)
            }
            is Load.Ready -> {
                val sections = if (personal) h.value.take(2) else h.value
                if (personal && sections.isNotEmpty()) item(key = "discover", contentType = "head") {
                    Text(
                        "À découvrir", style = Type.Caption, color = Ink.Tertiary,
                        modifier = Modifier.padding(start = 20.dp, top = 30.dp),
                    )
                }
                items(sections, key = { "s:" + it.title }, contentType = { "section" }) { section -> SectionBlock(section, vm) }
            }
        }
    }
}

private val TILE = 56.dp

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

@Composable
private fun ShortcutTile(s: Shortcut, modifier: Modifier = Modifier) {
    Row(
        modifier.height(TILE).clip(Radius.Item).background(Ink.Fill).pressable(scale = 0.97f, onClick = s.onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        s.cover()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(s.title, style = Type.Label, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.subtitle, style = Type.Caption, color = Ink.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val MixTileColors = listOf(Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF14B8A6))

@Composable
private fun MixCover(mixing: Boolean) {
    Box(Modifier.size(TILE).clip(Radius.Control).background(Brush.linearGradient(MixTileColors)), contentAlignment = Alignment.Center) {
        if (mixing) Spinner(size = 22.dp, color = Color.White) else Glyph(R.drawable.ic_sparkles_fill, tint = Color.White, size = 24.dp)
    }
}

/** Session ouverte : rappel discret sous l'en-tête, qui mène à « Écouter ensemble ». */
@Composable
private fun SessionPill(members: Int, hostName: String, isHost: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(Radius.Pill).background(Ink.FillStrong)
            .pressable(scale = 0.98f, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Ink.Accent))
        Spacer(Modifier.width(10.dp))
        RollingText(
            when {
                isHost && members < 2 -> "Ta session est ouverte · en attente d'invités"
                isHost -> "Ta session · $members personnes"
                else -> "Session de $hostName · $members personnes"
            },
            style = Type.Label, color = Ink.Primary, maxLines = 1, modifier = Modifier.weight(1f),
        )
        Glyph(R.drawable.ic_right_line, tint = Ink.Tertiary, size = 18.dp)
    }
}

@Composable
private fun RecentCard(track: Track, onClick: () -> Unit, onLong: () -> Unit) {
    Column(Modifier.width(124.dp).pressable(onLongClick = onLong, onClick = onClick)) {
        Cover(track.cover, 124.dp)
        Spacer(Modifier.height(8.dp))
        Text(track.title, style = Type.Label, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artist, style = Type.Caption, color = Ink.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

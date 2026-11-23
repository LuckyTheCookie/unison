package fr.lulu.unison.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.lulu.unison.R
import fr.lulu.unison.model.Track
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.Load
import fr.lulu.unison.ui.LocalBottomInset
import fr.lulu.unison.ui.LocalNav
import fr.lulu.unison.ui.components.RollingText
import fr.lulu.unison.ui.components.SkeletonSection
import fr.lulu.unison.ui.components.Avatar
import fr.lulu.unison.ui.components.CenterBox
import fr.lulu.unison.ui.components.Cover
import fr.lulu.unison.ui.components.Glyph
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.Notice
import fr.lulu.unison.ui.components.SectionHeader
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

@Composable
fun HomeScreen(vm: AppViewModel) {
    val nav = LocalNav.current
    val home by vm.home.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val session by vm.sessionSummary.collectAsStateWithLifecycle()
    val join by vm.joinInfo.collectAsStateWithLifecycle()
    val artistFeed by vm.artistFeed.collectAsStateWithLifecycle()
    val hello = remember { greeting() }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
        item(key = "head", contentType = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(hello, style = Type.Hero, color = Ink.Primary, modifier = Modifier.weight(1f))
                IconBtn(R.drawable.ic_settings_line, "Réglages", nav.settings, tint = Ink.Secondary)
            }
        }
        item(key = "together", contentType = "together") { TogetherCard(shared = join != null, members = session.members, hostName = session.hostName, isHost = vm.isHost, onClick = nav.together) }

        if (history.isNotEmpty()) {
            item(key = "recent", contentType = "row") {
                SectionHeader("Réécouter", action = "Historique", onAction = nav.history)
                val recent = history.take(12)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(recent, key = { it.id }) { track -> RecentCard(track, onClick = { vm.play(recent, recent.indexOf(track)) }, onLong = { nav.trackMenu(track) }) }
                }
            }
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
            if (feed.popular.size >= 3) item(key = "mine-songs", contentType = "section") { SectionBlock(Section("Titres de tes artistes", feed.popular.map { SongItem(it) }), vm) }
            if (feed.releases.isNotEmpty()) item(key = "mine-new", contentType = "section") { SectionBlock(Section("Sorties de tes artistes", feed.releases), vm) }
        }

        when (val h = home) {
            Load.Loading -> item(key = "loading") { Column { repeat(3) { SkeletonSection() } } }
            is Load.Failed -> item(key = "failed") {
                Notice("YouTube Music ne répond pas", detail = h.message, icon = R.drawable.ic_wifi_line, action = "Réessayer", onAction = vm::loadHome)
            }
            is Load.Ready -> items(h.value, key = { "s:" + it.title }, contentType = { "section" }) { section -> SectionBlock(section, vm) }
        }
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

/** Entrée vers « Écouter ensemble » ; résume la session quand il y en a une. */
@Composable
private fun TogetherCard(shared: Boolean, members: Int, hostName: String, isHost: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(Radius.Panel).background(if (shared) Ink.FillStrong else Ink.Fill).border(0.5.dp, Ink.Hairline, Radius.Panel)
            .pressable(scale = 0.985f, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (shared) Ink.Accent else Ink.FillHover), contentAlignment = Alignment.Center) {
            Glyph(R.drawable.ic_group_fill, tint = if (shared) androidx.compose.ui.graphics.Color.Black else Ink.Primary, size = 22.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    !shared -> "Écouter ensemble"
                    isHost -> "Ta session est ouverte"
                    else -> "Session de $hostName"
                },
                style = Type.Title, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            RollingText(
                when {
                    !shared -> "La même musique, au même instant, sur plusieurs téléphones"
                    members < 2 -> "En attente d'invités"
                    else -> "$members personnes écoutent"
                },
                style = Type.Body, color = Ink.Secondary, maxLines = 2,
            )
        }
        Glyph(R.drawable.ic_right_line, tint = Ink.Tertiary, size = 20.dp)
    }
}

@Suppress("unused")
@Composable
private fun MemberDots(names: List<Pair<String, Int>>) {
    Row { names.take(4).forEach { (n, c) -> Avatar(n, c, 22.dp, Modifier.padding(end = 2.dp)) } }
}

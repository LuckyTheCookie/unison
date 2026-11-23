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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.lulu.unison.R
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.Load
import fr.lulu.unison.ui.LocalBottomInset
import fr.lulu.unison.ui.LocalNav
import fr.lulu.unison.ui.components.CenterBox
import fr.lulu.unison.ui.components.Chip
import fr.lulu.unison.ui.components.Cover
import fr.lulu.unison.ui.components.Glyph
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.Notice
import fr.lulu.unison.ui.components.Spinner
import fr.lulu.unison.ui.components.TrackRow
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type
import fr.lulu.unison.youtube.ArtistItem
import fr.lulu.unison.youtube.SearchFilter
import fr.lulu.unison.youtube.SongItem
import fr.lulu.unison.youtube.YtItem

@Composable
fun SearchScreen(vm: AppViewModel) {
    val nav = LocalNav.current
    val ui by vm.search.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val source by vm.activeSource.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit(q: String? = null) { if (q != null) vm.submitSearch(q) else vm.submitSearch(); focus.clearFocus(); keyboard?.hide() }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (ui.query.isEmpty()) runCatching { requester.requestFocus() } }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Text("Recherche", style = Type.Hero, color = Ink.Primary, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(50.dp).clip(Radius.Item).background(Ink.FillHover).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(R.drawable.ic_search_line, tint = Ink.Secondary, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (ui.query.isEmpty()) Text("Titres, artistes, albums…", style = Type.Row, color = Ink.Tertiary)
                BasicTextField(
                    value = ui.query,
                    onValueChange = vm::setQuery,
                    singleLine = true,
                    textStyle = Type.Row.copy(color = Ink.Primary),
                    cursorBrush = SolidColor(Ink.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, capitalization = KeyboardCapitalization.None),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester),
                )
            }
            if (ui.query.isNotEmpty()) IconBtn(R.drawable.ic_close_line, "Effacer", { vm.setQuery(""); runCatching { requester.requestFocus() } }, size = 42.dp, iconSize = 18.dp, tint = Ink.Secondary)
        }
        LazyRow(Modifier.padding(top = 12.dp, bottom = 6.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchFilter.entries) { f -> Chip(f.label, selected = ui.filter == f && !ui.inSource, onClick = { vm.setFilter(f) }) }
            // Source importée choisie : ses titres, cherchés directement chez elle.
            source?.let { s -> item(key = "source") { Chip(s.name, selected = ui.inSource, onClick = vm::searchInSource) } }
        }

        val bottom = LocalBottomInset.current + 16.dp
        when {
            ui.query.isBlank() -> Notice("Que veux-tu écouter ?", detail = "Cherche un titre, un artiste, un album ou une playlist sur YouTube Music.", icon = R.drawable.ic_search_line)
            !ui.submitted -> LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
                items(listOf(ui.query) + ui.suggestions.filter { it != ui.query }) { s ->
                    Row(
                        Modifier.fillMaxWidth().height(50.dp).pressable(scale = 0.99f) { submit(s) }.padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(R.drawable.ic_search_line, tint = Ink.Tertiary, size = 18.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(s, style = Type.Row, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            else -> when (val r = ui.results) {
                null, Load.Loading -> CenterBox { Spinner() }
                is Load.Failed -> Notice("Recherche impossible", detail = r.message, icon = R.drawable.ic_wifi_line, action = "Réessayer", onAction = { vm.submitSearch() })
                is Load.Ready -> if (r.value.isEmpty()) Notice("Aucun résultat", detail = "Essaie avec d'autres mots.", icon = R.drawable.ic_search_line)
                else LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
                    items(r.value, key = { it.key }) { item ->
                        when (item) {
                            is SongItem -> TrackRow(
                                item.track,
                                onClick = { vm.play(listOf(item.track)) },
                                current = item.track.id == snap.anchor.item?.track?.id,
                                playing = snap.anchor.playing,
                                subtitle = listOf(item.track.artist, item.track.album).filter { it.isNotBlank() }.joinToString(" • "),
                                onMore = { nav.trackMenu(item.track) },
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            else -> ResultRow(item, onClick = { nav.open(item, vm) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(item: YtItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).pressable(scale = 0.985f, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(item.thumbnail, 54.dp, shape = if (item is ArtistItem) CircleShape else Radius.Control)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = Type.Row, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, style = Type.Body, color = Ink.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Glyph(R.drawable.ic_right_line, tint = Ink.Tertiary, size = 18.dp)
    }
}

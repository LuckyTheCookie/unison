package fr.lulu.unison.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.lulu.unison.R
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.LocalBottomInset
import fr.lulu.unison.ui.LocalNav
import fr.lulu.unison.ui.LocalReduceMotion
import fr.lulu.unison.ui.components.Glyph
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.Notice
import fr.lulu.unison.ui.components.Spinner
import fr.lulu.unison.ui.components.TrackRow
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val MixColors = listOf(Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF14B8A6))

/**
 * Carte « Mix » en haut de la bibliothèque : un dégradé qui dérive lentement (figé avec « Réduire les
 * animations »), et un bouton lecture. Lance un mélange de favoris, playlists, téléchargements et écoutes.
 */
@Composable
fun MixCard(mixing: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    val drift = rememberInfiniteTransition(label = "mix")
    val angle = drift.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(92.dp).clip(Radius.Panel)
            .drawBehind {
                // Dégradé qui tourne autour du centre : lu au dessin, il ne recompose rien.
                val a = if (reduce) 0.15f else angle.value
                val r = size.maxDimension
                val dx = cos(a * 2 * PI).toFloat() * r / 2
                val dy = sin(a * 2 * PI).toFloat() * r / 4
                drawRect(Brush.linearGradient(MixColors, start = Offset(center.x - dx, center.y - dy), end = Offset(center.x + dx, center.y + dy)))
                drawRect(Color.Black.copy(alpha = 0.28f))
            }
            .border(0.5.dp, Ink.Hairline, Radius.Panel)
            .semantics { role = Role.Button }
            .pressable(enabled = !mixing, scale = 0.98f) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onPlay()
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(R.drawable.ic_sparkles_fill, tint = Color.White, size = 30.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Mix", style = Type.Heading, color = Color.White)
            Text(
                "Tes favoris, playlists et écoutes, mélangés", style = Type.Body, color = Color.White.copy(alpha = 0.82f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            if (mixing) Spinner(size = 22.dp, color = Color.Black)
            else Glyph(R.drawable.ic_play_fill, tint = Color.Black, size = 24.dp)
        }
    }
}

/** Recherche dans sa bibliothèque : favoris, playlists, téléchargements, écoutes récentes. */
@Composable
fun LibrarySearchScreen(vm: AppViewModel) {
    val nav = LocalNav.current
    val query by vm.libraryQuery.collectAsStateWithLifecycle()
    val hits by vm.libraryHits.collectAsStateWithLifecycle()
    val current by vm.currentItem.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(R.drawable.ic_left_line, "Retour", nav.back)
            Row(
                Modifier.weight(1f).height(50.dp).clip(Radius.Item).background(Ink.FillHover).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(R.drawable.ic_search_line, tint = Ink.Secondary, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Dans ta bibliothèque…", style = Type.Row, color = Ink.Tertiary)
                    BasicTextField(
                        value = query,
                        onValueChange = vm::setLibraryQuery,
                        singleLine = true,
                        textStyle = Type.Row.copy(color = Ink.Primary),
                        cursorBrush = SolidColor(Ink.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(requester),
                    )
                }
                if (query.isNotEmpty()) {
                    IconBtn(R.drawable.ic_close_line, "Effacer", { vm.setLibraryQuery(""); runCatching { requester.requestFocus() } }, size = 42.dp, iconSize = 18.dp, tint = Ink.Secondary)
                }
            }
        }

        val list = hits
        when {
            list == null -> Notice(
                "Cherche dans ta bibliothèque",
                detail = "Un titre, un artiste, un album ou le nom d'une playlist : favoris, playlists, téléchargements et écoutes récentes.",
                icon = R.drawable.ic_library_line,
            )
            list.isEmpty() -> Notice("Rien dans ta bibliothèque", detail = "Aucun de tes titres ne correspond à « $query ».", icon = R.drawable.ic_search_line)
            else -> LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomInset.current + 24.dp)) {
                item(key = "count") {
                    Text(
                        if (list.size == 1) "1 titre" else "${list.size} titres",
                        style = Type.Caption, color = Ink.Tertiary, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                    )
                }
                itemsIndexed(list, key = { _, hit -> hit.track.id }) { i, hit ->
                    TrackRow(
                        hit.track,
                        onClick = { focus.clearFocus(); keyboard?.hide(); vm.play(list.map { it.track }, i) },
                        modifier = Modifier.animateItem().padding(horizontal = 4.dp),
                        current = hit.track.id == current?.track?.id,
                        playing = playing,
                        subtitle = listOf(hit.track.artist, hit.where).filter { it.isNotBlank() }.joinToString(" · "),
                        onMore = { nav.trackMenu(hit.track) },
                        onSwipe = { vm.playNext(hit.track) },
                    )
                }
            }
        }
    }
}

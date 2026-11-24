package fr.lulu.unison.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.lulu.unison.R
import fr.lulu.unison.model.Track
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.LocalBottomInset
import fr.lulu.unison.ui.components.Glyph
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type

/**
 * Sélection de titres dans une liste, par leur position (une playlist peut contenir deux fois le même titre).
 * Un appui long l'ouvre ; décocher le dernier titre la referme.
 */
@Stable
class TrackSelection {
    var positions by mutableStateOf(emptySet<Int>())
        private set
    val active: Boolean get() = positions.isNotEmpty()

    fun toggle(i: Int) { positions = if (i in positions) positions - i else positions + i }
    fun select(all: Collection<Int>) { positions = all.toSet() }
    fun clear() { positions = emptySet() }
    fun tracks(list: List<Track>): List<Track> = positions.sorted().mapNotNull { list.getOrNull(it) }
}

@Composable
fun rememberTrackSelection(): TrackSelection = remember { TrackSelection() }

/** Barre du haut pendant la sélection : fermer, nombre de titres, tout sélectionner. */
@Composable
fun SelectionTopBar(selection: TrackSelection, selectable: List<Int>) {
    BackHandler { selection.clear() }
    val all = selectable.isNotEmpty() && selection.positions.containsAll(selectable)
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(R.drawable.ic_close_line, "Annuler la sélection", { selection.clear() })
        val n = selection.positions.size
        Text(
            if (n == 1) "1 titre" else "$n titres",
            style = Type.Title, color = Ink.Primary, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        Text(
            if (all) "Aucun" else "Tout",
            style = Type.Button, color = Ink.Accent,
            modifier = Modifier.clip(Radius.Control).pressable { if (all) selection.clear() else selection.select(selectable) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/**
 * Actions sur les titres choisis, dans une barre de verre au-dessus du mini-lecteur. [onRemove] : retirer
 * de la liste (playlist de l'utilisateur seulement).
 */
@Composable
fun BoxScope.SelectionActions(
    selection: TrackSelection,
    tracks: List<Track>,
    vm: AppViewModel,
    onRemove: ((List<Int>) -> Unit)? = null,
) {
    var picking by remember { mutableStateOf<List<Track>?>(null) }
    AnimatedVisibility(
        selection.active,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        val chosen = selection.tracks(tracks)
        fun done(action: () -> Unit) { action(); selection.clear() }
        Row(
            Modifier.padding(start = 12.dp, end = 12.dp, bottom = LocalBottomInset.current + 10.dp)
                .fillMaxWidth().clip(Radius.Panel).background(Ink.Sheet).border(0.5.dp, Ink.Hairline, Radius.Panel)
                .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SelectionAction(R.drawable.ic_play_fill, "Lire") { done { vm.playAll(chosen) } }
            SelectionAction(R.drawable.ic_queue_line, "Ensuite") { done { vm.playAllNext(chosen) } }
            SelectionAction(R.drawable.ic_add_line, "File") { done { vm.addAllToQueue(chosen) } }
            SelectionAction(R.drawable.ic_library_line, "Playlist") { picking = chosen }
            SelectionAction(R.drawable.ic_heart_line, "Favoris") { done { vm.toggleFavorites(chosen) } }
            SelectionAction(R.drawable.ic_download_line, "Télécharger") { done { vm.download(chosen) } }
            if (onRemove != null) SelectionAction(R.drawable.ic_delete_line, "Retirer") { done { onRemove(selection.positions.sorted()) } }
        }
    }
    picking?.let { list -> PlaylistPicker(list, vm, onDismiss = { picking = null; selection.clear() }) }
}

@Composable
private fun SelectionAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier.width(62.dp).clip(Radius.Control).pressable { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) { Glyph(icon, tint = Ink.Primary, size = 22.dp) }
        Text(label, style = Type.Caption, color = Ink.Secondary, textAlign = TextAlign.Center, maxLines = 1)
    }
}

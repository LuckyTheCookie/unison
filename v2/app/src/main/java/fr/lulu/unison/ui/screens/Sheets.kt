package fr.lulu.unison.ui.screens

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import fr.lulu.unison.R
import fr.lulu.unison.source.SourceTrackId
import fr.lulu.unison.library.DownloadState
import fr.lulu.unison.model.Track
import fr.lulu.unison.ui.components.DownloadBadge
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.components.Cover
import fr.lulu.unison.ui.components.GlassButton
import fr.lulu.unison.ui.components.Glyph
import fr.lulu.unison.ui.components.PrimaryButton
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type

/** Feuille qui monte du bas de l'écran. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, expand: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = expand),
        containerColor = Ink.Sheet,
        contentColor = Ink.Primary,
        shape = Radius.Sheet,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Ink.FillStrong))
        },
    ) { Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp), content = content) }
}

@Composable
fun SheetAction(@DrawableRes icon: Int, label: String, tint: Color = Ink.Primary, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).pressable(scale = 0.99f, onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, tint = tint, size = 22.dp)
        Spacer(Modifier.width(16.dp))
        Text(label, style = Type.Row, color = tint, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) Text(detail, style = Type.Body, color = Ink.Tertiary)
    }
}

/** Menu d'un titre : file d'attente, favoris, playlists, radio, partage. */
@Composable
fun TrackMenu(
    track: Track,
    vm: AppViewModel,
    onDismiss: () -> Unit,
    removeLabel: String? = null,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val favorites by vm.favoriteIds.collectAsState()
    val downloads by vm.downloadStatus.collectAsState()
    val download = downloads[track.id]
    var picking by remember { mutableStateOf(false) }
    if (picking) { PlaylistPicker(listOf(track), vm, onDismiss = { picking = false; onDismiss() }); return }
    Sheet(onDismiss) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(track.cover, 52.dp, shape = Radius.Control)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(track.title, style = Type.Title, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadBadge(download)
                    Text(track.artist, style = Type.Body, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        SheetAction(R.drawable.ic_next_fill, "Lire ensuite") { vm.playNext(track); onDismiss() }
        SheetAction(R.drawable.ic_queue_line, "Ajouter à la file") { vm.addToQueue(track); onDismiss() }
        val fav = track.id in favorites
        SheetAction(if (fav) R.drawable.ic_heart_fill else R.drawable.ic_heart_line, if (fav) "Retirer des favoris" else "Ajouter aux favoris", tint = if (fav) Ink.Accent else Ink.Primary) {
            vm.toggleFavorite(track); onDismiss()
        }
        SheetAction(R.drawable.ic_add_line, "Ajouter à une playlist") { picking = true }
        when {
            download == null || download.state == DownloadState.FAILED ->
                SheetAction(R.drawable.ic_download_line, "Télécharger") { vm.download(listOf(track)); onDismiss() }
            download.done ->
                SheetAction(R.drawable.ic_downloaded_fill, "Supprimer le téléchargement", tint = Ink.Accent) { vm.removeDownload(track.id); onDismiss() }
            else ->
                SheetAction(R.drawable.ic_close_line, "Annuler le téléchargement", detail = "${(download.progress * 100).toInt()} %") { vm.removeDownload(track.id); onDismiss() }
        }
        SheetAction(R.drawable.ic_radio_line, "Lancer la radio de ce titre") { vm.startRadio(track); onDismiss() }
        SheetAction(R.drawable.ic_share_line, "Partager") {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                // Un titre d'une source importée n'a pas de lien public : on partage son nom seulement.
                .putExtra(Intent.EXTRA_TEXT, if (SourceTrackId.isSource(track.id)) "${track.title} — ${track.artist}" else "${track.title} — ${track.artist}\nhttps://music.youtube.com/watch?v=${track.id}")
            context.startActivity(Intent.createChooser(send, "Partager le titre"))
            onDismiss()
        }
        if (removeLabel != null && onRemove != null) {
            SheetAction(R.drawable.ic_delete_line, removeLabel, tint = Ink.Danger) { onRemove(); onDismiss() }
        }
    }
}

@Composable
fun PlaylistPicker(tracks: List<Track>, vm: AppViewModel, onDismiss: () -> Unit) {
    val playlists by vm.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextPrompt("Nouvelle playlist", "Nom de la playlist", confirm = "Créer", onDismiss = { creating = false }) { name ->
            vm.createPlaylist(name, tracks); creating = false; onDismiss()
        }
        return
    }
    Sheet(onDismiss) {
        Text("Ajouter à une playlist", style = Type.Heading, color = Ink.Primary, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
        SheetAction(R.drawable.ic_add_line, "Nouvelle playlist") { creating = true }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            playlists.forEach { p ->
                SheetAction(R.drawable.ic_queue_line, p.name, detail = "${p.count}") { vm.addToPlaylist(p, tracks); onDismiss() }
            }
        }
    }
}

@Composable
internal fun DialogCard(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(Radius.Panel).background(Ink.Panel).border(0.5.dp, Ink.Hairline, Radius.Panel).padding(22.dp),
            content = content,
        )
    }
}

@Composable
fun TextPrompt(
    title: String,
    hint: String,
    confirm: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    DialogCard(onDismiss) {
        Text(title, style = Type.Heading, color = Ink.Primary)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(50.dp).clip(Radius.Control).background(Ink.FillHover).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (value.text.isEmpty()) Text(hint, style = Type.Row, color = Ink.Tertiary)
            BasicTextField(
                value = value,
                onValueChange = { value = it.copy(text = it.text.take(60)) },
                singleLine = true,
                textStyle = Type.Row.copy(color = Ink.Primary),
                cursorBrush = SolidColor(Ink.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                keyboardActions = KeyboardActions(onDone = { if (value.text.isNotBlank()) onConfirm(value.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            GlassButton("Annuler", onDismiss)
            PrimaryButton(confirm, { onConfirm(value.text.trim()) }, enabled = value.text.isNotBlank())
        }
    }
}

@Composable
fun Confirm(title: String, detail: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    DialogCard(onDismiss) {
        Text(title, style = Type.Heading, color = Ink.Primary)
        Spacer(Modifier.height(8.dp))
        Text(detail, style = Type.Body, color = Ink.Secondary)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            GlassButton("Annuler", onDismiss)
            PrimaryButton(confirm, onConfirm)
        }
    }
}

/** Message seul, avec un bouton pour le fermer. */
@Composable
fun Alert(title: String, detail: String, onDismiss: () -> Unit) {
    DialogCard(onDismiss) {
        Text(title, style = Type.Heading, color = Ink.Primary)
        Spacer(Modifier.height(8.dp))
        Text(detail, style = Type.Body, color = Ink.Secondary)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PrimaryButton("OK", onDismiss) }
    }
}

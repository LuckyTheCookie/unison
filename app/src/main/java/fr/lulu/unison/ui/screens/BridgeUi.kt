package fr.lulu.unison.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kyant.shapes.RoundedRectangle
import fr.lulu.unison.deezer.BridgeState
import fr.lulu.unison.deezer.DeezerBridge
import fr.lulu.unison.deezer.DeezerPlaylist
import fr.lulu.unison.ui.PlaylistsUi
import fr.lulu.unison.ui.components.LoadingSpinner
import fr.lulu.unison.ui.glass.GlassButton
import fr.lulu.unison.ui.glass.GlassIconButton
import fr.lulu.unison.ui.glass.GlassStyle
import fr.lulu.unison.ui.glass.GlassSurface
import fr.lulu.unison.ui.glass.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type

/** Ce dont les écrans ont besoin pour le pont Deezer patché, fourni une fois à la racine. */
data class BridgeUi(
    val state: BridgeState = BridgeState.DeezerMissing,
    val chosen: DeezerPlaylist? = null,
    val autoAdd: Boolean = false,
    val hintDismissed: Boolean = true,
    val message: String? = null,
    val playlists: PlaylistsUi = PlaylistsUi.Idle,
    val onAddCurrent: () -> Unit = {},
    val onLoadPlaylists: () -> Unit = {},
    val onPick: (DeezerPlaylist) -> Unit = {},
    val onAutoAdd: (Boolean) -> Unit = {},
    val onDismissHint: () -> Unit = {},
    val onOpenDeezer: () -> Unit = {},
)

val LocalBridge = compositionLocalOf { BridgeUi() }

private fun android.content.Context.openMorphe() {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DeezerBridge.MORPHE_ADD_SOURCE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Fenêtre de choix de la playlist Deezer dans laquelle ajouter les titres. */
@Composable
fun PlaylistPicker(onDismiss: () -> Unit) {
    val bridge = LocalBridge.current
    LaunchedEffect(Unit) { bridge.onLoadPlaylists() }
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedRectangle(30.dp), style = GlassStyle.Thick) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ma playlist Deezer", style = Type.Title2, color = Ink.Primary, modifier = Modifier.weight(1f))
                    GlassIconButton(Icons.Rounded.Close, "Fermer", onDismiss, size = 36.dp)
                }
                Spacer(Modifier.size(10.dp))
                when (val p = bridge.playlists) {
                    PlaylistsUi.Idle, PlaylistsUi.Loading ->
                        Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) { LoadingSpinner(32.dp) }
                    is PlaylistsUi.Error -> {
                        Text(p.message, style = Type.Callout, color = Ink.Secondary, modifier = Modifier.padding(vertical = 12.dp))
                        GlassButton(onClick = bridge.onLoadPlaylists) { Text("Réessayer", style = Type.Callout, color = Ink.Primary) }
                    }
                    is PlaylistsUi.Ready -> if (p.items.isEmpty()) {
                        Text("Aucune playlist modifiable trouvée.", style = Type.Callout, color = Ink.Secondary, modifier = Modifier.padding(vertical = 12.dp))
                    } else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(p.items, key = { it.id }) { pl ->
                            Row(
                                Modifier.fillMaxWidth().pressable { bridge.onPick(pl); onDismiss() }.padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(pl.title, style = Type.Headline, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${pl.trackCount} titre${if (pl.trackCount > 1) "s" else ""}${if (pl.collaborative) " · collaborative" else ""}",
                                        style = Type.Footnote, color = Ink.Secondary,
                                    )
                                }
                                if (bridge.chosen?.id == pl.id) Text("Choisie", style = Type.Footnote, color = Ink.Accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Bouton du lecteur : ajoute le titre en cours à la playlist choisie (ou ouvre le choix). */
@Composable
fun AddToPlaylistButton(modifier: Modifier = Modifier, pickerOpen: Boolean, onPicker: (Boolean) -> Unit) {
    val bridge = LocalBridge.current
    val patched = bridge.state as? BridgeState.Patched ?: return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        GlassButton(
            onClick = { if (bridge.chosen == null) onPicker(true) else if (patched.hasToken) bridge.onAddCurrent() else bridge.onOpenDeezer() },
            style = GlassStyle.Regular,
        ) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = Ink.Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                bridge.chosen?.let { "Ajouter à « ${it.title} »" } ?: "Ajouter à une playlist",
                style = Type.Callout, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val note = bridge.message ?: if (!patched.hasToken) "Ouvre Deezer une fois pour connecter ta session" else null
        if (note != null) Text(note, style = Type.Footnote, color = Ink.Secondary, modifier = Modifier.padding(top = 6.dp))
        else if (bridge.chosen != null) Text(
            "Changer de playlist", style = Type.Footnote, color = Ink.Tertiary,
            modifier = Modifier.pressable { onPicker(true) }.padding(top = 6.dp, bottom = 2.dp),
        )
    }
    if (pickerOpen) PlaylistPicker { onPicker(false) }
}

/** Réglages : état du patch et choix de la playlist. */
@Composable
fun BridgeSettingsCard(pickerOpen: Boolean, onPicker: (Boolean) -> Unit) {
    val bridge = LocalBridge.current
    val context = LocalContext.current
    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedRectangle(26.dp), refraction = false) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Extension, null, tint = Ink.Secondary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Deezer patché", style = Type.Headline, color = Ink.Primary)
                    Text(
                        when (val s = bridge.state) {
                            BridgeState.DeezerMissing -> "Deezer n'est pas installé sur ce téléphone."
                            BridgeState.NotPatched -> "Patche Deezer avec Morphe pour ajouter la musique en cours à ta playlist, depuis Unison."
                            is BridgeState.Patched -> if (s.hasToken) "Connecté à Deezer." else "Ouvre Deezer une fois pour connecter ta session."
                        },
                        style = Type.Footnote, color = Ink.Secondary,
                    )
                }
            }
            when (val s = bridge.state) {
                BridgeState.DeezerMissing -> Unit
                BridgeState.NotPatched -> {
                    Spacer(Modifier.size(14.dp))
                    GlassButton(onClick = { context.openMorphe() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Patcher Deezer avec Morphe", style = Type.Callout, color = Ink.Primary)
                    }
                    Text(
                        "Le patch réinstalle Deezer avec une autre signature : désinstalle d'abord l'original (les titres téléchargés hors ligne seront perdus).",
                        style = Type.Footnote, color = Ink.Tertiary, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                is BridgeState.Patched -> {
                    Spacer(Modifier.size(10.dp))
                    if (!s.hasToken) GlassButton(onClick = bridge.onOpenDeezer, modifier = Modifier.fillMaxWidth()) {
                        Text("Ouvrir Deezer", style = Type.Callout, color = Ink.Primary)
                    } else {
                        Row(
                            Modifier.fillMaxWidth().pressable { onPicker(true) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Playlist", style = Type.Callout, color = Ink.Primary)
                                Text(bridge.chosen?.title ?: "Aucune choisie", style = Type.Footnote, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ink.Tertiary)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Ajout automatique", style = Type.Callout, color = Ink.Primary)
                                Text("Chaque titre écouté sur ce téléphone rejoint la playlist.", style = Type.Footnote, color = Ink.Secondary)
                            }
                            Switch(checked = bridge.autoAdd && bridge.chosen != null, enabled = bridge.chosen != null, onCheckedChange = bridge.onAutoAdd)
                        }
                    }
                }
            }
        }
    }
    if (pickerOpen) PlaylistPicker { onPicker(false) }
}

/** Suggestion affichée une fois à l'accueil quand Deezer n'est pas patché. */
@Composable
fun BridgeHint(modifier: Modifier = Modifier) {
    val bridge = LocalBridge.current
    val context = LocalContext.current
    if (bridge.state != BridgeState.NotPatched || bridge.hintDismissed) return
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedRectangle(26.dp), refraction = false) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Extension, null, tint = Ink.Secondary)
                Spacer(Modifier.width(12.dp))
                Text("Patche Deezer pour aller plus loin", style = Type.Headline, color = Ink.Primary, modifier = Modifier.weight(1f))
            }
            Text(
                "Avec le patch Morphe, Unison peut ajouter la musique en cours à ta playlist Deezer, même automatiquement.",
                style = Type.Footnote, color = Ink.Secondary, modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(onClick = { context.openMorphe() }, modifier = Modifier.weight(1f)) { Text("Patcher", style = Type.Callout, color = Ink.Primary) }
                GlassButton(onClick = bridge.onDismissHint, modifier = Modifier.weight(1f), style = GlassStyle.Clear) { Text("Plus tard", style = Type.Callout, color = Ink.Secondary) }
            }
        }
    }
}

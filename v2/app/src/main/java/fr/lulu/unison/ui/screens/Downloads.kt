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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import fr.lulu.unison.R
import fr.lulu.unison.source.QualityLabel
import fr.lulu.unison.source.SourceConfig
import fr.lulu.unison.source.SourceIds
import fr.lulu.unison.source.YouTubeSource
import fr.lulu.unison.library.DownloadRow
import fr.lulu.unison.library.DownloadState
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.LocalBottomInset
import fr.lulu.unison.ui.LocalNav
import fr.lulu.unison.ui.components.GlassButton
import fr.lulu.unison.ui.components.GlassPanel
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.Notice
import fr.lulu.unison.ui.components.PrimaryButton
import fr.lulu.unison.ui.components.TopBar
import fr.lulu.unison.ui.components.TrackRow
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type
import kotlinx.coroutines.delay
import java.util.Locale

val DownloadsColors = listOf(androidx.compose.ui.graphics.Color(0xFF2E7DFF), androidx.compose.ui.graphics.Color(0xFF13254A))

/** Titres gardés sur le téléphone : avancement, pause, suppression, place occupée. */
@Composable
fun DownloadsScreen(vm: AppViewModel) {
    val nav = LocalNav.current
    val rows by vm.downloadRows.collectAsStateWithLifecycle()
    val status by vm.downloadStatus.collectAsStateWithLifecycle()
    val paused by vm.downloadsPaused.collectAsStateWithLifecycle()
    val waiting by vm.waitingForWifi.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    var clearing by remember { mutableStateOf(false) }
    // Place occupée, relue de temps en temps pendant les téléchargements.
    var space by remember { mutableStateOf(0L to 0L) }
    LaunchedEffect(Unit) { while (true) { space = vm.downloadSpace(); delay(2_000) } }

    val done = rows.filter { it.state == DownloadState.DONE }
    val pending = rows.filter { it.state != DownloadState.DONE }
    val ready = done.map { it.track.toTrack() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("Téléchargements", onBack = nav.back) {
            if (rows.isNotEmpty()) IconBtn(R.drawable.ic_delete_line, "Tout supprimer", { clearing = true }, tint = Ink.Secondary)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
            item {
                GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 12.dp), padding = PaddingValues(16.dp)) {
                    Text("${plural(done.size, "titre")} sur ce téléphone", style = Type.Row, color = Ink.Primary)
                    Text("${size(space.first)} utilisés · ${size(space.second)} libres", style = Type.Caption, color = Ink.Tertiary)
                    if (!online) Text("Hors ligne : seuls les titres téléchargés se jouent.", style = Type.Caption, color = Ink.Accent, modifier = Modifier.padding(top = 6.dp))
                    StorageBar(space.first, space.second)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().pressable(scale = 0.99f) { vm.setDownloadWifiOnly(!settings.downloadWifiOnly) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Wi-Fi uniquement", style = Type.Row, color = Ink.Primary)
                            Text(
                                when {
                                    !settings.downloadWifiOnly -> "Utilise aussi les données mobiles"
                                    waiting && pending.isNotEmpty() -> "En attente d'un réseau Wi-Fi"
                                    else -> "N'utilise pas les données mobiles"
                                },
                                style = Type.Caption, color = Ink.Tertiary,
                            )
                        }
                        Toggle(settings.downloadWifiOnly)
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Tout lire", { vm.playAll(ready) }, icon = R.drawable.ic_play_fill, enabled = ready.isNotEmpty())
                    GlassButton(null, { vm.playAll(ready, shuffle = true) }, icon = R.drawable.ic_shuffle_line, enabled = ready.isNotEmpty())
                    if (pending.isNotEmpty()) {
                        GlassButton(
                            if (paused) "Reprendre" else "Pause",
                            { if (paused) vm.resumeDownloads() else vm.pauseDownloads() },
                            icon = if (paused) R.drawable.ic_play_fill else R.drawable.ic_pause_fill,
                        )
                    }
                }
            }
            if (rows.isEmpty()) item {
                Notice(
                    "Aucun téléchargement", icon = R.drawable.ic_download_line,
                    detail = "Choisis « Télécharger » dans le menu d'un titre, ou « Tout télécharger » sur tes favoris, une playlist ou un album.",
                )
            }
            if (pending.isNotEmpty()) {
                item { Text("En cours", style = Type.Heading, color = Ink.Primary, modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp)) }
                items(pending, key = { "p" + it.track.trackId }) { row -> PendingRow(row, status[row.track.trackId]?.progress ?: 0f, paused, vm) }
            }
            if (done.isNotEmpty()) {
                item { Text("Prêts hors ligne", style = Type.Heading, color = Ink.Primary, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp)) }
                items(done, key = { "d" + it.track.trackId }) { row ->
                    val track = row.track.toTrack()
                    TrackRow(
                        track,
                        onClick = { vm.play(ready, ready.indexOfFirst { it.id == track.id }.coerceAtLeast(0)) },
                        current = snap.anchor.item?.track?.id == track.id,
                        playing = snap.anchor.playing,
                        subtitle = listOfNotNull(track.artist, formatOf(row, sources), size(row.totalBytes)).joinToString(" · "),
                        onMore = { nav.trackMenu(track) },
                        trailing = { IconBtn(R.drawable.ic_close_line, "Supprimer le téléchargement", { vm.removeDownload(track.id) }, size = 40.dp, iconSize = 18.dp, tint = Ink.Tertiary) },
                    )
                }
            }
        }
    }
    if (clearing) Confirm("Supprimer tous les téléchargements ?", "Les titres restent dans ta bibliothèque, mais il faudra le réseau pour les écouter.", "Supprimer", onDismiss = { clearing = false }) {
        clearing = false
        vm.removeDownloads(rows.map { it.track.trackId })
    }
}

@Composable
private fun PendingRow(row: DownloadRow, progress: Float, allPaused: Boolean, vm: AppViewModel) {
    val track = row.track.toTrack()
    val state = when {
        row.state == DownloadState.FAILED -> "Échec"
        row.state == DownloadState.PAUSED -> "En pause"
        allPaused -> "En pause"
        row.state == DownloadState.DOWNLOADING -> "${(progress * 100).toInt()} %"
        else -> "En attente"
    }
    TrackRow(
        track, onClick = {},
        subtitle = "${track.artist} · $state",
        trailing = {
            when (row.state) {
                DownloadState.FAILED -> IconBtn(R.drawable.ic_refresh_line, "Réessayer", { vm.download(listOf(track)) }, size = 40.dp, iconSize = 20.dp, tint = Ink.Secondary)
                DownloadState.PAUSED -> IconBtn(R.drawable.ic_play_fill, "Reprendre", { vm.resumeDownload(track.id) }, size = 40.dp, iconSize = 20.dp, tint = Ink.Secondary)
                else -> IconBtn(R.drawable.ic_pause_fill, "Mettre en pause", { vm.pauseDownload(track.id) }, size = 40.dp, iconSize = 20.dp, tint = Ink.Secondary)
            }
            IconBtn(R.drawable.ic_close_line, "Annuler", { vm.removeDownload(track.id) }, size = 40.dp, iconSize = 18.dp, tint = Ink.Tertiary)
        },
    )
}

/**
 * Format et origine d'un titre téléchargé : « FLAC 24 bits · 96 kHz · Ma source », « Opus 160 kbps ».
 * Un titre garde la source d'où il a été téléchargé, même si elle a été retirée depuis.
 */
private fun formatOf(row: DownloadRow, sources: List<SourceConfig>): String? {
    val codec = row.codec ?: YouTubeSource.codecOfItag(row.itag)
    val kbps = row.bitrateKbps ?: YouTubeSource.kbpsOfItag(row.itag).takeIf { row.codec == null }
    val format = QualityLabel.short(codec, kbps, row.sampleRate, row.bitDepth)
    val origin = if (row.sourceId == SourceIds.YOUTUBE) null else sources.firstOrNull { it.id == row.sourceId }?.name ?: "source retirée"
    return listOfNotNull(format, origin).joinToString(" · ").ifBlank { null }
}

/** Part du stockage prise par les téléchargements. */
@Composable
private fun StorageBar(used: Long, free: Long) {
    val total = (used + free).coerceAtLeast(1)
    Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Ink.FillStrong)) {
        Box(Modifier.fillMaxWidth((used.toFloat() / total).coerceIn(0.01f, 1f)).height(6.dp).clip(RoundedCornerShape(50)).background(Ink.Accent))
    }
}

private fun plural(n: Int, word: String) = if (n <= 1) "$n $word" else "$n ${word}s"

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.FRANCE, "%.1f Go", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.FRANCE, "%.0f Mo", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.FRANCE, "%.0f Ko", bytes / 1024.0)
}

package fr.lulu.unison.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.lulu.unison.R
import fr.lulu.unison.importer.ImportEntry
import fr.lulu.unison.importer.ImportReport
import fr.lulu.unison.importer.ImportTarget
import fr.lulu.unison.importer.ImportUi
import fr.lulu.unison.model.Track
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.LocalBottomInset
import fr.lulu.unison.ui.LocalNav
import fr.lulu.unison.ui.Load
import fr.lulu.unison.ui.components.CenterBox
import fr.lulu.unison.ui.components.GlassButton
import fr.lulu.unison.ui.components.GlassPanel
import fr.lulu.unison.ui.components.Glyph
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.Notice
import fr.lulu.unison.ui.components.PrimaryButton
import fr.lulu.unison.ui.components.Spinner
import fr.lulu.unison.ui.components.TopBar
import fr.lulu.unison.ui.components.TrackRow
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type

/** Types de fichiers proposés par le sélecteur : les exports CSV portent des types variés selon l'appareil. */
private val CsvTypes = arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")

/** Import d'une bibliothèque exportée (Deezer, Spotify… via TuneMyMusic, Exportify) : aperçu, progression, bilan. */
@Composable
fun ImportScreen(vm: AppViewModel) {
    val nav = LocalNav.current
    val state by vm.importState.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::openImport) }
    var manual by remember { mutableStateOf<ImportEntry.Song?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("Importer une bibliothèque", onBack = nav.back)
        when (val s = state) {
            ImportUi.Idle -> Intro(onPick = { pick.launch(CsvTypes) })
            ImportUi.Reading -> CenterBox(Modifier.fillMaxSize()) { Spinner() }
            is ImportUi.Failed -> Notice(
                "Import impossible", detail = s.message, icon = R.drawable.ic_info_line,
                action = "Choisir un autre fichier", onAction = { pick.launch(CsvTypes) },
            )
            is ImportUi.Interrupted -> Column {
                Notice(
                    "Import interrompu", detail = "L'import de « ${s.name} » s'est arrêté avant la fin. Les titres déjà retrouvés ne seront pas recherchés à nouveau.",
                    icon = R.drawable.ic_refresh_line, action = "Reprendre", onAction = vm::startImport,
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { GlassButton("Abandonner", vm::cancelImport) }
            }
            is ImportUi.Ready -> Preview(s, onStart = vm::startImport, onCancel = vm::cancelImport)
            is ImportUi.Running -> Progress(s)
            is ImportUi.Finished -> Report(s.name, s.report, onManual = { manual = it }, onRetry = vm::startImport, onDone = { vm.dismissImport(); nav.back() })
        }
    }
    manual?.let { entry -> ManualMatch(entry, vm, onDismiss = { manual = null }) }
}

@Composable
private fun Intro(onPick: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = LocalBottomInset.current + 24.dp)) {
        GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
            Glyph(R.drawable.ic_import_line, tint = Ink.Accent, size = 32.dp)
            Spacer(Modifier.height(12.dp))
            Text("Retrouve ta musique d'ailleurs", style = Type.Heading, color = Ink.Primary)
            Text(
                "Exporte ta bibliothèque Deezer, Spotify ou Apple Music en fichier CSV (avec TuneMyMusic, Exportify ou Soundiiz), puis choisis ce fichier ici. " +
                    "Chaque titre est recherché sur YouTube Music : tes titres favoris rejoignent tes favoris, chaque playlist devient une playlist Unison et tes artistes sont suivis.",
                style = Type.Body, color = Ink.Secondary, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Choisir un fichier CSV", onPick, Modifier.fillMaxWidth(), icon = R.drawable.ic_import_line)
        }
    }
}

@Composable
private fun Preview(s: ImportUi.Ready, onStart: () -> Unit, onCancel: () -> Unit) {
    val plan = s.plan
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = LocalBottomInset.current + 24.dp)) {
        item {
            GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
                Text("« ${s.name} »", style = Type.Heading, color = Ink.Primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Voici ce que contient le fichier :", style = Type.Body, color = Ink.Secondary, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                if (plan.favorites > 0) Line(R.drawable.ic_heart_fill, "Favoris", "${plan.favorites}")
                plan.playlists.forEach { (name, n) -> Line(R.drawable.ic_queue_line, name, "$n") }
                if (plan.artists.isNotEmpty()) Line(R.drawable.ic_user_line, "Artistes à suivre", "${plan.artists.size}")
                if (plan.skipped > 0) Line(R.drawable.ic_info_line, "Lignes ignorées", "${plan.skipped}")
                Text(
                    "La recherche prend un peu de temps (quelques recherches à la fois). Tu peux quitter cet écran : l'import continue, et reprend là où il en était si l'app est fermée.",
                    style = Type.Caption, color = Ink.Tertiary, modifier = Modifier.padding(top = 12.dp),
                )
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("Annuler", onCancel)
                    PrimaryButton("Importer", onStart, icon = R.drawable.ic_import_line)
                }
            }
        }
    }
}

@Composable
private fun Line(icon: Int, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, tint = Ink.Secondary, size = 18.dp)
        Spacer(Modifier.padding(start = 12.dp))
        Text(label, style = Type.Row, color = Ink.Primary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = Type.Label, color = Ink.Secondary)
    }
}

@Composable
private fun Progress(s: ImportUi.Running) {
    val fraction = if (s.total > 0) s.done.toFloat() / s.total else 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
            Text(if (s.artists) "Recherche des artistes…" else "Recherche des titres…", style = Type.Heading, color = Ink.Primary)
            Text("${s.done} titres sur ${s.total} · ${s.found} retrouvés", style = Type.Body, color = Ink.Secondary, modifier = Modifier.padding(top = 4.dp))
            Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Ink.FillStrong)) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(6.dp).clip(RoundedCornerShape(50)).background(Color.White))
            }
            Text("Tu peux continuer à utiliser Unison pendant ce temps.", style = Type.Caption, color = Ink.Tertiary, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun Report(name: String, r: ImportReport, onManual: (ImportEntry.Song) -> Unit, onRetry: () -> Unit, onDone: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = LocalBottomInset.current + 24.dp)) {
        item {
            GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
                Text("Import terminé", style = Type.Heading, color = Ink.Primary)
                Text("« $name »", style = Type.Body, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Line(R.drawable.ic_heart_fill, "Favoris importés", "${r.favorites}")
                r.playlists.forEach { (p, n) -> Line(R.drawable.ic_queue_line, p, "$n") }
                if (r.artists > 0 || r.artistsNotFound.isNotEmpty()) Line(R.drawable.ic_user_line, "Artistes suivis", "${r.artists}")
                Line(R.drawable.ic_search_line, "Non trouvés", "${r.notFound.size}")
                if (r.ignored > 0) Line(R.drawable.ic_info_line, "Ignorés (doublons, lignes vides…)", "${r.ignored}")
                if (r.failed > 0) {
                    Text("${r.failed} recherches ont échoué faute de réseau.", style = Type.Caption, color = Ink.Accent, modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (r.failed > 0) GlassButton("Reprendre", onRetry, icon = R.drawable.ic_refresh_line)
                    PrimaryButton("Terminé", onDone)
                }
            }
        }
        if (r.notFound.isNotEmpty()) {
            item {
                Text("Non trouvés", style = Type.Heading, color = Ink.Primary, modifier = Modifier.padding(start = 8.dp, top = 22.dp))
                Text("Touche un titre pour le chercher toi-même.", style = Type.Caption, color = Ink.Tertiary, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            }
            items(r.notFound, key = { "${it.line}" }) { e ->
                Row(
                    Modifier.fillMaxWidth().height(60.dp).pressable(scale = 0.99f) { onManual(e) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = Type.Row, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(e.artist, (e.target as? ImportTarget.Playlist)?.name ?: "Favoris").filter { it.isNotBlank() }.joinToString(" · "),
                            style = Type.Body, color = Ink.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Glyph(R.drawable.ic_search_line, tint = Ink.Secondary, size = 20.dp)
                }
            }
        }
        if (r.artistsNotFound.isNotEmpty()) item {
            Text("Artistes non trouvés : " + r.artistsNotFound.joinToString(", "), style = Type.Caption, color = Ink.Tertiary, modifier = Modifier.padding(8.dp))
        }
    }
}

/** Recherche manuelle d'un titre que l'import n'a pas retrouvé. */
@Composable
private fun ManualMatch(entry: ImportEntry.Song, vm: AppViewModel, onDismiss: () -> Unit) {
    // Recherche proposée : titre et artiste du fichier ; on peut la réécrire si elle ne donne rien de bon.
    var input by remember(entry) { mutableStateOf(entry.query) }
    var submitted by remember(entry) { mutableStateOf<String?>(null) }
    var attempt by remember(entry) { mutableIntStateOf(0) }
    val results by produceState<Load<List<Track>>>(Load.Loading, entry, submitted, attempt) {
        value = Load.Loading
        value = vm.importCandidates(entry, submitted)?.let { Load.Ready(it) } ?: Load.Failed("Recherche impossible, vérifie ta connexion.")
    }
    val submit = { if (input.isNotBlank()) { submitted = input.trim(); attempt++ } }
    Sheet(onDismiss, expand = true) {
        Text(entry.title, style = Type.Heading, color = Ink.Primary, modifier = Modifier.padding(horizontal = 22.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "Choisis la bonne version pour « ${(entry.target as? ImportTarget.Playlist)?.name ?: "Favoris"} »",
            style = Type.Body, color = Ink.Secondary, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(50.dp).clip(Radius.Item).background(Ink.FillHover).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(R.drawable.ic_search_line, tint = Ink.Secondary, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (input.isEmpty()) Text("Titre, artiste…", style = Type.Row, color = Ink.Tertiary)
                BasicTextField(
                    value = input,
                    onValueChange = { input = it.take(120) },
                    singleLine = true,
                    textStyle = Type.Row.copy(color = Ink.Primary),
                    cursorBrush = SolidColor(Ink.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (input.isNotEmpty()) IconBtn(R.drawable.ic_close_line, "Effacer", { input = "" }, size = 42.dp, iconSize = 18.dp, tint = Ink.Secondary)
        }
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            when (val r = results) {
                Load.Loading -> CenterBox { Spinner() }
                is Load.Failed -> Notice("Recherche impossible", detail = r.message, icon = R.drawable.ic_wifi_line, action = "Réessayer", onAction = { attempt++ })
                is Load.Ready -> if (r.value.isEmpty()) Notice("Aucun résultat", detail = "Essaie avec d'autres mots.", icon = R.drawable.ic_search_line)
                else r.value.forEach { t -> TrackRow(t, onClick = { vm.resolveImport(entry, t); onDismiss() }) }
            }
        }
    }
}

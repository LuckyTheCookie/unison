package fr.lulu.unison.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import fr.lulu.unison.source.SourceHealth
import fr.lulu.unison.source.SourcePolicy
import fr.lulu.unison.ui.AddSourceUi
import fr.lulu.unison.ui.components.Chip
import fr.lulu.unison.ui.components.Spinner
import fr.lulu.unison.ui.theme.Radius
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.lulu.unison.R
import fr.lulu.unison.source.SourceConfig
import fr.lulu.unison.source.SourceIds
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.components.GlassButton
import fr.lulu.unison.ui.components.GlassPanel
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.PrimaryButton
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type

/**
 * Choix de la source des flux : YouTube Music (par défaut) ou une source importée par son adresse.
 * Le catalogue (accueil, artistes, albums, radio) reste toujours celui de YouTube Music.
 */
@Composable
fun SourcesPanel(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val sources by vm.sources.collectAsState()
    val health by vm.sourceHealth.collectAsState()
    var removing by remember { mutableStateOf<SourceConfig?>(null) }
    LaunchedEffect(sources.size) { vm.refreshSourceHealth() }
    val active = settings.activeSource.takeIf { id -> id == SourceIds.YOUTUBE || sources.any { it.id == id } } ?: SourceIds.YOUTUBE

    GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Text("D'où vient le son", style = Type.Row, color = Ink.Primary)
        Text(
            "L'accueil, les artistes, les albums et la radio restent ceux de YouTube Music. Une source importée ne sert qu'à lire les titres.",
            style = Type.Caption, color = Ink.Tertiary,
        )
        Spacer(Modifier.height(8.dp))
        SourceRow(
            title = "YouTube Music",
            detail = "Par défaut",
            selected = active == SourceIds.YOUTUBE,
            onSelect = { vm.setActiveSource(SourceIds.YOUTUBE) },
        )
        sources.sortedBy { it.addedAt }.forEach { s ->
            SourceRow(
                title = s.name,
                detail = listOfNotNull(s.version.ifBlank { null }?.let { "v$it" }, hostOf(s.baseUrl)).joinToString(" · "),
                selected = active == s.id,
                onSelect = { vm.setActiveSource(s.id) },
                onRemove = { removing = s },
                status = { HealthLine(health[s.id]) },
            )
            if (active == s.id && s.qualityOptions.size > 1) {
                val picked = s.pickedQuality ?: s.defaults["quality"]
                FlowRow(Modifier.padding(start = 36.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.qualityOptions.forEach { o -> Chip(o.label, picked == o.value, onClick = { vm.setSourceQuality(s.id, o.value) }) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        GlassButton("Ajouter une source", vm::startAddSource, Modifier.fillMaxWidth(), icon = R.drawable.ic_add_line)
        if (sources.isNotEmpty()) {
            Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
            Row(Modifier.fillMaxWidth().pressable(scale = 0.99f) { vm.setFallbackToYouTube(!settings.fallbackToYouTube) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Repli sur YouTube Music", style = Type.Row, color = Ink.Primary)
                    Text(
                        "Un titre absent de la source choisie, ou une source qui ne répond pas, se lit sur YouTube Music. À plusieurs, tout le monde reste sur YouTube Music.",
                        style = Type.Caption, color = Ink.Tertiary,
                    )
                }
                Toggle(settings.fallbackToYouTube)
            }
        }
    }

    val adding by vm.addSource.collectAsState()
    AddSourceDialog(adding, vm)

    removing?.let { s ->
        DialogCard({ removing = null }) {
            Text("Retirer « ${s.name} » ?", style = Type.Heading, color = Ink.Primary)
            Text(
                "Les titres trouvés dans cette source restent dans ta bibliothèque ; ils se liront sur YouTube Music quand c'est possible.",
                style = Type.Body, color = Ink.Secondary, modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                GlassButton("Annuler", { removing = null })
                PrimaryButton("Retirer", { vm.removeSource(s.id); removing = null })
            }
        }
    }
}

@Composable
private fun SourceRow(title: String, detail: String, selected: Boolean, onSelect: () -> Unit, onRemove: (() -> Unit)? = null, status: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).pressable(scale = 0.99f, onClick = onSelect).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Row, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, style = Type.Caption, color = Ink.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            status?.invoke()
        }
        if (onRemove != null) IconBtn(R.drawable.ic_delete_line, "Retirer la source", onRemove, size = 40.dp, iconSize = 18.dp, tint = Ink.Tertiary)
    }
}

/** État d'une source importée sous son nom. */
@Composable
private fun HealthLine(h: SourceHealth?) {
    val (text, color) = when (h) {
        null -> "Test en cours…" to Ink.Tertiary
        SourceHealth.Ok -> "En ligne" to Ink.Accent
        is SourceHealth.Unreachable -> "Injoignable : ${h.reason}" to Ink.Secondary
        is SourceHealth.Rejected -> "Refusée : ${h.reason}" to Ink.Danger
    }
    Text(text, style = Type.Caption, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Ajout d'une source : adresse, vérification (manifest et test de santé), puis confirmation explicite. */
@Composable
private fun AddSourceDialog(ui: AddSourceUi, vm: AppViewModel) {
    when (ui) {
        AddSourceUi.Idle -> Unit
        is AddSourceUi.Entering -> DialogCard(vm::dismissAddSource) {
            var value by remember(ui) { mutableStateOf(TextFieldValue(ui.url, TextRange(ui.url.length))) }
            val requester = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
            Text("Ajouter une source", style = Type.Heading, color = Ink.Primary)
            Text(
                "Adresse du manifest de la source (https://…). Unison lit son manifest et fait une recherche d'essai avant de te demander confirmation.",
                style = Type.Body, color = Ink.Secondary, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(50.dp).clip(Radius.Control).background(Ink.FillHover).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) Text("https://…", style = Type.Row, color = Ink.Tertiary)
                BasicTextField(
                    value = value,
                    onValueChange = { value = it.copy(text = it.text.take(SourcePolicy.MAX_URL)) },
                    singleLine = true,
                    textStyle = Type.Row.copy(color = Ink.Primary),
                    cursorBrush = SolidColor(Ink.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { if (value.text.isNotBlank()) vm.inspectSource(value.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester),
                )
            }
            ui.error?.let { Text(it, style = Type.Caption, color = Ink.Danger, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                GlassButton("Annuler", vm::dismissAddSource)
                PrimaryButton("Vérifier", { vm.inspectSource(value.text) }, enabled = value.text.isNotBlank())
            }
        }
        is AddSourceUi.Checking -> DialogCard(vm::dismissAddSource) {
            Text("Vérification de la source…", style = Type.Heading, color = Ink.Primary)
            Text(hostOf(ui.url.trim()), style = Type.Body, color = Ink.Secondary, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            Spinner()
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { GlassButton("Annuler", vm::dismissAddSource) }
        }
        is AddSourceUi.Confirm -> DialogCard(vm::dismissAddSource) {
            val c = ui.inspection.config
            val m = ui.inspection.manifest
            val ok = ui.inspection.health == SourceHealth.Ok
            Text("Ajouter « ${c.name} » ?", style = Type.Heading, color = Ink.Primary)
            Column(Modifier.padding(top = 12.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Fact("Nom", c.name)
                Fact("Identifiant", m.id)
                Fact("Version", c.version.ifBlank { "non indiquée" })
                Fact("Adresse", c.baseUrl)
                Fact("Fonctions", (m.resources.ifEmpty { listOf("search", "stream") }).joinToString(", "))
                Fact("Téléchargements", if (c.allowDownloads) "autorisés" else "non autorisés par la source")
                Fact("Test de santé", when (val h = ui.inspection.health) {
                    SourceHealth.Ok -> "réussi"
                    is SourceHealth.Unreachable -> "échec : ${h.reason}"
                    is SourceHealth.Rejected -> "échec : ${h.reason}"
                }, if (ok) Ink.Accent else Ink.Danger)
                Box(Modifier.padding(top = 12.dp).fillMaxWidth().clip(Radius.Control).background(Ink.Fill).padding(12.dp)) {
                    Text(SOURCE_WARNING, style = Type.Caption, color = Ink.Secondary)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                GlassButton("Annuler", vm::dismissAddSource)
                PrimaryButton("Ajouter", vm::confirmSource, enabled = ok)
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, color: Color = Ink.Primary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = Type.Caption, color = Ink.Tertiary, modifier = Modifier.width(112.dp))
        Text(value, style = Type.Caption, color = color, modifier = Modifier.weight(1f))
    }
}

/** Avertissement montré avant d'ajouter une source. */
internal const val SOURCE_WARNING =
    "Unison ne contrôle pas cette source et ne vérifie pas ce qu'elle diffuse. Tu es seul responsable de la légalité " +
        "des contenus que tu écoutes ou télécharges par son intermédiaire, et du respect de ses conditions d'utilisation. " +
        "La source reçoit tes recherches, les titres que tu écoutes et ton adresse IP ; rien d'autre (ni ton nom ni ton profil)."

/** Pastille de choix unique. */
@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) Ink.Accent else Ink.Tertiary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(Ink.Accent))
    }
}

/** Hôte d'une adresse, pour l'affichage. */
internal fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?')

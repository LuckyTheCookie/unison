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
import androidx.compose.runtime.Composable
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
    var removing by remember { mutableStateOf<SourceConfig?>(null) }
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
            )
        }
    }

    removing?.let { s ->
        DialogCard({ removing = null }) {
            Text("Retirer « ${s.name} » ?", style = Type.Heading, color = Ink.Primary)
            Text(
                "Les titres trouvés dans cette source restent dans ta bibliothèque ; ils se liront sur YouTube Music quand c'est possible.",
                style = Type.Body, color = Ink.Secondary, modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp, Alignment.End)) {
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

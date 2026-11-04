package fr.lulu.unison.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import fr.lulu.unison.ui.components.LoadingSpinner
import fr.lulu.unison.ui.components.UnisonLogo
import fr.lulu.unison.ui.glass.GlassButton
import fr.lulu.unison.ui.glass.GlassStyle
import fr.lulu.unison.ui.glass.GlassSurface
import fr.lulu.unison.ui.glass.PrimaryButton
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type
import fr.lulu.unison.update.UpdateState
import java.util.Locale

/** Vrai quand l'état mérite d'afficher la carte de mise à jour. */
fun UpdateState.isActionable() = this is UpdateState.Available || this is UpdateState.Downloading ||
    this is UpdateState.NeedsPermission || this is UpdateState.Installing || this is UpdateState.Failed

@Composable
fun UpdateCard(
    state: UpdateState,
    onDownload: (fr.lulu.unison.update.Release) -> Unit,
    onGrantPermission: () -> Unit,
    onLater: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedRectangle(34.dp), style = GlassStyle.Thick) {
            Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (state) {
                    is UpdateState.Available -> {
                        UnisonLogo(64.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Nouvelle version disponible", style = Type.Title, color = Ink.Primary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text("Unison ${state.release.version} · ${megabytes(state.release.sizeBytes)}", style = Type.Callout, color = Ink.Secondary)
                        if (state.release.notes.isNotBlank()) {
                            Spacer(Modifier.height(14.dp))
                            Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                                Text(cleanNotes(state.release.notes), style = Type.Footnote, color = Ink.Secondary)
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlassButton(onClick = onLater, style = GlassStyle.Clear) { Text("Plus tard", style = Type.Headline, color = Ink.Primary) }
                            Spacer(Modifier.width(12.dp))
                            PrimaryButton("Télécharger", onClick = { onDownload(state.release) })
                        }
                    }
                    is UpdateState.Downloading -> {
                        UnisonLogo(64.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Téléchargement…", style = Type.Headline, color = Ink.Primary)
                        Spacer(Modifier.height(16.dp))
                        ProgressBar(state.progress)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.progress?.let { "${(it * 100).toInt()} %" } ?: "Connexion à GitHub…",
                            style = Type.Footnote, color = Ink.Secondary,
                        )
                        Spacer(Modifier.height(18.dp))
                        GlassButton(onClick = onCancel, style = GlassStyle.Clear) { Text("Annuler", style = Type.Callout, color = Ink.Secondary) }
                    }
                    is UpdateState.NeedsPermission -> {
                        Text("Autorisation requise", style = Type.Title, color = Ink.Primary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Pour installer la mise à jour, autorise Unison à installer des applications dans les réglages Android, puis reviens ici : l'installation reprendra toute seule.",
                            style = Type.Body, color = Ink.Secondary, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(22.dp))
                        Row {
                            GlassButton(onClick = onLater, style = GlassStyle.Clear) { Text("Plus tard", style = Type.Headline, color = Ink.Primary) }
                            Spacer(Modifier.width(12.dp))
                            PrimaryButton("Autoriser", onClick = onGrantPermission)
                        }
                    }
                    is UpdateState.Installing -> {
                        UnisonLogo(64.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Installation de la ${state.release.version}…", style = Type.Headline, color = Ink.Primary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text("Confirme l'installation si Android te le demande.", style = Type.Footnote, color = Ink.Secondary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        LoadingSpinner(28.dp)
                    }
                    is UpdateState.Failed -> {
                        Text("Mise à jour impossible", style = Type.Title, color = Ink.Primary)
                        Spacer(Modifier.height(8.dp))
                        Text(state.message, style = Type.Body, color = Ink.Secondary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(22.dp))
                        Row {
                            GlassButton(onClick = onLater, style = GlassStyle.Clear) { Text("Fermer", style = Type.Headline, color = Ink.Primary) }
                            state.release?.let { r ->
                                Spacer(Modifier.width(12.dp))
                                PrimaryButton("Réessayer", onClick = { onDownload(r) })
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float?) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))) {
        if (progress == null) {
            Box(Modifier.fillMaxWidth(0.3f).height(8.dp).clip(RoundedCornerShape(50)).background(Ink.Accent))
        } else {
            Box(Modifier.fillMaxWidth(progress).height(8.dp).clip(RoundedCornerShape(50)).background(Ink.Accent))
        }
    }
}

private fun megabytes(bytes: Long) = String.format(Locale.FRANCE, "%.1f Mo", bytes / 1_048_576.0)

/** Garde le texte lisible : retire les titres, la mise en gras et les citations Markdown. */
private fun cleanNotes(md: String) = md.lines()
    .map { it.trim().trimStart('#', '>', ' ').replace("**", "").replace("`", "") }
    .filter { it.isNotBlank() && !it.startsWith("Signature SHA") }
    .joinToString("\n")

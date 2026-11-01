package fr.lulu.unison.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.shapes.RoundedRectangle
import fr.lulu.unison.MainActivity
import fr.lulu.unison.UnisonApp
import fr.lulu.unison.model.Track
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.ui.components.Artwork
import fr.lulu.unison.ui.components.UnisonLogo
import fr.lulu.unison.ui.glass.GlassStyle
import fr.lulu.unison.ui.glass.GlassSurface
import fr.lulu.unison.ui.glass.PrimaryButton
import fr.lulu.unison.ui.glass.ProvideBackdrop
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type
import fr.lulu.unison.ui.theme.UnisonTheme
import kotlinx.coroutines.delay

/** Reçoit « Partager → Ajouter à Unison » depuis Deezer et ajoute le titre à la file. */
class ShareReceiverActivity : ComponentActivity() {
    private sealed interface UiState {
        data object Resolving : UiState
        data class Added(val track: Track, val sessionLabel: String) : UiState
        data class NoSession(val track: Track?) : UiState
        data class Error(val message: String) : UiState
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val graph = (application as UnisonApp).graph

        setContent {
            val theme by graph.profiles.theme.collectAsState(initial = fr.lulu.unison.ui.theme.UiStyle.Glass)
            UnisonTheme(theme) {
                var state by remember { mutableStateOf<UiState>(UiState.Resolving) }
                var visible by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    state = try {
                        val track = graph.api.resolveShared(text)
                        val session = graph.sessions.active.value
                        if (session == null) UiState.NoSession(track) else {
                            session.send(Cmd.AddTrack(track))
                            val label = if (session.isHost) "ta session" else "la session de ${session.joinInfo.hostName}"
                            UiState.Added(track, label)
                        }
                    } catch (t: Throwable) {
                        UiState.Error(t.message ?: "Lien non reconnu")
                    }
                    if (state is UiState.Added) { delay(1_800); visible = false; delay(350); finish() }
                }

                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).padding(16.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    AnimatedVisibility(
                        visible,
                        enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        // Pas de fond à réfracter ici (fenêtre transparente) : verre « épais » givré.
                        ProvideBackdrop(emptyBackdrop()) {
                            GlassSurface(
                                Modifier.fillMaxWidth().navigationBarsPadding(),
                                shape = RoundedRectangle(32.dp), style = GlassStyle.Thick, refraction = false,
                                tint = Color(0xFF15151F).copy(alpha = 0.9f),
                            ) {
                                AnimatedContent(state, label = "share", transitionSpec = { fadeIn() togetherWith fadeOut() }) { s ->
                                    ShareCard(s, onOpen = {
                                        startActivity(Intent(this@ShareReceiverActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        finish()
                                    }, onClose = { finish() })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ShareCard(s: UiState, onOpen: () -> Unit, onClose: () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            when (s) {
                UiState.Resolving -> Row(verticalAlignment = Alignment.CenterVertically) {
                    UnisonLogo(44.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Ajout à la file…", style = Type.Headline, color = Ink.Primary, modifier = Modifier.weight(1f))
                    fr.lulu.unison.ui.components.LoadingSpinner(24.dp)
                }
                is UiState.Added -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(s.track.coverSmall, 56.dp, 14.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.track.title, style = Type.Headline, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Ajouté à ${s.sessionLabel}", style = Type.Footnote, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF4CD9A0), modifier = Modifier.size(30.dp))
                }
                is UiState.NoSession -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(s.track?.coverSmall, 56.dp, 14.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Aucune session en cours", style = Type.Headline, color = Ink.Primary)
                            Text("Crée ou rejoins une session, puis partage à nouveau.", style = Type.Footnote, color = Ink.Secondary)
                        }
                    }
                    Spacer(Modifier.size(16.dp))
                    PrimaryButton("Ouvrir Unison", onClick = onOpen, modifier = Modifier.fillMaxWidth())
                }
                is UiState.Error -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = Color(0xFFFFB443), modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Impossible d'ajouter", style = Type.Headline, color = Ink.Primary)
                            Text(s.message, style = Type.Footnote, color = Ink.Secondary)
                        }
                    }
                    Spacer(Modifier.size(16.dp))
                    PrimaryButton("Fermer", onClick = onClose, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

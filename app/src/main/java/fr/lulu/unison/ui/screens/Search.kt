package fr.lulu.unison.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import fr.lulu.unison.model.Track
import fr.lulu.unison.ui.components.Artwork
import fr.lulu.unison.ui.components.formatMs
import fr.lulu.unison.ui.glass.GlassIconButton
import fr.lulu.unison.ui.glass.GlassStyle
import fr.lulu.unison.ui.glass.GlassSurface
import fr.lulu.unison.ui.glass.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type

@Composable
fun SearchScreen(
    query: String,
    results: List<Track>,
    searching: Boolean,
    error: String?,
    added: Set<Long>,
    onQuery: (String) -> Unit,
    onAdd: (Track, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassSurface(Modifier.weight(1f), shape = Capsule(), style = GlassStyle.Thick) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = Ink.Secondary)
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = Type.Body.copy(color = Ink.Primary),
                        cursorBrush = SolidColor(Ink.Primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) Text("Titres, artistes…", style = Type.Body, color = Ink.Tertiary)
                                inner()
                            }
                        },
                    )
                    if (searching) CircularProgressIndicator(Modifier.size(18.dp), color = Ink.Secondary, strokeWidth = 2.dp)
                    else if (query.isNotEmpty()) Icon(Icons.Rounded.Cancel, "Effacer", tint = Ink.Tertiary, modifier = Modifier.size(20.dp).pressable { onQuery("") })
                }
            }
            Spacer(Modifier.width(10.dp))
            Text("OK", style = Type.Headline, color = Ink.Primary, modifier = Modifier.pressable(onClick = onClose).padding(8.dp))
        }

        if (error != null) {
            Text(error, style = Type.Footnote, color = Color(0xFFFFB443), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }

        if (query.isBlank()) {
            Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Search, null, tint = Ink.Tertiary, modifier = Modifier.size(48.dp))
                Text("Cherche dans tout Deezer", style = Type.Headline, color = Ink.Secondary, modifier = Modifier.padding(top = 10.dp))
                Text("Le titre est ajouté à la file de tout le monde", style = Type.Footnote, color = Ink.Tertiary)
            }
        }

        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom + 24.dp)) {
            items(results, key = { it.id }) { t ->
                val isAdded = t.id in added
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).pressable(haptic = false) { if (!isAdded) onAdd(t, false) }.padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(t.coverSmall, 54.dp, 12.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = Type.Headline, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${t.artist} · ${formatMs(t.durationMs)}", style = Type.Footnote, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!isAdded) {
                        GlassIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Lire ensuite", { onAdd(t, true) }, size = 38.dp, style = GlassStyle.Clear, iconTint = Ink.Secondary)
                        Spacer(Modifier.width(8.dp))
                    }
                    AnimatedContent(
                        isAdded, label = "add",
                        transitionSpec = { scaleIn(spring(Spring.DampingRatioMediumBouncy)) togetherWith scaleOut() },
                    ) { done ->
                        Box(
                            Modifier.size(38.dp).background(if (done) Ink.Success else Ink.Accent, Capsule())
                                .pressable { if (!done) onAdd(t, false) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (done) Icons.Rounded.Check else Icons.Rounded.Add, if (done) "Ajouté" else "Ajouter", tint = if (done) Color.Black else Ink.OnAccent, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private val searchCard = RoundedRectangle(20.dp)

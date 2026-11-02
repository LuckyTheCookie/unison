package fr.lulu.unison.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import fr.lulu.unison.lyrics.Lyrics
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.session.Cmd
import fr.lulu.unison.session.SessionHandle
import fr.lulu.unison.ui.LyricsUi
import fr.lulu.unison.ui.components.Artwork
import fr.lulu.unison.ui.components.LoadingSpinner
import fr.lulu.unison.ui.glass.GlassIconButton
import fr.lulu.unison.ui.glass.GlassStyle
import fr.lulu.unison.ui.glass.GlassSurface
import fr.lulu.unison.ui.glass.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type
import kotlin.math.abs

/** Avance d'affichage : la ligne s'allume juste avant d'être chantée. */
private const val LEAD_MS = 250L

@Composable
fun LyricsScreen(
    handle: SessionHandle,
    snapshot: SessionSnapshot,
    state: LyricsUi,
    onClose: () -> Unit,
) {
    val item = snapshot.anchor.item
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(snapshot.anchor) {
        while (true) withFrameMillis { pos = snapshot.anchor.positionAt(handle.hostNow()) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // En-tête : pochette, titre, fermer
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(item?.track?.coverSmall, 52.dp, 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item?.track?.title ?: "Rien en lecture", style = Type.Headline, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item?.track?.artist ?: "", style = Type.Footnote, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            GlassIconButton(Icons.Rounded.ExpandMore, "Fermer les paroles", onClose, size = 40.dp)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = state, label = "lyrics",
                contentKey = { s -> when (s) { is LyricsUi.Ready -> "r${s.trackId}"; is LyricsUi.Loading -> "l${s.trackId}"; is LyricsUi.Missing -> "m${s.trackId}"; LyricsUi.Idle -> "i" } },
                transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) },
            ) { s ->
                when (s) {
                    LyricsUi.Idle -> Message("♪", "Lance un titre pour voir ses paroles")
                    is LyricsUi.Loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        LoadingSpinner(48.dp)
                        Spacer(Modifier.height(14.dp))
                        Text("Recherche des paroles…", style = Type.Callout, color = Ink.Secondary)
                    }
                    is LyricsUi.Missing -> Message("🎤", "Pas de paroles trouvées pour ce titre", "Fournisseurs consultés : LRCLIB, NetEase")
                    is LyricsUi.Ready -> when {
                        s.lyrics.lines.isEmpty() -> Message("♪", "Instrumental")
                        s.lyrics.synced -> SyncedLyrics(s.lyrics, pos + LEAD_MS, onSeek = { handle.send(Cmd.Seek(it)) })
                        else -> PlainLyrics(s.lyrics)
                    }
                }
            }
        }

        // Mini-lecteur
        MiniControls(handle, snapshot, pos)
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, position: Long, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    // Recalculé à chaque image : la position change en continu.
    val active = lyrics.indexAt(position)
    var userScrollUntil by remember { mutableLongStateOf(0L) }

    // Le doigt de l'utilisateur suspend le défilement automatique quelques secondes.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            if (i is DragInteraction.Start || i is DragInteraction.Stop) userScrollUntil = System.currentTimeMillis() + 3_000
        }
    }
    LaunchedEffect(active) {
        if (System.currentTimeMillis() < userScrollUntil) return@LaunchedEffect
        listState.animateScrollToItem(active.coerceAtLeast(0))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topPad = maxHeight * 0.28f
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = topPad, bottom = maxHeight * 0.6f),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lyrics.lines, key = { i, _ -> i }) { i, line ->
                val isActive = i == active
                val distance = abs(i - active)
                val alpha by animateFloatAsState(
                    when {
                        isActive -> 1f
                        active < 0 -> 0.45f
                        else -> (0.42f - distance * 0.04f).coerceAtLeast(0.18f)
                    },
                    tween(450), label = "a",
                )
                val scale by animateFloatAsState(if (isActive) 1f else 0.94f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow), label = "s")
                val blurDp = if (isActive || active < 0) 0f else (distance * 0.9f).coerceAtMost(3.5f)

                if (line.text.isBlank()) {
                    // Pause instrumentale : trois points qui respirent pendant le silence.
                    val next = lyrics.lines.getOrNull(i + 1)?.timeMs ?: (line.timeMs + 4_000)
                    if (isActive && next - line.timeMs > 3_000) BreathingDots(Modifier.padding(start = 28.dp, top = 14.dp, bottom = 14.dp))
                    else Spacer(Modifier.height(10.dp))
                } else {
                    Text(
                        line.text,
                        color = Ink.Primary,
                        fontSize = 31.sp,
                        lineHeight = 37.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.02).em,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressable(haptic = false) { onSeek(line.timeMs) }
                            .padding(horizontal = 28.dp, vertical = 9.dp)
                            .graphicsLayer {
                                this.alpha = alpha
                                scaleX = scale; scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            }
                            .then(if (blurDp > 0.2f) Modifier.blur(blurDp.dp) else Modifier),
                    )
                }
            }
            item {
                Text(
                    "Paroles : ${lyrics.source}",
                    style = Type.Caption, color = Ink.Tertiary,
                    modifier = Modifier.padding(start = 28.dp, top = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics) {
    LazyColumn(contentPadding = PaddingValues(top = 24.dp, bottom = 80.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.padding(start = 28.dp, bottom = 14.dp).clip(Capsule()).background(Ink.Fill).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Lyrics, null, tint = Ink.Secondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paroles non synchronisées", style = Type.Footnote, color = Ink.Secondary)
            }
        }
        itemsIndexed(lyrics.lines) { _, line ->
            if (line.text.isBlank()) Spacer(Modifier.height(18.dp))
            else Text(
                line.text, color = Ink.Primary.copy(alpha = 0.88f), fontSize = 24.sp, lineHeight = 31.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 3.dp),
            )
        }
        item {
            Text("Paroles : ${lyrics.source}", style = Type.Caption, color = Ink.Tertiary, modifier = Modifier.padding(start = 28.dp, top = 28.dp))
        }
    }
}

@Composable
private fun BreathingDots(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { i ->
            val s by t.animateFloat(
                0.6f, 1.15f,
                infiniteRepeatable(tween(900, delayMillis = i * 180, easing = LinearEasing), RepeatMode.Reverse),
                label = "d$i",
            )
            Box(Modifier.size(14.dp).graphicsLayer { scaleX = s; scaleY = s; alpha = 0.5f + (s - 0.6f) }.clip(CircleShape).background(Ink.Primary))
        }
    }
}

@Composable
private fun Message(emoji: String, title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(emoji, fontSize = 44.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = Type.Title2, color = Ink.Primary)
        if (subtitle != null) Text(subtitle, style = Type.Footnote, color = Ink.Secondary, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun MiniControls(handle: SessionHandle, s: SessionSnapshot, pos: Long) {
    val duration = s.anchor.item?.track?.durationMs ?: 0L
    val fraction = if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f
    GlassSurface(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = Capsule(), style = GlassStyle.Regular,
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
            if (fr.lulu.unison.ui.theme.isExpressive) {
                androidx.compose.material3.LinearWavyProgressIndicator(
                    progress = { fraction }, modifier = Modifier.fillMaxWidth().height(10.dp),
                    amplitude = { if (s.anchor.playing) 1f else 0f },
                )
            } else Box(Modifier.fillMaxWidth().height(4.dp).clip(Capsule()).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(Capsule()).background(Color.White))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                IconAction(Icons.Rounded.SkipPrevious, "Précédent") { handle.send(Cmd.Previous) }
                IconAction(if (s.anchor.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Lecture/Pause", big = true) {
                    handle.send(if (s.anchor.playing) Cmd.Pause else Cmd.Resume)
                }
                IconAction(Icons.Rounded.SkipNext, "Suivant") { handle.send(Cmd.Skip) }
            }
        }
    }
}

@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, big: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(if (big) 52.dp else 44.dp).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Ink.Primary, modifier = Modifier.size(if (big) 38.dp else 28.dp))
    }
}

@Suppress("unused")
@Composable
private fun dpToPx(v: androidx.compose.ui.unit.Dp) = with(LocalDensity.current) { v.toPx() }

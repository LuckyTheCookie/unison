package fr.lulu.unison.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import fr.lulu.unison.data.AvatarColors
import fr.lulu.unison.data.Profile
import fr.lulu.unison.model.Member
import fr.lulu.unison.ui.components.Avatar
import fr.lulu.unison.ui.components.UnisonLogo
import fr.lulu.unison.ui.glass.GlassStyle
import fr.lulu.unison.ui.glass.GlassSurface
import fr.lulu.unison.ui.glass.PrimaryButton
import fr.lulu.unison.ui.glass.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type

@Composable
fun OnboardingScreen(initial: Profile?, onDone: (String, Int) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var color by rememberSaveable { mutableIntStateOf(initial?.color ?: AvatarColors[0]) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 28.dp)) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        UnisonLogo(120.dp)
        Spacer(Modifier.height(18.dp))
        Text("Unison", style = Type.LargeTitle, color = Ink.Primary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Une file d'attente commune.\nLa même musique, au même instant.",
            style = Type.Body, color = Ink.Secondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(44.dp))

        Avatar(Member("me", name.ifBlank { "?" }, color), size = 88.dp)
        Spacer(Modifier.height(22.dp))

        GlassSurface(Modifier.fillMaxWidth(), shape = Capsule(), style = GlassStyle.Regular) {
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                singleLine = true,
                textStyle = Type.Title2.copy(color = Ink.Primary, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Ink.Primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onDone(name, color) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.Center) {
                        if (name.isEmpty()) Text("Ton prénom", style = Type.Title2, color = Ink.Tertiary)
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AvatarColors.forEach { c ->
                val selected = c == color
                val scale by animateFloatAsState(if (selected) 1.18f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "sel")
                Box(
                    Modifier.size(30.dp).graphicsLayer { scaleX = scale; scaleY = scale }.pressable { color = c },
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(Member("c", " ", c), 30.dp, ring = if (selected) Ink.Primary else null)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
        PrimaryButton(
            "C'est parti",
            onClick = { onDone(name, color) },
            enabled = name.isNotBlank(),
            icon = Icons.AutoMirrored.Rounded.ArrowForward,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
    }
}

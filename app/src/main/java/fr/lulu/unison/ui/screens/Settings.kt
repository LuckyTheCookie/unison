package fr.lulu.unison.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import fr.lulu.unison.BuildConfig
import fr.lulu.unison.data.Profile
import fr.lulu.unison.model.Member
import fr.lulu.unison.ui.components.Avatar
import fr.lulu.unison.ui.glass.GlassIconButton
import fr.lulu.unison.ui.glass.GlassSurface
import fr.lulu.unison.ui.glass.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type
import fr.lulu.unison.ui.theme.UiStyle

@Composable
fun SettingsScreen(
    style: UiStyle,
    profile: Profile,
    onStyle: (UiStyle) -> Unit,
    onEditProfile: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Réglages", style = Type.Title, color = Ink.Primary, modifier = Modifier.weight(1f))
            GlassIconButton(Icons.Rounded.Close, "Fermer", onClose, size = 40.dp)
        }

        Section("PROFIL")
        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedRectangle(26.dp), refraction = false) {
            Row(Modifier.fillMaxWidth().pressable(onClick = onEditProfile).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(Member(profile.deviceId, profile.name, profile.color), 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = Type.Headline, color = Ink.Primary)
                    Text("Prénom et couleur visibles par les autres", style = Type.Footnote, color = Ink.Secondary)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ink.Tertiary)
            }
        }

        Section("APPARENCE")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeCard(
                title = "Liquid Glass", subtitle = "Verre, reflets, réfraction",
                selected = style == UiStyle.Glass, modifier = Modifier.weight(1f),
                onClick = { onStyle(UiStyle.Glass) },
            ) { GlassPreview() }
            ThemeCard(
                title = "Material 3 Expressive", subtitle = "Formes, ondes, Material You",
                selected = style == UiStyle.Expressive, modifier = Modifier.weight(1f),
                onClick = { onStyle(UiStyle.Expressive) },
            ) { ExpressivePreview() }
        }
        Text(
            "Material 3 Expressive suit le mode clair/sombre du téléphone et les couleurs de ton fond d'écran.",
            style = Type.Footnote, color = Ink.Tertiary, modifier = Modifier.padding(top = 10.dp, start = 6.dp, end = 6.dp),
        )

        Section("PAROLES")
        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedRectangle(26.dp), refraction = false) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lyrics, null, tint = Ink.Secondary)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Paroles synchronisées", style = Type.Headline, color = Ink.Primary)
                    Text("LRCLIB, puis NetEase. Gratuits, sans compte.", style = Type.Footnote, color = Ink.Secondary)
                }
            }
        }

        Section("À PROPOS")
        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedRectangle(26.dp), refraction = false) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Palette, null, tint = Ink.Secondary)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Unison ${BuildConfig.VERSION_NAME}", style = Type.Headline, color = Ink.Primary)
                    Text("Écoutez Deezer ensemble, sans serveur.", style = Type.Footnote, color = Ink.Secondary)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = Type.Caption, color = Ink.Tertiary, modifier = Modifier.padding(start = 6.dp, top = 24.dp, bottom = 8.dp))
}

@Composable
private fun ThemeCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
    val ring by animateDpAsState(if (selected) 3.dp else 0.dp, label = "ring")
    GlassSurface(
        modifier.border(ring, Ink.Accent, RoundedCornerShape(30.dp)),
        shape = RoundedRectangle(26.dp), refraction = false,
    ) {
        Column(Modifier.pressable(onClick = onClick).padding(10.dp)) {
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp))) { preview() }
            Row(Modifier.padding(top = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = Type.Callout, color = Ink.Primary)
                    Text(subtitle, style = Type.Footnote, color = Ink.Secondary)
                }
                if (selected) Icon(Icons.Rounded.CheckCircle, "Sélectionné", tint = Ink.Accent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Aperçu du thème verre : dégradé vif et pastilles translucides. */
@Composable
private fun GlassPreview() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFFB04CFF), Color(0xFF1E2B8F), Color(0xFFFF4F8B)))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(RoundedRectangle(16.dp)).background(Color.White.copy(alpha = 0.22f)).border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(16.dp)))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(96.dp).height(22.dp).clip(Capsule()).background(Color.White.copy(alpha = 0.25f)).border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(50)))
        }
    }
}

/** Aperçu du thème M3 Expressive : forme « cookie » et progression ondulée. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressivePreview() {
    // Toujours rendu en vrai Material You, même quand le thème verre est actif.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.MaterialExpressiveTheme(colorScheme = androidx.compose.material3.dynamicDarkColorScheme(ctx)) { ExpressivePreviewContent() }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressivePreviewContent() {
    val cs = MaterialTheme.colorScheme
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    val flower = MaterialShapes.Flower.toShape()
    Box(Modifier.fillMaxSize().background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(cookie).background(cs.primary))
                Box(Modifier.size(38.dp).clip(flower).background(cs.tertiary))
            }
            Spacer(Modifier.height(14.dp))
            LinearWavyProgressIndicator(progress = { 0.62f }, modifier = Modifier.width(110.dp).height(10.dp))
        }
    }
}

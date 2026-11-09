package fr.lulu.unison.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import fr.lulu.unison.apkshare.ApkSharer
import fr.lulu.unison.data.Profile
import fr.lulu.unison.deezer.DeezerState
import fr.lulu.unison.model.Member
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.ui.components.Avatar
import fr.lulu.unison.ui.components.UnisonLogo
import fr.lulu.unison.ui.glass.GlassButton
import fr.lulu.unison.ui.glass.GlassIconButton
import fr.lulu.unison.ui.glass.GlassStyle
import fr.lulu.unison.ui.glass.GlassSurface
import fr.lulu.unison.ui.glass.PrimaryButton
import fr.lulu.unison.ui.glass.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    profile: Profile,
    deezer: DeezerState,
    listenerGranted: Boolean,
    onCreate: (TransportKind) -> Unit,
    onScan: () -> Unit,
    onEditProfile: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(TransportKind.LAN) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        BridgeHint(Modifier.padding(bottom = 14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.pressable(onClick = onEditProfile)) {
                Avatar(Member(profile.deviceId, profile.name, profile.color), 44.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Salut", style = Type.Footnote, color = Ink.Secondary)
                Text(profile.name, style = Type.Title2, color = Ink.Primary)
            }
            GlassIconButton(Icons.Rounded.Settings, "Réglages", onSettings, size = 44.dp)
        }

        Spacer(Modifier.height(36.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { UnisonLogo(130.dp) }
        Spacer(Modifier.height(12.dp))
        Text(
            "Écoutez ensemble",
            style = Type.LargeTitle, color = Ink.Primary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            "Chacun sur son Deezer, parfaitement synchronisés.",
            style = Type.Callout, color = Ink.Secondary,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
        )
        Spacer(Modifier.height(32.dp))

        // Carte de création
        GlassSurface(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedRectangle(32.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Nouvelle session", style = Type.Headline, color = Ink.Primary)
                Text("Ton téléphone devient l'hôte, pas besoin de serveur.", style = Type.Footnote, color = Ink.Secondary)
                Spacer(Modifier.height(16.dp))
                Segmented(
                    options = listOf(
                        Triple(TransportKind.LAN, "Wi-Fi", Icons.Rounded.Wifi),
                        Triple(TransportKind.NEARBY, "Sans Wi-Fi", Icons.Rounded.Bluetooth),
                    ),
                    selected = kind,
                    onSelect = { kind = it },
                )
                AnimatedVisibility(kind == TransportKind.NEARBY) {
                    Text(
                        "Connexion directe en Bluetooth / Wi-Fi Direct entre téléphones proches. Deezer a toujours besoin des données mobiles pour lire.",
                        style = Type.Footnote, color = Ink.Tertiary, modifier = Modifier.padding(top = 10.dp),
                    )
                }
                AnimatedVisibility(kind == TransportKind.LAN) {
                    Text(
                        "Tout le monde sur le même Wi-Fi, ou sur le partage de connexion d'un des téléphones.",
                        style = Type.Footnote, color = Ink.Tertiary, modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Spacer(Modifier.height(18.dp))
                PrimaryButton("Créer la session", onClick = { onCreate(kind) }, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(14.dp))
        GlassButton(onClick = onScan, modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
            Icon(Icons.Rounded.QrCodeScanner, null, tint = Ink.Primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Rejoindre une session", style = Type.Headline, color = Ink.Primary)
                Text("Scanne le QR code de l'hôte", style = Type.Footnote, color = Ink.Secondary)
            }
        }

        Spacer(Modifier.height(26.dp))
        Text("PRÊT À JOUER", style = Type.Caption, color = Ink.Tertiary, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedRectangle(26.dp), refraction = false) {
            Column(Modifier.padding(vertical = 6.dp)) {
                CheckRow(
                    ok = deezer.installed,
                    title = "Deezer installé",
                    subtitle = if (deezer.installed) "Premium requis pour choisir les titres" else "Installe Deezer depuis le Play Store",
                )
                CheckRow(
                    ok = listenerGranted,
                    title = "Contrôle de Deezer",
                    subtitle = if (listenerGranted) "Autorisé" else "Touche pour autoriser l'accès aux notifications",
                    onClick = if (listenerGranted) null else ({
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        GlassButton(
            onClick = { scope.launch { ApkSharer.share(context) } },
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = GlassStyle.Clear,
        ) {
            Icon(Icons.Rounded.IosShare, null, tint = Ink.Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Partager l'app à un ami", style = Type.Callout, color = Ink.Primary)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun CheckRow(ok: Boolean, title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null,
            tint = if (ok) Color(0xFF4CD9A0) else Color(0xFFFFB443), modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = Type.Callout, color = Ink.Primary)
            Text(subtitle, style = Type.Footnote, color = Ink.Secondary)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> Segmented(options: List<Triple<T, String, ImageVector>>, selected: T, onSelect: (T) -> Unit) {
    if (fr.lulu.unison.ui.theme.isExpressive) {
        // Groupe de boutons connectés M3 Expressive.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween)) {
            options.forEachIndexed { i, (value, label, icon) ->
                androidx.compose.material3.ToggleButton(
                    checked = value == selected,
                    onCheckedChange = { onSelect(value) },
                    modifier = Modifier.weight(1f),
                    shapes = when (i) {
                        0 -> androidx.compose.material3.ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> androidx.compose.material3.ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> androidx.compose.material3.ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                ) {
                    Icon(icon, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label)
                }
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().clip(Capsule()).background(Color.Black.copy(alpha = 0.25f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label, icon) ->
            val sel = value == selected
            Row(
                Modifier.weight(1f).clip(Capsule())
                    .background(if (sel) Color.White.copy(alpha = 0.92f) else Color.Transparent)
                    .pressable { onSelect(value) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = if (sel) Color.Black else Ink.Secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, style = Type.Callout, color = if (sel) Color.Black else Ink.Secondary)
            }
        }
    }
}

@Suppress("unused")
private val dot = CircleShape

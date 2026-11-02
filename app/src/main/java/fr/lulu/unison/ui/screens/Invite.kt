package fr.lulu.unison.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.kyant.shapes.RoundedRectangle
import fr.lulu.unison.apkshare.ApkSharer
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.session.SessionHandle
import fr.lulu.unison.ui.components.Avatar
import fr.lulu.unison.ui.glass.GlassButton
import fr.lulu.unison.ui.glass.GlassIconButton
import fr.lulu.unison.ui.glass.GlassStyle
import fr.lulu.unison.ui.glass.GlassSurface
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Type
import kotlinx.coroutines.launch

fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF0B0B12.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun InviteScreen(handle: SessionHandle, snapshot: SessionSnapshot, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = handle.joinInfo.toUri()
    val qr = remember(uri) { qrBitmap(uri).asImageBitmap() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Inviter", style = Type.Title, color = Ink.Primary, modifier = Modifier.weight(1f))
            GlassIconButton(Icons.Rounded.Close, "Fermer", onClose, size = 40.dp)
        }
        Spacer(Modifier.height(14.dp))

        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedRectangle(36.dp), style = GlassStyle.Regular) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(RoundedRectangle(28.dp)).background(Color.White).padding(14.dp)) {
                    Image(qr, "QR code de la session", Modifier.size(250.dp), filterQuality = FilterQuality.None)
                }
                Spacer(Modifier.height(18.dp))
                Text("Scanne pour rejoindre", style = Type.Title2, color = Ink.Primary)
                Text(
                    if (snapshot.transport == TransportKind.LAN)
                        "Avec Unison ou l'appareil photo. Il faut être sur le même Wi-Fi que ${if (handle.isHost) "toi" else snapshot.hostName}."
                    else "Avec Unison. Restez à quelques mètres : la connexion passe en Bluetooth / Wi-Fi Direct.",
                    style = Type.Footnote, color = Ink.Secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        GlassButton(onClick = { scope.launch { ApkSharer.share(context) } }, style = GlassStyle.Regular) {
            Icon(Icons.Rounded.IosShare, null, tint = Ink.Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Ton ami n'a pas l'app ? Partage-la", style = Type.Callout, color = Ink.Primary)
        }

        Spacer(Modifier.height(26.dp))
        Text(
            "PARTICIPANTS · ${snapshot.members.size}", style = Type.Caption, color = Ink.Tertiary,
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 8.dp),
        )
        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedRectangle(26.dp), refraction = false) {
            Column(Modifier.padding(vertical = 6.dp)) {
                snapshot.members.sortedByDescending { it.isHost }.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            m.name + if (m.id == handle.me.id) " (toi)" else "",
                            style = Type.Callout, color = Ink.Primary, modifier = Modifier.weight(1f),
                        )
                        if (m.isHost) Row(
                            Modifier.clip(RoundedRectangle(10.dp)).background(Color(0x33FFD93D)).padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Rounded.Star, null, tint = Color(0xFFFFD93D), modifier = Modifier.size(12.dp))
                            Text("Hôte", style = Type.Caption, color = Color(0xFFFFD93D))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

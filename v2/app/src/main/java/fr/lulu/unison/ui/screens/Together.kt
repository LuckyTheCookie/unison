package fr.lulu.unison.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fr.lulu.unison.R
import fr.lulu.unison.apkshare.ApkSharer
import fr.lulu.unison.model.JoinInfo
import fr.lulu.unison.model.TransportKind
import fr.lulu.unison.session.LinkStatus
import fr.lulu.unison.ui.AppViewModel
import fr.lulu.unison.ui.Busy
import fr.lulu.unison.ui.components.Avatar
import fr.lulu.unison.ui.components.GlassButton
import fr.lulu.unison.ui.components.GlassPanel
import fr.lulu.unison.ui.components.Glyph
import fr.lulu.unison.ui.components.IconBtn
import fr.lulu.unison.ui.components.PrimaryButton
import fr.lulu.unison.ui.components.Spinner
import fr.lulu.unison.ui.components.TopBar
import fr.lulu.unison.ui.components.pressable
import fr.lulu.unison.ui.theme.Ink
import fr.lulu.unison.ui.theme.Radius
import fr.lulu.unison.ui.theme.Type
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs

fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF0B0B12.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/**
 * « Écouter ensemble » : ouvrir sa file aux autres, rejoindre quelqu'un, ou voir la session en cours.
 * [onShare] et [onScan] passent par l'activité, qui demande d'abord les autorisations nécessaires.
 */
@Composable
fun TogetherScreen(vm: AppViewModel, onShare: (TransportKind) -> Unit, onScan: () -> Unit, onClose: () -> Unit) {
    val snap by vm.snapshot.collectAsState()
    val join by vm.joinInfo.collectAsState()
    val busy by vm.busy.collectAsState()
    val status by vm.linkStatus.collectAsState()
    val listenHere by vm.listenHere.collectAsState()
    val player by vm.player.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Ink.Sheet).statusBarsPadding().navigationBarsPadding()) {
        TopBar("Écouter ensemble", onBack = onClose)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            val info = join
            when {
                busy is Busy.Opening || busy is Busy.Joining -> Column(Modifier.fillMaxWidth().padding(vertical = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spinner(size = 34.dp)
                    Spacer(Modifier.height(18.dp))
                    Text((busy as? Busy.Joining)?.let { "Connexion à ${it.hostName}…" } ?: "Ouverture de la session…", style = Type.Title, color = Ink.Primary)
                }

                info == null -> {
                    Text(
                        "La même musique, au même instant, sur plusieurs téléphones. Chacun peut ajouter des titres à la file, passer au suivant ou mettre en pause.",
                        style = Type.Body, color = Ink.Secondary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Inviter", style = Type.Heading, color = Ink.Primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                    ChoiceCard(R.drawable.ic_wifi_line, "Sur le même Wi-Fi", "Le plus simple quand tout le monde est sur le même réseau.") { onShare(TransportKind.LAN) }
                    Spacer(Modifier.height(10.dp))
                    ChoiceCard(R.drawable.ic_bluetooth_line, "Sans Wi-Fi", "En voiture ou dehors : les téléphones se relient directement (Bluetooth et Wi-Fi Direct).") { onShare(TransportKind.NEARBY) }
                    Spacer(Modifier.height(22.dp))
                    Text("Rejoindre", style = Type.Heading, color = Ink.Primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                    ChoiceCard(R.drawable.ic_scan_line, "Scanner un QR code", "Vise le code affiché sur le téléphone de l'hôte. Ta file reviendra quand tu quitteras la session.", onClick = onScan)
                    Spacer(Modifier.height(22.dp))
                    GlassButton("Envoyer Unison à un ami", { scope.launch { ApkSharer.share(context) } }, Modifier.fillMaxWidth(), icon = R.drawable.ic_share_line)
                }

                else -> {
                    if (vm.isHost) {
                        val qr = remember(info) { qrBitmap(info.toUri()).asImageBitmap() }
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.padding(top = 8.dp).size(232.dp).clip(Radius.Panel).background(Color.White).padding(14.dp)) {
                                Image(qr, "QR code de la session", Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("Fais scanner ce code depuis Unison", style = Type.Title, color = Ink.Primary, textAlign = TextAlign.Center)
                            Text(
                                if (info.transport == TransportKind.NEARBY) "Liaison directe, sans Wi-Fi" else "Même Wi-Fi requis · ${info.host}",
                                style = Type.Body, color = Ink.Secondary, textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Session de ${info.hostName}", style = Type.Heading, color = Ink.Primary)
                            Text(
                                when (val s = status) {
                                    LinkStatus.Connected -> "Connecté"
                                    is LinkStatus.Reconnecting -> "Reconnexion… (essai ${s.attempt})"
                                    is LinkStatus.Lost -> s.reason
                                },
                                style = Type.Body, color = if (status is LinkStatus.Lost) Ink.Danger else Ink.Secondary,
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text("${snap.members.size} personne${if (snap.members.size > 1) "s" else ""}", style = Type.Heading, color = Ink.Primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                    GlassPanel(Modifier.fillMaxWidth()) {
                        snap.members.forEach { m ->
                            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(m.name, m.color)
                                Spacer(Modifier.width(12.dp))
                                Text(m.name + if (m.id == vm.myId) " (toi)" else "", style = Type.Row, color = Ink.Primary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (m.isHost) Text("Hôte", style = Type.Caption, color = Ink.Tertiary)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                        Row(Modifier.fillMaxWidth().pressable(scale = 0.99f) { vm.setListenHere(!listenHere) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(if (listenHere) R.drawable.ic_headphone_fill else R.drawable.ic_headphone_line, tint = Ink.Primary, size = 22.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Écouter sur ce téléphone", style = Type.Row, color = Ink.Primary)
                                Text(
                                    when {
                                        !listenHere -> "Tu gères la file sans sortir le son ici"
                                        !snap.shared -> "En attente d'un autre téléphone"
                                        player.driftMs == null -> "Synchronisation…"
                                        abs(player.driftMs!!) <= 30 -> "Parfaitement calé (${player.driftMs} ms)"
                                        else -> "Calage en cours (${player.driftMs} ms)"
                                    },
                                    style = Type.Caption, color = Ink.Tertiary,
                                )
                            }
                            Toggle(listenHere)
                        }
                    }

                    Spacer(Modifier.height(22.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (vm.isHost) GlassButton("Envoyer Unison", { scope.launch { ApkSharer.share(context) } }, Modifier.weight(1f), icon = R.drawable.ic_share_line)
                        GlassButton(
                            if (vm.isHost) "Fermer la session" else "Quitter la session", { vm.leave() }, Modifier.weight(1f),
                            icon = R.drawable.ic_exit_line, tint = Ink.Danger,
                        )
                    }
                }
            }
        }
    }
    (busy as? Busy.Failed)?.let { Alert("Ça n'a pas marché", it.message, onDismiss = vm::dismissBusy) }
}

@Composable
private fun ChoiceCard(icon: Int, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.Panel).background(Ink.Fill).pressable(scale = 0.985f, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(Radius.Item).background(Ink.FillHover), contentAlignment = Alignment.Center) { Glyph(icon, size = 22.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Title, color = Ink.Primary)
            Text(detail, style = Type.Body, color = Ink.Secondary)
        }
    }
}

@Composable
fun ScannerScreen(onResult: (String) -> Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    var hint by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraPreview(onCode = { raw ->
                when (JoinInfo.inspect(raw)) {
                    is JoinInfo.Companion.Parsed.Ok -> onResult(raw)
                    JoinInfo.Companion.Parsed.Outdated -> { hint = "Session d'une ancienne version : mets Unison à jour sur les deux téléphones"; false }
                    JoinInfo.Companion.Parsed.Invalid -> { hint = "Ce QR code n'est pas une session Unison"; false }
                }
            })
            ScanOverlay()
        } else {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Unison a besoin de l'appareil photo pour scanner le QR code.", style = Type.Body, color = Ink.Secondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Autoriser", onClick = { launcher.launch(Manifest.permission.CAMERA) })
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rejoindre", style = Type.Heading, color = Color.White, modifier = Modifier.weight(1f))
            IconBtn(R.drawable.ic_close_line, "Fermer", onClose, background = Color.Black.copy(alpha = 0.45f))
        }
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).clip(Radius.Pill).background(Color.Black.copy(alpha = 0.6f))) {
            Text(hint ?: "Vise le QR code affiché par l'hôte", style = Type.Label, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp))
        }
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    var handled by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown(); scanner.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || handled) { proxy.close(); return@setAnalyzer }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val raw = codes.firstNotNullOfOrNull { it.rawValue }
                            if (raw != null && !handled) {
                                ContextCompat.getMainExecutor(context).execute { if (!handled && onCode(raw)) handled = true }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}

@Composable
private fun ScanOverlay() {
    val t = rememberInfiniteTransition(label = "scan")
    val pulse by t.animateFloat(0.97f, 1.03f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "p")
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val side = size.minDimension * 0.68f * pulse
        val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2.2f)
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.Transparent, topLeft, Size(side, side), CornerRadius(56f, 56f), blendMode = BlendMode.Clear)
        drawRoundRect(Color.White.copy(alpha = 0.9f), topLeft, Size(side, side), CornerRadius(56f, 56f), style = Stroke(width = 5f))
    }
}

package fr.lulu.unison.ui.theme

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Les deux designs de l'app, au choix dans les réglages. */
enum class UiStyle { Glass, Expressive }

val LocalUiStyle = staticCompositionLocalOf { UiStyle.Glass }

val isExpressive: Boolean
    @Composable @ReadOnlyComposable get() = LocalUiStyle.current == UiStyle.Expressive

/** Couleurs sémantiques : verre sombre, ou rôles Material You en Expressive. */
object Ink {
    private val GlassBg = Color(0xFF07070B)

    val Bg: Color @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.colorScheme.surface else GlassBg
    val Primary: Color @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.colorScheme.onSurface else Color.White
    val Secondary: Color @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.colorScheme.onSurfaceVariant else Color.White.copy(alpha = 0.62f)
    val Tertiary: Color @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.38f)
    val Hairline: Color @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.colorScheme.outlineVariant else Color.White.copy(alpha = 0.12f)
    /** Fond discret des pastilles et puces. */
    val Fill: Color @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White.copy(alpha = 0.10f)
    /** Couleur d'action principale et son contenu. */
    val Accent: Color @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.colorScheme.primary else Color.White
    val OnAccent: Color @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.colorScheme.onPrimary else Color.Black
    val Danger: Color @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.colorScheme.error else Color(0xFFFF5A5F)
    val Success = Color(0xFF4CD9A0)
    val Warning = Color(0xFFFFB443)
}

private val Sans = FontFamily.SansSerif

/** Typographie : serrée façon SF Pro (verre), ou échelle M3 « emphasized » (Expressive). */
object Type {
    private val gLargeTitle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 40.sp, letterSpacing = (-0.035).em, lineHeight = 44.sp)
    private val gTitle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.03).em, lineHeight = 32.sp)
    private val gTitle2 = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.02).em, lineHeight = 26.sp)
    private val gHeadline = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = (-0.01).em, lineHeight = 22.sp)
    private val gBody = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, letterSpacing = (-0.005).em, lineHeight = 21.sp)
    private val gCallout = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = (-0.005).em, lineHeight = 20.sp)
    private val gFootnote = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.em, lineHeight = 17.sp)
    private val gCaption = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.06.em, lineHeight = 14.sp)

    val LargeTitle: TextStyle @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.typography.displaySmallEmphasized else gLargeTitle
    val Title: TextStyle @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.typography.headlineMediumEmphasized else gTitle
    val Title2: TextStyle @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.typography.headlineSmallEmphasized else gTitle2
    val Headline: TextStyle @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.typography.titleMediumEmphasized else gHeadline
    val Body: TextStyle @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.typography.bodyLarge else gBody
    val Callout: TextStyle @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.typography.labelLargeEmphasized else gCallout
    val Footnote: TextStyle @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.typography.bodySmall else gFootnote
    val Caption: TextStyle @Composable @ReadOnlyComposable get() = if (isExpressive) MaterialTheme.typography.labelMediumEmphasized else gCaption
    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp)
}

private val GlassScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color(0xFF07070B),
    surface = Color(0xFF15151C),
    onSurface = Color.White,
    onBackground = Color.White,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UnisonTheme(style: UiStyle = UiStyle.Glass, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val lightBars = style == UiStyle.Expressive && !dark

    // Icônes de la barre d'état claires ou sombres selon le thème.
    LaunchedEffect(lightBars) {
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (lightBars) SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
            else SystemBarStyle.dark(Color.Transparent.toArgb()),
            navigationBarStyle = if (lightBars) SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
            else SystemBarStyle.dark(Color.Transparent.toArgb()),
        )
    }

    CompositionLocalProvider(LocalUiStyle provides style) {
        when (style) {
            UiStyle.Glass -> MaterialTheme(
                colorScheme = GlassScheme,
                typography = Typography(bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp)),
                content = content,
            )
            UiStyle.Expressive -> MaterialExpressiveTheme(
                // Material You : couleurs tirées du fond d'écran.
                colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
                motionScheme = MotionScheme.expressive(),
                content = content,
            )
        }
    }
}

@Suppress("unused")
private fun Activity.unused() = Unit

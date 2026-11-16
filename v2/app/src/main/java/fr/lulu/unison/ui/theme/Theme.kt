package fr.lulu.unison.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.lulu.unison.R

/**
 * Le chrome reste neutre (blanc ou noir à faible opacité) : c'est la pochette, à travers le fond animé,
 * qui donne sa couleur à l'écran.
 */
object Ink {
    val Canvas = Color(0xFF000000)
    val Surface = Color(0xFF0A0A0A)
    val Elevated = Color(0xFF141414)
    val Panel = Color(0xFF1A1A1A)

    val Primary = Color(0xFFFFFFFF)
    val Secondary = Color(0xB3FFFFFF)
    val Tertiary = Color(0x73FFFFFF)

    val FillSubtle = Color(0x0AFFFFFF)
    val Fill = Color(0x12FFFFFF)
    val FillHover = Color(0x1AFFFFFF)
    val FillStrong = Color(0x24FFFFFF)
    val Hairline = Color(0x14FFFFFF)

    /** Fond des surfaces en verre posées sur du contenu qui défile. */
    val Glass = Color(0x99161618)
    val Sheet = Color(0xF2121214)

    val Accent = Color(0xFFC9A5FF)
    val Danger = Color(0xFFFF6B6B)

    /** Bouton principal : clair sur fond sombre. */
    val ButtonBg = Color(0xFFE6E6EA)
    val ButtonText = Color(0xFF000000)
}

object Radius {
    val Control = RoundedCornerShape(10.dp)
    val Item = RoundedCornerShape(12.dp)
    val Art = RoundedCornerShape(16.dp)
    val Panel = RoundedCornerShape(20.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val Pill = RoundedCornerShape(50)
}

object Motion {
    /** Courbe de sortie douce, commune à toutes les transitions. */
    val Ease = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    const val Fast = 160
    const val Page = 220
    const val Slow = 420
}

private fun variable(res: Int, vararg weights: Int) = FontFamily(
    weights.map { w -> Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) },
)

/** Inter pour l'interface, Montserrat pour le lecteur et les paroles (polices libres, licence OFL). */
val Inter = variable(R.font.inter, 400, 500, 600, 700)
val Montserrat = variable(R.font.montserrat, 500, 600, 700, 800)

object Type {
    private fun inter(size: Int, weight: Int, line: Int, tracking: Float = 0f) = TextStyle(
        fontFamily = Inter, fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = line.sp, letterSpacing = tracking.em,
    )

    val Caption = inter(12, 500, 16)
    val Body = inter(14, 400, 20)
    val Label = inter(14, 500, 18)
    val Row = inter(15, 500, 20)
    val Title = inter(17, 600, 22, -0.01f)
    val Heading = inter(20, 700, 26, -0.015f)
    val Section = inter(22, 700, 28, -0.02f)
    val Hero = inter(30, 700, 36, -0.025f)
    val Button = inter(15, 600, 20)

    val PlayerTitle = TextStyle(fontFamily = Montserrat, fontSize = 22.sp, fontWeight = FontWeight(700), lineHeight = 28.sp)
    val PlayerArtist = TextStyle(fontFamily = Montserrat, fontSize = 15.sp, fontWeight = FontWeight(500), lineHeight = 20.sp)
    val Lyric = TextStyle(fontFamily = Montserrat, fontSize = 26.sp, fontWeight = FontWeight(700), lineHeight = 34.sp)
    val Time = inter(12, 500, 16).copy(fontFeatureSettings = "tnum")
}

@Composable
fun UnisonTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Ink.Accent,
        onPrimary = Color.Black,
        background = Ink.Canvas,
        onBackground = Ink.Primary,
        surface = Ink.Surface,
        onSurface = Ink.Primary,
        surfaceVariant = Ink.Elevated,
        onSurfaceVariant = Ink.Secondary,
        surfaceContainer = Ink.Elevated,
        surfaceContainerHigh = Ink.Panel,
        outline = Ink.Hairline,
        error = Ink.Danger,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides Ink.Primary, content = content)
    }
}

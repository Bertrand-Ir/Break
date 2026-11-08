package app.breakpiano.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.breakpiano.R

/**
 * Break brand palette. One brand color (Ember), used flat and sparingly — the logo and the
 * key light. Every control is greyscale: raised when idle, recessed ("pressed in") when active.
 */
object Brand {
    val Obsidian = Color(0xFF0B0B0F)   // app background, piano lacquer
    val Night = Color(0xFF111116)      // recessed wells
    val Graphite = Color(0xFF17171D)   // surfaces
    val Slate = Color(0xFF24242C)      // raised controls
    val SlateHi = Color(0xFF2E2E38)    // raised control edge / hover
    val Pressed = Color(0xFF0F0F13)    // active control — sits below the others
    val Line = Color(0xFF2B2B36)       // hairlines
    val Ivory = Color(0xFFF4EFE6)      // primary text, white keys, "on" states
    val Mist = Color(0xFFA6A3B3)       // secondary text
    val Dim = Color(0xFF6F6C7D)        // tertiary text
    val Ember = Color(0xFFFF5A3C)      // THE brand color — flat, never a gradient
    val Rec = Color(0xFFE5303F)        // record / destructive
    val Felt = Color(0xFF4A0E18)
    val Success = Color(0xFF59D99B)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(
    manrope(400), manrope(500), manrope(600), manrope(700), manrope(800),
)

object Type {
    val Wordmark = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 22.sp, letterSpacing = (-0.6).sp)
    val Display = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 44.sp, letterSpacing = (-1.5).sp)
    val Title = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 20.sp, letterSpacing = (-0.4).sp)
    val Heading = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(700), fontSize = 15.sp, letterSpacing = (-0.1).sp)
    val Body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 13.sp)
    val Label = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(700), fontSize = 12.sp, letterSpacing = 0.1.sp)
    val Caption = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(600), fontSize = 10.5.sp, letterSpacing = 0.6.sp)
    val Mono = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 13.sp, letterSpacing = 0.sp)
}

@Composable
fun BreakTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Brand.Ember,
            onPrimary = Color.White,
            secondary = Brand.Ivory,
            background = Brand.Obsidian,
            surface = Brand.Graphite,
            surfaceVariant = Brand.Slate,
            onSurface = Brand.Ivory,
            onSurfaceVariant = Brand.Mist,
            outline = Brand.Line,
        ),
        content = content,
    )
}

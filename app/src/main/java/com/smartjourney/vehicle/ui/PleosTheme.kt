@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.smartjourney.vehicle.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartjourney.vehicle.R

// Pleos Connect 2.1.5: Qc0CvEITX6nhlFIGDx8pzf (Colors, Typography, Basic Button).
// System surfaces and text follow the vehicle's day/night configuration.
private val LocalNight = staticCompositionLocalOf { false }
private val AstaSans = FontFamily(
    Font(R.font.asta_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.asta_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.asta_sans, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.asta_sans, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

object Palette {
    private val night: Boolean @Composable @ReadOnlyComposable get() = LocalNight.current
    private fun pick(night: Boolean, day: Long, dark: Long) = Color(if (night) dark else day)
    val Background: Color @Composable @ReadOnlyComposable get() = pick(night, 0xFFF7F8FA, 0xFF1D1E21)
    val Surface: Color @Composable @ReadOnlyComposable get() = pick(night, 0xFFFFFFFF, 0xFF131417)
    val Elevated: Color @Composable @ReadOnlyComposable get() = pick(night, 0xFFFFFFFF, 0xFF313236)
    val Ink: Color @Composable @ReadOnlyComposable get() = pick(night, 0xFF131417, 0xFFFFFFFF)
    val Muted: Color @Composable @ReadOnlyComposable get() = Ink.copy(alpha = 0.64f)
    val Line: Color @Composable @ReadOnlyComposable get() = Ink.copy(alpha = 0.10f)
    val Primary: Color @Composable @ReadOnlyComposable get() = pick(night, 0xFF44464E, 0xFFE0E1E6)
    val OnPrimary: Color @Composable @ReadOnlyComposable get() = pick(night, 0xFFFFFFFF, 0xFF131417)
    val Button: Color @Composable @ReadOnlyComposable get() = Ink.copy(alpha = 0.05f)
    val Blue: Color @Composable @ReadOnlyComposable get() = pick(night, 0xFF0064FF, 0xFF6FAAFF)
    // Darker informative foregrounds on light surfaces preserve the guide's 4.5:1 contrast.
    val Green: Color @Composable @ReadOnlyComposable get() = pick(night, 0xFF00723B, 0xFF00E074)
    val Orange: Color @Composable @ReadOnlyComposable get() = pick(night, 0xFF9A4000, 0xFFFFB580)
    val Red: Color @Composable @ReadOnlyComposable get() = pick(night, 0xFFBA2910, 0xFFFF927D)
    val Gray: Color @Composable @ReadOnlyComposable get() = Ink.copy(alpha = 0.84f)
    val DemoBackground: Color @Composable @ReadOnlyComposable get() = Elevated
    val DemoInk: Color @Composable @ReadOnlyComposable get() = Ink
}

object Type {
    private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = AstaSans, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)
    val Display: TextStyle @Composable @ReadOnlyComposable get() = style(60, 80, FontWeight.ExtraBold).copy(color = Palette.Ink)
    val Headline: TextStyle @Composable @ReadOnlyComposable get() = style(48, 64, FontWeight.Bold).copy(color = Palette.Ink)
    val Title: TextStyle @Composable @ReadOnlyComposable get() = style(32, 40, FontWeight.Bold).copy(color = Palette.Ink)
    val Body: TextStyle @Composable @ReadOnlyComposable get() = style(28, 36).copy(color = Palette.Ink)
    val Label: TextStyle @Composable @ReadOnlyComposable get() = style(24, 28).copy(color = Palette.Muted)
    val Caption: TextStyle @Composable @ReadOnlyComposable get() = style(20, 24).copy(color = Palette.Muted)
}

object PleosSpace {
    val Page = 40.dp
    val Section = 32.dp
    val Gap = 24.dp
    val CardRadius = 32.dp
}

@Composable
fun SmartJourneyTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNight provides isSystemInDarkTheme()) {
        val colors = if (LocalNight.current) darkColorScheme(
            primary = Palette.Primary, onPrimary = Palette.OnPrimary,
            background = Palette.Background, onBackground = Palette.Ink,
            surface = Palette.Surface, onSurface = Palette.Ink,
            surfaceVariant = Palette.Elevated, onSurfaceVariant = Palette.Gray,
            outline = Palette.Line, error = Palette.Red,
        ) else lightColorScheme(
            primary = Palette.Primary, onPrimary = Palette.OnPrimary,
            background = Palette.Background, onBackground = Palette.Ink,
            surface = Palette.Surface, onSurface = Palette.Ink,
            surfaceVariant = Palette.Elevated, onSurfaceVariant = Palette.Gray,
            outline = Palette.Line, error = Palette.Red,
        )
        MaterialTheme(
            colorScheme = colors,
            typography = Typography(
                displayLarge = Type.Display, headlineLarge = Type.Headline,
                titleLarge = Type.Title, bodyLarge = Type.Body,
                bodyMedium = Type.Label.copy(color = Palette.Ink),
                labelLarge = Type.Body.copy(fontWeight = FontWeight.Bold),
                labelMedium = Type.Label,
            ),
            content = content,
        )
    }
}

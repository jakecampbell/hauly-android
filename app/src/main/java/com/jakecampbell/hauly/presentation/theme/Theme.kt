package com.jakecampbell.hauly.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Minimalist dark palette: near-black surfaces, a single accent in Hauly's
// brand blue (#06AFFF, matching the app icon).
private val Ink = Color(0xFF101314)
private val InkRaised = Color(0xFF1A1E20)
private val InkHigh = Color(0xFF24292C)
private val Mist = Color(0xFFE4E7E8)
private val MistDim = Color(0xFF9AA4A8)
private val HaulyBlue = Color(0xFF06AFFF)
private val HaulyBlueDeep = Color(0xFF0E3A52)
private val Ember = Color(0xFFF28B82)

private val HaulyColors = darkColorScheme(
    primary = HaulyBlue,
    onPrimary = Color(0xFF00253A),
    primaryContainer = HaulyBlueDeep,
    onPrimaryContainer = HaulyBlue,
    secondary = MistDim,
    onSecondary = Ink,
    secondaryContainer = InkHigh,
    onSecondaryContainer = Mist,
    background = Ink,
    onBackground = Mist,
    surface = Ink,
    onSurface = Mist,
    surfaceVariant = InkRaised,
    onSurfaceVariant = MistDim,
    surfaceContainer = InkRaised,
    surfaceContainerHigh = InkHigh,
    surfaceContainerHighest = InkHigh,
    error = Ember,
    onError = Color(0xFF3A100C),
    outline = Color(0xFF3A4145),
    outlineVariant = Color(0xFF2A3034),
)

private val HaulyTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

private val HaulyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

/** Hauly is dark-only by design: a fast, glare-free list for one-handed use. */
@Composable
fun HaulyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HaulyColors,
        typography = HaulyTypography,
        shapes = HaulyShapes,
        content = content,
    )
}

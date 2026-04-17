package com.handleit.transitpresence.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── Color palette ────────────────────────────────────────────────────────────

object TransitColors {
    val Background = Color(0xFF0A0E1A)
    val Surface = Color(0xFF0F1628)
    val SurfaceVariant = Color(0xFF141E35)
    val Border = Color(0xFF1A2545)

    val Accent = Color(0xFF00D4FF)
    val AccentDim = Color(0xFF0099BB)
    val Green = Color(0xFF00FF9F)
    val GreenDim = Color(0xFF00C478)
    val Yellow = Color(0xFFFFD600)
    val Orange = Color(0xFFFF6B35)
    val Red = Color(0xFFFF3366)
    val Muted = Color(0xFF4A5A7A)

    val TextPrimary = Color(0xFFC8D8F0)
    val TextSecondary = Color(0xFF7A8AAA)
    val TextOnAccent = Color(0xFF000000)

    // State-specific colors
    val StateIdle = Muted
    val StateWaiting = Green
    val StateApproaching = Accent
    val StateBoarding = Yellow
    val StateOnBus = Green
    val StateApproachingExit = Orange
    val StateExitWindow = Red
    val StateComplete = Muted
}

// ─── Dark color scheme ────────────────────────────────────────────────────────

private val DarkColorScheme = darkColorScheme(
    primary = TransitColors.Accent,
    onPrimary = TransitColors.TextOnAccent,
    primaryContainer = Color(0xFF00344A),
    onPrimaryContainer = TransitColors.Accent,
    secondary = TransitColors.Green,
    onSecondary = TransitColors.TextOnAccent,
    tertiary = TransitColors.Yellow,
    background = TransitColors.Background,
    onBackground = TransitColors.TextPrimary,
    surface = TransitColors.Surface,
    onSurface = TransitColors.TextPrimary,
    surfaceVariant = TransitColors.SurfaceVariant,
    onSurfaceVariant = TransitColors.TextSecondary,
    outline = TransitColors.Border,
    error = TransitColors.Red,
)

// ─── Typography ───────────────────────────────────────────────────────────────

// In production: declare actual font resources; use system monospace as fallback
val MonoFontFamily = FontFamily.Monospace

val TransitTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp,
    ),
)

// ─── App theme ────────────────────────────────────────────────────────────────

@Composable
fun TransitPresenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = TransitTypography,
        content = content,
    )
}

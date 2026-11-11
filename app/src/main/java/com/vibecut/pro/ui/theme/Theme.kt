package com.vibecut.pro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.max

val ObsidianBg = Color(0xFF0B0D12)
val PanelDark = Color(0xFF131722)
val SurfaceElevated = Color(0xFF1B2130)
val SurfaceStroke = Color(0xFF283147)
val ElectricCyan = Color(0xFF00E5FF)
val NeonViolet = Color(0xFF7C4DFF)
val KeyframeGold = Color(0xFFFFAB00)
val CoralRed = Color(0xFFFF1744)
val EmeraldBeat = Color(0xFF00E676)
val TextPrimary = Color(0xFFF5F7FA)
val TextSecondary = Color(0xFF9AA5B8)

private val VibeCutDarkColors = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF001F24),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = NeonViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF341E78),
    onSecondaryContainer = Color(0xFFE5DEFF),
    tertiary = KeyframeGold,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = PanelDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = SurfaceStroke,
    error = Color(0xFFFF5252)
)

private val VibeCutTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    )
)

@Composable
fun VibeCutProTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VibeCutDarkColors,
        typography = VibeCutTypography,
        content = content
    )
}

fun formatTimecode(ms: Long, fps: Int = 30): String {
    val safe = max(0L, ms)
    val totalSec = safe / 1_000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    val frame = ((safe % 1_000L) * fps.coerceAtLeast(1)) / 1_000L
    return String.format(Locale.US, "%02d:%02d.%02d", mins, secs, frame)
}

fun formatCompactDuration(ms: Long): String {
    val safe = max(0L, ms)
    val totalSec = safe / 1_000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

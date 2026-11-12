package com.vibecut.pro.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------- design tokens

/** Near-black studio canvas. */
val ZeroBlack = Color(0xFF08090C)
/** Primary editor background. */
val ZeroBackground = Color(0xFF0D0F14)
/** Panels, sheets and cards. */
val ZeroSurface = Color(0xFF14171E)
/** Elevated controls and chips. */
val ZeroSurfaceHigh = Color(0xFF1C2029)
/** Hairline separators and borders. */
val ZeroStroke = Color(0xFF2A303C)

/** Primary accent: fresh mint used for active states and the playhead. */
val ZeroAccent = Color(0xFF6EF2B5)
/** Accent used for warning/FX badges and keyframes. */
val ZeroAmber = Color(0xFFFFC46B)
val ZeroViolet = Color(0xFF9B8CFF)
val ZeroCoral = Color(0xFFFF7A7A)
val ZeroBlue = Color(0xFF64B5F6)

val TextPrimary = Color(0xFFF2F5F9)
val TextSecondary = Color(0xFFA4ADBD)
val TextTertiary = Color(0xFF6C7689)

// Timeline lane tints
val LaneVideo = Color(0xFF2A4C7E)
val LaneOverlay = Color(0xFF4A3A78)
val LaneText = Color(0xFF6E4A2A)
val LaneSticker = Color(0xFF6B3550)
val LaneAudio = Color(0xFF1F5F52)
val LaneVoice = Color(0xFF5A3350)
val LaneSfx = Color(0xFF3E5230)

private val ZeroColorScheme = darkColorScheme(
    primary = ZeroAccent,
    onPrimary = Color(0xFF00251A),
    primaryContainer = Color(0xFF11362A),
    onPrimaryContainer = Color(0xFFB8FFDE),
    secondary = ZeroViolet,
    onSecondary = Color(0xFF140F2E),
    tertiary = ZeroAmber,
    background = ZeroBackground,
    onBackground = TextPrimary,
    surface = ZeroSurface,
    onSurface = TextPrimary,
    surfaceVariant = ZeroSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = ZeroStroke,
    error = ZeroCoral
)

private val ZeroTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    )
)

@Composable
fun ZeroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZeroColorScheme,
        typography = ZeroTypography,
        content = content
    )
}

// ---------------------------------------------------------------------------- legacy aliases
// Old screens used these names; they now point at the premium palette so nothing regresses visually
// while individual screens are migrated to the tokens above.

val ElectricCyan = ZeroAccent
val NeonViolet = ZeroViolet
val KeyframeGold = ZeroAmber
val SurfaceStroke = ZeroStroke
val StudioBackground = ZeroBackground
val StudioSurface = ZeroSurface
val StudioSurfaceHigh = ZeroSurfaceHigh
val StudioPanel = ZeroSurface
val StudioMuted = TextSecondary
val PanelDark = ZeroSurface
val SurfaceElevated = ZeroSurfaceHigh
val ObsidianBg = ZeroBackground
val DangerRed = ZeroCoral
val CoralRed = ZeroCoral
val EmeraldBeat = ZeroAccent
val SkyBlue = ZeroBlue
val AmberGold = ZeroAmber

@Composable
fun VibeCutProTheme(content: @Composable () -> Unit) = ZeroTheme(content)

// ---------------------------------------------------------------------------- time helpers

/** `mm:ss.ff` timecode at the project frame rate. */
fun formatTimecode(ms: Long, fps: Int = 30): String {
    val safe = max(0L, ms)
    val totalSec = safe / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    val frames = ((safe % 1000L) * fps.coerceAtLeast(1)) / 1000L
    return String.format(Locale.US, "%02d:%02d.%02d", mins, secs, frames)
}

fun formatDurationShort(ms: Long): String {
    val safe = max(0L, ms)
    val secs = safe / 1000.0
    return if (secs < 10) String.format(Locale.US, "%.1fs", secs)
    else String.format(Locale.US, "%d:%02d", (safe / 60000L).toInt(), ((safe / 1000L) % 60L).toInt())
}

/** `+12` / `-8` / `0` for signed sliders. */
fun formatSignedPercent(value: Float): String {
    val v = (value * 100).roundToInt()
    return when {
        v > 0 -> "+$v"
        v < 0 -> "$v"
        else -> "0"
    }
}

// ---------------------------------------------------------------------------- shared components

/** Section card used inside every tool sheet. */
@Composable
fun StudioCard(
    title: String? = null,
    badge: String? = null,
    onReset: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ZeroSurface)
            .border(1.dp, ZeroStroke, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (title != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    if (badge != null) {
                        Text(
                            badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZeroAccent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ZeroAccent.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (onReset != null) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onReset)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = TextTertiary, modifier = Modifier.size(13.dp))
                        Text("Reset", fontSize = 10.sp, color = TextTertiary)
                    }
                }
            }
        }
        content()
    }
}

/** Slider row with label, live value and double-tap-to-reset semantics. */
@Composable
fun StudioSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    valueText: String,
    accent: Color = ZeroAccent,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 12.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(valueText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = ZeroStroke
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
        )
        if (value != defaultValue) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onValueChange(defaultValue) }
                    .padding(vertical = 1.dp, horizontal = 2.dp)
            ) {
                Text("reset", fontSize = 9.sp, color = TextTertiary)
            }
        }
    }
}

/** Pill-shaped chip used for presets and segmented options. */
@Composable
fun StudioChip(
    label: String,
    selected: Boolean = false,
    accent: Color = ZeroAccent,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else ZeroSurfaceHigh)
            .border(1.dp, if (selected) accent else ZeroStroke, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accent else TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
fun StudioChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Icon + label action button used in tool clusters. */
@Composable
fun StudioAction(
    icon: ImageVector,
    label: String,
    tint: Color = TextPrimary,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(66.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ZeroSurfaceHigh)
            .border(1.dp, ZeroStroke, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) tint else TextTertiary, modifier = Modifier.size(19.dp))
        Text(
            label,
            fontSize = 9.5.sp,
            color = if (enabled) TextSecondary else TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StudioToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = TextPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ZeroBlack,
                checkedTrackColor = ZeroAccent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = ZeroSurfaceHigh
            ),
            modifier = Modifier.height(26.dp)
        )
    }
}

/** Large success/danger primary button for sheets. */
@Composable
fun StudioPrimaryButton(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    container: Color = ZeroAccent,
    contentColor: Color = ZeroBlack,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(46.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = contentColor)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Sheet header with title, subtitle and apply/done affordance. */
@Composable
fun SheetHeader(
    title: String,
    subtitle: String? = null,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ZeroAccent)
                .clickable(onClick = onClose)
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text("Done", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ZeroBlack)
        }
    }
}

/** Card gradient used behind empty states and hero headers. */
val StudioGradient: Brush = Brush.linearGradient(
    listOf(Color(0xFF16241F), Color(0xFF14171E))
)

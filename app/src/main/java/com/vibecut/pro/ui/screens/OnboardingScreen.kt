package com.vibecut.pro.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecut.pro.ui.theme.*

private data class OnboardingPage(
    val icon: ImageVector,
    val badge: String,
    val title: String,
    val description: String,
    val accent: Color
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pages = remember {
        listOf(
            OnboardingPage(
                icon = Icons.Default.MovieFilter,
                badge = "MULTI-TRACK ENGINE",
                title = "Precision VN-Style Timeline",
                description = "Magnetic multi-layer editing with frame-accurate split, speed curves, freeze frames, keyframes & 100-step undo/redo.",
                accent = ElectricCyan
            ),
            OnboardingPage(
                icon = Icons.Default.Tune,
                badge = "OPENGL ES 2.0 SHADERS",
                title = "Pro Sharpen, 22 FX & 3D LUTs",
                description = "Stack real GPU effects—Unsharp-Mask Sharpen, Neon Glow, VHS, Glitch—plus 8-band HSL, RGB Curves & .cube 3D LUT color grading.",
                accent = NeonViolet
            ),
            OnboardingPage(
                icon = Icons.Default.AutoAwesome,
                badge = "LOCAL ON-DEVICE AI",
                title = "AI Vision, Beat Sync & Hindi/EN Captions",
                description = "ML Kit Face & Subject Tracking, Auto-Reframe, Scene Detection, PCM Beat Sync & Speech-to-Text captions—100% local, zero login required.",
                accent = KeyframeGold
            ),
            OnboardingPage(
                icon = Icons.Default.HighQuality,
                badge = "MEDIACODEC HARDWARE EXPORT",
                title = "Up to 4K 60fps H.264 / HEVC",
                description = "Hardware-accelerated Media3 Transformer export with custom Mbps bitrate control, audio-only mastering & direct gallery save.",
                accent = EmeraldBeat
            )
        )
    }

    var pageIndex by remember { mutableIntStateOf(0) }
    val current = pages[pageIndex]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(ObsidianBg, Color(0xFF101524), ObsidianBg)
                )
            )
            .systemBarsPadding()
            .padding(24.dp)
    ) {
        TextButton(
            onClick = onFinish,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Text("Skip", color = TextSecondary)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedContent(targetState = current, label = "onboarding_card") { item ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(108.dp)
                            .clip(RoundedCornerShape(30.dp))
                            .background(item.accent.copy(alpha = 0.14f))
                            .border(1.5.dp, item.accent.copy(alpha = 0.6f), RoundedCornerShape(30.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = item.accent,
                            modifier = Modifier.size(52.dp)
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Surface(
                        color = item.accent.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = item.badge,
                            color = item.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            Spacer(Modifier.height(36.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.forEachIndexed { idx, p ->
                    Box(
                        modifier = Modifier
                            .height(8.dp)
                            .width(if (idx == pageIndex) 28.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (idx == pageIndex) p.accent else SurfaceStroke)
                    )
                }
            }
        }

        Button(
            onClick = {
                if (pageIndex < pages.lastIndex) pageIndex++ else onFinish()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ElectricCyan,
                contentColor = Color(0xFF001F24)
            )
        ) {
            Text(
                text = if (pageIndex < pages.lastIndex) "Continue" else "Enter VibeCut Studio",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

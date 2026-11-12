package com.vibecut.pro.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
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
import com.vibecut.pro.engine.model.EffectType
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
                badge = "MULTI-TRACK TIMELINE",
                title = "Frame-accurate editing",
                description = "Drag the playhead, pinch to zoom, trim with handles, split at the frame, stack overlay, text and audio tracks with 100-step undo.",
                accent = ZeroAccent
            ),
            OnboardingPage(
                icon = Icons.Default.Tune,
                badge = "${EffectType.entries.size} GPU EFFECTS",
                title = "Real OpenGL effects",
                description = "Blur, glitch, VHS, glow, sharpen, grain and more — stacked live on the GPU, with colour grading, HSL, curves and .cube 3D LUTs.",
                accent = ZeroViolet
            ),
            OnboardingPage(
                icon = Icons.Default.Speed,
                badge = "SPEED, MOTION & AUDIO",
                title = "Keyframes and beat sync",
                description = "Velocity ramps, freeze frames, keyframed motion, voiceover, generated SFX and beat-synced cuts — all mixed by a real DSP chain.",
                accent = ZeroAmber
            ),
            OnboardingPage(
                icon = Icons.Default.HighQuality,
                badge = "HARDWARE EXPORT",
                title = "Up to 4K H.264 / HEVC",
                description = "MediaCodec export with custom bitrate, audio mastering, gallery save and the same GPU pipeline as the preview.",
                accent = ZeroBlue
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
                    listOf(ZeroBlack, ZeroBackground, ZeroBlack)
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
                            .background(if (idx == pageIndex) p.accent else ZeroStroke)
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
                containerColor = ZeroAccent,
                contentColor = ZeroBlack
            )
        ) {
            Text(
                text = if (pageIndex < pages.lastIndex) "Continue" else "Enter ZERO",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

package com.vibecut.pro.ui.editor

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.media.export.ExportProgressState
import com.vibecut.pro.ui.theme.*
import java.util.Locale

@Composable
fun ExportStudioSheet(
    project: Project,
    isHevcSupported: Boolean,
    exportState: ExportProgressState,
    onUpdateConfig: (ExportConfig) -> Unit,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onResetState: () -> Unit,
    onBuildShareIntent: (String, Boolean) -> Intent?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val cfg = project.exportConfig
    val estimatedMb = cfg.estimatedFileSizeMb(project.totalDurationMs, project.aspectRatio)
    val bitrateMbps = cfg.targetVideoBitrateBps(project.aspectRatio) / 1_000_000f

    ModalBottomSheet(
        onDismissRequest = {
            if (exportState !is ExportProgressState.Running) {
                onResetState()
                onDismiss()
            }
        },
        containerColor = PanelDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Professional Export Engine", style = MaterialTheme.typography.titleLarge)

            when (exportState) {
                is ExportProgressState.Running -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { exportState.percent / 100f },
                            modifier = Modifier.size(84.dp),
                            color = ElectricCyan,
                            strokeWidth = 6.dp
                        )
                        Text("${exportState.percent}%", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        Text(exportState.statusLabel, fontSize = 12.sp, color = TextSecondary)
                        OutlinedButton(onClick = onCancelExport) {
                            Text("Cancel Export", color = CoralRed)
                        }
                    }
                }

                is ExportProgressState.Completed -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldBeat, modifier = Modifier.size(56.dp))
                        Text("Export Completed!", style = MaterialTheme.typography.titleLarge, color = EmeraldBeat)
                        val actualMb = String.format(Locale.US, "%.2f MB", exportState.fileSizeBytes / (1024f * 1024f))
                        Text(
                            text = "Saved (${actualMb}) ${if (exportState.galleryUri != null) "• Added to Device Gallery" else ""}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    onBuildShareIntent(exportState.outputFilePath, exportState.audioOnly)?.let { intent ->
                                        context.startActivity(Intent.createChooser(intent, "Share Video"))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share to Reels / Shorts / Social", fontWeight = FontWeight.Bold)
                            }
                        }

                        TextButton(onClick = {
                            onResetState()
                            onDismiss()
                        }) {
                            Text("Done")
                        }
                    }
                }

                else -> {
                    if (exportState is ExportProgressState.Error) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CoralRed)
                            Spacer(Modifier.width(8.dp))
                            Text(exportState.message, color = CoralRed, fontSize = 12.sp)
                        }
                    }

                    // Audio-Only Master Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Audio-Only Export (.M4A AAC)", fontWeight = FontWeight.SemiBold)
                            Text("Exports mixed audio tracks without video stream", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = cfg.audioOnly,
                            onCheckedChange = { onUpdateConfig(cfg.copy(audioOnly = it)) }
                        )
                    }

                    if (!cfg.audioOnly) {
                        // Resolution (480p, 720p, 1080p, 2K, 4K)
                        Text("Resolution", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ExportResolution.entries.forEach { res ->
                                FilterChip(
                                    selected = cfg.resolution == res,
                                    onClick = { onUpdateConfig(cfg.copy(resolution = res)) },
                                    label = { Text(res.label) }
                                )
                            }
                        }

                        // Frame Rate (24, 25, 30, 50, 60 FPS)
                        Text("Frame Rate", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ExportFrameRate.entries.forEach { fr ->
                                FilterChip(
                                    selected = cfg.frameRate == fr,
                                    onClick = { onUpdateConfig(cfg.copy(frameRate = fr)) },
                                    label = { Text("${fr.fps} FPS") }
                                )
                            }
                        }

                        // Bitrate Controls (Low, Medium, High, Custom Mbps)
                        Text(
                            "Bitrate (${String.format(Locale.US, "%.1f Mbps", bitrateMbps)})",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BitrateTier.entries.forEach { tier ->
                                FilterChip(
                                    selected = cfg.bitrateTier == tier,
                                    onClick = { onUpdateConfig(cfg.copy(bitrateTier = tier)) },
                                    label = { Text(tier.label) }
                                )
                            }
                        }

                        if (cfg.bitrateTier == BitrateTier.CUSTOM) {
                            Slider(
                                value = cfg.customBitrateMbps,
                                onValueChange = { mbps -> onUpdateConfig(cfg.copy(customBitrateMbps = mbps)) },
                                valueRange = 2f..80f
                            )
                        }

                        // Video Codec (H.264 / H.265 HEVC)
                        Text("Hardware Video Codec", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = cfg.codec == VideoCodecOption.H264,
                                onClick = { onUpdateConfig(cfg.copy(codec = VideoCodecOption.H264)) },
                                label = { Text("H.264 (AVC)") }
                            )
                            FilterChip(
                                selected = cfg.codec == VideoCodecOption.H265,
                                enabled = isHevcSupported,
                                onClick = { onUpdateConfig(cfg.copy(codec = VideoCodecOption.H265)) },
                                label = {
                                    Text(if (isHevcSupported) "H.265 (HEVC)" else "H.265 (Unsupported)")
                                }
                            )
                        }
                    }

                    // Save to Gallery + Background Export Toggles
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Save Automatically to Gallery", fontSize = 13.sp)
                        Switch(
                            checked = cfg.saveToGallery,
                            onCheckedChange = { onUpdateConfig(cfg.copy(saveToGallery = it)) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Background Export (WorkManager)", fontSize = 13.sp)
                        Switch(
                            checked = cfg.runInBackground,
                            onCheckedChange = { onUpdateConfig(cfg.copy(runInBackground = it)) }
                        )
                    }

                    val (outW, outH) = project.aspectRatio.computeDimensions(cfg.resolution.shortSidePx)
                    Text(
                        text = if (cfg.audioOnly) {
                            String.format(Locale.US, "AAC 192kbps • Est. Size: %.1f MB", estimatedMb)
                        } else {
                            String.format(Locale.US, "Output: %dx%d MP4 • Est. Size: %.1f MB", outW, outH, estimatedMb)
                        },
                        fontSize = 12.sp,
                        color = ElectricCyan
                    )

                    Button(
                        onClick = onStartExport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF001F24)
                        )
                    ) {
                        Text(
                            text = if (cfg.audioOnly) "Export Master Audio (.M4A)" else "Render & Export MP4",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

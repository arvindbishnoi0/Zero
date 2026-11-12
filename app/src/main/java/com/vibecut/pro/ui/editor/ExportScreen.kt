package com.vibecut.pro.ui.editor

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.media.export.ExportProgressState
import com.vibecut.pro.ui.theme.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportStudioSheet(
    project: Project,
    isHevcSupported: Boolean,
    exportState: ExportProgressState,
    onUpdateConfig: (ExportConfig) -> Unit,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onResetState: () -> Unit,
    onBuildShareIntent: (String, Boolean, Boolean) -> Intent?,
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
            Text("Export Studio", style = MaterialTheme.typography.titleLarge)

            when (exportState) {
                is ExportProgressState.Running -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 22.dp),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldBeat,
                            modifier = Modifier.size(56.dp)
                        )
                        val titleLabel = when {
                            exportState.isPhotoFrame -> "Photo Saved!"
                            exportState.audioOnly -> "Audio Master Saved!"
                            else -> "Video Export Completed!"
                        }
                        Text(titleLabel, style = MaterialTheme.typography.titleLarge, color = EmeraldBeat)
                        val actualMb = String.format(Locale.US, "%.2f MB", exportState.fileSizeBytes / (1024f * 1024f))
                        Text(
                            text = "Size: $actualMb ${if (exportState.galleryUri != null) "• Saved to Device Gallery" else ""}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )

                        Button(
                            onClick = {
                                onBuildShareIntent(
                                    exportState.outputFilePath,
                                    exportState.audioOnly,
                                    exportState.isPhotoFrame
                                )?.let { intent ->
                                    context.startActivity(Intent.createChooser(intent, "Share Export"))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share Export", fontWeight = FontWeight.Bold)
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
                        Surface(
                            color = CoralRed.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CoralRed)
                                Spacer(Modifier.width(8.dp))
                                Text(exportState.message, color = CoralRed, fontSize = 12.sp)
                            }
                        }
                    }

                    // 3 Clean Export Format Mode Cards (Video MP4 / Photo JPG / Audio M4A)
                    Text("Export Format", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExportFormatCard(
                            icon = Icons.Default.Movie,
                            title = "Video",
                            subtitle = ".MP4",
                            selected = !cfg.audioOnly && !cfg.exportAsPhotoFrame,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onUpdateConfig(cfg.copy(audioOnly = false, exportAsPhotoFrame = false))
                            }
                        )
                        ExportFormatCard(
                            icon = Icons.Default.Image,
                            title = "Photo",
                            subtitle = ".JPG Frame",
                            selected = cfg.exportAsPhotoFrame,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onUpdateConfig(cfg.copy(audioOnly = false, exportAsPhotoFrame = true))
                            }
                        )
                        ExportFormatCard(
                            icon = Icons.Default.Audiotrack,
                            title = "Audio",
                            subtitle = ".M4A AAC",
                            selected = cfg.audioOnly,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onUpdateConfig(cfg.copy(audioOnly = true, exportAsPhotoFrame = false))
                            }
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
                    }

                    if (!cfg.audioOnly && !cfg.exportAsPhotoFrame) {
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

                        // Bitrate Controls
                        Text(
                            "Quality & Bitrate (${String.format(Locale.US, "%.1f Mbps", bitrateMbps)})",
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

                        // Hardware Video Codec
                        Text("Video Codec", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = cfg.codec == VideoCodecOption.H264,
                                onClick = { onUpdateConfig(cfg.copy(codec = VideoCodecOption.H264)) },
                                label = { Text("H.264 (Universal)") }
                            )
                            FilterChip(
                                selected = cfg.codec == VideoCodecOption.H265,
                                enabled = isHevcSupported,
                                onClick = { onUpdateConfig(cfg.copy(codec = VideoCodecOption.H265)) },
                                label = {
                                    Text(if (isHevcSupported) "H.265 (HEVC)" else "H.265 (N/A)")
                                }
                            )
                        }
                    }

                    // Save to Gallery Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Save Automatically to Device Gallery", fontSize = 13.sp)
                        Switch(
                            checked = cfg.saveToGallery,
                            onCheckedChange = { onUpdateConfig(cfg.copy(saveToGallery = it)) }
                        )
                    }

                    val (outW, outH) = project.aspectRatio.computeCodecSafeDimensions(cfg.resolution.shortSidePx)
                    Text(
                        text = when {
                            cfg.audioOnly -> String.format(Locale.US, "AAC 192kbps Audio • Est. Size: %.1f MB", estimatedMb)
                            cfg.exportAsPhotoFrame -> "Graded Still Frame (${outW}×${outH} JPEG) at current playhead"
                            else -> String.format(Locale.US, "Output: %d×%d MP4 • Est. Size: %.1f MB", outW, outH, estimatedMb)
                        },
                        fontSize = 12.sp,
                        color = ElectricCyan
                    )

                    Button(
                        onClick = onStartExport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF001F24)
                        )
                    ) {
                        val ctaText = when {
                            cfg.exportAsPhotoFrame -> "SAVE GRADED PHOTO (.JPG)"
                            cfg.audioOnly -> "EXPORT AUDIO MASTER (.M4A)"
                            else -> "EXPORT ${cfg.resolution.badge} VIDEO (.MP4)"
                        }
                        Text(ctaText, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ExportFormatCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) ElectricCyan.copy(alpha = 0.18f) else SurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) ElectricCyan else SurfaceStroke),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                icon,
                contentDescription = title,
                tint = if (selected) ElectricCyan else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) ElectricCyan else TextPrimary
            )
            Text(subtitle, fontSize = 10.sp, color = TextSecondary)
        }
    }
}

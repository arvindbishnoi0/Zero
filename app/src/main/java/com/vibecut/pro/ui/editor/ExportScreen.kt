package com.vibecut.pro.ui.editor

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.media.export.ExportProgressState
import com.vibecut.pro.ui.theme.*
import java.util.Locale

/**
 * Export sheet: resolution, frame rate, codec, bitrate, audio and the real output estimate.
 *
 * The pipeline is the same one used in the editor — Media3 Transformer with the ZERO GPU effect
 * factory — so effects, colour, transitions, overlays, text and audio DSP are all baked into the
 * file. Nothing here is a mock progress bar: the percentage comes from Transformer.getProgress().
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    project: Project,
    isHevcSupported: Boolean,
    exportState: ExportProgressState,
    encoders: List<String>,
    onConfigChange: (ExportConfig) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onShare: (String, Boolean, Boolean) -> Intent?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val cfg = project.exportConfig
    val estimatedMb = cfg.estimatedFileSizeMb(project.totalDurationMs, project.aspectRatio, project.customAspectRatio)
    val bitrateMbps = cfg.targetVideoBitrateBps(project.aspectRatio, project.customAspectRatio) / 1_000_000f
    var codecUnavailable by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = {
            if (exportState !is ExportProgressState.Running) {
                onReset()
                onDismiss()
            }
        },
        containerColor = ZeroBackground,
        contentColor = TextPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = ZeroStroke) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SheetHeader(
                title = "Export",
                subtitle = if (project.totalDurationMs > 0) {
                    "${project.aspectRatio.badge} • ${formatDurationShort(project.totalDurationMs)}"
                } else "Add clips before exporting",
                onClose = { if (exportState !is ExportProgressState.Running) onDismiss() }
            )

            when (exportState) {
                is ExportProgressState.Running -> RunningExport(exportState, onCancel)

                is ExportProgressState.Completed -> CompletedExport(
                    state = exportState,
                    onShare = {
                        onShare(exportState.outputFilePath, exportState.audioOnly, exportState.isPhotoFrame)
                            ?.let { intent ->
                                context.startActivity(Intent.createChooser(intent, "Share export"))
                            }
                    },
                    onDone = {
                        onReset()
                        onDismiss()
                    }
                )

                is ExportProgressState.Error -> ErrorExport(exportState.message, onReset)

                ExportProgressState.Cancelled -> ErrorExport("Export cancelled.", onReset)

                ExportProgressState.Idle -> {
                    StudioCard(title = "Resolution") {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ExportResolution.entries.forEach { res ->
                                StudioChip(res.badge, cfg.resolution == res) {
                                    onConfigChange(cfg.copy(resolution = res))
                                }
                            }
                        }
                        val dims = project.aspectRatio.computeDimensionsFor(
                            cfg.resolution.shortSidePx, project.customAspectRatio
                        )
                        Text(
                            "Output: ${dims.first}x${dims.second} (codec-aligned)",
                            fontSize = 10.sp,
                            color = TextTertiary
                        )
                        if (cfg.resolution == ExportResolution.UHD_4K) {
                            val source = project.mainVideoClips.firstOrNull()
                            val srcH = source?.sourceHeight ?: 0
                            Text(
                                if (srcH in 1 until 2160) {
                                    "Heads up: your source is ${srcH}p. A 4K file gives you 4K container/bitrate, " +
                                        "but the detail is upscaled — enable the 4K enhancer for the best result."
                                } else {
                                    "Source is already ${srcH}p or higher — 4K export preserves native detail."
                                },
                                fontSize = 10.sp,
                                color = ZeroAmber
                            )
                        }
                    }

                    StudioCard(title = "Frame rate") {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ExportFrameRate.entries.forEach { fps ->
                                StudioChip(fps.label, cfg.frameRate == fps) {
                                    onConfigChange(cfg.copy(frameRate = fps))
                                }
                            }
                        }
                    }

                    StudioCard(title = "Bitrate", badge = String.format(Locale.US, "%.1f Mbps", bitrateMbps)) {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BitrateTier.entries.forEach { tier ->
                                StudioChip(tier.label, cfg.bitrateTier == tier) {
                                    onConfigChange(cfg.copy(bitrateTier = tier))
                                }
                            }
                        }
                        if (cfg.bitrateTier == BitrateTier.CUSTOM) {
                            StudioSlider(
                                label = "Custom bitrate",
                                value = cfg.customBitrateMbps,
                                valueRange = 1f..80f,
                                defaultValue = 16f,
                                valueText = String.format(Locale.US, "%.0f Mbps", cfg.customBitrateMbps)
                            ) { onConfigChange(cfg.copy(customBitrateMbps = it)) }
                        }
                    }

                    StudioCard(title = "Codec") {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StudioChip("H.264 (compatible)", cfg.codec == VideoCodecOption.H264) {
                                onConfigChange(cfg.copy(codec = VideoCodecOption.H264))
                            }
                            StudioChip(
                                if (isHevcSupported) "H.265 / HEVC" else "H.265 (unsupported)",
                                cfg.codec == VideoCodecOption.H265
                            ) {
                                if (!isHevcSupported) codecUnavailable = true
                                else onConfigChange(cfg.copy(codec = VideoCodecOption.H265))
                            }
                        }
                        if (codecUnavailable) {
                            Text(
                                "This device has no H.265 hardware encoder; H.264 will be used.",
                                fontSize = 10.sp,
                                color = ZeroCoral
                            )
                        }
                        if (encoders.isNotEmpty()) {
                            Text(
                                "Encoders: ${encoders.take(2).joinToString()}",
                                fontSize = 9.sp,
                                color = TextTertiary,
                                maxLines = 2
                            )
                        }
                    }

                    StudioCard(title = "Audio") {
                        StudioToggleRow("Include audio track", !cfg.audioOnly) { include ->
                            onConfigChange(cfg.copy(audioOnly = !include))
                        }
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(128, 192, 256, 320).forEach { kbps ->
                                StudioChip("$kbps kbps", cfg.audioBitrateKbps == kbps) {
                                    onConfigChange(cfg.copy(audioBitrateKbps = kbps))
                                }
                            }
                        }
                        StudioToggleRow("Save to gallery", cfg.saveToGallery) { save ->
                            onConfigChange(cfg.copy(saveToGallery = save))
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ZeroSurface)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Estimated file size", fontSize = 11.sp, color = TextSecondary)
                        Text(
                            String.format(Locale.US, "%.1f MB", estimatedMb),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZeroAccent
                        )
                    }

                    StudioPrimaryButton(
                        text = if (cfg.audioOnly) "Export audio (.m4a)" else "Export video",
                        icon = Icons.Default.Movie,
                        enabled = project.totalDurationMs > 0,
                        onClick = onStart
                    )

                    StudioPrimaryButton(
                        text = "Save current frame as photo",
                        icon = Icons.Default.PhotoCamera,
                        container = ZeroSurfaceHigh,
                        contentColor = TextPrimary,
                        enabled = project.mainVideoClips.isNotEmpty(),
                        onClick = {
                            onConfigChange(cfg.copy(exportAsPhotoFrame = true))
                            onStart()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RunningExport(state: ExportProgressState.Running, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            progress = { state.percent / 100f },
            modifier = Modifier.size(74.dp),
            color = ZeroAccent,
            trackColor = ZeroStroke,
            strokeWidth = 5.dp
        )
        Text("${state.percent}%", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(state.statusLabel, fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center)
        Text(
            "Rendering with the same GPU pipeline as the preview.",
            fontSize = 10.sp,
            color = TextTertiary,
            textAlign = TextAlign.Center
        )
        OutlinedButton(onClick = onCancel) { Text("Cancel export", color = ZeroCoral) }
    }
}

@Composable
private fun CompletedExport(state: ExportProgressState.Completed, onShare: () -> Unit, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ZeroAccent, modifier = Modifier.size(52.dp))
        Text(
            when {
                state.isPhotoFrame -> "Photo saved"
                state.audioOnly -> "Audio exported"
                else -> "Video exported"
            },
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )
        Text(
            String.format(Locale.US, "%.2f MB", state.fileSizeBytes / (1024f * 1024f)) +
                if (state.galleryUri != null) " • saved to gallery" else " • saved in app storage",
            fontSize = 11.sp,
            color = TextSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onShare,
                colors = ButtonDefaults.buttonColors(containerColor = ZeroAccent, contentColor = ZeroBlack)
            ) { Text("Share") }
            OutlinedButton(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun ErrorExport(message: String, onReset: () -> Unit) {
    StudioCard(title = "Export failed") {
        Text(message, fontSize = 11.sp, color = ZeroCoral)
        StudioPrimaryButton(
            text = "Try again",
            container = ZeroSurfaceHigh,
            contentColor = TextPrimary,
            onClick = onReset
        )
    }
}

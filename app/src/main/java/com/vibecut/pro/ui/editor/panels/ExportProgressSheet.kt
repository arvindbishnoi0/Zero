package com.vibecut.pro.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecut.pro.engine.model.Project
import com.vibecut.pro.media.export.ExportProgressState
import com.vibecut.pro.ui.theme.*
import com.vibecut.pro.ui.viewmodel.EditorViewModel
import java.io.File
import java.util.Locale

/**
 * Export progress / result surface. It is driven entirely by the real [ExportProgressState] that the
 * Media3 Transformer pipeline publishes, so the percentage, the output path and the share buttons
 * only exist once a file has actually been written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportProgressSheet(
    project: Project,
    exportState: ExportProgressState,
    isHevcSupported: Boolean,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: EditorViewModel
) {
    val context = LocalContext.current
    val cfg = project.exportConfig
    ModalBottomSheet(
        onDismissRequest = {
            if (exportState !is ExportProgressState.Running) onDismiss()
        },
        containerColor = ZeroBackground,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ZeroStroke)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (exportState) {
                is ExportProgressState.Running -> RunningBody(
                    label = exportState.statusLabel,
                    percent = exportState.percent,
                    onCancel = onCancel
                )

                is ExportProgressState.Completed -> CompletedBody(
                    state = exportState,
                    onShare = {
                        viewModel.buildShareExportIntent(
                            exportState.outputFilePath,
                            exportState.audioOnly,
                            exportState.isPhotoFrame
                        )?.let { intent ->
                            context.startActivity(
                                android.content.Intent.createChooser(intent, "Share export")
                            )
                        }
                    },
                    onOpen = {
                        val file = File(exportState.outputFilePath)
                        if (file.exists()) {
                            val intent = viewModel.buildShareExportIntent(
                                exportState.outputFilePath,
                                exportState.audioOnly,
                                exportState.isPhotoFrame
                            )
                            if (intent != null) context.startActivity(intent)
                        }
                    },
                    onDone = onDismiss,
                    savedToGallery = exportState.galleryUri != null
                )

                ExportProgressState.Cancelled -> MessageBody(
                    icon = Icons.Default.Cancel,
                    tint = ZeroAmber,
                    title = "Export cancelled",
                    body = "Nothing was written to your gallery. Your edit is untouched.",
                    primaryLabel = "Back to editor",
                    onPrimary = onDismiss
                )

                is ExportProgressState.Error -> MessageBody(
                    icon = Icons.Default.ErrorOutline,
                    tint = ZeroCoral,
                    title = "Export failed",
                    body = exportState.message,
                    primaryLabel = "Close",
                    onPrimary = onDismiss
                )

                ExportProgressState.Idle -> {
                    Text(
                        "Preparing export…",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                    StudioPrimaryButton(text = "Close", onClick = onDismiss)
                }
            }

            Text(
                buildString {
                    append(project.title)
                    append(" · ")
                    append(cfg.resolution.badge)
                    append(" ")
                    append(cfg.frameRate.fps)
                    append("fps · ")
                    append(cfg.codec.label)
                    if (cfg.bitrateTier == com.vibecut.pro.engine.model.BitrateTier.CUSTOM) {
                        append(String.format(Locale.US, " · %.0f Mbps", cfg.customBitrateMbps))
                    }
                    append(if (isHevcSupported) "" else " · HEVC unavailable on this device")
                },
                fontSize = 9.5.sp,
                color = TextTertiary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RunningBody(label: String, percent: Int, onCancel: () -> Unit) {
    Spacer(Modifier.height(6.dp))
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(ZeroAccent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { (percent / 100f).coerceIn(0f, 1f) },
            color = ZeroAccent,
            trackColor = ZeroStroke,
            strokeWidth = 5.dp,
            modifier = Modifier.size(52.dp)
        )
        Text("$percent%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ZeroAccent)
    }
    Text("Rendering your video", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
    Text(
        label.ifBlank { "Encoding with hardware MediaCodec…" },
        fontSize = 11.sp,
        color = TextSecondary,
        textAlign = TextAlign.Center
    )
    Text(
        "Rendering on the GPU pipeline: effects, colour, overlays, transitions and audio are all applied.",
        fontSize = 9.5.sp,
        color = TextTertiary,
        textAlign = TextAlign.Center
    )
    StudioPrimaryButton(
        text = "Cancel export",
        icon = Icons.Default.Close,
        container = ZeroSurfaceHigh,
        contentColor = TextPrimary,
        onClick = onCancel
    )
}

@Composable
private fun CompletedBody(
    state: ExportProgressState.Completed,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    savedToGallery: Boolean
) {
    Spacer(Modifier.height(6.dp))
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(ZeroAccent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ZeroAccent, modifier = Modifier.size(38.dp))
    }
    Text(
        when {
            state.isPhotoFrame -> "Photo frame saved"
            state.audioOnly -> "Audio master exported"
            else -> "Export complete"
        },
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = TextPrimary
    )
    Text(
        buildString {
            append(String.format(Locale.US, "%.1f MB", state.fileSizeBytes / (1024f * 1024f)))
            append(" · ")
            append(formatDurationShort(state.durationMs))
            append(if (savedToGallery) " · in your gallery" else " · saved to app storage")
        },
        fontSize = 11.sp,
        color = TextSecondary
    )
    Text(
        state.outputFilePath.substringAfterLast('/'),
        fontSize = 9.5.sp,
        color = TextTertiary,
        textAlign = TextAlign.Center
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StudioPrimaryButton(
            text = "Share",
            icon = Icons.Default.Share,
            container = ZeroSurfaceHigh,
            contentColor = TextPrimary,
            onClick = onShare,
            modifier = Modifier.weight(1f)
        )
        StudioPrimaryButton(
            text = "Open",
            icon = Icons.Default.PlayCircle,
            container = ZeroSurfaceHigh,
            contentColor = TextPrimary,
            onClick = onOpen,
            modifier = Modifier.weight(1f)
        )
    }
    StudioPrimaryButton(text = "Done", icon = Icons.Default.Check, onClick = onDone)
}

@Composable
private fun MessageBody(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit
) {
    Spacer(Modifier.height(6.dp))
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(44.dp))
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
    Text(body, fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center)
    StudioPrimaryButton(text = primaryLabel, onClick = onPrimary)
}

/** Small helper so an empty state can offer the export sheet directly. */
@Composable
fun QuickExportRow(project: Project, onOpenExport: () -> Unit, onGrabPhoto: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ZeroSurface)
            .clickable(onClick = onOpenExport)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.Movie, contentDescription = null, tint = ZeroAccent, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Export ${project.exportConfig.resolution.badge} · ${project.exportConfig.frameRate.fps}fps",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                project.exportConfig.codec.label + " · " + project.exportConfig.bitrateTier.label + " bitrate",
                fontSize = 9.5.sp,
                color = TextTertiary
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(ZeroSurfaceHigh)
                .clickable(onClick = onGrabPhoto)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text("Photo", fontSize = 9.5.sp, color = TextSecondary)
        }
    }
}

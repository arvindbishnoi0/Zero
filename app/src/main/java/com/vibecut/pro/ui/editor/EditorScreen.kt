package com.vibecut.pro.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.ui.editor.components.PreviewOverlays
import com.vibecut.pro.ui.editor.components.UniversalMediaImporterSheet
import com.vibecut.pro.ui.editor.panels.*
import com.vibecut.pro.ui.editor.timeline.ProTimeline
import com.vibecut.pro.ui.editor.timeline.TimelineViewportState
import com.vibecut.pro.ui.theme.*
import com.vibecut.pro.ui.viewmodel.EditorSheet
import com.vibecut.pro.ui.viewmodel.EditorViewModel
import kotlinx.coroutines.delay

/**
 * ZERO editor workspace.
 *
 * Layout: header → large aspect-correct preview → transport → timeline → tool dock. Every tool opens
 * its own bottom sheet (one sheet per job), so nothing is hidden behind scrolling inspector pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectId: String,
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playheadMs by viewModel.playheadMs.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val heldStill by viewModel.heldStillClip.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val viewport = remember { TimelineViewportState() }
    val project = state.project

    var showRenameDialog by remember { mutableStateOf(false) }
    var showFpsMenu by remember { mutableStateOf(false) }

    LaunchedEffect(projectId) { viewModel.loadProject(projectId) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.toastMessage) {
        val msg = state.toastMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearToast()
    }

    BackHandler(enabled = state.openSheet != EditorSheet.NONE) { viewModel.closeSheet() }

    Scaffold(
        containerColor = ZeroBlack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            EditorTopBar(
                title = project.title,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                isCompare = state.isCompareOriginal,
                onBack = onNavigateBack,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onCompare = viewModel::toggleCompareOriginal,
                onRename = { showRenameDialog = true },
                onExport = { viewModel.openSheet(EditorSheet.EXPORT) },
                onProjectSettings = { viewModel.openSheet(EditorSheet.PROJECT) }
            )
        },
        bottomBar = {
            EditorDock(
                hasSelection = state.selectedClip != null,
                hasClips = project.mainVideoClips.isNotEmpty(),
                onImport = { viewModel.setShowMediaImporterSheet(true) },
                onClip = { viewModel.openSheet(EditorSheet.CLIP) },
                onFilters = { viewModel.openSheet(EditorSheet.FILTERS) },
                onAdjust = { viewModel.openSheet(EditorSheet.ADJUST) },
                onEffects = { viewModel.openSheet(EditorSheet.EFFECTS) },
                onText = { viewModel.openSheet(EditorSheet.TEXT) },
                onAudio = { viewModel.openSheet(EditorSheet.AUDIO) },
                onMore = { viewModel.openSheet(EditorSheet.TRANSFORM) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(ZeroBackground)
        ) {
            // ---------------- preview stage: takes every pixel the timeline does not need
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(ZeroBlack),
                contentAlignment = Alignment.Center
            ) {
                PreviewStage(
                    project = project,
                    playheadMs = playheadMs,
                    viewModel = viewModel,
                    heldStill = heldStill,
                    isCompare = state.isCompareOriginal,
                    selectedClipId = state.selectedClipId
                )
            }

            // ---------------- transport
            TransportBar(
                project = project,
                playheadMs = playheadMs,
                isPlaying = isPlaying,
                hasClips = project.mainVideoClips.isNotEmpty(),
                onTogglePlay = viewModel::togglePlayPause,
                onStep = viewModel::stepFrames,
                onSeek = viewModel::seekTo,
                fpsMenuOpen = showFpsMenu,
                onToggleFpsMenu = { showFpsMenu = !showFpsMenu },
                onSetFps = { fps ->
                    viewModel.updateExportConfig(project.exportConfig.copy(frameRate = fps))
                    showFpsMenu = false
                }
            )

            // ---------------- timeline
            if (project.mainVideoClips.isEmpty() && project.tracks.all { it.clips.isEmpty() }) {
                com.vibecut.pro.ui.editor.timeline.TimelineEmptyHint(
                    onImport = { viewModel.setShowMediaImporterSheet(true) }
                )
                Spacer(Modifier.height(10.dp))
            } else {
                ProTimeline(
                    project = project,
                    playheadMs = playheadMs,
                    selectedClipId = state.selectedClipId,
                    isPlaying = isPlaying,
                    viewport = viewport,
                    onSeek = viewModel::scrubbingTo,
                    onScrubStart = viewModel::beginScrub,
                    onScrubEnd = viewModel::endScrub,
                    onSelectClip = viewModel::selectClip,
                    onDoubleTapClip = {
                        viewModel.selectClip(it)
                        viewModel.openSheet(EditorSheet.CLIP)
                    },
                    onMoveClip = viewModel::moveClip,
                    onTrimClip = { id, s, e -> viewModel.trimClip(id, s, e) },
                    onSplitAtPlayhead = viewModel::splitSelectedAtPlayhead,
                    onToggleTrackLock = viewModel::toggleTrackLock,
                    onToggleTrackHidden = viewModel::toggleTrackHidden,
                    onToggleTrackMute = viewModel::toggleTrackMute,
                    onAddToTrack = { type -> viewModel.setShowMediaImporterSheet(true, type) },
                    onOpenClipTools = { viewModel.openSheet(EditorSheet.CLIP) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(timelineHeight())
                )
            }
        }
    }

    // ------------------------------------------------------------------ sheets

    EditorToolSheets(
        state = state,
        playheadMs = playheadMs,
        viewModel = viewModel
    )

    // Real export surface: appears as soon as the transformer reports progress and stays until the
    // user dismisses the finished/failed result.
    if (exportState !is com.vibecut.pro.media.export.ExportProgressState.Idle) {
        com.vibecut.pro.ui.editor.panels.ExportProgressSheet(
            project = project,
            exportState = exportState,
            isHevcSupported = state.isHevcSupported,
            onCancel = viewModel::cancelExport,
            onDismiss = viewModel::resetExportStatus,
            viewModel = viewModel
        )
    }

    if (state.showMediaImporterSheet) {
        UniversalMediaImporterSheet(
            deviceMedia = state.deviceMediaLibrary,
            importProgress = importProgress,
            targetTrackType = state.mediaImportTargetTrack,
            onImportUris = { uris, track -> viewModel.importMediaUris(uris, track) },
            onAddBackdrop = { title, c1, c2 -> viewModel.addStudioCanvasBackdrop(title, c1, c2) },
            onGenerateSamples = viewModel::generateAndImportSampleClips,
            onRefreshLibrary = viewModel::refreshDeviceMediaLibrary,
            onDismiss = { viewModel.setShowMediaImporterSheet(false) }
        )
    }

    if (showRenameDialog) {
        RenameDialog(
            current = project.title,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newTitle ->
                viewModel.renameProject(newTitle)
                showRenameDialog = false
            }
        )
    }
}

/** Timeline gets 27% of the screen (min 168dp, max 260dp) so the preview stays dominant. */
@Composable
private fun timelineHeight(): androidx.compose.ui.unit.Dp {
    val screen = LocalConfiguration.current.screenHeightDp.dp
    return (screen * 0.27f).coerceIn(168.dp, 268.dp)
}

// ---------------------------------------------------------------------------- top bar

@Composable
private fun EditorTopBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    isCompare: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCompare: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onProjectSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZeroSurface)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.ArrowBack,
            contentDescription = "Back",
            tint = TextPrimary,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(7.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRename)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("Tap to rename", fontSize = 9.sp, color = TextTertiary)
        }
        Icon(
            Icons.AutoMirrored.Filled.Undo,
            contentDescription = "Undo",
            tint = if (canUndo) TextPrimary else TextTertiary,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(enabled = canUndo, onClick = onUndo)
                .padding(6.dp)
        )
        Icon(
            Icons.AutoMirrored.Filled.Redo,
            contentDescription = "Redo",
            tint = if (canRedo) TextPrimary else TextTertiary,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(enabled = canRedo, onClick = onRedo)
                .padding(6.dp)
        )
        Icon(
            Icons.Default.Compare,
            contentDescription = "Before / after",
            tint = if (isCompare) ZeroAmber else TextSecondary,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (isCompare) ZeroAmber.copy(alpha = 0.15f) else Color.Transparent)
                .clickable(onClick = onCompare)
                .padding(6.dp)
        )
        Icon(
            Icons.Default.Tune,
            contentDescription = "Project settings",
            tint = TextSecondary,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onProjectSettings)
                .padding(6.dp)
        )
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ZeroAccent)
                .clickable(onClick = onExport)
                .padding(horizontal = 13.dp, vertical = 7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.IosShare, contentDescription = null, tint = ZeroBlack, modifier = Modifier.size(14.dp))
                Text("Export", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ZeroBlack)
            }
        }
    }
}

// ---------------------------------------------------------------------------- preview

@Composable
private fun PreviewStage(
    project: Project,
    playheadMs: Long,
    viewModel: EditorViewModel,
    heldStill: TimelineClip?,
    isCompare: Boolean,
    selectedClipId: String?
) {
    val hasClips = project.mainVideoClips.isNotEmpty()
    val aspect = if (project.aspectRatio == AspectRatioPreset.CUSTOM) {
        project.customAspectRatio
    } else {
        project.aspectRatio.ratioFloat
    }.coerceIn(0.3f, 3.5f)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .aspectRatio(aspect, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(project.backgroundColorArgb))
                .border(
                    1.dp,
                    if (isCompare) ZeroAmber else ZeroStroke,
                    RoundedCornerShape(12.dp)
                )
        ) {
            if (hasClips) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            player = viewModel.previewController.videoPlayer
                        }
                    },
                    update = { view ->
                        val player = viewModel.previewController.videoPlayer
                        if (view.player !== player) view.player = player
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Held still frames (photos / freeze frames) are drawn here because a 1 ms video item
            // cannot carry a picture through the decoder pipeline.
            heldStill?.let { still ->
                val thumb = still.thumbnailUri ?: still.sourceUri
                AsyncImage(
                    model = thumb,
                    contentDescription = still.sourceName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Active overlay/text/sticker layers are drawn live for immediate feedback.
            PreviewOverlays(
                project = project,
                playheadMs = playheadMs,
                selectedClipId = selectedClipId,
                onDrag = { dx, dy -> viewModel.dragSelectedOverlayOnCanvas(dx, dy) }
            )

            if (isCompare) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ZeroAmber)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text("ORIGINAL", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ZeroBlack)
                }
            }

            if (!hasClips) {
                EmptyPreviewState(onImport = { viewModel.setShowMediaImporterSheet(true) })
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                project.aspectRatio.label + if (project.aspectRatio == AspectRatioPreset.CUSTOM)
                    " ${"%.2f".format(project.customAspectRatio)}" else "",
                fontSize = 9.sp,
                color = TextTertiary
            )
            Text(
                "${project.previewQuality.label} preview · ${project.exportConfig.frameRate.fps} fps",
                fontSize = 9.sp,
                color = TextTertiary
            )
            if (project.enhancement.enabled) {
                Text(
                    "Enhance ${project.enhancement.targetHeight}p",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZeroAccent
                )
            }
        }
    }
}

@Composable
private fun EmptyPreviewState(onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioGradient)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.MovieCreation, contentDescription = null, tint = ZeroAccent, modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(10.dp))
        Text("Start your edit", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(
            "Import video, photos or audio from your device",
            fontSize = 11.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ZeroAccent)
                .clickable(onClick = onImport)
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, tint = ZeroBlack, modifier = Modifier.size(16.dp))
                Text("Add media", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ZeroBlack)
            }
        }
    }
}

// ---------------------------------------------------------------------------- transport

@Composable
private fun TransportBar(
    project: Project,
    playheadMs: Long,
    isPlaying: Boolean,
    hasClips: Boolean,
    onTogglePlay: () -> Unit,
    onStep: (Int) -> Unit,
    onSeek: (Long) -> Unit,
    fpsMenuOpen: Boolean,
    onToggleFpsMenu: () -> Unit,
    onSetFps: (ExportFrameRate) -> Unit
) {
    val totalMs = project.totalDurationMs.coerceAtLeast(1L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZeroSurface)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            formatTimecode(playheadMs, project.exportConfig.frameRate.fps),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = ZeroAccent,
            modifier = Modifier.width(64.dp)
        )

        // Scrub bar doubles as a fast seek surface.
        Slider(
            value = (playheadMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f),
            onValueChange = { frac -> onSeek((frac * totalMs).toLong()) },
            colors = SliderDefaults.colors(
                thumbColor = ZeroAccent,
                activeTrackColor = ZeroAccent,
                inactiveTrackColor = ZeroStroke
            ),
            modifier = Modifier.weight(1f).height(22.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(
                Icons.Default.KeyboardDoubleArrowLeft,
                contentDescription = "Previous frame",
                tint = TextSecondary,
                modifier = Modifier.size(22.dp).clickable { onStep(-1) }
            )
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (hasClips) ZeroAccent else ZeroSurfaceHigh)
                    .clickable(enabled = hasClips, onClick = onTogglePlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (hasClips) ZeroBlack else TextTertiary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Icon(
                Icons.Default.KeyboardDoubleArrowRight,
                contentDescription = "Next frame",
                tint = TextSecondary,
                modifier = Modifier.size(22.dp).clickable { onStep(1) }
            )
        }

        Box {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ZeroSurfaceHigh)
                    .clickable(onClick = onToggleFpsMenu)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text("${project.exportConfig.frameRate.fps}fps", fontSize = 10.sp, color = TextSecondary)
            }
            DropdownMenu(
                expanded = fpsMenuOpen,
                onDismissRequest = onToggleFpsMenu,
                containerColor = ZeroSurfaceHigh
            ) {
                ExportFrameRate.entries.forEach { fps ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                fps.label,
                                color = if (project.exportConfig.frameRate == fps) ZeroAccent else TextPrimary,
                                fontSize = 12.sp
                            )
                        },
                        onClick = { onSetFps(fps) }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- dock

@Composable
private fun EditorDock(
    hasSelection: Boolean,
    hasClips: Boolean,
    onImport: () -> Unit,
    onClip: () -> Unit,
    onFilters: () -> Unit,
    onAdjust: () -> Unit,
    onEffects: () -> Unit,
    onText: () -> Unit,
    onAudio: () -> Unit,
    onMore: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZeroSurface)
            .navigationBarsPadding()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DockItem(Icons.Default.AddPhotoAlternate, "Media", true, ZeroAccent, onImport)
            DockItem(Icons.Default.ContentCut, "Clip", hasSelection, TextPrimary, onClip)
            DockItem(Icons.Default.FilterVintage, "Filters", hasClips, ZeroAmber, onFilters)
            DockItem(Icons.Default.Tune, "Adjust", hasClips, ZeroAccent, onAdjust)
            DockItem(Icons.Default.AutoAwesome, "Effects", hasClips, ZeroViolet, onEffects)
            DockItem(Icons.Default.TextFields, "Text", true, ZeroBlue, onText)
            DockItem(Icons.Default.GraphicEq, "Audio", true, ZeroAccent, onAudio)
            DockItem(Icons.Default.MoreHoriz, "More", hasSelection, TextPrimary, onMore)
        }
    }
}

@Composable
private fun DockItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(62.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (enabled) accent.copy(alpha = 0.14f) else ZeroSurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) accent else TextTertiary,
                modifier = Modifier.size(17.dp)
            )
        }
        Text(
            label,
            fontSize = 9.5.sp,
            color = if (enabled) TextSecondary else TextTertiary,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------- dialogs

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZeroSurface,
        title = { Text("Rename project", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ZeroAccent,
                    unfocusedBorderColor = ZeroStroke,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("Save", color = ZeroAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

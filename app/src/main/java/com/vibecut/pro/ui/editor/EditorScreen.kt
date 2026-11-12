package com.vibecut.pro.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.media.export.ExportProgressState
import com.vibecut.pro.ui.editor.components.UniversalMediaImporterSheet
import com.vibecut.pro.ui.editor.panels.*
import com.vibecut.pro.ui.editor.timeline.*
import com.vibecut.pro.ui.theme.*
import com.vibecut.pro.ui.viewmodel.EditorUiState
import com.vibecut.pro.ui.viewmodel.EditorViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ZERO editor workspace.
 *
 * Layout contract (portrait first, responsive in landscape):
 *   1. Compact top bar: back, project title/aspect, undo/redo, compare, export.
 *   2. Preview canvas — as large as the screen allows, aspect-correct, never cropped.
 *   3. Transport row: frame step, play/pause, scrub-time readout, add-media.
 *   4. Multi-track timeline (drag playhead, pinch zoom, trim handles, tap to seek).
 *   5. Tool rail — every tool is one tap away and opens its own sheet.
 */
@Composable
fun EditorScreen(
    projectId: String,
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val playheadMs by viewModel.playheadMs.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val viewport = remember { TimelineViewportState() }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    var showExportSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    LaunchedEffect(projectId) { viewModel.loadProject(projectId) }

    LaunchedEffect(ui.toastMessage) {
        val msg = ui.toastMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearToast()
    }

    Scaffold(
        containerColor = ZeroBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = ZeroSurfaceHigh,
                contentColor = TextPrimary,
                shape = RoundedCornerShape(12.dp)
            )
        } }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(ZeroBackground)
        ) {
            EditorTopBar(
                title = ui.project.title,
                aspectLabel = ui.project.aspectRatio.badge,
                canUndo = ui.canUndo,
                canRedo = ui.canRedo,
                isCompare = ui.isCompareOriginal,
                onBack = onNavigateBack,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onCompare = viewModel::toggleCompareOriginal,
                onExport = { showExportSheet = true },
                onProject = { viewModel.openSheet(EditorSheet.PROJECT) }
            )

            if (!isLandscape) {
                PreviewCanvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.42f),
                    viewModel = viewModel,
                    project = ui.project,
                    isPlaying = isPlaying,
                    isCompare = ui.isCompareOriginal
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth().weight(0.6f)) {
                    PreviewCanvas(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        viewModel = viewModel,
                        project = ui.project,
                        isPlaying = isPlaying,
                        isCompare = ui.isCompareOriginal
                    )
                }
            }

            TransportBar(
                playheadMs = playheadMs,
                totalMs = ui.project.totalDurationMs,
                fps = ui.project.exportConfig.frameRate.fps,
                isPlaying = isPlaying,
                onStepBack = { viewModel.stepFrames(-1) },
                onPlayPause = viewModel::togglePlayPause,
                onStepForward = { viewModel.stepFrames(1) },
                onAddMedia = { viewModel.openSheet(EditorSheet.MEDIA, TrackType.VIDEO_MAIN) },
                onVoiceover = {
                    viewModel.selectClip(ui.selectedClipId)
                    viewModel.openSheet(EditorSheet.AUDIO)
                }
            )

            if (ui.project.allClips.isEmpty()) {
                TimelineEmptyHint { viewModel.openSheet(EditorSheet.MEDIA, TrackType.VIDEO_MAIN) }
            } else {
                ProTimeline(
                    project = ui.project,
                    playheadMs = playheadMs,
                    selectedClipId = ui.selectedClipId,
                    isPlaying = isPlaying,
                    viewport = viewport,
                    onSeek = { ms -> if (viewport.isScrubbing) viewModel.scrubTo(ms) else viewModel.seekTo(ms) },
                    onScrubStart = { viewport.scrubbingMs = playheadMs },
                    onScrubEnd = {
                        viewport.scrubbingMs = null
                        viewModel.endScrub(playheadMs)
                    },
                    onSelectClip = viewModel::selectClip,
                    onDoubleTapClip = { clipId ->
                        viewModel.selectClip(clipId)
                        val track = ui.project.findTrackForClip(clipId)
                        val clip = ui.project.findClip(clipId)
                        when {
                            track?.type == TrackType.TEXT -> viewModel.openSheet(EditorSheet.TEXT)
                            track?.type?.isAudioTrack == true -> viewModel.openSheet(EditorSheet.AUDIO)
                            clip?.mediaKind == MediaKind.IMAGE || track?.type == TrackType.VIDEO_MAIN ->
                                viewModel.openSheet(EditorSheet.CLIP)
                            else -> viewModel.openSheet(EditorSheet.TRANSFORM)
                        }
                    },
                    onMoveClip = viewModel::moveClipById,
                    onTrimClip = viewModel::trimClipById,
                    onSplitAtPlayhead = viewModel::splitSelectedAtPlayhead,
                    onToggleTrackLock = viewModel::toggleTrackLock,
                    onToggleTrackHidden = viewModel::toggleTrackHidden,
                    onToggleTrackMute = viewModel::toggleTrackMute,
                    onAddToTrack = { type -> viewModel.openSheet(EditorSheet.MEDIA, type) },
                    onOpenClipTools = { viewModel.openSheet(EditorSheet.CLIP) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.31f)
                )
            }

            ToolRail(
                hasSelection = ui.selectedClip != null,
                selectedTrack = ui.selectedTrack?.type,
                canRedo = ui.canRedo,
                onTool = { sheet -> viewModel.openSheet(sheet) },
                onUndo = viewModel::undo,
                onRedo = viewModel::redo
            )
        }
    }

    // ------------------------------------------------------------------ sheets

    if (ui.openSheet != EditorSheet.NONE) {
        EditorToolSheet(
            sheet = ui.openSheet,
            ui = ui,
            playheadMs = playheadMs,
            viewModel = viewModel,
            importProgress = importProgress,
            onDismiss = viewModel::closeSheet,
            onRename = { showRenameDialog = true }
        )
    }

    if (showExportSheet) {
        ExportSheet(
            project = ui.project,
            isHevcSupported = ui.isHevcSupported,
            exportState = exportState,
            encoders = remember { viewModel.deviceVideoEncoders() },
            onConfigChange = viewModel::updateExportConfig,
            onStart = viewModel::startExport,
            onCancel = viewModel::cancelExport,
            onReset = viewModel::resetExportStatus,
            onShare = { path, audioOnly, isPhoto ->
                viewModel.buildShareExportIntent(path, audioOnly, isPhoto)
            },
            onDismiss = { showExportSheet = false }
        )
    }

    if (showRenameDialog) {
        var draft by remember { mutableStateOf(ui.project.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Project name") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameProject(draft)
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ---------------------------------------------------------------------------- top bar

@Composable
private fun EditorTopBar(
    title: String,
    aspectLabel: String,
    canUndo: Boolean,
    canRedo: Boolean,
    isCompare: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCompare: () -> Unit,
    onExport: () -> Unit,
    onProject: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZeroSurface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Default.ArrowBack,
            contentDescription = "Back",
            tint = TextPrimary,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(4.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onProject)
        ) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("$aspectLabel • tap to change canvas", fontSize = 9.sp, color = TextTertiary)
        }
        Icon(
            Icons.Default.Undo,
            contentDescription = "Undo",
            tint = if (canUndo) TextPrimary else TextTertiary.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp).clickable(enabled = canUndo, onClick = onUndo)
        )
        Icon(
            Icons.Default.Redo,
            contentDescription = "Redo",
            tint = if (canRedo) TextPrimary else TextTertiary.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp).clickable(enabled = canRedo, onClick = onRedo)
        )
        Icon(
            Icons.Default.Compare,
            contentDescription = "Before / after",
            tint = if (isCompare) ZeroAmber else TextSecondary,
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isCompare) ZeroAmber.copy(alpha = 0.16f) else Color.Transparent)
                .clickable(onClick = onCompare)
                .padding(5.dp)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ZeroAccent)
                .clickable(onClick = onExport)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.FileUpload, contentDescription = null, tint = ZeroBlack, modifier = Modifier.size(13.dp))
                Text("Export", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ZeroBlack)
            }
        }
    }
}

// ---------------------------------------------------------------------------- preview

@Composable
private fun PreviewCanvas(
    modifier: Modifier,
    viewModel: EditorViewModel,
    project: Project,
    isPlaying: Boolean,
    isCompare: Boolean
) {
    val aspect = project.effectiveAspectRatio.coerceIn(0.35f, 2.8f)
    var showControls by remember { mutableStateOf(true) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(ZeroBlack)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { viewModel.togglePlayPause() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            // Fit the project canvas into the available space without ever cropping it.
            val availableW = maxWidth
            val availableH = maxHeight
            val targetW: androidx.compose.ui.unit.Dp
            val targetH: androidx.compose.ui.unit.Dp
            if (availableW / availableH > aspect) {
                targetH = availableH
                targetW = availableH * aspect
            } else {
                targetW = availableW
                targetH = availableW / aspect
            }

            Box(
                modifier = Modifier
                    .size(width = targetW, height = targetH)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
                    .border(1.dp, ZeroStroke, RoundedCornerShape(10.dp))
            ) {
                if (project.mainVideoClips.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(StudioGradient)
                            .clickable { viewModel.openSheet(EditorSheet.MEDIA, TrackType.VIDEO_MAIN) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = ZeroAccent, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Add video, photos or audio", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Tap to open the media picker", fontSize = 10.sp, color = TextTertiary)
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                player = viewModel.previewController.videoPlayer
                            }
                        },
                        update = { view ->
                            if (view.player !== viewModel.previewController.videoPlayer) {
                                view.player = viewModel.previewController.videoPlayer
                            }
                            view.setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                    )
                    // Text / sticker / overlay status chips (informational, real state from the model)
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isCompare) Badge("ORIGINAL", ZeroAmber)
                        if (project.enhancement.enabled) Badge("ENHANCED", ZeroViolet)
                    }
                }
            }
        }

        // Center transport overlay
        AnimatedVisibility(visible = showControls, modifier = Modifier.align(Alignment.Center)) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Replay10,
                    contentDescription = "Back 10 frames",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp).clickable { viewModel.stepFrames(-10) }
                )
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp).clickable { viewModel.togglePlayPause() }
                )
                Icon(
                    Icons.Default.Forward10,
                    contentDescription = "Forward 10 frames",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp).clickable { viewModel.stepFrames(10) }
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

// ---------------------------------------------------------------------------- transport

@Composable
private fun TransportBar(
    playheadMs: Long,
    totalMs: Long,
    fps: Int,
    isPlaying: Boolean,
    onStepBack: () -> Unit,
    onPlayPause: () -> Unit,
    onStepForward: () -> Unit,
    onAddMedia: () -> Unit,
    onVoiceover: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZeroSurface)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            formatTimecode(playheadMs, fps),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = ZeroAccent
        )
        Text("/ ${formatDurationShort(totalMs)}", fontSize = 9.sp, color = TextTertiary)

        Spacer(Modifier.weight(1f))

        Icon(
            Icons.Default.SkipPrevious,
            contentDescription = "Previous frame",
            tint = TextSecondary,
            modifier = Modifier.size(20.dp).clickable(onClick = onStepBack)
        )
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(ZeroAccent)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = ZeroBlack,
                modifier = Modifier.size(19.dp)
            )
        }
        Icon(
            Icons.Default.SkipNext,
            contentDescription = "Next frame",
            tint = TextSecondary,
            modifier = Modifier.size(20.dp).clickable(onClick = onStepForward)
        )

        Spacer(Modifier.weight(1f))

        Icon(
            Icons.Default.Mic,
            contentDescription = "Voiceover",
            tint = TextSecondary,
            modifier = Modifier.size(19.dp).clickable(onClick = onVoiceover)
        )
        Icon(
            Icons.Default.Add,
            contentDescription = "Add media",
            tint = ZeroAccent,
            modifier = Modifier.size(20.dp).clickable(onClick = onAddMedia)
        )
    }
}

// ---------------------------------------------------------------------------- tool rail

private data class ToolEntry(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val sheet: EditorSheet
)

private val EDIT_TOOLS = listOf(
    ToolEntry("Clip", Icons.Default.ContentCut, EditorSheet.CLIP),
    ToolEntry("Speed", Icons.Default.Speed, EditorSheet.SPEED),
    ToolEntry("Transform", Icons.Default.Transform, EditorSheet.TRANSFORM),
    ToolEntry("Effects", Icons.Default.AutoAwesome, EditorSheet.EFFECTS),
    ToolEntry("Filters", Icons.Default.FilterVintage, EditorSheet.FILTERS),
    ToolEntry("Adjust", Icons.Default.Tune, EditorSheet.ADJUST),
    ToolEntry("Detail", Icons.Default.Grain, EditorSheet.DETAIL),
    ToolEntry("Text", Icons.Default.Title, EditorSheet.TEXT),
    ToolEntry("Audio", Icons.Default.GraphicEq, EditorSheet.AUDIO),
    ToolEntry("Trans", Icons.Default.SwapHoriz, EditorSheet.TRANSITIONS),
    ToolEntry("Motion", Icons.Default.Timeline, EditorSheet.KEYFRAMES),
    ToolEntry("4K", Icons.Default.HighQuality, EditorSheet.ENHANCE)
)

@Composable
private fun ToolRail(
    hasSelection: Boolean,
    selectedTrack: TrackType?,
    canRedo: Boolean,
    onTool: (EditorSheet) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZeroSurface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EDIT_TOOLS.forEach { tool ->
            val needsClip = tool.sheet != EditorSheet.ENHANCE
            val enabled = !needsClip || hasSelection
            ToolButton(
                label = tool.label,
                icon = tool.icon,
                enabled = enabled,
                highlight = when (tool.sheet) {
                    EditorSheet.TEXT -> selectedTrack == TrackType.TEXT
                    EditorSheet.AUDIO -> selectedTrack?.isAudioTrack == true
                    else -> false
                },
                onClick = { onTool(tool.sheet) }
            )
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    highlight: Boolean,
    onClick: () -> Unit
) {
    val tint by animateFloatAsState(if (enabled) 1f else 0.35f, label = "toolTint")
    Column(
        modifier = Modifier
            .width(54.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (highlight) ZeroAccent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = (if (highlight) ZeroAccent else TextPrimary).copy(alpha = tint),
            modifier = Modifier.size(19.dp)
        )
        Text(
            label,
            fontSize = 8.5.sp,
            color = (if (highlight) ZeroAccent else TextSecondary).copy(alpha = tint),
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------- tool sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorToolSheet(
    sheet: EditorSheet,
    ui: EditorUiState,
    playheadMs: Long,
    viewModel: EditorViewModel,
    importProgress: com.vibecut.pro.media.loader.MediaImportProgress,
    onDismiss: () -> Unit,
    onRename: () -> Unit
) {
    val clip = ui.selectedClip
    val visualClip = clip ?: ui.project.findMainClipAt(playheadMs)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ZeroBackground,
        contentColor = TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = ZeroStroke) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val title = when (sheet) {
                EditorSheet.MEDIA -> "Add media"
                EditorSheet.CLIP -> "Clip"
                EditorSheet.TRANSFORM -> "Transform & crop"
                EditorSheet.SPEED -> "Speed & time"
                EditorSheet.FILTERS -> "Filters & LUTs"
                EditorSheet.ADJUST -> "Color adjust"
                EditorSheet.DETAIL -> "Detail & optics"
                EditorSheet.EFFECTS -> "Effects"
                EditorSheet.TRANSITIONS -> "Transitions"
                EditorSheet.TEXT -> "Text & captions"
                EditorSheet.AUDIO -> "Audio"
                EditorSheet.KEYFRAMES -> "Motion keyframes"
                EditorSheet.ENHANCE -> "4K enhancer"
                EditorSheet.PROJECT -> "Project settings"
                EditorSheet.EXPORT -> "Export"
                EditorSheet.NONE -> ""
            }
            SheetHeader(
                title = title,
                subtitle = clip?.sourceName ?: ui.project.title,
                onClose = onDismiss
            )

            when (sheet) {
                EditorSheet.MEDIA -> UniversalMediaImporterSheet(
                    deviceMedia = ui.deviceMediaLibrary,
                    importProgress = importProgress,
                    targetTrackType = ui.mediaTargetTrack,
                    isLibraryLoading = ui.isLibraryLoading,
                    onImportUris = viewModel::importMediaUris,
                    onAddBackdrop = viewModel::addStudioCanvasBackdrop,
                    onGenerateSamples = viewModel::generateAndImportSampleClips,
                    onRefreshLibrary = viewModel::refreshDeviceMediaLibrary,
                    onDismiss = onDismiss
                )

                EditorSheet.CLIP -> {
                    if (clip == null) {
                        Text("Select a clip on the timeline first.", fontSize = 12.sp, color = TextTertiary)
                    } else {
                        ClipSheetContent(clip = clip, project = ui.project, viewModel = viewModel)
                    }
                }

                EditorSheet.TRANSFORM -> if (visualClip == null) {
                    Text("Add a clip first.", fontSize = 12.sp, color = TextTertiary)
                } else TransformSheetContent(visualClip, viewModel)

                EditorSheet.SPEED -> if (clip == null) {
                    Text("Select a clip first.", fontSize = 12.sp, color = TextTertiary)
                } else SpeedSheetContent(clip, ui.project, viewModel)

                EditorSheet.FILTERS -> if (visualClip == null) {
                    Text("Add a clip first.", fontSize = 12.sp, color = TextTertiary)
                } else FiltersSheetContent(visualClip, ui.project, viewModel)

                EditorSheet.ADJUST -> if (visualClip == null) {
                    Text("Add a clip first.", fontSize = 12.sp, color = TextTertiary)
                } else AdjustSheetContent(visualClip, viewModel)

                EditorSheet.DETAIL -> if (visualClip == null) {
                    Text("Add a clip first.", fontSize = 12.sp, color = TextTertiary)
                } else DetailSheetContent(visualClip, viewModel)

                EditorSheet.EFFECTS -> if (visualClip == null) {
                    Text("Add a clip first.", fontSize = 12.sp, color = TextTertiary)
                } else EffectsSheetContent(visualClip, viewModel)

                EditorSheet.TRANSITIONS -> if (visualClip == null) {
                    Text("Add a clip first.", fontSize = 12.sp, color = TextTertiary)
                } else TransitionsSheetContent(visualClip, viewModel)

                EditorSheet.TEXT -> if (clip?.textSpec == null) {
                    TextSheetAddPrompt(onAdd = { viewModel.addTextClip() })
                } else TextSheetContent(clip, viewModel)

                EditorSheet.AUDIO -> if (clip == null) {
                    Text("Select a clip first.", fontSize = 12.sp, color = TextTertiary)
                } else AudioSheetContent(clip, viewModel)

                EditorSheet.KEYFRAMES -> if (clip == null) {
                    Text("Select a clip first.", fontSize = 12.sp, color = TextTertiary)
                } else KeyframesSheetContent(clip, playheadMs, viewModel)

                EditorSheet.ENHANCE -> EnhanceSheetContent(ui.project, viewModel)

                EditorSheet.PROJECT -> ProjectSheetContent(ui.project, viewModel, onRename)

                else -> Unit
            }
        }
    }
}

@Composable
private fun TextSheetAddPrompt(onAdd: () -> Unit) {
    StudioCard(title = "No text selected") {
        Text(
            "Add a text layer at the playhead, then style it here.",
            fontSize = 11.sp,
            color = TextTertiary
        )
        StudioPrimaryButton(text = "Add text", icon = Icons.Default.Title, onClick = onAdd)
    }
}

// ---------------------------------------------------------------------------- clip sheet

@Composable
private fun ClipSheetContent(
    clip: TimelineClip,
    project: Project,
    viewModel: EditorViewModel
) {
    val track = project.findTrackForClip(clip.id)
    StudioCard(title = "Clip", badge = track?.type?.label ?: "") {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StudioAction(Icons.Default.ContentCut, "Split", ZeroAccent) { viewModel.splitSelectedAtPlayhead() }
            StudioAction(Icons.Default.ContentCopy, "Duplicate") { viewModel.duplicateSelectedClip() }
            StudioAction(Icons.Default.Delete, "Delete", ZeroCoral) { viewModel.deleteSelectedClip() }
            StudioAction(Icons.Default.AcUnit, "Freeze") { viewModel.insertFreezeFrame() }
            StudioAction(Icons.Default.Rotate90DegreesCw, "Rotate") { viewModel.rotateSelected90(true) }
            StudioAction(Icons.Default.Flip, "Flip H") { viewModel.flipSelectedHorizontal() }
            StudioAction(Icons.Default.SwapVert, "Flip V") { viewModel.flipSelectedVertical() }
            StudioAction(
                Icons.Default.Replay,
                if (clip.isReversed) "Reversed" else "Reverse",
                if (clip.isReversed) ZeroAmber else TextPrimary
            ) { viewModel.toggleReverseSelected() }
            StudioAction(Icons.Default.CallSplit, "Cut mid") { viewModel.cutMiddleTwentyPercentOfSelected() }
            StudioAction(Icons.Default.KeyboardArrowLeft, "Move ◀") { viewModel.moveSelectedClipOrder(-1) }
            StudioAction(Icons.Default.KeyboardArrowRight, "Move ▶") { viewModel.moveSelectedClipOrder(1) }
            StudioAction(Icons.Default.MergeType, "Merge") { viewModel.mergeAdjacentOnCurrentTrack() }
        }
        Text(
            "Start ${formatDurationShort(clip.startTimeOnTrackMs)} • length ${formatDurationShort(clip.timelineDurationMs)} • " +
                "source ${formatDurationShort(clip.sourceDurationMs)}",
            fontSize = 10.sp,
            color = TextTertiary
        )
    }

    if (track?.type?.isAudioTrack == true || clip.mediaKind == MediaKind.AUDIO) {
        AudioSheetContent(clip, viewModel)
        return
    }

    StudioCard(title = "Quick actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioChip("Effects") { viewModel.openSheet(EditorSheet.EFFECTS) }
            StudioChip("Filters") { viewModel.openSheet(EditorSheet.FILTERS) }
            StudioChip("Adjust") { viewModel.openSheet(EditorSheet.ADJUST) }
            StudioChip("Speed") { viewModel.openSheet(EditorSheet.SPEED) }
            StudioChip("Transition") { viewModel.openSheet(EditorSheet.TRANSITIONS) }
        }
        StudioChipRow {
            StudioChip("Apply effects to all") { viewModel.applyEffectsToAllClips() }
            StudioChip("Apply look to all") { viewModel.applyColorGradeToAllClips() }
        }
    }
}

package com.vibecut.pro.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecut.pro.engine.model.AspectRatioPreset
import com.vibecut.pro.engine.model.TrackType
import com.vibecut.pro.engine.templates.TemplateCategory
import com.vibecut.pro.ui.editor.components.CompactTimelineStrip
import com.vibecut.pro.ui.editor.components.MultiTrackTimeline
import com.vibecut.pro.ui.editor.components.PreviewViewport
import com.vibecut.pro.ui.editor.components.UniversalMediaImporterSheet
import com.vibecut.pro.ui.editor.panels.*
import com.vibecut.pro.ui.theme.*
import com.vibecut.pro.ui.viewmodel.EditorInspectorTab
import com.vibecut.pro.ui.viewmodel.EditorViewModel
import kotlin.math.abs

@Composable
fun EditorScreen(
    projectId: String,
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit
) {
    LaunchedEffect(projectId) {
        viewModel.loadProject(projectId)
    }

    val uiState by viewModel.uiState.collectAsState()
    val playheadMs by viewModel.playheadMs.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()

    var pendingImportTrackType by remember { mutableStateOf(TrackType.VIDEO_MAIN) }
    var showExportModal by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSaveTemplateDialog by remember { mutableStateOf(false) }
    var showAspectMenu by remember { mutableStateOf(false) }
    var forceFullTimelineInStudio by remember { mutableStateOf(false) }

    val activeVisualClip = remember(uiState.project, uiState.selectedClipId, playheadMs) {
        uiState.activeVisualClip(playheadMs)
    }

    // Automatically give customization tabs spacious vertical room unless user expanded full tracks
    val isCustomizationFocusedTab = uiState.activeTab in setOf(
        EditorInspectorTab.FILTERS,
        EditorInspectorTab.DETAIL,
        EditorInspectorTab.ADJUST,
        EditorInspectorTab.EFFECTS,
        EditorInspectorTab.AI
    )
    val showCompactTimeline = isCustomizationFocusedTab && !forceFullTimelineInStudio

    Scaffold(
        containerColor = ObsidianBg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelDark)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(
                        onClick = {
                            viewModel.saveImmediately("Exit editor")
                            onNavigateBack()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
                    }

                    Column(
                        modifier = Modifier
                            .clickable { showRenameDialog = true }
                            .padding(end = 6.dp)
                    ) {
                        Text(
                            text = uiState.project.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Autosaved • Rev #${uiState.project.revision}",
                            fontSize = 10.sp,
                            color = EmeraldBeat
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Aspect Ratio Selector
                    Box {
                        Surface(
                            color = SurfaceElevated,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, SurfaceStroke),
                            modifier = Modifier.clickable { showAspectMenu = true }
                        ) {
                            Text(
                                text = uiState.project.aspectRatio.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showAspectMenu,
                            onDismissRequest = { showAspectMenu = false },
                            containerColor = SurfaceElevated
                        ) {
                            AspectRatioPreset.entries.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text("${preset.label} — ${preset.subtitle}") },
                                    onClick = {
                                        showAspectMenu = false
                                        viewModel.setAspectRatio(preset)
                                    }
                                )
                            }
                        }
                    }

                    IconButton(onClick = { showSaveTemplateDialog = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.BookmarkAdd,
                            contentDescription = "Save as Template",
                            tint = KeyframeGold,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Button(
                        onClick = { showExportModal = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF001F24)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                }
            }
        },
        bottomBar = {
            // Redesigned 10-Tab Pro Studio Tool Dock with Active Edit Indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelDark)
                    .navigationBarsPadding()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val g = activeVisualClip?.colorGrade
                EditorInspectorTab.entries.forEach { tab ->
                    val selected = uiState.activeTab == tab
                    val icon = when (tab) {
                        EditorInspectorTab.EDIT -> Icons.Default.ContentCut
                        EditorInspectorTab.FILTERS -> Icons.Default.FilterVintage
                        EditorInspectorTab.DETAIL -> Icons.Default.Deblur
                        EditorInspectorTab.ADJUST -> Icons.Default.Tune
                        EditorInspectorTab.EFFECTS -> Icons.Default.AutoFixHigh
                        EditorInspectorTab.TRANSITIONS -> Icons.Default.SwapHoriz
                        EditorInspectorTab.AUDIO -> Icons.Default.GraphicEq
                        EditorInspectorTab.TEXT -> Icons.Default.Title
                        EditorInspectorTab.KEYFRAMES -> Icons.Default.Diamond
                        EditorInspectorTab.AI -> Icons.Default.AutoAwesome
                    }
                    val hasActiveBadge = when (tab) {
                        EditorInspectorTab.FILTERS -> g?.lutPresetId != null || !g?.customCubeLutUri.isNullOrBlank()
                        EditorInspectorTab.DETAIL -> g != null && (g.hasSpatialDetailChanges || abs(g.dehaze) > 0.01f)
                        EditorInspectorTab.ADJUST -> g != null && (
                            abs(g.exposure) > 0.01f || abs(g.contrast) > 0.01f ||
                                abs(g.saturation) > 0.01f || abs(g.vibrance) > 0.01f ||
                                abs(g.temperature) > 0.01f || !g.curves.isIdentity || !g.colorWheels.isIdentity
                            )
                        EditorInspectorTab.EFFECTS -> activeVisualClip?.effects?.any { it.enabled } == true
                        EditorInspectorTab.TRANSITIONS -> activeVisualClip?.transitionIn != null
                        EditorInspectorTab.KEYFRAMES -> uiState.selectedClip?.keyframes?.isNotEmpty() == true
                        else -> false
                    }

                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) ElectricCyan.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { viewModel.selectTab(tab) }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box {
                            Icon(
                                icon,
                                contentDescription = tab.label,
                                tint = if (selected) ElectricCyan else TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            if (hasActiveBadge) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 3.dp, y = (-2).dp)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(KeyframeGold)
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tab.label,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (selected) ElectricCyan else TextSecondary
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Live GPU OpenGL Preview Stage
                PreviewViewport(
                    exoPlayer = viewModel.previewController.videoPlayer,
                    project = uiState.project,
                    playheadMs = playheadMs,
                    isPlaying = isPlaying,
                    selectedClip = uiState.selectedClip,
                    isCompareOriginal = uiState.isCompareOriginal,
                    canUndo = uiState.canUndo,
                    canRedo = uiState.canRedo,
                    onTogglePlay = viewModel::togglePlayPause,
                    onStepFrames = viewModel::stepFrames,
                    onToggleCompare = viewModel::toggleCompareOriginal,
                    onQuickSplit = viewModel::splitSelectedAtPlayhead,
                    onQuickSnapshot = viewModel::quickCaptureGradedPhotoFrame,
                    onUndo = viewModel::undo,
                    onRedo = viewModel::redo,
                    onOpenMediaHub = {
                        pendingImportTrackType = TrackType.VIDEO_MAIN
                        viewModel.setShowMediaImporterSheet(true)
                    },
                    onGenerateDemoClick = viewModel::generateAndImportSampleClips,
                    onDragSelectedOverlay = { dxNdc, dyNdc ->
                        val c = uiState.selectedClip ?: return@PreviewViewport
                        val tf = c.transform
                        viewModel.updateSelectedTransform(
                            tf.copy(
                                positionX = (tf.positionX + dxNdc).coerceIn(-1f, 1f),
                                positionY = (tf.positionY + dyNdc).coerceIn(-1f, 1f)
                            )
                        )
                    },
                    modifier = Modifier.weight(if (showCompactTimeline) 0.43f else 0.36f)
                )

                // 2. Adaptive Timeline (Compact Strip in Customization Studio OR Full Multi-Track Timeline)
                if (showCompactTimeline) {
                    CompactTimelineStrip(
                        project = uiState.project,
                        playheadMs = playheadMs,
                        selectedClipId = activeVisualClip?.id ?: uiState.selectedClip?.id,
                        onSelectClip = viewModel::selectClip,
                        onSeekMs = viewModel::seekTo,
                        onExpandTimeline = { forceFullTimelineInStudio = true },
                        onOpenMediaHub = {
                            pendingImportTrackType = TrackType.VIDEO_MAIN
                            viewModel.setShowMediaImporterSheet(true)
                        }
                    )
                } else {
                    MultiTrackTimeline(
                        project = uiState.project,
                        playheadMs = playheadMs,
                        selectedClipId = uiState.selectedClip?.id,
                        onSelectClip = viewModel::selectClip,
                        onSeekMs = viewModel::seekTo,
                        onZoomChange = viewModel::setTimelineZoom,
                        onToggleTrackLock = viewModel::toggleTrackLock,
                        onToggleTrackHidden = viewModel::toggleTrackHidden,
                        onToggleTrackMute = viewModel::toggleTrackMute,
                        onOpenTransitionInspector = { clipId ->
                            viewModel.selectClip(clipId)
                            viewModel.selectTab(EditorInspectorTab.TRANSITIONS)
                        },
                        onImportClipToTrack = { trackType ->
                            when (trackType) {
                                TrackType.TEXT -> viewModel.addTextClip()
                                TrackType.STICKER -> viewModel.selectTab(EditorInspectorTab.TEXT)
                                else -> {
                                    pendingImportTrackType = trackType
                                    viewModel.setShowMediaImporterSheet(true)
                                }
                            }
                        },
                        onCollapseTimeline = if (isCustomizationFocusedTab) {
                            { forceFullTimelineInStudio = false }
                        } else null,
                        modifier = Modifier.weight(0.28f)
                    )
                }

                HorizontalDivider(color = SurfaceStroke)

                // 3. Spacious Customization & Studio Inspector Panel
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(if (showCompactTimeline) 0.57f else 0.36f)
                        .background(ObsidianBg)
                ) {
                    when (uiState.activeTab) {
                        EditorInspectorTab.EDIT -> ClipEditPanel(uiState.selectedClip, uiState.project, viewModel)
                        EditorInspectorTab.FILTERS -> FilterStudioPanel(activeVisualClip, uiState.project, viewModel)
                        EditorInspectorTab.DETAIL -> DetailAndSharpenPanel(activeVisualClip, uiState.project, viewModel)
                        EditorInspectorTab.ADJUST -> ColorGradingPanel(activeVisualClip, uiState.project, viewModel)
                        EditorInspectorTab.EFFECTS -> EffectsStudioPanel(activeVisualClip, viewModel)
                        EditorInspectorTab.TRANSITIONS -> TransitionsPanel(activeVisualClip, viewModel)
                        EditorInspectorTab.AUDIO -> AudioStudioPanel(uiState, viewModel)
                        EditorInspectorTab.TEXT -> TextAndCaptionsPanel(uiState, viewModel)
                        EditorInspectorTab.KEYFRAMES -> KeyframesPanel(uiState.selectedClip ?: activeVisualClip, playheadMs, viewModel)
                        EditorInspectorTab.AI -> AiToolsStudioPanel(uiState, viewModel)
                    }
                }
            }

            // Toast notification pill
            uiState.toastMessage?.let { msg ->
                LaunchedEffect(msg) {
                    kotlinx.coroutines.delay(2400L)
                    viewModel.clearToast()
                }
                Surface(
                    color = SurfaceElevated,
                    shape = RoundedCornerShape(50),
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                ) {
                    Text(
                        text = msg,
                        color = ElectricCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    // Universal Media Supporter & Loader Sheet
    if (uiState.showMediaImporterSheet) {
        UniversalMediaImporterSheet(
            deviceMedia = uiState.deviceMediaLibrary,
            importProgress = importProgress,
            targetTrackType = pendingImportTrackType,
            onImportUris = { uris, destTrack ->
                viewModel.importMediaUris(uris, destTrack)
            },
            onAddBackdrop = { title, primary, secondary ->
                viewModel.addStudioCanvasBackdrop(title, primary, secondary)
            },
            onGenerateSamples = viewModel::generateAndImportSampleClips,
            onRefreshLibrary = viewModel::refreshDeviceMediaLibrary,
            onDismiss = { viewModel.setShowMediaImporterSheet(false) }
        )
    }

    if (showExportModal) {
        ExportStudioSheet(
            project = uiState.project,
            isHevcSupported = uiState.isHevcSupported,
            exportState = exportState,
            onUpdateConfig = viewModel::updateExportConfig,
            onStartExport = viewModel::startExport,
            onCancelExport = viewModel::cancelExport,
            onResetState = viewModel::resetExportStatus,
            onBuildShareIntent = viewModel::buildShareExportIntent,
            onDismiss = { showExportModal = false }
        )
    }

    if (showRenameDialog) {
        var draft by remember(uiState.project.title) { mutableStateOf(uiState.project.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = PanelDark,
            title = { Text("Rename Project") },
            text = {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.renameProject(draft)
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showSaveTemplateDialog) {
        var tplName by remember { mutableStateOf("${uiState.project.title} Template") }
        var category by remember { mutableStateOf(TemplateCategory.TRENDING) }
        AlertDialog(
            onDismissRequest = { showSaveTemplateDialog = false },
            containerColor = PanelDark,
            title = { Text("Save as Marketplace Template") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = tplName,
                        onValueChange = { tplName = it },
                        label = { Text("Template Name") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TemplateCategory.entries.forEach { cat ->
                            FilterChip(
                                selected = category == cat,
                                onClick = { category = cat },
                                label = { Text(cat.label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveCurrentProjectAsTemplate(tplName, category)
                    showSaveTemplateDialog = false
                }) { Text("Save Template") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveTemplateDialog = false }) { Text("Cancel") }
            }
        )
    }
}

package com.vibecut.pro.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.vibecut.pro.engine.model.KeyframeProperty
import com.vibecut.pro.engine.model.TrackType
import com.vibecut.pro.engine.templates.TemplateCategory
import com.vibecut.pro.ui.editor.components.MultiTrackTimeline
import com.vibecut.pro.ui.editor.components.PreviewViewport
import com.vibecut.pro.ui.editor.panels.*
import com.vibecut.pro.ui.theme.*
import com.vibecut.pro.ui.viewmodel.EditorInspectorTab
import com.vibecut.pro.ui.viewmodel.EditorViewModel

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

    var pendingImportTrackType by remember { mutableStateOf(TrackType.VIDEO_MAIN) }
    var showExportModal by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSaveTemplateDialog by remember { mutableStateOf(false) }
    var showAspectMenu by remember { mutableStateOf(false) }

    val visualMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importMediaUris(uris, pendingImportTrackType)
        }
    }

    val audioFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importMediaUris(listOf(uri), pendingImportTrackType)
        }
    }

    Scaffold(
        containerColor = ObsidianBg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelDark)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(onClick = {
                        viewModel.saveImmediately("Exit editor")
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                    // Aspect Ratio Selector Chip
                    Box {
                        AssistChip(
                            onClick = { showAspectMenu = true },
                            label = { Text(uiState.project.aspectRatio.label, fontSize = 11.sp) }
                        )
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

                    IconButton(onClick = { showSaveTemplateDialog = true }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = "Save as Template", tint = KeyframeGold, modifier = Modifier.size(19.dp))
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
                        Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                }
            }
        },
        bottomBar = {
            // 8-Tab Professional Studio Tool Dock
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelDark)
                    .navigationBarsPadding()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EditorInspectorTab.entries.forEach { tab ->
                    val selected = uiState.activeTab == tab
                    val icon = when (tab) {
                        EditorInspectorTab.EDIT -> Icons.Default.Tune
                        EditorInspectorTab.EFFECTS -> Icons.Default.AutoFixHigh
                        EditorInspectorTab.COLOR -> Icons.Default.Palette
                        EditorInspectorTab.TRANSITIONS -> Icons.Default.SwapHoriz
                        EditorInspectorTab.KEYFRAMES -> Icons.Default.Diamond
                        EditorInspectorTab.AUDIO -> Icons.Default.GraphicEq
                        EditorInspectorTab.TEXT -> Icons.Default.Title
                        EditorInspectorTab.AI -> Icons.Default.AutoAwesome
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) ElectricCyan.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable { viewModel.selectTab(tab) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            icon,
                            contentDescription = tab.label,
                            tint = if (selected) ElectricCyan else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = tab.label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
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
                // 1. Live OpenGL ES Video Preview + Interactive Overlay Gizmo (36% height)
                PreviewViewport(
                    exoPlayer = viewModel.previewController.videoPlayer,
                    project = uiState.project,
                    playheadMs = playheadMs,
                    isPlaying = isPlaying,
                    selectedClip = uiState.selectedClip,
                    canUndo = uiState.canUndo,
                    canRedo = uiState.canRedo,
                    onTogglePlay = viewModel::togglePlayPause,
                    onStepFrames = viewModel::stepFrames,
                    onQuickSplit = viewModel::splitSelectedAtPlayhead,
                    onQuickKeyframe = {
                        val c = uiState.selectedClip
                        if (c != null) {
                            viewModel.upsertKeyframeAtPlayhead(KeyframeProperty.SCALE, c.transform.scaleX)
                        }
                    },
                    onUndo = viewModel::undo,
                    onRedo = viewModel::redo,
                    onImportMediaClick = {
                        pendingImportTrackType = TrackType.VIDEO_MAIN
                        visualMediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoAndImage))
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
                    modifier = Modifier.weight(0.36f)
                )

                // 2. Multi-Layer VN-Style Magnetic Timeline (30% height)
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
                        pendingImportTrackType = trackType
                        when (trackType) {
                            TrackType.TEXT -> viewModel.addTextClip()
                            TrackType.STICKER -> viewModel.selectTab(EditorInspectorTab.TEXT)
                            TrackType.AUDIO_MUSIC, TrackType.AUDIO_VOICEOVER, TrackType.AUDIO_SFX ->
                                audioFilePicker.launch("audio/*")
                            else ->
                                visualMediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoAndImage))
                        }
                    },
                    modifier = Modifier.weight(0.30f)
                )

                HorizontalDivider(color = SurfaceStroke)

                // 3. Active Tool Studio Inspector Panel (34% height)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.34f)
                        .background(ObsidianBg)
                ) {
                    when (uiState.activeTab) {
                        EditorInspectorTab.EDIT -> ClipEditPanel(uiState.selectedClip, uiState.project, viewModel)
                        EditorInspectorTab.EFFECTS -> EffectsStudioPanel(uiState.selectedClip, viewModel)
                        EditorInspectorTab.COLOR -> ColorGradingPanel(uiState.selectedClip, uiState.project, viewModel)
                        EditorInspectorTab.TRANSITIONS -> TransitionsPanel(uiState.selectedClip, viewModel)
                        EditorInspectorTab.KEYFRAMES -> KeyframesPanel(uiState.selectedClip, playheadMs, viewModel)
                        EditorInspectorTab.AUDIO -> AudioStudioPanel(uiState, viewModel)
                        EditorInspectorTab.TEXT -> TextAndCaptionsPanel(uiState, viewModel)
                        EditorInspectorTab.AI -> AiToolsStudioPanel(uiState, viewModel)
                    }
                }
            }

            // Toast notification banner
            uiState.toastMessage?.let { msg ->
                LaunchedEffect(msg) {
                    kotlinx.coroutines.delay(2400L)
                    viewModel.clearToast()
                }
                Surface(
                    color = SurfaceElevated,
                    shape = RoundedCornerShape(50),
                    shadowElevation = 6.dp,
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

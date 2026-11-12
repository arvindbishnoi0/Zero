package com.vibecut.pro.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vibecut.pro.data.repository.ProjectSummary
import com.vibecut.pro.engine.model.AspectRatioPreset
import com.vibecut.pro.engine.model.ExportFrameRate
import com.vibecut.pro.engine.model.ExportResolution
import com.vibecut.pro.ui.theme.*
import com.vibecut.pro.ui.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HomeNavSection(val label: String) {
    STUDIO("Projects"),
    SETTINGS("Settings")
}

@Composable
fun HomeStudioScreen(
    viewModel: HomeViewModel,
    onOpenEditor: (String) -> Unit
) {
    val projects by viewModel.projects.collectAsState()
    val prefs by viewModel.preferences.collectAsState()
    val galleryItems by viewModel.galleryItems.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val cacheBytes by viewModel.cacheSizeBytes.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val banner by viewModel.statusBanner.collectAsState()

    var activeSection by remember { mutableStateOf(HomeNavSection.STUDIO) }
    var selectedAspect by remember { mutableStateOf(AspectRatioPreset.REELS_9_16) }
    var showNewProjectSheet by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var historyTargetId by remember { mutableStateOf<String?>(null) }

    val multiVisualPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(25)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.createProjectWithUris("New Edit", selectedAspect, uris, onOpenEditor)
        }
    }

    val universalDocPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.createProjectWithUris("New Edit", selectedAspect, uris, onOpenEditor)
        }
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.loadDeviceGalleryMedia()
    }

    Scaffold(
        containerColor = ZeroBlack,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.linearGradient(listOf(ZeroViolet, ZeroAccent))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MovieFilter, contentDescription = null, tint = Color.Black)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("ZERO", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = TextPrimary)
                        Text("Pro video editor · GPU effects", fontSize = 11.sp, color = TextSecondary)
                    }
                }

                Surface(
                    color = SurfaceElevated,
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke)
                ) {
                    Text(
                        text = if (viewModel.isHevcSupported) "4K • HEVC Ready" else "4K • H.264 Ready",
                        color = ElectricCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = ZeroSurface, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = activeSection == HomeNavSection.STUDIO,
                    onClick = { activeSection = HomeNavSection.STUDIO },
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                    label = { Text("Projects") }
                )
                NavigationBarItem(
                    selected = activeSection == HomeNavSection.SETTINGS,
                    onClick = {
                        viewModel.refreshCacheSize()
                        activeSection = HomeNavSection.SETTINGS
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeSection) {
                HomeNavSection.STUDIO -> ProjectsStudioSection(
                    projects = projects,
                    selectedAspect = selectedAspect,
                    onSelectAspect = { selectedAspect = it },
                    onPickVideosQuick = {
                        multiVisualPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    onOpenNewProjectModal = {
                        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            arrayOf(
                                Manifest.permission.READ_MEDIA_VIDEO,
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_AUDIO
                            )
                        } else {
                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                        mediaPermissionLauncher.launch(perms)
                        showNewProjectSheet = true
                    },
                    onCreateDemoReel = { viewModel.createDemoProject(selectedAspect, onOpenEditor) },
                    onOpenProject = onOpenEditor,
                    onRenameProject = { renameTarget = it },
                    onDuplicateProject = { viewModel.duplicateProject(it.id) },
                    onDeleteProject = { viewModel.deleteProject(it.id) },
                    onViewHistory = { historyTargetId = it.id }
                )

                HomeNavSection.SETTINGS -> StudioSettingsSection(
                    prefs = prefs,
                    cacheBytes = cacheBytes,
                    encoders = viewModel.hardwareEncoders,
                    onAutosaveChange = viewModel::updateAutosaveInterval,
                    onProxyToggle = viewModel::updateLowEndProxyMode,
                    onDefaultExportChange = viewModel::updateDefaultExport,
                    onClearCache = viewModel::clearCache
                )
            }

            if (isBusy || importProgress.isImporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.padding(horizontal = 28.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(color = ElectricCyan, modifier = Modifier.size(32.dp))
                            Text(
                                text = if (importProgress.isImporting) {
                                    "Loading ${importProgress.currentIndex}/${importProgress.totalCount}: ${importProgress.currentItemName}"
                                } else "Preparing Studio Timeline...",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (importProgress.isImporting) {
                                LinearProgressIndicator(
                                    progress = { importProgress.fraction },
                                    color = ElectricCyan,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(50))
                                )
                                Text(
                                    text = importProgress.stageDescription,
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            banner?.let { msg ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = viewModel::dismissBanner) { Text("OK", color = ElectricCyan) }
                    }
                ) {
                    Text(msg)
                }
            }
        }
    }

    // New Project + Universal Media Picker Sheet
    if (showNewProjectSheet) {
        NewProjectPickerSheet(
            initialAspect = selectedAspect,
            galleryItems = galleryItems,
            onDismiss = { showNewProjectSheet = false },
            onLaunchSystemPicker = { aspect ->
                selectedAspect = aspect
                showNewProjectSheet = false
                multiVisualPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            },
            onLaunchUniversalFiles = { aspect ->
                selectedAspect = aspect
                showNewProjectSheet = false
                universalDocPicker.launch(arrayOf("video/*", "image/*", "audio/*"))
            },
            onCreateBlank = { title, aspect ->
                showNewProjectSheet = false
                viewModel.createStudioCanvasProject(title, aspect, onCreated = onOpenEditor)
            },
            onCreateFromGallerySelection = { title, aspect, uris ->
                showNewProjectSheet = false
                viewModel.createProjectWithUris(title, aspect, uris, onOpenEditor)
            }
        )
    }

    // Rename Project Dialog
    renameTarget?.let { target ->
        var titleText by remember(target) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = PanelDark,
            title = { Text("Rename Project") },
            text = {
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    singleLine = true,
                    label = { Text("Project Name") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.renameProject(target.id, titleText)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Revision History Restore Dialog
    historyTargetId?.let { pid ->
        val revisions by viewModel.observeRevisions(pid).collectAsState(initial = emptyList())
        AlertDialog(
            onDismissRequest = { historyTargetId = null },
            containerColor = PanelDark,
            title = { Text("Project Revision History") },
            text = {
                if (revisions.isEmpty()) {
                    Text("No snapshots recorded yet.", color = TextSecondary)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(revisions) { rev ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        historyTargetId = null
                                        viewModel.restoreRevision(rev, onOpenEditor)
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Rev #${rev.revisionNumber} • ${rev.actionSummary}", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date(rev.savedAtMs)),
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }
                                Text("Restore", color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { historyTargetId = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun ProjectsStudioSection(
    projects: List<ProjectSummary>,
    selectedAspect: AspectRatioPreset,
    onSelectAspect: (AspectRatioPreset) -> Unit,
    onPickVideosQuick: () -> Unit,
    onOpenNewProjectModal: () -> Unit,
    onCreateDemoReel: () -> Unit,
    onOpenProject: (String) -> Unit,
    onRenameProject: (ProjectSummary) -> Unit,
    onDuplicateProject: (ProjectSummary) -> Unit,
    onDeleteProject: (ProjectSummary) -> Unit,
    onViewHistory: (ProjectSummary) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Hero New Project Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF1D1340), Color(0xFF0B2B3C))
                        )
                    )
                    .border(1.dp, ElectricCyan.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text("Create Masterpiece", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Import multi-clip video/photos, stack GPU Sharpen & FX, grade 3D LUTs & export in 4K.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(14.dp))

                    // Aspect Ratio Selector Row
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AspectRatioPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = selectedAspect == preset,
                                onClick = { onSelectAspect(preset) },
                                label = { Text(preset.label, fontSize = 12.sp) }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onPickVideosQuick,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = Color(0xFF001F24)
                            )
                        ) {
                            Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import Media", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onOpenNewProjectModal,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Browser")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    TextButton(
                        onClick = onCreateDemoReel,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = KeyframeGold, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Instant Demo Reel (Generate 3 H.264 Test Clips)", color = KeyframeGold, fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Local Drafts & Projects (${projects.size})", style = MaterialTheme.typography.titleMedium)
                Text("Autosave Active", fontSize = 11.sp, color = EmeraldBeat)
            }
        }

        if (projects.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(PanelDark)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MovieCreation, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No local projects yet", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Tap 'Import Media' to pick videos from gallery, or 'Instant Demo Reel' to generate test clips.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            items(projects, key = { it.id }) { proj ->
                ProjectDraftCard(
                    project = proj,
                    onClick = { onOpenProject(proj.id) },
                    onRename = { onRenameProject(proj) },
                    onDuplicate = { onDuplicateProject(proj) },
                    onDelete = { onDeleteProject(proj) },
                    onHistory = { onViewHistory(proj) }
                )
            }
        }
    }
}

@Composable
private fun ProjectDraftCard(
    project: ProjectSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PanelDark)
            .border(1.dp, SurfaceStroke, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            if (!project.thumbnailUri.isNullOrBlank()) {
                AsyncImage(
                    model = project.thumbnailUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.Theaters, contentDescription = null, tint = ElectricCyan)
            }
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
            ) {
                Text(
                    text = formatCompactDuration(project.durationMs),
                    fontSize = 9.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project.title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${project.aspectRatio.label} • ${project.clipCount} items • Rev #${project.revision}",
                fontSize = 12.sp,
                color = TextSecondary
            )
            Text(
                text = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(Date(project.updatedAtMs)),
                fontSize = 11.sp,
                color = TextSecondary.copy(alpha = 0.75f)
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Project actions", tint = TextSecondary)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = SurfaceElevated
            ) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = { menuExpanded = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                    onClick = { menuExpanded = false; onDuplicate() }
                )
                DropdownMenuItem(
                    text = { Text("Revision History") },
                    leadingIcon = { Icon(Icons.Default.History, null) },
                    onClick = { menuExpanded = false; onHistory() }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = CoralRed) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = CoralRed) },
                    onClick = { menuExpanded = false; onDelete() }
                )
            }
        }
    }
}

@Composable
private fun StudioSettingsSection(
    prefs: com.vibecut.pro.data.preferences.StudioPreferences,
    cacheBytes: Long,
    encoders: List<String>,
    onAutosaveChange: (Int) -> Unit,
    onProxyToggle: (Boolean) -> Unit,
    onDefaultExportChange: (String, String) -> Unit,
    onClearCache: () -> Unit
) {

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Performance & Autosave", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Low-End Device 480p Proxy Preview")
                            Text("Reduces live GPU FBO resolution while keeping full 4K export quality.", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(checked = prefs.lowEndProxyPreview, onCheckedChange = onProxyToggle)
                    }

                    Text("Autosave Interval: ${prefs.autosaveIntervalSec}s", fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 8, 15, 30).forEach { sec ->
                            FilterChip(
                                selected = prefs.autosaveIntervalSec == sec,
                                onClick = { onAutosaveChange(sec) },
                                label = { Text("${sec}s") }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Default Export Target", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExportResolution.entries.forEach { res ->
                            FilterChip(
                                selected = prefs.defaultResolutionName == res.name,
                                onClick = { onDefaultExportChange(res.name, prefs.defaultFrameRateName) },
                                label = { Text(res.badge) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExportFrameRate.entries.forEach { fr ->
                            FilterChip(
                                selected = prefs.defaultFrameRateName == fr.name,
                                onClick = { onDefaultExportChange(prefs.defaultResolutionName, fr.name) },
                                label = { Text("${fr.fps} FPS") }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val mb = String.format(Locale.US, "%.2f MB", cacheBytes / (1024f * 1024f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Cache & Temp Storage", fontWeight = FontWeight.Bold)
                            Text("Current footprint: $mb", fontSize = 12.sp, color = TextSecondary)
                        }
                        OutlinedButton(onClick = onClearCache) {
                            Text("Clear Cache")
                        }
                    }
                    HorizontalDivider(color = SurfaceStroke)
                    Text("Hardware MediaCodec Video Encoders (${encoders.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    encoders.take(6).forEach { enc ->
                        Text("• $enc", fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewProjectPickerSheet(
    initialAspect: AspectRatioPreset,
    galleryItems: List<com.vibecut.pro.ui.viewmodel.DeviceGalleryItem>,
    onDismiss: () -> Unit,
    onLaunchSystemPicker: (AspectRatioPreset) -> Unit,
    onLaunchUniversalFiles: (AspectRatioPreset) -> Unit,
    onCreateBlank: (String, AspectRatioPreset) -> Unit,
    onCreateFromGallerySelection: (String, AspectRatioPreset, List<Uri>) -> Unit
) {
    var title by remember { mutableStateOf("My VibeCut Edit") }
    var aspect by remember { mutableStateOf(initialAspect) }
    var kindFilter by remember { mutableStateOf("ALL") }
    val selectedUris = remember { mutableStateListOf<String>() }

    val filtered = remember(galleryItems, kindFilter) {
        when (kindFilter) {
            "VIDEO" -> galleryItems.filter { it.mediaKind == com.vibecut.pro.engine.model.MediaKind.VIDEO }
            "PHOTO" -> galleryItems.filter { it.mediaKind == com.vibecut.pro.engine.model.MediaKind.IMAGE }
            "AUDIO" -> galleryItems.filter { it.mediaKind == com.vibecut.pro.engine.model.MediaKind.AUDIO }
            else -> galleryItems
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PanelDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("New Studio Project", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Project Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AspectRatioPreset.entries.forEach { p ->
                    FilterChip(
                        selected = aspect == p,
                        onClick = { aspect = p },
                        label = { Text("${p.label} ${p.subtitle.substringBefore('/')}") }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { onLaunchSystemPicker(aspect) }) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Photos & Videos")
                }
                OutlinedButton(onClick = { onLaunchUniversalFiles(aspect) }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browse Files")
                }
                OutlinedButton(onClick = { onCreateBlank(title, aspect) }) {
                    Text("Studio Canvas")
                }
            }

            if (galleryItems.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ALL" to "All", "VIDEO" to "Videos", "PHOTO" to "Photos", "AUDIO" to "Audio").forEach { (key, lbl) ->
                        FilterChip(
                            selected = kindFilter == key,
                            onClick = { kindFilter = key },
                            label = { Text(lbl, fontSize = 11.sp) }
                        )
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(filtered, key = { it.uri }) { item ->
                        val isChosen = item.uri in selectedUris
                        val kindLabel = when (item.mediaKind) {
                            com.vibecut.pro.engine.model.MediaKind.IMAGE -> "PHOTO"
                            com.vibecut.pro.engine.model.MediaKind.AUDIO -> "AUDIO • ${formatCompactDuration(item.durationMs)}"
                            else -> formatCompactDuration(item.durationMs)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChosen) selectedUris.remove(item.uri) else selectedUris.add(item.uri)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                Text(kindLabel, fontSize = 10.sp, color = ElectricCyan)
                            }
                            Checkbox(
                                checked = isChosen,
                                onCheckedChange = {
                                    if (isChosen) selectedUris.remove(item.uri) else selectedUris.add(item.uri)
                                }
                            )
                        }
                    }
                }
                if (selectedUris.isNotEmpty()) {
                    Button(
                        onClick = {
                            onCreateFromGallerySelection(title, aspect, selectedUris.map { Uri.parse(it) })
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Import ${selectedUris.size} Selected Media Item(s)", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

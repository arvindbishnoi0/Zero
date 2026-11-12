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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vibecut.pro.data.repository.ProjectSummary
import com.vibecut.pro.engine.model.AspectRatioPreset
import com.vibecut.pro.ui.theme.*
import com.vibecut.pro.ui.viewmodel.DeviceGalleryItem
import com.vibecut.pro.ui.viewmodel.HomeViewModel

/**
 * ZERO home: project library plus a single, obvious "new project" flow.
 *
 * Deliberately no template marketplace and no AI studio — those entries were non-functional
 * placeholders and are removed. Everything on this screen either opens an existing project or
 * creates one from real media.
 */
@Composable
fun HomeStudioScreen(
    viewModel: HomeViewModel,
    onOpenEditor: (String) -> Unit
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val galleryItems by viewModel.galleryItems.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheSizeBytes.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var showNewProject by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }

    LaunchedEffect(Unit) { viewModel.loadDeviceGalleryMedia() }
    LaunchedEffect(statusBanner) {
        val msg = statusBanner ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.dismissBanner()
    }

    Scaffold(
        containerColor = ZeroBackground,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = ZeroSurfaceHigh,
                    contentColor = TextPrimary,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 30.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                HomeHeader(
                    projectCount = projects.size,
                    isHevcSupported = viewModel.isHevcSupported,
                    onNewProject = { showNewProject = true },
                    onSettings = { showSettings = true }
                )
            }

            if (isBusy) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ZeroSurface)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(color = ZeroAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Text("Preparing your project…", fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }

            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                QuickStartStrip(
                    galleryItems = galleryItems,
                    onStartWithMedia = { uris ->
                        viewModel.createProjectWithUris(
                            title = "New Edit",
                            aspectRatio = AspectRatioPreset.REELS_9_16,
                            uris = uris,
                            onCreated = onOpenEditor
                        )
                    }
                )
            }

            if (projects.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                    EmptyProjectsCard(onCreate = { showNewProject = true })
                }
            } else {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Your projects", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("${projects.size}", fontSize = 11.sp, color = TextTertiary)
                    }
                }

                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        summary = project,
                        onClick = { onOpenEditor(project.id) },
                        onRename = { renameTarget = project },
                        onDuplicate = { viewModel.duplicateProject(project.id) },
                        onDelete = { deleteTarget = project }
                    )
                }
            }
        }
    }

    if (showNewProject) {
        NewProjectSheet(
            galleryItems = galleryItems,
            onDismiss = { showNewProject = false },
            onCreateEmpty = { title, aspect ->
                viewModel.createEmptyProject(title, aspect) { id ->
                    showNewProject = false
                    onOpenEditor(id)
                }
            },
            onCreateWithMedia = { title, aspect, uris ->
                viewModel.createProjectWithUris(title, aspect, uris) { id ->
                    showNewProject = false
                    onOpenEditor(id)
                }
            },
            onCreateDemo = { aspect ->
                viewModel.createDemoProject(aspect) { id ->
                    showNewProject = false
                    onOpenEditor(id)
                }
            }
        )
    }

    if (showSettings) {
        SettingsSheet(
            lowEndProxy = prefs.lowEndProxyPreview,
            autosaveSec = prefs.autosaveIntervalSec,
            cacheBytes = cacheBytes,
            encoders = viewModel.hardwareEncoders,
            isHevcSupported = viewModel.isHevcSupported,
            onProxyChange = viewModel::updateLowEndProxyMode,
            onAutosaveChange = viewModel::updateAutosaveInterval,
            onClearCache = viewModel::clearCache,
            onDismiss = { showSettings = false }
        )
    }

    renameTarget?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename project") },
            text = {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameProject(target.id, draft)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete project?") },
            text = { Text("\"${target.title}\" will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProject(target.id)
                    deleteTarget = null
                }) { Text("Delete", color = ZeroCoral) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Keep") } }
        )
    }
}

// ---------------------------------------------------------------------------- header

@Composable
private fun HomeHeader(
    projectCount: Int,
    isHevcSupported: Boolean,
    onNewProject: () -> Unit,
    onSettings: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "ZERO",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    letterSpacing = 3.sp
                )
                Text(
                    "Precision video editor",
                    fontSize = 11.sp,
                    color = ZeroAccent,
                    letterSpacing = 1.sp
                )
            }
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = TextSecondary,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(ZeroSurface)
                    .clickable(onClick = onSettings)
                    .padding(8.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(StudioGradient)
                .border(1.dp, ZeroStroke, RoundedCornerShape(18.dp))
                .clickable(onClick = onNewProject)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ZeroAccent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = ZeroAccent, modifier = Modifier.size(26.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("New project", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    "9:16, 16:9, 1:1, 4:5 or custom canvas",
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
            Text(
                if (isHevcSupported) "HEVC ✓" else "H.264",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (isHevcSupported) ZeroAccent else TextTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(ZeroBlack.copy(alpha = 0.35f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        if (projectCount > 0) {
            Text(
                "$projectCount project${if (projectCount == 1) "" else "s"} on this device",
                fontSize = 10.sp,
                color = TextTertiary
            )
        }
    }
}

// ---------------------------------------------------------------------------- quick start

@Composable
private fun QuickStartStrip(
    galleryItems: List<DeviceGalleryItem>,
    onStartWithMedia: (List<Uri>) -> Unit
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris -> if (uris.isNotEmpty()) onStartWithMedia(uris) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Start from your media", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(
                "Pick",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = ZeroAccent,
                modifier = Modifier.clickable {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
            )
        }
        if (galleryItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ZeroSurface)
                    .clickable {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Tap to choose photos or videos",
                    fontSize = 11.sp,
                    color = TextTertiary
                )
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(galleryItems.take(24), key = { it.uri }) { item ->
                    Column(
                        modifier = Modifier
                            .width(78.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ZeroSurface)
                            .clickable { onStartWithMedia(listOf(Uri.parse(item.uri))) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().height(60.dp)) {
                            if (item.mediaKind == com.vibecut.pro.engine.model.MediaKind.VIDEO ||
                                item.mediaKind == com.vibecut.pro.engine.model.MediaKind.IMAGE
                            ) {
                                AsyncImage(
                                    model = item.uri,
                                    contentDescription = item.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(ZeroSurfaceHigh),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Audiotrack, contentDescription = null, tint = ZeroAccent, modifier = Modifier.size(20.dp))
                                }
                            }
                            if (item.durationMs > 0) {
                                Text(
                                    "%d:%02d".format(item.durationMs / 60000, (item.durationMs / 1000) % 60),
                                    fontSize = 7.5.sp,
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(2.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- project cards

@Composable
private fun ProjectCard(
    summary: ProjectSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ZeroSurface)
            .border(1.dp, ZeroStroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(ZeroBlack)) {
            if (summary.thumbnailUri != null) {
                AsyncImage(
                    model = summary.thumbnailUri,
                    contentDescription = summary.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(StudioGradient))
            }
            Text(
                summary.aspectRatio.badge,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
            Text(
                formatDurationShort(summary.durationMs),
                fontSize = 8.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = Color.White, modifier = Modifier.size(15.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuOpen = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Delete", color = ZeroCoral) }, onClick = { menuOpen = false; onDelete() })
            }
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                summary.title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${summary.clipCount} clips",
                fontSize = 9.sp,
                color = TextTertiary
            )
        }
    }
}

@Composable
private fun EmptyProjectsCard(onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ZeroSurface)
            .border(1.dp, ZeroStroke, RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.Movie, contentDescription = null, tint = ZeroAccent, modifier = Modifier.size(34.dp))
        Text("No projects yet", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(
            "Create a project, import your footage and the editor opens with everything you need.",
            fontSize = 10.sp,
            color = TextTertiary,
            textAlign = TextAlign.Center
        )
        StudioPrimaryButton(text = "Create project", icon = Icons.Default.Add, onClick = onCreate)
    }
}

// ---------------------------------------------------------------------------- new project sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewProjectSheet(
    galleryItems: List<DeviceGalleryItem>,
    onDismiss: () -> Unit,
    onCreateEmpty: (String, AspectRatioPreset) -> Unit,
    onCreateWithMedia: (String, AspectRatioPreset, List<Uri>) -> Unit,
    onCreateDemo: (AspectRatioPreset) -> Unit
) {
    var title by remember { mutableStateOf("New Edit") }
    var aspect by remember { mutableStateOf(AspectRatioPreset.REELS_9_16) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) onCreateWithMedia(title, aspect, uris)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ZeroBackground,
        contentColor = TextPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = ZeroStroke) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SheetHeader(title = "New project", onClose = onDismiss)

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Project name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            StudioCard(title = "Canvas") {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AspectRatioPreset.entries.filter { it != AspectRatioPreset.CUSTOM }.forEach { preset ->
                        StudioChip("${preset.label} ${preset.subtitle}", aspect == preset) { aspect = preset }
                    }
                }
            }

            StudioPrimaryButton(
                text = "Choose photos & videos",
                icon = Icons.Default.PhotoLibrary
            ) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            StudioPrimaryButton(
                text = "Start empty",
                icon = Icons.Default.NoteAdd,
                container = ZeroSurfaceHigh,
                contentColor = TextPrimary
            ) { onCreateEmpty(title, aspect) }

            if (galleryItems.isNotEmpty()) {
                Text(
                    "Tip: starting from media drops your clips straight onto the main track.",
                    fontSize = 10.sp,
                    color = TextTertiary
                )
            }
            TextButton(onClick = { onCreateDemo(aspect) }, modifier = Modifier.fillMaxWidth()) {
                Text("Create a demo project with generated clips", fontSize = 11.sp, color = ZeroAccent)
            }
        }
    }
}

// ---------------------------------------------------------------------------- settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    lowEndProxy: Boolean,
    autosaveSec: Int,
    cacheBytes: Long,
    encoders: List<String>,
    isHevcSupported: Boolean,
    onProxyChange: (Boolean) -> Unit,
    onAutosaveChange: (Int) -> Unit,
    onClearCache: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ZeroBackground,
        contentColor = TextPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = ZeroStroke) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SheetHeader(title = "Settings", onClose = onDismiss)
            StudioCard(title = "Performance") {
                StudioToggleRow("Proxy preview (720p decode)", lowEndProxy, onProxyChange)
                Text(
                    "Proxy preview decodes a smaller stream for smoother scrubbing. Export always uses full resolution.",
                    fontSize = 10.sp,
                    color = TextTertiary
                )
                StudioChipRow {
                    listOf(5, 8, 15, 30).forEach { sec ->
                        StudioChip("Autosave ${sec}s", autosaveSec == sec) { onAutosaveChange(sec) }
                    }
                }
            }
            StudioCard(title = "Storage") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Cache", fontSize = 11.sp, color = TextSecondary)
                    Text(
                        String.format(java.util.Locale.US, "%.1f MB", cacheBytes / (1024f * 1024f)),
                        fontSize = 11.sp,
                        color = TextPrimary
                    )
                }
                StudioChip("Clear cache") { onClearCache() }
            }
            StudioCard(title = "Hardware") {
                Text(
                    if (isHevcSupported) "H.265 / HEVC hardware encoder available" else "H.264 encoder only",
                    fontSize = 11.sp,
                    color = if (isHevcSupported) ZeroAccent else TextSecondary
                )
                encoders.take(4).forEach { enc ->
                    Text(enc, fontSize = 9.sp, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                "ZERO v1.0 • ${Build.MODEL} • API ${Build.VERSION.SDK_INT}",
                fontSize = 9.sp,
                color = TextTertiary
            )
        }
    }
}

package com.vibecut.pro.ui.editor.components

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vibecut.pro.engine.model.MediaKind
import com.vibecut.pro.engine.model.TrackType
import com.vibecut.pro.media.loader.MediaImportProgress
import com.vibecut.pro.ui.theme.*
import com.vibecut.pro.ui.viewmodel.DeviceGalleryItem
import java.util.Locale

private enum class MediaBrowserFilter(val label: String) {
    ALL("All Media"),
    VIDEOS("Videos"),
    PHOTOS("Photos"),
    AUDIO("Audio")
}

private data class BackdropOption(
    val title: String,
    val primaryArgb: Int,
    val secondaryArgb: Int
)

private val studioBackdrops = listOf(
    BackdropOption("Midnight Neon", 0xFF0E1326.toInt(), 0xFF00E5FF.toInt()),
    BackdropOption("Sunset Gold", 0xFF2B1328.toInt(), 0xFFFF9100.toInt()),
    BackdropOption("Cyber Violet", 0xFF160E2E.toInt(), 0xFF7C4DFF.toInt()),
    BackdropOption("Minimal Slate", 0xFF18202B.toInt(), 0xFF455A64.toInt()),
    BackdropOption("Emerald Studio", 0xFF0A241E.toInt(), 0xFF00E676.toInt())
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversalMediaImporterSheet(
    deviceMedia: List<DeviceGalleryItem>,
    importProgress: MediaImportProgress,
    targetTrackType: TrackType = TrackType.VIDEO_MAIN,
    onImportUris: (List<Uri>, TrackType) -> Unit,
    onAddBackdrop: (String, Int, Int) -> Unit,
    onGenerateSamples: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onDismiss: () -> Unit
) {
    var activeFilter by remember {
        mutableStateOf(
            if (targetTrackType.isAudioTrack) MediaBrowserFilter.AUDIO else MediaBrowserFilter.ALL
        )
    }
    var destTrack by remember(targetTrackType) { mutableStateOf(targetTrackType) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedUriStrings = remember { mutableStateListOf<String>() }

    val multiVisualLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(25)
    ) { uris ->
        if (uris.isNotEmpty()) onImportUris(uris, destTrack)
    }

    val universalDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onImportUris(uris, destTrack)
    }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val audioTrack = if (destTrack.isAudioTrack) destTrack else TrackType.AUDIO_MUSIC
            onImportUris(listOf(uri), audioTrack)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshLibrary()
    }

    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms)
    }

    val filteredItems = remember(deviceMedia, activeFilter, searchQuery) {
        deviceMedia.filter { item ->
            val kindOk = when (activeFilter) {
                MediaBrowserFilter.ALL -> true
                MediaBrowserFilter.VIDEOS -> item.mediaKind == MediaKind.VIDEO
                MediaBrowserFilter.PHOTOS -> item.mediaKind == MediaKind.IMAGE
                MediaBrowserFilter.AUDIO -> item.mediaKind == MediaKind.AUDIO
            }
            val queryOk = searchQuery.isBlank() ||
                item.displayName.contains(searchQuery.trim(), ignoreCase = true)
            kindOk && queryOk
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!importProgress.isImporting) onDismiss()
        },
        containerColor = PanelDark,
        dragHandle = { BottomSheetDefaults.DragHandle(color = SurfaceStroke) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header + Target Track Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Media Hub & Loader", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text(
                        "Supports MP4, MOV, MKV, WEBM, JPG, PNG, HEIC, WEBP, MP3, WAV, M4A, FLAC",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = onRefreshLibrary) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Media", tint = ElectricCyan)
                }
            }

            // Live Import Progress Banner
            if (importProgress.isImporting) {
                Surface(
                    color = SurfaceElevated,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Loading ${importProgress.currentIndex}/${importProgress.totalCount}: ${importProgress.currentItemName}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${(importProgress.fraction * 100).toInt()}%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        LinearProgressIndicator(
                            progress = { importProgress.fraction },
                            color = ElectricCyan,
                            trackColor = ObsidianBg,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(50))
                        )
                        Text(importProgress.stageDescription, fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }

            // Destination Track Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Target:", fontSize = 11.sp, color = TextSecondary)
                listOf(
                    TrackType.VIDEO_MAIN to "Main Timeline",
                    TrackType.VIDEO_PIP to "PIP Layer",
                    TrackType.AUDIO_MUSIC to "Music Track",
                    TrackType.AUDIO_VOICEOVER to "Voiceover"
                ).forEach { (track, label) ->
                    FilterChip(
                        selected = destTrack == track,
                        onClick = { destTrack = track },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }

            // 4 Universal System Source Launchers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickSourceCard(
                    icon = Icons.Default.PhotoLibrary,
                    title = "Photos & Videos",
                    subtitle = "Gallery Picker",
                    accent = ElectricCyan,
                    onClick = {
                        multiVisualLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    }
                )
                QuickSourceCard(
                    icon = Icons.Default.FolderOpen,
                    title = "Browse Files",
                    subtitle = "All Formats / SAF",
                    accent = NeonViolet,
                    onClick = {
                        universalDocLauncher.launch(arrayOf("video/*", "image/*", "audio/*"))
                    }
                )
                QuickSourceCard(
                    icon = Icons.Default.LibraryMusic,
                    title = "Import Audio",
                    subtitle = "MP3 / WAV / M4A",
                    accent = EmeraldBeat,
                    onClick = { audioLauncher.launch("audio/*") }
                )
                QuickSourceCard(
                    icon = Icons.Default.AutoAwesomeMotion,
                    title = "Sample Reel",
                    subtitle = "3 Studio Clips",
                    accent = KeyframeGold,
                    onClick = onGenerateSamples
                )
            }

            // Instant Studio Canvas Backdrops Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Backdrops:", fontSize = 11.sp, color = TextSecondary)
                studioBackdrops.forEach { bd ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(bd.primaryArgb), Color(bd.secondaryArgb))
                                )
                            )
                            .border(1.dp, SurfaceStroke, RoundedCornerShape(50))
                            .clickable { onAddBackdrop(bd.title, bd.primaryArgb, bd.secondaryArgb) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(bd.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }

            // Device Library Filter Tabs + Search
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MediaBrowserFilter.entries.forEach { f ->
                    val selected = activeFilter == f
                    Surface(
                        color = if (selected) ElectricCyan.copy(alpha = 0.2f) else SurfaceElevated,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) ElectricCyan else SurfaceStroke
                        ),
                        modifier = Modifier.clickable { activeFilter = f }
                    ) {
                        Text(
                            text = f.label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) ElectricCyan else TextSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search device videos, photos, or audio...", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            )

            // Device Media Grid
            Box(modifier = Modifier.weight(1f)) {
                if (filteredItems.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.PermMedia, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Use 'Photos & Videos' or 'Browse Files' above to pick any media file.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 106.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 72.dp)
                    ) {
                        items(filteredItems, key = { it.uri }) { item ->
                            val isSelected = item.uri in selectedUriStrings
                            DeviceMediaTile(
                                item = item,
                                isSelected = isSelected,
                                selectionIndex = selectedUriStrings.indexOf(item.uri) + 1,
                                onClick = {
                                    if (isSelected) selectedUriStrings.remove(item.uri)
                                    else selectedUriStrings.add(item.uri)
                                }
                            )
                        }
                    }
                }

                // Floating Multi-Import Action Bar
                if (selectedUriStrings.isNotEmpty()) {
                    Button(
                        onClick = {
                            val uris = selectedUriStrings.map { Uri.parse(it) }
                            selectedUriStrings.clear()
                            onImportUris(uris, destTrack)
                        },
                        enabled = !importProgress.isImporting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF001F24)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Import ${selectedUriStrings.size} Selected to ${destTrack.label}",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSourceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(subtitle, fontSize = 10.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun DeviceMediaTile(
    item: DeviceGalleryItem,
    isSelected: Boolean,
    selectionIndex: Int,
    onClick: () -> Unit
) {
    val badgeColor = when (item.mediaKind) {
        MediaKind.VIDEO -> ElectricCyan
        MediaKind.IMAGE -> KeyframeGold
        else -> EmeraldBeat
    }
    val typeBadge = when (item.mediaKind) {
        MediaKind.VIDEO -> formatCompactDuration(item.durationMs)
        MediaKind.IMAGE -> "PHOTO"
        else -> "AUDIO • ${formatCompactDuration(item.durationMs)}"
    }

    Box(
        modifier = Modifier
            .aspectRatio(0.88f)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) ElectricCyan else SurfaceStroke,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
    ) {
        if (item.mediaKind == MediaKind.AUDIO) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF122824), ObsidianBg))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = EmeraldBeat, modifier = Modifier.size(34.dp))
            }
        } else {
            AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Top badge: resolution or size
        if (item.width > 0 && item.height > 0) {
            Text(
                text = "${item.width}×${item.height}",
                fontSize = 9.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        } else if (item.sizeBytes > 0L) {
            val mb = String.format(Locale.US, "%.1fMB", item.sizeBytes / (1024f * 1024f))
            Text(
                text = mb,
                fontSize = 9.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        // Selection circle
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (isSelected) ElectricCyan else Color(0x88000000))
                .border(1.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Text(
                    text = "$selectionIndex",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.Black
                )
            }
        }

        // Bottom info bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD080A10)))
                )
                .padding(horizontal = 6.dp, vertical = 5.dp)
        ) {
            Text(
                text = typeBadge,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = badgeColor
            )
            Text(
                text = item.displayName,
                fontSize = 10.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

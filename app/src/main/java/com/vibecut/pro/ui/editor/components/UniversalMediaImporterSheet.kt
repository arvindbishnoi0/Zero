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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

private enum class MediaFilter(val label: String) {
    ALL("All"),
    VIDEOS("Videos"),
    PHOTOS("Photos"),
    AUDIO("Audio")
}

private data class BackdropOption(val title: String, val primaryArgb: Int, val secondaryArgb: Int)

private val STUDIO_BACKDROPS = listOf(
    BackdropOption("Midnight", 0xFF0E1326.toInt(), 0xFF00E5FF.toInt()),
    BackdropOption("Sunset", 0xFF2B1328.toInt(), 0xFFFF9100.toInt()),
    BackdropOption("Violet", 0xFF160E2E.toInt(), 0xFF7C4DFF.toInt()),
    BackdropOption("Slate", 0xFF18202B.toInt(), 0xFF455A64.toInt()),
    BackdropOption("Emerald", 0xFF0A241E.toInt(), 0xFF00E676.toInt())
)

/**
 * Media importer: system pickers, the scanned device library, generated backdrops and demo clips.
 * Every entry point actually imports bytes through [MediaSupportManager] — nothing here is a stub.
 */
@Composable
fun UniversalMediaImporterSheet(
    deviceMedia: List<DeviceGalleryItem>,
    importProgress: MediaImportProgress,
    targetTrackType: TrackType = TrackType.VIDEO_MAIN,
    isLibraryLoading: Boolean = false,
    onImportUris: (List<Uri>, TrackType) -> Unit,
    onAddBackdrop: (String, Int, Int) -> Unit,
    onGenerateSamples: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onDismiss: () -> Unit
) {
    var filter by remember {
        mutableStateOf(if (targetTrackType.isAudioTrack) MediaFilter.AUDIO else MediaFilter.ALL)
    }
    var destTrack by remember(targetTrackType) { mutableStateOf(targetTrackType) }
    var query by remember { mutableStateOf("") }

    val visualLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris -> if (uris.isNotEmpty()) onImportUris(uris, destTrack) }

    val docLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) onImportUris(uris, destTrack) }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val track = if (destTrack.isAudioTrack) destTrack else TrackType.AUDIO_MUSIC
            onImportUris(listOf(uri), track)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onRefreshLibrary() }

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
        onRefreshLibrary()
    }

    val visibleMedia = remember(deviceMedia, filter, query) {
        deviceMedia.filter { item ->
            val kindOk = when (filter) {
                MediaFilter.ALL -> true
                MediaFilter.VIDEOS -> item.mediaKind == MediaKind.VIDEO
                MediaFilter.PHOTOS -> item.mediaKind == MediaKind.IMAGE
                MediaFilter.AUDIO -> item.mediaKind == MediaKind.AUDIO || item.mediaKind == MediaKind.GENERATED_SFX
            }
            kindOk && (query.isBlank() || item.displayName.contains(query, ignoreCase = true))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 360.dp, max = 640.dp)
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Import progress (real decoder progress from MediaSupportManager)
        if (importProgress.isActive) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ZeroSurfaceHigh)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(importProgress.stageDescription, fontSize = 11.sp, color = TextSecondary)
                    Text("${(importProgress.fraction * 100).toInt()}%", fontSize = 11.sp, color = ZeroAccent, fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(
                    progress = { importProgress.fraction },
                    color = ZeroAccent,
                    trackColor = ZeroStroke,
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50))
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                TrackType.VIDEO_MAIN to "Main",
                TrackType.VIDEO_PIP to "Overlay",
                TrackType.AUDIO_MUSIC to "Music",
                TrackType.AUDIO_VOICEOVER to "Voice"
            ).forEach { (track, label) ->
                StudioChip(label, destTrack == track) { destTrack = track }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SourceCard(Icons.Default.PhotoLibrary, "Gallery", "Photos & video", ZeroAccent) {
                visualLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            }
            SourceCard(Icons.Default.FolderOpen, "Files", "Any format", ZeroViolet) {
                docLauncher.launch(arrayOf("video/*", "image/*", "audio/*"))
            }
            SourceCard(Icons.Default.LibraryMusic, "Audio", "MP3 / WAV / M4A", ZeroBlue) {
                audioLauncher.launch("audio/*")
            }
            SourceCard(Icons.Default.AutoAwesome, "Demo", "Generated clips", ZeroAmber, onGenerateSamples)
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Backdrops", fontSize = 10.sp, color = TextTertiary)
            STUDIO_BACKDROPS.forEach { bd ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(Color(bd.primaryArgb), Color(bd.secondaryArgb))))
                        .clickable { onAddBackdrop(bd.title, bd.primaryArgb, bd.secondaryArgb) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(bd.title, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search your media", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaFilter.entries.forEach { f ->
                StudioChip(f.label, filter == f) { filter = f }
            }
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Refresh library",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp).clickable(onClick = onRefreshLibrary)
            )
        }

        if (isLibraryLoading) {
            Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ZeroAccent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else if (visibleMedia.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ZeroSurface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No media found yet — use Gallery / Files above,\nor grant access and tap refresh.",
                    fontSize = 11.sp,
                    color = TextTertiary,
                    lineHeight = 16.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(visibleMedia, key = { it.uri }) { item ->
                    GridMediaTile(item) {
                        val track = when {
                            item.mediaKind == MediaKind.AUDIO -> if (destTrack.isAudioTrack) destTrack else TrackType.AUDIO_MUSIC
                            destTrack.isAudioTrack -> TrackType.VIDEO_MAIN
                            else -> destTrack
                        }
                        onImportUris(listOf(Uri.parse(item.uri)), track)
                    }
                }
            }
        }

        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Close", color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ZeroSurfaceHigh)
            .border(1.dp, ZeroStroke, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(16.dp))
        }
        Column {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(subtitle, fontSize = 9.sp, color = TextTertiary)
        }
    }
}

@Composable
private fun GridMediaTile(item: DeviceGalleryItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(ZeroSurfaceHigh)
            .clickable(onClick = onClick)
    ) {
        if (item.mediaKind == MediaKind.VIDEO || item.mediaKind == MediaKind.IMAGE) {
            AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Audiotrack, contentDescription = null, tint = ZeroAccent, modifier = Modifier.size(22.dp))
            }
        }
        // Duration badge
        if (item.durationMs > 0) {
            Text(
                "%d:%02d".format(item.durationMs / 60000, (item.durationMs / 1000) % 60),
                fontSize = 8.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
        if (item.mediaKind == MediaKind.VIDEO) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopStart).padding(3.dp).size(13.dp)
            )
        }
    }
}

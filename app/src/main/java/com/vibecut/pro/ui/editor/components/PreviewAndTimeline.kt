package com.vibecut.pro.ui.editor.components

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.keyframes.KeyframeInterpolator
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.ui.theme.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PreviewViewport(
    exoPlayer: ExoPlayer,
    project: Project,
    playheadMs: Long,
    isPlaying: Boolean,
    selectedClip: TimelineClip?,
    isCompareOriginal: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onTogglePlay: () -> Unit,
    onStepFrames: (Int) -> Unit,
    onToggleCompare: () -> Unit,
    onQuickSplit: () -> Unit,
    onQuickSnapshot: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenMediaHub: () -> Unit,
    onGenerateDemoClick: () -> Unit,
    onDragSelectedOverlay: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val mainHasClips = project.mainVideoClips.isNotEmpty()
    val visibleVisualClip = remember(project, playheadMs, selectedClip?.id) {
        project.findMainClipAt(playheadMs) ?: selectedClip
    }

    val activePips = remember(project.tracks, playheadMs) {
        project.tracks
            .filter { (it.type == TrackType.VIDEO_PIP || it.type == TrackType.OVERLAY) && !it.isHidden }
            .flatMap { it.clips }
            .filter { playheadMs in it.startTimeOnTrackMs..it.endTimeOnTrackMs }
    }
    val activeTexts = remember(project.tracks, playheadMs) {
        project.tracks
            .filter { it.type == TrackType.TEXT && !it.isHidden }
            .flatMap { it.clips }
            .filter { playheadMs in it.startTimeOnTrackMs..it.endTimeOnTrackMs }
    }
    val activeStickers = remember(project.tracks, playheadMs) {
        project.tracks
            .filter { it.type == TrackType.STICKER && !it.isHidden }
            .flatMap { it.clips }
            .filter { playheadMs in it.startTimeOnTrackMs..it.endTimeOnTrackMs }
    }

    val canDragSelectedOnCanvas = remember(selectedClip, project) {
        val clip = selectedClip ?: return@remember false
        val track = project.findTrackForClip(clip.id) ?: return@remember false
        track.type != TrackType.VIDEO_MAIN ||
            abs(clip.transform.scaleX) > 1.03f ||
            abs(clip.transform.positionX) > 0.01f ||
            abs(clip.transform.positionY) > 0.01f
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Video Stage
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF06080C))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .aspectRatio(project.aspectRatio.ratioFloat, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(project.backgroundColorArgb))
                    .border(
                        1.dp,
                        if (isCompareOriginal) KeyframeGold.copy(alpha = 0.8f) else SurfaceStroke,
                        RoundedCornerShape(12.dp)
                    )
                    .pointerInput(selectedClip?.id, canDragSelectedOnCanvas) {
                        if (!canDragSelectedOnCanvas) return@pointerInput
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (size.width > 0 && size.height > 0) {
                                val dxNdc = (dragAmount.x / size.width) * 2f
                                val dyNdc = -(dragAmount.y / size.height) * 2f
                                onDragSelectedOverlay(dxNdc, dyNdc)
                            }
                        }
                    }
            ) {
                val stageW = maxWidth
                val stageH = maxHeight

                if (mainHasClips) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                player = exoPlayer
                            }
                        },
                        update = { view ->
                            if (view.player !== exoPlayer) view.player = exoPlayer
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onOpenMediaHub,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = Color(0xFF001F24)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Import Videos, Photos or Audio", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        TextButton(onClick = onGenerateDemoClick) {
                            Text("Or Generate 3 Studio Sample Clips", color = KeyframeGold, fontSize = 11.sp)
                        }
                    }
                }

                // Live PIP / Overlay Visual Layers
                activePips.forEach { pip ->
                    val localMs = (playheadMs - pip.startTimeOnTrackMs).coerceAtLeast(0L)
                    val state = KeyframeInterpolator.evaluateClipState(pip, localMs)
                    val offsetX = stageW * (state.positionX * 0.45f)
                    val offsetY = stageH * (-state.positionY * 0.45f)
                    val isSelected = selectedClip?.id == pip.id

                    AsyncImage(
                        model = pip.thumbnailUri ?: pip.sourceUri,
                        contentDescription = pip.sourceName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(stageW * 0.52f)
                            .offset(x = offsetX, y = offsetY)
                            .graphicsLayer(
                                scaleX = state.scaleX,
                                scaleY = state.scaleY,
                                rotationZ = state.rotationDegrees,
                                alpha = state.opacity.coerceIn(0f, 1f)
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (isSelected) Modifier.border(1.5.dp, ElectricCyan, RoundedCornerShape(8.dp))
                                else Modifier
                            )
                    )
                }

                // Live Interactive Text Layers Overlay
                activeTexts.forEach { tc ->
                    val spec = tc.textSpec ?: return@forEach
                    val localMs = (playheadMs - tc.startTimeOnTrackMs).coerceAtLeast(0L)
                    val state = KeyframeInterpolator.evaluateClipState(tc, localMs)
                    val offsetX = stageW * (state.positionX * 0.45f)
                    val offsetY = stageH * (-state.positionY * 0.45f)
                    val isSelected = selectedClip?.id == tc.id

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = offsetX, y = offsetY)
                            .graphicsLayer(
                                rotationZ = state.rotationDegrees,
                                alpha = state.opacity.coerceIn(0f, 1f)
                            )
                            .then(
                                if (isSelected) Modifier.border(1.dp, ElectricCyan, RoundedCornerShape(6.dp))
                                else Modifier
                            )
                            .background(Color(spec.backgroundColorArgb), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = spec.text,
                            fontSize = (spec.fontSizeSp * 0.72f * abs(state.scaleX)).sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = when {
                                spec.fontFamilyName.contains("Mono", true) -> FontFamily.Monospace
                                spec.fontFamilyName.contains("Serif", true) && !spec.fontFamilyName.contains("Sans", true) -> FontFamily.Serif
                                else -> FontFamily.SansSerif
                            },
                            style = if (spec.useGradient) {
                                TextStyle(
                                    brush = Brush.linearGradient(
                                        listOf(Color(spec.textColorArgb), Color(spec.gradientEndColorArgb))
                                    ),
                                    shadow = Shadow(
                                        color = Color(spec.shadowColorArgb),
                                        offset = if (spec.is3dExtruded) Offset(5f, 5f) else Offset(0f, 3f),
                                        blurRadius = spec.shadowRadius
                                    )
                                )
                            } else {
                                TextStyle(
                                    color = Color(spec.textColorArgb),
                                    shadow = Shadow(
                                        color = Color(spec.shadowColorArgb),
                                        offset = if (spec.is3dExtruded) Offset(5f, 5f) else Offset(0f, 3f),
                                        blurRadius = spec.shadowRadius
                                    )
                                )
                            }
                        )
                    }
                }

                // Live Interactive Stickers Overlay
                activeStickers.forEach { sc ->
                    val sticker = sc.stickerSpec ?: return@forEach
                    val localMs = (playheadMs - sc.startTimeOnTrackMs).coerceAtLeast(0L)
                    val state = KeyframeInterpolator.evaluateClipState(sc, localMs)
                    val offsetX = stageW * (state.positionX * 0.45f)
                    val offsetY = stageH * (-state.positionY * 0.45f)

                    Text(
                        text = sticker.content,
                        fontSize = (36f * abs(state.scaleX)).sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = offsetX, y = offsetY)
                            .graphicsLayer(
                                rotationZ = state.rotationDegrees,
                                alpha = state.opacity.coerceIn(0f, 1f)
                            )
                    )
                }

                // Live Status Overlay Badge (Compare Original or Active Filter/Detail/FX summary)
                if (mainHasClips && visibleVisualClip != null) {
                    val g = visibleVisualClip.colorGrade
                    val presetName = ColorGradingEngine.findPresetById(g.lutPresetId, project.customColorPresets)?.name
                    val summaryParts = buildList {
                        if (presetName != null) add(presetName)
                        if (g.sharpness > 0.01f || abs(g.clarity) > 0.01f || g.structure > 0.01f) add("Sharpen")
                        val adjCount = g.activeManualAdjustmentCount -
                            (if (g.sharpness > 0.01f) 1 else 0) -
                            (if (abs(g.clarity) > 0.01f) 1 else 0) -
                            (if (g.structure > 0.01f) 1 else 0)
                        if (adjCount > 0) add("+$adjCount Adjust")
                        val enabledFx = visibleVisualClip.effects.count { it.enabled }
                        if (enabledFx > 0) add("$enabledFx FX")
                    }

                    if (isCompareOriginal) {
                        Surface(
                            color = KeyframeGold.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                        ) {
                            Text(
                                text = "ORIGINAL (BEFORE)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.Black,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    } else if (summaryParts.isNotEmpty()) {
                        Surface(
                            color = Color(0xAA0B0D14),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                        ) {
                            Text(
                                text = summaryParts.joinToString(" • "),
                                fontSize = 9.sp,
                                color = ElectricCyan,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            // Floating +Media Quick Pill on Top-Right of Stage
            if (mainHasClips) {
                Surface(
                    color = SurfaceElevated.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, SurfaceStroke),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 4.dp)
                        .clickable(onClick = onOpenMediaHub)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(14.dp))
                        Text("Media", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                }
            }
        }

        // Precision Studio Transport Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelDark)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${formatTimecode(playheadMs, project.exportConfig.frameRate.fps)} / ${formatTimecode(project.totalDurationMs, project.exportConfig.frameRate.fps)}",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = ElectricCyan
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onStepFrames(-1) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Prev Frame", modifier = Modifier.size(18.dp))
                }
                FilledIconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = ElectricCyan,
                        contentColor = Color(0xFF001F24)
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(onClick = { onStepFrames(1) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next Frame", modifier = Modifier.size(18.dp))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                // Before / After Compare Toggle
                IconButton(onClick = onToggleCompare, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Compare,
                        contentDescription = "Compare Before/After",
                        tint = if (isCompareOriginal) KeyframeGold else TextSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                }
                IconButton(onClick = onQuickSnapshot, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "Capture Frame", tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onQuickSplit, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.ContentCut, contentDescription = "Split", tint = TextPrimary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Undo, contentDescription = "Undo", modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Redo, contentDescription = "Redo", modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

/**
 * Compact 52dp Scrubber & Clip Selector Strip shown when full Customization Studio panels
 * (`Filters`, `Sharpen`, `Adjust`, `Effects`, `Text`, `AI`) are active so sliders & presets
 * have spacious room without losing timeline scrubbing or clip switching!
 */
@Composable
fun CompactTimelineStrip(
    project: Project,
    playheadMs: Long,
    selectedClipId: String?,
    onSelectClip: (String) -> Unit,
    onSeekMs: (Long) -> Unit,
    onExpandTimeline: () -> Unit,
    onOpenMediaHub: () -> Unit
) {
    val totalMs = project.totalDurationMs.coerceAtLeast(1L)
    val mainClips = project.mainVideoClips

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0E111A))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Horizontal clip selector pills
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                mainClips.forEachIndexed { idx, clip ->
                    val selected = clip.id == selectedClipId
                    val hasEdits = !clip.colorGrade.isIdentity || clip.effects.isNotEmpty()
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) ElectricCyan.copy(alpha = 0.20f) else SurfaceElevated)
                            .border(
                                1.dp,
                                if (selected) ElectricCyan else SurfaceStroke,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectClip(clip.id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${idx + 1}. ${clip.sourceName.take(14)}",
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) ElectricCyan else TextPrimary,
                            maxLines = 1
                        )
                        if (hasEdits) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(KeyframeGold)
                            )
                        }
                    }
                }
                Surface(
                    color = SurfaceElevated,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, SurfaceStroke),
                    modifier = Modifier.clickable(onClick = onOpenMediaHub)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(13.dp))
                        Text("Add", fontSize = 10.sp, color = ElectricCyan, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Expand Full Multi-Track Timeline Toggle
            Surface(
                color = SurfaceElevated,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, SurfaceStroke),
                modifier = Modifier.clickable(onClick = onExpandTimeline)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(Icons.Default.ViewStream, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(13.dp))
                    Text("Tracks", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.ExpandMore, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(13.dp))
                }
            }
        }

        // Smooth Interactive Scrubber Bar
        if (mainClips.isNotEmpty()) {
            Slider(
                value = playheadMs.toFloat().coerceIn(0f, totalMs.toFloat()),
                onValueChange = { onSeekMs(it.toLong()) },
                valueRange = 0f..totalMs.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = ElectricCyan,
                    activeTrackColor = ElectricCyan,
                    inactiveTrackColor = SurfaceStroke
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
            )
        }
    }
}

@Composable
fun MultiTrackTimeline(
    project: Project,
    playheadMs: Long,
    selectedClipId: String?,
    onSelectClip: (String) -> Unit,
    onSeekMs: (Long) -> Unit,
    onZoomChange: (Float) -> Unit,
    onToggleTrackLock: (String) -> Unit,
    onToggleTrackHidden: (String) -> Unit,
    onToggleTrackMute: (String) -> Unit,
    onOpenTransitionInspector: (String) -> Unit,
    onImportClipToTrack: (TrackType) -> Unit,
    onCollapseTimeline: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val zoom = project.timelineZoom.coerceIn(0.25f, 5.0f)
    val dpPerSec = 56f * zoom
    val totalTimelineSec = max(10f, (project.totalDurationMs / 1000f) + 4f)
    val timelineContentWidth: Dp = (totalTimelineSec * dpPerSec).dp
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0E111A))
    ) {
        // Timeline Mini-Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceElevated.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (project.magneticTimeline) "🧲 Magnetic" else "Free Track",
                    fontSize = 10.sp,
                    color = ElectricCyan
                )
                Text("•", color = TextSecondary, fontSize = 10.sp)
                Text(
                    text = if (project.snappingEnabled) "Snap ON" else "Snap OFF",
                    fontSize = 10.sp,
                    color = EmeraldBeat
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { onZoomChange((zoom / 1.25f).coerceAtLeast(0.25f)) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", modifier = Modifier.size(15.dp))
                }
                Text(
                    "${(zoom * 100).roundToInt()}%",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                IconButton(onClick = { onZoomChange((zoom * 1.25f).coerceAtMost(5.0f)) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", modifier = Modifier.size(15.dp))
                }
                if (onCollapseTimeline != null) {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        color = PanelDark,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, SurfaceStroke),
                        modifier = Modifier.clickable(onClick = onCollapseTimeline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Compact", fontSize = 9.sp, color = TextSecondary)
                            Icon(Icons.Default.ExpandLess, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
        }

        // Scrollable Multi-Track Area
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left Track Headers Column
                Column(
                    modifier = Modifier
                        .width(86.dp)
                        .fillMaxHeight()
                        .verticalScroll(verticalScroll)
                        .background(PanelDark)
                        .padding(top = 22.dp)
                ) {
                    project.tracks.forEach { track ->
                        TrackHeaderCell(
                            track = track,
                            onLock = { onToggleTrackLock(track.id) },
                            onHide = { onToggleTrackHidden(track.id) },
                            onMute = { onToggleTrackMute(track.id) },
                            onAdd = { onImportClipToTrack(track.type) }
                        )
                    }
                }

                // Right Scrollable Lanes + Time Ruler + Playhead Line
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .horizontalScroll(scrollState)
                            .verticalScroll(verticalScroll)
                            .width(timelineContentWidth)
                    ) {
                        TimeRulerBar(
                            totalSeconds = totalTimelineSec.toInt(),
                            dpPerSec = dpPerSec,
                            horizontalOffsetPx = scrollState.value,
                            onSeekMs = onSeekMs
                        )

                        project.tracks.forEach { track ->
                            TrackClipsLane(
                                track = track,
                                dpPerSec = dpPerSec,
                                totalWidth = timelineContentWidth,
                                selectedClipId = selectedClipId,
                                onSelectClip = onSelectClip,
                                onOpenTransition = onOpenTransitionInspector
                            )
                        }
                    }

                    val playheadXPx = with(density) { ((playheadMs / 1000f) * dpPerSec).dp.toPx() } - scrollState.value
                    if (playheadXPx >= 0f) {
                        val xDp = with(density) { playheadXPx.toDp() }
                        Box(
                            modifier = Modifier
                                .offset(x = xDp)
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(ElectricCyan)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackHeaderCell(
    track: Track,
    onLock: () -> Unit,
    onHide: () -> Unit,
    onMute: () -> Unit,
    onAdd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(0.5.dp, SurfaceStroke)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = track.name,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (track.isHidden) TextSecondary.copy(alpha = 0.4f) else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add to track",
                tint = ElectricCyan,
                modifier = Modifier
                    .size(13.dp)
                    .clickable(onClick = onAdd)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = if (track.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = "Lock",
                tint = if (track.isLocked) KeyframeGold else TextSecondary,
                modifier = Modifier.size(12.dp).clickable(onClick = onLock)
            )
            if (track.type.isVisual) {
                Icon(
                    imageVector = if (track.isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = "Hide",
                    tint = if (track.isHidden) CoralRed else TextSecondary,
                    modifier = Modifier.size(12.dp).clickable(onClick = onHide)
                )
            }
            if (track.type.isAudio) {
                Icon(
                    imageVector = if (track.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = "Mute",
                    tint = if (track.isMuted) CoralRed else TextSecondary,
                    modifier = Modifier.size(12.dp).clickable(onClick = onMute)
                )
            }
        }
    }
}

@Composable
private fun TimeRulerBar(
    totalSeconds: Int,
    dpPerSec: Float,
    horizontalOffsetPx: Int,
    onSeekMs: (Long) -> Unit
) {
    val density = LocalDensity.current
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(Color(0xFF141824))
            .pointerInput(dpPerSec) {
                detectTapGestures { offset ->
                    val pxPerSec = with(density) { dpPerSec.dp.toPx() }
                    val targetMs = (((offset.x + horizontalOffsetPx) / pxPerSec) * 1000f).toLong().coerceAtLeast(0L)
                    onSeekMs(targetMs)
                }
            }
            .pointerInput(dpPerSec) {
                detectDragGestures { change, _ ->
                    val pxPerSec = with(density) { dpPerSec.dp.toPx() }
                    val targetMs = (((change.position.x + horizontalOffsetPx) / pxPerSec) * 1000f).toLong().coerceAtLeast(0L)
                    onSeekMs(targetMs)
                }
            }
    ) {
        val pxPerSec = dpPerSec.dp.toPx()
        for (s in 0..totalSeconds) {
            val x = s * pxPerSec
            drawLine(
                color = Color(0xFF5A657D),
                start = Offset(x, size.height * 0.45f),
                end = Offset(x, size.height),
                strokeWidth = 2f
            )
            val halfX = x + pxPerSec * 0.5f
            drawLine(
                color = Color(0xFF2D3548),
                start = Offset(halfX, size.height * 0.7f),
                end = Offset(halfX, size.height),
                strokeWidth = 1.5f
            )
        }
    }
}

@Composable
private fun TrackClipsLane(
    track: Track,
    dpPerSec: Float,
    totalWidth: Dp,
    selectedClipId: String?,
    onSelectClip: (String) -> Unit,
    onOpenTransition: (String) -> Unit
) {
    val laneColor = when (track.type) {
        TrackType.VIDEO_MAIN -> Color(0xFF121826)
        TrackType.VIDEO_PIP, TrackType.OVERLAY -> Color(0xFF101622)
        TrackType.TEXT -> Color(0xFF161326)
        TrackType.STICKER -> Color(0xFF181522)
        else -> Color(0xFF0F191E)
    }

    Box(
        modifier = Modifier
            .width(totalWidth)
            .height(48.dp)
            .background(laneColor)
            .border(0.5.dp, SurfaceStroke.copy(alpha = 0.55f))
    ) {
        track.clips.forEachIndexed { idx, clip ->
            val xOffset = ((clip.startTimeOnTrackMs / 1000f) * dpPerSec).dp
            val clipWidth = max(24f, (clip.timelineDurationMs / 1000f) * dpPerSec).dp
            val isSelected = clip.id == selectedClipId

            val blockTint = when (track.type) {
                TrackType.VIDEO_MAIN -> Color(0xFF1E3A5F)
                TrackType.VIDEO_PIP, TrackType.OVERLAY -> Color(0xFF392B68)
                TrackType.TEXT -> Color(0xFF5D2E6A)
                TrackType.STICKER -> Color(0xFF6B4722)
                TrackType.AUDIO_MUSIC -> Color(0xFF124B48)
                TrackType.AUDIO_VOICEOVER -> Color(0xFF5A2438)
                TrackType.AUDIO_SFX -> Color(0xFF2B4D26)
            }

            Box(
                modifier = Modifier
                    .offset(x = xOffset)
                    .width(clipWidth)
                    .height(42.dp)
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(blockTint)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) ElectricCyan else Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(7.dp)
                    )
                    .clickable { onSelectClip(clip.id) }
            ) {
                val thumbModel = clip.thumbnailUri ?: clip.sourceUri
                if (clip.mediaKind == MediaKind.VIDEO || clip.mediaKind == MediaKind.IMAGE) {
                    AsyncImage(
                        model = thumbModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.48f,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (clip.waveformPeaks.isNotEmpty() && (track.type.isAudio || track.type == TrackType.VIDEO_MAIN)) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                        val stepX = size.width / clip.waveformPeaks.size.coerceAtLeast(1)
                        val midY = size.height * (if (track.type.isAudio) 0.5f else 0.78f)
                        val ampScale = if (track.type.isAudio) 0.45f else 0.18f
                        clip.waveformPeaks.forEachIndexed { wIdx, amp ->
                            val x = wIdx * stepX
                            val barH = (amp * size.height * ampScale).coerceAtLeast(1.5f)
                            drawLine(
                                color = EmeraldBeat.copy(alpha = if (track.type.isAudio) 0.65f else 0.35f),
                                start = Offset(x, midY - barH),
                                end = Offset(x, midY + barH),
                                strokeWidth = max(1.5f, stepX * 0.55f)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = clip.textSpec?.text ?: clip.sourceName,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (!clip.colorGrade.isIdentity) {
                            Text(
                                text = "LUT",
                                fontSize = 8.sp,
                                color = KeyframeGold,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (clip.effects.isNotEmpty()) {
                            Text(
                                text = "FX:${clip.effects.size}",
                                fontSize = 8.sp,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val durMs = clip.timelineDurationMs.coerceAtLeast(1L).toFloat()
                    for (kf in clip.keyframes) {
                        val kx = (kf.timeOffsetMs / durMs) * size.width
                        drawCircle(
                            color = KeyframeGold,
                            radius = 4.2f,
                            center = Offset(kx.coerceIn(4f, size.width - 4f), size.height - 6f)
                        )
                    }
                    for (beat in clip.beatMarkersMs) {
                        if (beat <= durMs) {
                            val bx = (beat / durMs) * size.width
                            drawCircle(
                                color = Color.White.copy(alpha = 0.7f),
                                radius = 2.4f,
                                center = Offset(bx, size.height - 3f),
                                style = Stroke(width = 1.2f)
                            )
                        }
                    }
                }
            }

            if (track.type == TrackType.VIDEO_MAIN && idx > 0) {
                Box(
                    modifier = Modifier
                        .offset(x = xOffset - 9.dp, y = 13.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (clip.transitionIn != null) NeonViolet else SurfaceElevated)
                        .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        .clickable {
                            onSelectClip(clip.id)
                            onOpenTransition(clip.id)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Transition",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

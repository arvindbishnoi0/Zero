package com.vibecut.pro.ui.editor.components

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
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
import com.vibecut.pro.engine.keyframes.KeyframeInterpolator
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.ui.theme.*
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
    canUndo: Boolean,
    canRedo: Boolean,
    onTogglePlay: () -> Unit,
    onStepFrames: (Int) -> Unit,
    onQuickSplit: () -> Unit,
    onQuickKeyframe: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onImportMediaClick: () -> Unit,
    onGenerateDemoClick: () -> Unit,
    onDragSelectedOverlay: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val mainHasClips = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN }?.clips?.isNotEmpty() == true
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

    Column(modifier = modifier.fillMaxWidth()) {
        // Video Stage
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF06080C))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .aspectRatio(project.aspectRatio.ratioFloat, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(project.backgroundColorArgb))
                    .border(1.dp, SurfaceStroke, RoundedCornerShape(12.dp))
                    .pointerInput(selectedClip?.id) {
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
                        Icon(Icons.Default.VideoCall, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onImportMediaClick,
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Import Videos / Photos", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        TextButton(onClick = onGenerateDemoClick) {
                            Text("Or Generate 3 Sample Clips", color = KeyframeGold, fontSize = 11.sp)
                        }
                    }
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
                            .then(
                                if (isSelected) Modifier.border(1.dp, ElectricCyan, RoundedCornerShape(6.dp))
                                else Modifier
                            )
                            .background(
                                Color(spec.backgroundColorArgb),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = spec.text,
                            fontSize = (spec.fontSizeSp * 0.72f * kotlin.math.abs(state.scaleX)).sp,
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
                        fontSize = (36f * kotlin.math.abs(state.scaleX)).sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = offsetX, y = offsetY)
                    )
                }
            }
        }

        // Precision Studio Transport Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelDark)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${formatTimecode(playheadMs, project.exportConfig.frameRate.fps)} / ${formatTimecode(project.totalDurationMs, project.exportConfig.frameRate.fps)}",
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onStepFrames(-1) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Frame", modifier = Modifier.size(19.dp))
                }
                FilledIconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = ElectricCyan,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { onStepFrames(1) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next Frame", modifier = Modifier.size(19.dp))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onQuickSplit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ContentCut, contentDescription = "Split", tint = TextPrimary, modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onQuickKeyframe, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Diamond, contentDescription = "Add Keyframe", tint = KeyframeGold, modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Redo, contentDescription = "Redo", modifier = Modifier.size(18.dp))
                }
            }
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
        // Timeline Mini-Toolbar (Zoom, Magnetic, Snapping, +Media)
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
                Text("${(zoom * 100).roundToInt()}%", fontSize = 10.sp, color = TextSecondary, modifier = Modifier.align(Alignment.CenterVertically))
                IconButton(onClick = { onZoomChange((zoom * 1.25f).coerceAtMost(5.0f)) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", modifier = Modifier.size(15.dp))
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
                        .verticalScroll(rememberScrollState())
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
                val verticalScroll = rememberScrollState()
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
                        // Time Ruler (Tap anywhere to scrub!)
                        TimeRulerBar(
                            totalSeconds = totalTimelineSec.toInt(),
                            dpPerSec = dpPerSec,
                            onSeekMs = onSeekMs
                        )

                        // Each Track Lane
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

                    // Live Playhead Vertical Indicator overlaid at playheadMs position
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
    onSeekMs: (Long) -> Unit
) {
    val density = LocalDensity.current
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(Color(0xFF141824))
            .pointerInput(dpPerSec) {
                detectDragGestures { change, _ ->
                    val pxPerSec = with(density) { dpPerSec.dp.toPx() }
                    val targetMs = ((change.position.x / pxPerSec) * 1000f).toLong().coerceAtLeast(0L)
                    onSeekMs(targetMs)
                }
            }
            .clickable { }
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
                // Video thumbnail strip
                if (clip.mediaKind == MediaKind.VIDEO || clip.mediaKind == MediaKind.IMAGE) {
                    AsyncImage(
                        model = clip.sourceUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.42f,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Real Audio Waveform Canvas
                if (clip.waveformPeaks.isNotEmpty() && track.type.isAudio) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                        val stepX = size.width / clip.waveformPeaks.size.coerceAtLeast(1)
                        val midY = size.height / 2f
                        clip.waveformPeaks.forEachIndexed { wIdx, amp ->
                            val x = wIdx * stepX
                            val barH = (amp * midY * 0.9f).coerceAtLeast(2f)
                            drawLine(
                                color = EmeraldBeat.copy(alpha = 0.65f),
                                start = Offset(x, midY - barH),
                                end = Offset(x, midY + barH),
                                strokeWidth = max(1.5f, stepX * 0.6f)
                            )
                        }
                    }
                }

                // Clip Title + Active Badges (FX / LUT / Speed / Freeze)
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
                    if (clip.effects.isNotEmpty()) {
                        Text(
                            text = "FX:${clip.effects.size}",
                            fontSize = 8.sp,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Keyframe Diamonds & Beat Dots along bottom of clip
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

            // Transition Junction Node `[⇄]` between adjacent Main Video clips
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

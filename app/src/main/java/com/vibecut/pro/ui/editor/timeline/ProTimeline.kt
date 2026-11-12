package com.vibecut.pro.ui.editor.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.ui.theme.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * ZERO multi-track timeline.
 *
 * Real interaction contract:
 *  - Tap the ruler or an empty lane area → seek.
 *  - Drag the ruler → scrub (preview follows, ticks pause while scrubbing).
 *  - Pinch → zoom the time axis around the pinch centroid.
 *  - Horizontal drag on empty space → scroll (two shared scroll states keep ruler and lanes locked).
 *  - Drag a clip → move it in time; the magnetic main track re-orders instead of overlapping.
 *  - Drag the left/right edge of a selected clip → trim in/out.
 *  - Double-tap a clip → open its tool sheet.
 */
@Composable
fun ProTimeline(
    project: Project,
    playheadMs: Long,
    selectedClipId: String?,
    isPlaying: Boolean,
    viewport: TimelineViewportState,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrubEnd: () -> Unit,
    onSelectClip: (String) -> Unit,
    onDoubleTapClip: (String) -> Unit,
    onMoveClip: (String, Long) -> Unit,
    onTrimClip: (String, Long, Long) -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onToggleTrackLock: (String) -> Unit,
    onToggleTrackHidden: (String) -> Unit,
    onToggleTrackMute: (String) -> Unit,
    onAddToTrack: (TrackType) -> Unit,
    onOpenClipTools: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val totalMs = project.totalDurationMs.coerceAtLeast(1000L)
    val contentWidth = viewport.contentWidth(totalMs)
    val viewportWidthPx = with(density) { 300.dp.toPx() }

    // Keep the viewport state in sync with the shared scroll state (source of truth for gestures).
    LaunchedEffect(hScroll.value, viewport.pxPerSecond) {
        viewport.setScroll(hScroll.value.toFloat(), Float.MAX_VALUE)
    }

    // Auto-follow the playhead while playing.
    LaunchedEffect(playheadMs, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        val x = viewport.msToPx(playheadMs)
        if (x < hScroll.value || x > hScroll.value + viewportWidthPx * 2.6f) {
            hScroll.scrollTo((x - viewportWidthPx * 0.3f).roundToInt().coerceAtLeast(0))
        }
    }

    Column(modifier = modifier.fillMaxWidth().background(ZeroBackground)) {
        TimelineToolbar(
            project = project,
            viewport = viewport,
            playheadMs = playheadMs,
            selectedClipId = selectedClipId,
            onSplitAtPlayhead = onSplitAtPlayhead,
            onOpenClipTools = onOpenClipTools,
            onNudgePlayhead = { deltaMs ->
                // Goes through the scrub path so the decoder actually re-seeks (a raw state change
                // would move the line but leave the preview frame behind).
                onScrubStart()
                onSeek((playheadMs + deltaMs).coerceIn(0L, totalMs))
                onScrubEnd()
            },
            onFit = {
                val seconds = (totalMs / 1000f).coerceAtLeast(1f)
                viewport.setZoom((viewportWidthPx / seconds).coerceIn(10f, 400f))
                scope.launch { hScroll.scrollTo(0) }
            }
        )

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            TrackHeaders(
                project = project,
                selectedClipId = selectedClipId,
                vScroll = vScroll,
                onToggleTrackLock = onToggleTrackLock,
                onToggleTrackHidden = onToggleTrackHidden,
                onToggleTrackMute = onToggleTrackMute,
                onAddToTrack = onAddToTrack
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(viewport.pxPerSecond) {
                        detectTransformGestures { centroid, _, zoom, _ ->
                            if (abs(zoom - 1f) > 0.02f) {
                                // Zoom around the pinch centroid so the frame under the fingers stays put.
                                val focusMs = viewport.pxToMs(centroid.x + hScroll.value)
                                viewport.zoomBy(zoom)
                                scope.launch {
                                    hScroll.scrollTo(
                                        (viewport.msToPx(focusMs) - centroid.x).roundToInt().coerceAtLeast(0)
                                    )
                                }
                            }
                        }
                    }
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Ruler strip shares the horizontal scroll state with the lanes below.
                    Ruler(
                        viewport = viewport,
                        durationMs = totalMs,
                        width = contentWidth,
                        hScroll = hScroll,
                        onSeek = onSeek,
                        onScrubStart = onScrubStart,
                        onScrubEnd = onScrubEnd
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .horizontalScroll(hScroll)
                    ) {
                        Box(modifier = Modifier.width(contentWidth).fillMaxHeight()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(vScroll)
                            ) {
                                project.tracks.forEach { track ->
                                    TrackLane(
                                        track = track,
                                        viewport = viewport,
                                        selectedClipId = selectedClipId,
                                        width = contentWidth,
                                        onSelectClip = onSelectClip,
                                        onDoubleTapClip = onDoubleTapClip,
                                        onMoveClip = onMoveClip,
                                        onTrimClip = onTrimClip,
                                        onSeek = onSeek
                                    )
                                }
                                Spacer(Modifier.height(14.dp))
                            }
                            PlayheadOverlay(viewport = viewport, playheadMs = playheadMs)
                        }
                    }
                }
            }
        }
    }
}

/** 24dp minimum touch target with a real clickable layer (not a bare 12dp icon). */
@Composable
private fun ToolbarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    tint: Color = TextSecondary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) tint else TextTertiary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun TimelineToolbar(
    project: Project,
    viewport: TimelineViewportState,
    playheadMs: Long,
    selectedClipId: String?,
    onSplitAtPlayhead: () -> Unit,
    onOpenClipTools: () -> Unit,
    onNudgePlayhead: (Long) -> Unit,
    onFit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZeroSurface)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolbarIcon(
                Icons.Default.ContentCut,
                "Split at playhead",
                enabled = selectedClipId != null,
                tint = ZeroAccent,
                onClick = onSplitAtPlayhead
            )
            ToolbarIcon(
                Icons.Default.Tune,
                "Clip tools",
                enabled = selectedClipId != null,
                onClick = onOpenClipTools
            )
            // Frame-accurate playhead nudging and boundary jumps (the ruler also drags).
            ToolbarIcon(
                Icons.Default.FirstPage,
                "Jump to start",
                onClick = { onNudgePlayhead(-project.totalDurationMs) }
            )
            ToolbarIcon(Icons.Default.ChevronLeft, "Playhead back", onClick = { onNudgePlayhead(-500L) })
            ToolbarIcon(Icons.Default.ChevronRight, "Playhead forward", onClick = { onNudgePlayhead(500L) })
            ToolbarIcon(
                Icons.Default.LastPage,
                "Jump to end",
                onClick = { onNudgePlayhead(project.totalDurationMs) }
            )
            Text(
                text = formatTimecode(playheadMs, project.exportConfig.frameRate.fps),
                fontSize = 11.sp,
                color = ZeroAccent,
                fontWeight = FontWeight.Bold
            )
            Text("/ ${formatDurationShort(project.totalDurationMs)}", fontSize = 10.sp, color = TextTertiary)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(
                Icons.Default.ZoomOut,
                contentDescription = "Zoom out",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp).clickable { viewport.zoomBy(0.72f) }
            )
            Text(
                "${viewport.pxPerSecond.roundToInt()}",
                fontSize = 9.sp,
                color = TextTertiary,
                modifier = Modifier.width(26.dp),
                textAlign = TextAlign.Center
            )
            Icon(
                Icons.Default.ZoomIn,
                contentDescription = "Zoom in",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp).clickable { viewport.zoomBy(1.4f) }
            )
            Icon(
                Icons.Default.VerticalAlignCenter,
                contentDescription = "Fit timeline to screen",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp).clickable(onClick = onFit)
            )
        }
    }
}

@Composable
private fun TrackHeaders(
    project: Project,
    selectedClipId: String?,
    vScroll: androidx.compose.foundation.ScrollState,
    onToggleTrackLock: (String) -> Unit,
    onToggleTrackHidden: (String) -> Unit,
    onToggleTrackMute: (String) -> Unit,
    onAddToTrack: (TrackType) -> Unit
) {
    Column(
        modifier = Modifier
            .width(78.dp)
            .fillMaxHeight()
            .background(ZeroSurface)
    ) {
        Spacer(
            modifier = Modifier
                .height(30.dp)
                .fillMaxWidth()
                .background(ZeroSurface)
        )
        Column(modifier = Modifier.fillMaxSize().verticalScroll(vScroll)) {
            project.tracks.forEach { track ->
                val hasSelected = track.clips.any { it.id == selectedClipId }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .background(if (hasSelected) ZeroSurfaceHigh else Color.Transparent)
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            track.type.label,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (track.isHidden) TextTertiary else TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .clickable { onAddToTrack(track.type) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add to ${track.type.label}",
                                tint = ZeroAccent,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (track.type.isVisual) {
                            ToolbarIcon(
                                if (track.isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                "Hide track",
                                tint = if (track.isHidden) ZeroCoral else TextTertiary,
                                onClick = { onToggleTrackHidden(track.id) }
                            )
                        }
                        if (track.type.isAudio) {
                            ToolbarIcon(
                                if (track.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                "Mute track",
                                tint = if (track.isMuted) ZeroCoral else TextTertiary,
                                onClick = { onToggleTrackMute(track.id) }
                            )
                        }
                        ToolbarIcon(
                            if (track.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            "Lock track",
                            tint = if (track.isLocked) ZeroAmber else TextTertiary,
                            onClick = { onToggleTrackLock(track.id) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun Ruler(
    viewport: TimelineViewportState,
    durationMs: Long,
    width: Dp,
    hScroll: androidx.compose.foundation.ScrollState,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrubEnd: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .horizontalScroll(hScroll)
    ) {
        Canvas(
            modifier = Modifier
                .width(width)
                .fillMaxHeight()
                .background(ZeroSurface)
                .pointerInput(viewport.pxPerSecond, durationMs) {
                    detectTapGestures { offset ->
                        onSeek(viewport.pxToMs(offset.x))
                    }
                }
                .pointerInput(viewport.pxPerSecond, durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            onScrubStart()
                            onSeek(viewport.pxToMs(offset.x))
                        },
                        onDragEnd = { onScrubEnd() },
                        onDragCancel = { onScrubEnd() }
                    ) { change, _ ->
                        onChangeConsume(change)
                        onSeek(viewport.pxToMs(change.position.x))
                    }
                }
        ) {
            val pxPerSec = viewport.pxPerSecond
            val stepSeconds = when {
                pxPerSec >= 260f -> 0.25f
                pxPerSec >= 130f -> 0.5f
                pxPerSec >= 65f -> 1f
                pxPerSec >= 32f -> 2f
                pxPerSec >= 16f -> 5f
                else -> 10f
            }
            val majorEvery = if (stepSeconds <= 0.5f) 4f else 2f
            val totalSeconds = size.width / pxPerSec
            var index = 0
            while (index * stepSeconds <= totalSeconds) {
                val t = index * stepSeconds
                val x = t * pxPerSec
                val isMajor = index % majorEvery.toInt() == 0
                drawLine(
                    color = if (isMajor) TextSecondary.copy(alpha = 0.65f) else ZeroStroke,
                    start = Offset(x, if (isMajor) size.height * 0.45f else size.height * 0.68f),
                    end = Offset(x, size.height),
                    strokeWidth = if (isMajor) 1.5f else 1f
                )
                index++
            }
            // Bottom hairline
            drawLine(
                color = ZeroStroke,
                start = Offset(0f, size.height - 1f),
                end = Offset(size.width, size.height - 1f),
                strokeWidth = 1f
            )
        }
    }
}

private fun onChangeConsume(change: androidx.compose.ui.input.pointer.PointerInputChange) {
    change.consume()
}

@Composable
private fun PlayheadOverlay(viewport: TimelineViewportState, playheadMs: Long) {
    val density = LocalDensity.current
    val xPx = viewport.msToPx(playheadMs)
    val xDp = with(density) { xPx.toDp() }
    Box(
        modifier = Modifier
            .offset(x = xDp)
            .width(1.5.dp)
            .fillMaxHeight()
            .background(ZeroAccent)
    )
    Box(
        modifier = Modifier
            .offset(x = xDp - 5.dp)
            .size(width = 11.5.dp, height = 9.dp)
            .clip(RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
            .background(ZeroAccent)
    )
}

@Composable
private fun TrackLane(
    track: Track,
    viewport: TimelineViewportState,
    selectedClipId: String?,
    width: Dp,
    onSelectClip: (String) -> Unit,
    onDoubleTapClip: (String) -> Unit,
    onMoveClip: (String, Long) -> Unit,
    onTrimClip: (String, Long, Long) -> Unit,
    onSeek: (Long) -> Unit
) {
    val laneColor = when (track.type) {
        TrackType.VIDEO_MAIN -> Color(0xFF0F141C)
        TrackType.VIDEO_PIP -> Color(0xFF121020)
        TrackType.TEXT -> Color(0xFF1A1410)
        TrackType.STICKER -> Color(0xFF1C1218)
        else -> Color(0xFF0C1714)
    }

    Box(
        modifier = Modifier
            .width(width)
            .height(46.dp)
            .background(laneColor)
            .pointerInput(track.id, viewport.pxPerSecond) {
                detectTapGestures { offset -> onSeek(viewport.pxToMs(offset.x)) }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.BottomStart)
                .background(ZeroStroke.copy(alpha = 0.45f))
        )
        track.clips.forEach { clip ->
            ClipBlock(
                clip = clip,
                track = track,
                viewport = viewport,
                isSelected = clip.id == selectedClipId,
                onSelect = { onSelectClip(clip.id) },
                onDoubleTap = { onDoubleTapClip(clip.id) },
                onMove = { newStart -> onMoveClip(clip.id, newStart) },
                onTrim = { s, e -> onTrimClip(clip.id, s, e) }
            )
        }
    }
}

@Composable
private fun ClipBlock(
    clip: TimelineClip,
    track: Track,
    viewport: TimelineViewportState,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDoubleTap: () -> Unit,
    onMove: (Long) -> Unit,
    onTrim: (Long, Long) -> Unit
) {
    val density = LocalDensity.current
    val widthPx = max(22f, viewport.msToPx(clip.timelineDurationMs))

    val tint = when (track.type) {
        TrackType.VIDEO_MAIN -> LaneVideo
        TrackType.VIDEO_PIP -> LaneOverlay
        TrackType.TEXT -> LaneText
        TrackType.STICKER -> LaneSticker
        TrackType.AUDIO_MUSIC -> LaneAudio
        TrackType.AUDIO_VOICEOVER -> LaneVoice
        TrackType.AUDIO_SFX -> LaneSfx
    }

    var dragStartMs by remember(clip.id, clip.startTimeOnTrackMs) { mutableStateOf(clip.startTimeOnTrackMs) }
    var trimSide by remember(clip.id) { mutableIntStateOf(0) }
    var trimDeltaMs by remember(clip.id) { mutableLongStateOf(0L) }
    var isDragging by remember(clip.id) { mutableStateOf(false) }

    val displayStartMs = if (trimSide == 0) dragStartMs else clip.startTimeOnTrackMs
    val startDp = with(density) { viewport.msToPx(displayStartMs).toDp() }
    val widthDp = with(density) { widthPx.toDp() }

    Box(
        modifier = Modifier
            .offset(x = startDp)
            .width(widthDp)
            .fillMaxHeight()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(tint)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) ZeroAccent else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(6.dp)
            )
            .pointerInput(clip.id, viewport.pxPerSecond) {
                detectTapGestures(
                    onTap = { onSelect() },
                    onDoubleTap = { onDoubleTap() }
                )
            }
            .pointerInput(clip.id, viewport.pxPerSecond) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onSelect()
                        isDragging = true
                        dragStartMs = clip.startTimeOnTrackMs
                        trimSide = when {
                            offset.x < with(density) { 16.dp.toPx() } -> -1
                            offset.x > size.width - with(density) { 16.dp.toPx() } -> 1
                            else -> 0
                        }
                        trimDeltaMs = 0L
                    },
                    onDragEnd = {
                        when (trimSide) {
                            0 -> onMove(dragStartMs)
                            -1 -> {
                                val speed = clip.clampedSpeed
                                val newTrimStart = (clip.trimStartMs + (trimDeltaMs * speed).roundToLong())
                                    .coerceIn(0L, clip.trimEndMs - 100L)
                                onTrim(newTrimStart, clip.trimEndMs)
                            }
                            1 -> {
                                val speed = clip.clampedSpeed
                                val newTrimEnd = (clip.effectiveTrimEndMs + (trimDeltaMs * speed).roundToLong())
                                    .coerceIn(clip.trimStartMs + 100L, max(clip.trimStartMs + 100L, clip.sourceDurationMs))
                                onTrim(clip.trimStartMs, newTrimEnd)
                            }
                        }
                        isDragging = false
                        trimSide = 0
                        trimDeltaMs = 0L
                    },
                    onDragCancel = {
                        isDragging = false
                        trimSide = 0
                        trimDeltaMs = 0L
                        dragStartMs = clip.startTimeOnTrackMs
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val deltaMs = ((dragAmount / viewport.pxPerSecond) * 1000f).roundToLong()
                    if (trimSide == 0) {
                        dragStartMs = (dragStartMs + deltaMs).coerceAtLeast(0L)
                    } else {
                        trimDeltaMs += deltaMs
                    }
                }
            }
    ) {
        if (clip.mediaKind == MediaKind.VIDEO || clip.mediaKind == MediaKind.IMAGE) {
            AsyncImage(
                model = clip.thumbnailUri ?: clip.sourceUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.42f,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (clip.waveformPeaks.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                val stepX = size.width / clip.waveformPeaks.size.coerceAtLeast(1)
                val midY = if (track.type.isAudio) size.height * 0.5f else size.height * 0.78f
                val ampScale = if (track.type.isAudio) 0.45f else 0.16f
                clip.waveformPeaks.forEachIndexed { i, amp ->
                    val x = i * stepX
                    val h = (amp * size.height * ampScale).coerceAtLeast(1f)
                    drawLine(
                        color = ZeroAccent.copy(alpha = if (track.type.isAudio) 0.6f else 0.3f),
                        start = Offset(x, midY - h),
                        end = Offset(x, midY + h),
                        strokeWidth = max(1f, stepX * 0.6f)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = clip.textSpec?.text ?: ((if (track.type.isAudio) "♪ " else "") + clip.sourceName),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (clip.effects.any { it.enabled }) Text("FX", fontSize = 8.sp, color = ZeroAccent, fontWeight = FontWeight.Bold)
                if (!clip.colorGrade.isIdentity) Text("CC", fontSize = 8.sp, color = ZeroAmber, fontWeight = FontWeight.Bold)
                if (clip.hasSpeedRamp || abs(clip.clampedSpeed - 1f) > 0.05f) {
                    Text("${"%.2f".format(clip.clampedSpeed)}x", fontSize = 8.sp, color = ZeroViolet, fontWeight = FontWeight.Bold)
                }
                if (clip.isReversed) Text("◀", fontSize = 8.sp, color = ZeroCoral, fontWeight = FontWeight.Bold)
            }
        }

        if (isSelected) {
            // Trim grabbers: the visual bar is 4dp but the gesture area is the outer 16dp of the clip.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(16.dp)
                    .fillMaxHeight()
                    .background(ZeroAccent.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp)
                    .background(ZeroAccent)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(16.dp)
                    .fillMaxHeight()
                    .background(ZeroAccent.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp)
                    .background(ZeroAccent)
            )
            clip.transitionIn?.let { trans ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .clip(RoundedCornerShape(3.dp))
                        .background(ZeroViolet)
                        .padding(horizontal = 3.dp)
                ) {
                    Text(trans.type.label.take(4), fontSize = 7.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TimelineEmptyHint(onImport: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZeroSurface)
            .clickable(onClick = onImport)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape).background(ZeroAccent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = ZeroAccent, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("Add video, photos or audio", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Your clips appear here on the timeline", fontSize = 10.sp, color = TextTertiary)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(18.dp))
    }
}

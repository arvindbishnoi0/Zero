package com.vibecut.pro.ui.editor.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.timeline.TimelineEngine
import com.vibecut.pro.engine.timeline.TimelineViewportMath
import com.vibecut.pro.engine.timeline.TimelineViewportMath.DragMode
import com.vibecut.pro.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * ZERO multi-track timeline.
 *
 * Interaction contract:
 *  - Tap the ruler or an empty lane area → seek.
 *  - Drag the ruler → scrub (the preview follows frame by frame).
 *  - **Two-finger** pinch → zoom around the pinch centroid; one finger always scrolls.
 *  - Drag a clip → move it, with a live ghost that follows the finger exactly.
 *  - Drag the left/right edge of a **selected** clip → trim, previewed live while dragging.
 *  - Double-tap a clip → open its inspector.
 *
 * All geometry goes through [TimelineViewportMath] in raw pixels and is converted to dp exactly
 * once, at the layout boundary.
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

    // One single conversion from the px world to the dp world.
    val contentWidthPx = viewport.contentWidthPx(totalMs)
    val contentWidth: Dp = with(density) { contentWidthPx.toDp() }

    // The viewport width is measured, never assumed (it used to be hard-coded to 300dp, which
    // made "fit to screen" and playhead-follow wrong on every device).
    LaunchedEffect(hScroll.value) {
        viewport.setScroll(hScroll.value.toFloat(), Float.MAX_VALUE)
    }

    // Auto-follow the playhead while playing, with a dead zone so it does not jitter.
    LaunchedEffect(playheadMs, isPlaying, viewport.viewportWidthPx) {
        if (!isPlaying || viewport.viewportWidthPx <= 1f) return@LaunchedEffect
        viewport.followPlayheadScroll(playheadMs, totalMs)?.let { target ->
            hScroll.scrollTo(target.roundToInt().coerceAtLeast(0))
        }
    }

    Column(modifier = modifier.fillMaxWidth().background(ZeroBackground)) {
        TimelineToolbar(
            project = project,
            viewport = viewport,
            playheadMs = playheadMs,
            canSplit = project.findMainClipAt(playheadMs) != null || selectedClipId != null,
            canOpenTools = selectedClipId != null,
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
                viewport.fitToWidth(totalMs)
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
                    .onSizeChanged { viewport.setViewportWidth(it.width.toFloat()) }
                    // Pinch-only: a single finger is left untouched so the lanes keep scrolling.
                    .pointerInput(Unit) {
                        detectPinchZoomOnly { centroid, zoom ->
                            val nextScroll = viewport.pinchZoom(centroid.x, zoom, totalMs)
                            scope.launch { hScroll.scrollTo(nextScroll.roundToInt().coerceAtLeast(0)) }
                        }
                    }
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Ruler(
                        viewport = viewport,
                        frameRate = project.exportConfig.frameRate.fps,
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
                                        snappingEnabled = project.snappingEnabled,
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

/**
 * Zoom detector that **only** reacts to two or more pointers.
 *
 * [androidx.compose.foundation.gestures.detectTransformGestures] swallows single-finger pans,
 * which made the timeline impossible to scroll. This variant never consumes a one-finger gesture,
 * so `horizontalScroll`/`verticalScroll` keep working underneath.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectPinchZoomOnly(
    onZoom: (centroid: Offset, zoom: Float) -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoomAccumulator = 1f
        var pinching = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) break
            val pressed = event.changes.count { it.pressed }
            if (pressed < 2) {
                // Hand the gesture straight back to the scrollables.
                pinching = false
                zoomAccumulator = 1f
                continue
            }
            val zoomChange = event.calculateZoom()
            if (!pinching) {
                zoomAccumulator *= zoomChange
                if (abs(zoomAccumulator - 1f) > 0.04f) pinching = true
            }
            if (pinching && zoomChange != 1f && zoomChange.isFinite() && zoomChange > 0f) {
                onZoom(event.calculateCentroid(useCurrent = true), zoomChange)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    }
}

/** 26dp touch target with a real clickable layer (not a bare 12dp icon). */
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
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
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
    canSplit: Boolean,
    canOpenTools: Boolean,
    onSplitAtPlayhead: () -> Unit,
    onOpenClipTools: () -> Unit,
    onNudgePlayhead: (Long) -> Unit,
    onFit: () -> Unit
) {
    val frameMs = (1000f / project.exportConfig.frameRate.fps.coerceAtLeast(1)).toLong().coerceAtLeast(1L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZeroSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            ToolbarIcon(
                Icons.Default.ContentCut,
                "Split at playhead",
                enabled = canSplit,
                tint = ZeroAccent,
                onClick = onSplitAtPlayhead
            )
            ToolbarIcon(Icons.Default.Tune, "Clip inspector", enabled = canOpenTools, onClick = onOpenClipTools)
            ToolbarIcon(Icons.Default.FirstPage, "Jump to start") { onNudgePlayhead(-project.totalDurationMs) }
            // Frame-accurate stepping, not a blind 500ms jump.
            ToolbarIcon(Icons.Default.ChevronLeft, "Previous frame") { onNudgePlayhead(-frameMs) }
            ToolbarIcon(Icons.Default.ChevronRight, "Next frame") { onNudgePlayhead(frameMs) }
            ToolbarIcon(Icons.Default.LastPage, "Jump to end") { onNudgePlayhead(project.totalDurationMs) }
            Spacer(Modifier.width(3.dp))
            Text(
                text = formatTimecode(playheadMs, project.exportConfig.frameRate.fps),
                fontSize = 11.sp,
                color = ZeroAccent,
                fontWeight = FontWeight.Bold
            )
            Text(" / ${formatDurationShort(project.totalDurationMs)}", fontSize = 10.sp, color = TextTertiary)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            ToolbarIcon(Icons.Default.ZoomOut, "Zoom out") { viewport.zoomBy(0.72f) }
            Text(
                "${viewport.pxPerSecond.roundToInt()}",
                fontSize = 9.sp,
                color = TextTertiary,
                modifier = Modifier.width(26.dp),
                textAlign = TextAlign.Center
            )
            ToolbarIcon(Icons.Default.ZoomIn, "Zoom in") { viewport.zoomBy(1.4f) }
            ToolbarIcon(Icons.Default.FitScreen, "Fit timeline to screen", onClick = onFit)
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
        Spacer(modifier = Modifier.height(RULER_HEIGHT).fillMaxWidth().background(ZeroSurface))
        Column(modifier = Modifier.fillMaxSize().verticalScroll(vScroll)) {
            project.tracks.forEach { track ->
                val hasSelected = track.clips.any { it.id == selectedClipId }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LANE_HEIGHT)
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (track.type.isVisual) {
                            ToolbarIcon(
                                if (track.isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                "Hide track",
                                tint = if (track.isHidden) ZeroCoral else TextTertiary
                            ) { onToggleTrackHidden(track.id) }
                        }
                        if (track.type.isAudio) {
                            ToolbarIcon(
                                if (track.isMuted) Icons.AutoMirrored.Filled.VolumeOff
                                else Icons.AutoMirrored.Filled.VolumeUp,
                                "Mute track",
                                tint = if (track.isMuted) ZeroCoral else TextTertiary
                            ) { onToggleTrackMute(track.id) }
                        }
                        ToolbarIcon(
                            if (track.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            "Lock track",
                            tint = if (track.isLocked) ZeroAmber else TextTertiary
                        ) { onToggleTrackLock(track.id) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

private val RULER_HEIGHT = 32.dp
private val LANE_HEIGHT = 46.dp

@Composable
private fun Ruler(
    viewport: TimelineViewportState,
    frameRate: Int,
    width: Dp,
    hScroll: androidx.compose.foundation.ScrollState,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrubEnd: () -> Unit
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = androidx.compose.ui.text.TextStyle(
        fontSize = 8.sp,
        color = TextSecondary,
        fontWeight = FontWeight.Medium
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RULER_HEIGHT)
            .horizontalScroll(hScroll)
    ) {
        Canvas(
            modifier = Modifier
                .width(width)
                .fillMaxHeight()
                .background(ZeroSurface)
                .pointerInput(Unit) {
                    detectTapGestures { offset -> onSeek(viewport.pxToMs(offset.x)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            onScrubStart()
                            onSeek(viewport.pxToMs(offset.x))
                        },
                        onDragEnd = { onScrubEnd() },
                        onDragCancel = { onScrubEnd() }
                    ) { change, _ ->
                        change.consume()
                        onSeek(viewport.pxToMs(change.position.x))
                    }
                }
        ) {
            val pxPerSec = viewport.pxPerSecond
            val stepSeconds = TimelineViewportMath.tickStepSeconds(pxPerSec)
            val majorEvery = TimelineViewportMath.majorTickEvery(stepSeconds)
            val totalSeconds = size.width / pxPerSec
            var index = 0
            while (index * stepSeconds <= totalSeconds) {
                val t = index * stepSeconds
                val x = t * pxPerSec
                val isMajor = index % majorEvery == 0
                drawLine(
                    color = if (isMajor) TextSecondary.copy(alpha = 0.7f) else ZeroStroke,
                    start = Offset(x, if (isMajor) size.height * 0.46f else size.height * 0.72f),
                    end = Offset(x, size.height),
                    strokeWidth = if (isMajor) 1.5f else 1f
                )
                if (isMajor) {
                    // Real time labels — a ruler without numbers is just stripes.
                    val label = formatRulerLabel((t * 1000f).toLong(), stepSeconds, frameRate)
                    val measured = textMeasurer.measure(label, labelStyle)
                    if (x + measured.size.width < size.width) {
                        drawText(
                            textMeasurer = textMeasurer,
                            text = label,
                            style = labelStyle,
                            topLeft = Offset(x + 3f, 2f)
                        )
                    }
                }
                index++
            }
            drawLine(
                color = ZeroStroke,
                start = Offset(0f, size.height - 1f),
                end = Offset(size.width, size.height - 1f),
                strokeWidth = 1f
            )
        }
    }
}

private fun formatRulerLabel(ms: Long, stepSeconds: Float, frameRate: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (stepSeconds < 1f) {
        val frames = ((ms % 1000) * frameRate / 1000f).roundToInt()
        "%d:%02d.%02d".format(minutes, seconds, frames)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun PlayheadOverlay(viewport: TimelineViewportState, playheadMs: Long) {
    val density = LocalDensity.current
    val xDp = with(density) { viewport.msToPx(playheadMs).toDp() }
    Box(
        modifier = Modifier
            .offset(x = xDp)
            .width(1.5.dp)
            .fillMaxHeight()
            .background(ZeroAccent)
    )
    Box(
        modifier = Modifier
            .offset(x = xDp - 5.75.dp)
            .size(width = 11.5.dp, height = 9.dp)
            .clip(RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
            .background(ZeroAccent)
    )
}

/**
 * One track lane.
 *
 * All drag gestures live **here**, on the lane, not on the individual clip blocks. The lane never
 * moves, so the pointer coordinate space is stable: previously the clip itself carried the
 * `pointerInput` *and* an `offset()` that the drag updated, which shifted the pointer origin by
 * `-delta` on every frame and made dragged clips oscillate violently.
 */
@Composable
private fun TrackLane(
    track: Track,
    viewport: TimelineViewportState,
    selectedClipId: String?,
    snappingEnabled: Boolean,
    width: Dp,
    onSelectClip: (String) -> Unit,
    onDoubleTapClip: (String) -> Unit,
    onMoveClip: (String, Long) -> Unit,
    onTrimClip: (String, Long, Long) -> Unit,
    onSeek: (Long) -> Unit
) {
    val density = LocalDensity.current
    val laneColor = when (track.type) {
        TrackType.VIDEO_MAIN -> Color(0xFF0F141C)
        TrackType.VIDEO_PIP -> Color(0xFF121020)
        TrackType.TEXT -> Color(0xFF1A1410)
        TrackType.STICKER -> Color(0xFF1C1218)
        else -> Color(0xFF0C1714)
    }

    // Live drag state for this lane (only one clip can be dragged at a time).
    var dragClipId by remember(track.id) { mutableStateOf<String?>(null) }
    var dragMode by remember(track.id) { mutableStateOf(DragMode.NONE) }
    var dragDeltaMs by remember(track.id) { mutableLongStateOf(0L) }

    // The gesture lambdas run outside composition, so always read the newest data.
    val latestTrack by rememberUpdatedState(track)
    val latestSelection by rememberUpdatedState(selectedClipId)
    val handlePx = with(density) { 20.dp.toPx() }
    val minClipPx = with(density) { 22.dp.toPx() }

    fun clipAt(x: Float): TimelineClip? = latestTrack.clips
        .sortedByDescending { it.startTimeOnTrackMs }
        .firstOrNull { c ->
            val start = viewport.msToPx(c.startTimeOnTrackMs)
            val end = start + max(minClipPx, viewport.msToPx(c.timelineDurationMs))
            x in start..end
        }

    Box(
        modifier = Modifier
            .width(width)
            .height(LANE_HEIGHT)
            .background(laneColor)
            .pointerInput(track.id) {
                detectTapGestures(
                    onTap = { offset ->
                        val hit = clipAt(offset.x)
                        if (hit != null) onSelectClip(hit.id) else onSeek(viewport.pxToMs(offset.x))
                    },
                    onDoubleTap = { offset ->
                        clipAt(offset.x)?.let { onDoubleTapClip(it.id) }
                    }
                )
            }
            .pointerInput(track.id) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val hit = clipAt(offset.x)
                        if (hit == null || latestTrack.isLocked) {
                            dragClipId = null
                            dragMode = DragMode.NONE
                            return@detectHorizontalDragGestures
                        }
                        onSelectClip(hit.id)
                        val startPx = viewport.msToPx(hit.startTimeOnTrackMs)
                        val widthPx = max(minClipPx, viewport.msToPx(hit.timelineDurationMs))
                        dragClipId = hit.id
                        dragMode = TimelineViewportMath.classifyTouch(
                            localXPx = offset.x - startPx,
                            clipWidthPx = widthPx,
                            handlePx = handlePx,
                            selected = hit.id == latestSelection
                        )
                        dragDeltaMs = 0L
                    },
                    onDragEnd = {
                        val id = dragClipId
                        val clip = latestTrack.clips.firstOrNull { it.id == id }
                        if (id != null && clip != null && dragDeltaMs != 0L) {
                            commitDrag(clip, dragMode, dragDeltaMs, onMoveClip, onTrimClip)
                        }
                        dragClipId = null
                        dragMode = DragMode.NONE
                        dragDeltaMs = 0L
                    },
                    onDragCancel = {
                        dragClipId = null
                        dragMode = DragMode.NONE
                        dragDeltaMs = 0L
                    }
                ) { change, dragAmount ->
                    if (dragClipId == null) return@detectHorizontalDragGestures
                    change.consume()
                    dragDeltaMs += viewport.pxDeltaToMs(dragAmount)
                }
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
            val dragging = clip.id == dragClipId && dragMode != DragMode.NONE
            val (previewStart, previewDuration) = if (dragging) {
                TimelineViewportMath.previewGeometry(
                    mode = dragMode,
                    originalStartMs = clip.startTimeOnTrackMs,
                    originalDurationMs = clip.timelineDurationMs,
                    deltaMs = dragDeltaMs,
                    minDurationMs = TimelineEngine.MIN_CLIP_DURATION_MS,
                    maxExtendStartMs = (clip.trimStartMs / clip.clampedSpeed).toLong(),
                    maxExtendEndMs = ((clip.sourceDurationMs - clip.effectiveTrimEndMs) / clip.clampedSpeed).toLong()
                )
            } else {
                clip.startTimeOnTrackMs to clip.timelineDurationMs
            }
            ClipBlock(
                clip = clip,
                track = track,
                viewport = viewport,
                isSelected = clip.id == selectedClipId,
                isDragging = dragging,
                startMs = previewStart,
                durationMs = previewDuration,
                snappingEnabled = snappingEnabled
            )
        }
    }
}

/** Translates a finished drag into the right engine call. */
private fun commitDrag(
    clip: TimelineClip,
    mode: DragMode,
    deltaMs: Long,
    onMoveClip: (String, Long) -> Unit,
    onTrimClip: (String, Long, Long) -> Unit
) {
    when (mode) {
        DragMode.MOVE -> onMoveClip(clip.id, (clip.startTimeOnTrackMs + deltaMs).coerceAtLeast(0L))
        DragMode.TRIM_START -> {
            // Timeline delta -> source delta goes through the clip's own mapping so speed ramps
            // and reversed clips trim to the frame the user actually sees.
            val sourceDelta = (deltaMs * clip.clampedSpeed).toLong()
            val newTrimStart = (clip.trimStartMs + sourceDelta)
                .coerceIn(0L, clip.effectiveTrimEndMs - TimelineEngine.MIN_CLIP_DURATION_MS)
            onTrimClip(clip.id, newTrimStart, clip.effectiveTrimEndMs)
        }
        DragMode.TRIM_END -> {
            val sourceDelta = (deltaMs * clip.clampedSpeed).toLong()
            val maxEnd = max(clip.trimStartMs + TimelineEngine.MIN_CLIP_DURATION_MS, clip.sourceDurationMs)
            val newTrimEnd = (clip.effectiveTrimEndMs + sourceDelta)
                .coerceIn(clip.trimStartMs + TimelineEngine.MIN_CLIP_DURATION_MS, maxEnd)
            onTrimClip(clip.id, clip.trimStartMs, newTrimEnd)
        }
        DragMode.NONE -> Unit
    }
}

@Composable
private fun ClipBlock(
    clip: TimelineClip,
    track: Track,
    viewport: TimelineViewportState,
    isSelected: Boolean,
    isDragging: Boolean,
    startMs: Long,
    durationMs: Long,
    snappingEnabled: Boolean
) {
    val density = LocalDensity.current
    val tint = when (track.type) {
        TrackType.VIDEO_MAIN -> LaneVideo
        TrackType.VIDEO_PIP -> LaneOverlay
        TrackType.TEXT -> LaneText
        TrackType.STICKER -> LaneSticker
        TrackType.AUDIO_MUSIC -> LaneAudio
        TrackType.AUDIO_VOICEOVER -> LaneVoice
        TrackType.AUDIO_SFX -> LaneSfx
    }

    val startDp = with(density) { viewport.msToPx(startMs).toDp() }
    val widthDp = with(density) { max(22f, viewport.msToPx(durationMs)).toDp() }

    Box(
        modifier = Modifier
            .offset(x = startDp)
            .width(widthDp)
            .fillMaxHeight()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isDragging) tint.copy(alpha = 0.82f) else tint)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = when {
                    isDragging -> ZeroAmber
                    isSelected -> ZeroAccent
                    else -> Color.White.copy(alpha = 0.14f)
                },
                shape = RoundedCornerShape(6.dp)
            )
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
        // Beat markers make music-synced cutting possible at a glance.
        if (clip.beatMarkersMs.isNotEmpty() && snappingEnabled) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val spanMs = clip.timelineDurationMs.coerceAtLeast(1L)
                clip.beatMarkersMs.forEach { beat ->
                    val fraction = beat.toFloat() / spanMs
                    if (fraction in 0f..1f) {
                        val x = fraction * size.width
                        drawLine(
                            color = ZeroAmber.copy(alpha = 0.55f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f
                        )
                    }
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
                if (clip.effects.any { it.enabled }) {
                    Text("FX", fontSize = 8.sp, color = ZeroAccent, fontWeight = FontWeight.Bold)
                }
                if (!clip.colorGrade.isIdentity) {
                    Text("CC", fontSize = 8.sp, color = ZeroAmber, fontWeight = FontWeight.Bold)
                }
                if (clip.keyframes.isNotEmpty()) {
                    Text("◆", fontSize = 8.sp, color = ZeroAmber, fontWeight = FontWeight.Bold)
                }
                if (clip.hasSpeedRamp) {
                    Text("RAMP", fontSize = 7.sp, color = ZeroViolet, fontWeight = FontWeight.Bold)
                } else if (abs(clip.clampedSpeed - 1f) > 0.05f) {
                    Text(
                        "${"%.2f".format(clip.clampedSpeed)}x",
                        fontSize = 8.sp,
                        color = ZeroViolet,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (clip.isReversed) Text("◀", fontSize = 8.sp, color = ZeroCoral, fontWeight = FontWeight.Bold)
            }
        }

        if (isSelected) {
            TrimHandle(Alignment.CenterStart)
            TrimHandle(Alignment.CenterEnd)
        }
        clip.transitionIn?.let { trans -> TransitionChip(trans, Alignment.BottomStart) }
        clip.transitionOut?.let { trans -> TransitionChip(trans, Alignment.BottomEnd) }
    }
}

@Composable
private fun BoxScope.TrimHandle(alignment: Alignment) {
    Box(
        modifier = Modifier
            .align(alignment)
            .width(14.dp)
            .fillMaxHeight()
            .background(ZeroAccent.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight(0.55f)
                .clip(RoundedCornerShape(2.dp))
                .background(ZeroAccent)
        )
    }
}

@Composable
private fun BoxScope.TransitionChip(transition: ClipTransition, alignment: Alignment) {
    Box(
        modifier = Modifier
            .align(alignment)
            .padding(1.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(ZeroViolet)
            .padding(horizontal = 3.dp)
    ) {
        Text(
            transition.type.label.take(4),
            fontSize = 7.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
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

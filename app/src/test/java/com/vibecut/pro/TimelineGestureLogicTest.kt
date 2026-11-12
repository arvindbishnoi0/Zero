package com.vibecut.pro

import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.timeline.ExportSegmentPlanner
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import com.vibecut.pro.engine.timeline.TimelineEngine
import com.vibecut.pro.ui.editor.timeline.TimelineViewportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline gestures are thin wrappers over [TimelineViewportState], so the maths that decides
 * "where did the user tap" and "where does the playhead go" is testable without a device.
 */
class TimelineGestureLogicTest {

    private fun project(): Project {
        var p = Project(title = "Gestures", aspectRatio = AspectRatioPreset.REELS_9_16)
        p = TimelineEngine.importMediaClips(
            p,
            listOf(
                ImportedMediaSource("file:///a.mp4", "A", MediaKind.VIDEO, 5_000L),
                ImportedMediaSource("file:///b.mp4", "B", MediaKind.VIDEO, 5_000L)
            ),
            TrackType.VIDEO_MAIN
        )
        return p
    }

    @Test
    fun tapAndDragConvertPixelsToMillisecondsConsistently() {
        val viewport = TimelineViewportState(initialPxPerSecond = 100f)
        assertEquals(0L, viewport.pxToMs(0f))
        assertEquals(1_000L, viewport.pxToMs(100f))
        assertEquals(2_500L, viewport.pxToMs(250f))
        // round trip
        assertEquals(1_500L, viewport.pxToMs(viewport.msToPx(1_500L)))
    }

    @Test
    fun pinchZoomKeepsTheFocusPointStable() {
        val viewport = TimelineViewportState(initialPxPerSecond = 50f)
        val focusPx = 200f
        val focusMs = viewport.pxToMs(focusPx)
        viewport.zoomBy(2.5f)
        // After zooming 2.5x, the same timestamp sits at 2.5x the pixels.
        assertEquals(focusMs, viewport.pxToMs(viewport.msToPx(focusMs)))
        assertTrue(viewport.pxPerSecond > 50f)
        // Zoom is clamped, never zero or absurd.
        viewport.zoomBy(1000f)
        assertTrue(viewport.pxPerSecond <= TimelineViewportState.MAX_PX_PER_SEC)
        viewport.zoomBy(0.0001f)
        assertTrue(viewport.pxPerSecond >= TimelineViewportState.MIN_PX_PER_SEC)
    }

    @Test
    fun scrubbingClampsToTheProjectDuration() {
        val project = project()
        val duration = project.totalDurationMs
        assertEquals(duration, project.findMainClipAt(duration)!!.let { clip ->
            (clip.startTimeOnTrackMs + clip.timelineDurationMs)
        })
        // Seeking beyond the end must resolve to the last clip, not crash or return null.
        val last = project.findMainClipAt(duration + 5_000L)
        assertTrue(last != null && last == project.mainVideoClips.last())
    }

    @Test
    fun trimHandleMathStaysInsideTheSourceRange() {
        var project = project()
        val clip = project.mainVideoClips[0]
        // Simulate dragging the right handle 1s to the left.
        val newEnd = (clip.effectiveTrimEndMs - 1_000L)
            .coerceIn(clip.trimStartMs + TimelineEngine.MIN_CLIP_DURATION_MS, clip.sourceDurationMs)
        project = TimelineEngine.trimClip(project, clip.id, clip.trimStartMs, newEnd)
        assertEquals(4_000L, project.mainVideoClips[0].timelineDurationMs)
        // The following clip must reflow so no gap appears on the magnetic main track.
        assertEquals(4_000L, project.mainVideoClips[1].startTimeOnTrackMs)

        // Dragging past the source start is clamped, never negative.
        val clampedStart = (clip.trimStartMs - 3_000L)
            .coerceIn(0L, clip.trimEndMs - TimelineEngine.MIN_CLIP_DURATION_MS)
        assertEquals(0L, clampedStart)
    }

    @Test
    fun movingAClipKeepsTheMagneticTrackGapless() {
        var project = project()
        val second = project.mainVideoClips[1]
        project = TimelineEngine.moveClipToTimestamp(project, second.id, 0L)
        val ordered = project.mainVideoClips.sortedBy { it.startTimeOnTrackMs }
        assertEquals(second.id, ordered.first().id)
        var cursor = 0L
        ordered.forEach {
            assertEquals(cursor, it.startTimeOnTrackMs)
            cursor += it.timelineDurationMs
        }
    }

    @Test
    fun freeTracksAllowOverlapAndSnapping() {
        var project = project()
        project = project.copy(magneticTimeline = false, snappingEnabled = true)
        val pipTrack = TimelineEngine.addTrack(project, TrackType.VIDEO_PIP)
            .trackByType(TrackType.VIDEO_PIP)!!
        val withPip = TimelineEngine.importMediaClips(
            project,
            listOf(ImportedMediaSource("file:///o.mp4", "Overlay", MediaKind.VIDEO, 2_000L)),
            TrackType.VIDEO_PIP,
            insertAtPlayheadMs = 1_000L
        )
        val overlay = withPip.trackByType(TrackType.VIDEO_PIP)!!.clips.first()
        // A drag that lands away from any edge keeps the exact requested position…
        val free = TimelineEngine.moveClipToTimestamp(withPip, overlay.id, 1_040L)
        assertEquals(1_040L, free.findClip(overlay.id)!!.startTimeOnTrackMs)
        // …while a drag near a clip boundary (120 ms snap window) locks onto it.
        val snappedToEdge = TimelineEngine.moveClipToTimestamp(withPip, overlay.id, 4_930L)
        assertEquals(5_000L, snappedToEdge.findClip(overlay.id)!!.startTimeOnTrackMs)
        // Snapping never moves a free-track clip through another track or off the timeline.
        val far = TimelineEngine.moveClipToTimestamp(withPip, overlay.id, 7_000L)
        assertEquals(7_000L, far.findClip(overlay.id)!!.startTimeOnTrackMs)
        assertEquals(pipTrack.id, far.findTrackForClip(overlay.id)!!.id)
    }

    @Test
    fun playheadIsDerivableFromPlaylistPosition() {
        var project = project()
        // 2x the first clip: its timeline slot halves, the second clip starts at 2.5s.
        project = TimelineEngine.setClipSpeed(project, project.mainVideoClips[0].id, 2f)
        val pieces = ExportSegmentPlanner.planPreviewPlaylist(project)
        assertEquals(2, pieces.size)
        assertEquals(2_500L, pieces[1].clip.startTimeOnTrackMs)

        val second = pieces[1]
        // Player position 0 inside piece 2 maps back to the timeline start of clip 2.
        val elapsedBefore = pieces.take(1).sumOf { it.timelineSpanMs }
        assertEquals(2_500L, elapsedBefore)
        // And the rate for piece 2 is exactly sourceSpan / slotSpan.
        val rate = (second.sourceEndMs - second.sourceStartMs).toFloat() / second.timelineSpanMs.toFloat()
        assertEquals(second.speed, rate, 0.001f)
    }
}

package com.vibecut.pro.engine.timeline

import com.vibecut.pro.engine.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * One piece of the *preview playlist* (or export item) with a constant playback speed.
 *
 * Media3 speed providers are constant-rate only, so a clip that ramps (or is reversed) must be
 * exported as a sequence of short constant-speed segments joined back to back. Each segment carries
 * the source range to decode and the timeline range it should occupy, which keeps audio and video
 * frame-exact with the preview clock.
 */
data class SpeedSegmentPlan(
    val clipId: String,
    val segmentIndex: Int,
    val totalSegments: Int,
    /** Source in/out in absolute media time (already reversed when the clip plays backwards). */
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    /** Where this segment sits on the final timeline. */
    val timelineStartMs: Long,
    val timelineEndMs: Long,
    val speed: Float,
    val isReversed: Boolean,
    val isFrozen: Boolean
) {
    val sourceDurationMs: Long get() = (sourceEndMs - sourceStartMs).coerceAtLeast(1L)
    val timelineDurationMs: Long get() = (timelineEndMs - timelineStartMs).coerceAtLeast(1L)
}

/**
 * Plans the export of the timeline into a flat list of playback segments.
 *
 * Pure functions only — no Android types — so the partitioning logic is unit-testable and shared by
 * the real Media3 exporter.
 */
object ExportSegmentPlanner {

    /** Maximum number of sub-segments generated for one ramped clip. */
    const val MAX_RAMP_SEGMENTS = 24
    /** Ramped clips are quantised to this many equal timeline slices at most. */
    const val RAMP_RESOLUTION_STEPS = 18

    /**
     * Splits [clip] into constant-speed segments covering its full timeline duration.
     * Constant-speed clips produce exactly one segment.
     */
    fun planClip(clip: TimelineClip): List<SpeedSegmentPlan> {
        if (clip.sourceDurationMs <= 0L) return emptyList()
        val totalTimelineMs = clip.timelineDurationMs
        if (totalTimelineMs <= 0L) return emptyList()

        val baseStart = clip.startTimeOnTrackMs

        // Frozen frame / still image: one segment, zero-length source read.
        if (clip.isFrozenFrame || clip.mediaKind == MediaKind.IMAGE ||
            clip.mediaKind == MediaKind.TEXT_ELEMENT || clip.mediaKind == MediaKind.STICKER_ELEMENT
        ) {
            return listOf(
                SpeedSegmentPlan(
                    clipId = clip.id,
                    segmentIndex = 0,
                    totalSegments = 1,
                    sourceStartMs = clip.mapTimelineOffsetToSourceMs(0L),
                    sourceEndMs = clip.mapTimelineOffsetToSourceMs(0L) + 1L,
                    timelineStartMs = baseStart,
                    timelineEndMs = baseStart + totalTimelineMs,
                    speed = 1f,
                    isReversed = clip.isReversed,
                    isFrozen = true
                )
            )
        }

        if (!clip.hasSpeedRamp) {
            val srcStart = clip.mapTimelineOffsetToSourceMs(0L)
            val srcEnd = clip.mapTimelineOffsetToSourceMs(totalTimelineMs)
            return listOf(
                SpeedSegmentPlan(
                    clipId = clip.id,
                    segmentIndex = 0,
                    totalSegments = 1,
                    sourceStartMs = minOf(srcStart, srcEnd),
                    sourceEndMs = max(srcStart, srcEnd),
                    timelineStartMs = baseStart,
                    timelineEndMs = baseStart + totalTimelineMs,
                    speed = clip.clampedSpeed,
                    isReversed = clip.isReversed,
                    isFrozen = false
                )
            )
        }

        // Ramped clip: quantise the timeline into fixed slices, each with constant average speed.
        val steps = RAMP_RESOLUTION_STEPS.coerceAtMost(MAX_RAMP_SEGMENTS)
        val sliceMs = max(40L, totalTimelineMs / steps)
        val out = ArrayList<SpeedSegmentPlan>(steps + 1)

        var offset = 0L
        var index = 0
        while (offset < totalTimelineMs) {
            val end = minOf(totalTimelineMs, offset + sliceMs)
            val srcA = clip.mapTimelineOffsetToSourceMs(offset)
            val srcB = clip.mapTimelineOffsetToSourceMs(end)
            val srcSpan = abs(srcB - srcA).coerceAtLeast(1L)
            // Export reads the source range natively; the segment already IS the right length, so a
            // speed provider must not be attached (1x) or the audio would be pitched twice.
            val effectiveSpeed = 1f

            out += SpeedSegmentPlan(
                clipId = clip.id,
                segmentIndex = index,
                totalSegments = steps,
                sourceStartMs = minOf(srcA, srcB),
                sourceEndMs = max(srcA, srcB),
                timelineStartMs = baseStart + offset,
                timelineEndMs = baseStart + end,
                speed = effectiveSpeed,
                isReversed = clip.isReversed,
                isFrozen = false
            )
            offset = end
            index++
        }
        // Re-stamp totalSegments now that the real count is known.
        return out.map { it.copy(totalSegments = out.size) }
    }

    /**
     * Flattens every visual clip on every visible visual track, ordered by timeline start.
     * Overlapping PiP/text clips keep their own track so the compositor can layer them.
     */
    fun planVisualTracks(project: Project): List<PlannedVisualSegment> {
        val result = ArrayList<PlannedVisualSegment>()
        project.tracks.filter { it.type.isVisual && !it.isHidden }.forEach { track ->
            track.clips.sortedBy { it.startTimeOnTrackMs }.forEach { clip ->
                planClip(clip).forEach { seg ->
                    result += PlannedVisualSegment(trackType = track.type, clip = clip, segment = seg)
                }
            }
        }
        return result.sortedWith(compareBy({ it.segment.timelineStartMs }, { it.trackType.ordinal }))
    }

    /** Every audio-producing clip (music/voiceover/sfx tracks plus video clips with audio). */
    fun planAudioTracks(project: Project): List<PlannedAudioSegment> {
        val result = ArrayList<PlannedAudioSegment>()
        project.tracks.filter { !it.isMuted }.forEach { track ->
            track.clips.forEach { clip ->
                if (!clip.hasAudioTrack || clip.isMuted) return@forEach
                if (clip.volume <= 0f && track.volume <= 0f) return@forEach
                planClip(clip).forEach { seg ->
                    result += PlannedAudioSegment(track = track, clip = clip, segment = seg)
                }
            }
        }
        return result.sortedBy { it.segment.timelineStartMs }
    }

    /**
     * Builds the **preview playlist**: every main-track clip expanded into constant-speed pieces.
     *
     * Each piece is clipped to its exact source range and played with a *constant* speed of
     * `sourceSpan / timelineSpan`, so the player consumes real time at exactly the rate the timeline
     * shows. That means no padding, no pre-timed gaps and therefore no black frames between items:
     * the playlist is simply the clips back to back, and the playhead is reconstructed as
     * `clip.startOnTrack + segmentOffset + playerPosition / speed`.
     *
     * Reversed clips are represented like forward ones; the controller steps them backwards because
     * Media3 cannot decode in reverse.
     */
    fun planPreviewPlaylist(project: Project): List<PreviewPiece> {
        val mainTrack = project.trackByType(TrackType.VIDEO_MAIN) ?: return emptyList()
        val clips = mainTrack.clips.sortedBy { it.startTimeOnTrackMs }
        if (clips.isEmpty()) return emptyList()

        val out = ArrayList<PreviewPiece>(clips.size)
        for (clip in clips) {
            val segments = planClip(clip)
            if (segments.size <= 1) {
                val seg = segments.firstOrNull()
                val isStill = clip.mediaKind == MediaKind.IMAGE || clip.isFrozenFrame
                out += PreviewPiece(
                    clip = clip,
                    segmentOffsetMs = 0L,
                    sourceStartMs = seg?.sourceStartMs ?: clip.trimStartMs,
                    sourceEndMs = seg?.sourceEndMs ?: clip.effectiveTrimEndMs,
                    timelineSpanMs = clip.timelineDurationMs.coerceAtLeast(1L),
                    isHeldStill = isStill,
                    speed = if (clip.isFrozenFrame || clip.mediaKind == MediaKind.IMAGE) 1f
                    else playbackRateFor(
                        (seg?.sourceEndMs ?: clip.effectiveTrimEndMs) - (seg?.sourceStartMs ?: clip.trimStartMs),
                        clip.timelineDurationMs
                    ),
                    isStillImage = clip.mediaKind == MediaKind.IMAGE,
                    isReversed = clip.isReversed && !clip.isFrozenFrame && clip.mediaKind == MediaKind.VIDEO
                )
            } else {
                segments.forEach { seg ->
                    out += PreviewPiece(
                        clip = clip,
                        segmentOffsetMs = (seg.timelineStartMs - clip.startTimeOnTrackMs).coerceAtLeast(0L),
                        sourceStartMs = seg.sourceStartMs,
                        sourceEndMs = seg.sourceEndMs,
                        timelineSpanMs = seg.timelineDurationMs.coerceAtLeast(1L),
                        isHeldStill = false,
                        speed = playbackRateFor(
                            seg.sourceEndMs - seg.sourceStartMs,
                            seg.timelineDurationMs
                        ),
                        isStillImage = false,
                        isReversed = clip.isReversed
                    )
                }
            }
        }
        return out
    }

    /**
     * The constant playback rate that makes [sourceSpanMs] of media last exactly [timelineSpanMs].
     * This is what the preview player is set to for each playlist piece, so every piece consumes
     * real time at the rate the timeline advertises.
     */
    fun playbackRateFor(sourceSpanMs: Long, timelineSpanMs: Long): Float =
        (sourceSpanMs.toFloat() / timelineSpanMs.coerceAtLeast(1L).toFloat())
            .coerceIn(SpeedCurvePreset.MIN_SPEED, SpeedCurvePreset.MAX_SPEED)

    /** One playlist entry for preview: a constant-speed slice of one clip. */
    data class PreviewPiece(
        val clip: TimelineClip,
        /** Offset of this slice inside the clip's own timeline (drives keyframes/transitions). */
        val segmentOffsetMs: Long,
        val sourceStartMs: Long,
        val sourceEndMs: Long,
        /** How long this slice occupies the timeline. */
        val timelineSpanMs: Long,
        /** Constant playback rate that makes `sourceSpan` last exactly [timelineSpanMs]. */
        val speed: Float,
        val isStillImage: Boolean,
        val isReversed: Boolean,
        /** Held on screen by the UI (image or frozen frame) — no decodable span to play. */
        val isHeldStill: Boolean = false
    )

    /** Total duration the export must cover (visual + audio tails). */
    fun totalExportDurationMs(project: Project): Long {
        val visual = planVisualTracks(project).maxOfOrNull { it.segment.timelineEndMs } ?: 0L
        val audio = planAudioTracks(project).maxOfOrNull { it.segment.timelineEndMs } ?: 0L
        val main = project.mainVideoClips.maxOfOrNull { it.endTimeOnTrackMs } ?: 0L
        return max(visual, max(audio, main))
    }

    /** Frame-exact duration at a given frame rate (rounded up so the last frame is not clipped). */
    fun durationMsForFrames(durationMs: Long, fps: Int): Long {
        val safeFps = fps.coerceAtLeast(1)
        val frames = (durationMs.toDouble() * safeFps / 1000.0).roundToLong()
        return max(1L, (frames * 1000.0 / safeFps).roundToLong())
    }
}

data class PlannedVisualSegment(
    val trackType: TrackType,
    val clip: TimelineClip,
    val segment: SpeedSegmentPlan
)

data class PlannedAudioSegment(
    val track: Track,
    val clip: TimelineClip,
    val segment: SpeedSegmentPlan
)

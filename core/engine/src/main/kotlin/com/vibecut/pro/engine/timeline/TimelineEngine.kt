package com.vibecut.pro.engine.timeline

import com.vibecut.pro.engine.model.*
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Immutable timeline editing operations. Every function returns a new [Project] (or the same
 * instance when nothing changed) and bumps the revision, which drives undo/redo and autosave.
 *
 * Editing semantics:
 *  - VIDEO_MAIN with `magneticTimeline` is always gapless and re-flows after trim/split/delete.
 *  - Every other track is free-positioned; clips may overlap and are sorted by start time.
 *  - Trim / split / cut always map through [TimelineClip.mapTimelineOffsetToSourceMs] so speed
 *    ramps and reverse clips never desync audio or video.
 */
object TimelineEngine {

    const val MIN_CLIP_DURATION_MS = 100L

    // ---------------------------------------------------------------- media import

    fun importMediaClips(
        project: Project,
        items: List<ImportedMediaSource>,
        targetTrackType: TrackType = TrackType.VIDEO_MAIN,
        insertAtPlayheadMs: Long? = null
    ): Project {
        if (items.isEmpty()) return project
        val targetTrack = project.tracks.firstOrNull { it.type == targetTrackType && !it.isLocked }
            ?: return project

        val magnetic = targetTrack.type == TrackType.VIDEO_MAIN && project.magneticTimeline
        var cursorMs = when {
            magnetic -> targetTrack.durationMs
            insertAtPlayheadMs != null -> max(0L, insertAtPlayheadMs)
            else -> targetTrack.durationMs
        }

        val added = items.map { src ->
            val safeDuration = max(MIN_CLIP_DURATION_MS, src.durationMs)
            val defaultTransform = if (targetTrack.type == TrackType.VIDEO_PIP) {
                ClipTransform(positionX = 0f, positionY = 0f, scaleX = 0.55f, scaleY = 0.55f)
            } else {
                ClipTransform()
            }
            val isImage = src.mediaKind == MediaKind.IMAGE
            val clipDuration = if (isImage) max(3_000L, safeDuration) else safeDuration
            val clip = TimelineClip(
                id = UUID.randomUUID().toString(),
                trackId = targetTrack.id,
                sourceUri = src.uri,
                sourceName = src.displayName,
                mediaKind = src.mediaKind,
                sourceDurationMs = clipDuration,
                startTimeOnTrackMs = cursorMs,
                trimStartMs = 0L,
                trimEndMs = clipDuration,
                transform = defaultTransform,
                waveformPeaks = src.waveformPeaks,
                hasAudioTrack = src.hasAudioTrack && !isImage,
                sourceWidth = src.width,
                sourceHeight = src.height,
                rotationDegreesMeta = src.rotationDegreesMeta,
                mimeType = src.mimeType,
                thumbnailUri = src.thumbnailUri
            )
            cursorMs = clip.endTimeOnTrackMs
            clip
        }

        return updateTrack(project, targetTrack.id) { track ->
            val merged = track.clips + added
            track.copy(clips = if (magnetic) reflowMagneticClips(merged) else merged.sortedBy { it.startTimeOnTrackMs })
        }
    }

    // ---------------------------------------------------------------- project settings

    fun setProjectAspectRatio(project: Project, preset: AspectRatioPreset, customRatio: Float = project.customAspectRatio): Project =
        project.copy(
            aspectRatio = preset,
            customAspectRatio = AspectRatioPreset.sanitizeCustomRatio(customRatio),
            updatedAtMs = System.currentTimeMillis(),
            revision = project.revision + 1
        )

    // ---------------------------------------------------------------- trim / split / cut

    fun trimClip(project: Project, clipId: String, newTrimStartMs: Long, newTrimEndMs: Long): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project

        return updateTrack(project, track.id) { t ->
            val updated = t.clips.map { clip ->
                if (clip.id != clipId) return@map clip
                val maxSource = max(MIN_CLIP_DURATION_MS, clip.sourceDurationMs)
                val safeStart = newTrimStartMs.coerceIn(0L, maxSource - MIN_CLIP_DURATION_MS)
                val safeEnd = newTrimEndMs.coerceIn(safeStart + MIN_CLIP_DURATION_MS, maxSource)
                val shiftedKeyframes = clip.keyframes.map {
                    it.copy(timeOffsetMs = it.timeOffsetMs.coerceIn(0L, max(50L, safeEnd - safeStart)))
                }
                clip.copy(trimStartMs = safeStart, trimEndMs = safeEnd, keyframes = shiftedKeyframes)
            }
            maybeMagneticReflow(project, t, updated)
        }
    }

    /** Splits a clip at an absolute timeline position into two independent clips. */
    fun splitClipAt(project: Project, clipId: String, playheadMs: Long): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return project

        val offsetInClipMs = playheadMs - clip.startTimeOnTrackMs
        if (offsetInClipMs <= MIN_CLIP_DURATION_MS || offsetInClipMs >= clip.timelineDurationMs - MIN_CLIP_DURATION_MS) {
            return project
        }
        if (clip.mediaKind == MediaKind.TEXT_ELEMENT || clip.mediaKind == MediaKind.STICKER_ELEMENT) {
            // Text/sticker clips are synthetic: they have no real media, so both halves get a
            // self-consistent 0..duration source range *and* their keyframes are partitioned and
            // rebased exactly like a media clip (previously the right half kept every keyframe and
            // the left half kept a stale sourceDurationMs, so both halves rendered wrong).
            val leftSpan = offsetInClipMs
            val rightSpan = clip.timelineDurationMs - offsetInClipMs
            val left = clip.copy(
                sourceDurationMs = leftSpan,
                trimStartMs = 0L,
                trimEndMs = leftSpan,
                transitionOut = null,
                keyframes = clip.keyframes.filter { it.timeOffsetMs <= offsetInClipMs }
            )
            val right = clip.copy(
                id = UUID.randomUUID().toString(),
                startTimeOnTrackMs = clip.startTimeOnTrackMs + offsetInClipMs,
                sourceDurationMs = rightSpan,
                trimStartMs = 0L,
                trimEndMs = rightSpan,
                transitionIn = null,
                keyframes = clip.keyframes
                    .filter { it.timeOffsetMs > offsetInClipMs }
                    .map {
                        it.copy(
                            id = UUID.randomUUID().toString(),
                            timeOffsetMs = it.timeOffsetMs - offsetInClipMs
                        )
                    }
            )
            return updateTrack(project, track.id) { t ->
                t.copy(clips = replaceClipWith(t.clips, clipId, listOf(left, right))
                    .sortedBy { it.startTimeOnTrackMs })
            }
        }

        val splitSourceMs = clip.mapTimelineOffsetToSourceMs(offsetInClipMs)
        if (splitSourceMs <= clip.trimStartMs + 40L || splitSourceMs >= clip.effectiveTrimEndMs - 40L) return project

        // A speed ramp is parameterised over the clip's own 0..1 source span, so each half has to
        // keep the slice of the curve it actually covers, re-normalised to its new 0..1 range.
        val splitSourceFraction = if (clip.trimmedSourceDurationMs > 0) {
            ((splitSourceMs - clip.trimStartMs).toFloat() / clip.trimmedSourceDurationMs.toFloat())
                .coerceIn(0f, 1f)
        } else 0f
        val leftCurve = resampleSpeedCurve(clip.activeSpeedPoints, 0f, splitSourceFraction)
        val rightCurve = resampleSpeedCurve(clip.activeSpeedPoints, splitSourceFraction, 1f)

        val left = clip.copy(
            trimEndMs = splitSourceMs,
            transitionOut = null,
            speedCurvePoints = leftCurve,
            speedCurvePreset = if (leftCurve.isEmpty()) clip.speedCurvePreset else SpeedCurvePreset.NONE,
            keyframes = clip.keyframes.filter { it.timeOffsetMs <= offsetInClipMs }
        )
        val right = clip.copy(
            id = UUID.randomUUID().toString(),
            startTimeOnTrackMs = left.endTimeOnTrackMs,
            trimStartMs = splitSourceMs,
            transitionIn = null,
            speedCurvePoints = rightCurve,
            speedCurvePreset = if (rightCurve.isEmpty()) clip.speedCurvePreset else SpeedCurvePreset.NONE,
            effects = clip.effects.map { it.copy(id = UUID.randomUUID().toString()) },
            keyframes = clip.keyframes
                .filter { it.timeOffsetMs > offsetInClipMs }
                .map { it.copy(id = UUID.randomUUID().toString(), timeOffsetMs = it.timeOffsetMs - offsetInClipMs) }
        )

        return updateTrack(project, track.id) { t ->
            maybeMagneticReflow(project, t, replaceClipWith(t.clips, clipId, listOf(left, right)))
        }
    }

    /** Removes the timeline span [cutStartMs, cutEndMs] from a clip, rippling the remainder. */
    fun cutRangeFromClip(project: Project, clipId: String, cutStartMs: Long, cutEndMs: Long): Project {
        if (cutEndMs <= cutStartMs + 40L) return project
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return project

        val clampedStart = max(clip.startTimeOnTrackMs, cutStartMs)
        val clampedEnd = min(clip.endTimeOnTrackMs, cutEndMs)
        if (clampedEnd <= clampedStart + 40L) return project

        if (clampedStart <= clip.startTimeOnTrackMs + MIN_CLIP_DURATION_MS) {
            val newTrimStart = clip.mapTimelineOffsetToSourceMs(clampedEnd - clip.startTimeOnTrackMs)
            return trimClip(project, clipId, newTrimStart, clip.effectiveTrimEndMs)
        }
        if (clampedEnd >= clip.endTimeOnTrackMs - MIN_CLIP_DURATION_MS) {
            val newTrimEnd = clip.mapTimelineOffsetToSourceMs(clampedStart - clip.startTimeOnTrackMs)
            return trimClip(project, clipId, clip.trimStartMs, newTrimEnd)
        }

        val srcCutStart = clip.mapTimelineOffsetToSourceMs(clampedStart - clip.startTimeOnTrackMs)
        val srcCutEnd = clip.mapTimelineOffsetToSourceMs(clampedEnd - clip.startTimeOnTrackMs)
        val left = clip.copy(trimEndMs = srcCutStart, transitionOut = null)
        val right = clip.copy(
            id = UUID.randomUUID().toString(),
            startTimeOnTrackMs = if (track.type == TrackType.VIDEO_MAIN && project.magneticTimeline) left.endTimeOnTrackMs else clampedEnd,
            trimStartMs = srcCutEnd,
            transitionIn = null,
            effects = clip.effects.map { it.copy(id = UUID.randomUUID().toString()) }
        )
        return updateTrack(project, track.id) { t ->
            maybeMagneticReflow(project, t, replaceClipWith(t.clips, clipId, listOf(left, right)))
        }
    }

    fun duplicateClip(project: Project, clipId: String): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        return updateTrack(project, track.id) { t ->
            val idx = t.clips.indexOfFirst { it.id == clipId }
            if (idx < 0) return@updateTrack t
            val original = t.clips[idx]
            val duplicate = original.copy(
                id = UUID.randomUUID().toString(),
                sourceName = "${original.sourceName} (copy)",
                startTimeOnTrackMs = original.endTimeOnTrackMs,
                effects = original.effects.map { it.copy(id = UUID.randomUUID().toString()) },
                keyframes = original.keyframes.map { it.copy(id = UUID.randomUUID().toString()) }
            )
            val next = t.clips.toMutableList().apply { add(idx + 1, duplicate) }
            maybeMagneticReflow(project, t, next)
        }
    }

    fun deleteClip(project: Project, clipId: String): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        return updateTrack(project, track.id) { t ->
            maybeMagneticReflow(project, t, t.clips.filterNot { it.id == clipId })
        }
    }

    /**
     * Moves a clip along its own track (free tracks) or re-orders it (magnetic main track).
     * This is what the timeline drag uses.
     */
    fun moveClipToTimestamp(project: Project, clipId: String, newStartMs: Long): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return project

        if (track.type == TrackType.VIDEO_MAIN && project.magneticTimeline) {
            // Re-order within the gapless main track by target position.
            val others = track.clips.filterNot { it.id == clipId }.sortedBy { it.startTimeOnTrackMs }
            val target = max(0L, newStartMs)
            val insertIndex = others.indexOfFirst { it.startTimeOnTrackMs + it.timelineDurationMs / 2 > target }
                .let { if (it < 0) others.size else it }
            val reordered = others.toMutableList().apply { add(insertIndex, clip) }
            return updateTrack(project, track.id) { t -> t.copy(clips = reflowMagneticClips(reordered)) }
        }

        val safeStart = max(0L, newStartMs)
        return updateTrack(project, track.id) { t ->
            val snapped = if (project.snappingEnabled) {
                snapToAnchors(project, t, clip, safeStart, thresholdMs = 120L)
            } else {
                safeStart
            }
            t.copy(
                clips = t.clips.map { if (it.id == clipId) it.copy(startTimeOnTrackMs = snapped) else it }
                    .sortedBy { it.startTimeOnTrackMs }
            )
        }
    }

    fun reorderClips(project: Project, trackId: String, fromIndex: Int, toIndex: Int): Project {
        val track = project.tracks.firstOrNull { it.id == trackId } ?: return project
        if (track.isLocked) return project
        if (fromIndex !in track.clips.indices || toIndex !in track.clips.indices) return project
        val mutable = track.clips.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        return updateTrack(project, trackId) { t ->
            maybeMagneticReflow(project, t, mutable)
        }
    }

    fun moveClipOrder(project: Project, clipId: String, delta: Int): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        val idx = track.clips.indexOfFirst { it.id == clipId }
        if (idx < 0) return project
        val target = (idx + delta).coerceIn(0, track.clips.lastIndex)
        if (target == idx) return project
        return reorderClips(project, track.id, idx, target)
    }

    fun nudgeClipPosition(project: Project, clipId: String, deltaMs: Long): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return project
        return moveClipToTimestamp(project, clipId, clip.startTimeOnTrackMs + deltaMs)
    }

    /** Merges adjacent clips of the same source by extending the trim range. */
    fun mergeClips(project: Project, clipIds: List<String>): Project {
        if (clipIds.size < 2) return project
        val firstTrack = project.findTrackForClip(clipIds.first()) ?: return project
        if (firstTrack.isLocked) return project
        return updateTrack(project, firstTrack.id) { t ->
            val selected = t.clips.filter { it.id in clipIds }.sortedBy { it.startTimeOnTrackMs }
            if (selected.size < 2) return@updateTrack t

            val allSameSource = selected.all {
                it.sourceUri == selected.first().sourceUri && it.mediaKind == selected.first().mediaKind && !it.isFrozenFrame
            }
            if (allSameSource) {
                val base = selected.first()
                val merged = base.copy(
                    id = UUID.randomUUID().toString(),
                    sourceName = "${base.sourceName} (merged)",
                    trimStartMs = selected.minOf { it.trimStartMs },
                    trimEndMs = selected.maxOf { it.effectiveTrimEndMs },
                    transitionOut = selected.last().transitionOut
                )
                val idx = t.clips.indexOfFirst { it.id == selected.first().id }
                val remaining = t.clips.filterNot { it.id in clipIds }.toMutableList()
                remaining.add(idx.coerceIn(0, remaining.size), merged)
                maybeMagneticReflow(project, t, remaining)
            } else {
                var cursor = selected.first().startTimeOnTrackMs
                val ids = selected.map { it.id }.toSet()
                val packed = selected.mapIndexed { i, c ->
                    val next = c.copy(
                        startTimeOnTrackMs = cursor,
                        transitionIn = if (i > 0 && c.transitionIn == null) ClipTransition(TransitionType.DISSOLVE, 350L) else c.transitionIn
                    )
                    cursor += next.timelineDurationMs
                    next
                }
                val idx = t.clips.indexOfFirst { it.id in ids }
                val remaining = t.clips.filterNot { it.id in ids }.toMutableList()
                remaining.addAll(idx.coerceIn(0, remaining.size), packed)
                maybeMagneticReflow(project, t, remaining)
            }
        }
    }

    // ---------------------------------------------------------------- transform / framing

    fun setCrop(project: Project, clipId: String, cropRect: CropRect): Project =
        updateClip(project, clipId) { it.copy(crop = cropRect.sanitized()) }

    fun rotateClip90(project: Project, clipId: String, clockwise: Boolean = true): Project =
        updateClip(project, clipId) { clip ->
            val delta = if (clockwise) 90f else -90f
            val next = ((clip.transform.rotationDegrees + delta) % 360f + 360f) % 360f
            clip.copy(transform = clip.transform.copy(rotationDegrees = next))
        }

    /**
     * Rotates in 90° steps.
     *
     * This is **non-destructive**: `sourceWidth`/`sourceHeight` always describe the real decoded
     * media and are never mutated. (Swapping them on every quarter turn permanently corrupted the
     * clip's aspect after 4 rotations, because the swap was applied again on reload.) Consumers ask
     * [TimelineClip.displayWidth]/[TimelineClip.displayHeight] for the post-rotation framing.
     */
    fun rotateClipQuarterTurn(project: Project, clipId: String, clockwise: Boolean = true): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        return rotateClip90(project, clipId, clockwise)
    }

    fun setClipTransform(project: Project, clipId: String, transform: ClipTransform): Project =
        updateClip(project, clipId) { clip ->
            clip.copy(
                transform = transform.copy(
                    scaleX = transform.scaleX.coerceIn(0.05f, 6f),
                    scaleY = transform.scaleY.coerceIn(0.05f, 6f),
                    opacity = transform.opacity.coerceIn(0f, 1f)
                )
            )
        }

    fun toggleFlipHorizontal(project: Project, clipId: String): Project =
        updateClip(project, clipId) { it.copy(transform = it.transform.copy(flipHorizontal = !it.transform.flipHorizontal)) }

    fun toggleFlipVertical(project: Project, clipId: String): Project =
        updateClip(project, clipId) { it.copy(transform = it.transform.copy(flipVertical = !it.transform.flipVertical)) }

    fun setBlendMode(project: Project, clipId: String, mode: BlendMode): Project =
        updateClip(project, clipId) { it.copy(transform = it.transform.copy(blendMode = mode)) }

    fun setChromaKey(project: Project, clipId: String, config: ChromaKeyConfig): Project =
        updateClip(project, clipId) { it.copy(chromaKey = config) }

    fun toggleReverseClip(project: Project, clipId: String): Project =
        updateClip(project, clipId) { clip ->
            if (clip.mediaKind == MediaKind.IMAGE || clip.isFrozenFrame) clip
            else clip.copy(isReversed = !clip.isReversed)
        }

    // ---------------------------------------------------------------- speed / time

    fun setClipSpeed(
        project: Project,
        clipId: String,
        speed: Float,
        curvePreset: SpeedCurvePreset = SpeedCurvePreset.NONE,
        customPoints: List<SpeedPoint> = emptyList()
    ): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        return updateTrack(project, track.id) { t ->
            val updated = t.clips.map { clip ->
                if (clip.id != clipId) return@map clip
                clip.copy(
                    speed = speed.coerceIn(SpeedCurvePreset.MIN_SPEED, SpeedCurvePreset.MAX_SPEED),
                    speedCurvePreset = curvePreset,
                    speedCurvePoints = if (curvePreset == SpeedCurvePreset.NONE) emptyList() else customPoints
                )
            }
            maybeMagneticReflow(project, t, updated)
        }
    }

    fun insertFreezeFrame(project: Project, clipId: String, playheadMs: Long, freezeDurationMs: Long = 2_000L): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return project
        if (clip.mediaKind != MediaKind.VIDEO && clip.mediaKind != MediaKind.IMAGE) return project

        val clampedOffset = (playheadMs - clip.startTimeOnTrackMs).coerceIn(0L, clip.timelineDurationMs)
        val freezeSourceMs = clip.mapTimelineOffsetToSourceMs(clampedOffset)
        val frozen = TimelineClip(
            id = UUID.randomUUID().toString(),
            trackId = track.id,
            sourceUri = clip.sourceUri,
            sourceName = "${clip.sourceName} (freeze)",
            mediaKind = clip.mediaKind,
            sourceDurationMs = freezeDurationMs,
            startTimeOnTrackMs = clip.startTimeOnTrackMs + clampedOffset,
            trimStartMs = 0L,
            trimEndMs = freezeDurationMs,
            isFrozenFrame = true,
            freezeFrameSourceMs = freezeSourceMs,
            crop = clip.crop,
            transform = clip.transform,
            effects = clip.effects.map { it.copy(id = UUID.randomUUID().toString()) },
            colorGrade = clip.colorGrade,
            volume = 0f,
            isMuted = true,
            hasAudioTrack = false,
            sourceWidth = clip.sourceWidth,
            sourceHeight = clip.sourceHeight,
            rotationDegreesMeta = clip.rotationDegreesMeta,
            mimeType = clip.mimeType,
            thumbnailUri = clip.thumbnailUri
        )

        return updateTrack(project, track.id) { t ->
            val next = mutableListOf<TimelineClip>()
            for (existing in t.clips) {
                if (existing.id != clipId) {
                    next.add(existing)
                    continue
                }
                val canSplitBefore = freezeSourceMs > existing.trimStartMs + MIN_CLIP_DURATION_MS
                val canSplitAfter = freezeSourceMs < existing.effectiveTrimEndMs - MIN_CLIP_DURATION_MS
                if (canSplitBefore) {
                    next.add(existing.copy(trimEndMs = freezeSourceMs, transitionOut = null))
                }
                next.add(frozen)
                if (canSplitAfter) {
                    next.add(
                        existing.copy(
                            id = UUID.randomUUID().toString(),
                            trimStartMs = freezeSourceMs,
                            transitionIn = null
                        )
                    )
                }
            }
            maybeMagneticReflow(project, t, next)
        }
    }

    /** Frozen-frame helper for still images: gives the clip an explicit duration. */
    fun setClipTimelineDuration(project: Project, clipId: String, durationMs: Long): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return project
        val safe = max(MIN_CLIP_DURATION_MS, durationMs)
        return updateTrack(project, track.id) { t ->
            val updated = t.clips.map { c ->
                if (c.id != clipId) c
                else if (c.mediaKind == MediaKind.IMAGE) {
                    c.copy(trimEndMs = safe, sourceDurationMs = max(safe, c.sourceDurationMs))
                } else {
                    // Extend/shrink using speed so trim points stay valid.
                    val targetSource = (safe * c.clampedSpeed).roundToLong()
                        .coerceIn(MIN_CLIP_DURATION_MS, max(MIN_CLIP_DURATION_MS, c.sourceDurationMs - c.trimStartMs))
                    c.copy(trimEndMs = (c.trimStartMs + targetSource).coerceAtMost(c.sourceDurationMs))
                }
            }
            maybeMagneticReflow(project, t, updated)
        }
    }

    fun stepPlayheadByFrames(playheadMs: Long, fps: Int, deltaFrames: Int, maxDurationMs: Long): Long {
        val safeFps = fps.coerceIn(1, 120)
        val frameMs = max(1L, (1000.0 / safeFps).roundToLong())
        return (playheadMs + deltaFrames * frameMs).coerceIn(0L, max(0L, maxDurationMs))
    }

    fun setTimelineZoom(project: Project, zoom: Float): Project =
        project.copy(timelineZoom = zoom.coerceIn(0.25f, 6f), updatedAtMs = System.currentTimeMillis())

    // ---------------------------------------------------------------- tracks

    fun addTrack(project: Project, type: TrackType, customName: String? = null): Project {
        val count = project.tracks.count { it.type == type } + 1
        val track = Track(
            id = UUID.randomUUID().toString(),
            name = customName ?: "${type.label} $count",
            type = type
        )
        return project.copy(tracks = project.tracks + track, updatedAtMs = System.currentTimeMillis(), revision = project.revision + 1)
    }

    fun toggleTrackLock(project: Project, trackId: String): Project =
        updateTrack(project, trackId) { it.copy(isLocked = !it.isLocked) }

    fun toggleTrackHidden(project: Project, trackId: String): Project =
        updateTrack(project, trackId) { it.copy(isHidden = !it.isHidden) }

    fun toggleTrackMute(project: Project, trackId: String): Project =
        updateTrack(project, trackId) { it.copy(isMuted = !it.isMuted) }

    fun setTrackVolume(project: Project, trackId: String, volume: Float): Project =
        updateTrack(project, trackId) { it.copy(volume = volume.coerceIn(0f, 2f)) }

    // ---------------------------------------------------------------- effects

    fun addOrUpdateEffect(project: Project, clipId: String, effect: AppliedEffect): Project =
        updateClip(project, clipId) { clip ->
            val idx = clip.effects.indexOfFirst { it.id == effect.id }
            val next = if (idx >= 0) {
                clip.effects.toMutableList().apply { set(idx, effect) }
            } else {
                clip.effects + effect
            }
            clip.copy(effects = next)
        }

    fun setEffectEnabled(project: Project, clipId: String, effectId: String, enabled: Boolean): Project =
        updateClip(project, clipId) { clip ->
            clip.copy(effects = clip.effects.map { if (it.id == effectId) it.copy(enabled = enabled) else it })
        }

    fun reorderEffect(project: Project, clipId: String, effectId: String, direction: Int): Project =
        updateClip(project, clipId) { clip ->
            val idx = clip.effects.indexOfFirst { it.id == effectId }
            val target = idx + direction
            if (idx < 0 || target !in clip.effects.indices) return@updateClip clip
            val mutable = clip.effects.toMutableList()
            mutable.add(target, mutable.removeAt(idx))
            clip.copy(effects = mutable)
        }

    fun removeEffect(project: Project, clipId: String, effectId: String): Project =
        updateClip(project, clipId) { it.copy(effects = it.effects.filterNot { fx -> fx.id == effectId }) }

    fun clearClipEffects(project: Project, clipId: String): Project =
        updateClip(project, clipId) { it.copy(effects = emptyList()) }

    fun applyEffectsToAllMainClips(project: Project, effects: List<AppliedEffect>): Project {
        val main = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN } ?: return project
        if (main.isLocked) return project
        return updateTrack(project, main.id) { t ->
            t.copy(clips = t.clips.map { clip ->
                clip.copy(effects = effects.map { it.copy(id = UUID.randomUUID().toString()) })
            })
        }
    }

    // ---------------------------------------------------------------- color

    fun setColorGrade(project: Project, clipId: String, grade: ColorGradeConfig): Project =
        updateClip(project, clipId) { it.copy(colorGrade = grade) }

    fun applyColorGradeToAllMainClips(project: Project, grade: ColorGradeConfig): Project {
        val main = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN } ?: return project
        if (main.isLocked) return project
        return updateTrack(project, main.id) { t -> t.copy(clips = t.clips.map { it.copy(colorGrade = grade) }) }
    }

    fun addCustomColorPreset(project: Project, preset: ColorGradePreset): Project {
        val existing = project.customColorPresets.filterNot { it.id == preset.id || it.name.equals(preset.name, true) }
        return project.copy(
            customColorPresets = listOf(preset.copy(isUserCustom = true)) + existing,
            updatedAtMs = System.currentTimeMillis(),
            revision = project.revision + 1
        )
    }

    fun deleteCustomColorPreset(project: Project, presetId: String): Project =
        project.copy(
            customColorPresets = project.customColorPresets.filterNot { it.id == presetId },
            revision = project.revision + 1
        )

    // ---------------------------------------------------------------- transitions

    fun setClipTransition(project: Project, clipId: String, transitionIn: ClipTransition?): Project =
        updateClip(project, clipId) { clip ->
            clip.copy(transitionIn = transitionIn?.copy(durationMs = transitionIn.durationMs.coerceIn(100L, 3000L)))
        }

    fun applyTransitionToAllMainClips(project: Project, transition: ClipTransition): Project {
        val main = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN } ?: return project
        if (main.isLocked) return project
        val clamped = transition.copy(durationMs = transition.durationMs.coerceIn(100L, 3000L))
        return updateTrack(project, main.id) { t ->
            t.copy(clips = t.clips.mapIndexed { idx, clip -> if (idx == 0) clip else clip.copy(transitionIn = clamped) })
        }
    }

    // ---------------------------------------------------------------- keyframes

    fun upsertKeyframe(project: Project, clipId: String, keyframe: Keyframe): Project =
        updateClip(project, clipId) { clip ->
            val offset = keyframe.timeOffsetMs.coerceIn(0L, clip.timelineDurationMs)
            val normalized = keyframe.copy(
                timeOffsetMs = offset,
                value = keyframe.value.coerceIn(keyframe.property.minValue, keyframe.property.maxValue)
            )
            val filtered = clip.keyframes.filterNot {
                it.id == normalized.id || (it.property == normalized.property && abs(it.timeOffsetMs - offset) <= 40L)
            }
            clip.copy(keyframes = (filtered + normalized).sortedBy { it.timeOffsetMs })
        }

    fun removeKeyframe(project: Project, clipId: String, keyframeId: String): Project =
        updateClip(project, clipId) { clip -> clip.copy(keyframes = clip.keyframes.filterNot { it.id == keyframeId }) }

    fun clearKeyframes(project: Project, clipId: String): Project =
        updateClip(project, clipId) { it.copy(keyframes = emptyList()) }

    // ---------------------------------------------------------------- text / stickers

    fun addTextClip(project: Project, textSpec: TextStyleSpec, startMs: Long, durationMs: Long = 3_000L): Project {
        val textTrack = project.tracks.firstOrNull { it.type == TrackType.TEXT && !it.isLocked } ?: return project
        val safeDuration = max(300L, durationMs)
        val clip = TimelineClip(
            id = UUID.randomUUID().toString(),
            trackId = textTrack.id,
            sourceUri = "text://${UUID.randomUUID()}",
            sourceName = textSpec.text.take(24),
            mediaKind = MediaKind.TEXT_ELEMENT,
            sourceDurationMs = safeDuration,
            startTimeOnTrackMs = max(0L, startMs),
            trimStartMs = 0L,
            trimEndMs = safeDuration,
            textSpec = textSpec,
            hasAudioTrack = false
        )
        return updateTrack(project, textTrack.id) { t ->
            t.copy(clips = (t.clips + clip).sortedBy { it.startTimeOnTrackMs })
        }
    }

    fun addStickerClip(project: Project, stickerSpec: StickerSpec, startMs: Long, durationMs: Long = 2_500L): Project {
        val stickerTrack = project.tracks.firstOrNull { it.type == TrackType.STICKER && !it.isLocked } ?: return project
        val safeDuration = max(300L, durationMs)
        val clip = TimelineClip(
            id = UUID.randomUUID().toString(),
            trackId = stickerTrack.id,
            sourceUri = "sticker://${stickerSpec.label}",
            sourceName = "${stickerSpec.content} ${stickerSpec.label}",
            mediaKind = MediaKind.STICKER_ELEMENT,
            sourceDurationMs = safeDuration,
            startTimeOnTrackMs = max(0L, startMs),
            trimStartMs = 0L,
            trimEndMs = safeDuration,
            stickerSpec = stickerSpec,
            hasAudioTrack = false
        )
        return updateTrack(project, stickerTrack.id) { t ->
            t.copy(clips = (t.clips + clip).sortedBy { it.startTimeOnTrackMs })
        }
    }

    fun upsertSubtitleCue(project: Project, cue: SubtitleCue): Project {
        val filtered = project.subtitles.filterNot { it.id == cue.id }
        return project.copy(
            subtitles = (filtered + cue).sortedBy { it.startTimeMs },
            revision = project.revision + 1,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun deleteSubtitleCue(project: Project, cueId: String): Project =
        project.copy(subtitles = project.subtitles.filterNot { it.id == cueId }, revision = project.revision + 1)

    // ---------------------------------------------------------------- generic helpers

    fun updateClip(project: Project, clipId: String, mutator: (TimelineClip) -> TimelineClip): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        return updateTrack(project, track.id) { t ->
            t.copy(clips = t.clips.map { if (it.id == clipId) mutator(it) else it })
        }
    }

    fun updateTrack(project: Project, trackId: String, mutator: (Track) -> Track): Project {
        val nextTracks = project.tracks.map { if (it.id == trackId) mutator(it) else it }
        return project.copy(
            tracks = nextTracks,
            updatedAtMs = System.currentTimeMillis(),
            revision = project.revision + 1
        )
    }

    fun reflowMagneticClips(clips: List<TimelineClip>): List<TimelineClip> {
        var cursor = 0L
        return clips.map { clip ->
            val updated = clip.copy(startTimeOnTrackMs = cursor)
            cursor = updated.endTimeOnTrackMs
            updated
        }
    }

    /**
     * Snaps a clip start to nearby clip edges and beat markers on the same/different tracks.
     */
    fun snapToAnchors(project: Project, track: Track, movingClip: TimelineClip, candidateMs: Long, thresholdMs: Long): Long {
        val anchors = buildList {
            add(0L)
            project.totalDurationMs.let { if (it > 0) add(it) }
            project.tracks.forEach { t ->
                t.clips.forEach { clip ->
                    if (clip.id == movingClip.id) return@forEach
                    add(clip.startTimeOnTrackMs)
                    add(clip.endTimeOnTrackMs)
                    clip.beatMarkersMs.forEach { add(clip.startTimeOnTrackMs + it) }
                }
            }
        }
        val nearest = anchors.minByOrNull { abs(it - candidateMs) } ?: return candidateMs
        return if (abs(nearest - candidateMs) <= thresholdMs) max(0L, nearest) else candidateMs
    }

    fun mapTimelineOffsetToSourceMs(clip: TimelineClip, timelineOffsetMs: Long): Long =
        clip.mapTimelineOffsetToSourceMs(timelineOffsetMs)

    private fun replaceClipWith(clips: List<TimelineClip>, clipId: String, replacements: List<TimelineClip>): List<TimelineClip> {
        val out = mutableListOf<TimelineClip>()
        for (clip in clips) {
            if (clip.id == clipId) out.addAll(replacements) else out.add(clip)
        }
        return out
    }

    private fun maybeMagneticReflow(project: Project, track: Track, clips: List<TimelineClip>): Track =
        if (track.type == TrackType.VIDEO_MAIN && project.magneticTimeline) {
            track.copy(clips = reflowMagneticClips(clips))
        } else {
            track.copy(clips = clips.sortedBy { it.startTimeOnTrackMs })
        }

    // ------------------------------------------------------------------ speed curve utilities

    /**
     * Extracts the `[from, to]` slice of a speed curve and re-normalises it to span 0..1, so that
     * splitting a ramped clip keeps each half's instantaneous speed identical to the original.
     */
    fun resampleSpeedCurve(points: List<SpeedPoint>, from: Float, to: Float): List<SpeedPoint> {
        if (points.size < 2) return emptyList()
        val lo = from.coerceIn(0f, 1f)
        val hi = to.coerceIn(0f, 1f)
        if (hi - lo < 0.02f) return emptyList()
        val sorted = points.sortedBy { it.normalizedPosition }
        val span = hi - lo
        val samples = 6
        val out = ArrayList<SpeedPoint>(samples + 1)
        for (i in 0..samples) {
            val u = lo + span * (i.toFloat() / samples)
            out += SpeedPoint(
                normalizedPosition = i.toFloat() / samples,
                speedMultiplier = TimelineClip.sampleSpeedCurve(sorted, u)
                    .coerceIn(SpeedCurvePreset.MIN_SPEED, SpeedCurvePreset.MAX_SPEED)
            )
        }
        return out
    }

    // ------------------------------------------------------------------ smart editing tools

    /**
     * **Auto-cut silence.** Removes every stretch of a clip quieter than [thresholdDb] for longer
     * than [minSilenceMs], leaving [paddingMs] of headroom either side so speech is never clipped.
     *
     * [levelsDb] is a per-window loudness envelope of the clip's audio (dBFS, window =
     * `clipDurationMs / levelsDb.size`), which the app computes with the PCM decoder.
     * Returns the project unchanged when nothing is quiet enough to cut.
     */
    fun autoCutSilence(
        project: Project,
        clipId: String,
        levelsDb: List<Float>,
        thresholdDb: Float = -38f,
        minSilenceMs: Long = 400L,
        paddingMs: Long = 90L
    ): Project {
        if (levelsDb.size < 4) return project
        val clip = project.findClip(clipId) ?: return project
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        val duration = clip.timelineDurationMs
        if (duration <= MIN_CLIP_DURATION_MS * 2) return project

        val keep = planSilenceKeepRanges(duration, levelsDb, thresholdDb, minSilenceMs, paddingMs)
        // Nothing to do when the whole clip survives as one piece.
        if (keep.size == 1 && keep[0].first <= 0L && keep[0].second >= duration) return project
        if (keep.isEmpty()) return project

        // Rebuild the clip as N pieces in one pass: cutRangeFromClip() re-IDs the tail on every
        // call, so looping over it would lose track of the clip after the first cut.
        val pieces = keep.mapIndexed { index, (from, to) ->
            val srcStart = clip.mapTimelineOffsetToSourceMs(from)
            val srcEnd = clip.mapTimelineOffsetToSourceMs(to)
            clip.copy(
                id = if (index == 0) clip.id else UUID.randomUUID().toString(),
                startTimeOnTrackMs = clip.startTimeOnTrackMs + from,
                trimStartMs = min(srcStart, srcEnd),
                trimEndMs = max(srcStart, srcEnd),
                transitionIn = if (index == 0) clip.transitionIn else null,
                transitionOut = if (index == keep.lastIndex) clip.transitionOut else null,
                effects = if (index == 0) clip.effects
                else clip.effects.map { it.copy(id = UUID.randomUUID().toString()) },
                keyframes = clip.keyframes
                    .filter { it.timeOffsetMs in from..to }
                    .map {
                        it.copy(
                            id = if (index == 0) it.id else UUID.randomUUID().toString(),
                            timeOffsetMs = it.timeOffsetMs - from
                        )
                    }
            )
        }.filter { it.trimmedSourceDurationMs >= MIN_CLIP_DURATION_MS }
        if (pieces.size < 2) return project

        return updateTrack(project, track.id) { t ->
            maybeMagneticReflow(project, t, replaceClipWith(t.clips, clipId, pieces))
        }
    }

    /**
     * Pure helper behind [autoCutSilence]: converts a loudness envelope into the list of
     * `(startMs, endMs)` windows worth keeping. Exposed so the behaviour is unit-testable.
     */
    fun planSilenceKeepRanges(
        durationMs: Long,
        levelsDb: List<Float>,
        thresholdDb: Float = -38f,
        minSilenceMs: Long = 400L,
        paddingMs: Long = 90L
    ): List<Pair<Long, Long>> {
        if (levelsDb.isEmpty() || durationMs <= 0L) return emptyList()
        val windowMs = durationMs.toDouble() / levelsDb.size

        // 1. Collect raw silent runs.
        val silences = mutableListOf<Pair<Long, Long>>()
        var runStart = -1
        levelsDb.forEachIndexed { index, db ->
            val quiet = db <= thresholdDb
            if (quiet && runStart < 0) runStart = index
            if (!quiet && runStart >= 0) {
                silences += runToMs(runStart, index - 1, windowMs, durationMs)
                runStart = -1
            }
        }
        if (runStart >= 0) silences += runToMs(runStart, levelsDb.lastIndex, windowMs, durationMs)

        // 2. Keep only runs long enough to be worth cutting, then shrink them by the padding so
        //    the first/last syllable at each boundary is never clipped off.
        val cuts = silences
            .filter { it.second - it.first >= minSilenceMs }
            .map { (s, e) -> (s + paddingMs) to (e - paddingMs) }
            .filter { it.second - it.first >= MIN_CLIP_DURATION_MS }
            .sortedBy { it.first }
        if (cuts.isEmpty()) return listOf(0L to durationMs)

        // 3. Invert the cuts into keep ranges.
        val keep = mutableListOf<Pair<Long, Long>>()
        var cursor = 0L
        cuts.forEach { (s, e) ->
            if (s - cursor >= MIN_CLIP_DURATION_MS) keep += cursor to s
            cursor = max(cursor, e)
        }
        if (durationMs - cursor >= MIN_CLIP_DURATION_MS) keep += cursor to durationMs
        return keep
    }

    private fun runToMs(startIndex: Int, endIndex: Int, windowMs: Double, durationMs: Long): Pair<Long, Long> {
        val start = (startIndex * windowMs).roundToLong().coerceIn(0L, durationMs)
        val end = ((endIndex + 1) * windowMs).roundToLong().coerceIn(0L, durationMs)
        return start to max(start, end)
    }

    /**
     * **Beat sync.** Snaps every main-track cut point onto the nearest detected beat so clip
     * boundaries land on the music. Only moves a boundary when a beat is within [toleranceMs].
     */
    fun snapCutsToBeats(project: Project, beatsMs: List<Long>, toleranceMs: Long = 260L): Project {
        if (beatsMs.size < 2) return project
        val track = project.trackByType(TrackType.VIDEO_MAIN) ?: return project
        if (track.isLocked || track.clips.size < 2) return project
        val sortedBeats = beatsMs.sorted()
        val clips = track.clips.sortedBy { it.startTimeOnTrackMs }

        var result = project
        // Boundaries between clip i and i+1; walk forward so each retime ripples the rest.
        for (i in 0 until clips.lastIndex) {
            val current = result.findClip(clips[i].id) ?: continue
            val boundary = current.endTimeOnTrackMs
            val beat = sortedBeats.minByOrNull { abs(it - boundary) } ?: continue
            val delta = beat - boundary
            if (delta == 0L || abs(delta) > toleranceMs) continue
            val newEndSource = current.mapTimelineOffsetToSourceMs(current.timelineDurationMs + delta)
            val clamped = newEndSource.coerceIn(
                current.trimStartMs + MIN_CLIP_DURATION_MS,
                current.sourceDurationMs
            )
            if (clamped <= current.trimStartMs + MIN_CLIP_DURATION_MS) continue
            result = trimClip(result, current.id, current.trimStartMs, clamped)
        }
        return result
    }

    /** Everything "copy attributes" transfers from one clip to another. */
    data class ClipAttributes(
        val colorGrade: ColorGradeConfig,
        val effects: List<AppliedEffect>,
        val transform: ClipTransform,
        val crop: CropRect,
        val chromaKey: ChromaKeyConfig,
        val speed: Float,
        val speedCurvePreset: SpeedCurvePreset,
        val speedCurvePoints: List<SpeedPoint>,
        val volume: Float,
        val fadeInMs: Long,
        val fadeOutMs: Long,
        val noiseReduction: Float,
        val voiceEnhancement: Float,
        val transitionIn: ClipTransition?,
        val transitionOut: ClipTransition?
    )

    /** Snapshots the reusable look/motion/audio attributes of [clipId]. */
    fun copyClipAttributes(project: Project, clipId: String): ClipAttributes? {
        val c = project.findClip(clipId) ?: return null
        return ClipAttributes(
            colorGrade = c.colorGrade,
            effects = c.effects,
            transform = c.transform,
            crop = c.crop,
            chromaKey = c.chromaKey,
            speed = c.speed,
            speedCurvePreset = c.speedCurvePreset,
            speedCurvePoints = c.speedCurvePoints,
            volume = c.volume,
            fadeInMs = c.fadeInMs,
            fadeOutMs = c.fadeOutMs,
            noiseReduction = c.noiseReduction,
            voiceEnhancement = c.voiceEnhancement,
            transitionIn = c.transitionIn,
            transitionOut = c.transitionOut
        )
    }

    /**
     * Pastes previously copied attributes onto [targetIds]. Each group can be toggled so a user can
     * paste "just the grade" or "look + motion" without clobbering audio.
     */
    fun pasteClipAttributes(
        project: Project,
        targetIds: List<String>,
        attrs: ClipAttributes,
        includeLook: Boolean = true,
        includeTransform: Boolean = true,
        includeSpeed: Boolean = false,
        includeAudio: Boolean = false,
        includeTransitions: Boolean = false
    ): Project {
        if (targetIds.isEmpty()) return project
        var result = project
        targetIds.distinct().forEach { id ->
            val track = result.findTrackForClip(id) ?: return@forEach
            if (track.isLocked) return@forEach
            result = updateClip(result, id) { clip ->
                var next = clip
                if (includeLook) {
                    next = next.copy(
                        colorGrade = attrs.colorGrade,
                        effects = attrs.effects.map { it.copy(id = UUID.randomUUID().toString()) },
                        chromaKey = attrs.chromaKey
                    )
                }
                if (includeTransform) {
                    next = next.copy(transform = attrs.transform, crop = attrs.crop)
                }
                if (includeSpeed && next.mediaKind == MediaKind.VIDEO) {
                    next = next.copy(
                        speed = attrs.speed.coerceIn(SpeedCurvePreset.MIN_SPEED, SpeedCurvePreset.MAX_SPEED),
                        speedCurvePreset = attrs.speedCurvePreset,
                        speedCurvePoints = attrs.speedCurvePoints
                    )
                }
                if (includeAudio) {
                    next = next.copy(
                        volume = attrs.volume.coerceIn(0f, 2f),
                        fadeInMs = attrs.fadeInMs,
                        fadeOutMs = attrs.fadeOutMs,
                        noiseReduction = attrs.noiseReduction.coerceIn(0f, 1f),
                        voiceEnhancement = attrs.voiceEnhancement.coerceIn(0f, 1f)
                    )
                }
                if (includeTransitions) {
                    next = next.copy(transitionIn = attrs.transitionIn, transitionOut = attrs.transitionOut)
                }
                next
            }
        }
        // Speed changes resize clips, so the magnetic main track must re-flow.
        val main = result.trackByType(TrackType.VIDEO_MAIN)
        return if (includeSpeed && main != null && result.magneticTimeline) {
            updateTrack(result, main.id) { it.copy(clips = reflowMagneticClips(it.clips)) }
        } else {
            result
        }
    }

    /**
     * **Auto transitions.** Drops the same transition between every adjacent pair on the main
     * track in one action — the single most requested "make it look edited" button.
     */
    fun applyTransitionToAllCuts(
        project: Project,
        type: TransitionType,
        durationMs: Long = type.defaultDurationMs
    ): Project {
        val track = project.trackByType(TrackType.VIDEO_MAIN) ?: return project
        if (track.isLocked || track.clips.size < 2) return project
        val ordered = track.clips.sortedBy { it.startTimeOnTrackMs }
        var result = project
        for (i in 0 until ordered.lastIndex) {
            val a = ordered[i]
            val b = ordered[i + 1]
            val safe = durationMs.coerceIn(
                100L,
                minOf(a.timelineDurationMs, b.timelineDurationMs) / 2
            )
            if (safe < 100L) continue
            val transition = ClipTransition(type = type, durationMs = safe)
            result = updateClip(result, a.id) { it.copy(transitionOut = transition) }
            result = updateClip(result, b.id) { it.copy(transitionIn = transition) }
        }
        return result
    }

    /** Clears every transition on the main track. */
    fun clearAllTransitions(project: Project): Project {
        val track = project.trackByType(TrackType.VIDEO_MAIN) ?: return project
        return updateTrack(project, track.id) { t ->
            t.copy(clips = t.clips.map { it.copy(transitionIn = null, transitionOut = null) })
        }
    }

    /**
     * **Auto-captions.** Turns a list of detected speech windows into real text clips on the TEXT
     * track, styled with the given spec, so captions are editable like any other element.
     */
    fun buildCaptionClips(
        project: Project,
        cues: List<SubtitleCue>,
        style: TextStyleSpec = TextStyleSpec()
    ): Project {
        if (cues.isEmpty()) return project
        val track = project.tracks.firstOrNull { it.type == TrackType.TEXT && !it.isLocked }
            ?: return project
        val existing = track.clips.filterNot { it.sourceName == CAPTION_SOURCE_NAME }
        val captions = cues.sortedBy { it.startTimeMs }.map { cue ->
            val span = max(MIN_CLIP_DURATION_MS, cue.endTimeMs - cue.startTimeMs)
            TimelineClip(
                trackId = track.id,
                sourceUri = "zero://caption",
                sourceName = CAPTION_SOURCE_NAME,
                mediaKind = MediaKind.TEXT_ELEMENT,
                sourceDurationMs = span,
                startTimeOnTrackMs = max(0L, cue.startTimeMs),
                trimStartMs = 0L,
                trimEndMs = span,
                hasAudioTrack = false,
                textSpec = style.copy(text = cue.text, languageTag = cue.languageTag),
                transform = ClipTransform(positionY = 0.62f)
            )
        }
        return updateTrack(project, track.id) { t ->
            t.copy(clips = (existing + captions).sortedBy { it.startTimeOnTrackMs })
        }
    }

    const val CAPTION_SOURCE_NAME = "Auto Caption"
}

data class ImportedMediaSource(
    val uri: String,
    val displayName: String,
    val mediaKind: MediaKind,
    val durationMs: Long,
    val width: Int = 1080,
    val height: Int = 1920,
    val waveformPeaks: List<Float> = emptyList(),
    val hasAudioTrack: Boolean = true,
    val rotationDegreesMeta: Int = 0,
    val mimeType: String = "",
    val thumbnailUri: String? = null,
    val fileSizeBytes: Long = 0L
)

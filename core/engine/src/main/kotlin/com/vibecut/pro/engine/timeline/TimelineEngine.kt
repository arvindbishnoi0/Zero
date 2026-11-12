package com.vibecut.pro.engine.timeline

import com.vibecut.pro.engine.model.*
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

object TimelineEngine {

    private const val MIN_CLIP_DURATION_MS = 100L

    fun importMediaClips(
        project: Project,
        items: List<ImportedMediaSource>,
        targetTrackType: TrackType = TrackType.VIDEO_MAIN,
        insertAtPlayheadMs: Long? = null
    ): Project {
        if (items.isEmpty()) return project
        val targetTrack = project.tracks.firstOrNull { it.type == targetTrackType && !it.isLocked }
            ?: return project

        var cursorMs = if (targetTrack.type == TrackType.VIDEO_MAIN && project.magneticTimeline) {
            targetTrack.durationMs
        } else {
            insertAtPlayheadMs ?: targetTrack.durationMs
        }

        val addedClips = items.map { src ->
            val safeDuration = max(MIN_CLIP_DURATION_MS, src.durationMs)
            val defaultTransform = if (targetTrack.type == TrackType.VIDEO_PIP) {
                ClipTransform(positionX = 0.25f, positionY = 0.25f, scaleX = 0.42f, scaleY = 0.42f)
            } else {
                ClipTransform()
            }
            val clip = TimelineClip(
                id = UUID.randomUUID().toString(),
                trackId = targetTrack.id,
                sourceUri = src.uri,
                sourceName = src.displayName,
                mediaKind = src.mediaKind,
                sourceDurationMs = if (src.mediaKind == MediaKind.IMAGE) max(30_000L, safeDuration) else safeDuration,
                startTimeOnTrackMs = cursorMs,
                trimStartMs = 0L,
                trimEndMs = safeDuration,
                transform = defaultTransform,
                waveformPeaks = src.waveformPeaks,
                hasAudioTrack = src.hasAudioTrack && src.mediaKind != MediaKind.IMAGE,
                sourceWidth = src.width,
                sourceHeight = src.height,
                rotationDegreesMeta = src.rotationDegreesMeta,
                mimeType = src.mimeType,
                thumbnailUri = src.thumbnailUri
            )
            cursorMs += clip.timelineDurationMs
            clip
        }

        return updateTrack(project, targetTrack.id) { track ->
            val updated = track.clips + addedClips
            if (track.type == TrackType.VIDEO_MAIN && project.magneticTimeline) {
                track.copy(clips = reflowMagneticClips(updated))
            } else {
                track.copy(clips = updated.sortedBy { it.startTimeOnTrackMs })
            }
        }
    }

    fun setProjectAspectRatio(project: Project, aspectRatio: AspectRatioPreset): Project =
        project.copy(
            aspectRatio = aspectRatio,
            updatedAtMs = System.currentTimeMillis(),
            revision = project.revision + 1
        )

    fun trimClip(
        project: Project,
        clipId: String,
        newTrimStartMs: Long,
        newTrimEndMs: Long
    ): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project

        return updateTrack(project, track.id) { t ->
            val updated = t.clips.map { clip ->
                if (clip.id != clipId) return@map clip
                val maxSource = max(MIN_CLIP_DURATION_MS, clip.sourceDurationMs)
                val safeStart = newTrimStartMs.coerceIn(0L, maxSource - MIN_CLIP_DURATION_MS)
                val safeEnd = newTrimEndMs.coerceIn(safeStart + MIN_CLIP_DURATION_MS, maxSource)
                clip.copy(trimStartMs = safeStart, trimEndMs = safeEnd)
            }
            maybeMagneticReflow(project, t, updated)
        }
    }

    /**
     * Splits a clip at [playheadMs] on the timeline, accurately mapping timeline time to
     * source media time even when constant speed or variable speed curves are active.
     */
    fun splitClipAt(project: Project, clipId: String, playheadMs: Long): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return project

        val offsetInClipMs = playheadMs - clip.startTimeOnTrackMs
        if (offsetInClipMs <= MIN_CLIP_DURATION_MS ||
            offsetInClipMs >= clip.timelineDurationMs - MIN_CLIP_DURATION_MS
        ) {
            return project
        }

        val splitSourceMs = mapTimelineOffsetToSourceMs(clip, offsetInClipMs)
        if (splitSourceMs <= clip.trimStartMs + 50L || splitSourceMs >= clip.trimEndMs - 50L) {
            return project
        }

        val leftKeyframes = clip.keyframes.filter { it.timeOffsetMs <= offsetInClipMs }
        val rightKeyframes = clip.keyframes
            .filter { it.timeOffsetMs > offsetInClipMs }
            .map { it.copy(id = UUID.randomUUID().toString(), timeOffsetMs = it.timeOffsetMs - offsetInClipMs) }

        val leftClip = clip.copy(
            trimEndMs = splitSourceMs,
            transitionOut = null,
            keyframes = leftKeyframes
        )
        val rightClip = clip.copy(
            id = UUID.randomUUID().toString(),
            startTimeOnTrackMs = leftClip.endTimeOnTrackMs,
            trimStartMs = splitSourceMs,
            transitionIn = null,
            effects = clip.effects.map { it.copy(id = UUID.randomUUID().toString()) },
            keyframes = rightKeyframes
        )

        return updateTrack(project, track.id) { t ->
            val nextList = mutableListOf<TimelineClip>()
            for (existing in t.clips) {
                if (existing.id == clipId) {
                    nextList.add(leftClip)
                    nextList.add(rightClip)
                } else {
                    nextList.add(existing)
                }
            }
            maybeMagneticReflow(project, t, nextList)
        }
    }

    /**
     * Cuts out an interior timeline span [cutStartMs, cutEndMs] from a clip and joins/ripples.
     */
    fun cutRangeFromClip(
        project: Project,
        clipId: String,
        cutStartMs: Long,
        cutEndMs: Long
    ): Project {
        if (cutEndMs <= cutStartMs + 40L) return project
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return project

        val clampedStart = max(clip.startTimeOnTrackMs, cutStartMs)
        val clampedEnd = min(clip.endTimeOnTrackMs, cutEndMs)
        if (clampedEnd <= clampedStart + 40L) return project

        if (clampedStart <= clip.startTimeOnTrackMs + MIN_CLIP_DURATION_MS) {
            val newTrimStart = mapTimelineOffsetToSourceMs(clip, clampedEnd - clip.startTimeOnTrackMs)
            return trimClip(project, clipId, newTrimStart, clip.trimEndMs)
        }
        if (clampedEnd >= clip.endTimeOnTrackMs - MIN_CLIP_DURATION_MS) {
            val newTrimEnd = mapTimelineOffsetToSourceMs(clip, clampedStart - clip.startTimeOnTrackMs)
            return trimClip(project, clipId, clip.trimStartMs, newTrimEnd)
        }
        val srcCutStart = mapTimelineOffsetToSourceMs(clip, clampedStart - clip.startTimeOnTrackMs)
        val srcCutEnd = mapTimelineOffsetToSourceMs(clip, clampedEnd - clip.startTimeOnTrackMs)

        val leftClip = clip.copy(trimEndMs = srcCutStart, transitionOut = null)
        val rightStart = if (track.type == TrackType.VIDEO_MAIN && project.magneticTimeline) {
            leftClip.endTimeOnTrackMs
        } else {
            clampedEnd
        }
        val rightClip = clip.copy(
            id = UUID.randomUUID().toString(),
            startTimeOnTrackMs = rightStart,
            trimStartMs = srcCutEnd,
            transitionIn = null,
            effects = clip.effects.map { it.copy(id = UUID.randomUUID().toString()) }
        )

        return updateTrack(project, track.id) { t ->
            val nextList = mutableListOf<TimelineClip>()
            for (existing in t.clips) {
                if (existing.id == clipId) {
                    nextList.add(leftClip)
                    nextList.add(rightClip)
                } else {
                    nextList.add(existing)
                }
            }
            maybeMagneticReflow(project, t, nextList)
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
                sourceName = "${original.sourceName} (Copy)",
                startTimeOnTrackMs = original.endTimeOnTrackMs,
                effects = original.effects.map { it.copy(id = UUID.randomUUID().toString()) },
                keyframes = original.keyframes.map { it.copy(id = UUID.randomUUID().toString()) }
            )
            val nextList = t.clips.toMutableList().apply { add(idx + 1, duplicate) }
            maybeMagneticReflow(project, t, nextList)
        }
    }

    fun deleteClip(project: Project, clipId: String): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project

        return updateTrack(project, track.id) { t ->
            val nextList = t.clips.filterNot { it.id == clipId }
            maybeMagneticReflow(project, t, nextList)
        }
    }

    fun reorderClips(project: Project, trackId: String, fromIndex: Int, toIndex: Int): Project {
        val track = project.tracks.firstOrNull { it.id == trackId } ?: return project
        if (track.isLocked) return project
        if (fromIndex !in track.clips.indices || toIndex !in track.clips.indices || fromIndex == toIndex) {
            return project
        }

        return updateTrack(project, trackId) { t ->
            val mutable = t.clips.toMutableList()
            val moved = mutable.removeAt(fromIndex)
            mutable.add(toIndex, moved)
            if (t.type == TrackType.VIDEO_MAIN || project.magneticTimeline) {
                t.copy(clips = reflowMagneticClips(mutable))
            } else {
                t.copy(clips = mutable)
            }
        }
    }

    fun moveClipToTimestamp(
        project: Project,
        clipId: String,
        requestedStartMs: Long,
        playheadMs: Long = 0L,
        snapThresholdMs: Long = 120L
    ): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        if (track.type == TrackType.VIDEO_MAIN && project.magneticTimeline) {
            val sorted = track.clips.sortedBy { it.startTimeOnTrackMs }
            val currentIdx = sorted.indexOfFirst { it.id == clipId }
            if (currentIdx < 0) return project
            val targetIdx = sorted.indexOfLast { it.startTimeOnTrackMs <= requestedStartMs }
                .coerceIn(0, sorted.lastIndex)
            return reorderClips(project, track.id, currentIdx, targetIdx)
        }

        val finalStart = if (project.snappingEnabled) {
            computeSnappedTimestamp(project, clipId, max(0L, requestedStartMs), playheadMs, snapThresholdMs)
        } else {
            max(0L, requestedStartMs)
        }

        return updateClip(project, clipId) { clip ->
            clip.copy(startTimeOnTrackMs = finalStart)
        }
    }

    fun computeSnappedTimestamp(
        project: Project,
        movingClipId: String,
        candidateMs: Long,
        playheadMs: Long,
        thresholdMs: Long = 120L
    ): Long {
        val anchors = mutableListOf(0L, playheadMs)
        for (track in project.tracks) {
            if (track.isHidden) continue
            for (clip in track.clips) {
                if (clip.id == movingClipId) continue
                anchors.add(clip.startTimeOnTrackMs)
                anchors.add(clip.endTimeOnTrackMs)
                clip.beatMarkersMs.forEach { beat ->
                    anchors.add(clip.startTimeOnTrackMs + beat)
                }
            }
        }
        val nearest = anchors.minByOrNull { abs(it - candidateMs) } ?: return candidateMs
        return if (abs(nearest - candidateMs) <= thresholdMs) max(0L, nearest) else candidateMs
    }

    fun mergeClips(project: Project, clipIds: List<String>): Project {
        if (clipIds.size < 2) return project
        val firstTrack = project.findTrackForClip(clipIds.first()) ?: return project
        if (firstTrack.isLocked) return project

        return updateTrack(project, firstTrack.id) { t ->
            val selected = t.clips.filter { it.id in clipIds }.sortedBy { it.startTimeOnTrackMs }
            if (selected.size < 2) return@updateTrack t

            val allSameSource = selected.all {
                it.sourceUri == selected.first().sourceUri &&
                    it.mediaKind == selected.first().mediaKind &&
                    !it.isFrozenFrame
            }
            if (allSameSource) {
                val base = selected.first()
                val mergedClip = base.copy(
                    id = UUID.randomUUID().toString(),
                    sourceName = "${base.sourceName} (Merged)",
                    trimStartMs = selected.minOf { it.trimStartMs },
                    trimEndMs = selected.maxOf { it.trimEndMs },
                    transitionOut = selected.last().transitionOut
                )
                val firstIdx = t.clips.indexOfFirst { it.id == selected.first().id }
                val remaining = t.clips.filterNot { it.id in clipIds }.toMutableList()
                remaining.add(firstIdx.coerceIn(0, remaining.size), mergedClip)
                maybeMagneticReflow(project, t, remaining)
            } else {
                var cursor = selected.first().startTimeOnTrackMs
                val packedIds = selected.map { it.id }.toSet()
                val packed = selected.mapIndexed { i, c ->
                    val next = c.copy(
                        startTimeOnTrackMs = cursor,
                        transitionIn = if (i > 0 && c.transitionIn == null) {
                            ClipTransition(TransitionType.DISSOLVE, 350L)
                        } else c.transitionIn
                    )
                    cursor += next.timelineDurationMs
                    next
                }
                val firstIdx = t.clips.indexOfFirst { it.id in packedIds }
                val remaining = t.clips.filterNot { it.id in packedIds }.toMutableList()
                remaining.addAll(firstIdx.coerceIn(0, remaining.size), packed)
                maybeMagneticReflow(project, t, remaining)
            }
        }
    }

    fun setCrop(project: Project, clipId: String, cropRect: CropRect): Project =
        updateClip(project, clipId) { clip ->
            clip.copy(
                crop = CropRect(
                    left = cropRect.left.coerceIn(0f, 0.9f),
                    top = cropRect.top.coerceIn(0f, 0.9f),
                    right = cropRect.right.coerceIn(cropRect.left + 0.05f, 1f),
                    bottom = cropRect.bottom.coerceIn(cropRect.top + 0.05f, 1f),
                    aspectPresetId = cropRect.aspectPresetId
                )
            )
        }

    fun rotateClip90(project: Project, clipId: String, clockwise: Boolean = true): Project =
        updateClip(project, clipId) { clip ->
            val delta = if (clockwise) 90f else -90f
            val nextDeg = ((clip.transform.rotationDegrees + delta) % 360f + 360f) % 360f
            clip.copy(transform = clip.transform.copy(rotationDegrees = nextDeg))
        }

    fun setClipTransform(project: Project, clipId: String, transform: ClipTransform): Project =
        updateClip(project, clipId) { clip ->
            clip.copy(
                transform = transform.copy(
                    scaleX = transform.scaleX.coerceIn(0.1f, 6.0f),
                    scaleY = transform.scaleY.coerceIn(0.1f, 6.0f),
                    opacity = transform.opacity.coerceIn(0f, 1f)
                )
            )
        }

    fun toggleFlipHorizontal(project: Project, clipId: String): Project =
        updateClip(project, clipId) { clip ->
            clip.copy(transform = clip.transform.copy(flipHorizontal = !clip.transform.flipHorizontal))
        }

    fun toggleFlipVertical(project: Project, clipId: String): Project =
        updateClip(project, clipId) { clip ->
            clip.copy(transform = clip.transform.copy(flipVertical = !clip.transform.flipVertical))
        }

    fun toggleReverseClip(project: Project, clipId: String): Project =
        updateClip(project, clipId) { clip ->
            clip.copy(isReversed = !clip.isReversed)
        }

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
                    speed = speed.coerceIn(0.1f, 10.0f),
                    speedCurvePreset = curvePreset,
                    speedCurvePoints = customPoints
                )
            }
            maybeMagneticReflow(project, t, updated)
        }
    }

    fun insertFreezeFrame(
        project: Project,
        clipId: String,
        playheadMs: Long,
        freezeDurationMs: Long = 2_000L
    ): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return project
        if (clip.mediaKind != MediaKind.VIDEO && clip.mediaKind != MediaKind.IMAGE) return project

        val clampedOffset = (playheadMs - clip.startTimeOnTrackMs)
            .coerceIn(0L, clip.timelineDurationMs)
        val freezeSourceMs = mapTimelineOffsetToSourceMs(clip, clampedOffset)

        val frozenClip = TimelineClip(
            id = UUID.randomUUID().toString(),
            trackId = track.id,
            sourceUri = clip.sourceUri,
            sourceName = "${clip.sourceName} (Freeze)",
            mediaKind = clip.mediaKind,
            sourceDurationMs = freezeDurationMs,
            startTimeOnTrackMs = clip.startTimeOnTrackMs + clampedOffset,
            trimStartMs = 0L,
            trimEndMs = freezeDurationMs,
            isFrozenFrame = clip.mediaKind == MediaKind.VIDEO,
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
                val canSplitAfter = freezeSourceMs < existing.trimEndMs - MIN_CLIP_DURATION_MS
                if (canSplitBefore) {
                    next.add(existing.copy(trimEndMs = freezeSourceMs, transitionOut = null))
                }
                next.add(frozenClip)
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

    fun stepPlayheadByFrames(
        playheadMs: Long,
        fps: Int,
        deltaFrames: Int,
        maxDurationMs: Long
    ): Long {
        val safeFps = fps.coerceIn(1, 120)
        val frameDurationMs = (1000.0 / safeFps).roundToLong().coerceAtLeast(1L)
        return (playheadMs + deltaFrames * frameDurationMs).coerceIn(0L, max(0L, maxDurationMs))
    }

    fun setTimelineZoom(project: Project, zoom: Float): Project =
        project.copy(timelineZoom = zoom.coerceIn(0.25f, 5.0f), updatedAtMs = System.currentTimeMillis())

    fun addTrack(project: Project, type: TrackType, customName: String? = null): Project {
        val countOfType = project.tracks.count { it.type == type } + 1
        val track = Track(
            id = UUID.randomUUID().toString(),
            name = customName ?: "${type.label} $countOfType",
            type = type
        )
        return project.copy(
            tracks = project.tracks + track,
            updatedAtMs = System.currentTimeMillis(),
            revision = project.revision + 1
        )
    }

    fun toggleTrackLock(project: Project, trackId: String): Project =
        updateTrack(project, trackId) { it.copy(isLocked = !it.isLocked) }

    fun toggleTrackHidden(project: Project, trackId: String): Project =
        updateTrack(project, trackId) { it.copy(isHidden = !it.isHidden) }

    fun toggleTrackMute(project: Project, trackId: String): Project =
        updateTrack(project, trackId) { it.copy(isMuted = !it.isMuted) }

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

    fun reorderEffect(project: Project, clipId: String, effectId: String, direction: Int): Project =
        updateClip(project, clipId) { clip ->
            val idx = clip.effects.indexOfFirst { it.id == effectId }
            val targetIdx = idx + direction
            if (idx < 0 || targetIdx !in clip.effects.indices) return@updateClip clip
            val mutable = clip.effects.toMutableList()
            val moved = mutable.removeAt(idx)
            mutable.add(targetIdx, moved)
            clip.copy(effects = mutable)
        }

    fun removeEffect(project: Project, clipId: String, effectId: String): Project =
        updateClip(project, clipId) { clip ->
            clip.copy(effects = clip.effects.filterNot { it.id == effectId })
        }

    fun clearClipEffects(project: Project, clipId: String): Project =
        updateClip(project, clipId) { clip ->
            clip.copy(effects = emptyList())
        }

    fun applyEffectsToAllMainClips(project: Project, effects: List<AppliedEffect>): Project {
        val mainTrack = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN } ?: return project
        if (mainTrack.isLocked) return project
        return updateTrack(project, mainTrack.id) { t ->
            t.copy(
                clips = t.clips.map { clip ->
                    clip.copy(effects = effects.map { it.copy(id = UUID.randomUUID().toString()) })
                }
            )
        }
    }

    fun setColorGrade(project: Project, clipId: String, grade: ColorGradeConfig): Project =
        updateClip(project, clipId) { clip -> clip.copy(colorGrade = grade) }

    fun applyColorGradeToAllMainClips(project: Project, grade: ColorGradeConfig): Project {
        val mainTrack = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN } ?: return project
        if (mainTrack.isLocked) return project
        return updateTrack(project, mainTrack.id) { t ->
            t.copy(clips = t.clips.map { it.copy(colorGrade = grade) })
        }
    }

    fun setClipTransition(
        project: Project,
        clipId: String,
        transitionIn: ClipTransition?,
        transitionOut: ClipTransition? = null
    ): Project = updateClip(project, clipId) { clip ->
        clip.copy(
            transitionIn = transitionIn?.copy(durationMs = transitionIn.durationMs.coerceIn(100L, 3000L)),
            transitionOut = transitionOut?.copy(durationMs = transitionOut.durationMs.coerceIn(100L, 3000L))
        )
    }

    fun applyTransitionToAllMainClips(project: Project, transition: ClipTransition): Project {
        val mainTrack = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN } ?: return project
        if (mainTrack.isLocked) return project
        val clamped = transition.copy(durationMs = transition.durationMs.coerceIn(100L, 3000L))
        return updateTrack(project, mainTrack.id) { t ->
            t.copy(
                clips = t.clips.mapIndexed { idx, clip ->
                    if (idx == 0) clip else clip.copy(transitionIn = clamped)
                }
            )
        }
    }

    fun upsertKeyframe(project: Project, clipId: String, keyframe: Keyframe): Project =
        updateClip(project, clipId) { clip ->
            val clampedOffset = keyframe.timeOffsetMs.coerceIn(0L, clip.timelineDurationMs)
            val normalized = keyframe.copy(
                timeOffsetMs = clampedOffset,
                value = keyframe.value.coerceIn(keyframe.property.minValue, keyframe.property.maxValue)
            )
            val filtered = clip.keyframes.filterNot {
                it.id == normalized.id ||
                    (it.property == normalized.property && abs(it.timeOffsetMs - clampedOffset) <= 40L)
            }
            clip.copy(keyframes = (filtered + normalized).sortedBy { it.timeOffsetMs })
        }

    fun removeKeyframe(project: Project, clipId: String, keyframeId: String): Project =
        updateClip(project, clipId) { clip ->
            clip.copy(keyframes = clip.keyframes.filterNot { it.id == keyframeId })
        }

    fun addTextClip(
        project: Project,
        textSpec: TextStyleSpec,
        startMs: Long,
        durationMs: Long = 3_000L
    ): Project {
        val textTrack = project.tracks.firstOrNull { it.type == TrackType.TEXT && !it.isLocked }
            ?: return project
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

    fun addStickerClip(
        project: Project,
        stickerSpec: StickerSpec,
        startMs: Long,
        durationMs: Long = 2_500L
    ): Project {
        val stickerTrack = project.tracks.firstOrNull { it.type == TrackType.STICKER && !it.isLocked }
            ?: return project
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

    fun mapTimelineOffsetToSourceMs(clip: TimelineClip, timelineOffsetMs: Long): Long {
        if (clip.isFrozenFrame) return clip.freezeFrameSourceMs
        if (clip.mediaKind == MediaKind.IMAGE) {
            return (clip.trimStartMs + timelineOffsetMs).coerceIn(clip.trimStartMs, clip.trimEndMs)
        }
        val clampedOffset = timelineOffsetMs.coerceIn(0L, clip.timelineDurationMs)
        val activePoints = when {
            clip.speedCurvePoints.size >= 2 -> clip.speedCurvePoints
            clip.speedCurvePreset != SpeedCurvePreset.NONE -> clip.speedCurvePreset.defaultPoints
            else -> emptyList()
        }
        if (activePoints.size < 2) {
            val deltaSource = (clampedOffset * clip.speed.coerceIn(0.1f, 10f)).roundToLong()
            return (clip.trimStartMs + deltaSource).coerceIn(clip.trimStartMs, clip.trimEndMs)
        }
        val sorted = activePoints.sortedBy { it.normalizedPosition }
        val steps = 80
        val rawSpan = clip.trimmedSourceDurationMs.toDouble()
        var accumulatedTimelineMs = 0.0
        for (i in 0 until steps) {
            val u0 = i.toFloat() / steps
            val uMid = (i + 0.5f) / steps
            val spd = TimelineClip.sampleSpeedCurve(sorted, uMid).coerceIn(0.1f, 10f)
            val sliceSourceMs = rawSpan / steps
            val sliceTimelineMs = sliceSourceMs / spd
            if (accumulatedTimelineMs + sliceTimelineMs >= clampedOffset) {
                val frac = ((clampedOffset - accumulatedTimelineMs) / sliceTimelineMs).coerceIn(0.0, 1.0)
                val uExact = u0 + (frac / steps)
                return (clip.trimStartMs + (rawSpan * uExact).roundToLong())
                    .coerceIn(clip.trimStartMs, clip.trimEndMs)
            }
            accumulatedTimelineMs += sliceTimelineMs
        }
        return clip.trimEndMs
    }

    fun reflowMagneticClips(clips: List<TimelineClip>): List<TimelineClip> {
        var cursorMs = 0L
        return clips.map { clip ->
            val updated = clip.copy(startTimeOnTrackMs = cursorMs)
            cursorMs += updated.timelineDurationMs
            updated
        }
    }

    private fun maybeMagneticReflow(
        project: Project,
        track: Track,
        clips: List<TimelineClip>
    ): Track {
        return if (track.type == TrackType.VIDEO_MAIN && project.magneticTimeline) {
            track.copy(clips = reflowMagneticClips(clips))
        } else {
            track.copy(clips = clips.sortedBy { it.startTimeOnTrackMs })
        }
    }

    fun updateClip(
        project: Project,
        clipId: String,
        mutator: (TimelineClip) -> TimelineClip
    ): Project {
        val track = project.findTrackForClip(clipId) ?: return project
        if (track.isLocked) return project
        return updateTrack(project, track.id) { t ->
            t.copy(clips = t.clips.map { if (it.id == clipId) mutator(it) else it })
        }
    }

    fun updateTrack(
        project: Project,
        trackId: String,
        mutator: (Track) -> Track
    ): Project {
        val nextTracks = project.tracks.map { if (it.id == trackId) mutator(it) else it }
        return project.copy(
            tracks = nextTracks,
            updatedAtMs = System.currentTimeMillis(),
            revision = project.revision + 1
        )
    }
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

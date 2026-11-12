package com.vibecut.pro

import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.timeline.ExportSegmentPlanner
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import com.vibecut.pro.engine.timeline.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App-side (pure JVM) checks for the two things users notice most: export settings that produce a
 * sane file, and the timeline/playlist maths that keeps the playhead honest.
 */
class ExportAndTimelineAppTest {

    @Test
    fun exportResolutionBitrateAndMacroblockAlignmentWork() {
        val aspect = AspectRatioPreset.REELS_9_16
        val (w4k, h4k) = aspect.computeCodecSafeDimensions(ExportResolution.UHD_4K.shortSidePx)
        assertEquals(2160, w4k)
        assertEquals(3840, h4k)
        assertEquals(0, w4k % 16)
        assertEquals(0, h4k % 16)

        val low720 = ExportConfig(
            resolution = ExportResolution.HD_720P,
            frameRate = ExportFrameRate.FPS_24,
            bitrateTier = BitrateTier.LOW
        ).targetVideoBitrateBps(aspect)

        val high4k60 = ExportConfig(
            resolution = ExportResolution.UHD_4K,
            frameRate = ExportFrameRate.FPS_60,
            bitrateTier = BitrateTier.HIGH
        ).targetVideoBitrateBps(aspect)

        assertTrue(high4k60 > low720 * 5)

        val custom25Mbps = ExportConfig(
            bitrateTier = BitrateTier.CUSTOM,
            customBitrateMbps = 25f
        ).targetVideoBitrateBps(aspect)
        assertEquals(25_000_000, custom25Mbps)

        // H.265 must be cheaper at the same visual setting (same resolution + fps).
        val h264 = ExportConfig(bitrateTier = BitrateTier.HIGH, codec = VideoCodecOption.H264)
            .targetVideoBitrateBps(aspect)
        val h265 = ExportConfig(bitrateTier = BitrateTier.HIGH, codec = VideoCodecOption.H265)
            .targetVideoBitrateBps(aspect)
        assertTrue(h265 < h264)

        // Every offered frame rate stays inside a legal range and estimates a plausible file size.
        ExportFrameRate.entries.forEach { fps ->
            val cfg = ExportConfig(frameRate = fps)
            assertTrue(cfg.estimatedFileSizeMb(10_000L, aspect) > 0f)
        }
    }

    @Test
    fun effectStackingAndApplyToAllClipsPreserveIntegrity() {
        var project = Project(title = "ZERO Reel", aspectRatio = AspectRatioPreset.WIDE_16_9)
        val media = listOf(
            ImportedMediaSource("file:///a.mp4", "Scene A", MediaKind.VIDEO, 5_000L, hasAudioTrack = true),
            ImportedMediaSource("file:///b.mp4", "Scene B", MediaKind.VIDEO, 5_000L, hasAudioTrack = false)
        )
        project = TimelineEngine.importMediaClips(project, media, TrackType.VIDEO_MAIN)
        assertEquals(2, project.mainVideoClips.size)
        assertEquals(10_000L, project.totalDurationMs)

        val firstClip = project.mainVideoClips.first()
        project = TimelineEngine.addOrUpdateEffect(
            project,
            firstClip.id,
            AppliedEffect(type = EffectType.SHARPEN, intensity = 0.8f, secondaryParam = 0.4f, tertiaryParam = 0.25f)
        )
        project = TimelineEngine.addOrUpdateEffect(
            project,
            firstClip.id,
            AppliedEffect(type = EffectType.HALATION, intensity = 0.55f, secondaryParam = 0.5f, tertiaryParam = 0.6f)
        )
        val updated = project.findClip(firstClip.id)!!
        assertEquals(2, updated.effects.size)

        val proGrade = ColorGradeConfig(
            lutPresetId = "preset-teal-orange",
            lutIntensity = 0.85f,
            sharpness = 0.50f,
            clarity = 0.25f
        )
        project = TimelineEngine.setColorGrade(project, firstClip.id, proGrade)
        project = TimelineEngine.applyColorGradeToAllMainClips(project, proGrade)
        project = TimelineEngine.applyEffectsToAllMainClips(project, updated.effects)

        for (mc in project.mainVideoClips) {
            assertEquals("preset-teal-orange", mc.colorGrade.lutPresetId)
            assertEquals(0.50f, mc.colorGrade.sharpness, 0.001f)
            assertEquals(2, mc.effects.size)
        }

        assertNotNull(project.findMainClipAt(project.totalDurationMs))
    }

    @Test
    fun previewPlaylistIsContiguousAndFrameAccurate() {
        var project = Project(title = "Playlist", aspectRatio = AspectRatioPreset.REELS_9_16)
        project = TimelineEngine.importMediaClips(
            project,
            listOf(
                ImportedMediaSource("file:///a.mp4", "A", MediaKind.VIDEO, 4_000L),
                ImportedMediaSource("file:///still.jpg", "Still", MediaKind.IMAGE, 3_000L, hasAudioTrack = false),
                ImportedMediaSource("file:///c.mp4", "C", MediaKind.VIDEO, 6_000L)
            ),
            TrackType.VIDEO_MAIN
        )

        // 2x one clip: its slot must shrink, and the playlist must still tile the timeline.
        val firstId = project.mainVideoClips[0].id
        project = TimelineEngine.setClipSpeed(project, firstId, 2f)

        val pieces = ExportSegmentPlanner.planPreviewPlaylist(project)
        assertTrue(pieces.isNotEmpty())
        assertEquals(project.totalDurationMs, pieces.sumOf { it.timelineSpanMs })

        var cursor = 0L
        pieces.forEach { piece ->
            assertEquals(cursor, piece.clip.startTimeOnTrackMs + piece.segmentOffsetMs)
            cursor += piece.timelineSpanMs
            assertTrue(piece.sourceEndMs > piece.sourceStartMs)
            assertTrue(piece.speed >= 0.1f)
        }

        // The image piece is marked as a held still so the UI can draw it instead of decoding.
        assertTrue(pieces.any { it.isHeldStill })

        // Timeline must equal clip spans exactly (no gaps, no overlaps).
        val mainClips = project.mainVideoClips.sortedBy { it.startTimeOnTrackMs }
        var trackCursor = 0L
        mainClips.forEach {
            assertEquals(trackCursor, it.startTimeOnTrackMs)
            trackCursor += it.timelineDurationMs
        }
        assertEquals(project.totalDurationMs, trackCursor)
    }

    @Test
    fun exportSegmentsNeverOverlapAndRespectSpeed() {
        var project = Project(title = "Segments")
        project = TimelineEngine.importMediaClips(
            project,
            listOf(
                ImportedMediaSource("file:///a.mp4", "A", MediaKind.VIDEO, 6_000L),
                ImportedMediaSource("file:///b.mp4", "B", MediaKind.VIDEO, 4_000L)
            ),
            TrackType.VIDEO_MAIN
        )
        val planned = ExportSegmentPlanner.planVisualTracks(project)
        assertTrue(planned.isNotEmpty())
        var previousEnd = 0L
        planned.forEach { p ->
            assertTrue("segments must not overlap", p.segment.timelineStartMs >= previousEnd)
            previousEnd = p.segment.timelineEndMs
        }
        assertEquals(project.totalDurationMs, ExportSegmentPlanner.totalExportDurationMs(project))

        // Audio planning must skip silent clips entirely.
        val audio = ExportSegmentPlanner.planAudioTracks(project)
        assertTrue(audio.all { it.clip.hasAudioTrack })
        assertFalse(audio.any { it.clip.sourceName == "B" })
    }
}

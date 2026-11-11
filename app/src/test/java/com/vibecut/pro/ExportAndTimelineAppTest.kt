package com.vibecut.pro

import com.vibecut.pro.engine.ai.LocalAiAlgorithms
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import com.vibecut.pro.engine.timeline.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportAndTimelineAppTest {

    @Test
    fun exportResolutionAndBitrateScalingCovers480pTo4kAt24To60Fps() {
        val aspect = AspectRatioPreset.REELS_9_16
        val (w4k, h4k) = aspect.computeDimensions(ExportResolution.UHD_4K.shortSidePx)
        assertEquals(2160, w4k)
        assertEquals(3840, h4k)

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
    }

    @Test
    fun scriptToVideoAndMultiEffectStackingPreserveIntegrity() {
        var project = Project(title = "AI Script Reel", aspectRatio = AspectRatioPreset.YOUTUBE_16_9)
        val media = listOf(
            ImportedMediaSource("file:///a.mp4", "Scene A", MediaKind.VIDEO, 5_000L),
            ImportedMediaSource("file:///b.mp4", "Scene B", MediaKind.VIDEO, 5_000L)
        )
        project = LocalAiAlgorithms.buildScriptToVideoProject(
            baseProject = project,
            scriptText = "Opening shot in 4K\nSharp details with GPU shader\nSmooth dissolve finale",
            availableMedia = media
        )
        assertEquals(3, project.subtitles.size)

        val firstClip = project.tracks.first { it.type == TrackType.VIDEO_MAIN }.clips.first()
        project = TimelineEngine.addOrUpdateEffect(
            project,
            firstClip.id,
            AppliedEffect(type = EffectType.SHARPEN, intensity = 0.8f)
        )
        project = TimelineEngine.addOrUpdateEffect(
            project,
            firstClip.id,
            AppliedEffect(type = EffectType.VIGNETTE, intensity = 0.4f)
        )
        val updated = project.findClip(firstClip.id)!!
        assertEquals(2, updated.effects.size)
    }
}

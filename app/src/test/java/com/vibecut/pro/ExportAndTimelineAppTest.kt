package com.vibecut.pro

import com.vibecut.pro.engine.ai.LocalAiAlgorithms
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import com.vibecut.pro.engine.timeline.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    }

    @Test
    fun scriptToVideoMultiEffectStackingAndApplyToAllClipsPreserveIntegrity() {
        var project = Project(title = "AI Script Reel", aspectRatio = AspectRatioPreset.YOUTUBE_16_9)
        val media = listOf(
            ImportedMediaSource("file:///a.mp4", "Scene A", MediaKind.VIDEO, 5_000L, hasAudioTrack = true),
            ImportedMediaSource("file:///b.mp4", "Scene B", MediaKind.VIDEO, 5_000L, hasAudioTrack = false)
        )
        project = LocalAiAlgorithms.buildScriptToVideoProject(
            baseProject = project,
            scriptText = "Opening shot in 4K\nSharp details with GPU shader\nSmooth dissolve finale",
            availableMedia = media
        )
        assertEquals(3, project.subtitles.size)

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

        // Apply color grade & effects across all main clips
        val proGrade = ColorGradeConfig(
            lutPresetId = "preset-cinematic-gold",
            lutIntensity = 0.85f,
            sharpness = 0.50f,
            clarity = 0.25f
        )
        project = TimelineEngine.setColorGrade(project, firstClip.id, proGrade)
        project = TimelineEngine.applyColorGradeToAllMainClips(project, proGrade)
        project = TimelineEngine.applyEffectsToAllMainClips(project, updated.effects)

        for (mc in project.mainVideoClips) {
            assertEquals("preset-cinematic-gold", mc.colorGrade.lutPresetId)
            assertEquals(0.50f, mc.colorGrade.sharpness, 0.001f)
            assertEquals(2, mc.effects.size)
        }

        // Playhead at exact end of timeline still resolves last main clip cleanly
        assertNotNull(project.findMainClipAt(project.totalDurationMs))
    }
}

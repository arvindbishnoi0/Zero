package com.vibecut.pro.engine

import com.vibecut.pro.engine.ai.LocalAiAlgorithms
import com.vibecut.pro.engine.audio.AudioDspEngine
import com.vibecut.pro.engine.audio.BuiltInSfxPreset
import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.effects.GlslShaderGenerator
import com.vibecut.pro.engine.keyframes.KeyframeInterpolator
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.serialization.ProjectJsonCodec
import com.vibecut.pro.engine.subtitles.SubtitleCodec
import com.vibecut.pro.engine.templates.TemplateCatalog
import com.vibecut.pro.engine.templates.TemplateCategory
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import com.vibecut.pro.engine.timeline.TimelineEngine
import com.vibecut.pro.engine.timeline.TimelineSessionManager
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VibeCutEngineTest {

    private fun sampleSources() = listOf(
        ImportedMediaSource(
            uri = "file:///clip1.mp4",
            displayName = "Clip 1",
            mediaKind = MediaKind.VIDEO,
            durationMs = 6_000L,
            width = 1920,
            height = 1080,
            hasAudioTrack = true,
            thumbnailUri = "file:///thumb1.jpg"
        ),
        ImportedMediaSource(
            uri = "file:///clip2.mp4",
            displayName = "Clip 2",
            mediaKind = MediaKind.VIDEO,
            durationMs = 4_000L,
            width = 1080,
            height = 1920,
            hasAudioTrack = false,
            thumbnailUri = "file:///thumb2.jpg"
        )
    )

    @Test
    fun timelineImportTrimSplitCutFreezeAndMagneticReflowWork() {
        var project = Project(title = "Studio Test")
        project = TimelineEngine.importMediaClips(project, sampleSources(), TrackType.VIDEO_MAIN)
        val mainClips = project.mainVideoClips
        assertEquals(2, mainClips.size)
        assertEquals(10_000L, project.totalDurationMs)
        assertEquals(6_000L, mainClips[1].startTimeOnTrackMs)
        assertTrue(mainClips[0].hasAudioTrack)
        assertFalse(mainClips[1].hasAudioTrack)
        assertEquals("file:///thumb1.jpg", mainClips[0].thumbnailUri)

        // Trim first clip from 6s down to 4s -> magnetic timeline shifts clip 2 to 4_000ms
        project = TimelineEngine.trimClip(project, mainClips[0].id, 1_000L, 5_000L)
        val afterTrim = project.mainVideoClips
        assertEquals(4_000L, afterTrim[0].timelineDurationMs)
        assertEquals(4_000L, afterTrim[1].startTimeOnTrackMs)
        assertEquals(8_000L, project.totalDurationMs)

        // Split first clip at 2_000ms playhead
        project = TimelineEngine.splitClipAt(project, afterTrim[0].id, 2_000L)
        val afterSplit = project.mainVideoClips
        assertEquals(3, afterSplit.size)
        assertEquals(1_000L, afterSplit[0].trimStartMs)
        assertEquals(3_000L, afterSplit[0].trimEndMs)
        assertEquals(3_000L, afterSplit[1].trimStartMs)
        assertEquals(5_000L, afterSplit[1].trimEndMs)

        // Insert 1.5s freeze frame at 1_000ms
        project = TimelineEngine.insertFreezeFrame(project, afterSplit[0].id, 1_000L, 1_500L)
        val afterFreeze = project.mainVideoClips
        assertTrue(afterFreeze.any { it.isFrozenFrame && it.timelineDurationMs == 1_500L })
        assertEquals(9_500L, project.totalDurationMs)
    }

    @Test
    fun lockedTrackBlocksMutationsAndCoalescedUndoRedoRestoresExactState() {
        val session = TimelineSessionManager(Project(title = "Undo Test"))
        session.execute("Import") {
            TimelineEngine.importMediaClips(it, sampleSources(), TrackType.VIDEO_MAIN)
        }
        assertEquals(10_000L, session.currentProject.totalDurationMs)

        val mainTrackId = session.currentProject.mainVideoClips.first().trackId
        val firstClipId = session.currentProject.mainVideoClips.first().id

        // Rapid slider updates coalesce into a single undo entry
        val depthBeforeSlider = session.undoDepth
        for (step in 1..10) {
            session.executeCoalesced("sharp_$firstClipId", "Sharpen", windowMs = 5_000L) { proj ->
                TimelineEngine.setColorGrade(proj, firstClipId, ColorGradeConfig(sharpness = step * 0.1f))
            }
        }
        assertEquals(depthBeforeSlider + 1, session.undoDepth)
        assertEquals(1.0f, session.currentProject.findClip(firstClipId)!!.colorGrade.sharpness, 0.001f)
        session.undo()
        assertEquals(0.0f, session.currentProject.findClip(firstClipId)!!.colorGrade.sharpness, 0.001f)

        // Lock track and attempt delete -> should be blocked
        session.execute("Lock") { TimelineEngine.toggleTrackLock(it, mainTrackId) }
        val lockedBefore = session.currentProject
        val attempted = TimelineEngine.deleteClip(lockedBefore, firstClipId)
        assertEquals(2, attempted.tracks.first { it.id == mainTrackId }.clips.size)

        // Unlock and delete -> clip count becomes 1
        session.execute("Unlock") { TimelineEngine.toggleTrackLock(it, mainTrackId) }
        session.execute("Delete Clip") { TimelineEngine.deleteClip(it, firstClipId) }
        assertEquals(1, session.currentProject.tracks.first { it.id == mainTrackId }.clips.size)

        // Undo restores deleted clip; Redo re-deletes it
        assertNotNull(session.undo())
        assertEquals(2, session.currentProject.tracks.first { it.id == mainTrackId }.clips.size)
        assertNotNull(session.redo())
        assertEquals(1, session.currentProject.tracks.first { it.id == mainTrackId }.clips.size)
    }

    @Test
    fun speedCurvesAndKeyframesInterpolateSmoothly() {
        var project = TimelineEngine.importMediaClips(Project(), listOf(sampleSources().first()))
        val clipId = project.tracks.first().clips.first().id

        project = TimelineEngine.setClipSpeed(project, clipId, 1.0f, SpeedCurvePreset.BULLET)
        val curvedClip = project.findClip(clipId)!!
        assertTrue(curvedClip.timelineDurationMs > 6_000L)

        project = TimelineEngine.upsertKeyframe(
            project,
            clipId,
            Keyframe(property = KeyframeProperty.SCALE, timeOffsetMs = 0L, value = 1.0f, easing = EasingCurve.EASE_IN_OUT)
        )
        project = TimelineEngine.upsertKeyframe(
            project,
            clipId,
            Keyframe(property = KeyframeProperty.SCALE, timeOffsetMs = 2_000L, value = 2.0f, easing = EasingCurve.EASE_IN_OUT)
        )
        val updatedClip = project.findClip(clipId)!!
        val mid = KeyframeInterpolator.evaluateClipState(updatedClip, 1_000L)
        assertEquals(1.5f, mid.scaleX, 0.05f)
        val end = KeyframeInterpolator.evaluateClipState(updatedClip, 2_000L)
        assertEquals(2.0f, end.scaleX, 0.01f)
    }

    @Test
    fun all32EffectsAndProMasterShaderCompileValidGlslStructures() {
        assertEquals(32, EffectType.entries.size)
        for (fx in EffectType.entries) {
            val shader = GlslShaderGenerator.buildEffectFragmentShader(fx)
            assertTrue("Missing precision header in $fx", shader.contains("precision highp float;"))
            assertTrue("Missing gl_FragColor in $fx", shader.contains("gl_FragColor"))
            assertTrue("Missing uTexSampler in $fx", shader.contains("uTexSampler"))
            assertTrue("Missing uTertiary uniform in $fx", shader.contains("uniform float uTertiary;"))
            assertTrue("Blank primary label in $fx", fx.primaryLabel.isNotBlank())
            assertTrue("Blank secondary label in $fx", fx.secondaryLabel.isNotBlank())
            assertTrue("Blank tertiary label in $fx", fx.tertiaryLabel.isNotBlank())
        }
        val master = GlslShaderGenerator.buildMasterFinishingFragmentShader()
        assertTrue(master.contains("uGradeSharpness"))
        assertTrue(master.contains("uSharpenRadius"))
        assertTrue(master.contains("uSharpenThreshold"))
        assertTrue(master.contains("uClarity"))
        assertTrue(master.contains("uStructure"))
        assertTrue(master.contains("uVignetteFeather"))
        assertTrue(master.contains("uTransitionType"))
    }

    @Test
    fun decoupledFilterPresetsProAdjustmentsSpatialResolverAnd3dCubeLutWork() {
        assertTrue("Expected >= 30 built-in filter presets", ColorGradingEngine.builtInPresets.size >= 30)

        // Selecting a filter preset while keeping manual exposure & sharpness preserves both
        val cfg = ColorGradeConfig(
            lutPresetId = "preset-teal-orange",
            lutIntensity = 0.75f,
            exposure = 0.4f,
            vibrance = 0.35f,
            dehaze = 0.25f,
            whites = 0.15f,
            blacks = -0.10f,
            sharpness = 0.55f,
            clarity = 0.30f,
            structure = 0.25f
        )
        assertTrue(cfg.hasColorLutChanges)
        assertTrue(cfg.hasSpatialDetailChanges)

        val effectiveSpatial = ColorGradingEngine.resolveEffectiveSpatialConfig(cfg)
        assertTrue(effectiveSpatial.sharpness >= 0.55f)
        assertEquals(0.30f, effectiveSpatial.clarity, 0.25f)

        val neutralOut = ColorGradingEngine.gradePixelRgb(0.5f, 0.5f, 0.5f, ColorGradeConfig())
        val gradedOut = ColorGradingEngine.gradePixelRgb(0.5f, 0.5f, 0.5f, cfg)
        assertTrue(abs(gradedOut[0] - neutralOut[0]) > 0.02f || abs(gradedOut[2] - neutralOut[2]) > 0.02f)

        val baked = ColorGradingEngine.bakeTo3dCube(cfg, size = 9)
        assertEquals(9, baked.size)
        assertEquals(9, baked.argbCube.size)

        val cubeContent = buildString {
            appendLine("TITLE \"UnitTestLUT\"")
            appendLine("LUT_3D_SIZE 2")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) {
                appendLine("$r.0 $g.0 $b.0")
            }
        }
        val parsed = ColorGradingEngine.parseAdobeCubeLut(cubeContent)
        assertEquals("UnitTestLUT", parsed.title)
        assertEquals(2, parsed.size)
        assertEquals(0xFFFFFFFF.toInt(), parsed.argbCube[1][1][1])
    }

    @Test
    fun codecSafeMacroblockDimensionsAlignTo16PixelsAcrossAllAspects() {
        for (aspect in AspectRatioPreset.entries) {
            for (res in ExportResolution.entries) {
                val (w, h) = aspect.computeCodecSafeDimensions(res.shortSidePx, alignment = 16)
                assertEquals("Width $w not 16px-aligned for $aspect @ $res", 0, w % 16)
                assertEquals("Height $h not 16px-aligned for $aspect @ $res", 0, h % 16)
                assertTrue(w >= 64 && h >= 64)
            }
        }
    }

    @Test
    fun audioBeatDetectionDspAndSfxWavSynthesisWork() {
        val sr = 44_100
        val pcm = ShortArray(sr * 4)
        for (beat in 0 until 8) {
            val start = beat * (sr / 2)
            for (i in 0 until 1500) {
                if (start + i < pcm.size) {
                    val env = 1.0 - (i / 1500.0)
                    pcm[start + i] = (sin(2.0 * PI * 110.0 * i / sr) * env * 28000).toInt().toShort()
                }
            }
        }
        val analysis = AudioDspEngine.detectBeatsAndBpm(pcm, sr, 1)
        assertTrue("Expected >= 5 beats, got ${analysis.beatTimestampsMs.size}", analysis.beatTimestampsMs.size >= 5)
        assertEquals(120f, analysis.estimatedBpm, 6f)

        for (preset in BuiltInSfxPreset.entries) {
            val wav = AudioDspEngine.synthesizeSfxWavBytes(preset, 22_050)
            assertTrue(wav.size > 44)
            assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
            assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        }
    }

    @Test
    fun srtSubtitlesTemplatesAiAndProjectJsonRoundTripLosslessly() {
        assertEquals(12, TemplateCategory.entries.size)
        val tpl = TemplateCatalog.builtInTemplates.first()
        var project = TemplateCatalog.applyTemplate(tpl, sampleSources())
        project = LocalAiAlgorithms.applyAiAutoEdit(project)
        val mainId = project.mainVideoClips.first().id
        project = TimelineEngine.setColorGrade(
            project,
            mainId,
            ColorGradeConfig(
                lutPresetId = "preset-kodak-2383",
                lutIntensity = 0.82f,
                sharpness = 0.64f,
                sharpenRadius = 0.45f,
                sharpenThreshold = 0.22f,
                clarity = 0.35f,
                structure = 0.28f,
                dehaze = 0.20f,
                vibrance = 0.30f
            )
        )
        project = TimelineEngine.addOrUpdateEffect(
            project,
            mainId,
            AppliedEffect(
                type = EffectType.HALATION,
                intensity = 0.72f,
                secondaryParam = 0.58f,
                tertiaryParam = 0.41f
            )
        )
        project = project.copy(exportConfig = project.exportConfig.copy(exportAsPhotoFrame = true))
        project = SubtitleCodec.applySubtitlesToTextTrack(
            project,
            listOf(
                SubtitleCue(text = "नमस्ते इंडिया", startTimeMs = 200L, endTimeMs = 1500L, languageTag = "hi-IN"),
                SubtitleCue(text = "Welcome to VibeCut Pro", startTimeMs = 1600L, endTimeMs = 3200L, languageTag = "en-US")
            )
        )

        val srt = SubtitleCodec.exportSrt(project.subtitles)
        val parsedCues = SubtitleCodec.parseSrt(srt)
        assertEquals(2, parsedCues.size)
        assertEquals("नमस्ते इंडिया", parsedCues[0].text)

        val json = ProjectJsonCodec.encodeProject(project)
        val restored = ProjectJsonCodec.decodeProject(json)
        val restoredMain = restored.findClip(mainId)!!
        assertEquals("preset-kodak-2383", restoredMain.colorGrade.lutPresetId)
        assertEquals(0.82f, restoredMain.colorGrade.lutIntensity, 0.001f)
        assertEquals(0.64f, restoredMain.colorGrade.sharpness, 0.001f)
        assertEquals(0.45f, restoredMain.colorGrade.sharpenRadius, 0.001f)
        assertEquals(0.35f, restoredMain.colorGrade.clarity, 0.001f)
        assertEquals(0.41f, restoredMain.effects.last().tertiaryParam, 0.001f)
        assertTrue(restored.exportConfig.exportAsPhotoFrame)
    }
}

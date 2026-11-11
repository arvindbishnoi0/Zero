package com.vibecut.pro.engine

import com.vibecut.pro.engine.ai.FrameVisualSample
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VibeCutEngineTest {

    private fun sampleSources() = listOf(
        ImportedMediaSource("file:///clip1.mp4", "Clip 1", MediaKind.VIDEO, 6_000L),
        ImportedMediaSource("file:///clip2.mp4", "Clip 2", MediaKind.VIDEO, 4_000L)
    )

    @Test
    fun timelineImportTrimSplitCutFreezeAndMagneticReflowWork() {
        var project = Project(title = "Studio Test")
        project = TimelineEngine.importMediaClips(project, sampleSources(), TrackType.VIDEO_MAIN)
        val mainClips = project.tracks.first { it.type == TrackType.VIDEO_MAIN }.clips
        assertEquals(2, mainClips.size)
        assertEquals(10_000L, project.totalDurationMs)
        assertEquals(6_000L, mainClips[1].startTimeOnTrackMs)

        // Trim first clip from 6s down to 4s -> magnetic timeline shifts clip 2 to 4_000ms
        project = TimelineEngine.trimClip(project, mainClips[0].id, 1_000L, 5_000L)
        val afterTrim = project.tracks.first { it.type == TrackType.VIDEO_MAIN }.clips
        assertEquals(4_000L, afterTrim[0].timelineDurationMs)
        assertEquals(4_000L, afterTrim[1].startTimeOnTrackMs)
        assertEquals(8_000L, project.totalDurationMs)

        // Split first clip at 2_000ms playhead
        project = TimelineEngine.splitClipAt(project, afterTrim[0].id, 2_000L)
        val afterSplit = project.tracks.first { it.type == TrackType.VIDEO_MAIN }.clips
        assertEquals(3, afterSplit.size)
        assertEquals(1_000L, afterSplit[0].trimStartMs)
        assertEquals(3_000L, afterSplit[0].trimEndMs)
        assertEquals(3_000L, afterSplit[1].trimStartMs)
        assertEquals(5_000L, afterSplit[1].trimEndMs)

        // Insert 1.5s freeze frame at 1_000ms
        project = TimelineEngine.insertFreezeFrame(project, afterSplit[0].id, 1_000L, 1_500L)
        val afterFreeze = project.tracks.first { it.type == TrackType.VIDEO_MAIN }.clips
        assertTrue(afterFreeze.any { it.isFrozenFrame && it.timelineDurationMs == 1_500L })
        assertEquals(9_500L, project.totalDurationMs)
    }

    @Test
    fun lockedTrackBlocksMutationsAndUndoRedoRestoresExactState() {
        val session = TimelineSessionManager(Project(title = "Undo Test"))
        session.execute("Import") {
            TimelineEngine.importMediaClips(it, sampleSources(), TrackType.VIDEO_MAIN)
        }
        assertEquals(10_000L, session.currentProject.totalDurationMs)

        val mainTrackId = session.currentProject.tracks.first { it.type == TrackType.VIDEO_MAIN }.id
        val firstClipId = session.currentProject.tracks.first { it.type == TrackType.VIDEO_MAIN }.clips.first().id

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
        assertTrue(curvedClip.timelineDurationMs > 6_000L) // Bullet slow-mo expands timeline duration

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
    fun all22EffectsAndMasterShaderCompileValidGlslStructures() {
        assertEquals(22, EffectType.entries.size)
        for (fx in EffectType.entries) {
            val shader = GlslShaderGenerator.buildEffectFragmentShader(fx)
            assertTrue("Missing precision header in $fx", shader.contains("precision highp float;"))
            assertTrue("Missing gl_FragColor in $fx", shader.contains("gl_FragColor"))
            assertTrue("Missing uTexSampler in $fx", shader.contains("uTexSampler"))
        }
        val master = GlslShaderGenerator.buildMasterFinishingFragmentShader()
        assertTrue(master.contains("uGradeSharpness"))
        assertTrue(master.contains("uTransitionType"))
    }

    @Test
    fun colorGrading3dCubeLutAndAdobeCubeParserWork() {
        val cfg = ColorGradingEngine.builtInPresets.first { it.id == "preset-teal-orange" }.config
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
    fun audioBeatDetectionDspAndSfxWavSynthesisWork() {
        // Synthesize 4 seconds of 120 BPM impulses (every 0.5s = 22,050 samples @ 44.1kHz)
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

        // Verify all 12 built-in SFX presets synthesize valid RIFF WAVE headers
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
        assertEquals(project.id, restored.id)
        assertEquals(project.title, restored.title)
        assertEquals(project.aspectRatio, restored.aspectRatio)
        assertEquals(project.subtitles.size, restored.subtitles.size)
        assertEquals(
            project.tracks.first { it.type == TrackType.VIDEO_MAIN }.clips.size,
            restored.tracks.first { it.type == TrackType.VIDEO_MAIN }.clips.size
        )
    }
}

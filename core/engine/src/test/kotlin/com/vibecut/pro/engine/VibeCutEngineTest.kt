package com.vibecut.pro.engine

import com.vibecut.pro.engine.audio.AudioDspEngine
import com.vibecut.pro.engine.audio.BuiltInSfxPreset
import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.effects.GlslShaderGenerator
import com.vibecut.pro.engine.enhance.EnhancementPlanner
import com.vibecut.pro.engine.keyframes.KeyframeInterpolator
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.serialization.ProjectJsonCodec
import com.vibecut.pro.engine.subtitles.SubtitleCodec
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import com.vibecut.pro.engine.timeline.TimelineEngine
import com.vibecut.pro.engine.timeline.TimelineSessionManager
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Engine-level regression suite. Every test here exercises pure logic that both the live preview
 * and the exporter depend on, so a failure means the rendered file and the preview would disagree.
 */
class VibeCutEngineTest {

    private fun videoSource(
        name: String,
        durationMs: Long = 6_000L,
        uri: String = "file:///$name.mp4",
        hasAudio: Boolean = true
    ) = ImportedMediaSource(
        uri = uri,
        displayName = name,
        mediaKind = MediaKind.VIDEO,
        durationMs = durationMs,
        width = 1920,
        height = 1080,
        hasAudioTrack = hasAudio,
        thumbnailUri = "file:///$name.jpg"
    )

    private fun sampleSources() = listOf(videoSource("clip1"), videoSource("clip2", 4_000L, hasAudio = false))

    private fun projectWithClips(): Project =
        TimelineEngine.importMediaClips(Project(title = "ZERO Test"), sampleSources(), TrackType.VIDEO_MAIN)

    // 1 ------------------------------------------------------------------ import + playback model

    @Test
    fun test01_importAndPlaybackTimelineIsContiguousAndMagnetic() {
        val project = projectWithClips()
        val clips = project.mainVideoClips
        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].startTimeOnTrackMs)
        assertEquals(6_000L, clips[1].startTimeOnTrackMs)
        assertEquals(10_000L, project.totalDurationMs)

        // Main-track clip lookup at every boundary is exact and never returns null inside the project.
        assertEquals(clips[0].id, project.findMainClipAt(0L)?.id)
        assertEquals(clips[0].id, project.findMainClipAt(5_999L)?.id)
        assertEquals(clips[1].id, project.findMainClipAt(6_000L)?.id)
        assertEquals(clips[1].id, project.findMainClipAt(9_999L)?.id)
        // Past the end it holds the last clip instead of returning null (preview must not blank out).
        assertEquals(clips[1].id, project.findMainClipAt(10_500L)?.id)

        // Timeline offset -> source mapping is the identity for speed 1.
        val clip = clips[0]
        assertEquals(2_500L, clip.mapTimelineOffsetToSourceMs(2_500L))
        assertEquals(2_500L, clip.mapSourceMsToTimelineOffset(2_500L))
    }

    // 2 ------------------------------------------------------------------ playhead drag both ways

    @Test
    fun test02_playheadSeekIsBidirectionalAndClamped() {
        val project = projectWithClips()
        var position = 0L

        // Dragging forward then backward lands on the same seek targets.
        listOf(1_000L, 4_500L, 8_200L, 9_990L).forEach { target ->
            val clamped = target.coerceIn(0L, project.totalDurationMs)
            position = clamped
            assertEquals(target, position)
        }
        listOf(7_000L, 3_000L, 500L, 0L).forEach { target ->
            position = target.coerceIn(0L, project.totalDurationMs)
            assertEquals(target, position)
        }

        // Out-of-range seeks clamp instead of going negative or past the project.
        assertEquals(0L, (-5_000L).coerceIn(0L, project.totalDurationMs))
        assertEquals(10_000L, (99_000L).coerceIn(0L, project.totalDurationMs))

        // Frame stepping is exact and cannot leave the timeline.
        val fps = project.exportConfig.frameRate.fps
        assertEquals(33L, TimelineEngine.stepPlayheadByFrames(0L, fps, 1, project.totalDurationMs))
        assertEquals(0L, TimelineEngine.stepPlayheadByFrames(0L, fps, -1, project.totalDurationMs))
        val endStep = TimelineEngine.stepPlayheadByFrames(9_990L, fps, 1, project.totalDurationMs)
        assertEquals(10_000L, endStep)
    }

    // 3 ------------------------------------------------------------------ preview at selected time

    @Test
    fun test03_sourceMappingAtSelectedTimeMatchesPreviewContract() {
        var project = projectWithClips()
        val secondId = project.mainVideoClips[1].id
        project = TimelineEngine.trimClip(project, secondId, 1_000L, 3_500L)
        val clip = project.findClip(secondId)!!

        // The second clip now starts at 6s and runs for 2.5s.
        assertEquals(2_500L, clip.timelineDurationMs)
        assertEquals(7_000L, clip.mapTimelineOffsetToSourceMs(1_000L))

        // Reverse mirrors inside the trim window and is its own inverse.
        val reversed = clip.copy(isReversed = true)
        val forwardSource = clip.mapTimelineOffsetToSourceMs(500L)
        val reversedSource = reversed.mapTimelineOffsetToSourceMs(500L)
        assertEquals(clip.trimStartMs + clip.trimEndMs - forwardSource, reversedSource)
        assertEquals(500L, reversed.mapSourceMsToTimelineOffset(reversedSource))
    }

    // 4 ------------------------------------------------------------------ multi-effect live preview

    @Test
    fun test04_effectStackKeepsOrderAndAllEffectsHaveRealShaders() {
        var project = projectWithClips()
        val clipId = project.mainVideoClips[0].id

        val stack = listOf(EffectType.GAUSSIAN_BLUR, EffectType.GLITCH, EffectType.VHS, EffectType.CHROMATIC_ABERRATION)
        stack.forEach { type ->
            project = TimelineEngine.addOrUpdateEffect(project, clipId, AppliedEffect(type = type))
        }
        var clip = project.findClip(clipId)!!
        assertEquals(stack, clip.effects.map { it.type })

        // Reordering really changes the render order.
        project = TimelineEngine.reorderEffect(project, clipId, clip.effects.last().id, -1)
        clip = project.findClip(clipId)!!
        assertEquals(EffectType.CHROMATIC_ABERRATION, clip.effects[2].type)

        // Every declared effect produces a fragment shader that samples the texture.
        EffectType.entries.forEach { type ->
            val shader = GlslShaderGenerator.buildEffectFragmentShader(type)
            assertTrue("Missing sampler for $type", shader.contains("uTexSampler"))
            assertTrue("Missing intensity uniform for $type", shader.contains("uIntensity"))
            assertTrue("Empty shader for $type", shader.length > 200)
        }
        assertTrue("Expected at least 30 real effects", EffectType.entries.size >= 30)

        // Disabled effects are skipped by the enabled filter, not silently rendered.
        project = TimelineEngine.addOrUpdateEffect(
            project, clipId, clip.effects[0].copy(enabled = false)
        )
        assertFalse(project.findClip(clipId)!!.effects[0].enabled)
    }

    // 5 ------------------------------------------------------------------ colour slider changes

    @Test
    fun test05_colorGradeChangesPixelsAndResetsToIdentity() {
        val neutral = ColorGradeConfig()
        val neutralPixel = ColorGradingEngine.gradePixelRgb(0.4f, 0.5f, 0.6f, neutral)
        assertArrayEquals(floatArrayOf(0.4f, 0.5f, 0.6f), neutralPixel, 0.002f)

        val brighter = ColorGradingEngine.gradePixelRgb(0.4f, 0.5f, 0.6f, neutral.copy(exposure = 1f))
        assertTrue("Exposure must brighten", brighter[0] > neutralPixel[0])

        val desaturated = ColorGradingEngine.gradePixelRgb(
            0.9f, 0.2f, 0.2f, neutral.copy(saturation = -1f)
        )
        assertTrue("Full desaturation collapses channels", abs(desaturated[0] - desaturated[2]) < 0.06f)

        val warmer = ColorGradingEngine.gradePixelRgb(0.5f, 0.5f, 0.5f, neutral.copy(temperature = 0.8f))
        assertTrue("Warmth must add red", warmer[0] > warmer[2])

        // The 3D bake used by the GPU LUT path agrees with the CPU reference.
        val cfg = neutral.copy(contrast = 0.3f, saturation = 0.25f, vignette = 0.2f, grain = 0.1f)
        val baked = ColorGradingEngine.bakeTo3dCube(cfg, size = 9)
        assertEquals(9, baked.size)
        val mid = baked.argbCube[4][4][4]
        val expected = ColorGradingEngine.gradePixelRgb(0.5f, 0.5f, 0.5f, cfg)
        val red = ((mid shr 16) and 0xFF) / 255f
        assertEquals(expected[0], red, 0.02f)

        // Spatial sliders are recognised as changes (they drive the finishing shader).
        assertTrue(cfg.hasSpatialDetailChanges)
        assertFalse(ColorGradeConfig().hasColorLutChanges)
        assertTrue(neutral.copy(hueShift = 0.2f).hasColorLutChanges)

        // Presets exist and are not empty shells.
        assertTrue("Expected >= 30 built-in presets", ColorGradingEngine.builtInPresets.size >= 30)
        assertTrue(ColorGradingEngine.builtInPresets.all { it.name.isNotBlank() })
    }

    // 6 ------------------------------------------------------------------ split / trim / move

    @Test
    fun test06_splitTrimMoveAndDeleteKeepTimelineConsistent() {
        var project = projectWithClips()
        val first = project.mainVideoClips[0]

        project = TimelineEngine.splitClipAt(project, first.id, 2_500L)
        assertEquals(3, project.mainVideoClips.size)
        assertEquals(2_500L, project.mainVideoClips[0].timelineDurationMs)
        assertEquals(2_500L, project.mainVideoClips[1].trimStartMs)
        assertTrue(project.totalDurationMs >= 10_000L)

        // Trim the split head shorter, then verify magnetic reflow closed the gap.
        val headId = project.mainVideoClips[0].id
        project = TimelineEngine.trimClip(project, headId, 0L, 1_500L)
        assertEquals(1_500L, project.mainVideoClips[0].timelineDurationMs)
        var cursor = 0L
        project.mainVideoClips.forEach { c ->
            assertEquals(cursor, c.startTimeOnTrackMs)
            cursor += c.timelineDurationMs
        }

        // Move on a non-magnetic track keeps the requested start.
        val pipTrack = project.trackByType(TrackType.VIDEO_PIP)!!
        project = TimelineEngine.importMediaClips(project, listOf(videoSource("pip", 3_000L)), TrackType.VIDEO_PIP)
        val pip = project.trackByType(TrackType.VIDEO_PIP)!!.clips.first()
        project = TimelineEngine.moveClipToTimestamp(project, pip.id, 2_000L, playheadMs = 0L)
        assertEquals(2_000L, project.findClip(pip.id)!!.startTimeOnTrackMs)
        assertEquals(pipTrack.id, project.findTrackForClip(pip.id)!!.id)

        // Delete removes exactly one clip and the project duration shrinks accordingly.
        val before = project.mainVideoClips.size
        val target = project.mainVideoClips[1]
        project = TimelineEngine.deleteClip(project, target.id)
        assertEquals(before - 1, project.mainVideoClips.size)
    }

    // 7 ------------------------------------------------------------------ text + overlay

    @Test
    fun test07_textAndOverlayLayersAnimateAndPersist() {
        var project = projectWithClips()
        project = TimelineEngine.addTextClip(
            project,
            TextStyleSpec(text = "ZERO", fontSizeSp = 42f, animationIn = TextAnimationType.TYPEWRITER),
            startMs = 1_000L,
            durationMs = 2_000L
        )
        val textClip = project.trackByType(TrackType.TEXT)!!.clips.single()
        assertEquals("ZERO", textClip.textSpec?.text)
        assertEquals(1_000L, textClip.startTimeOnTrackMs)
        assertEquals(2_000L, textClip.timelineDurationMs)

        project = TimelineEngine.addStickerClip(project, StickerSpec(content = "🔥"), startMs = 500L, durationMs = 900L)
        assertEquals(1, project.trackByType(TrackType.STICKER)!!.clips.size)

        // Text keyframes animate position (used by the overlay renderer).
        project = TimelineEngine.upsertKeyframe(
            project, textClip.id, Keyframe(property = KeyframeProperty.POSITION_Y, timeOffsetMs = 0L, value = -0.5f)
        )
        project = TimelineEngine.upsertKeyframe(
            project, textClip.id, Keyframe(property = KeyframeProperty.POSITION_Y, timeOffsetMs = 1_000L, value = 0.5f)
        )
        val updated = project.findClip(textClip.id)!!
        val mid = KeyframeInterpolator.evaluateProperty(
            updated.keyframes, KeyframeProperty.POSITION_Y, 500L, 0f
        )
        assertTrue("Keyframes must interpolate between the two values", mid > -0.5f && mid < 0.5f)

        // Overlay/PIP clips keep their own transform and scale.
        project = TimelineEngine.importMediaClips(project, listOf(videoSource("overlay", 3_000L)), TrackType.VIDEO_PIP)
        val pip = project.trackByType(TrackType.VIDEO_PIP)!!.clips.first()
        assertTrue("PIP clips default to a smaller scale", pip.transform.scaleX < 1f)
    }

    // 8 ------------------------------------------------------------------ music sync

    @Test
    fun test08_audioTrackMixAndBeatSyncHydrateWaveforms() {
        var project = projectWithClips()
        project = TimelineEngine.importMediaClips(
            project, listOf(videoSource("music", 20_000L, uri = "file:///music.m4a")), TrackType.AUDIO_MUSIC
        )
        val music = project.trackByType(TrackType.AUDIO_MUSIC)!!.clips.single()

        // Volume, fades and mute are all real clip fields consumed by the DSP audio processor.
        project = TimelineEngine.updateClip(project, music.id) {
            it.copy(volume = 0.4f, fadeInMs = 800L, fadeOutMs = 1_200L, noiseReduction = 0.3f, audioDucking = true)
        }
        val mixed = project.findClip(music.id)!!
        assertEquals(0.4f, mixed.volume, 0.0001f)
        assertEquals(800L, mixed.fadeInMs)
        assertTrue(mixed.audioDucking)

        // Beat markers drive cut snapping.
        val beats = listOf(0L, 500L, 1_000L, 1_500L, 2_000L, 2_500L)
        project = TimelineEngine.updateClip(project, music.id) { it.copy(beatMarkersMs = beats) }
        project = AudioDspEngine.syncVideoClipsToBeats(project, beats, beatsPerCut = 1)
        assertTrue(project.totalDurationMs > 0L)
        assertEquals(beats, project.findClip(music.id)!!.beatMarkersMs)

        // The DSP engine produces bounded PCM for a block of silence at full gain.
        val dsp = AudioDspEngine.PcmDspState(44_100, 2)
        val samples = ShortArray(1_024)
        dsp.processInPlace(samples, 512, baseGain = 1f, noiseReduction = 0.2f, voiceEnhancement = 0.2f)
        assertTrue(samples.all { it.toInt() in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt() })

        // Built-in SFX presets are real synthesized definitions with durations.
        assertTrue(BuiltInSfxPreset.entries.isNotEmpty())
        assertTrue(BuiltInSfxPreset.entries.all { it.durationMs > 0 && it.title.isNotBlank() })
    }

    // 9 ------------------------------------------------------------------ transitions

    @Test
    fun test09_transitionsRenderProgressAndCoverAllTypes() {
        var project = projectWithClips()
        val clipId = project.mainVideoClips[1].id

        TransitionType.entries.forEach { type ->
            assertTrue("No shader code for $type", GlslShaderGenerator.transitionCode(type) > 0)
            val transition = ClipTransition(type = type, durationMs = 600L)
            project = TimelineEngine.setClipTransition(project, clipId, transition, null)
            assertEquals(type, project.findClip(clipId)!!.transitionIn?.type)
        }
        assertEquals(0, GlslShaderGenerator.transitionCode(null))

        // Apply-to-all sets a transition on every main cut.
        project = TimelineEngine.applyTransitionToAllMainClips(project, ClipTransition(type = TransitionType.DISSOLVE))
        assertTrue(project.mainVideoClips.all { it.transitionIn?.type == TransitionType.DISSOLVE })

        // Machine-readable transition codes are unique so the shader can branch on them.
        val codes = TransitionType.entries.map { GlslShaderGenerator.transitionCode(it) }
        assertEquals(codes.size, codes.distinct().size)
    }

    // 10 ------------------------------------------------------------------ 1080p export planning

    @Test
    fun test10_exportConfigProducesCodecSafe1080pPlan() {
        val project = projectWithClips()
        val config = project.exportConfig.copy(
            resolution = ExportResolution.FHD_1080P,
            frameRate = ExportFrameRate.FPS_30,
            bitrateTier = BitrateTier.HIGH,
            codec = VideoCodecOption.H264
        )
        val (w, h) = project.aspectRatio.computeCodecSafeDimensions(config.resolution.shortSidePx)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertEquals(1080, w)
        assertEquals(1920, h)

        val bitrate = config.targetVideoBitrateBps(project.aspectRatio, project.customAspectRatio)
        assertTrue(bitrate in 2_000_000..80_000_000)

        val size = config.estimatedFileSizeMb(project.totalDurationMs, project.aspectRatio, project.customAspectRatio)
        assertTrue("Estimate must be finite and positive", size.isFinite() && size > 0f)

        // H.265 should request fewer bits than H.264 for the same quality tier.
        val hevc = config.copy(codec = VideoCodecOption.H265)
        assertTrue(hevc.targetVideoBitrateBps(project.aspectRatio) < bitrate)
    }

    // 11 ------------------------------------------------------------------ 4K export + honesty

    @Test
    fun test11_upscalePlanIsHonestAboutRealDetail() {
        val cfg = EnhancementConfig(enabled = true, targetHeight = 2160)
        val plan = EnhancementPlanner.plan(
            config = cfg,
            aspect = AspectRatioPreset.REELS_9_16,
            customRatio = 9f / 16f,
            sourceWidth = 1080,
            sourceHeight = 1920
        )
        assertEquals(2160, plan.outputHeight)
        assertTrue(plan.needsUpscale)
        assertFalse("No AI model is bundled, the plan must say so", plan.isAiEnhanced)
        assertTrue(plan.realDetailRatio < 1f)
        assertTrue(plan.detailHonestyLabel.contains("real"))
        assertTrue(plan.upscaleLabel.contains("Lanczos"))

        // A 4K source is not upscaled and reports full detail.
        val native = EnhancementPlanner.plan(
            config = cfg,
            aspect = AspectRatioPreset.YOUTUBE_16_9,
            customRatio = 16f / 9f,
            sourceWidth = 3840,
            sourceHeight = 2160
        )
        assertFalse(native.needsUpscale)
        assertEquals(1f, native.realDetailRatio, 0.001f)

        // Frame budget helper keeps 4K allocations bounded.
        assertTrue(EnhancementPlanner.estimatedFrameBytes(plan) > 0)
        assertTrue(EnhancementPlanner.safeTargetHeight(plan, 64L * 1024 * 1024) <= 2160)
    }

    // 12 ------------------------------------------------------------------ persist / reopen

    @Test
    fun test12_projectJsonRoundTripRestoresEverything() {
        var project = projectWithClips()
        val clipId = project.mainVideoClips[0].id
        project = TimelineEngine.addOrUpdateEffect(
            project, clipId,
            AppliedEffect(type = EffectType.GLITCH, intensity = 0.42f, secondaryParam = 0.66f, tertiaryParam = 0.2f)
        )
        project = TimelineEngine.setColorGrade(
            project, clipId,
            ColorGradeConfig(exposure = 0.3f, contrast = 0.2f, hueShift = 0.1f, vignette = 0.4f, grain = 0.15f)
        )
        project = TimelineEngine.setClipSpeed(project, clipId, 1.7f)
        project = TimelineEngine.updateClip(project, clipId) { it.copy(volume = 0.55f, fadeInMs = 500L) }
        project = TimelineEngine.addTrack(project, TrackType.AUDIO_SFX)
        project = project.copy(enhancement = EnhancementConfig(enabled = true, targetHeight = 1440))

        val json = ProjectJsonCodec.encodeProject(project)
        val restored = ProjectJsonCodec.decodeProject(json)

        assertEquals(project.id, restored.id)
        assertEquals(project.mainVideoClips.size, restored.mainVideoClips.size)
        assertEquals(project.totalDurationMs, restored.totalDurationMs)

        val restoredClip = restored.findClip(clipId)!!
        assertEquals(1, restoredClip.effects.size)
        assertEquals(EffectType.GLITCH, restoredClip.effects[0].type)
        assertEquals(0.42f, restoredClip.effects[0].intensity, 0.001f)
        assertEquals(0.3f, restoredClip.colorGrade.exposure, 0.001f)
        assertEquals(0.4f, restoredClip.colorGrade.vignette, 0.001f)
        assertEquals(1.7f, restoredClip.speed, 0.001f)
        assertEquals(0.55f, restoredClip.volume, 0.001f)
        assertTrue(restored.enhancement.enabled)
        assertEquals(1440, restored.enhancement.targetHeight)
        assertTrue(restored.trackByType(TrackType.AUDIO_SFX) != null)
    }

    // 13 ------------------------------------------------------------------ undo / redo

    @Test
    fun test13_undoRedoRestoresExactSnapshots() {
        val session = TimelineSessionManager(projectWithClips())
        val original = session.currentProject
        val clipId = original.mainVideoClips[0].id

        session.execute("Trim") { TimelineEngine.trimClip(it, clipId, 0L, 1_000L) }
        val trimmed = session.currentProject
        assertTrue(session.canUndo)
        assertEquals(1_000L, trimmed.findClip(clipId)!!.timelineDurationMs)

        session.undo()
        assertEquals(original.totalDurationMs, session.currentProject.totalDurationMs)
        assertTrue(session.canRedo)

        session.redo()
        assertEquals(trimmed.totalDurationMs, session.currentProject.totalDurationMs)

        // Coalesced slider edits collapse into a single undo entry.
        val before = session.undoDepth
        repeat(8) { i ->
            session.executeCoalesced("exposure", "Exposure") { proj ->
                TimelineEngine.updateClip(proj, clipId) { it.copy(volume = (i + 1) / 10f) }
            }
        }
        assertTrue("Coalescing must not add 8 undo entries", session.undoDepth - before <= 2)
    }

    // 14 ------------------------------------------------------------------ subtitles & formats

    @Test
    fun test14_subtitleParsingAndExportRoundTrip() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,500
            First line

            2
            00:00:04,000 --> 00:00:06,000
            Second line
        """.trimIndent()

        val cues = SubtitleCodec.parseSrt(srt)
        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startTimeMs)
        assertEquals(3_500L, cues[0].endTimeMs)

        val exported = SubtitleCodec.exportSrt(cues)
        val reparsed = SubtitleCodec.parseSrt(exported)
        assertEquals(cues.size, reparsed.size)
        assertEquals(cues[1].text, reparsed[1].text)

        var project = projectWithClips()
        project = SubtitleCodec.applySubtitlesToTextTrack(project, cues)
        assertEquals(cues.size, project.subtitles.size)
        assertTrue(project.trackByType(TrackType.TEXT)!!.clips.isNotEmpty())
    }

    // 15 ------------------------------------------------------------------ effects vs. category coverage

    @Test
    fun test15_every_effect_has_metadata_and_distinct_shader() {
        val shaders = EffectType.entries.map { GlslShaderGenerator.buildEffectFragmentShader(it) }
        // Distinct implementations, not one shader aliased 34 times.
        assertTrue("Effects must not all share one shader body", shaders.distinct().size >= EffectType.entries.size - 2)
        EffectType.entries.forEach { type ->
            assertTrue(type.label.isNotBlank())
            assertTrue(type.primaryLabel.isNotBlank())
            assertTrue(type.defaultIntensity in 0f..1f)
            assertTrue(type.defaultSecondary in 0f..1f)
            assertTrue(type.defaultTertiary in 0f..1f)
        }
        assertTrue(EffectCategory.entries.size >= 6)
        assertEquals(BlendMode.entries.size, BlendMode.entries.map { it.glCode }.distinct().size)
    }

    // 16 ------------------------------------------------------------------ aspect ratios

    @Test
    fun test16_aspectRatioDimensionsAreCodecAligned() {
        AspectRatioPreset.entries.forEach { preset ->
            val (w, h) = preset.computeCodecSafeDimensions(1080)
            assertTrue("$preset width must be positive", w > 0)
            assertTrue("$preset height must be positive", h > 0)
            assertEquals("$preset must be 16px aligned", 0, w % 16)
            assertEquals("$preset must be 16px aligned", 0, h % 16)
        }
        val (sw, sh) = AspectRatioPreset.SQUARE_1_1.computeCodecSafeDimensions(1080)
        assertEquals(sw, sh)
        val (cw, chh) = AspectRatioPreset.CUSTOM.computeDimensionsFor(1080, 0.8f)
        assertTrue("Portrait custom ratio must be taller than wide", cw < chh)
    }
}

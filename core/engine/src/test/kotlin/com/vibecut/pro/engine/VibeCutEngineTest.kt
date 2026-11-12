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
import com.vibecut.pro.engine.timeline.ExportSegmentPlanner
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import com.vibecut.pro.engine.timeline.TimelineEngine
import com.vibecut.pro.engine.timeline.TimelineSessionManager
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Engine regression suite. Every test here maps to a feature the UI exposes, so a failure means a
 * user-visible feature is broken (not just an implementation detail).
 */
class VibeCutEngineTest {

    private fun video(name: String, durationMs: Long, audio: Boolean = true) = ImportedMediaSource(
        uri = "file:///$name.mp4",
        displayName = name,
        mediaKind = MediaKind.VIDEO,
        durationMs = durationMs,
        width = 1920,
        height = 1080,
        hasAudioTrack = audio,
        thumbnailUri = "file:///$name.jpg"
    )

    private fun image(name: String, durationMs: Long = 3_000L) = ImportedMediaSource(
        uri = "file:///$name.jpg",
        displayName = name,
        mediaKind = MediaKind.IMAGE,
        durationMs = durationMs,
        width = 1080,
        height = 1350,
        hasAudioTrack = false
    )

    private fun twoClipProject(): Project {
        var project = Project(title = "Test Reel", aspectRatio = AspectRatioPreset.REELS_9_16)
        project = TimelineEngine.importMediaClips(
            project,
            listOf(video("clipA", 8_000), video("clipB", 5_000, audio = false)),
            TrackType.VIDEO_MAIN
        )
        return project
    }

    // ------------------------------------------------------------------ 1. import & playback model

    @Test
    fun test01_importMediaBuildsGaplessPlayableTimeline() {
        val project = twoClipProject()
        val clips = project.mainVideoClips
        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].startTimeOnTrackMs)
        assertEquals(8_000L, clips[0].endTimeOnTrackMs)
        assertEquals(8_000L, clips[1].startTimeOnTrackMs)
        assertEquals(13_000L, project.totalDurationMs)
        assertTrue(clips[0].hasAudioTrack)
        assertFalse(clips[1].hasAudioTrack)

        // A seekable playlist piece exists for every clip and covers the whole timeline.
        val pieces = ExportSegmentPlanner.planPreviewPlaylist(project)
        assertEquals(2, pieces.size)
        assertEquals(13_000L, pieces.sumOf { it.timelineSpanMs })
    }

    // ------------------------------------------------------------------ 2. playhead both directions

    @Test
    fun test02_playheadMovesBothWaysAndClamps() {
        val project = twoClipProject()
        // The engine snaps a frame to a whole millisecond, so derive the expected step from it.
        val frameMs = Math.round(1000.0 / 30.0)
        var playhead = 0L
        // forward
        playhead = TimelineEngine.stepPlayheadByFrames(playhead, 30, 45, project.totalDurationMs)
        assertEquals(45 * frameMs, playhead)
        // backward — the playhead must move the same way it came
        playhead = TimelineEngine.stepPlayheadByFrames(playhead, 30, -15, project.totalDurationMs)
        assertEquals(30 * frameMs, playhead)
        // one frame back lands exactly one frame earlier (frame-step accuracy)
        assertEquals(
            29 * frameMs,
            TimelineEngine.stepPlayheadByFrames(playhead, 30, -1, project.totalDurationMs)
        )
        // clamp at both ends
        assertEquals(0L, TimelineEngine.stepPlayheadByFrames(0L, 30, -60, project.totalDurationMs))
        assertEquals(
            project.totalDurationMs,
            TimelineEngine.stepPlayheadByFrames(project.totalDurationMs - 10L, 30, 30, project.totalDurationMs)
        )
    }

    // ------------------------------------------------------------------ 3. seek accuracy

    @Test
    fun test03_timelineToSourceMappingIsExactInverse() {
        val project = twoClipProject()
        val clip = project.mainVideoClips[0]
        for (offset in listOf(0L, 250L, 1_000L, 3_333L, 7_999L)) {
            val source = clip.mapTimelineOffsetToSourceMs(offset)
            val back = clip.mapSourceMsToTimelineOffset(source)
            assertTrue(
                "round-trip drift at $offset: $source -> $back",
                abs(back - offset) <= 2L
            )
        }

        // Speed changes must keep the mapping consistent at 2x.
        val fast = TimelineEngine.setClipSpeed(project, clip.id, 2f)
        val fastClip = fast.mainVideoClips[0]
        assertEquals(4_000L, fastClip.timelineDurationMs)
        assertEquals(2_000L, fastClip.mapTimelineOffsetToSourceMs(1_000L))
        assertEquals(1_000L, fastClip.mapSourceMsToTimelineOffset(2_000L))
    }

    @Test
    fun test03b_reversedClipMapsBackwards() {
        val project = twoClipProject()
        val clip = project.mainVideoClips[0]
        val reversed = TimelineEngine.toggleReverseClip(project, clip.id).mainVideoClips[0]
        assertTrue(reversed.isReversed)
        val first = reversed.mapTimelineOffsetToSourceMs(0L)
        val last = reversed.mapTimelineOffsetToSourceMs(reversed.timelineDurationMs)
        assertTrue("reversed start should come from the tail", first > last)
        assertEquals(reversed.trimStartMs, last)
        assertEquals(reversed.effectiveTrimEndMs, first)
    }

    // ------------------------------------------------------------------ 4. effects stack

    @Test
    fun test04_allDeclaredEffectsHaveRealShaders() {
        // Every effect the UI can add must have a dedicated fragment shader body.
        val shaders = EffectType.entries.map { GlslShaderGenerator.buildEffectFragmentShader(it) }
        assertEquals(EffectType.entries.size, shaders.size)
        shaders.forEachIndexed { index, shader ->
            assertTrue(
                "effect ${EffectType.entries[index]} has no shader",
                shader.contains("gl_FragColor") && shader.contains("uTexSampler")
            )
        }
        // and the finishing pass really implements the spatial adjustments
        val finishing = GlslShaderGenerator.buildMasterFinishingFragmentShader()
        assertTrue(finishing.contains("uSharpen"))
        assertTrue(finishing.contains("uVignette"))
        assertTrue(finishing.contains("uGrain"))
    }

    @Test
    fun test04b_effectStackSupportsAddToggleReorderRemove() {
        var project = twoClipProject()
        val clipId = project.mainVideoClips[0].id
        val blur = AppliedEffect(type = EffectType.GAUSSIAN_BLUR, intensity = 0.6f)
        val glow = AppliedEffect(type = EffectType.GLOW, intensity = 0.4f)

        project = TimelineEngine.addOrUpdateEffect(project, clipId, blur)
        project = TimelineEngine.addOrUpdateEffect(project, clipId, glow)
        assertEquals(2, project.findClip(clipId)!!.effects.size)
        assertEquals(EffectType.GAUSSIAN_BLUR, project.findClip(clipId)!!.effects[0].type)

        project = TimelineEngine.reorderEffect(project, clipId, glow.id, -1)
        assertEquals(EffectType.GLOW, project.findClip(clipId)!!.effects[0].type)

        project = TimelineEngine.setEffectEnabled(project, clipId, glow.id, false)
        assertFalse(project.findClip(clipId)!!.effects.first { it.id == glow.id }.enabled)

        project = TimelineEngine.removeEffect(project, clipId, blur.id)
        assertEquals(1, project.findClip(clipId)!!.effects.size)

        project = TimelineEngine.clearClipEffects(project, clipId)
        assertTrue(project.findClip(clipId)!!.effects.isEmpty())
    }

    // ------------------------------------------------------------------ 5. colour grading

    @Test
    fun test05_colorSlidersChangePixelsInTheRightDirection() {
        val neutral = ColorGradingEngine.gradePixelRgb(0.5f, 0.5f, 0.5f, ColorGradeConfig())
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), neutral, 0.001f)
        // Pure black and pure white must survive a neutral grade unchanged.
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f),
            ColorGradingEngine.gradePixelRgb(0f, 0f, 0f, ColorGradeConfig()),
            0.01f
        )
        val white = ColorGradingEngine.gradePixelRgb(1f, 1f, 1f, ColorGradeConfig())
        assertTrue(white.all { it in 0.99f..1f })

        val brighter = ColorGradingEngine.gradePixelRgb(
            0.5f, 0.5f, 0.5f, ColorGradeConfig(brightness = 0.4f)
        )
        assertTrue("brightness must raise luminance", brighter[0] > neutral[0])

        val darker = ColorGradingEngine.gradePixelRgb(
            0.5f, 0.5f, 0.5f, ColorGradeConfig(exposure = -1f)
        )
        assertTrue("negative exposure must darken", darker[0] < neutral[0])

        val warm = ColorGradingEngine.gradePixelRgb(
            0.5f, 0.5f, 0.5f, ColorGradeConfig(temperature = 0.6f)
        )
        assertTrue("temperature must push red above blue", warm[0] > warm[2])

        val saturated = ColorGradingEngine.gradePixelRgb(
            0.8f, 0.3f, 0.3f, ColorGradeConfig(saturation = 0.8f)
        )
        assertTrue("saturation must increase channel spread", saturated[0] - saturated[1] > 0.5f - 0.3f)

        val desaturated = ColorGradingEngine.gradePixelRgb(
            0.8f, 0.3f, 0.3f, ColorGradeConfig(saturation = -1f)
        )
        assertTrue(
            "full desaturation must collapse channels",
            abs(desaturated[0] - desaturated[1]) < 0.05f
        )
    }

    @Test
    fun test05b_lutBakeMatchesReferenceAndPresetsExist() {
        val cfg = ColorGradeConfig(exposure = 0.3f, contrast = 0.2f, saturation = 0.25f)
        val baked = ColorGradingEngine.bakeTo3dCube(cfg, size = 9)
        assertEquals(9, baked.size)
        val mid = baked.argbCube[4][4][4]
        val reference = ColorGradingEngine.gradePixelRgb(0.5f, 0.5f, 0.5f, cfg)
        val r = ((mid shr 16) and 0xFF) / 255f
        assertEquals(reference[0], r, 0.02f)

        assertTrue(ColorGradingEngine.builtInPresets.size >= 30)
        assertTrue(ColorGradingEngine.filterCategories.contains("Cinematic"))
    }

    @Test
    fun test05c_customCubeLutParsesAndNeutralisesOnBadInput() {
        val size = 3
        val sb = StringBuilder()
        sb.appendLine("TITLE \"Invert Test\"")
        sb.appendLine("LUT_3D_SIZE $size")
        // inverted cube: value = 1 - input
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            sb.appendLine(
                "${1f - r / (size - 1f)} ${1f - g / (size - 1f)} ${1f - b / (size - 1f)}"
            )
        }
        val cube = ColorGradingEngine.parseAdobeCubeLut(sb.toString())
        assertEquals("Invert Test", cube.title)
        assertEquals(size, cube.size)
        // cube[r][g][b]: input 0 maps to 1.0, input 1 maps to 0.0 for an inverted LUT.
        val darkCorner = cube.argbCube[0][0][0]
        val brightCorner = cube.argbCube[2][2][2]
        assertEquals(255, (darkCorner shr 16) and 0xFF)
        assertEquals(255, (darkCorner shr 8) and 0xFF)
        assertEquals(255, darkCorner and 0xFF)
        assertEquals(0, (brightCorner shr 16) and 0xFF)

        assertThrows(IllegalArgumentException::class.java) {
            ColorGradingEngine.parseAdobeCubeLut("TITLE broken\n0 0 0\n")
        }
    }

    @Test
    fun test05d_spatialPresetFoldingReachesFinishingPass() {
        val preset = ColorGradingEngine.builtInPresets.first { it.config.sharpness > 0.3f }
        val cfg = ColorGradeConfig(lutPresetId = preset.id, lutIntensity = 1f)
        val folded = ColorGradingEngine.resolveEffectiveSpatialConfig(cfg)
        assertTrue(folded.hasSpatialDetailChanges)
        assertTrue(ColorGradingEngine.needsFinishingPass(cfg))

        val disabled = ColorGradingEngine.resolveEffectiveSpatialConfig(cfg.copy(lutIntensity = 0f))
        assertFalse(disabled.hasSpatialDetailChanges)
    }

    // ------------------------------------------------------------------ 6. split / trim / move

    @Test
    fun test06_splitTrimAndMoveKeepTimelineConsistent() {
        var project = twoClipProject()
        val firstId = project.mainVideoClips[0].id

        project = TimelineEngine.splitClipAt(project, firstId, 3_000L)
        assertEquals(3, project.mainVideoClips.size)
        assertTrue(project.mainVideoClips.all { it.startTimeOnTrackMs >= 0L })

        // trimming the middle clip shortens the timeline; the magnetic track closes the gap
        val middle = project.mainVideoClips[1]
        project = TimelineEngine.trimClip(project, middle.id, middle.trimStartMs, middle.trimStartMs + 1_000L)
        val middleAfter = project.findClip(middle.id)!!
        assertEquals(1_000L, middleAfter.trimmedSourceDurationMs)
        var trimCursor = 0L
        project.mainVideoClips.sortedBy { it.startTimeOnTrackMs }.forEach {
            assertEquals(trimCursor, it.startTimeOnTrackMs)
            trimCursor += it.timelineDurationMs
        }

        // moving a clip to the front of a magnetic track reflows instead of overlapping
        val lastId = project.mainVideoClips.last().id
        project = TimelineEngine.moveClipToTimestamp(project, lastId, 0L)
        val ordered = project.mainVideoClips.sortedBy { it.startTimeOnTrackMs }
        assertEquals(lastId, ordered.first().id)
        var cursor = 0L
        ordered.forEach { clip ->
            assertEquals(cursor, clip.startTimeOnTrackMs)
            cursor += clip.timelineDurationMs
        }
        assertEquals(cursor, project.totalDurationMs)
    }

    @Test
    fun test06b_freezeFrameAndDuplication() {
        var project = twoClipProject()
        val clipId = project.mainVideoClips[0].id
        project = TimelineEngine.insertFreezeFrame(project, clipId, 2_000L, 1_500L)
        val frozen = project.mainVideoClips.firstOrNull { it.isFrozenFrame }
        assertNotNull(frozen)
        assertEquals(1_500L, frozen!!.timelineDurationMs)

        val before = project.mainVideoClips.size
        project = TimelineEngine.duplicateClip(project, clipId)
        assertEquals(before + 1, project.mainVideoClips.size)
        // The duplicate is inserted right after the source and the magnetic track stays gapless.
        val ordered = project.mainVideoClips.sortedBy { it.startTimeOnTrackMs }
        var cursor = 0L
        ordered.forEach {
            assertEquals(cursor, it.startTimeOnTrackMs)
            cursor += it.timelineDurationMs
        }
    }

    // ------------------------------------------------------------------ 7. text & overlays

    @Test
    fun test07_textAndOverlayTracksAndSubtitles() {
        var project = twoClipProject()
        project = TimelineEngine.addTextClip(project, TextStyleSpec(text = "Hello ZERO"), 1_000L, 2_500L)
        val textTrack = project.trackByType(TrackType.TEXT)
        assertNotNull(textTrack)
        assertEquals(1, textTrack!!.clips.size)
        assertEquals("Hello ZERO", textTrack.clips[0].textSpec?.text)
        // timelineDurationMs honours the requested cue length, but never drops under the 300 ms floor.
        assertEquals(2_500L, textTrack.clips[0].timelineDurationMs)
        assertEquals(MediaKind.TEXT_ELEMENT, textTrack.clips[0].mediaKind)

        val pip = TimelineEngine.addTrack(project, TrackType.VIDEO_PIP)
        assertNotNull(pip.trackByType(TrackType.VIDEO_PIP))
        val withOverlay = TimelineEngine.importMediaClips(
            pip,
            listOf(video("overlay", 4_000)),
            TrackType.VIDEO_PIP,
            insertAtPlayheadMs = 500L
        )
        val overlayClip = withOverlay.trackByType(TrackType.VIDEO_PIP)!!.clips.first()
        assertEquals(500L, overlayClip.startTimeOnTrackMs)
        assertTrue(overlayClip.transform.scaleX < 0.9f) // PiP default inset
        assertEquals(0.55f, overlayClip.transform.scaleX, 0.001f)

        // transform + opacity + blend are honoured by the keyframe evaluator
        val transformed = TimelineEngine.setClipTransform(
            withOverlay, overlayClip.id, ClipTransform(positionX = 0.25f, scaleX = 1.4f, scaleY = 1.4f, opacity = 0.6f, blendMode = BlendMode.SCREEN)
        )
        val overlayTransform = transformed.findClip(overlayClip.id)!!.transform
        assertEquals(0.25f, overlayTransform.positionX, 0.001f)
        assertEquals(1.4f, overlayTransform.scaleX, 0.001f)
        assertEquals(0.6f, overlayTransform.opacity, 0.001f)
        val state = KeyframeInterpolator.evaluateClipState(transformed.findClip(overlayClip.id)!!, 0L)
        assertEquals(0.25f, state.positionX, 0.001f)
        assertEquals(1.4f, state.scaleX, 0.001f)
        assertEquals(0.6f, state.opacity, 0.001f)

        // subtitles round-trip through SRT
        project = TimelineEngine.upsertSubtitleCue(
            project, SubtitleCue(text = "First line", startTimeMs = 0, endTimeMs = 1_500)
        )
        project = TimelineEngine.upsertSubtitleCue(
            project, SubtitleCue(text = "Second line", startTimeMs = 1_500, endTimeMs = 3_000)
        )
        val srt = SubtitleCodec.exportSrt(project.subtitles)
        val parsed = SubtitleCodec.parseSrt(srt)
        assertEquals(2, parsed.size)
        assertEquals("First line", parsed[0].text)
    }

    // ------------------------------------------------------------------ 8. music sync & mixing

    @Test
    fun test08_audioMixGainsFadesAndDucking() {
        var project = twoClipProject()
        project = TimelineEngine.importMediaClips(
            project,
            listOf(
                ImportedMediaSource(
                    uri = "file:///music.m4a",
                    displayName = "Music",
                    mediaKind = MediaKind.AUDIO,
                    durationMs = 12_000L,
                    hasAudioTrack = true
                )
            ),
            TrackType.AUDIO_MUSIC,
            0L
        )
        val musicClip = project.trackByType(TrackType.AUDIO_MUSIC)!!.clips.first()
        assertEquals(0L, musicClip.startTimeOnTrackMs)

        project = TimelineEngine.updateClip(project, musicClip.id) {
            it.copy(volume = 0.5f, fadeInMs = 1_000L, fadeOutMs = 1_000L, audioDucking = true)
        }
        val updated = project.findClip(musicClip.id)!!
        assertEquals(0.5f, updated.volume, 0.001f)

        // beat markers survive serialisation (used by the waveform lane)
        val marked = TimelineEngine.updateClip(project, musicClip.id) {
            it.copy(beatMarkersMs = listOf(0L, 500L, 1_000L), waveformPeaks = List(32) { 0.4f })
        }
        val decoded = ProjectJsonCodec.decodeProject(ProjectJsonCodec.encodeProject(marked))
        val roundTripped = decoded.findClip(musicClip.id)!!
        assertEquals(3, roundTripped.beatMarkersMs.size)
        assertEquals(32, roundTripped.waveformPeaks.size)

        // audio DSP produces deterministic samples for a WAV SFX preset
        val wav = AudioDspEngine.synthesizeSfxWavBytes(BuiltInSfxPreset.entries.first(), 44_100)
        assertTrue(wav.size > 1_000)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        // Declared WAV length must match the real byte count, or players truncate the SFX.
        val declared = java.nio.ByteBuffer.wrap(wav, 4, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(wav.size - 8, declared)
    }

    // ------------------------------------------------------------------ 9. transitions

    @Test
    fun test09_transitionsHaveRealShaderCode() {
        // Each transition must map to its own shader branch (0 = no transition).
        val codes = TransitionType.entries.map { GlslShaderGenerator.transitionCode(it) }
        assertEquals(TransitionType.entries.size, codes.toSet().size)
        assertTrue("transition codes must be positive", codes.all { it > 0 })
        assertEquals(0, GlslShaderGenerator.transitionCode(null))
        // The finishing shader must actually select on those ids (1..7 are explicit branches,
        // 8 = CUSTOM is the designed fallback branch).
        val finishing = GlslShaderGenerator.buildMasterFinishingFragmentShader()
        (1..7).forEach { id ->
            assertTrue("finishing pass has no branch for transition id $id", finishing.contains("uTransitionType == $id"))
        }
        assertEquals(8, GlslShaderGenerator.transitionCode(TransitionType.CUSTOM))
        assertTrue(finishing.contains("uTransitionChromatic"))
        // FADE and DISSOLVE must be distinct implementations (a dissolve is a dithered reveal).
        assertTrue(finishing.contains("uTransitionType == 1"))
        assertTrue(finishing.contains("uTransitionType == 2"))
        assertTrue("dissolve needs a dither term", finishing.contains("dissolve"))
        var project = twoClipProject()
        val clipId = project.mainVideoClips[0].id
        project = TimelineEngine.setClipTransition(
            project, clipId, ClipTransition(type = TransitionType.WIPE, durationMs = 600L, directionAngleDeg = 90f)
        )
        val clip = project.findClip(clipId)!!
        assertEquals(TransitionType.WIPE, clip.transitionIn!!.type)
        assertEquals(600L, clip.transitionIn!!.durationMs)

        project = TimelineEngine.setClipTransition(project, clipId, null)
        assertNull(project.findClip(clipId)!!.transitionIn)
    }

    // ------------------------------------------------------------------ 10/11. export planning

    @Test
    fun test10_exportStructureMatchesTimelineFor1080p() {
        val project = twoClipProject()
        val cfg = project.exportConfig.copy(
            resolution = ExportResolution.FHD_1080P,
            frameRate = ExportFrameRate.FPS_30,
            bitrateTier = BitrateTier.HIGH
        )
        val (w, h) = AspectRatioPreset.dimensionsForProject(
            project.aspectRatio, cfg.resolution.shortSidePx, project.customAspectRatio
        )
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertTrue("1080p portrait must be ~1080 wide, got $w", abs(w - 1080) <= 16)
        assertTrue("1080p portrait must be ~1920 tall, got $h", abs(h - 1920) <= 16)
        assertTrue(cfg.targetVideoBitrateBps(project.aspectRatio, project.customAspectRatio) > 4_000_000)

        val planned = ExportSegmentPlanner.planVisualTracks(project)
        assertTrue(planned.isNotEmpty())
        assertEquals(0L, planned.first().segment.timelineStartMs)
        assertEquals(project.totalDurationMs, ExportSegmentPlanner.totalExportDurationMs(project))
        assertEquals(
            project.totalDurationMs,
            ExportSegmentPlanner.durationMsForFrames(project.totalDurationMs, 30)
        )
    }

    @Test
    fun test11_speedRampExportSplitsIntoConstantSegments() {
        var project = twoClipProject()
        val clipId = project.mainVideoClips[0].id
        project = TimelineEngine.setClipSpeed(
            project,
            clipId,
            speed = 1f,
            curvePreset = SpeedCurvePreset.MONTAGE
        )
        val clip = project.findClip(clipId)!!
        assertTrue(clip.hasSpeedRamp)

        val segments = ExportSegmentPlanner.planClip(clip)
        assertTrue("ramped clip must be split for export", segments.size > 1)
        // Segment timeline spans must tile the clip exactly, in order, with no gaps.
        var cursor = clip.startTimeOnTrackMs
        segments.forEach { seg ->
            assertEquals(cursor, seg.timelineStartMs)
            cursor = seg.timelineEndMs
            assertTrue(seg.sourceEndMs > seg.sourceStartMs)
        }
        assertEquals(clip.endTimeOnTrackMs, cursor)

        // Preview pieces have the same coverage and a real playback rate.
        val pieces = ExportSegmentPlanner.planPreviewPlaylist(project).filter { it.clip.id == clipId }
        assertEquals(clip.timelineDurationMs, pieces.sumOf { it.timelineSpanMs })
        pieces.forEach {
            assertTrue(it.speed in 0.1f..4.0f)
        }
    }

    @Test
    fun test11b_4kExportIsHonestAboutDetail() {
        val project = twoClipProject()
        val cfg = project.exportConfig.copy(
            resolution = ExportResolution.UHD_4K,
            frameRate = ExportFrameRate.FPS_60
        )
        val (w, h) = AspectRatioPreset.dimensionsForProject(
            project.aspectRatio, cfg.resolution.shortSidePx, project.customAspectRatio
        )
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertTrue("4K portrait must be at least 2160 tall, got $h", h >= 2160)

        val plan = EnhancementPlanner.plan(
            config = project.enhancement.copy(enabled = true, targetHeight = 2160),
            aspect = project.aspectRatio,
            customRatio = project.customAspectRatio,
            sourceWidth = 1080,
            sourceHeight = 1920
        )
        assertFalse("upscaling must never be advertised as AI", plan.isAiEnhanced)
        assertTrue(plan.realDetailRatio <= 1f)
        assertTrue(plan.upscaleLabel.isNotBlank())
        assertTrue(plan.detailHonestyLabel.isNotBlank())
    }

    // ------------------------------------------------------------------ 12. persistence

    @Test
    fun test12_projectRoundTripsThroughJsonAndUndoStack() {
        var project = twoClipProject()
        val clipId = project.mainVideoClips[0].id
        project = TimelineEngine.setColorGrade(
            project, clipId, ColorGradeConfig(exposure = 0.4f, sharpness = 0.5f, vignette = 0.3f, grain = 0.2f)
        )
        project = TimelineEngine.addOrUpdateEffect(
            project, clipId, AppliedEffect(type = EffectType.VHS, intensity = 0.7f)
        )
        project = TimelineEngine.upsertKeyframe(
            project, clipId, Keyframe(property = KeyframeProperty.SCALE, timeOffsetMs = 0L, value = 1f)
        )
        project = TimelineEngine.upsertKeyframe(
            project, clipId, Keyframe(property = KeyframeProperty.SCALE, timeOffsetMs = 2_000L, value = 1.6f)
        )

        val decoded = ProjectJsonCodec.decodeProject(ProjectJsonCodec.encodeProject(project))
        val clip = decoded.findClip(clipId)!!
        assertEquals(0.4f, clip.colorGrade.exposure, 0.001f)
        assertEquals(0.5f, clip.colorGrade.sharpness, 0.001f)
        assertEquals(0.3f, clip.colorGrade.vignette, 0.001f)
        assertEquals(1, clip.effects.size)
        assertEquals(EffectType.VHS, clip.effects[0].type)
        assertEquals(2, clip.keyframes.size)

        // animated scale interpolates between the two keyframes
        val mid = KeyframeInterpolator.evaluateClipState(clip, 1_000L)
        assertTrue(mid.scaleX > 1.05f && mid.scaleX < 1.55f)

        // undo/redo restores exactly the previous grade
        val session = TimelineSessionManager()
        session.reset(decoded)
        session.execute("Grade") { TimelineEngine.setColorGrade(it, clipId, ColorGradeConfig()) }
        assertEquals(0f, session.currentProject.findClip(clipId)!!.colorGrade.exposure, 0.001f)
        val undone = session.undo()
        assertNotNull(undone)
        assertEquals(0.4f, undone!!.findClip(clipId)!!.colorGrade.exposure, 0.001f)
        val redone = session.redo()
        assertEquals(0f, redone!!.findClip(clipId)!!.colorGrade.exposure, 0.001f)
    }

    @Test
    fun test12b_keyframeEasingIsMonotonicAndBounded() {
        val kfs = listOf(
            Keyframe(property = KeyframeProperty.OPACITY, timeOffsetMs = 0L, value = 0f, easing = EasingCurve.EASE_IN_OUT),
            Keyframe(property = KeyframeProperty.OPACITY, timeOffsetMs = 1_000L, value = 1f, easing = EasingCurve.EASE_IN_OUT)
        )
        var previous = -1f
        for (t in 0..1_000 step 100) {
            val value = KeyframeInterpolator.evaluateProperty(kfs, KeyframeProperty.OPACITY, t.toLong(), 1f)
            assertTrue("opacity eased backwards at $t", value >= previous - 0.001f)
            assertTrue(value in 0f..1f)
            previous = value
        }
        assertEquals(0f, KeyframeInterpolator.evaluateEasing(0f, EasingCurve.EASE_IN_OUT), 0.001f)
        assertEquals(1f, KeyframeInterpolator.evaluateEasing(1f, EasingCurve.EASE_IN_OUT), 0.001f)
    }

    @Test
    fun test12c_aspectRatioPresetsAndCustomRatiosAreCodecSafe() {
        // 9:16 (portrait) and 16:9 (landscape) must orient the short side correctly.
        val (pw, ph) = AspectRatioPreset.dimensionsForProject(AspectRatioPreset.REELS_9_16, 1080, 0.5625f)
        assertTrue("portrait must be taller", ph > pw)
        val (lw, lh) = AspectRatioPreset.dimensionsForProject(AspectRatioPreset.YOUTUBE_16_9, 1080, 0.5625f)
        assertTrue("landscape must be wider", lw > lh)
        // 1080 is the short side, so a 16:9 frame is ~1920x1080 (snapped to the 16px grid).
        assertTrue("landscape height ~1080, got $lh", abs(lh - 1080) <= 16)
        assertTrue("landscape width ~1920, got $lw", abs(lw - 1920) <= 16)

        // A custom ratio is honoured: for ratio <= 1 the short side is the width.
        val (cw, ch) = AspectRatioPreset.CUSTOM.computeDimensionsFor(1080, 0.8f)
        assertEquals(1080, cw)  // unaligned helper keeps the exact requested short side
        assertTrue("custom 0.8 height ~1350, got $ch", abs(ch - 1350) <= 16)
        listOf(
            AspectRatioPreset.REELS_9_16,
            AspectRatioPreset.YOUTUBE_16_9,
            AspectRatioPreset.SQUARE_1_1,
            AspectRatioPreset.PORTRAIT_4_5,
            AspectRatioPreset.CUSTOM
        ).forEach { preset ->
            listOf(720, 1080, 1440, 2160).forEach { shortSide ->
                val (w, h) = AspectRatioPreset.dimensionsForProject(preset, shortSide, 0.8f)
                assertEquals("$preset @ $shortSide width must be aligned", 0, w % 16)
                assertEquals("$preset @ $shortSide height must be aligned", 0, h % 16)
                assertTrue(w > 0 && h > 0)
            }
        }
        assertEquals(AspectRatioPreset.SQUARE_1_1, AspectRatioPreset.fromId("1:1"))
        // Unknown ids fall back to the default ratio instead of crashing a saved project.
        assertEquals(AspectRatioPreset.REELS_9_16, AspectRatioPreset.fromId("nonsense"))
    }
}

package com.vibecut.pro.engine.ai

import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import com.vibecut.pro.engine.timeline.TimelineEngine
import java.util.UUID
import kotlin.math.*

enum class AiToolType(
    val title: String,
    val subtitle: String,
    val executionMode: AiExecutionMode,
    val badge: String
) {
    AUTO_EDIT("AI Auto Editing", "Beat-matched cuts, color grade & transitions", AiExecutionMode.ON_DEVICE_DSP_VISION, "On-Device"),
    VIDEO_HIGHLIGHTS("AI Video Highlights", "Extracts peak motion & audio energy moments", AiExecutionMode.ON_DEVICE_DSP_VISION, "On-Device"),
    SCENE_DETECTION("AI Scene Detection", "Histogram χ² cut detection & auto-split", AiExecutionMode.ON_DEVICE_DSP_VISION, "On-Device"),
    BEAT_SYNC("AI Beat Sync", "Onset energy-flux beat analysis & clip snapping", AiExecutionMode.ON_DEVICE_DSP_VISION, "On-Device"),
    SMART_CROP("AI Smart Crop", "ML Kit face/salience subject-centered crop", AiExecutionMode.ON_DEVICE_MLKIT, "ML Kit"),
    AUTO_REFRAME("AI Auto Reframing", "Dynamic pan-scan keyframes for 9:16 / 1:1 / 16:9", AiExecutionMode.ON_DEVICE_MLKIT, "ML Kit"),
    FACE_TRACKING("AI Face Tracking", "ML Kit face trajectory -> motion keyframes", AiExecutionMode.ON_DEVICE_MLKIT, "ML Kit"),
    MOTION_TRACKING("AI Motion Tracking", "Patch NCC optical tracker -> sticker/text lock", AiExecutionMode.ON_DEVICE_DSP_VISION, "On-Device"),
    BACKGROUND_REMOVAL("AI Background Removal", "ML Kit Selfie Subject Segmentation mask", AiExecutionMode.ON_DEVICE_MLKIT, "ML Kit"),
    SUBTITLE_GENERATOR("AI Subtitle Generator", "Hindi (hi-IN) & English SpeechRecognizer + VAD", AiExecutionMode.ON_DEVICE_SPEECH, "Speech AI"),
    VOICE_ENHANCEMENT("AI Voice Enhancement", "2.8kHz vocal clarity exciter & dynamics", AiExecutionMode.ON_DEVICE_DSP_VISION, "DSP AI"),
    NOISE_REMOVAL("AI Noise Removal", "High-pass rumble filter + spectral noise gate", AiExecutionMode.ON_DEVICE_DSP_VISION, "DSP AI"),
    VIDEO_UPSCALING("AI Video Upscaling", "2K/4K lanczos target + adaptive unsharp detail", AiExecutionMode.ON_DEVICE_DSP_VISION, "GPU Shader"),
    IMAGE_TO_VIDEO("AI Image to Video", "3D Ken-Burns depth parallax & motion sequence", AiExecutionMode.ON_DEVICE_DSP_VISION, "On-Device"),
    SCRIPT_TO_VIDEO("AI Script to Video", "Parses scenes -> timed captions, media & transitions", AiExecutionMode.ON_DEVICE_DSP_VISION, "On-Device"),
    VIDEO_SUMMARIZATION("AI Video Summarization", "Analyzes scenes, pace, audio & generates summary", AiExecutionMode.ON_DEVICE_DSP_VISION, "On-Device"),
    OBJECT_REMOVAL("AI Object Removal", "Cloud inpainting API interface + local blur mask", AiExecutionMode.EXTERNAL_API_WITH_LOCAL_MASK, "API / Mask")
}

enum class AiExecutionMode {
    ON_DEVICE_MLKIT,
    ON_DEVICE_SPEECH,
    ON_DEVICE_DSP_VISION,
    EXTERNAL_API_WITH_LOCAL_MASK
}

data class FrameVisualSample(
    val timestampMs: Long,
    /** 24-bin RGB histogram normalized to sum = 1 */
    val rgbHistogram: FloatArray,
    /** Mean Sobel gradient edge sharpness [0..1] */
    val edgeEnergy: Float,
    /** Normalized subject / face center X in [0..1] if detected */
    val subjectCenterX: Float = 0.5f,
    /** Normalized subject / face center Y in [0..1] if detected */
    val subjectCenterY: Float = 0.5f,
    val hasDetectedFace: Boolean = false
)

data class HighlightSegment(
    val startMs: Long,
    val endMs: Long,
    val score: Float,
    val reason: String
)

data class VideoSummaryReport(
    val totalDurationSec: Float,
    val detectedScenesCount: Int,
    val highlightWindows: List<HighlightSegment>,
    val dominantPaceLabel: String,
    val summaryBullets: List<String>
)

/**
 * Clean integration contract for cloud-only generative AI features (e.g., neural object inpainting).
 * Never fakes cloud output — returns explicit [ExternalAiResult.NotConfigured] if no endpoint/key is set.
 */
interface ExternalGenAiGateway {
    val isConfigured: Boolean
    val providerName: String
    suspend fun requestObjectInpainting(
        clipUri: String,
        normalizedMaskRect: CropRect,
        startMs: Long,
        endMs: Long
    ): ExternalAiResult
}

sealed class ExternalAiResult {
    data class Success(val processedMediaUri: String, val message: String) : ExternalAiResult()
    data class NotConfigured(val integrationHint: String) : ExternalAiResult()
    data class Failure(val errorMessage: String) : ExternalAiResult()
}

object LocalAiAlgorithms {

    /**
     * Computes Chi-Square histogram distance between consecutive sampled frames to detect real visual scene cuts.
     */
    fun detectSceneCutTimestamps(
        samples: List<FrameVisualSample>,
        sensitivity: Float = 0.55f,
        minSceneLengthMs: Long = 900L
    ): List<Long> {
        if (samples.size < 2) return emptyList()
        val sorted = samples.sortedBy { it.timestampMs }
        val distances = FloatArray(sorted.size)
        for (i in 1 until sorted.size) {
            distances[i] = chiSquareDistance(sorted[i - 1].rgbHistogram, sorted[i].rgbHistogram)
        }
        val mean = distances.drop(1).average().toFloat()
        val threshold = max(0.14f, mean * (2.4f - sensitivity.coerceIn(0.1f, 0.95f) * 1.2f))

        val cuts = mutableListOf<Long>()
        var lastCut = 0L
        for (i in 1 until sorted.size) {
            val ts = sorted[i].timestampMs
            if (distances[i] >= threshold && ts - lastCut >= minSceneLengthMs) {
                cuts.add(ts)
                lastCut = ts
            }
        }
        return cuts
    }

    /**
     * Scores candidate windows using visual sharpness/motion delta + audio RMS envelope
     * to extract the best video highlight segments.
     */
    fun extractHighlightSegments(
        samples: List<FrameVisualSample>,
        audioPeaks: List<Float>,
        sourceDurationMs: Long,
        windowDurationMs: Long = 3_000L,
        maxHighlights: Int = 3
    ): List<HighlightSegment> {
        if (sourceDurationMs <= windowDurationMs) {
            return listOf(HighlightSegment(0L, sourceDurationMs, 0.92f, "Full high-energy clip"))
        }
        val candidates = mutableListOf<HighlightSegment>()
        val stepMs = max(500L, windowDurationMs / 2)
        var start = 0L
        while (start + windowDurationMs <= sourceDurationMs) {
            val end = start + windowDurationMs
            val inWindow = samples.filter { it.timestampMs in start..end }
            val visualScore = if (inWindow.isNotEmpty()) {
                val sharpness = inWindow.map { it.edgeEnergy }.average().toFloat()
                val faceBonus = if (inWindow.any { it.hasDetectedFace }) 0.2f else 0f
                (sharpness * 0.8f + faceBonus).coerceIn(0f, 1f)
            } else 0.45f

            val audioScore = if (audioPeaks.isNotEmpty()) {
                val i0 = ((start.toDouble() / sourceDurationMs) * audioPeaks.size).toInt().coerceIn(0, audioPeaks.lastIndex)
                val i1 = ((end.toDouble() / sourceDurationMs) * audioPeaks.size).toInt().coerceIn(i0, audioPeaks.lastIndex)
                audioPeaks.subList(i0, i1 + 1).average().toFloat()
            } else 0.5f

            val combined = (visualScore * 0.6f + audioScore * 0.4f).coerceIn(0f, 1f)
            val reason = when {
                inWindow.any { it.hasDetectedFace } && audioScore > 0.65f -> "Subject focus + peak audio energy"
                visualScore > 0.6f -> "Sharp high-detail action moment"
                else -> "Balanced scene energy"
            }
            candidates.add(HighlightSegment(start, end, combined, reason))
            start += stepMs
        }

        val chosen = mutableListOf<HighlightSegment>()
        for (cand in candidates.sortedByDescending { it.score }) {
            if (chosen.size >= maxHighlights) break
            val overlaps = chosen.any { max(it.startMs, cand.startMs) < min(it.endMs, cand.endMs) }
            if (!overlaps) chosen.add(cand)
        }
        return chosen.sortedBy { it.startMs }
    }

    /**
     * Generates smooth pan-and-scan keyframes from detected face/salience coordinates
     * for AI Smart Crop, AI Auto Reframing, AI Face Tracking, and AI Motion Tracking.
     */
    fun generateTrackingKeyframes(
        clipDurationMs: Long,
        samples: List<FrameVisualSample>,
        sourceAspect: Float,
        targetAspect: Float
    ): List<Keyframe> {
        if (samples.isEmpty()) return emptyList()
        val zoomNeeded = if (sourceAspect > targetAspect) {
            (sourceAspect / targetAspect).coerceIn(1.05f, 2.4f)
        } else {
            (targetAspect / sourceAspect).coerceIn(1.05f, 2.4f)
        }
        val kfs = mutableListOf<Keyframe>()
        for (sample in samples) {
            val tMs = sample.timestampMs.coerceIn(0L, clipDurationMs)
            // Convert [0..1] image coords to NDC [-1..1] centering shift
            val panX = ((0.5f - sample.subjectCenterX) * 0.85f).coerceIn(-0.65f, 0.65f)
            val panY = ((sample.subjectCenterY - 0.5f) * 0.65f).coerceIn(-0.55f, 0.55f)
            kfs.add(Keyframe(property = KeyframeProperty.POSITION_X, timeOffsetMs = tMs, value = panX, easing = EasingCurve.EASE_IN_OUT))
            kfs.add(Keyframe(property = KeyframeProperty.POSITION_Y, timeOffsetMs = tMs, value = panY, easing = EasingCurve.EASE_IN_OUT))
            kfs.add(Keyframe(property = KeyframeProperty.SCALE, timeOffsetMs = tMs, value = zoomNeeded, easing = EasingCurve.EASE_IN_OUT))
        }
        return kfs
    }

    /**
     * AI Auto-Edit: Combines highlight trims, cinematic color preset, pro sharpen, and beat transitions.
     */
    fun applyAiAutoEdit(
        project: Project,
        beatTimestampsMs: List<Long> = emptyList()
    ): Project {
        val mainTrack = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN } ?: return project
        if (mainTrack.isLocked || mainTrack.clips.isEmpty()) return project

        val cinematicGrade = ColorGradingEngine.builtInPresets.first().config.copy(sharpness = 0.30f)
        val transitions = listOf(
            TransitionType.ZOOM,
            TransitionType.DISSOLVE,
            TransitionType.FLASH,
            TransitionType.SWIPE
        )

        val editedClips = mainTrack.clips.mapIndexed { idx, clip ->
            // Trim dead head/tail (10% trim if > 4 seconds) for punchier pacing
            val rawLen = clip.trimmedSourceDurationMs
            val smartStart = if (rawLen > 4_000L) clip.trimStartMs + (rawLen * 0.08).roundToLong() else clip.trimStartMs
            val smartEnd = if (rawLen > 4_000L) clip.trimEndMs - (rawLen * 0.10).roundToLong() else clip.trimEndMs
            val sharpenFx = AppliedEffect(
                type = EffectType.SHARPEN,
                intensity = 0.42f,
                secondaryParam = 0.5f
            )
            val existingNonSharpen = clip.effects.filterNot { it.type == EffectType.SHARPEN }
            clip.copy(
                trimStartMs = smartStart,
                trimEndMs = max(smartStart + 500L, smartEnd),
                colorGrade = if (clip.colorGrade.isNeutral) cinematicGrade else clip.colorGrade,
                effects = existingNonSharpen + sharpenFx,
                transitionIn = if (idx == 0) null else ClipTransition(
                    type = transitions[idx % transitions.size],
                    durationMs = 420L
                )
            )
        }

        var nextProject = TimelineEngine.updateTrack(project, mainTrack.id) { t ->
            t.copy(clips = TimelineEngine.reflowMagneticClips(editedClips))
        }
        if (beatTimestampsMs.size >= 3) {
            nextProject = com.vibecut.pro.engine.audio.AudioDspEngine.syncVideoClipsToBeats(
                nextProject,
                beatTimestampsMs,
                beatsPerCut = 2
            )
        }
        return nextProject
    }

    /**
     * AI Script-to-Video: Parses user script lines/paragraphs into timed scenes, pairs with
     * available clips (or generates stylized title scenes), adds animated captions & transitions.
     */
    fun buildScriptToVideoProject(
        baseProject: Project,
        scriptText: String,
        availableMedia: List<ImportedMediaSource>
    ): Project {
        val lines = scriptText.lines()
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return baseProject

        var working = baseProject
        if (working.tracks.first { it.type == TrackType.VIDEO_MAIN }.clips.isEmpty() && availableMedia.isNotEmpty()) {
            working = TimelineEngine.importMediaClips(working, availableMedia, TrackType.VIDEO_MAIN)
        }

        var cursorMs = 0L
        val generatedCues = mutableListOf<SubtitleCue>()
        for ((index, line) in lines.withIndex()) {
            val wordCount = line.split(Regex("\\s+")).size
            val sceneDurationMs = (wordCount * 420L).coerceIn(2_000L, 5_000L)
            generatedCues.add(
                SubtitleCue(
                    id = UUID.randomUUID().toString(),
                    text = line,
                    startTimeMs = cursorMs + 80L,
                    endTimeMs = cursorMs + sceneDurationMs - 80L,
                    languageTag = if (line.any { it.code in 0x0900..0x097F }) "hi-IN" else "en-US"
                )
            )
            if (index == 0) {
                working = TimelineEngine.addTextClip(
                    working,
                    TextStyleSpec(
                        text = line.take(36),
                        fontSizeSp = 32f,
                        useGradient = true,
                        textColorArgb = 0xFFFFFFFF,
                        gradientEndColorArgb = 0xFF00E5FF,
                        animationIn = TextAnimationType.POP_BOUNCE
                    ),
                    startMs = 0L,
                    durationMs = min(2_400L, sceneDurationMs)
                )
            }
            cursorMs += sceneDurationMs
        }

        working = com.vibecut.pro.engine.subtitles.SubtitleCodec.applySubtitlesToTextTrack(working, generatedCues)
        return TimelineEngine.applyTransitionToAllMainClips(
            working,
            ClipTransition(TransitionType.DISSOLVE, 450L)
        )
    }

    /**
     * AI Image-to-Video: Applies 3D Ken-Burns keyframed camera motion + cinematic vignette & transitions
     * to still images (or selected clips) so static photos become dynamic animated sequences.
     */
    fun applyImageToVideoMotion(project: Project, clipId: String? = null): Project {
        val mainTrack = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN } ?: return project
        if (mainTrack.isLocked) return project

        return TimelineEngine.updateTrack(project, mainTrack.id) { track ->
            track.copy(
                clips = track.clips.mapIndexed { idx, clip ->
                    if (clipId != null && clip.id != clipId) return@mapIndexed clip
                    val dur = clip.timelineDurationMs
                    val zoomIn = idx % 2 == 0
                    val startScale = if (zoomIn) 1.02f else 1.26f
                    val endScale = if (zoomIn) 1.26f else 1.04f
                    val panDir = if (idx % 3 == 0) 0.08f else -0.08f
                    val motionKfs = listOf(
                        Keyframe(property = KeyframeProperty.SCALE, timeOffsetMs = 0L, value = startScale, easing = EasingCurve.EASE_IN_OUT),
                        Keyframe(property = KeyframeProperty.SCALE, timeOffsetMs = dur, value = endScale, easing = EasingCurve.EASE_IN_OUT),
                        Keyframe(property = KeyframeProperty.POSITION_X, timeOffsetMs = 0L, value = -panDir, easing = EasingCurve.EASE_IN_OUT),
                        Keyframe(property = KeyframeProperty.POSITION_X, timeOffsetMs = dur, value = panDir, easing = EasingCurve.EASE_IN_OUT)
                    )
                    clip.copy(
                        keyframes = clip.keyframes.filterNot {
                            it.property == KeyframeProperty.SCALE || it.property == KeyframeProperty.POSITION_X
                        } + motionKfs,
                        transitionIn = clip.transitionIn ?: if (idx > 0) ClipTransition(TransitionType.DISSOLVE, 600L) else null
                    )
                }
            )
        }
    }

    /**
     * Generates a factual Video Summarization report from real timeline & frame metrics.
     */
    fun summarizeProject(
        project: Project,
        visualSamples: List<FrameVisualSample> = emptyList()
    ): VideoSummaryReport {
        val mainClips = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN }?.clips.orEmpty()
        val totalSec = project.totalDurationMs / 1000f
        val sceneCuts = detectSceneCutTimestamps(visualSamples)
        val sceneCount = max(mainClips.size, sceneCuts.size + if (mainClips.isNotEmpty()) 1 else 0)
        val avgClipSec = if (mainClips.isNotEmpty()) totalSec / mainClips.size else 0f
        val pace = when {
            avgClipSec < 1.8f -> "Fast Rhythmic Montage"
            avgClipSec < 4.0f -> "Dynamic Social Reel Pace"
            else -> "Narrative / Documentary Pace"
        }
        val combinedPeaks = mainClips.flatMap { it.waveformPeaks }
        val highlights = extractHighlightSegments(
            samples = visualSamples,
            audioPeaks = combinedPeaks,
            sourceDurationMs = max(3_000L, project.totalDurationMs)
        )
        val fxCount = mainClips.sumOf { it.effects.size }
        val gradedCount = mainClips.count { !it.colorGrade.isNeutral }
        val bullets = buildList {
            add("Timeline spans ${String.format("%.1f", totalSec)}s across ${mainClips.size} clip(s) and $sceneCount detected visual scene(s).")
            add("Editing rhythm classified as '$pace' (avg shot ${String.format("%.1f", avgClipSec)}s).")
            add("Active GPU pipeline: $fxCount shader effect(s), $gradedCount color-graded clip(s), and ${project.subtitles.size} caption cue(s).")
            if (highlights.isNotEmpty()) {
                val top = highlights.maxBy { it.score }
                add("Peak highlight window at ${top.startMs / 1000}s–${top.endMs / 1000}s (${top.reason}).")
            }
        }
        return VideoSummaryReport(
            totalDurationSec = totalSec,
            detectedScenesCount = sceneCount,
            highlightWindows = highlights,
            dominantPaceLabel = pace,
            summaryBullets = bullets
        )
    }

    private fun chiSquareDistance(h1: FloatArray, h2: FloatArray): Float {
        val len = min(h1.size, h2.size)
        var sum = 0f
        for (i in 0 until len) {
            val denom = h1[i] + h2[i]
            if (denom > 1e-6f) {
                val diff = h1[i] - h2[i]
                sum += (diff * diff) / denom
            }
        }
        return sum * 0.5f
    }
}

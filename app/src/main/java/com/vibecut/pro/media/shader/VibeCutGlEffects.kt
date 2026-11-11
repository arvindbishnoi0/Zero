package com.vibecut.pro.media.shader

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.*
import androidx.media3.transformer.Effects
import com.vibecut.pro.engine.audio.AudioDspEngine
import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.effects.GlslShaderGenerator
import com.vibecut.pro.engine.keyframes.KeyframeInterpolator
import com.vibecut.pro.engine.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Single GPU shader pass for any of the 22 [EffectType] effects (Sharpen, Blur, Glitch, VHS, Neon, etc.).
 * Multiple effects on one clip chain sequentially via Media3's FBO pipeline.
 */
@OptIn(UnstableApi::class)
class VibeCutSingleEffectGlEffect(
    private val effect: AppliedEffect,
    private val keyframes: List<Keyframe>
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return SingleEffectShaderProgram(useHdr, effect, keyframes)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        return !effect.enabled || effect.intensity <= 0.001f
    }

    private class SingleEffectShaderProgram(
        useHdr: Boolean,
        private val effect: AppliedEffect,
        private val keyframes: List<Keyframe>
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

        private val glProgram: GlProgram
        private var texelW = 1f / 1080f
        private var texelH = 1f / 1920f

        init {
            try {
                glProgram = GlProgram(
                    GlslShaderGenerator.STANDARD_VERTEX_SHADER_ES2,
                    GlslShaderGenerator.buildEffectFragmentShader(effect.type)
                )
                glProgram.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            texelW = 1f / max(1, inputWidth).toFloat()
            texelH = 1f / max(1, inputHeight).toFloat()
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val clipOffsetMs = (presentationTimeUs / 1000L).coerceAtLeast(0L)
                val kfMultiplier = KeyframeInterpolator.evaluateProperty(
                    keyframes,
                    KeyframeProperty.EFFECT_INTENSITY,
                    clipOffsetMs,
                    1.0f
                )
                val activeIntensity = (effect.intensity * kfMultiplier).coerceIn(0f, 1.5f)
                val timeSec = (presentationTimeUs / 1_000_000.0).toFloat()

                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.setFloatsUniformIfPresent("uTexelSize", floatArrayOf(texelW, texelH))
                glProgram.setFloatUniformSafe("uTimeSec", timeSec)
                glProgram.setFloatUniformSafe("uIntensity", activeIntensity)
                glProgram.setFloatUniformSafe("uSecondary", effect.secondaryParam.coerceIn(0f, 1f))
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }
}

/**
 * Master finishing OpenGL ES 2.0 pass applying Color Grading Sharpness, Keyframed Blur,
 * Clip Entry/Exit Transitions, and Opacity per presentation timestamp.
 */
@OptIn(UnstableApi::class)
class VibeCutMasterFinishingGlEffect(
    private val clip: TimelineClip
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return MasterShaderProgram(useHdr, clip)
    }

    private class MasterShaderProgram(
        useHdr: Boolean,
        private val clip: TimelineClip
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

        private val glProgram: GlProgram
        private var texelW = 1f / 1080f
        private var texelH = 1f / 1920f

        init {
            try {
                glProgram = GlProgram(
                    GlslShaderGenerator.STANDARD_VERTEX_SHADER_ES2,
                    GlslShaderGenerator.buildMasterFinishingFragmentShader()
                )
                glProgram.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            texelW = 1f / max(1, inputWidth).toFloat()
            texelH = 1f / max(1, inputHeight).toFloat()
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val offsetMs = (presentationTimeUs / 1000L).coerceAtLeast(0L)
                val state = KeyframeInterpolator.evaluateClipState(clip, offsetMs)

                var activeTransition: ClipTransition? = null
                var transitionProgress = 1.0f

                val transIn = clip.transitionIn
                val transOut = clip.transitionOut
                val clipDur = clip.timelineDurationMs

                if (transIn != null && offsetMs < transIn.durationMs) {
                    activeTransition = transIn
                    transitionProgress = (offsetMs.toFloat() / transIn.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                } else if (transOut != null && offsetMs > clipDur - transOut.durationMs) {
                    activeTransition = transOut
                    val rem = (clipDur - offsetMs).coerceAtLeast(0L)
                    transitionProgress = (rem.toFloat() / transOut.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                }

                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.setFloatsUniformIfPresent("uTexelSize", floatArrayOf(texelW, texelH))
                glProgram.setFloatUniformSafe("uGradeSharpness", clip.colorGrade.sharpness.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uKeyframeBlur", state.extraBlur.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uOpacity", state.opacity.coerceIn(0f, 1f))
                glProgram.setIntUniformSafe("uTransitionType", GlslShaderGenerator.transitionCode(activeTransition?.type))
                glProgram.setFloatUniformSafe("uTransitionProgress", transitionProgress)
                glProgram.setFloatUniformSafe("uTransitionSoftness", activeTransition?.softness ?: 0.35f)
                glProgram.setFloatUniformSafe("uTransitionChromatic", activeTransition?.chromaticAmount ?: 0.4f)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }
}

/**
 * Dynamic per-frame 2D/3D affine MatrixTransformation driven by KeyframeInterpolator.
 */
@OptIn(UnstableApi::class)
class VibeCutKeyframeMatrixTransformation(
    private val clip: TimelineClip
) : MatrixTransformation {

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val offsetMs = (presentationTimeUs / 1000L).coerceAtLeast(0L)
        val state = KeyframeInterpolator.evaluateClipState(clip, offsetMs)
        return Matrix().apply {
            postScale(state.scaleX, state.scaleY)
            if (kotlin.math.abs(state.rotationDegrees) > 0.01f) {
                postRotate(state.rotationDegrees)
            }
            if (kotlin.math.abs(state.positionX) > 0.001f || kotlin.math.abs(state.positionY) > 0.001f) {
                postTranslate(state.positionX, state.positionY)
            }
        }
    }
}

/**
 * Renders animated multi-layer Text (with Gradients, Shadows, Outlines, 3D Extrusion, Pill Background)
 * and Stickers directly onto exported/previewed video frames at exact timeline timestamps.
 */
@OptIn(UnstableApi::class)
class VibeCutSceneOverlay(
    private val clipStartOnTimelineMs: Long,
    private val textClips: List<TimelineClip>,
    private val stickerClips: List<TimelineClip>,
    private val subtitles: List<SubtitleCue>
) : BitmapOverlay() {

    private val canvasWidth = 1080
    private val canvasHeight = 1920
    private val bitmap: Bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
    private val emptyBitmap: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var lastRenderedBucketMs = -1L
    private var lastWasEmpty = true

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val globalTimelineMs = clipStartOnTimelineMs + (presentationTimeUs / 1000L)
        val activeTexts = textClips.filter { globalTimelineMs in it.startTimeOnTrackMs..it.endTimeOnTrackMs }
        val activeStickers = stickerClips.filter { globalTimelineMs in it.startTimeOnTrackMs..it.endTimeOnTrackMs }
        val activeSubs = subtitles.filter {
            globalTimelineMs in it.startTimeMs..it.endTimeMs &&
                textClips.none { tc -> tc.sourceUri == "caption://${it.id}" }
        }

        if (activeTexts.isEmpty() && activeStickers.isEmpty() && activeSubs.isEmpty()) {
            lastWasEmpty = true
            return emptyBitmap
        }

        // Quantize to ~33ms (30fps) so static frames reuse the uploaded GL texture
        val bucketMs = globalTimelineMs / 33L
        if (!lastWasEmpty && bucketMs == lastRenderedBucketMs) {
            return bitmap
        }
        lastRenderedBucketMs = bucketMs
        lastWasEmpty = false

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Draw Text Layers
        for (tc in activeTexts) {
            val spec = tc.textSpec ?: continue
            val localMs = (globalTimelineMs - tc.startTimeOnTrackMs).coerceAtLeast(0L)
            val kfState = KeyframeInterpolator.evaluateClipState(tc, localMs)
            drawStyledText(spec, localMs, kfState)
        }

        // Draw standalone subtitles if any
        for (cue in activeSubs) {
            drawStyledText(
                spec = TextStyleSpec(
                    text = cue.text,
                    fontSizeSp = 24f,
                    outlineWidth = 3f,
                    backgroundColorArgb = 0x88000000,
                    animationIn = TextAnimationType.NONE
                ),
                localMs = 500L,
                kfState = KeyframeInterpolator.evaluateClipState(
                    TimelineClip(trackId = "sub", sourceUri = "", sourceName = "", transform = ClipTransform(positionY = -0.68f)),
                    0L
                )
            )
        }

        // Draw Stickers
        for (sc in activeStickers) {
            val sticker = sc.stickerSpec ?: continue
            val localMs = (globalTimelineMs - sc.startTimeOnTrackMs).coerceAtLeast(0L)
            val kfState = KeyframeInterpolator.evaluateClipState(sc, localMs)
            fillPaint.shader = null
            fillPaint.clearShadowLayer()
            fillPaint.color = Color.WHITE
            fillPaint.alpha = (kfState.opacity * 255).roundToInt().coerceIn(0, 255)
            fillPaint.textSize = 110f * kotlin.math.abs(kfState.scaleX)
            fillPaint.textAlign = Paint.Align.CENTER
            val cx = canvasWidth * (0.5f + kfState.positionX * 0.45f)
            val cy = canvasHeight * (0.5f - kfState.positionY * 0.45f)
            canvas.save()
            canvas.rotate(kfState.rotationDegrees, cx, cy)
            canvas.drawText(sticker.content, cx, cy, fillPaint)
            canvas.restore()
        }

        return bitmap
    }

    private fun drawStyledText(
        spec: TextStyleSpec,
        localMs: Long,
        kfState: com.vibecut.pro.engine.keyframes.InterpolatedClipState
    ) {
        val animFrac = if (spec.animationIn == TextAnimationType.NONE || spec.animationDurationMs <= 0L) {
            1f
        } else {
            (localMs.toFloat() / spec.animationDurationMs).coerceIn(0f, 1f)
        }

        val visibleText = if (spec.animationIn == TextAnimationType.TYPEWRITER) {
            val count = (spec.text.length * animFrac).roundToInt().coerceIn(1, spec.text.length)
            spec.text.substring(0, count)
        } else spec.text

        val animScale = when (spec.animationIn) {
            TextAnimationType.POP_BOUNCE -> 0.5f + 0.5f * KeyframeInterpolator.evaluateEasing(animFrac, EasingCurve.CUBIC_BOUNCE)
            else -> 1f
        }
        val animAlpha = when (spec.animationIn) {
            TextAnimationType.FADE_UP, TextAnimationType.CINEMATIC_TRACKING -> animFrac
            else -> 1f
        }

        val pxSize = spec.fontSizeSp * 3.0f * kotlin.math.abs(kfState.scaleX) * animScale
        val typeface = when {
            spec.fontFamilyName.contains("Serif", ignoreCase = true) && !spec.fontFamilyName.contains("Sans", ignoreCase = true) ->
                Typeface.create(Typeface.SERIF, Typeface.BOLD)
            spec.fontFamilyName.contains("Mono", ignoreCase = true) ->
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        fillPaint.typeface = typeface
        fillPaint.textSize = pxSize
        fillPaint.textAlign = Paint.Align.CENTER
        fillPaint.letterSpacing = if (spec.animationIn == TextAnimationType.CINEMATIC_TRACKING) {
            spec.letterSpacingEm + (1f - animFrac) * 0.25f
        } else spec.letterSpacingEm

        val cx = canvasWidth * (0.5f + kfState.positionX * 0.45f)
        val yLift = if (spec.animationIn == TextAnimationType.FADE_UP) (1f - animFrac) * 45f else 0f
        val cy = canvasHeight * (0.5f - kfState.positionY * 0.45f) + yLift
        val alphaInt = (kfState.opacity * animAlpha * 255f).roundToInt().coerceIn(0, 255)

        val textWidth = fillPaint.measureText(visibleText)
        // Background pill
        if ((spec.backgroundColorArgb ushr 24) > 0) {
            bgPaint.color = spec.backgroundColorArgb.toInt()
            val padX = 28f
            val padY = 18f
            val rect = RectF(
                cx - textWidth / 2f - padX,
                cy - pxSize - padY * 0.4f,
                cx + textWidth / 2f + padX,
                cy + padY
            )
            canvas.drawRoundRect(rect, 18f, 18f, bgPaint)
        }

        canvas.save()
        canvas.rotate(kfState.rotationDegrees, cx, cy)

        // 3D Extrusion layers
        if (spec.is3dExtruded) {
            fillPaint.shader = null
            fillPaint.clearShadowLayer()
            fillPaint.color = spec.outlineColorArgb.toInt()
            fillPaint.alpha = alphaInt
            val steps = spec.extrudeDepth.roundToInt().coerceIn(2, 10)
            for (d in steps downTo 1) {
                canvas.drawText(visibleText, cx + d * 2f, cy + d * 2f, fillPaint)
            }
        }

        // Outline
        if (spec.outlineWidth > 0.2f) {
            strokePaint.typeface = typeface
            strokePaint.textSize = pxSize
            strokePaint.textAlign = Paint.Align.CENTER
            strokePaint.letterSpacing = fillPaint.letterSpacing
            strokePaint.strokeWidth = spec.outlineWidth * 2.5f
            strokePaint.color = spec.outlineColorArgb.toInt()
            strokePaint.alpha = alphaInt
            canvas.drawText(visibleText, cx, cy, strokePaint)
        }

        // Gradient or solid fill + Shadow
        if (spec.useGradient) {
            fillPaint.shader = LinearGradient(
                cx - textWidth / 2f, cy - pxSize,
                cx + textWidth / 2f, cy,
                spec.textColorArgb.toInt(),
                spec.gradientEndColorArgb.toInt(),
                Shader.TileMode.CLAMP
            )
        } else {
            fillPaint.shader = null
            fillPaint.color = spec.textColorArgb.toInt()
        }
        fillPaint.alpha = alphaInt
        if (spec.shadowRadius > 0.2f) {
            fillPaint.setShadowLayer(spec.shadowRadius * 1.8f, 0f, 3f, spec.shadowColorArgb.toInt())
        } else {
            fillPaint.clearShadowLayer()
        }
        canvas.drawText(visibleText, cx, cy, fillPaint)
        canvas.restore()
    }
}

/**
 * Media3 [SpeedProvider] supporting both constant Slow/Fast motion and multi-point Speed Curves.
 */
@OptIn(UnstableApi::class)
class VibeCutSpeedProvider(
    private val clip: TimelineClip
) : SpeedProvider {

    private val segmentsUs: LongArray
    private val speeds: FloatArray

    init {
        val activePoints = when {
            clip.speedCurvePoints.size >= 2 -> clip.speedCurvePoints
            clip.speedCurvePreset != SpeedCurvePreset.NONE -> clip.speedCurvePreset.defaultPoints
            else -> emptyList()
        }
        if (activePoints.size >= 2) {
            val steps = 24
            val durUs = max(100_000L, clip.trimmedSourceDurationMs * 1_000L)
            segmentsUs = LongArray(steps) { i -> (durUs * i) / steps }
            val sorted = activePoints.sortedBy { it.normalizedPosition }
            speeds = FloatArray(steps) { i ->
                val u = (i + 0.5f) / steps
                TimelineClip.sampleSpeedCurve(sorted, u).coerceIn(0.1f, 8.0f)
            }
        } else {
            segmentsUs = longArrayOf(0L)
            speeds = floatArrayOf(clip.speed.coerceIn(0.1f, 8.0f))
        }
    }

    override fun getSpeed(timeUs: Long): Float {
        if (speeds.size == 1) return speeds[0]
        var idx = segmentsUs.indexOfLast { it <= timeUs }
        if (idx < 0) idx = 0
        return speeds[idx]
    }

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        if (speeds.size == 1) return C.TIME_UNSET
        val nextIdx = segmentsUs.indexOfFirst { it > timeUs }
        return if (nextIdx >= 0) segmentsUs[nextIdx] else C.TIME_UNSET
    }
}

/**
 * Real-time 16-bit PCM AudioProcessor running VibeCut's Noise Gate/High-Pass,
 * Vocal Presence EQ, Fade-In/Out, Keyframed Volume, and Audio Ducking.
 */
@OptIn(UnstableApi::class)
class VibeCutDspAudioProcessor(
    private val clip: TimelineClip,
    private val project: Project,
    private val trackVolume: Float = 1.0f
) : BaseAudioProcessor() {

    private var dspState: AudioDspEngine.PcmDspState? = null
    private var processedFramesCount: Long = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        dspState = AudioDspEngine.PcmDspState(
            sampleRateHz = inputAudioFormat.sampleRate,
            channelCount = inputAudioFormat.channelCount
        )
        processedFramesCount = 0L
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val chCount = max(1, inputAudioFormat.channelCount)
        val sr = max(8000, inputAudioFormat.sampleRate)
        val shortCount = remaining / 2
        val frameCount = shortCount / chCount

        val localOffsetMs = (processedFramesCount * 1000L) / sr
        processedFramesCount += frameCount

        val kfVol = KeyframeInterpolator.evaluateClipState(clip, localOffsetMs).volume
        val clipDur = clip.timelineDurationMs.coerceAtLeast(1L)

        val fadeInFactor = if (clip.fadeInMs > 0L && localOffsetMs < clip.fadeInMs) {
            (localOffsetMs.toFloat() / clip.fadeInMs).coerceIn(0f, 1f)
        } else 1f
        val remMs = (clipDur - localOffsetMs).coerceAtLeast(0L)
        val fadeOutFactor = if (clip.fadeOutMs > 0L && remMs < clip.fadeOutMs) {
            (remMs.toFloat() / clip.fadeOutMs).coerceIn(0f, 1f)
        } else 1f

        val duckFactor = if (clip.audioDucking) {
            AudioDspEngine.computeDuckingGain(project, clip.startTimeOnTrackMs + localOffsetMs)
        } else 1f

        val totalGain = if (clip.isMuted) 0f else (kfVol * trackVolume * fadeInFactor * fadeOutFactor * duckFactor)

        val samples = ShortArray(shortCount)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        inputBuffer.position(inputBuffer.limit())

        dspState?.processInPlace(
            samples = samples,
            frameCount = frameCount,
            baseGain = totalGain,
            noiseReduction = clip.noiseReduction,
            voiceEnhancement = clip.voiceEnhancement
        )

        val out = replaceOutputBuffer(shortCount * 2)
        out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        out.limit(shortCount * 2)
    }

    override fun onFlush() {
        processedFramesCount = 0L
    }
}

/**
 * Factory building the complete Media3 [Effects] stack (AudioProcessors + OpenGL Video Effects)
 * for any [TimelineClip] in both Live ExoPlayer Preview and Hardware Transformer Export.
 */
@OptIn(UnstableApi::class)
object ClipEffectsFactory {

    fun buildVideoEffectsList(
        context: Context,
        clip: TimelineClip,
        project: Project,
        includeSceneOverlays: Boolean = false,
        targetExportHeight: Int? = null
    ): List<Effect> {
        val videoEffects = mutableListOf<Effect>()

        // 1. Crop (converted from [0..1] top-down UI coords to Media3 NDC [-1..1] bottom < top)
        if (!clip.crop.isIdentity) {
            val ndcLeft = (clip.crop.left * 2f - 1f).coerceIn(-1f, 0.9f)
            val ndcRight = (clip.crop.right * 2f - 1f).coerceIn(ndcLeft + 0.05f, 1f)
            val ndcTop = (1f - clip.crop.top * 2f).coerceIn(-0.9f, 1f)
            val ndcBottom = (1f - clip.crop.bottom * 2f).coerceIn(-1f, ndcTop - 0.05f)
            videoEffects.add(Crop(ndcLeft, ndcRight, ndcBottom, ndcTop))
        }

        // 2. Keyframed Position / Scale / Rotation / Flip Matrix
        if (!clip.transform.isIdentity || clip.keyframes.isNotEmpty()) {
            videoEffects.add(VibeCutKeyframeMatrixTransformation(clip))
        }

        // 3. Stacked GPU OpenGL ES 2.0 Shader Effects (all 22 supported, multiple per clip!)
        for (fx in clip.effects) {
            if (fx.enabled && fx.intensity > 0.005f) {
                videoEffects.add(VibeCutSingleEffectGlEffect(fx, clip.keyframes))
            }
        }

        // 4. 3D Color Grading Cube LUT (Custom .cube file OR Baked Grade Config)
        val customLutUri = clip.colorGrade.customCubeLutUri
        if (!customLutUri.isNullOrBlank()) {
            runCatching {
                val text = context.contentResolver.openInputStream(Uri.parse(customLutUri))
                    ?.bufferedReader()?.use { it.readText() }
                if (!text.isNullOrBlank()) {
                    val cube = ColorGradingEngine.parseAdobeCubeLut(text, clip.colorGrade.lutIntensity)
                    videoEffects.add(SingleColorLut.createFromCube(cube.argbCube))
                }
            }
        }
        if (!clip.colorGrade.isNeutral) {
            val bakedCube = ColorGradingEngine.bakeTo3dCube(
                cfg = clip.colorGrade,
                size = 17,
                mixIntensity = clip.colorGrade.lutIntensity
            )
            videoEffects.add(SingleColorLut.createFromCube(bakedCube.argbCube))
        }

        // 5. Master Finishing Shader (Grading Sharpness, Keyframe Blur, Transitions, Opacity)
        val needsFinishing = clip.colorGrade.sharpness > 0.005f ||
            clip.transitionIn != null ||
            clip.transitionOut != null ||
            clip.transform.opacity < 0.999f ||
            clip.keyframes.any { it.property == KeyframeProperty.BLUR || it.property == KeyframeProperty.OPACITY }
        if (needsFinishing) {
            videoEffects.add(VibeCutMasterFinishingGlEffect(clip))
        }

        // 6. Scene Text / Captions / Stickers Overlay (used during export)
        if (includeSceneOverlays) {
            val textTrackClips = project.tracks
                .filter { it.type == TrackType.TEXT && !it.isHidden }
                .flatMap { it.clips }
            val stickerTrackClips = project.tracks
                .filter { it.type == TrackType.STICKER && !it.isHidden }
                .flatMap { it.clips }
            if (textTrackClips.isNotEmpty() || stickerTrackClips.isNotEmpty() || project.subtitles.isNotEmpty()) {
                val overlay = VibeCutSceneOverlay(
                    clipStartOnTimelineMs = clip.startTimeOnTrackMs,
                    textClips = textTrackClips,
                    stickerClips = stickerTrackClips,
                    subtitles = project.subtitles
                )
                videoEffects.add(OverlayEffect(listOf(overlay)))
            }
        }

        // 7. Target Resolution / AI Super-Sharp Upscaling
        val desiredHeight = targetExportHeight ?: clip.upscaleTargetHeight
        if (desiredHeight != null && desiredHeight > 0) {
            val (outW, outH) = project.aspectRatio.computeDimensions(desiredHeight)
            videoEffects.add(
                Presentation.createForWidthAndHeight(
                    outW,
                    outH,
                    Presentation.LAYOUT_SCALE_TO_FIT
                )
            )
        }

        return videoEffects
    }

    fun buildFullClipEffects(
        context: Context,
        clip: TimelineClip,
        project: Project,
        trackVolume: Float = 1.0f,
        includeSceneOverlays: Boolean = false,
        targetExportHeight: Int? = null,
        targetFps: Int? = null
    ): Effects {
        val audioProcessors = mutableListOf<AudioProcessor>()
        val videoEffects = buildVideoEffectsList(
            context = context,
            clip = clip,
            project = project,
            includeSceneOverlays = includeSceneOverlays,
            targetExportHeight = targetExportHeight
        ).toMutableList()

        // Variable Speed Curve / Constant Speed with A/V synchronization
        val hasVariableCurve = clip.speedCurvePreset != SpeedCurvePreset.NONE || clip.speedCurvePoints.size >= 2
        val hasNonUnitSpeed = kotlin.math.abs(clip.speed - 1.0f) > 0.01f
        if ((hasVariableCurve || hasNonUnitSpeed) && !clip.isFrozenFrame && clip.mediaKind == MediaKind.VIDEO) {
            val pair = Effects.createExperimentalSpeedChangingEffect(VibeCutSpeedProvider(clip))
            audioProcessors.add(pair.first)
            videoEffects.add(0, pair.second)
        }

        if (targetFps != null && targetFps > 0) {
            videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(targetFps.toFloat()))
        }

        audioProcessors.add(VibeCutDspAudioProcessor(clip, project, trackVolume))
        return Effects(audioProcessors, videoEffects)
    }
}

@OptIn(UnstableApi::class)
private fun GlProgram.setFloatUniformSafe(name: String, value: Float) {
    if (getUniformLocation(name) >= 0) {
        setFloatUniform(name, value)
    }
}

@OptIn(UnstableApi::class)
private fun GlProgram.setIntUniformSafe(name: String, value: Int) {
    if (getUniformLocation(name) >= 0) {
        setIntUniform(name, value)
    }
}

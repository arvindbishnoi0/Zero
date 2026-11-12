package com.vibecut.pro.media.shader

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
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
import com.vibecut.pro.engine.color.CubeLut3D
import com.vibecut.pro.engine.effects.GlslShaderGenerator
import com.vibecut.pro.engine.keyframes.KeyframeInterpolator
import com.vibecut.pro.engine.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared thread-safe live state holder for real-time ExoPlayer GPU preview.
 * Slider edits update `@Volatile` fields here in <0.01ms without tearing down Media3's VideoGraph
 * or recompiling OpenGL shaders, eliminating lag and paused-frame black flickers.
 */
class LivePreviewStateHolder {
    @Volatile
    var activeClip: TimelineClip? = null

    @Volatile
    var customPresets: List<ColorGradePreset> = emptyList()

    @Volatile
    var localClipOffsetMs: Long = 0L

    @Volatile
    var isCompareBypass: Boolean = false

    @Volatile
    var customLutCubeCacheUri: String? = null

    @Volatile
    var customLutCubeCache: CubeLut3D? = null
}

/**
 * Single GPU shader pass for any of the 32 [EffectType] effects.
 * Supports both deterministic Export mode and zero-rebuild Live Preview mode (reading live slider params).
 */
@OptIn(UnstableApi::class)
class VibeCutSingleEffectGlEffect(
    private val effect: AppliedEffect,
    private val keyframes: List<Keyframe>,
    private val liveState: LivePreviewStateHolder? = null
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return SingleEffectShaderProgram(useHdr, effect, keyframes, liveState)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        if (liveState != null) return false
        return !effect.enabled || effect.intensity <= 0.001f
    }

    private class SingleEffectShaderProgram(
        useHdr: Boolean,
        private val initialEffect: AppliedEffect,
        private val initialKeyframes: List<Keyframe>,
        private val liveState: LivePreviewStateHolder?
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

        private val glProgram: GlProgram
        private val passthroughProgram: GlProgram
        private var texelW = 1f / 1080f
        private var texelH = 1f / 1920f
        private var firstPtsUs: Long = C.TIME_UNSET

        init {
            try {
                glProgram = GlProgram(
                    GlslShaderGenerator.STANDARD_VERTEX_SHADER_ES2,
                    GlslShaderGenerator.buildEffectFragmentShader(initialEffect.type)
                )
                glProgram.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
                passthroughProgram = GlProgram(
                    GlslShaderGenerator.STANDARD_VERTEX_SHADER_ES2,
                    """
                    precision highp float;
                    varying vec2 vTexSamplingCoord;
                    uniform sampler2D uTexSampler;
                    void main() {
                        gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
                    }
                    """.trimIndent()
                )
                passthroughProgram.setBufferAttribute(
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
            firstPtsUs = C.TIME_UNSET
            return Size(inputWidth, inputHeight)
        }

        override fun flush() {
            super.flush()
            firstPtsUs = C.TIME_UNSET
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val live = liveState
                val currentClip = live?.activeClip
                val liveFx = if (currentClip != null) {
                    currentClip.effects.firstOrNull { it.id == initialEffect.id }
                        ?: currentClip.effects.firstOrNull { it.type == initialEffect.type }
                } else {
                    initialEffect
                }

                if (live?.isCompareBypass == true || liveFx == null || !liveFx.enabled || liveFx.intensity <= 0.002f) {
                    passthroughProgram.use()
                    passthroughProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                    passthroughProgram.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    return
                }

                val clipOffsetMs = if (live != null) {
                    live.localClipOffsetMs.coerceAtLeast(0L)
                } else {
                    if (firstPtsUs == C.TIME_UNSET || presentationTimeUs < firstPtsUs) {
                        firstPtsUs = presentationTimeUs
                    }
                    ((presentationTimeUs - firstPtsUs).coerceAtLeast(0L)) / 1000L
                }

                val kfs = currentClip?.keyframes ?: initialKeyframes
                val kfMultiplier = KeyframeInterpolator.evaluateProperty(
                    kfs,
                    KeyframeProperty.EFFECT_INTENSITY,
                    clipOffsetMs,
                    1.0f
                )
                val activeIntensity = (liveFx.intensity * kfMultiplier).coerceIn(0f, 1.5f)
                val timeSec = ((clipOffsetMs % 600_000L) / 1000.0).toFloat()

                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.setFloatsUniformIfPresent("uTexelSize", floatArrayOf(texelW, texelH))
                glProgram.setFloatUniformSafe("uTimeSec", timeSec)
                glProgram.setFloatUniformSafe("uIntensity", activeIntensity)
                glProgram.setFloatUniformSafe("uSecondary", liveFx.secondaryParam.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uTertiary", liveFx.tertiaryParam.coerceIn(0f, 1f))
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
                passthroughProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }
}

/**
 * Live GPU 3D Color Grading LUT Shader using a `289 x 17` (`17^2 x 17`) 2D Hald strip texture.
 * Updates the 3D LUT texture on the OpenGL thread ONLY when color parameters change, without
 * rebuilding ExoPlayer's VideoGraph—giving zero-flicker 60fps live color & filter preview!
 */
@OptIn(UnstableApi::class)
class VibeCutLiveColorLutGlEffect(
    private val liveState: LivePreviewStateHolder
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return LiveColorLutShaderProgram(useHdr, liveState)
    }

    private class LiveColorLutShaderProgram(
        useHdr: Boolean,
        private val liveState: LivePreviewStateHolder
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

        private val glProgram: GlProgram
        private var lutTexId: Int = 0
        private var uploadedSignature: String = ""
        private val lutSize = 17
        private val lutBitmap = Bitmap.createBitmap(lutSize * lutSize, lutSize, Bitmap.Config.ARGB_8888)
        private val lutPixels = IntArray(lutSize * lutSize * lutSize)

        init {
            try {
                glProgram = GlProgram(
                    GlslShaderGenerator.STANDARD_VERTEX_SHADER_ES2,
                    """
                    precision highp float;
                    varying vec2 vTexSamplingCoord;
                    uniform sampler2D uTexSampler;
                    uniform sampler2D uLutSampler;
                    uniform float uColorMix;

                    void main() {
                        vec4 base = texture2D(uTexSampler, vTexSamplingCoord);
                        if (uColorMix <= 0.002) {
                            gl_FragColor = base;
                            return;
                        }
                        vec3 c = clamp(base.rgb, 0.0, 1.0);
                        float blueCoord = c.b * 16.0;
                        float b0 = floor(blueCoord);
                        float b1 = min(16.0, b0 + 1.0);
                        float bf = blueCoord - b0;

                        float v = (c.g * 16.0 + 0.5) / 17.0;
                        float u0 = (b0 * 17.0 + c.r * 16.0 + 0.5) / 289.0;
                        float u1 = (b1 * 17.0 + c.r * 16.0 + 0.5) / 289.0;

                        vec3 s0 = texture2D(uLutSampler, vec2(u0, v)).rgb;
                        vec3 s1 = texture2D(uLutSampler, vec2(u1, v)).rgb;
                        vec3 graded = mix(s0, s1, bf);
                        gl_FragColor = vec4(mix(base.rgb, graded, clamp(uColorMix, 0.0, 1.0)), base.a);
                    }
                    """.trimIndent()
                )
                glProgram.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                lutTexId = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val clip = liveState.activeClip
                val grade = clip?.colorGrade
                if (liveState.isCompareBypass || clip == null || grade == null || !grade.hasColorLutChanges) {
                    glProgram.use()
                    glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                    glProgram.setSamplerTexIdUniform("uLutSampler", lutTexId, 1)
                    glProgram.setFloatUniformSafe("uColorMix", 0f)
                    glProgram.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    return
                }

                val presets = liveState.customPresets
                val sig = buildColorLutSignature(grade, presets)
                if (sig != uploadedSignature) {
                    uploadedSignature = sig
                    uploadBakedLut2D(grade, presets, liveState.customLutCubeCache)
                }

                val kfColorMix = KeyframeInterpolator.evaluateProperty(
                    clip.keyframes,
                    KeyframeProperty.COLOR_INTENSITY,
                    liveState.localClipOffsetMs,
                    1.0f
                )

                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.setSamplerTexIdUniform("uLutSampler", lutTexId, 1)
                glProgram.setFloatUniformSafe("uColorMix", kfColorMix.coerceIn(0f, 1f))
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        private fun uploadBakedLut2D(
            grade: ColorGradeConfig,
            customPresets: List<ColorGradePreset>,
            customCube: CubeLut3D?
        ) {
            val n = lutSize
            val width = n * n
            for (gIdx in 0 until n) {
                val g0 = gIdx.toFloat() / (n - 1)
                for (bIdx in 0 until n) {
                    val b0 = bIdx.toFloat() / (n - 1)
                    for (rIdx in 0 until n) {
                        val r0 = rIdx.toFloat() / (n - 1)
                        var rIn = r0
                        var gIn = g0
                        var bIn = b0
                        if (customCube != null && !grade.customCubeLutUri.isNullOrBlank()) {
                            val sampled = sampleCubeTrilinear(customCube, r0, g0, b0, grade.lutIntensity)
                            rIn = sampled[0]
                            gIn = sampled[1]
                            bIn = sampled[2]
                        }
                        val rgb = ColorGradingEngine.gradePixelRgb(rIn, gIn, bIn, grade, customPresets)
                        val ri = (rgb[0] * 255f).roundToInt().coerceIn(0, 255)
                        val gi = (rgb[1] * 255f).roundToInt().coerceIn(0, 255)
                        val bi = (rgb[2] * 255f).roundToInt().coerceIn(0, 255)
                        val x = bIdx * n + rIdx
                        lutPixels[gIdx * width + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                    }
                }
            }
            lutBitmap.setPixels(lutPixels, 0, width, 0, 0, width, n)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, lutBitmap, 0)
        }

        private fun buildColorLutSignature(
            g: ColorGradeConfig,
            presets: List<ColorGradePreset>
        ): String = buildString(160) {
            append(g.exposure).append('|').append(g.brightness).append('|').append(g.contrast)
            append('|').append(g.saturation).append('|').append(g.vibrance)
            append('|').append(g.temperature).append('|').append(g.tint).append('|').append(g.hueShift)
            append('|').append(g.highlights).append('|').append(g.shadows)
            append('|').append(g.whites).append('|').append(g.blacks).append('|').append(g.gamma)
            append('|').append(g.dehaze).append('|').append(g.fade)
            append('|').append(g.lutPresetId).append('|').append(g.customCubeLutUri).append('|').append(g.lutIntensity)
            append('|').append(g.hslBands.hashCode()).append('|').append(g.curves.hashCode()).append('|').append(g.colorWheels.hashCode())
            append('|').append(presets.size)
        }

        override fun release() {
            super.release()
            if (lutTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(lutTexId), 0)
                lutTexId = 0
            }
            lutBitmap.recycle()
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }
}

/**
 * Master finishing OpenGL ES 2.0 pass applying Pro Unsharp-Mask Sharpness (Radius + Threshold),
 * Clarity, Structure, Denoise, Vignette, Film Grain, Keyframed Blur, Transitions, and Opacity.
 * Supports both Live Preview (reading from [LivePreviewStateHolder]) and Export mode.
 */
@OptIn(UnstableApi::class)
class VibeCutMasterFinishingGlEffect(
    private val clip: TimelineClip,
    private val customPresets: List<ColorGradePreset> = emptyList(),
    private val liveState: LivePreviewStateHolder? = null
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return MasterShaderProgram(useHdr, clip, customPresets, liveState)
    }

    private class MasterShaderProgram(
        useHdr: Boolean,
        private val initialClip: TimelineClip,
        private val initialCustomPresets: List<ColorGradePreset>,
        private val liveState: LivePreviewStateHolder?
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

        private val glProgram: GlProgram
        private var texelW = 1f / 1080f
        private var texelH = 1f / 1920f
        private var firstPtsUs: Long = C.TIME_UNSET

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
            firstPtsUs = C.TIME_UNSET
            return Size(inputWidth, inputHeight)
        }

        override fun flush() {
            super.flush()
            firstPtsUs = C.TIME_UNSET
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val live = liveState
                val activeClip = live?.activeClip ?: initialClip
                val presets = live?.customPresets ?: initialCustomPresets
                val bypass = live?.isCompareBypass == true

                val offsetMs = if (live != null) {
                    live.localClipOffsetMs.coerceAtLeast(0L)
                } else {
                    if (firstPtsUs == C.TIME_UNSET || presentationTimeUs < firstPtsUs) {
                        firstPtsUs = presentationTimeUs
                    }
                    ((presentationTimeUs - firstPtsUs).coerceAtLeast(0L)) / 1000L
                }

                val state = KeyframeInterpolator.evaluateClipState(activeClip, offsetMs)
                val spatial = if (bypass) {
                    ColorGradeConfig()
                } else {
                    ColorGradingEngine.resolveEffectiveSpatialConfig(activeClip.colorGrade, presets)
                }

                var activeTransition: ClipTransition? = null
                var transitionProgress = 1.0f

                if (!bypass) {
                    val transIn = activeClip.transitionIn
                    val transOut = activeClip.transitionOut
                    val clipDur = activeClip.timelineDurationMs

                    if (transIn != null && offsetMs < transIn.durationMs) {
                        activeTransition = transIn
                        transitionProgress = (offsetMs.toFloat() / transIn.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    } else if (transOut != null && offsetMs > clipDur - transOut.durationMs) {
                        activeTransition = transOut
                        val rem = (clipDur - offsetMs).coerceAtLeast(0L)
                        transitionProgress = (rem.toFloat() / transOut.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    }
                }

                val timeSec = ((offsetMs % 600_000L) / 1000.0).toFloat()

                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.setFloatsUniformIfPresent("uTexelSize", floatArrayOf(texelW, texelH))
                glProgram.setFloatUniformSafe("uTimeSec", timeSec)
                glProgram.setFloatUniformSafe("uGradeSharpness", spatial.sharpness.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uSharpenRadius", spatial.sharpenRadius.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uSharpenThreshold", spatial.sharpenThreshold.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uClarity", spatial.clarity.coerceIn(-1f, 1f))
                glProgram.setFloatUniformSafe("uStructure", spatial.structure.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uDenoise", spatial.denoise.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uVignette", spatial.vignette.coerceIn(-1f, 1f))
                glProgram.setFloatUniformSafe("uVignetteFeather", spatial.vignetteFeather.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uGrain", spatial.grain.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uKeyframeBlur", if (bypass) 0f else state.extraBlur.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uOpacity", if (bypass) 1f else state.opacity.coerceIn(0f, 1f))
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
 * Dynamic per-frame 2D affine MatrixTransformation driven by KeyframeInterpolator + Crop Zoom/Pan.
 */
@OptIn(UnstableApi::class)
class VibeCutKeyframeMatrixTransformation(
    private val clip: TimelineClip,
    private val liveState: LivePreviewStateHolder? = null
) : MatrixTransformation {

    private var firstPtsUs: Long = C.TIME_UNSET

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        firstPtsUs = C.TIME_UNSET
        return Size(inputWidth, inputHeight)
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val live = liveState
        if (live?.isCompareBypass == true) return Matrix()

        val activeClip = live?.activeClip ?: clip
        val offsetMs = if (live != null) {
            live.localClipOffsetMs.coerceAtLeast(0L)
        } else {
            if (firstPtsUs == C.TIME_UNSET || presentationTimeUs < firstPtsUs) {
                firstPtsUs = presentationTimeUs
            }
            ((presentationTimeUs - firstPtsUs).coerceAtLeast(0L)) / 1000L
        }

        val state = KeyframeInterpolator.evaluateClipState(activeClip, offsetMs)
        return Matrix().apply {
            // Live Crop support inside matrix when liveState is active (zero VideoGraph rebuild!)
            val crop = activeClip.crop
            if (live != null && !crop.isIdentity) {
                val zoomX = (1f / crop.widthFraction).coerceIn(1f, 6f)
                val zoomY = (1f / crop.heightFraction).coerceIn(1f, 6f)
                val centerShiftX = (0.5f - (crop.left + crop.right) * 0.5f) * 2f * zoomX
                val centerShiftY = (((crop.top + crop.bottom) * 0.5f) - 0.5f) * 2f * zoomY
                postScale(zoomX, zoomY)
                postTranslate(centerShiftX, centerShiftY)
            }

            postScale(state.scaleX, state.scaleY)
            if (abs(state.rotationDegrees) > 0.01f) {
                postRotate(state.rotationDegrees)
            }
            if (abs(state.positionX) > 0.001f || abs(state.positionY) > 0.001f) {
                postTranslate(state.positionX, state.positionY)
            }
        }
    }
}

/**
 * Renders animated multi-layer Text, Captions, Stickers, and PIP/Overlay layers directly onto
 * exported video frames at exact clip-relative normalized timeline timestamps.
 */
@OptIn(UnstableApi::class)
class VibeCutSceneOverlay(
    private val context: Context,
    private val aspectRatio: AspectRatioPreset,
    private val clipStartOnTimelineMs: Long,
    private val pipClips: List<TimelineClip>,
    private val textClips: List<TimelineClip>,
    private val stickerClips: List<TimelineClip>,
    private val subtitles: List<SubtitleCue>
) : BitmapOverlay() {

    private val dims = aspectRatio.computeDimensions(1080)
    private val canvasWidth = dims.first
    private val canvasHeight = dims.second
    private val bitmap: Bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
    private val emptyBitmap: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pipBitmapCache = LinkedHashMap<String, Bitmap?>()
    private var lastRenderedBucketMs = -1L
    private var lastWasEmpty = true
    private var firstPtsUs: Long = C.TIME_UNSET

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (firstPtsUs == C.TIME_UNSET || presentationTimeUs < firstPtsUs) {
            firstPtsUs = presentationTimeUs
        }
        val localClipMs = ((presentationTimeUs - firstPtsUs).coerceAtLeast(0L)) / 1000L
        val globalTimelineMs = clipStartOnTimelineMs + localClipMs

        val activePips = pipClips.filter { globalTimelineMs in it.startTimeOnTrackMs..it.endTimeOnTrackMs }
        val activeTexts = textClips.filter { globalTimelineMs in it.startTimeOnTrackMs..it.endTimeOnTrackMs }
        val activeStickers = stickerClips.filter { globalTimelineMs in it.startTimeOnTrackMs..it.endTimeOnTrackMs }
        val activeSubs = subtitles.filter {
            globalTimelineMs in it.startTimeMs..it.endTimeMs &&
                textClips.none { tc -> tc.sourceUri == "caption://${it.id}" }
        }

        if (activePips.isEmpty() && activeTexts.isEmpty() && activeStickers.isEmpty() && activeSubs.isEmpty()) {
            lastWasEmpty = true
            return emptyBitmap
        }

        val bucketMs = globalTimelineMs / 33L
        if (!lastWasEmpty && bucketMs == lastRenderedBucketMs) {
            return bitmap
        }
        lastRenderedBucketMs = bucketMs
        lastWasEmpty = false

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Draw PIP / Overlay visual clips
        for (pip in activePips) {
            val pipBmp = loadPipThumbnailBitmap(pip) ?: continue
            val localMs = (globalTimelineMs - pip.startTimeOnTrackMs).coerceAtLeast(0L)
            val kf = KeyframeInterpolator.evaluateClipState(pip, localMs)
            val cx = canvasWidth * (0.5f + kf.positionX * 0.45f)
            val cy = canvasHeight * (0.5f - kf.positionY * 0.45f)
            val baseW = canvasWidth * 0.55f * abs(kf.scaleX)
            val aspect = pipBmp.height.toFloat() / max(1, pipBmp.width).toFloat()
            val drawW = baseW
            val drawH = baseW * aspect
            pipPaint.alpha = (kf.opacity * 255f).roundToInt().coerceIn(0, 255)
            canvas.save()
            canvas.rotate(kf.rotationDegrees, cx, cy)
            val dst = RectF(cx - drawW / 2f, cy - drawH / 2f, cx + drawW / 2f, cy + drawH / 2f)
            canvas.drawBitmap(pipBmp, null, dst, pipPaint)
            canvas.restore()
        }

        // Draw Text Layers
        for (tc in activeTexts) {
            val spec = tc.textSpec ?: continue
            val localMs = (globalTimelineMs - tc.startTimeOnTrackMs).coerceAtLeast(0L)
            val kfState = KeyframeInterpolator.evaluateClipState(tc, localMs)
            drawStyledText(spec, localMs, kfState)
        }

        // Draw standalone subtitles
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
        val scaleFactor = min(canvasWidth, canvasHeight) / 1080f
        for (sc in activeStickers) {
            val sticker = sc.stickerSpec ?: continue
            val localMs = (globalTimelineMs - sc.startTimeOnTrackMs).coerceAtLeast(0L)
            val kfState = KeyframeInterpolator.evaluateClipState(sc, localMs)
            fillPaint.shader = null
            fillPaint.clearShadowLayer()
            fillPaint.color = Color.WHITE
            fillPaint.alpha = (kfState.opacity * 255).roundToInt().coerceIn(0, 255)
            fillPaint.textSize = 110f * scaleFactor * abs(kfState.scaleX)
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

    private fun loadPipThumbnailBitmap(pip: TimelineClip): Bitmap? {
        if (pipBitmapCache.containsKey(pip.id)) return pipBitmapCache[pip.id]
        val uriStr = pip.thumbnailUri ?: pip.sourceUri
        val bmp = runCatching {
            val u = Uri.parse(uriStr)
            if (u.scheme == "file" && !u.path.isNullOrBlank()) {
                BitmapFactory.decodeFile(u.path)
            } else {
                context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
        if (pipBitmapCache.size > 8) {
            pipBitmapCache.entries.firstOrNull()?.let { pipBitmapCache.remove(it.key) }
        }
        pipBitmapCache[pip.id] = bmp
        return bmp
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
            val count = (spec.text.length * animFrac).roundToInt().coerceIn(1, spec.text.length.coerceAtLeast(1))
            spec.text.take(count)
        } else spec.text

        val animScale = when (spec.animationIn) {
            TextAnimationType.POP_BOUNCE -> 0.5f + 0.5f * KeyframeInterpolator.evaluateEasing(animFrac, EasingCurve.CUBIC_BOUNCE)
            else -> 1f
        }
        val animAlpha = when (spec.animationIn) {
            TextAnimationType.FADE_UP, TextAnimationType.CINEMATIC_TRACKING -> animFrac
            else -> 1f
        }

        val refShortSide = min(canvasWidth, canvasHeight).toFloat()
        val pxSize = spec.fontSizeSp * (refShortSide / 360f) * abs(kfState.scaleX) * animScale
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
 * Multi-format PCM AudioProcessor supporting 16-bit, 32-bit float, 24-bit, 32-bit int, and 8-bit PCM
 * decoders (preventing `UnhandledAudioFormatException` on modern Android AAC/Opus/MP3/FLAC decoders!),
 * running VibeCut's Noise Gate/High-Pass, Vocal Presence EQ, Fade-In/Out, Keyframed Volume, and Ducking.
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
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_24BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_32BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_8BIT
        if (!supported) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        dspState = AudioDspEngine.PcmDspState(
            sampleRateHz = inputAudioFormat.sampleRate,
            channelCount = inputAudioFormat.channelCount
        )
        processedFramesCount = 0L
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_16BIT
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val chCount = max(1, inputAudioFormat.channelCount)
        val sr = max(8000, inputAudioFormat.sampleRate)
        val bytesPerSample = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val sampleCount = remaining / bytesPerSample
        if (sampleCount <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val frameCount = sampleCount / chCount

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

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(sampleCount)
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                inputBuffer.asShortBuffer().get(samples)
                inputBuffer.position(inputBuffer.position() + sampleCount * 2)
            }
            C.ENCODING_PCM_FLOAT -> {
                val fb = inputBuffer.asFloatBuffer()
                for (i in 0 until sampleCount) {
                    val f = fb.get()
                    val clean = if (f.isNaN() || f.isInfinite()) 0f else f.coerceIn(-1f, 1f)
                    samples[i] = (clean * 32767f).roundToInt().toShort()
                }
                inputBuffer.position(inputBuffer.position() + sampleCount * 4)
            }
            C.ENCODING_PCM_24BIT -> {
                for (i in 0 until sampleCount) {
                    val b0 = inputBuffer.get().toInt() and 0xFF
                    val b1 = inputBuffer.get().toInt() and 0xFF
                    val b2 = inputBuffer.get().toInt() // sign-extended MSB
                    val v24 = (b2 shl 16) or (b1 shl 8) or b0
                    samples[i] = (v24 shr 8).coerceIn(-32768, 32767).toShort()
                }
            }
            C.ENCODING_PCM_32BIT -> {
                val ib = inputBuffer.asIntBuffer()
                for (i in 0 until sampleCount) {
                    samples[i] = (ib.get() shr 16).coerceIn(-32768, 32767).toShort()
                }
                inputBuffer.position(inputBuffer.position() + sampleCount * 4)
            }
            C.ENCODING_PCM_8BIT -> {
                for (i in 0 until sampleCount) {
                    val u = inputBuffer.get().toInt() and 0xFF
                    samples[i] = ((u - 128) shl 8).toShort()
                }
            }
        }
        inputBuffer.position(inputBuffer.limit())

        dspState?.processInPlace(
            samples = samples,
            frameCount = frameCount,
            baseGain = totalGain,
            noiseReduction = clip.noiseReduction,
            voiceEnhancement = clip.voiceEnhancement
        )

        val out = replaceOutputBuffer(sampleCount * 2)
        out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        out.limit(sampleCount * 2)
    }

    override fun onFlush() {
        processedFramesCount = 0L
    }
}

/**
 * Factory building the Media3 [Effects] stack for both Live ExoPlayer Preview (zero-flicker live uniforms)
 * and Hardware Transformer Export (16px codec-safe alignment + deterministic timestamps).
 */
@OptIn(UnstableApi::class)
object ClipEffectsFactory {

    /**
     * Builds persistent live-preview effects bound to [liveState].
     * Only needs to be rebuilt when the ordered list of [EffectType]s on the active clip changes
     * or proxy target height changes! All slider adjustments update live in-place!
     */
    fun buildLivePreviewVideoEffects(
        activeClip: TimelineClip,
        liveState: LivePreviewStateHolder,
        lowResProxyMode: Boolean
    ): List<Effect> {
        val effects = mutableListOf<Effect>()

        // 1. Live Crop + Transform + Keyframe Matrix
        effects.add(VibeCutKeyframeMatrixTransformation(activeClip, liveState))

        // 2. Stacked GPU Effects (reading live intensity/secondary/tertiary/enabled from liveState)
        for (fx in activeClip.effects) {
            effects.add(VibeCutSingleEffectGlEffect(fx, activeClip.keyframes, liveState))
        }

        // 3. Live 2D-unwrapped 3D Color Grading & Filter LUT
        effects.add(VibeCutLiveColorLutGlEffect(liveState))

        // 4. Live Spatial Detail (Sharpness, Radius, Guard, Clarity, Structure, Denoise, Vignette, Grain) & Transitions
        effects.add(VibeCutMasterFinishingGlEffect(activeClip, liveState.customPresets, liveState))

        // 5. Optional proxy / upscale presentation
        val desiredHeight = if (lowResProxyMode) 480 else activeClip.upscaleTargetHeight
        if (desiredHeight != null && desiredHeight > 0) {
            effects.add(Presentation.createForHeight(desiredHeight))
        }

        return effects
    }

    /**
     * Builds deterministic per-clip export video effects for Media3 [Transformer].
     */
    fun buildVideoEffectsList(
        context: Context,
        clip: TimelineClip,
        project: Project,
        includeSceneOverlays: Boolean = false,
        targetExportHeight: Int? = null,
        useCodecSafeAlignment: Boolean = false
    ): List<Effect> {
        val videoEffects = mutableListOf<Effect>()

        // 1. Crop
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

        // 3. Stacked GPU OpenGL ES 2.0 Shader Effects
        for (fx in clip.effects) {
            if (fx.enabled && fx.intensity > 0.005f) {
                videoEffects.add(VibeCutSingleEffectGlEffect(fx, clip.keyframes))
            }
        }

        // 4. 3D Color Grading Cube LUT (Custom .cube file + Independent Filter Preset + Manual Adjust)
        val customLutUri = clip.colorGrade.customCubeLutUri
        if (!customLutUri.isNullOrBlank() && clip.colorGrade.lutIntensity > 0.002f) {
            runCatching {
                val u = Uri.parse(customLutUri)
                val text = if (u.scheme == "file" && !u.path.isNullOrBlank()) {
                    java.io.File(u.path!!).readText()
                } else {
                    context.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() }
                }
                if (!text.isNullOrBlank()) {
                    val cube = ColorGradingEngine.parseAdobeCubeLut(text, clip.colorGrade.lutIntensity)
                    videoEffects.add(SingleColorLut.createFromCube(cube.argbCube))
                }
            }
        }
        if (clip.colorGrade.hasColorLutChanges) {
            val bakedCube = ColorGradingEngine.bakeTo3dCube(
                cfg = clip.colorGrade,
                size = 17,
                mixIntensity = 1.0f, // lutIntensity already blended independently inside gradePixelRgb!
                customPresets = project.customColorPresets
            )
            videoEffects.add(SingleColorLut.createFromCube(bakedCube.argbCube))
        }

        // 5. Master Spatial Detail & Finishing Shader
        val effectiveSpatial = ColorGradingEngine.resolveEffectiveSpatialConfig(
            clip.colorGrade,
            project.customColorPresets
        )
        val needsFinishing = effectiveSpatial.hasSpatialDetailChanges ||
            clip.transitionIn != null ||
            clip.transitionOut != null ||
            clip.transform.opacity < 0.999f ||
            clip.keyframes.any { it.property == KeyframeProperty.BLUR || it.property == KeyframeProperty.OPACITY }
        if (needsFinishing) {
            videoEffects.add(VibeCutMasterFinishingGlEffect(clip, project.customColorPresets))
        }

        // 6. Scene PIP / Text / Captions / Stickers Overlay (used during export)
        if (includeSceneOverlays) {
            val pipTrackClips = project.tracks
                .filter { (it.type == TrackType.VIDEO_PIP || it.type == TrackType.OVERLAY) && !it.isHidden }
                .flatMap { it.clips }
            val textTrackClips = project.tracks
                .filter { it.type == TrackType.TEXT && !it.isHidden }
                .flatMap { it.clips }
            val stickerTrackClips = project.tracks
                .filter { it.type == TrackType.STICKER && !it.isHidden }
                .flatMap { it.clips }
            if (pipTrackClips.isNotEmpty() || textTrackClips.isNotEmpty() || stickerTrackClips.isNotEmpty() || project.subtitles.isNotEmpty()) {
                val overlay = VibeCutSceneOverlay(
                    context = context,
                    aspectRatio = project.aspectRatio,
                    clipStartOnTimelineMs = clip.startTimeOnTrackMs,
                    pipClips = pipTrackClips,
                    textClips = textTrackClips,
                    stickerClips = stickerTrackClips,
                    subtitles = project.subtitles
                )
                videoEffects.add(OverlayEffect(listOf(overlay)))
            }
        }

        // 7. Target Resolution / AI Super-Sharp Upscaling (16px macroblock-aligned for hardware MediaCodec!)
        val desiredHeight = targetExportHeight ?: clip.upscaleTargetHeight
        if (desiredHeight != null && desiredHeight > 0) {
            val (outW, outH) = if (useCodecSafeAlignment) {
                project.aspectRatio.computeCodecSafeDimensions(desiredHeight, alignment = 16)
            } else {
                project.aspectRatio.computeDimensions(desiredHeight)
            }
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
        includeAudioProcessor: Boolean = true,
        includeSceneOverlays: Boolean = false,
        targetExportHeight: Int? = null,
        targetFps: Int? = null,
        useCodecSafeAlignment: Boolean = true
    ): Effects {
        val audioProcessors = mutableListOf<AudioProcessor>()
        val videoEffects = buildVideoEffectsList(
            context = context,
            clip = clip,
            project = project,
            includeSceneOverlays = includeSceneOverlays,
            targetExportHeight = targetExportHeight,
            useCodecSafeAlignment = useCodecSafeAlignment
        ).toMutableList()

        val hasVariableCurve = clip.speedCurvePreset != SpeedCurvePreset.NONE || clip.speedCurvePoints.size >= 2
        val hasNonUnitSpeed = abs(clip.speed - 1.0f) > 0.01f
        if ((hasVariableCurve || hasNonUnitSpeed) && !clip.isFrozenFrame && clip.mediaKind == MediaKind.VIDEO) {
            val pair = Effects.createExperimentalSpeedChangingEffect(VibeCutSpeedProvider(clip))
            if (includeAudioProcessor) {
                audioProcessors.add(pair.first)
            }
            videoEffects.add(0, pair.second)
        }

        if (targetFps != null && targetFps > 0) {
            videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(targetFps.toFloat()))
        }

        if (includeAudioProcessor) {
            audioProcessors.add(VibeCutDspAudioProcessor(clip, project, trackVolume))
        }
        return Effects(audioProcessors, videoEffects)
    }
}

private fun sampleCubeTrilinear(
    cube: CubeLut3D,
    r: Float,
    g: Float,
    b: Float,
    mix: Float
): FloatArray {
    val n = cube.size
    val maxIdx = (n - 1).toFloat()
    val rf = (r.coerceIn(0f, 1f) * maxIdx)
    val gf = (g.coerceIn(0f, 1f) * maxIdx)
    val bf = (b.coerceIn(0f, 1f) * maxIdx)
    val r0 = rf.toInt().coerceIn(0, n - 1)
    val g0 = gf.toInt().coerceIn(0, n - 1)
    val b0 = bf.toInt().coerceIn(0, n - 1)
    val r1 = min(n - 1, r0 + 1)
    val g1 = min(n - 1, g0 + 1)
    val b1 = min(n - 1, b0 + 1)
    val dr = rf - r0
    val dg = gf - g0
    val db = bf - b0

    fun ch(argb: Int, shift: Int): Float = ((argb shr shift) and 0xFF) / 255f
    val c000 = cube.argbCube[r0][g0][b0]
    val c100 = cube.argbCube[r1][g0][b0]
    val c010 = cube.argbCube[r0][g1][b0]
    val c110 = cube.argbCube[r1][g1][b0]
    val c001 = cube.argbCube[r0][g0][b1]
    val c101 = cube.argbCube[r1][g0][b1]
    val c011 = cube.argbCube[r0][g1][b1]
    val c111 = cube.argbCube[r1][g1][b1]

    fun interp(shift: Int): Float {
        val c00 = ch(c000, shift) * (1f - dr) + ch(c100, shift) * dr
        val c10 = ch(c010, shift) * (1f - dr) + ch(c110, shift) * dr
        val c01 = ch(c001, shift) * (1f - dr) + ch(c101, shift) * dr
        val c11 = ch(c011, shift) * (1f - dr) + ch(c111, shift) * dr
        val c0 = c00 * (1f - dg) + c10 * dg
        val c1 = c01 * (1f - dg) + c11 * dg
        return c0 * (1f - db) + c1 * db
    }

    val m = mix.coerceIn(0f, 1f)
    return floatArrayOf(
        r + (interp(16) - r) * m,
        g + (interp(8) - g) * m,
        b + (interp(0) - b) * m
    )
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

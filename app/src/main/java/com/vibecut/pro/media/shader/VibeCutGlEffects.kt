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
import com.vibecut.pro.engine.enhance.EnhancementPlan
import com.vibecut.pro.engine.keyframes.KeyframeInterpolator
import com.vibecut.pro.engine.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Thread-safe holder for live preview shader state.
 *
 * Slider edits write these `@Volatile` fields from the UI thread in microseconds, and the GL
 * thread reads them for every frame. This lets ZERO update colour, effects, crop, transform and
 * chroma key *without* tearing down Media3's VideoGraph (which would flash black on every drag).
 */
class LivePreviewStateHolder {
    @Volatile var activeClip: TimelineClip? = null
    @Volatile var customPresets: List<ColorGradePreset> = emptyList()
    @Volatile var localClipOffsetMs: Long = 0L
    @Volatile var isCompareBypass: Boolean = false
    @Volatile var customLutCubeCacheUri: String? = null
    @Volatile var customLutCubeCache: CubeLut3D? = null
    @Volatile var enhancement: EnhancementConfig = EnhancementConfig()
    @Volatile var projectAspect: AspectRatioPreset? = null
    @Volatile var projectCustomRatio: Float = 9f / 16f
}

/** One GPU pass for any [EffectType]; identical code path for preview and export. */
@OptIn(UnstableApi::class)
class VibeCutSingleEffectGlEffect(
    private val effect: AppliedEffect,
    private val keyframes: List<Keyframe>,
    private val liveState: LivePreviewStateHolder? = null,
    /**
     * Timeline offset (ms) of the exported segment inside the clip. Export renders each clip from
     * its own first frame, so without this every keyframed effect would restart per segment.
     */
    private val segmentOffsetMs: Long = 0L
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        SingleEffectShaderProgram(useHdr, effect, keyframes, liveState, segmentOffsetMs)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        if (liveState != null) return false
        return !effect.enabled || effect.intensity <= 0.001f
    }

    private class SingleEffectShaderProgram(
        useHdr: Boolean,
        private val initialEffect: AppliedEffect,
        private val initialKeyframes: List<Keyframe>,
        private val liveState: LivePreviewStateHolder?,
        private val segmentOffsetMs: Long = 0L
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
                val fx = if (currentClip != null) {
                    currentClip.effects.firstOrNull { it.id == initialEffect.id }
                        ?: currentClip.effects.firstOrNull { it.type == initialEffect.type }
                } else {
                    initialEffect
                }

                if (live?.isCompareBypass == true || fx == null || !fx.enabled || fx.intensity <= 0.002f) {
                    passthroughProgram.use()
                    passthroughProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                    passthroughProgram.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    return
                }

                val clipOffsetMs = if (live != null) {
                    live.localClipOffsetMs.coerceAtLeast(0L)
                } else {
                    if (firstPtsUs == C.TIME_UNSET || presentationTimeUs < firstPtsUs) firstPtsUs = presentationTimeUs
                    segmentOffsetMs + ((presentationTimeUs - firstPtsUs).coerceAtLeast(0L)) / 1000L
                }

                val kfs = currentClip?.keyframes ?: initialKeyframes
                val kfMultiplier = KeyframeInterpolator.evaluateProperty(
                    kfs, KeyframeProperty.EFFECT_INTENSITY, clipOffsetMs, 1.0f
                )
                val intensity = (fx.intensity * kfMultiplier).coerceIn(0f, 1f)
                val timeSec = ((clipOffsetMs % 600_000L) / 1000.0).toFloat()

                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.setFloatsUniformIfPresent("uTexelSize", floatArrayOf(texelW, texelH))
                glProgram.setFloatUniformSafe("uTimeSec", timeSec)
                glProgram.setFloatUniformSafe("uIntensity", intensity)
                glProgram.setFloatUniformSafe("uSecondary", fx.secondaryParam.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uTertiary", fx.tertiaryParam.coerceIn(0f, 1f))
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
 * Colour grading pass: bakes the full [ColorGradeConfig] (filter LUT + manual light/colour/HSL/
 * curves/wheels) into a 17³ 3D LUT uploaded as a 289×17 strip texture. Re-upload happens only when
 * colour parameters actually change.
 */
@OptIn(UnstableApi::class)
class VibeCutLiveColorLutGlEffect(
    private val liveState: LivePreviewStateHolder
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LiveColorLutShaderProgram(useHdr, liveState)

    private class LiveColorLutShaderProgram(
        useHdr: Boolean,
        private val liveState: LivePreviewStateHolder
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

        private val lutSize = 17
        private val glProgram: GlProgram
        private var lutTexId = 0
        private val lutBitmap: Bitmap
        private val lutPixels: IntArray
        private var uploadedSignature = ""

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
                lutBitmap = Bitmap.createBitmap(lutSize * lutSize, lutSize, Bitmap.Config.ARGB_8888)
                lutPixels = IntArray(lutSize * lutSize * lutSize)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

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
                val sig = colorLutSignature(grade, presets)
                if (sig != uploadedSignature) {
                    uploadedSignature = sig
                    uploadBakedLut(grade, presets, liveState.customLutCubeCache)
                }

                val kfColorMix = KeyframeInterpolator.evaluateProperty(
                    clip.keyframes, KeyframeProperty.COLOR_INTENSITY, liveState.localClipOffsetMs, 1.0f
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

        private fun uploadBakedLut(
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
                        lutPixels[gIdx * width + bIdx * n + rIdx] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                    }
                }
            }
            lutBitmap.setPixels(lutPixels, 0, width, 0, 0, width, n)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, lutBitmap, 0)
        }

        private fun colorLutSignature(g: ColorGradeConfig, presets: List<ColorGradePreset>): String =
            buildString(200) {
                append(g.exposure).append('|').append(g.brightness).append('|').append(g.contrast)
                append('|').append(g.saturation).append('|').append(g.vibrance)
                append('|').append(g.temperature).append('|').append(g.tint).append('|').append(g.hueShift)
                append('|').append(g.highlights).append('|').append(g.shadows)
                append('|').append(g.whites).append('|').append(g.blacks).append('|').append(g.gamma)
                append('|').append(g.dehaze).append('|').append(g.fade)
                append('|').append(g.lutPresetId).append('|').append(g.customCubeLutUri).append('|').append(g.lutIntensity)
                append('|').append(g.hslBands.hashCode()).append('|').append(g.curves.hashCode())
                append('|').append(g.colorWheels.hashCode()).append('|').append(presets.size)
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
 * Master finishing pass:
 * chroma key → denoise → unsharp sharpen/structure → clarity → enhancement blocks →
 * keyframed blur → vignette → grain → clip transition → blend mode → opacity.
 */
@OptIn(UnstableApi::class)
class VibeCutMasterFinishingGlEffect(
    private val clip: TimelineClip,
    private val customPresets: List<ColorGradePreset> = emptyList(),
    private val liveState: LivePreviewStateHolder? = null,
    private val segmentOffsetMs: Long = 0L
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MasterShaderProgram(useHdr, clip, customPresets, liveState, segmentOffsetMs)

    private class MasterShaderProgram(
        useHdr: Boolean,
        private val initialClip: TimelineClip,
        private val initialPresets: List<ColorGradePreset>,
        private val liveState: LivePreviewStateHolder?,
        private val segmentOffsetMs: Long = 0L
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
                val presets = live?.customPresets ?: initialPresets
                val bypass = live?.isCompareBypass == true

                val offsetMs = if (live != null) {
                    live.localClipOffsetMs.coerceAtLeast(0L)
                } else {
                    if (firstPtsUs == C.TIME_UNSET || presentationTimeUs < firstPtsUs) firstPtsUs = presentationTimeUs
                    segmentOffsetMs + ((presentationTimeUs - firstPtsUs).coerceAtLeast(0L)) / 1000L
                }

                val state = KeyframeInterpolator.evaluateClipState(activeClip, offsetMs)
                val spatial = if (bypass) ColorGradeConfig() else
                    ColorGradingEngine.resolveEffectiveSpatialConfig(activeClip.colorGrade, presets)
                val enhancement = live?.enhancement ?: EnhancementConfig()

                var transition: ClipTransition? = null
                var progress = 1f
                if (!bypass) {
                    val transIn = activeClip.transitionIn
                    val clipDur = activeClip.timelineDurationMs
                    if (transIn != null && offsetMs < transIn.durationMs) {
                        transition = transIn
                        progress = (offsetMs.toFloat() / transIn.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    }
                }

                val chroma = activeClip.chromaKey
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
                glProgram.setFloatUniformSafe("uContrastBoost", if (enhancement.enabled) enhancement.contrastBoost else 0f)
                glProgram.setFloatUniformSafe("uColorBoost", if (enhancement.enabled) enhancement.colorBoost else 0f)
                glProgram.setFloatUniformSafe("uBrightnessFix", if (enhancement.enabled) enhancement.brightnessFix else 0f)
                glProgram.setFloatUniformSafe("uDetailPreserve", if (enhancement.enabled) enhancement.detailPreserve else 0f)
                glProgram.setFloatUniformSafe("uChromaEnabled", if (!bypass && chroma.isActive) 1f else 0f)
                glProgram.setFloatUniformSafe("uChromaHue", chroma.hueDeg.coerceIn(0f, 360f))
                glProgram.setFloatUniformSafe("uChromaTolerance", chroma.toleranceDeg.coerceIn(2f, 120f))
                glProgram.setFloatUniformSafe("uChromaSoftness", chroma.softness.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uChromaAmount", chroma.amount.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uChromaSpill", chroma.spillSuppression.coerceIn(0f, 1f))
                glProgram.setIntUniformSafe("uBlendMode", activeClip.transform.blendMode.glCode)
                glProgram.setIntUniformSafe("uTransitionType", GlslShaderGenerator.transitionCode(transition?.type))
                glProgram.setFloatUniformSafe("uTransitionProgress", progress)
                glProgram.setFloatUniformSafe("uTransitionSoftness", transition?.softness ?: 0.35f)
                glProgram.setFloatUniformSafe("uTransitionChromatic", transition?.chromaticAmount ?: 0.4f)
                glProgram.setFloatUniformSafe("uTransitionAngle", transition?.directionAngleDeg ?: 0f)
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
 * Per-frame 2D affine transform from keyframes + crop + rotation + flip + position.
 * Fixes the previous behaviour where clip rotation and flip were ignored in the preview.
 */
@OptIn(UnstableApi::class)
class VibeCutKeyframeMatrixTransformation(
    private val clip: TimelineClip,
    private val liveState: LivePreviewStateHolder? = null,
    private val segmentOffsetMs: Long = 0L
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
            if (firstPtsUs == C.TIME_UNSET || presentationTimeUs < firstPtsUs) firstPtsUs = presentationTimeUs
            segmentOffsetMs + ((presentationTimeUs - firstPtsUs).coerceAtLeast(0L)) / 1000L
        }

        val state = KeyframeInterpolator.evaluateClipState(activeClip, offsetMs)
        return Matrix().apply {
            val crop = activeClip.crop
            if (!crop.isIdentity) {
                val zoomX = (1f / crop.widthFraction).coerceIn(1f, 12f)
                val zoomY = (1f / crop.heightFraction).coerceIn(1f, 12f)
                val centerShiftX = (0.5f - (crop.left + crop.right) * 0.5f) * 2f * zoomX
                val centerShiftY = (((crop.top + crop.bottom) * 0.5f) - 0.5f) * 2f * zoomY
                postScale(zoomX, zoomY)
                postTranslate(centerShiftX, centerShiftY)
            }
            postScale(state.scaleX, state.scaleY)
            if (abs(state.rotationDegrees) > 0.01f) postRotate(state.rotationDegrees)
            if (abs(state.positionX) > 0.001f || abs(state.positionY) > 0.001f) {
                postTranslate(state.positionX, state.positionY)
            }
        }
    }
}

/**
 * GPU Lanczos-3 upscale used by the enhancement pipeline (preview + export). This is a genuine
 * resampling kernel, not a bilinear stretch — see [EnhancementPlan] for the honesty contract.
 */
@OptIn(UnstableApi::class)
class VibeCutLanczosUpscaleGlEffect(
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val sharpenAmount: Float = 0.35f
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LanczosShaderProgram(useHdr, targetWidth, targetHeight, sharpenAmount)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        inputWidth == targetWidth && inputHeight == targetHeight

    private class LanczosShaderProgram(
        useHdr: Boolean,
        private val targetWidth: Int,
        private val targetHeight: Int,
        private val sharpen: Float
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

        private val glProgram: GlProgram
        private var srcTexelW = 1f / 1080f
        private var srcTexelH = 1f / 1920f

        init {
            try {
                glProgram = GlProgram(
                    GlslShaderGenerator.STANDARD_VERTEX_SHADER_ES2,
                    GlslShaderGenerator.buildLanczosUpscaleFragmentShader()
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
            srcTexelW = 1f / max(1, inputWidth).toFloat()
            srcTexelH = 1f / max(1, inputHeight).toFloat()
            return Size(targetWidth, targetHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.setFloatsUniformIfPresent("uTexelSize", floatArrayOf(srcTexelW, srcTexelH))
                glProgram.setFloatsUniformIfPresent("uSourceTexelSize", floatArrayOf(srcTexelW, srcTexelH))
                glProgram.setFloatUniformSafe("uTimeSec", 0f)
                glProgram.setFloatUniformSafe("uIntensity", sharpen.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uSecondary", sharpen.coerceIn(0f, 1f))
                glProgram.setFloatUniformSafe("uTertiary", 0.5f)
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
 * Renders text, stickers, captions and PIP/overlay layers onto export frames at exact
 * clip-relative normalized timestamps.
 */
@OptIn(UnstableApi::class)
class VibeCutSceneOverlay(
    private val context: Context,
    private val aspectRatio: AspectRatioPreset,
    private val customRatio: Float,
    private val clipStartOnTimelineMs: Long,
    private val pipClips: List<TimelineClip>,
    private val textClips: List<TimelineClip>,
    private val stickerClips: List<TimelineClip>,
    private val subtitles: List<SubtitleCue>,
    canvasShortSide: Int = 1080,
    /** Offset of this export segment inside the clip (speed ramps split a clip into segments). */
    private val sceneSegmentOffsetMs: Long = 0L
) : BitmapOverlay() {

    private val dims = AspectRatioPreset.dimensionsForProject(aspectRatio, canvasShortSide, customRatio)
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
    /** True while [bitmap] holds a freshly drawn overlay that must not be reused for the same bucket. */
    private var bitmapValid = false
    private var firstPtsUs: Long = C.TIME_UNSET

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (firstPtsUs == C.TIME_UNSET || presentationTimeUs < firstPtsUs) firstPtsUs = presentationTimeUs
        val localClipMs = sceneSegmentOffsetMs + ((presentationTimeUs - firstPtsUs).coerceAtLeast(0L)) / 1000L
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
            bitmapValid = false
            return emptyBitmap
        }

        // Re-render at most once per ~33 ms bucket; the same bucket on the next frame reuses the
        // bitmap. The cache flag is what makes the reuse correct (the old `lastWasEmpty` gate could
        // hand back a stale bitmap after an empty stretch).
        val bucketMs = globalTimelineMs / 33L
        if (bitmapValid && bucketMs == lastRenderedBucketMs) return bitmap
        lastRenderedBucketMs = bucketMs
        lastWasEmpty = false
        bitmapValid = true

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        for (pip in activePips) {
            val bmp = loadPipBitmap(pip) ?: continue
            val localMs = (globalTimelineMs - pip.startTimeOnTrackMs).coerceAtLeast(0L)
            val kf = KeyframeInterpolator.evaluateClipState(pip, localMs)
            val cx = canvasWidth * (0.5f + kf.positionX * 0.45f)
            val cy = canvasHeight * (0.5f - kf.positionY * 0.45f)
            val drawW = canvasWidth * 0.55f * abs(kf.scaleX)
            val aspect = bmp.height.toFloat() / max(1, bmp.width).toFloat()
            val drawH = drawW * aspect
            pipPaint.alpha = (kf.opacity * 255f).roundToInt().coerceIn(0, 255)
            pipPaint.xfermode = null
            canvas.save()
            canvas.rotate(kf.rotationDegrees, cx, cy)
            canvas.drawBitmap(bmp, null, RectF(cx - drawW / 2f, cy - drawH / 2f, cx + drawW / 2f, cy + drawH / 2f), pipPaint)
            canvas.restore()
        }

        for (tc in activeTexts) {
            val spec = tc.textSpec ?: continue
            val localMs = (globalTimelineMs - tc.startTimeOnTrackMs).coerceAtLeast(0L)
            drawStyledText(spec, localMs, KeyframeInterpolator.evaluateClipState(tc, localMs))
        }

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

        val scaleFactor = min(canvasWidth, canvasHeight) / 1080f
        for (sc in activeStickers) {
            val sticker = sc.stickerSpec ?: continue
            val localMs = (globalTimelineMs - sc.startTimeOnTrackMs).coerceAtLeast(0L)
            val kf = KeyframeInterpolator.evaluateClipState(sc, localMs)
            fillPaint.shader = null
            fillPaint.clearShadowLayer()
            fillPaint.color = Color.WHITE
            fillPaint.alpha = (kf.opacity * 255).roundToInt().coerceIn(0, 255)
            fillPaint.textSize = 110f * scaleFactor * abs(kf.scaleX)
            fillPaint.textAlign = Paint.Align.CENTER
            val cx = canvasWidth * (0.5f + kf.positionX * 0.45f)
            val cy = canvasHeight * (0.5f - kf.positionY * 0.45f)
            canvas.save()
            canvas.rotate(kf.rotationDegrees, cx, cy)
            canvas.drawText(sticker.content, cx, cy, fillPaint)
            canvas.restore()
        }

        return bitmap
    }

    private fun loadPipBitmap(pip: TimelineClip): Bitmap? {
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
        if (spec.text.isEmpty()) return
        val animFrac = if (spec.animationIn == TextAnimationType.NONE || spec.animationDurationMs <= 0L) {
            1f
        } else {
            (localMs.toFloat() / spec.animationDurationMs).coerceIn(0f, 1f)
        }
        val visibleText = if (spec.animationIn == TextAnimationType.TYPEWRITER) {
            val count = (spec.text.length * animFrac).roundToInt().coerceIn(1, spec.text.length)
            spec.text.take(count)
        } else spec.text

        val animScale = if (spec.animationIn == TextAnimationType.POP_BOUNCE) {
            0.6f + 0.4f * KeyframeInterpolator.evaluateEasing(animFrac, EasingCurve.SNAP)
        } else 1f
        val animAlpha = if (spec.animationIn == TextAnimationType.NONE) 1f else animFrac

        val refShortSide = min(canvasWidth, canvasHeight).toFloat()
        val pxSize = spec.fontSizeSp * (refShortSide / 360f) * abs(kfState.scaleX) * animScale
        val typeface = when {
            spec.fontFamilyName.contains("Serif", true) && !spec.fontFamilyName.contains("Sans", true) ->
                Typeface.create(Typeface.SERIF, Typeface.BOLD)
            spec.fontFamilyName.contains("Mono", true) ->
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        fillPaint.typeface = typeface
        fillPaint.textSize = pxSize
        fillPaint.textAlign = Paint.Align.CENTER
        fillPaint.letterSpacing = spec.letterSpacingEm

        val cx = canvasWidth * (0.5f + kfState.positionX * 0.45f)
        val yLift = if (spec.animationIn == TextAnimationType.FADE_UP) (1f - animFrac) * 45f else 0f
        val cy = canvasHeight * (0.5f - kfState.positionY * 0.45f) + yLift
        val alphaInt = (kfState.opacity * animAlpha * 255f).roundToInt().coerceIn(0, 255)

        val textWidth = fillPaint.measureText(visibleText)
        if ((spec.backgroundColorArgb ushr 24) > 0) {
            bgPaint.color = spec.backgroundColorArgb.toInt()
            canvas.drawRoundRect(
                RectF(cx - textWidth / 2f - 28f, cy - pxSize, cx + textWidth / 2f + 28f, cy + 22f),
                18f, 18f, bgPaint
            )
        }

        canvas.save()
        canvas.rotate(kfState.rotationDegrees, cx, cy)

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

        fillPaint.shader = if (spec.useGradient) {
            LinearGradient(
                cx - textWidth / 2f, cy - pxSize, cx + textWidth / 2f, cy,
                spec.textColorArgb.toInt(), spec.gradientEndColorArgb.toInt(), Shader.TileMode.CLAMP
            )
        } else null
        fillPaint.color = spec.textColorArgb.toInt()
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
 * Media3 [SpeedProvider] for constant slow/fast motion and multi-point speed ramps.
 */
@OptIn(UnstableApi::class)
class VibeCutSpeedProvider(
    private val clip: TimelineClip,
    /** Constant speed override used by export segments (each segment has one fixed rate). */
    private val overrideSpeed: Float? = null
) : SpeedProvider {

    private val segmentsUs: LongArray
    private val speeds: FloatArray

    init {
        val durationUs = clip.timelineDurationMs.coerceAtLeast(1L) * 1000L
        val points = clip.activeSpeedPoints
        if (points.size < 2) {
            segmentsUs = longArrayOf(0L, durationUs)
            val constant = overrideSpeed?.coerceIn(SpeedCurvePreset.MIN_SPEED, SpeedCurvePreset.MAX_SPEED)
                ?: clip.clampedSpeed
            speeds = floatArrayOf(constant, constant)
        } else {
            val steps = 24
            val segs = ArrayList<Long>(steps + 1)
            val spds = ArrayList<Float>(steps + 1)
            var acc = 0L
            for (i in 0..steps) {
                segs.add((acc).coerceAtMost(durationUs))
                val u = i.toFloat() / steps
                spds.add(clip.instantaneousSpeedAt(u))
                if (i < steps) acc += durationUs / steps
            }
            segmentsUs = segs.toLongArray()
            speeds = spds.toFloatArray()
        }
    }

    override fun getSpeed(timeUs: Long): Float {
        if (speeds.isEmpty()) return 1f
        val idx = segmentsUs.indexOfLast { it <= timeUs }.coerceAtLeast(0)
        return speeds[idx.coerceIn(0, speeds.lastIndex)]
    }

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        if (segmentsUs.isEmpty()) return C.TIME_UNSET
        val idx = segmentsUs.indexOfLast { it <= timeUs }.coerceAtLeast(0)
        return if (idx + 1 < segmentsUs.size) segmentsUs[idx + 1] else C.TIME_UNSET
    }
}

/** Per-clip audio DSP chain: volume, fade in/out, noise gate, voice clarity, track gain. */
@OptIn(UnstableApi::class)
class VibeCutDspAudioProcessor(
    private val clip: TimelineClip,
    private val trackVolume: Float
) : BaseAudioProcessor() {

    private var sampleRate = 44_100
    private var channels = 2
    private var dspState: AudioDspEngine.PcmDspState? = null
    private var playedFrames = 0L
    private var totalFrames = 0L
    private var fadeInFrames = 0L
    private var fadeOutFrames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        dspState = AudioDspEngine.PcmDspState(sampleRate, channels)
        playedFrames = 0L
        totalFrames = (clip.trimmedSourceDurationMs * sampleRate / 1000L).coerceAtLeast(1L)
        fadeInFrames = clip.fadeInMs * sampleRate / 1000L
        fadeOutFrames = clip.fadeOutMs * sampleRate / 1000L
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining < 2) {
            replaceOutputBuffer(0).flip()
            return
        }
        val sampleCount = remaining / 2
        val shorts = ShortArray(sampleCount)
        inputBuffer.asShortBuffer().get(shorts)

        val frameCount = sampleCount / channels
        var gain = clip.volume.coerceIn(0f, 2f) * trackVolume.coerceIn(0f, 2f)
        if (fadeInFrames > 0L && playedFrames < fadeInFrames) {
            gain *= (playedFrames.toFloat() / fadeInFrames.toFloat()).coerceIn(0f, 1f)
        }
        if (fadeOutFrames > 0L && playedFrames + frameCount > totalFrames - fadeOutFrames) {
            val remainingFade = (totalFrames - playedFrames).coerceAtLeast(0L)
            gain *= (remainingFade.toFloat() / fadeOutFrames.toFloat()).coerceIn(0f, 1f)
        }

        dspState?.processInPlace(
            samples = shorts,
            frameCount = frameCount,
            baseGain = gain,
            noiseReduction = clip.noiseReduction,
            voiceEnhancement = clip.voiceEnhancement
        )
        playedFrames += frameCount

        val out = replaceOutputBuffer(sampleCount * 2)
        out.order(ByteOrder.nativeOrder()).asShortBuffer().put(shorts, 0, sampleCount)
        out.position(0).limit(sampleCount * 2)
    }

    override fun onFlush() {
        playedFrames = 0L
        dspState = AudioDspEngine.PcmDspState(sampleRate, channels)
    }

    override fun onReset() {
        playedFrames = 0L
        dspState = null
    }
}

/** Builds the GPU / DSP effect stacks for preview and export. */
object ClipEffectsFactory {

    /**
     * Live preview pipeline bound to [liveState]. Rebuilt only when the effect *topology* changes
     * (added/removed/reordered effects, clip change, proxy/upscale change); all slider values are
     * read live from the holder so dragging never tears down the VideoGraph.
     */
    fun buildLivePreviewVideoEffects(
        activeClip: TimelineClip,
        liveState: LivePreviewStateHolder,
        proxyHeight: Int?
    ): List<Effect> {
        val effects = mutableListOf<Effect>()
        effects.add(VibeCutKeyframeMatrixTransformation(activeClip, liveState))
        for (fx in activeClip.effects) {
            effects.add(VibeCutSingleEffectGlEffect(fx, activeClip.keyframes, liveState))
        }
        effects.add(VibeCutLiveColorLutGlEffect(liveState))
        effects.add(VibeCutMasterFinishingGlEffect(activeClip, liveState.customPresets, liveState))
        if (liveState.enhancement.enabled && liveState.enhancement.targetHeight > 0) {
            val (outW, outH) = AspectRatioPreset.dimensionsForProject(
                liveState.projectAspect ?: AspectRatioPreset.REELS_9_16,
                liveState.enhancement.targetHeight,
                liveState.projectCustomRatio
            )
            effects.add(VibeCutLanczosUpscaleGlEffect(outW, outH, liveState.enhancement.sharpen))
        }
        if (proxyHeight != null && proxyHeight > 0) {
            effects.add(Presentation.createForHeight(proxyHeight))
        }
        return effects
    }

    /** One effect stack entry per clip: crop, transform, effects, LUT, finishing, overlays, upscale. */
    fun buildVideoEffectsList(
        context: Context,
        clip: TimelineClip,
        project: Project,
        includeSceneOverlays: Boolean = false,
        targetExportHeight: Int? = null,
        useCodecSafeAlignment: Boolean = false,
        /** Offset of this export segment inside the clip, so keyframes/transitions stay continuous. */
        segmentOffsetMs: Long = 0L
    ): List<Effect> {
        val videoEffects = mutableListOf<Effect>()

        if (!clip.crop.isIdentity) {
            val ndcLeft = (clip.crop.left * 2f - 1f).coerceIn(-1f, 0.95f)
            val ndcRight = (clip.crop.right * 2f - 1f).coerceIn(ndcLeft + 0.05f, 1f)
            val ndcTop = (1f - clip.crop.top * 2f).coerceIn(-0.95f, 1f)
            val ndcBottom = (1f - clip.crop.bottom * 2f).coerceIn(-1f, ndcTop - 0.05f)
            videoEffects.add(Crop(ndcLeft, ndcRight, ndcBottom, ndcTop))
        }

        if (!clip.transform.isIdentity || clip.keyframes.isNotEmpty()) {
            videoEffects.add(VibeCutKeyframeMatrixTransformation(clip, segmentOffsetMs = segmentOffsetMs))
        }

        for (fx in clip.effects) {
            if (fx.enabled && fx.intensity > 0.002f) {
                videoEffects.add(VibeCutSingleEffectGlEffect(fx, clip.keyframes, segmentOffsetMs = segmentOffsetMs))
            }
        }

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
            val baked = ColorGradingEngine.bakeTo3dCube(
                cfg = clip.colorGrade,
                size = 17,
                mixIntensity = 1f,
                customPresets = project.customColorPresets
            )
            videoEffects.add(SingleColorLut.createFromCube(baked.argbCube))
        }

        val spatial = ColorGradingEngine.resolveEffectiveSpatialConfig(clip.colorGrade, project.customColorPresets)
        val needsFinishing = spatial.hasSpatialDetailChanges ||
            clip.transitionIn != null ||
            clip.transform.opacity < 0.999f ||
            clip.transform.rotationDegrees % 360f != 0f ||
            clip.transform.flipHorizontal ||
            clip.transform.flipVertical ||
            clip.transform.blendMode != BlendMode.NORMAL ||
            clip.chromaKey.isActive ||
            project.enhancement.enabled ||
            clip.keyframes.any { it.property == KeyframeProperty.BLUR || it.property == KeyframeProperty.OPACITY }
        if (needsFinishing) {
            videoEffects.add(VibeCutMasterFinishingGlEffect(clip, project.customColorPresets, segmentOffsetMs = segmentOffsetMs))
        }

        if (includeSceneOverlays) {
            val pipClips = project.tracks
                .filter { it.type == TrackType.VIDEO_PIP && !it.isHidden && !it.isMuted }
                .flatMap { it.clips }
            val textClips = project.tracks
                .filter { it.type == TrackType.TEXT && !it.isHidden }
                .flatMap { it.clips }
            val stickerClips = project.tracks
                .filter { it.type == TrackType.STICKER && !it.isHidden }
                .flatMap { it.clips }
            if (pipClips.isNotEmpty() || textClips.isNotEmpty() || stickerClips.isNotEmpty() || project.subtitles.isNotEmpty()) {
                videoEffects.add(
                    OverlayEffect(
                        listOf(
                            VibeCutSceneOverlay(
                                context = context,
                                aspectRatio = project.aspectRatio,
                                customRatio = project.customAspectRatio,
                                clipStartOnTimelineMs = clip.startTimeOnTrackMs,
                                pipClips = pipClips,
                                textClips = textClips,
                                stickerClips = stickerClips,
                                subtitles = project.subtitles,
                                canvasShortSide = 1080
                            )
                        )
                    )
                )
            }
        }

        val desiredHeight = targetExportHeight
        if (desiredHeight != null && desiredHeight > 0) {
            val (outW, outH) = if (useCodecSafeAlignment) {
                AspectRatioPreset.dimensionsForProject(project.aspectRatio, desiredHeight, project.customAspectRatio)
            } else {
                project.aspectRatio.computeDimensionsFor(desiredHeight, project.customAspectRatio)
            }
            if (project.enhancement.enabled) {
                videoEffects.add(VibeCutLanczosUpscaleGlEffect(outW, outH, project.enhancement.sharpen))
            } else {
                videoEffects.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
            }
        }

        return videoEffects
    }

    fun buildFullClipEffects(
        context: Context,
        clip: TimelineClip,
        project: Project,
        trackVolume: Float = 1f,
        includeAudioProcessor: Boolean = true,
        includeSceneOverlays: Boolean = false,
        targetExportHeight: Int? = null,
        useCodecSafeAlignment: Boolean = true,
        /** Constant playback speed of this export segment (null = derive from the clip). */
        segmentSpeed: Float? = null,
        /** False for pre-segmented ramps, where speed is already baked into the clipping range. */
        applySpeedProvider: Boolean = true,
        segmentTimelineOffsetMs: Long = 0L
    ): Effects {
        val audioProcessors = mutableListOf<AudioProcessor>()
        val videoEffects = buildVideoEffectsList(
            context = context,
            clip = clip,
            project = project,
            includeSceneOverlays = includeSceneOverlays,
            targetExportHeight = targetExportHeight,
            useCodecSafeAlignment = useCodecSafeAlignment,
            segmentOffsetMs = segmentTimelineOffsetMs
        ).toMutableList()

        // Speed ramps are handled by resampling the item timeline (see VibeCutExportEngine);
        // constant speed is handled by Media3's speed-changing effect.
        val hasRamp = clip.hasSpeedRamp
        val speedForItem = segmentSpeed ?: clip.clampedSpeed
        // Pre-segmented ramps (applySpeedProvider == false) already encode speed in the clipping
        // range, so no provider may be attached or the segment would be slowed twice.
        if (applySpeedProvider && !hasRamp && abs(speedForItem - 1f) > 0.01f &&
            !clip.isFrozenFrame && clip.mediaKind == MediaKind.VIDEO
        ) {
            val pair = Effects.createExperimentalSpeedChangingEffect(VibeCutSpeedProvider(clip, speedForItem))
            if (includeAudioProcessor) audioProcessors.add(pair.first)
            videoEffects.add(0, pair.second)
        }

        if (includeAudioProcessor) {
            audioProcessors.add(VibeCutDspAudioProcessor(clip, trackVolume))
        }
        return Effects(audioProcessors, videoEffects)
    }
}

/** Trilinear sampling of a 3D cube LUT (used to fold custom .cube files into the live LUT). */
private fun sampleCubeTrilinear(cube: CubeLut3D, r: Float, g: Float, b: Float, mix: Float): FloatArray {
    val n = cube.size.coerceAtLeast(2)
    val fr = (r.coerceIn(0f, 1f) * (n - 1))
    val fg = (g.coerceIn(0f, 1f) * (n - 1))
    val fb = (b.coerceIn(0f, 1f) * (n - 1))
    val r0 = fr.toInt().coerceIn(0, n - 2)
    val g0 = fg.toInt().coerceIn(0, n - 2)
    val b0 = fb.toInt().coerceIn(0, n - 2)
    val dr = fr - r0
    val dg = fg - g0
    val db = fb - b0

    fun ch(argb: Int, shift: Int): Float = ((argb shr shift) and 0xFF) / 255f
    fun corner(ri: Int, gi: Int, bi: Int, shift: Int): Float =
        ch(cube.argbCube[ri][gi][bi], shift)

    fun interp(shift: Int): Float {
        val c000 = corner(r0, g0, b0, shift)
        val c100 = corner(r0 + 1, g0, b0, shift)
        val c010 = corner(r0, g0 + 1, b0, shift)
        val c110 = corner(r0 + 1, g0 + 1, b0, shift)
        val c001 = corner(r0, g0, b0 + 1, shift)
        val c101 = corner(r0 + 1, g0, b0 + 1, shift)
        val c011 = corner(r0, g0 + 1, b0 + 1, shift)
        val c111 = corner(r0 + 1, g0 + 1, b0 + 1, shift)
        val c00 = c000 + (c100 - c000) * dr
        val c10 = c010 + (c110 - c010) * dr
        val c01 = c001 + (c101 - c001) * dr
        val c11 = c011 + (c111 - c011) * dr
        val c0 = c00 + (c10 - c00) * dg
        val c1 = c01 + (c11 - c01) * dg
        return c0 + (c1 - c0) * db
    }

    val m = mix.coerceIn(0f, 1f)
    return floatArrayOf(
        r + (interp(16) - r) * m,
        g + (interp(8) - g) * m,
        b + (interp(0) - b) * m
    )
}

@OptIn(UnstableApi::class)
internal fun GlProgram.setFloatUniformSafe(name: String, value: Float) {
    runCatching { setFloatUniform(name, value) }
}

@OptIn(UnstableApi::class)
internal fun GlProgram.setIntUniformSafe(name: String, value: Int) {
    runCatching { setIntUniform(name, value) }
}

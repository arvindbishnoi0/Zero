package com.vibecut.pro.engine.keyframes

import com.vibecut.pro.engine.model.*
import kotlin.math.abs

/**
 * Fully resolved visual/audio state of one clip at one instant.
 *
 * Produced by [KeyframeInterpolator.evaluateClipState] and consumed by the GPU effects
 * (matrix transform, finishing shader, LUT mix) and by the export audio pipeline, so preview and
 * export always animate identically.
 */
data class InterpolatedClipState(
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
    val volume: Float = 1f,
    /** Extra blur requested by BLUR keyframes, 0..1, added on top of the effect stack. */
    val extraBlur: Float = 0f,
    /** Multiplier applied to every effect intensity, 0..1. */
    val effectIntensityMultiplier: Float = 1f,
    /** Mix between graded and ungraded image, 0..1 (COLOR_INTENSITY keyframes). */
    val colorIntensityMix: Float = 1f,
    /** 0..1 progress used to drive text animation keyframes. */
    val textProgress: Float = 1f
)

object KeyframeInterpolator {

    /**
     * Resolves the complete clip state at [clipOffsetMs] (milliseconds from the clip's own start on
     * the timeline, already mapped through speed by the caller).
     */
    fun evaluateClipState(clip: TimelineClip, clipOffsetMs: Long): InterpolatedClipState {
        val base = clip.transform
        val flipSignX = if (base.flipHorizontal) -1f else 1f
        val flipSignY = if (base.flipVertical) -1f else 1f

        if (clip.keyframes.isEmpty()) {
            return InterpolatedClipState(
                positionX = base.positionX,
                positionY = base.positionY,
                scaleX = (base.scaleX * flipSignX).coerceIn(-8f, 8f),
                scaleY = (base.scaleY * flipSignY).coerceIn(-8f, 8f),
                rotationDegrees = base.rotationDegrees,
                opacity = base.opacity.coerceIn(0f, 1f),
                volume = if (clip.isMuted) 0f else clip.volume.coerceIn(0f, 2f),
                extraBlur = 0f,
                effectIntensityMultiplier = 1f,
                colorIntensityMix = 1f,
                textProgress = 1f
            )
        }

        val kfs = clip.keyframes
        val posX = evaluateProperty(kfs, KeyframeProperty.POSITION_X, clipOffsetMs, base.positionX)
        val posY = evaluateProperty(kfs, KeyframeProperty.POSITION_Y, clipOffsetMs, base.positionY)
        val scale = evaluateProperty(kfs, KeyframeProperty.SCALE, clipOffsetMs, 1f)
        val rot = evaluateProperty(kfs, KeyframeProperty.ROTATION, clipOffsetMs, base.rotationDegrees)
        val opacity = evaluateProperty(kfs, KeyframeProperty.OPACITY, clipOffsetMs, base.opacity)
        val volume = if (clip.isMuted) 0f
        else evaluateProperty(kfs, KeyframeProperty.VOLUME, clipOffsetMs, clip.volume)
        val blur = evaluateProperty(kfs, KeyframeProperty.BLUR, clipOffsetMs, 0f)
        val fxMix = evaluateProperty(kfs, KeyframeProperty.EFFECT_INTENSITY, clipOffsetMs, 1f)
        val colorMix = evaluateProperty(kfs, KeyframeProperty.COLOR_INTENSITY, clipOffsetMs, 1f)
        val textProgress = evaluateProperty(kfs, KeyframeProperty.TEXT_PROGRESS, clipOffsetMs, 1f)

        return InterpolatedClipState(
            positionX = posX.coerceIn(-2f, 2f),
            positionY = posY.coerceIn(-2f, 2f),
            scaleX = (base.scaleX * scale * flipSignX).coerceIn(-8f, 8f),
            scaleY = (base.scaleY * scale * flipSignY).coerceIn(-8f, 8f),
            rotationDegrees = ((rot % 360f) + 540f) % 360f - 180f,
            opacity = opacity.coerceIn(0f, 1f),
            volume = volume.coerceIn(0f, 2f),
            extraBlur = blur.coerceIn(0f, 1f),
            effectIntensityMultiplier = fxMix.coerceIn(0f, 1f),
            colorIntensityMix = colorMix.coerceIn(0f, 1f),
            textProgress = textProgress.coerceIn(0f, 1f)
        )
    }

    /**
     * Piecewise interpolation of one animated property. Returns [fallbackValue] when the property
     * has no keyframes; clamps outside the keyframe range; eases between neighbours.
     */
    fun evaluateProperty(
        allKeyframes: List<Keyframe>,
        property: KeyframeProperty,
        timeOffsetMs: Long,
        fallbackValue: Float
    ): Float {
        val kfs = allKeyframes.filter { it.property == property }.sortedBy { it.timeOffsetMs }
        if (kfs.isEmpty()) return fallbackValue
        if (kfs.size == 1) return kfs.first().value
        if (timeOffsetMs <= kfs.first().timeOffsetMs) return kfs.first().value
        if (timeOffsetMs >= kfs.last().timeOffsetMs) return kfs.last().value

        for (i in 0 until kfs.lastIndex) {
            val start = kfs[i]
            val end = kfs[i + 1]
            if (timeOffsetMs in start.timeOffsetMs..end.timeOffsetMs) {
                val span = (end.timeOffsetMs - start.timeOffsetMs).coerceAtLeast(1L).toFloat()
                val rawT = ((timeOffsetMs - start.timeOffsetMs) / span).coerceIn(0f, 1f)
                val eased = evaluateEasing(rawT, start.easing)
                return start.value + (end.value - start.value) * eased
            }
        }
        return kfs.last().value
    }

    fun evaluateEasing(t: Float, easing: EasingCurve): Float {
        val x = t.coerceIn(0f, 1f)
        if (easing == EasingCurve.LINEAR) return x
        return evaluateCubicBezier(x, easing.c1x, easing.c1y, easing.c2x, easing.c2y)
    }

    /** Solves x(s) = xTarget (Newton-Raphson with bisection fallback) then returns y(s). */
    fun evaluateCubicBezier(xTarget: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        if (xTarget <= 0f) return 0f
        if (xTarget >= 1f) return 1f
        val cx1 = x1.coerceIn(0f, 1f)
        val cx2 = x2.coerceIn(0f, 1f)

        var s = xTarget
        var iter = 0
        while (iter < 8) {
            iter++
            val err = sampleBezierCoord(s, cx1, cx2) - xTarget
            if (abs(err) < 1e-6f) break
            val dx = sampleBezierDerivative(s, cx1, cx2)
            if (abs(dx) < 1e-6f) break
            s = (s - err / dx).coerceIn(0f, 1f)
        }

        if (abs(sampleBezierCoord(s, cx1, cx2) - xTarget) > 1e-4f) {
            var lo = 0f
            var hi = 1f
            s = xTarget
            var b = 0
            while (b < 20) {
                b++
                val mid = sampleBezierCoord(s, cx1, cx2)
                if (abs(mid - xTarget) < 1e-6f) break
                if (mid < xTarget) lo = s else hi = s
                s = (lo + hi) * 0.5f
            }
        }
        return sampleBezierCoord(s, y1, y2).coerceIn(-0.5f, 1.5f)
    }

    /** Convenience for the shader path: only the animated blur/opacity that the finishing pass needs. */
    fun blurAndOpacityAt(clip: TimelineClip, clipOffsetMs: Long): Pair<Float, Float> {
        val state = evaluateClipState(clip, clipOffsetMs)
        return state.extraBlur to state.opacity
    }

    private fun sampleBezierCoord(s: Float, p1: Float, p2: Float): Float {
        val inv = 1f - s
        return 3f * inv * inv * s * p1 + 3f * inv * s * s * p2 + s * s * s
    }

    private fun sampleBezierDerivative(s: Float, p1: Float, p2: Float): Float {
        val inv = 1f - s
        return 3f * inv * inv * p1 + 6f * inv * s * (p2 - p1) + 3f * s * s * (1f - p2)
    }
}

package com.vibecut.pro.engine.keyframes

import com.vibecut.pro.engine.model.*
import kotlin.math.abs

data class InterpolatedClipState(
    val positionX: Float,
    val positionY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotationDegrees: Float,
    val opacity: Float,
    val volume: Float,
    val extraBlur: Float,
    val effectIntensityMultiplier: Float,
    val colorIntensityMix: Float,
    val textProgress: Float
)

object KeyframeInterpolator {

    fun evaluateClipState(clip: TimelineClip, clipOffsetMs: Long): InterpolatedClipState {
        val base = clip.transform
        if (clip.keyframes.isEmpty()) {
            return InterpolatedClipState(
                positionX = base.positionX,
                positionY = base.positionY,
                scaleX = base.scaleX * (if (base.flipHorizontal) -1f else 1f),
                scaleY = base.scaleY * (if (base.flipVertical) -1f else 1f),
                rotationDegrees = base.rotationDegrees,
                opacity = base.opacity,
                volume = if (clip.isMuted) 0f else clip.volume,
                extraBlur = 0f,
                effectIntensityMultiplier = 1f,
                colorIntensityMix = 1f,
                textProgress = 1f
            )
        }

        val posX = evaluateProperty(clip.keyframes, KeyframeProperty.POSITION_X, clipOffsetMs, base.positionX)
        val posY = evaluateProperty(clip.keyframes, KeyframeProperty.POSITION_Y, clipOffsetMs, base.positionY)
        val uniformScale = evaluateProperty(clip.keyframes, KeyframeProperty.SCALE, clipOffsetMs, 1f)
        val rot = evaluateProperty(clip.keyframes, KeyframeProperty.ROTATION, clipOffsetMs, base.rotationDegrees)
        val opacity = evaluateProperty(clip.keyframes, KeyframeProperty.OPACITY, clipOffsetMs, base.opacity)
        val vol = if (clip.isMuted) 0f else evaluateProperty(clip.keyframes, KeyframeProperty.VOLUME, clipOffsetMs, clip.volume)
        val blur = evaluateProperty(clip.keyframes, KeyframeProperty.BLUR, clipOffsetMs, 0f)
        val fxMult = evaluateProperty(clip.keyframes, KeyframeProperty.EFFECT_INTENSITY, clipOffsetMs, 1f)
        val colorMix = evaluateProperty(clip.keyframes, KeyframeProperty.COLOR_INTENSITY, clipOffsetMs, 1f)
        val textProg = evaluateProperty(clip.keyframes, KeyframeProperty.TEXT_PROGRESS, clipOffsetMs, 1f)

        val flipSignX = if (base.flipHorizontal) -1f else 1f
        val flipSignY = if (base.flipVertical) -1f else 1f

        return InterpolatedClipState(
            positionX = posX.coerceIn(-1.5f, 1.5f),
            positionY = posY.coerceIn(-1.5f, 1.5f),
            scaleX = (base.scaleX * uniformScale).coerceIn(0.05f, 8f) * flipSignX,
            scaleY = (base.scaleY * uniformScale).coerceIn(0.05f, 8f) * flipSignY,
            rotationDegrees = rot,
            opacity = opacity.coerceIn(0f, 1f),
            volume = vol.coerceIn(0f, 2f),
            extraBlur = blur.coerceIn(0f, 1f),
            effectIntensityMultiplier = fxMult.coerceIn(0f, 2f),
            colorIntensityMix = colorMix.coerceIn(0f, 1f),
            textProgress = textProg.coerceIn(0f, 1f)
        )
    }

    fun evaluateProperty(
        allKeyframes: List<Keyframe>,
        property: KeyframeProperty,
        timeOffsetMs: Long,
        fallbackValue: Float
    ): Float {
        val kfs = allKeyframes.filter { it.property == property }.sortedBy { it.timeOffsetMs }
        if (kfs.isEmpty()) return fallbackValue
        if (kfs.size == 1 || timeOffsetMs <= kfs.first().timeOffsetMs) return kfs.first().value
        if (timeOffsetMs >= kfs.last().timeOffsetMs) return kfs.last().value

        for (i in 0 until kfs.lastIndex) {
            val start = kfs[i]
            val end = kfs[i + 1]
            if (timeOffsetMs in start.timeOffsetMs..end.timeOffsetMs) {
                val span = (end.timeOffsetMs - start.timeOffsetMs).coerceAtLeast(1L).toFloat()
                val rawT = ((timeOffsetMs - start.timeOffsetMs) / span).coerceIn(0f, 1f)
                val easedT = evaluateEasing(
                    t = rawT,
                    easing = start.easing,
                    c1x = start.customC1x,
                    c1y = start.customC1y,
                    c2x = start.customC2x,
                    c2y = start.customC2y
                )
                return start.value + (end.value - start.value) * easedT
            }
        }
        return kfs.last().value
    }

    fun evaluateEasing(
        t: Float,
        easing: EasingCurve,
        c1x: Float = easing.c1x,
        c1y: Float = easing.c1y,
        c2x: Float = easing.c2x,
        c2y: Float = easing.c2y
    ): Float {
        val x = t.coerceIn(0f, 1f)
        if (easing == EasingCurve.LINEAR) return x
        val px1 = if (easing == EasingCurve.BEZIER_CUSTOM) c1x.coerceIn(0f, 1f) else easing.c1x
        val py1 = if (easing == EasingCurve.BEZIER_CUSTOM) c1y else easing.c1y
        val px2 = if (easing == EasingCurve.BEZIER_CUSTOM) c2x.coerceIn(0f, 1f) else easing.c2x
        val py2 = if (easing == EasingCurve.BEZIER_CUSTOM) c2y else easing.c2y
        return evaluateCubicBezier(x, px1, py1, px2, py2)
    }

    /**
     * Solves x(s) = xTarget via Newton-Raphson + bisection fallback, then evaluates y(s).
     */
    fun evaluateCubicBezier(xTarget: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        if (xTarget <= 0f) return 0f
        if (xTarget >= 1f) return 1f

        var s = xTarget
        for (iter in 0 until 8) {
            val xEst = sampleBezierCoord(s, x1, x2) - xTarget
            if (abs(xEst) < 1e-5f) break
            val dx = sampleBezierDerivative(s, x1, x2)
            if (abs(dx) < 1e-5f) break
            s = (s - xEst / dx).coerceIn(0f, 1f)
        }

        // Bisection refinement if needed
        if (abs(sampleBezierCoord(s, x1, x2) - xTarget) > 1e-4f) {
            var lo = 0f
            var hi = 1f
            s = xTarget
            for (i in 0 until 12) {
                val xMid = sampleBezierCoord(s, x1, x2)
                if (abs(xMid - xTarget) < 1e-5f) break
                if (xTarget > xMid) lo = s else hi = s
                s = (lo + hi) * 0.5f
            }
        }
        return sampleBezierCoord(s, y1, y2)
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

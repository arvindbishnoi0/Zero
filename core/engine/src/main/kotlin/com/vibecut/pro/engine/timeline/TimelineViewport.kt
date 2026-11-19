package com.vibecut.pro.engine.timeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Pure (Android-free) maths for the timeline viewport.
 *
 * Every pixel value here is a **raw device pixel**, never a dp. Mixing the two is what made the
 * old timeline draw clips at 1/3 of their real width on a 3x-density phone: the ruler ticks and
 * the pointer handler worked in px while the content width was declared in dp.
 *
 * Keeping the conversions in one pure object means the "where did the user tap" contract is unit
 * tested on the JVM instead of being discovered on a device.
 */
object TimelineViewportMath {

    const val MIN_PX_PER_SEC = 8f
    const val MAX_PX_PER_SEC = 640f

    /** Trailing runway after the last clip so the user can drag past the end. */
    const val TRAILING_SECONDS = 2f

    /** Minimum content width in seconds, so an empty timeline is still scrubbable. */
    const val MIN_CONTENT_SECONDS = 8f

    fun clampZoom(pxPerSecond: Float): Float =
        if (pxPerSecond.isFinite()) pxPerSecond.coerceIn(MIN_PX_PER_SEC, MAX_PX_PER_SEC)
        else MIN_PX_PER_SEC

    fun msToPx(ms: Long, pxPerSecond: Float): Float = (ms / 1000f) * clampZoom(pxPerSecond)

    fun pxToMs(px: Float, pxPerSecond: Float): Long =
        if (!px.isFinite()) 0L
        else ((px / clampZoom(pxPerSecond)) * 1000f).roundToLong().coerceAtLeast(0L)

    /** Signed millisecond delta for a pixel delta — used by drag/trim, so it must allow negatives. */
    fun pxDeltaToMs(deltaPx: Float, pxPerSecond: Float): Long =
        if (!deltaPx.isFinite()) 0L else ((deltaPx / clampZoom(pxPerSecond)) * 1000f).roundToLong()

    /** Total scrollable content width in raw pixels. */
    fun contentWidthPx(durationMs: Long, pxPerSecond: Float): Float {
        val seconds = (durationMs / 1000f).coerceAtLeast(MIN_CONTENT_SECONDS) + TRAILING_SECONDS
        return seconds * clampZoom(pxPerSecond)
    }

    /** Largest legal horizontal scroll offset for the given content and viewport. */
    fun maxScrollPx(durationMs: Long, pxPerSecond: Float, viewportWidthPx: Float): Float =
        max(0f, contentWidthPx(durationMs, pxPerSecond) - max(1f, viewportWidthPx))

    /**
     * Zoom around a focus point so the frame under the user's fingers stays put.
     * Returns the new `(pxPerSecond, scrollPx)` pair.
     */
    fun pinchZoom(
        pxPerSecond: Float,
        scrollPx: Float,
        centroidX: Float,
        zoomFactor: Float,
        durationMs: Long,
        viewportWidthPx: Float
    ): Pair<Float, Float> {
        val safeFactor = if (zoomFactor.isFinite() && zoomFactor > 0f) zoomFactor else 1f
        val focusMs = pxToMs(scrollPx + centroidX, pxPerSecond)
        val next = clampZoom(pxPerSecond * safeFactor)
        val nextScroll = (msToPx(focusMs, next) - centroidX)
            .coerceIn(0f, maxScrollPx(durationMs, next, viewportWidthPx))
        return next to nextScroll
    }

    /** Zoom level that makes the whole project fit in [viewportWidthPx]. */
    fun fitZoom(durationMs: Long, viewportWidthPx: Float): Float {
        val seconds = (durationMs / 1000f).coerceAtLeast(1f) + TRAILING_SECONDS
        return clampZoom(max(1f, viewportWidthPx) / seconds)
    }

    /**
     * Scroll offset that keeps the playhead visible while playing, using a dead-zone so the
     * timeline does not jitter on every frame. Returns null when no scroll is needed.
     */
    fun followPlayheadScroll(
        playheadMs: Long,
        pxPerSecond: Float,
        scrollPx: Float,
        viewportWidthPx: Float,
        durationMs: Long
    ): Float? {
        if (viewportWidthPx <= 1f) return null
        val x = msToPx(playheadMs, pxPerSecond)
        val leftEdge = scrollPx + viewportWidthPx * 0.12f
        val rightEdge = scrollPx + viewportWidthPx * 0.78f
        if (x in leftEdge..rightEdge) return null
        val target = (x - viewportWidthPx * 0.35f)
            .coerceIn(0f, maxScrollPx(durationMs, pxPerSecond, viewportWidthPx))
        return if (abs(target - scrollPx) < 1f) null else target
    }

    /** Ruler tick spacing in seconds for the current zoom. */
    fun tickStepSeconds(pxPerSecond: Float): Float = when {
        pxPerSecond >= 320f -> 0.1f
        pxPerSecond >= 180f -> 0.25f
        pxPerSecond >= 96f -> 0.5f
        pxPerSecond >= 48f -> 1f
        pxPerSecond >= 24f -> 2f
        pxPerSecond >= 12f -> 5f
        else -> 10f
    }

    /** How many minor ticks per labelled major tick. */
    fun majorTickEvery(stepSeconds: Float): Int = when {
        stepSeconds <= 0.1f -> 10
        stepSeconds <= 0.25f -> 4
        stepSeconds <= 0.5f -> 2
        stepSeconds <= 1f -> 5
        else -> 2
    }

    /** What a horizontal drag on a clip is doing. */
    enum class DragMode { NONE, MOVE, TRIM_START, TRIM_END }

    /**
     * Classifies a touch inside a clip: the outer [handlePx] of a **selected** clip trims, the
     * middle moves. Narrow clips never give more than 35% of their width to a handle so there is
     * always something left to grab for moving.
     */
    fun classifyTouch(
        localXPx: Float,
        clipWidthPx: Float,
        handlePx: Float,
        selected: Boolean
    ): DragMode {
        if (!selected) return DragMode.MOVE
        val handle = min(handlePx, clipWidthPx * 0.35f)
        return when {
            localXPx <= handle -> DragMode.TRIM_START
            localXPx >= clipWidthPx - handle -> DragMode.TRIM_END
            else -> DragMode.MOVE
        }
    }

    /**
     * Live preview geometry for a drag in progress, in milliseconds.
     * Returns `(startMs, durationMs)` so the clip block can render the change *while* dragging
     * instead of snapping only on release.
     */
    fun previewGeometry(
        mode: DragMode,
        originalStartMs: Long,
        originalDurationMs: Long,
        deltaMs: Long,
        minDurationMs: Long,
        maxExtendStartMs: Long,
        maxExtendEndMs: Long
    ): Pair<Long, Long> = when (mode) {
        DragMode.MOVE -> max(0L, originalStartMs + deltaMs) to originalDurationMs
        DragMode.TRIM_START -> {
            val d = deltaMs.coerceIn(-maxExtendStartMs, originalDurationMs - minDurationMs)
            max(0L, originalStartMs + d) to (originalDurationMs - d).coerceAtLeast(minDurationMs)
        }
        DragMode.TRIM_END -> {
            val d = deltaMs.coerceIn(minDurationMs - originalDurationMs, maxExtendEndMs)
            originalStartMs to (originalDurationMs + d).coerceAtLeast(minDurationMs)
        }
        DragMode.NONE -> originalStartMs to originalDurationMs
    }
}

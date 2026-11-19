package com.vibecut.pro.ui.editor.timeline

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vibecut.pro.engine.timeline.TimelineViewportMath

/**
 * Shared, hoisted timeline viewport state.
 *
 * The timeline is the most gesture-sensitive surface in the editor, so all viewport maths lives in
 * one place — [TimelineViewportMath] in `:core:engine`, which is pure Kotlin and unit tested. This
 * class is only the Compose-observable shell around it.
 *
 * **Units contract:** every value is a *raw device pixel*. Callers that need dp must convert with
 * the current [androidx.compose.ui.unit.Density]. The previous implementation returned the content
 * width in dp while the ruler, the hit-testing and the playhead all worked in px, so on a 3x phone
 * every clip rendered a third of its real width and tap-to-seek landed in the wrong place.
 */
@Stable
class TimelineViewportState(
    initialPxPerSecond: Float = 64f
) {
    var pxPerSecond by mutableFloatStateOf(TimelineViewportMath.clampZoom(initialPxPerSecond))
        private set

    var scrollPx by mutableFloatStateOf(0f)
        private set

    /** Width of the visible lane area in px; measured by the timeline, never assumed. */
    var viewportWidthPx by mutableFloatStateOf(0f)
        private set

    /** Playhead position while the user is dragging it (null = follow playback). */
    var scrubbingMs by mutableStateOf<Long?>(null)

    val isScrubbing: Boolean get() = scrubbingMs != null

    fun setViewportWidth(px: Float) {
        if (px.isFinite() && px > 0f) viewportWidthPx = px
    }

    fun setZoom(pxPerSecondNew: Float) {
        pxPerSecond = TimelineViewportMath.clampZoom(pxPerSecondNew)
    }

    fun zoomBy(factor: Float) = setZoom(pxPerSecond * factor)

    fun setScroll(px: Float, maxScroll: Float) {
        scrollPx = px.coerceIn(0f, maxScroll.coerceAtLeast(0f))
    }

    fun scrollBy(deltaPx: Float, maxScroll: Float) = setScroll(scrollPx + deltaPx, maxScroll)

    fun pxToMs(px: Float): Long = TimelineViewportMath.pxToMs(px, pxPerSecond)

    fun msToPx(ms: Long): Float = TimelineViewportMath.msToPx(ms, pxPerSecond)

    /** Signed conversion for drag deltas (keeps the sign, unlike [pxToMs]). */
    fun pxDeltaToMs(deltaPx: Float): Long = TimelineViewportMath.pxDeltaToMs(deltaPx, pxPerSecond)

    /** X position of a timeline timestamp inside the scrolling content, in px. */
    fun contentXForMs(ms: Long): Float = msToPx(ms)

    /** Content width needed for [durationMs] plus trailing runway, in **raw pixels**. */
    fun contentWidthPx(durationMs: Long): Float =
        TimelineViewportMath.contentWidthPx(durationMs, pxPerSecond)

    fun maxScrollPx(durationMs: Long): Float =
        TimelineViewportMath.maxScrollPx(durationMs, pxPerSecond, viewportWidthPx)

    /** Pinch-zoom around a focus point; returns the scroll offset the caller should apply. */
    fun pinchZoom(centroidX: Float, zoomFactor: Float, durationMs: Long): Float {
        val (nextZoom, nextScroll) = TimelineViewportMath.pinchZoom(
            pxPerSecond = pxPerSecond,
            scrollPx = scrollPx,
            centroidX = centroidX,
            zoomFactor = zoomFactor,
            durationMs = durationMs,
            viewportWidthPx = viewportWidthPx
        )
        pxPerSecond = nextZoom
        return nextScroll
    }

    /** Zoom so the whole project fits the measured viewport. */
    fun fitToWidth(durationMs: Long) {
        setZoom(TimelineViewportMath.fitZoom(durationMs, viewportWidthPx))
    }

    /** Target scroll that keeps the playhead on screen, or null when it already is. */
    fun followPlayheadScroll(playheadMs: Long, durationMs: Long): Float? =
        TimelineViewportMath.followPlayheadScroll(
            playheadMs, pxPerSecond, scrollPx, viewportWidthPx, durationMs
        )

    companion object {
        const val MIN_PX_PER_SEC = TimelineViewportMath.MIN_PX_PER_SEC
        const val MAX_PX_PER_SEC = TimelineViewportMath.MAX_PX_PER_SEC
    }
}

package com.vibecut.pro.ui.editor.timeline

import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared, hoisted timeline viewport state.
 *
 * The timeline is the most gesture-sensitive surface in the editor, so all viewport math lives in
 * one place: pixels-per-second, horizontal scroll, pinch zoom, and conversion between X pixels and
 * timeline milliseconds. Both the ruler and the lanes use these functions, which guarantees that
 * tapping the ruler, dragging the playhead and dragging a clip all agree on where "3.5s" is.
 */
@Stable
class TimelineViewportState(
    initialPxPerSecond: Float = 64f
) {
    var pxPerSecond by mutableFloatStateOf(initialPxPerSecond.coerceIn(MIN_PX_PER_SEC, MAX_PX_PER_SEC))
        private set

    var scrollPx by mutableFloatStateOf(0f)
        private set

    /** Playhead position while the user is dragging it (null = follow playback). */
    var scrubbingMs by mutableStateOf<Long?>(null)

    val isScrubbing: Boolean get() = scrubbingMs != null

    fun setZoom(pxPerSecondNew: Float) {
        pxPerSecond = pxPerSecondNew.coerceIn(MIN_PX_PER_SEC, MAX_PX_PER_SEC)
    }

    fun zoomBy(factor: Float) = setZoom(pxPerSecond * factor)

    fun setScroll(px: Float, maxScroll: Float) {
        scrollPx = px.coerceIn(0f, maxScroll.coerceAtLeast(0f))
    }

    fun scrollBy(deltaPx: Float, maxScroll: Float) = setScroll(scrollPx + deltaPx, maxScroll)

    fun pxToMs(px: Float): Long = ((px / pxPerSecond) * 1000f).toLong().coerceAtLeast(0L)

    fun msToPx(ms: Long): Float = (ms / 1000f) * pxPerSecond

    /** X position of a timeline timestamp inside the scrolling content. */
    fun contentXForMs(ms: Long): Float = msToPx(ms)

    /** Content width needed for [durationMs] plus trailing padding. */
    fun contentWidth(durationMs: Long, minSeconds: Float = 8f): Dp {
        val seconds = (durationMs / 1000f).coerceAtLeast(minSeconds) + 2f
        return (seconds * pxPerSecond).dp
    }

    companion object {
        const val MIN_PX_PER_SEC = 8f
        const val MAX_PX_PER_SEC = 640f
    }
}

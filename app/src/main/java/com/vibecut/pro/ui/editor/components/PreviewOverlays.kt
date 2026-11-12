package com.vibecut.pro.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vibecut.pro.engine.keyframes.KeyframeInterpolator
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.ui.theme.TextPrimary
import com.vibecut.pro.ui.theme.ZeroAccent
import kotlin.math.abs

/**
 * Live preview of every *scene* layer on top of the decoded video frame: PiP video, text, stickers
 * and burned-in captions.
 *
 * These layers are drawn by Compose during editing because they must react instantly to selection,
 * dragging and slider edits; the exact same content is rendered by [VibeCutSceneOverlay] into the
 * exported video, so what the user arranges is what gets exported.
 */
@Composable
fun PreviewOverlays(
    project: Project,
    playheadMs: Long,
    selectedClipId: String?,
    onDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val activePips = remember(project.tracks, playheadMs) {
        project.tracks
            .filter { it.type == TrackType.VIDEO_PIP && !it.isHidden }
            .flatMap { it.clips }
            .filter { playheadMs in it.startTimeOnTrackMs..it.endTimeOnTrackMs }
    }
    val activeTexts = remember(project.tracks, playheadMs) {
        project.tracks
            .filter { it.type == TrackType.TEXT && !it.isHidden }
            .flatMap { it.clips }
            .filter { playheadMs in it.startTimeOnTrackMs..it.endTimeOnTrackMs }
    }
    val activeStickers = remember(project.tracks, playheadMs) {
        project.tracks
            .filter { it.type == TrackType.STICKER && !it.isHidden }
            .flatMap { it.clips }
            .filter { playheadMs in it.startTimeOnTrackMs..it.endTimeOnTrackMs }
    }
    val activeSubtitles = remember(project.subtitles, playheadMs) {
        project.subtitles.filter { playheadMs in it.startTimeMs..it.endTimeMs }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val stageW = maxWidth
        val stageH = maxHeight
        // Drag deltas are converted to normalized stage units, so a 1 cm finger move on screen moves
        // the overlay the same distance no matter how big the overlay box currently is.
        val stageWidthPx = with(density) { stageW.toPx() }
        val stageHeightPx = with(density) { stageH.toPx() }

        // ---------------- Picture in picture
        activePips.forEach { pip ->
            val localMs = (playheadMs - pip.startTimeOnTrackMs).coerceAtLeast(0L)
            val state = KeyframeInterpolator.evaluateClipState(pip, localMs)
            val offsetX = stageW * (state.positionX * 0.45f)
            val offsetY = stageH * (-state.positionY * 0.45f)
            val isSelected = selectedClipId == pip.id

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(stageW * 0.55f)
                    .aspectRatio(
                        if (pip.sourceHeight > 0) pip.sourceWidth.toFloat() / pip.sourceHeight else 16f / 9f,
                        matchHeightConstraintsFirst = true
                    )
                    .offset(x = offsetX, y = offsetY)
                    .graphicsLayer(
                        scaleX = if (abs(state.scaleX) < 0.01f) 0.01f else state.scaleX,
                        scaleY = if (abs(state.scaleY) < 0.01f) 0.01f else state.scaleY,
                        rotationZ = state.rotationDegrees,
                        alpha = state.opacity
                    )
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        if (isSelected) 2.dp else 0.dp,
                        if (isSelected) ZeroAccent else Color.Transparent,
                        RoundedCornerShape(6.dp)
                    )
                    .pointerInput(pip.id, isSelected) {
                        if (!isSelected) return@pointerInput
                        detectDragGestures { change, drag ->
                            change.consume()
                            if (stageWidthPx > 0f && stageHeightPx > 0f) {
                                onDrag(
                                    (drag.x / stageWidthPx) * 2f,
                                    -(drag.y / stageHeightPx) * 2f
                                )
                            }
                        }
                    }
            ) {
                AsyncImage(
                    model = pip.thumbnailUri ?: pip.sourceUri,
                    contentDescription = pip.sourceName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // ---------------- Text layers
        activeTexts.forEach { textClip ->
            val spec = textClip.textSpec ?: return@forEach
            val localMs = (playheadMs - textClip.startTimeOnTrackMs).coerceAtLeast(0L)
            val state = KeyframeInterpolator.evaluateClipState(textClip, localMs)
            val animProgress = animationProgress(spec, localMs)
            val offsetX = stageW * (state.positionX * 0.45f)
            val offsetY = stageH * (-state.positionY * 0.45f - 0.28f)
            val isSelected = selectedClipId == textClip.id

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = offsetX, y = offsetY)
                    .graphicsLayer(
                        alpha = state.opacity * animProgress.alpha,
                        scaleX = state.scaleX * animProgress.scale,
                        scaleY = state.scaleY * animProgress.scale,
                        rotationZ = state.rotationDegrees,
                        translationY = with(density) { animProgress.slideDp.dp.toPx() }
                    )
                    .then(
                        if (isSelected) {
                            Modifier
                                .border(1.dp, ZeroAccent, RoundedCornerShape(6.dp))
                                .pointerInput(textClip.id) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        if (stageWidthPx > 0f && stageHeightPx > 0f) {
                                            onDrag(
                                                (drag.x / stageWidthPx) * 2f,
                                                -(drag.y / stageHeightPx) * 2f
                                            )
                                        }
                                    }
                                }
                        } else Modifier
                    )
                    .padding(6.dp)
            ) {
                Text(
                    text = animProgress.visibleText(spec.text),
                    color = Color(spec.textColorArgb.toInt()),
                    fontSize = spec.fontSizeSp.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color(spec.shadowColorArgb.toInt()),
                            blurRadius = spec.shadowRadius,
                            offset = Offset(0f, 2f)
                        ),
                        letterSpacing = spec.letterSpacingEm.sp
                    )
                )
            }
        }

        // ---------------- Stickers
        activeStickers.forEach { stickerClip ->
            val spec = stickerClip.stickerSpec ?: return@forEach
            val localMs = (playheadMs - stickerClip.startTimeOnTrackMs).coerceAtLeast(0L)
            val state = KeyframeInterpolator.evaluateClipState(stickerClip, localMs)
            val isSelected = selectedClipId == stickerClip.id
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = stageW * (state.positionX * 0.45f + 0.28f),
                        y = stageH * (-state.positionY * 0.45f + 0.3f)
                    )
                    .graphicsLayer(
                        scaleX = state.scaleX,
                        scaleY = state.scaleY,
                        rotationZ = state.rotationDegrees,
                        alpha = state.opacity
                    )
                    .then(
                        if (isSelected) {
                            Modifier
                                .border(1.dp, ZeroAccent, RoundedCornerShape(8.dp))
                                .pointerInput(stickerClip.id) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        if (stageWidthPx > 0f && stageHeightPx > 0f) {
                                            onDrag(
                                                (drag.x / stageWidthPx) * 2f,
                                                -(drag.y / stageHeightPx) * 2f
                                            )
                                        }
                                    }
                                }
                        } else Modifier
                    )
                    .padding(4.dp)
            ) {
                Text(spec.content, fontSize = 34.sp)
            }
        }

        // ---------------- Burned-in captions
        activeSubtitles.forEach { cue ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    cue.text,
                    color = TextPrimary,
                    fontSize = (13f * (stageHeightPx / 720f).coerceIn(0.8f, 2.4f)).sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private data class AnimFrame(
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val slideDp: Float = 0f,
    val visibleChars: Int = Int.MAX_VALUE
) {
    fun visibleText(full: String): String =
        if (visibleChars >= full.length) full else full.take(visibleChars.coerceAtLeast(0))
}

/**
 * Text animation timing, mirroring the export renderer so preview and output agree.
 */
private fun animationProgress(spec: TextStyleSpec, localMs: Long): AnimFrame {
    val duration = spec.animationDurationMs.coerceAtLeast(1L).toFloat()
    val t = (localMs.toFloat() / duration).coerceIn(0f, 1f)
    return when (spec.animationIn) {
        TextAnimationType.NONE -> AnimFrame()
        TextAnimationType.TYPEWRITER -> AnimFrame(
            visibleChars = (spec.text.length * t).toInt()
        )
        TextAnimationType.POP_BOUNCE -> {
            val overshoot = 1f + 0.25f * kotlin.math.sin(t * Math.PI).toFloat()
            AnimFrame(alpha = t, scale = (0.6f + 0.4f * t) * overshoot)
        }
        TextAnimationType.FADE_UP -> AnimFrame(alpha = t, slideDp = (1f - t) * 14f)
        TextAnimationType.SLIDE_IN -> AnimFrame(alpha = t, slideDp = -(1f - t) * 26f)
        TextAnimationType.KARAOKE -> AnimFrame(visibleChars = (spec.text.length * t).toInt())
    }
}

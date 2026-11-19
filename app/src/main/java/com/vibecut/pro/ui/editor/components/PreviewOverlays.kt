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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.em
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
            // Same placement formula as the export renderer: centre ± pos * 0.45. The preview
            // used to subtract a further 0.28 of the stage height here, so text sat visibly
            // higher on screen than in the file the user actually got.
            val offsetX = stageW * (state.positionX * 0.45f)
            val offsetY = stageH * (-state.positionY * 0.45f)
            val isSelected = selectedClipId == textClip.id

            // The exporter scales the point size by (shortSide / 360), so the preview must do
            // the same against its own stage or the text is the wrong size relative to the frame.
            val fontScale = min(stageW, stageH).value / 360f
            val visible = animProgress.visibleText(spec.text)
            val textStyle = TextStyle(
                fontSize = (spec.fontSizeSp * fontScale).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = spec.composeFontFamily(),
                letterSpacing = spec.letterSpacingEm.em,
                textAlign = TextAlign.Center,
                shadow = if (spec.shadowRadius > 0.2f) {
                    androidx.compose.ui.graphics.Shadow(
                        color = Color(spec.shadowColorArgb.toInt()),
                        blurRadius = spec.shadowRadius * 1.8f,
                        offset = Offset(0f, 3f)
                    )
                } else null
            )

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
                    // Background pill — exported but previously invisible while editing.
                    .then(
                        if ((spec.backgroundColorArgb ushr 24) > 0L) {
                            Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(spec.backgroundColorArgb.toInt()))
                        } else Modifier
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                // Outline is stroked underneath the fill, matching the export's two-pass draw.
                if (spec.outlineWidth > 0.2f) {
                    Text(
                        text = visible,
                        color = Color(spec.outlineColorArgb.toInt()),
                        style = textStyle.copy(
                            drawStyle = Stroke(width = spec.outlineWidth * 2.5f),
                            shadow = null
                        )
                    )
                }
                if (spec.useGradient) {
                    Text(
                        text = visible,
                        style = textStyle.copy(
                            brush = Brush.linearGradient(
                                listOf(
                                    Color(spec.textColorArgb.toInt()),
                                    Color(spec.gradientEndColorArgb.toInt())
                                )
                            )
                        )
                    )
                } else {
                    Text(
                        text = visible,
                        color = Color(spec.textColorArgb.toInt()),
                        style = textStyle
                    )
                }
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
                    // Matches the export renderer exactly. The extra +0.28 / +0.30 that used to
                    // be here pushed every sticker down and to the right of where it exported.
                    .offset(
                        x = stageW * (state.positionX * 0.45f),
                        y = stageH * (-state.positionY * 0.45f)
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
                // Export draws stickers at 110px on a 1080px short side; mirror that ratio.
                Text(spec.content, fontSize = (min(stageW, stageH).value * 110f / 1080f).sp)
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

/**
 * Mirrors the typeface selection in `VibeCutSceneOverlay.drawStyledText` so the font the user
 * picks is the font they see while editing, not just the one that lands in the export.
 */
private fun TextStyleSpec.composeFontFamily(): FontFamily = when {
    fontFamilyName.contains("Serif", true) && !fontFamilyName.contains("Sans", true) -> FontFamily.Serif
    fontFamilyName.contains("Mono", true) -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

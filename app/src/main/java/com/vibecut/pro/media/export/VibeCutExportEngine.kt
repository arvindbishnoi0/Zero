package com.vibecut.pro.media.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.color.CubeLut3D
import com.vibecut.pro.engine.keyframes.KeyframeInterpolator
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.timeline.ExportSegmentPlanner
import com.vibecut.pro.engine.timeline.SpeedSegmentPlan
import com.vibecut.pro.media.shader.ClipEffectsFactory
import com.vibecut.pro.media.shader.VibeCutDspAudioProcessor
import com.vibecut.pro.media.shader.VibeCutSceneOverlay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

sealed class ExportProgressState {
    data object Idle : ExportProgressState()
    data class Running(val percent: Int, val statusLabel: String) : ExportProgressState()
    data class Completed(
        val outputFilePath: String,
        val galleryUri: String?,
        val durationMs: Long,
        val fileSizeBytes: Long,
        val audioOnly: Boolean,
        val isPhotoFrame: Boolean = false
    ) : ExportProgressState()
    data object Cancelled : ExportProgressState()
    data class Error(val message: String) : ExportProgressState()
}

@Singleton
@OptIn(UnstableApi::class)
class VibeCutExportEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _exportState = MutableStateFlow<ExportProgressState>(ExportProgressState.Idle)
    val exportState: StateFlow<ExportProgressState> = _exportState.asStateFlow()

    private var activeTransformer: Transformer? = null
    private var progressPollingJob: Job? = null
    @Volatile
    private var wasCancelledByUser: Boolean = false

    fun isHevcHardwareSupported(): Boolean {
        return runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H265, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    fun listDeviceVideoEncoders(): List<String> {
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && infoHasVideoType(it.supportedTypes) }
                .map { "${it.name} (${it.supportedTypes.joinToString()})" }
        }.getOrDefault(emptyList())
    }

    private fun infoHasVideoType(types: Array<String>): Boolean =
        types.any { it.startsWith("video/", ignoreCase = true) }

    fun resetState() {
        wasCancelledByUser = false
        _exportState.value = ExportProgressState.Idle
    }

    fun cancelActiveExport() {
        wasCancelledByUser = true
        MainScope().launch(Dispatchers.Main) {
            progressPollingJob?.cancel()
            activeTransformer?.cancel()
            activeTransformer = null
            _exportState.value = ExportProgressState.Cancelled
        }
    }

    /**
     * Master Export entry point supporting:
     * 1. High-resolution graded Photo Frame capture (`config.exportAsPhotoFrame`) -> `.jpg` in `Pictures/VibeCutPro`
     * 2. Pure AAC Audio Master export (`config.audioOnly`) -> `.m4a` in `Music/VibeCutPro`
     * 3. Hardware-accelerated Multi-Track Video / Photo Slideshow MP4 export -> `.mp4` in `Movies/VibeCutPro`
     *    with 16px macroblock-aligned dimensions, silent-clip protection, and automatic H.264 fallback retry.
     */
    suspend fun exportProject(
        project: Project,
        config: ExportConfig = project.exportConfig,
        playheadMs: Long = 0L
    ): ExportProgressState = withContext(Dispatchers.Main) {
        wasCancelledByUser = false
        val mainTrack = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN && !it.isHidden }
        val mainClips = mainTrack?.clips.orEmpty()

        if (mainClips.isEmpty() && !config.audioOnly) {
            val err = ExportProgressState.Error("Add at least one video or photo clip to the timeline before exporting.")
            _exportState.value = err
            return@withContext err
        }

        if (config.exportAsPhotoFrame) {
            return@withContext exportPhotoFrameInternal(project, config, playheadMs)
        }

        if (config.audioOnly) {
            return@withContext exportAudioOnlyInternal(project, config)
        }

        // Video / Slideshow export with automatic hardware codec fallback retry if needed
        val primaryResult = runVideoTransformerPass(
            project = project,
            config = config,
            overrideResolution = null,
            forceAvcFallback = false
        )
        if (primaryResult is ExportProgressState.Error && !wasCancelledByUser) {
            val fallbackRes = if (config.resolution.shortSidePx > 1080) {
                ExportResolution.FHD_1080P
            } else {
                ExportResolution.HD_720P
            }
            val shouldRetry = config.codec == VideoCodecOption.H265 ||
                config.resolution != fallbackRes ||
                config.frameRate == ExportFrameRate.FPS_60
            if (shouldRetry) {
                _exportState.value = ExportProgressState.Running(
                    percent = 2,
                    statusLabel = "Adjusting encoder profile to compatible ${fallbackRes.badge} H.264..."
                )
                return@withContext runVideoTransformerPass(
                    project = project,
                    config = config.copy(
                        codec = VideoCodecOption.H264,
                        frameRate = ExportFrameRate.FPS_30
                    ),
                    overrideResolution = fallbackRes,
                    forceAvcFallback = true
                )
            }
        }
        primaryResult
    }

    private suspend fun runVideoTransformerPass(
        project: Project,
        config: ExportConfig,
        overrideResolution: ExportResolution?,
        forceAvcFallback: Boolean
    ): ExportProgressState = withContext(Dispatchers.Main) {
        val mainTrack = project.tracks.first { it.type == TrackType.VIDEO_MAIN }
        val mainClips = mainTrack.clips
        val effectiveRes = overrideResolution ?: config.resolution
        val targetShortSide = clampSupportedShortSide(
            aspect = project.aspectRatio,
            requestedShortSide = effectiveRes.shortSidePx,
            preferHevc = !forceAvcFallback && config.codec == VideoCodecOption.H265 && isHevcHardwareSupported(),
            customRatio = project.customAspectRatio
        )

        val exportDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }
        val sanitizedTitle = sanitizeFilename(project.title)
        val outFile = File(exportDir, "${sanitizedTitle}_${System.currentTimeMillis()}.mp4")

        var anyMainClipKeepsAudio = false

        // Clips whose speed ramps (or plays backwards) cannot be expressed with Media3's constant
        // speed provider, so each is expanded into frame-accurate constant-speed segments.
        val mainEditedItems = mainClips.flatMap { clip ->
            val isImageItem = clip.mediaKind == MediaKind.IMAGE
            val hasRealAudio = !isImageItem &&
                !clip.isFrozenFrame &&
                clip.hasAudioTrack &&
                probeHasAudioTrackCached(clip.sourceUri)
            val removeAudio = !hasRealAudio || mainTrack.isMuted || clip.isMuted
            if (!removeAudio) anyMainClipKeepsAudio = true

            val planned = ExportSegmentPlanner.planClip(clip)
            val segments = planned.ifEmpty {
                listOf(
                    SpeedSegmentPlan(
                        clipId = clip.id,
                        segmentIndex = 0,
                        totalSegments = 1,
                        sourceStartMs = clip.mapTimelineOffsetToSourceMs(0L),
                        sourceEndMs = clip.mapTimelineOffsetToSourceMs(clip.timelineDurationMs),
                        timelineStartMs = clip.startTimeOnTrackMs,
                        timelineEndMs = clip.endTimeOnTrackMs,
                        speed = clip.clampedSpeed,
                        isReversed = clip.isReversed,
                        isFrozen = clip.isFrozenFrame
                    )
                )
            }

            segments.map { seg ->
                val mediaItem = buildSegmentMediaItem(clip, seg)
                val effects = ClipEffectsFactory.buildFullClipEffects(
                    context = context,
                    clip = clip,
                    project = project,
                    trackVolume = if (mainTrack.isMuted) 0f else mainTrack.volume,
                    includeAudioProcessor = !removeAudio,
                    includeSceneOverlays = true,
                    targetExportHeight = targetShortSide,
                    useCodecSafeAlignment = true,
                    segmentSpeed = if (seg.isFrozen) 1f else seg.speed,
                    applySpeedProvider = !clip.hasSpeedRamp && !seg.isFrozen,
                    segmentTimelineOffsetMs = seg.timelineStartMs - clip.startTimeOnTrackMs
                )

                val itemBuilder = EditedMediaItem.Builder(mediaItem)
                    .setRemoveVideo(false)
                    .setRemoveAudio(removeAudio)
                    .setFrameRate(config.frameRate.fps)
                    .setEffects(effects)

                if (isImageItem) {
                    itemBuilder.setDurationUs(seg.timelineDurationMs.coerceAtLeast(100L) * 1_000L)
                }
                itemBuilder.build()
            }
        }

        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence.Builder(mainEditedItems).build())

        // Synchronized Auxiliary Audio Tracks (Music, Voiceover, SFX)
        val totalTimelineUs = project.totalDurationMs.coerceAtLeast(100L) * 1_000L
        val extraAudioSequences = buildAuxiliaryAudioSequences(project, totalTimelineUs)
        sequences.addAll(extraAudioSequences)

        val shouldForceAudioTrack = anyMainClipKeepsAudio || extraAudioSequences.isNotEmpty()
        val composition = Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(shouldForceAudioTrack)
            .build()

        val selectedVideoMime = if (!forceAvcFallback && config.codec == VideoCodecOption.H265 && isHevcHardwareSupported()) {
            MimeTypes.VIDEO_H265
        } else {
            MimeTypes.VIDEO_H264
        }

        // Always include the project's custom ratio so CUSTOM projects export at their real shape.
        val (alignedW, alignedH) = AspectRatioPreset.dimensionsForProject(
            project.aspectRatio,
            targetShortSide,
            project.customAspectRatio
        )
        val safeBitrate = clampBitrateForEncoder(
            mimeType = selectedVideoMime,
            width = alignedW,
            height = alignedH,
            requestedBitrate = config.copy(resolution = effectiveRes)
                .targetVideoBitrateBps(project.aspectRatio, project.customAspectRatio)
        )

        val videoEncoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(safeBitrate)
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(videoEncoderSettings)
            .setEnableFallback(true)
            .build()

        executeTransformerRun(
            composition = composition,
            outFile = outFile,
            videoMimeType = selectedVideoMime,
            encoderFactory = encoderFactory,
            audioOnly = false,
            saveToGallery = config.saveToGallery,
            fallbackDurationMs = project.totalDurationMs,
            progressLabelBuilder = { pct ->
                "Rendering ${effectiveRes.badge} ${config.frameRate.fps}fps • ${if (selectedVideoMime == MimeTypes.VIDEO_H265) "H.265" else "H.264"} ($pct%)"
            }
        )
    }

    /**
     * Builds the clipped [MediaItem] for one constant-speed export segment. Frozen frames are
     * handled by asking for a short source read; everything else clips to the segment's source range.
     */
    private fun buildSegmentMediaItem(clip: TimelineClip, seg: SpeedSegmentPlan): MediaItem {
        val clipUri = parseMediaUri(clip.sourceUri)
        if (clip.mediaKind == MediaKind.IMAGE) {
            return MediaItem.Builder()
                .setUri(clipUri)
                .setMimeType(clip.mimeType.ifBlank { MimeTypes.IMAGE_JPEG })
                .setImageDurationMs(seg.timelineDurationMs.coerceAtLeast(100L))
                .build()
        }
        val startPosMs = if (clip.isFrozenFrame) {
            clip.freezeFrameSourceMs.coerceAtLeast(0L)
        } else {
            seg.sourceStartMs.coerceAtLeast(0L)
        }
        val rawEndMs = if (clip.isFrozenFrame) {
            (startPosMs + 120L).coerceAtMost(clip.sourceDurationMs.coerceAtLeast(startPosMs + 80L))
        } else {
            seg.sourceEndMs
        }
        val safeEndMs = rawEndMs.coerceAtLeast(startPosMs + 40L)
        return MediaItem.Builder()
            .setUri(clipUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startPosMs)
                    .setEndPositionMs(safeEndMs)
                    .build()
            )
            .build()
    }

    private suspend fun exportAudioOnlyInternal(
        project: Project,
        config: ExportConfig
    ): ExportProgressState = withContext(Dispatchers.Main) {
        val mainTrack = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN && !it.isHidden }
        val mainClips = mainTrack?.clips.orEmpty()
        val totalTimelineUs = project.totalDurationMs.coerceAtLeast(100L) * 1_000L

        val sequences = mutableListOf<EditedMediaItemSequence>()

        // 1. Main track audio (if any main video clip actually has unmuted audio)
        if (mainTrack != null && !mainTrack.isMuted && mainClips.isNotEmpty()) {
            val seqBuilder = EditedMediaItemSequence.Builder()
            var hasAnyMainAudio = false
            for (clip in mainClips) {
                val hasAudio = clip.mediaKind == MediaKind.VIDEO &&
                    !clip.isFrozenFrame &&
                    !clip.isMuted &&
                    clip.hasAudioTrack &&
                    probeHasAudioTrackCached(clip.sourceUri)

                if (!hasAudio) {
                    seqBuilder.addGap(clip.timelineDurationMs.coerceAtLeast(50L) * 1_000L)
                } else {
                    val startMs = clip.trimStartMs.coerceAtLeast(0L)
                    val endMs = clip.effectiveTrimEndMs.coerceAtLeast(startMs + 60L)
                    val mediaItem = MediaItem.Builder()
                        .setUri(parseMediaUri(clip.sourceUri))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startMs)
                                .setEndPositionMs(endMs)
                                .build()
                        )
                        .build()
                    val audioEffects = ClipEffectsFactory.buildFullClipEffects(
                        context = context,
                        clip = clip,
                        project = project,
                        trackVolume = mainTrack.volume,
                        includeAudioProcessor = true,
                        includeSceneOverlays = false,
                        targetExportHeight = null
                    )
                    seqBuilder.addItem(
                        EditedMediaItem.Builder(mediaItem)
                            .setRemoveVideo(true)
                            .setEffects(Effects(audioEffects.audioProcessors, emptyList<Effect>()))
                            .build()
                    )
                    hasAnyMainAudio = true
                }
            }
            if (hasAnyMainAudio) {
                sequences.add(seqBuilder.build())
            }
        }

        // 2. Auxiliary Music / Voiceover / SFX tracks
        sequences.addAll(buildAuxiliaryAudioSequences(project, totalTimelineUs))

        if (sequences.isEmpty()) {
            val err = ExportProgressState.Error(
                "Cannot export Audio-Only (.m4a): no audible audio tracks or video sound exist in this project."
            )
            _exportState.value = err
            return@withContext err
        }

        val exportDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }
        val outFile = File(exportDir, "${sanitizeFilename(project.title)}_${System.currentTimeMillis()}.m4a")
        val composition = Composition.Builder(sequences).build()
        val encoderFactory = DefaultEncoderFactory.Builder(context).setEnableFallback(true).build()

        executeTransformerRun(
            composition = composition,
            outFile = outFile,
            videoMimeType = null,
            encoderFactory = encoderFactory,
            audioOnly = true,
            saveToGallery = config.saveToGallery,
            fallbackDurationMs = project.totalDurationMs,
            progressLabelBuilder = { pct -> "Encoding AAC Studio Audio Master ($pct%)..." }
        )
    }

    private fun buildAuxiliaryAudioSequences(
        project: Project,
        totalTimelineUs: Long
    ): List<EditedMediaItemSequence> {
        val result = mutableListOf<EditedMediaItemSequence>()
        val extraAudioTracks = project.tracks.filter {
            it.type.isAudioTrack && !it.isMuted && !it.isHidden && it.clips.isNotEmpty()
        }
        for (audioTrack in extraAudioTracks) {
            val seqBuilder = EditedMediaItemSequence.Builder()
            var cursorUs = 0L
            var addedAnyItem = false
            for (ac in audioTrack.clips.sortedBy { it.startTimeOnTrackMs }) {
                if (ac.isMuted) continue
                val startUs = ac.startTimeOnTrackMs.coerceAtLeast(0L) * 1_000L
                if (startUs >= totalTimelineUs) break
                if (startUs > cursorUs) {
                    seqBuilder.addGap(startUs - cursorUs)
                    cursorUs = startUs
                }
                val maxClipDurMs = ((totalTimelineUs - cursorUs) / 1_000L).coerceAtLeast(80L)
                val clipStartMs = ac.trimStartMs.coerceAtLeast(0L)
                val endTrimMs = minOf(ac.effectiveTrimEndMs, clipStartMs + maxClipDurMs)
                    .coerceAtLeast(clipStartMs + 60L)

                val item = MediaItem.Builder()
                    .setUri(parseMediaUri(ac.sourceUri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clipStartMs)
                            .setEndPositionMs(endTrimMs)
                            .build()
                    )
                    .build()
                // Resolve the processor's explicit type: `Effects` takes List<AudioProcessor>
                // and List<Effect>, so an untyped empty list is ambiguous here.
                val audioProcessors: List<AudioProcessor> = listOf(VibeCutDspAudioProcessor(ac, audioTrack.volume))
                val audioEffects = Effects(audioProcessors, emptyList<Effect>())
                seqBuilder.addItem(
                    EditedMediaItem.Builder(item)
                        .setRemoveVideo(true)
                        .setEffects(audioEffects)
                        .build()
                )
                addedAnyItem = true
                cursorUs += (endTrimMs - clipStartMs) * 1_000L
            }
            if (addedAnyItem) {
                result.add(seqBuilder.build())
            }
        }
        return result
    }

    private suspend fun executeTransformerRun(
        composition: Composition,
        outFile: File,
        videoMimeType: String?,
        encoderFactory: Codec.EncoderFactory,
        audioOnly: Boolean,
        saveToGallery: Boolean,
        fallbackDurationMs: Long,
        progressLabelBuilder: (Int) -> String
    ): ExportProgressState = coroutineScope {
        val completionDeferred = CompletableDeferred<ExportProgressState>()

        val transformerBuilder = Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    progressPollingJob?.cancel()
                    activeTransformer = null
                    launch {
                        val savedUri = if (saveToGallery) {
                            withContext(Dispatchers.IO) {
                                saveExportToMediaStore(outFile, audioOnly = audioOnly, isImage = false)
                            }
                        } else null

                        val done = ExportProgressState.Completed(
                            outputFilePath = outFile.absolutePath,
                            galleryUri = savedUri?.toString(),
                            durationMs = exportResult.durationMs.takeIf { it > 0 } ?: fallbackDurationMs,
                            fileSizeBytes = outFile.length(),
                            audioOnly = audioOnly,
                            isPhotoFrame = false
                        )
                        _exportState.value = done
                        completionDeferred.complete(done)
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    progressPollingJob?.cancel()
                    activeTransformer = null
                    runCatching { if (outFile.exists()) outFile.delete() }
                    val friendlyMsg = buildFriendlyExportError(exportException)
                    val err = ExportProgressState.Error(friendlyMsg)
                    _exportState.value = err
                    completionDeferred.complete(err)
                }
            })

        if (audioOnly || videoMimeType == null) {
            transformerBuilder.setRemoveVideo(true)
        } else {
            transformerBuilder.setVideoMimeType(videoMimeType)
        }

        val transformer = transformerBuilder.build()
        activeTransformer = transformer
        _exportState.value = ExportProgressState.Running(1, "Initializing MediaCodec & OpenGL ES Pipeline...")

        runCatching {
            transformer.start(composition, outFile.absolutePath)
        }.onFailure { startErr ->
            activeTransformer = null
            val err = ExportProgressState.Error("Failed to start media encoder: ${startErr.localizedMessage ?: "Unknown error"}")
            _exportState.value = err
            return@coroutineScope err
        }

        val progressHolder = ProgressHolder()
        progressPollingJob = launch {
            while (isActive) {
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val pct = progressHolder.progress.coerceIn(0, 99)
                    _exportState.value = ExportProgressState.Running(
                        percent = pct,
                        statusLabel = progressLabelBuilder(pct)
                    )
                }
                delay(160L)
            }
        }

        completionDeferred.await()
    }

    /**
     * Captures and grades a full-resolution still Photo Frame (`JPEG`) at [playheadMs] with full
     * Filter Preset, 3D LUT, Light/Color/HSL/Curves/Wheels, Pro Sharpness/Clarity/Vignette/Grain,
     * Crop, Transform, and Text/Sticker/PIP Overlays, saving directly to `Pictures/VibeCutPro`.
     */
    private suspend fun exportPhotoFrameInternal(
        project: Project,
        config: ExportConfig,
        playheadMs: Long
    ): ExportProgressState {
        _exportState.value = ExportProgressState.Running(12, "Decoding high-resolution frame...")

        val result = withContext(Dispatchers.Default) {
            runCatching {
                val clip = project.findMainClipAt(playheadMs)
                    ?: throw IllegalStateException("No visual clip found at current playhead.")
                val localOffsetMs = (playheadMs - clip.startTimeOnTrackMs).coerceIn(0L, clip.timelineDurationMs)
                val sourceFrame = decodeHighResClipFrame(clip, localOffsetMs)
                    ?: throw IllegalStateException("Unable to decode frame from '${clip.sourceName}'.")

                val (targetW, targetH) = project.aspectRatio.computeDimensions(config.resolution.shortSidePx)
                val outBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(outBitmap)
                canvas.drawColor(Color.BLACK)

                // Apply Crop if configured
                val croppedFrame = if (!clip.crop.isIdentity) {
                    val x = (clip.crop.left * sourceFrame.width).roundToInt().coerceIn(0, sourceFrame.width - 1)
                    val y = (clip.crop.top * sourceFrame.height).roundToInt().coerceIn(0, sourceFrame.height - 1)
                    val w = (clip.crop.widthFraction * sourceFrame.width).roundToInt().coerceIn(1, sourceFrame.width - x)
                    val h = (clip.crop.heightFraction * sourceFrame.height).roundToInt().coerceIn(1, sourceFrame.height - y)
                    Bitmap.createBitmap(sourceFrame, x, y, w, h)
                } else sourceFrame

                // Fit inside target canvas + apply Keyframed Transform
                val kf = KeyframeInterpolator.evaluateClipState(clip, localOffsetMs)
                val fitScale = min(
                    targetW.toFloat() / max(1, croppedFrame.width).toFloat(),
                    targetH.toFloat() / max(1, croppedFrame.height).toFloat()
                )
                val drawMatrix = Matrix().apply {
                    postTranslate(-croppedFrame.width / 2f, -croppedFrame.height / 2f)
                    postScale(fitScale * kf.scaleX, fitScale * kf.scaleY)
                    if (abs(kf.rotationDegrees) > 0.01f) {
                        postRotate(kf.rotationDegrees)
                    }
                    postTranslate(
                        targetW * 0.5f + kf.positionX * targetW * 0.5f,
                        targetH * 0.5f - kf.positionY * targetH * 0.5f
                    )
                }
                val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = (kf.opacity * 255f).roundToInt().coerceIn(0, 255)
                }
                canvas.drawBitmap(croppedFrame, drawMatrix, framePaint)
                if (croppedFrame !== sourceFrame) croppedFrame.recycle()
                sourceFrame.recycle()

                withContext(Dispatchers.Main) {
                    _exportState.value = ExportProgressState.Running(48, "Applying Filter, 3D LUT & Pro Color Grade...")
                }

                // Apply 3D Color Grading + Custom .cube LUT
                if (clip.colorGrade.hasColorLutChanges) {
                    applyBaked3dLutToBitmapInPlace(outBitmap, clip.colorGrade, project.customColorPresets)
                }

                withContext(Dispatchers.Main) {
                    _exportState.value = ExportProgressState.Running(76, "Applying Pro Sharpness, Detail & Overlays...")
                }

                // Apply Spatial Finishing (Sharpness, Clarity/Structure, Vignette, Grain)
                val spatial = ColorGradingEngine.resolveEffectiveSpatialConfig(
                    clip.colorGrade,
                    project.customColorPresets
                )
                if (spatial.hasSpatialDetailChanges) {
                    applySpatialFinishingToBitmapInPlace(outBitmap, spatial)
                }

                // Burn-in Scene Overlays (PIP, Text, Subtitles, Stickers)
                val pipClips = project.tracks
                    .filter { it.type == TrackType.VIDEO_PIP && !it.isHidden }
                    .flatMap { it.clips }
                val textClips = project.tracks
                    .filter { it.type == TrackType.TEXT && !it.isHidden }
                    .flatMap { it.clips }
                val stickerClips = project.tracks
                    .filter { it.type == TrackType.STICKER && !it.isHidden }
                    .flatMap { it.clips }
                if (pipClips.isNotEmpty() || textClips.isNotEmpty() || stickerClips.isNotEmpty() || project.subtitles.isNotEmpty()) {
                    val overlayRenderer = VibeCutSceneOverlay(
                        context = context,
                        aspectRatio = project.aspectRatio,
                        customRatio = project.customAspectRatio,
                        clipStartOnTimelineMs = clip.startTimeOnTrackMs,
                        pipClips = pipClips,
                        textClips = textClips,
                        stickerClips = stickerClips,
                        subtitles = project.subtitles
                    )
                    // First call initializes firstPtsUs = 0L; second call renders at localOffsetMs
                    overlayRenderer.getBitmap(0L)
                    val ovBmp = overlayRenderer.getBitmap(localOffsetMs * 1_000L)
                    if (ovBmp.width > 8) {
                        canvas.drawBitmap(
                            ovBmp,
                            null,
                            RectF(0f, 0f, targetW.toFloat(), targetH.toFloat()),
                            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        )
                    }
                }

                val exportDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }
                val outFile = File(exportDir, "${sanitizeFilename(project.title)}_Photo_${System.currentTimeMillis()}.jpg")
                FileOutputStream(outFile).use { fos ->
                    outBitmap.compress(Bitmap.CompressFormat.JPEG, 96, fos)
                }
                outBitmap.recycle()

                val galleryUri = if (config.saveToGallery) {
                    saveExportToMediaStore(outFile, audioOnly = false, isImage = true)
                } else null

                ExportProgressState.Completed(
                    outputFilePath = outFile.absolutePath,
                    galleryUri = galleryUri?.toString(),
                    durationMs = 0L,
                    fileSizeBytes = outFile.length(),
                    audioOnly = false,
                    isPhotoFrame = true
                )
            }
        }

        return result.fold(
            onSuccess = { done ->
                _exportState.value = done
                done
            },
            onFailure = { e ->
                val err = ExportProgressState.Error("Photo export failed: ${e.localizedMessage ?: "Unable to render frame"}")
                _exportState.value = err
                err
            }
        )
    }

    private fun decodeHighResClipFrame(clip: TimelineClip, localOffsetMs: Long): Bitmap? {
        // Prefer full-res image if clip came from a photo
        if (clip.mimeType.startsWith("image/", ignoreCase = true) || clip.mediaKind == MediaKind.IMAGE) {
            val candidate = clip.thumbnailUri ?: clip.sourceUri
            decodeBitmapFromUri(candidate)?.let { return it }
        }
        val sourceMs = clip.mapTimelineOffsetToSourceMs(localOffsetMs)
        val retriever = MediaMetadataRetriever()
        val videoFrame = try {
            val u = parseMediaUri(clip.sourceUri)
            if (u.scheme == "file" && !u.path.isNullOrBlank()) {
                retriever.setDataSource(u.path)
            } else {
                retriever.setDataSource(context, u)
            }
            retriever.getFrameAtTime(sourceMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(sourceMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
        if (videoFrame != null) return videoFrame
        return clip.thumbnailUri?.let { decodeBitmapFromUri(it) }
    }

    private fun decodeBitmapFromUri(uriStr: String): Bitmap? = runCatching {
        val u = parseMediaUri(uriStr)
        if (u.scheme == "file" && !u.path.isNullOrBlank()) {
            BitmapFactory.decodeFile(u.path)
        } else {
            context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    private fun applyBaked3dLutToBitmapInPlace(
        bitmap: Bitmap,
        grade: ColorGradeConfig,
        customPresets: List<ColorGradePreset>
    ) {
        val customCube: CubeLut3D? = grade.customCubeLutUri?.takeIf { it.isNotBlank() }?.let { uriStr ->
            runCatching {
                val u = parseMediaUri(uriStr)
                val text = if (u.scheme == "file" && !u.path.isNullOrBlank()) {
                    File(u.path!!).readText()
                } else {
                    context.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() }
                }
                if (!text.isNullOrBlank()) ColorGradingEngine.parseAdobeCubeLut(text, grade.lutIntensity) else null
            }.getOrNull()
        }

        val baked = ColorGradingEngine.bakeTo3dCube(
            cfg = grade,
            size = 17,
            mixIntensity = 1.0f,
            customPresets = customPresets
        )
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val px = pixels[i]
            val a = px and -0x1000000
            var r = ((px shr 16) and 0xFF) / 255f
            var g = ((px shr 8) and 0xFF) / 255f
            var b = (px and 0xFF) / 255f

            if (customCube != null) {
                val s = trilinearLookup(customCube, r, g, b)
                r = s[0]; g = s[1]; b = s[2]
            }
            val out = trilinearLookup(baked, r, g, b)
            val ri = (out[0] * 255f).roundToInt().coerceIn(0, 255)
            val gi = (out[1] * 255f).roundToInt().coerceIn(0, 255)
            val bi = (out[2] * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = a or (ri shl 16) or (gi shl 8) or bi
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun applySpatialFinishingToBitmapInPlace(
        bitmap: Bitmap,
        spatial: ColorGradeConfig
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val dst = src.copyOf()

        val sharpGain = spatial.sharpness * 2.1f + spatial.structure * 0.9f
        val radiusPx = (1 + (spatial.sharpenRadius * 3f).roundToInt()).coerceIn(1, 4)
        val thresh255 = spatial.sharpenThreshold * 36f
        val clarityAmt = spatial.clarity
        val vig = spatial.vignette
        val vigFeather = (0.20f + spatial.vignetteFeather * 0.65f)
        val grainAmt = spatial.grain * 28f

        for (y in 0 until h) {
            val ny = (y.toFloat() / max(1, h) - 0.5f) * 2f
            for (x in 0 until w) {
                val idx = y * w + x
                val c = src[idx]
                var r = ((c shr 16) and 0xFF).toFloat()
                var g = ((c shr 8) and 0xFF).toFloat()
                var b = (c and 0xFF).toFloat()

                if ((sharpGain > 0.01f || abs(clarityAmt) > 0.01f) &&
                    x >= radiusPx && x < w - radiusPx && y >= radiusPx && y < h - radiusPx
                ) {
                    val n1 = src[(y - radiusPx) * w + x]
                    val n2 = src[(y + radiusPx) * w + x]
                    val n3 = src[y * w + (x - radiusPx)]
                    val n4 = src[y * w + (x + radiusPx)]
                    val br = (((n1 shr 16) and 0xFF) + ((n2 shr 16) and 0xFF) + ((n3 shr 16) and 0xFF) + ((n4 shr 16) and 0xFF)) * 0.25f
                    val bg = (((n1 shr 8) and 0xFF) + ((n2 shr 8) and 0xFF) + ((n3 shr 8) and 0xFF) + ((n4 shr 8) and 0xFF)) * 0.25f
                    val bb = ((n1 and 0xFF) + (n2 and 0xFF) + (n3 and 0xFF) + (n4 and 0xFF)) * 0.25f

                    val dr = r - br
                    val dg = g - bg
                    val db = b - bb
                    val edgeMag = max(abs(dr), max(abs(dg), abs(db)))
                    if (sharpGain > 0.01f && edgeMag > thresh255) {
                        r += dr * sharpGain
                        g += dg * sharpGain
                        b += db * sharpGain
                    }
                    if (abs(clarityAmt) > 0.01f) {
                        val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                        val midMask = 1f - abs(luma - 0.5f) * 1.7f
                        if (midMask > 0f) {
                            val k = clarityAmt * midMask * 0.85f
                            r += dr * k
                            g += dg * k
                            b += db * k
                        }
                    }
                }

                if (abs(vig) > 0.01f) {
                    val nx = (x.toFloat() / max(1, w) - 0.5f) * 2f
                    val dist = sqrt(nx * nx + ny * ny) * 0.7071f
                    val t = ((dist - (0.32f)) / vigFeather).coerceIn(0f, 1f)
                    val smooth = t * t * (3f - 2f * t)
                    if (vig >= 0f) {
                        val factor = (1f - smooth * vig * 0.88f).coerceIn(0.05f, 1f)
                        r *= factor; g *= factor; b *= factor
                    } else {
                        val lift = smooth * (-vig) * 180f
                        r += lift; g += lift; b += lift
                    }
                }

                if (grainAmt > 0.2f) {
                    val hash = (((x * 73856093) xor (y * 19349663)) and 0xFF) / 127.5f - 1f
                    r += hash * grainAmt
                    g += hash * grainAmt
                    b += hash * grainAmt
                }

                val ri = r.roundToInt().coerceIn(0, 255)
                val gi = g.roundToInt().coerceIn(0, 255)
                val bi = b.roundToInt().coerceIn(0, 255)
                dst[idx] = (c and -0x1000000) or (ri shl 16) or (gi shl 8) or bi
            }
        }
        bitmap.setPixels(dst, 0, w, 0, 0, w, h)
    }

    private fun trilinearLookup(cube: CubeLut3D, r: Float, g: Float, b: Float): FloatArray {
        val n = cube.size
        val maxIdx = (n - 1).toFloat()
        val rf = r.coerceIn(0f, 1f) * maxIdx
        val gf = g.coerceIn(0f, 1f) * maxIdx
        val bf = b.coerceIn(0f, 1f) * maxIdx
        val r0 = rf.toInt().coerceIn(0, n - 1)
        val g0 = gf.toInt().coerceIn(0, n - 1)
        val b0 = bf.toInt().coerceIn(0, n - 1)
        val r1 = min(n - 1, r0 + 1)
        val g1 = min(n - 1, g0 + 1)
        val b1 = min(n - 1, b0 + 1)
        val dr = rf - r0
        val dg = gf - g0
        val db = bf - b0

        val c000 = cube.argbCube[r0][g0][b0]
        val c100 = cube.argbCube[r1][g0][b0]
        val c010 = cube.argbCube[r0][g1][b0]
        val c110 = cube.argbCube[r1][g1][b0]
        val c001 = cube.argbCube[r0][g0][b1]
        val c101 = cube.argbCube[r1][g0][b1]
        val c011 = cube.argbCube[r0][g1][b1]
        val c111 = cube.argbCube[r1][g1][b1]

        fun ch(c: Int, s: Int) = ((c shr s) and 0xFF) / 255f
        fun interp(s: Int): Float {
            val c00 = ch(c000, s) * (1f - dr) + ch(c100, s) * dr
            val c10 = ch(c010, s) * (1f - dr) + ch(c110, s) * dr
            val c01 = ch(c001, s) * (1f - dr) + ch(c101, s) * dr
            val c11 = ch(c011, s) * (1f - dr) + ch(c111, s) * dr
            return (c00 * (1f - dg) + c10 * dg) * (1f - db) + (c01 * (1f - dg) + c11 * dg) * db
        }
        return floatArrayOf(interp(16), interp(8), interp(0))
    }

    private val audioProbeCache = HashMap<String, Boolean>()

    private fun probeHasAudioTrackCached(uriStr: String): Boolean {
        audioProbeCache[uriStr]?.let { return it }
        val extractor = MediaExtractor()
        val found = try {
            val u = parseMediaUri(uriStr)
            if (u.scheme == "file" && !u.path.isNullOrBlank()) {
                extractor.setDataSource(u.path!!)
            } else {
                extractor.setDataSource(context, u, null)
            }
            var hasAudio = false
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/", ignoreCase = true)) {
                    hasAudio = true
                    break
                }
            }
            hasAudio
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { extractor.release() }
        }
        audioProbeCache[uriStr] = found
        return found
    }

    private fun clampSupportedShortSide(
        aspect: AspectRatioPreset,
        requestedShortSide: Int,
        preferHevc: Boolean,
        customRatio: Float = aspect.ratioFloat
    ): Int {
        val mime = if (preferHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        val candidates = listOf(requestedShortSide, 1440, 1080, 720).distinct().filter { it <= requestedShortSide }
        for (shortSide in candidates) {
            val (w, h) = AspectRatioPreset.dimensionsForProject(aspect, shortSide, customRatio)
            val supported = runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                        runCatching {
                            val vc = info.getCapabilitiesForType(mime).videoCapabilities
                            vc.isSizeSupported(w, h) || vc.isSizeSupported(h, w)
                        }.getOrDefault(false)
                }
            }.getOrDefault(true)
            if (supported) return shortSide
        }
        return 720
    }

    private fun clampBitrateForEncoder(
        mimeType: String,
        width: Int,
        height: Int,
        requestedBitrate: Int
    ): Int {
        return runCatching {
            val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { c ->
                c.isEncoder && c.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
                    runCatching {
                        val vc = c.getCapabilitiesForType(mimeType).videoCapabilities
                        vc.isSizeSupported(width, height) || vc.isSizeSupported(height, width)
                    }.getOrDefault(false)
            } ?: return@runCatching requestedBitrate
            val range = info.getCapabilitiesForType(mimeType).videoCapabilities.bitrateRange
            requestedBitrate.coerceIn(range.lower.coerceAtLeast(500_000), range.upper)
        }.getOrDefault(requestedBitrate)
    }

    private fun buildFriendlyExportError(e: ExportException): String {
        val raw = e.localizedMessage ?: e.errorCodeName
        return when (e.errorCode) {
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ExportException.ERROR_CODE_IO_NO_PERMISSION ->
                "One or more source media files could not be read. Re-import missing clips and try again."
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED ->
                "Hardware encoder busy or unsupported resolution ($raw). Try 1080p H.264 30fps."
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_DECODER_INIT_FAILED ->
                "Source clip decoder error ($raw). Try trimming or re-importing the clip."
            else -> "Export failed: $raw"
        }
    }

    private fun saveExportToMediaStore(file: File, audioOnly: Boolean, isImage: Boolean): Uri? {
        if (!file.exists()) return null
        return runCatching {
            val resolver = context.contentResolver
            val collection = when {
                isImage -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                audioOnly -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val mime = when {
                isImage -> "image/jpeg"
                audioOnly -> "audio/mp4"
                else -> "video/mp4"
            }
            val subDir = when {
                isImage -> Environment.DIRECTORY_PICTURES
                audioOnly -> Environment.DIRECTORY_MUSIC
                else -> Environment.DIRECTORY_MOVIES
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$subDir/VibeCutPro")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val itemUri = resolver.insert(collection, values) ?: return@runCatching null
            resolver.openOutputStream(itemUri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            }
            itemUri
        }.getOrNull()
    }

    fun buildShareIntent(
        outputFilePath: String,
        audioOnly: Boolean,
        isPhotoFrame: Boolean = false
    ): Intent? {
        val file = File(outputFilePath)
        if (!file.exists()) return null
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mime = when {
            isPhotoFrame || file.extension.equals("jpg", ignoreCase = true) || file.extension.equals("png", ignoreCase = true) ->
                "image/jpeg"
            audioOnly || file.extension.equals("m4a", ignoreCase = true) ->
                "audio/mp4"
            else -> "video/mp4"
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_TEXT, "Edited with VibeCut Pro ✨")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun parseMediaUri(uriStr: String): Uri {
        return if (uriStr.startsWith("/")) Uri.fromFile(File(uriStr)) else Uri.parse(uriStr)
    }

    private fun sanitizeFilename(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(28).ifBlank { "VibeCut" }
}

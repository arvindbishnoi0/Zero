package com.vibecut.pro.media.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.media.shader.ClipEffectsFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class ExportProgressState {
    data object Idle : ExportProgressState()
    data class Running(val percent: Int, val statusLabel: String) : ExportProgressState()
    data class Completed(
        val outputFilePath: String,
        val galleryUri: String?,
        val durationMs: Long,
        val fileSizeBytes: Long,
        val audioOnly: Boolean
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

    /**
     * Queries Android's hardware [MediaCodecList] to report whether H.265 / HEVC encoding is supported.
     */
    fun isHevcHardwareSupported(): Boolean {
        return runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H265, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    /**
     * Lists available hardware video encoders on the device for Settings diagnostics.
     */
    fun listDeviceVideoEncoders(): List<String> {
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.startsWith("video/") } }
                .map { "${it.name} (${it.supportedTypes.joinToString()})" }
        }.getOrDefault(emptyList())
    }

    fun resetState() {
        _exportState.value = ExportProgressState.Idle
    }

    fun cancelActiveExport() {
        MainScope().launch(Dispatchers.Main) {
            progressPollingJob?.cancel()
            activeTransformer?.cancel()
            activeTransformer = null
            _exportState.value = ExportProgressState.Cancelled
        }
    }

    /**
     * Executes a real Media3 [Transformer] multi-sequence [Composition] export on the Main Looper
     * (required by Transformer), applying all OpenGL ES 2.0 shaders, 3D LUTs, Keyframes, Speed Curves,
     * Text/Sticker Overlays, and PCM Audio DSP processors.
     */
    suspend fun exportProject(
        project: Project,
        config: ExportConfig = project.exportConfig
    ): ExportProgressState = withContext(Dispatchers.Main) {
        val mainClips = project.tracks
            .firstOrNull { it.type == TrackType.VIDEO_MAIN && !it.isHidden }
            ?.clips.orEmpty()

        if (mainClips.isEmpty()) {
            val err = ExportProgressState.Error("Add at least one video or image clip to export.")
            _exportState.value = err
            return@withContext err
        }

        val exportDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }
        val ext = if (config.audioOnly) "m4a" else "mp4"
        val sanitizedTitle = project.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(28).ifBlank { "VibeCut" }
        val outFile = File(exportDir, "${sanitizedTitle}_${System.currentTimeMillis()}.$ext")

        val mainTrack = project.tracks.first { it.type == TrackType.VIDEO_MAIN }
        val targetShortSide = config.resolution.shortSidePx

        // Sequence 1: Main Video Track with GPU Effects, 3D LUTs, Overlays & Audio DSP
        val mainEditedItems = mainClips.map { clip ->
            val startPosMs = if (clip.isFrozenFrame) clip.freezeFrameSourceMs else clip.trimStartMs
            val endPosMs = if (clip.isFrozenFrame) (clip.freezeFrameSourceMs + 120L) else clip.trimEndMs
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(clip.sourceUri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startPosMs)
                        .setEndPositionMs(endPosMs.coerceAtLeast(startPosMs + 80L))
                        .build()
                )
                .build()

            val effects = ClipEffectsFactory.buildFullClipEffects(
                context = context,
                clip = clip,
                project = project,
                trackVolume = if (mainTrack.isMuted) 0f else mainTrack.volume,
                includeSceneOverlays = !config.audioOnly,
                targetExportHeight = if (config.audioOnly) null else targetShortSide,
                targetFps = if (config.audioOnly) null else config.frameRate.fps
            )

            EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(config.audioOnly)
                .setRemoveAudio(mainTrack.isMuted || clip.isMuted)
                .setFrameRate(config.frameRate.fps)
                .setEffects(effects)
                .build()
        }

        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence.Builder(mainEditedItems).build())

        // Additional Audio Sequences (Music, Voiceover, Sound FX) mixed in synchronization
        val totalTimelineUs = project.totalDurationMs * 1_000L
        val extraAudioTracks = project.tracks.filter {
            (it.type == TrackType.AUDIO_MUSIC || it.type == TrackType.AUDIO_VOICEOVER || it.type == TrackType.AUDIO_SFX) &&
                !it.isMuted && !it.isHidden && it.clips.isNotEmpty()
        }
        for (audioTrack in extraAudioTracks) {
            val seqBuilder = EditedMediaItemSequence.Builder()
            var cursorUs = 0L
            var addedAnyItem = false
            for (ac in audioTrack.clips.sortedBy { it.startTimeOnTrackMs }) {
                val startUs = ac.startTimeOnTrackMs * 1_000L
                if (startUs >= totalTimelineUs) break
                if (startUs > cursorUs) {
                    seqBuilder.addGap(startUs - cursorUs)
                    cursorUs = startUs
                }
                val maxClipDurMs = ((totalTimelineUs - cursorUs) / 1_000L).coerceAtLeast(100L)
                val endTrimMs = minOf(ac.trimEndMs, ac.trimStartMs + maxClipDurMs)
                val item = MediaItem.Builder()
                    .setUri(Uri.parse(ac.sourceUri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(ac.trimStartMs)
                            .setEndPositionMs(endTrimMs.coerceAtLeast(ac.trimStartMs + 80L))
                            .build()
                    )
                    .build()
                val audioEffects = Effects(
                    listOf(com.vibecut.pro.media.shader.VibeCutDspAudioProcessor(ac, project, audioTrack.volume)),
                    emptyList()
                )
                seqBuilder.addItem(
                    EditedMediaItem.Builder(item)
                        .setRemoveVideo(true)
                        .setEffects(audioEffects)
                        .build()
                )
                addedAnyItem = true
                cursorUs += (endTrimMs - ac.trimStartMs) * 1_000L
            }
            if (addedAnyItem) {
                sequences.add(seqBuilder.build())
            }
        }

        val composition = Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(!config.audioOnly)
            .build()

        val selectedVideoMime = if (config.codec == VideoCodecOption.H265 && isHevcHardwareSupported()) {
            MimeTypes.VIDEO_H265
        } else {
            MimeTypes.VIDEO_H264
        }

        val videoEncoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(config.targetVideoBitrateBps(project.aspectRatio))
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(videoEncoderSettings)
            .setEnableFallback(true)
            .build()

        val completionDeferred = CompletableDeferred<ExportProgressState>()

        val transformerBuilder = Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    progressPollingJob?.cancel()
                    activeTransformer = null
                    launch {
                        val savedUri = if (config.saveToGallery) {
                            withContext(Dispatchers.IO) {
                                saveExportToMediaStore(outFile, config.audioOnly)
                            }
                        } else null

                        val done = ExportProgressState.Completed(
                            outputFilePath = outFile.absolutePath,
                            galleryUri = savedUri?.toString(),
                            durationMs = exportResult.durationMs.takeIf { it > 0 } ?: project.totalDurationMs,
                            fileSizeBytes = outFile.length(),
                            audioOnly = config.audioOnly
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
                    val err = ExportProgressState.Error(
                        exportException.localizedMessage ?: "Export error (${exportException.errorCodeName})"
                    )
                    _exportState.value = err
                    completionDeferred.complete(err)
                }
            })

        if (config.audioOnly) {
            transformerBuilder.setRemoveVideo(true)
        } else {
            transformerBuilder.setVideoMimeType(selectedVideoMime)
        }

        val transformer = transformerBuilder.build()
        activeTransformer = transformer
        _exportState.value = ExportProgressState.Running(0, "Initializing MediaCodec & OpenGL ES Shaders...")

        transformer.start(composition, outFile.absolutePath)

        val progressHolder = ProgressHolder()
        progressPollingJob = launch {
            while (isActive) {
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val pct = progressHolder.progress.coerceIn(0, 99)
                    _exportState.value = ExportProgressState.Running(
                        percent = pct,
                        statusLabel = if (config.audioOnly) {
                            "Encoding AAC Master Audio ($pct%)..."
                        } else {
                            "Rendering ${config.resolution.badge} ${config.frameRate.fps}fps • ${config.codec.name} ($pct%)"
                        }
                    )
                }
                delay(180L)
            }
        }

        completionDeferred.await()
    }

    private fun saveExportToMediaStore(file: File, audioOnly: Boolean): Uri? {
        if (!file.exists()) return null
        return runCatching {
            val resolver = context.contentResolver
            val collection = if (audioOnly) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (audioOnly) "audio/mp4" else "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val dir = if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/VibeCutPro")
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

    fun buildShareIntent(outputFilePath: String, audioOnly: Boolean): Intent? {
        val file = File(outputFilePath)
        if (!file.exists()) return null
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = if (audioOnly) "audio/mp4" else "video/mp4"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_TEXT, "Edited with VibeCut Pro ✨")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

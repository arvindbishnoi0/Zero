package com.vibecut.pro.media.loader

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.vibecut.pro.engine.model.MediaKind
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import com.vibecut.pro.media.audio.MediaAudioTools
import com.vibecut.pro.media.sample.DemoClipGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

data class MediaImportProgress(
    val isImporting: Boolean = false,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val currentFileName: String = "",
    val stageLabel: String = ""
) {
    val currentItemName: String get() = currentFileName
    val stageDescription: String get() = stageLabel
    /** True while a batch import is running (drives the progress card in the importer sheet). */
    val isActive: Boolean get() = isImporting
    val fraction: Float
        get() = if (!isImporting || totalCount <= 0) 0f
        else ((currentIndex - 1).coerceAtLeast(0).toFloat() + 0.5f) / totalCount.toFloat()
}

data class DeviceMediaLibraryItem(
    val uri: String,
    val displayName: String,
    val mediaKind: MediaKind,
    val durationMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val mimeType: String = ""
)

data class MediaBatchImportResult(
    val imported: List<ImportedMediaSource>,
    val skippedNames: List<String>
) {
    val failures: List<String>
        get() = skippedNames.map { "Could not load '$it'" }
}

/**
 * Universal Media Supporter & Loader for VibeCut Pro.
 * Supports diverse video (MP4, MOV, MKV, WEBM, 3GP, AVI, TS, M4V),
 * image (JPG, PNG, WEBP, HEIC, HEIF, BMP, GIF), and
 * audio (MP3, M4A, AAC, WAV, FLAC, OGG, OPUS, AMR) sources with:
 * - URI permission persistence + local studio media caching for transient picker URIs
 * - EXIF orientation normalization & safe downscaling for high-megapixel photos
 * - GPU-pipeline-ready H.264 photo carrier generation so photos support every live GLSL shader & export
 * - Dual MediaMetadataRetriever + MediaExtractor stream inspection (audio-track presence, rotation, duration)
 * - Cached frame thumbnails & PCM audio waveform extraction
 */
@Singleton
class MediaSupportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioTools: MediaAudioTools,
    private val demoClipGenerator: DemoClipGenerator
) {
    constructor(context: Context) : this(
        context = context.applicationContext,
        audioTools = MediaAudioTools(context.applicationContext),
        demoClipGenerator = DemoClipGenerator(context.applicationContext)
    )

    private val _importProgress = MutableStateFlow(MediaImportProgress(isImporting = false))
    val importProgress: StateFlow<MediaImportProgress> = _importProgress.asStateFlow()

    companion object {
        val SUPPORTED_VIDEO_EXTENSIONS = setOf(
            "mp4", "mov", "m4v", "mkv", "webm", "3gp", "3g2", "avi", "ts", "mts", "m2ts", "flv"
        )
        val SUPPORTED_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif", "dng"
        )
        val SUPPORTED_AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "oga", "amr", "wma", "mid"
        )
    }

    suspend fun importMediaBatch(
        uris: List<Uri>,
        forceAudio: Boolean = false,
        extractWaveforms: Boolean = true,
        defaultImageDurationMs: Long = 4_000L
    ): MediaBatchImportResult = importUrisBatch(
        uris = uris,
        forceAudio = forceAudio,
        extractWaveforms = extractWaveforms,
        defaultImageDurationMs = defaultImageDurationMs
    )

    suspend fun importUrisBatch(
        uris: List<Uri>,
        forceAudio: Boolean = false,
        extractWaveforms: Boolean = true,
        defaultImageDurationMs: Long = 4_000L
    ): MediaBatchImportResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext MediaBatchImportResult(emptyList(), emptyList())

        val imported = mutableListOf<ImportedMediaSource>()
        val skipped = mutableListOf<String>()

        try {
            uris.forEachIndexed { idx, uri ->
                val rawName = queryDisplayName(uri)
                _importProgress.value = MediaImportProgress(
                    isImporting = true,
                    currentIndex = idx + 1,
                    totalCount = uris.size,
                    currentFileName = rawName,
                    stageLabel = "Inspecting media format (${idx + 1}/${uris.size})..."
                )

                val item = runCatching {
                    importSingleUriInternal(
                        uri = uri,
                        rawDisplayName = rawName,
                        index = idx + 1,
                        total = uris.size,
                        forceAudio = forceAudio,
                        extractWaveforms = extractWaveforms,
                        defaultImageDurationMs = defaultImageDurationMs
                    )
                }.getOrNull()

                if (item != null) {
                    imported.add(item)
                } else {
                    skipped.add(rawName)
                }
            }
        } finally {
            _importProgress.value = MediaImportProgress(isImporting = false)
        }

        MediaBatchImportResult(imported = imported, skippedNames = skipped)
    }

    suspend fun importSingleUri(
        uri: Uri,
        forceAudio: Boolean = false,
        extractWaveform: Boolean = true,
        defaultImageDurationMs: Long = 4_000L
    ): Result<ImportedMediaSource> = withContext(Dispatchers.IO) {
        val batch = importUrisBatch(
            uris = listOf(uri),
            forceAudio = forceAudio,
            extractWaveforms = extractWaveform,
            defaultImageDurationMs = defaultImageDurationMs
        )
        val first = batch.imported.firstOrNull()
        if (first != null) {
            Result.success(first)
        } else {
            Result.failure(IllegalArgumentException(batch.failures.firstOrNull() ?: "Unsupported media"))
        }
    }

    private suspend fun importSingleUriInternal(
        uri: Uri,
        rawDisplayName: String,
        index: Int,
        total: Int,
        forceAudio: Boolean,
        extractWaveforms: Boolean,
        defaultImageDurationMs: Long
    ): ImportedMediaSource? {
        val resolver = context.contentResolver
        val persistedOk = tryPersistReadPermission(uri)

        val rawMime = resolver.getType(uri).orEmpty().lowercase(Locale.US)
        val ext = extractExtension(rawDisplayName, uri, rawMime)
        val detectedKind = if (forceAudio) MediaKind.AUDIO else classifyMediaKind(uri, rawMime, ext)

        return when (detectedKind) {
            MediaKind.IMAGE -> {
                _importProgress.value = MediaImportProgress(
                    isImporting = true,
                    currentIndex = index,
                    totalCount = total,
                    currentFileName = rawDisplayName,
                    stageLabel = "Normalizing EXIF & preparing GPU photo carrier..."
                )
                prepareImageSource(uri, rawDisplayName, defaultImageDurationMs)
            }

            MediaKind.AUDIO -> {
                _importProgress.value = MediaImportProgress(
                    isImporting = true,
                    currentIndex = index,
                    totalCount = total,
                    currentFileName = rawDisplayName,
                    stageLabel = "Loading audio stream & waveform..."
                )
                val stableUri = ensureStableMediaUri(uri, rawDisplayName, ext.ifBlank { "m4a" }, persistedOk)
                prepareAudioSource(stableUri, rawDisplayName, rawMime, extractWaveforms)
            }

            else -> {
                _importProgress.value = MediaImportProgress(
                    isImporting = true,
                    currentIndex = index,
                    totalCount = total,
                    currentFileName = rawDisplayName,
                    stageLabel = "Inspecting video codec, audio & thumbnail..."
                )
                val stableUri = ensureStableMediaUri(uri, rawDisplayName, ext.ifBlank { "mp4" }, persistedOk)
                prepareVideoSource(stableUri, rawDisplayName, rawMime, extractWaveforms)
            }
        }
    }

    private fun prepareImageSource(
        uri: Uri,
        displayName: String,
        defaultDurationMs: Long
    ): ImportedMediaSource? {
        val mediaDir = File(context.filesDir, "studio_media").apply { mkdirs() }
        val hash = stableShortHash("${uri}_$displayName")
        val normalizedJpg = File(mediaDir, "img_$hash.jpg")
        val carrierMp4 = File(mediaDir, "img_carrier_$hash.mp4")

        val decoded = decodeAndNormalizeBitmap(uri, maxLongSide = 1920) ?: return null
        val imgW = decoded.width
        val imgH = decoded.height

        try {
            if (!normalizedJpg.exists() || normalizedJpg.length() < 512L) {
                normalizedJpg.outputStream().use { out ->
                    decoded.compress(Bitmap.CompressFormat.JPEG, 94, out)
                }
            }
            val encodedCarrier = if (carrierMp4.exists() && carrierMp4.length() > 1024L) {
                true
            } else {
                demoClipGenerator.encodeBitmapToMp4(
                    bitmap = decoded,
                    outputFile = carrierMp4,
                    durationMs = 12_000L,
                    fps = 10
                )
            }

            val thumbUriStr = Uri.fromFile(normalizedJpg).toString()
            return if (encodedCarrier && carrierMp4.exists() && carrierMp4.length() > 1024L) {
                ImportedMediaSource(
                    uri = Uri.fromFile(carrierMp4).toString(),
                    displayName = displayName,
                    mediaKind = MediaKind.VIDEO,
                    durationMs = defaultDurationMs.coerceIn(500L, 12_000L),
                    width = imgW,
                    height = imgH,
                    waveformPeaks = emptyList(),
                    hasAudioTrack = false,
                    rotationDegreesMeta = 0,
                    mimeType = "video/mp4",
                    thumbnailUri = thumbUriStr,
                    fileSizeBytes = normalizedJpg.length()
                )
            } else {
                ImportedMediaSource(
                    uri = thumbUriStr,
                    displayName = displayName,
                    mediaKind = MediaKind.IMAGE,
                    durationMs = defaultDurationMs.coerceAtLeast(500L),
                    width = imgW,
                    height = imgH,
                    waveformPeaks = emptyList(),
                    hasAudioTrack = false,
                    rotationDegreesMeta = 0,
                    mimeType = "image/jpeg",
                    thumbnailUri = thumbUriStr,
                    fileSizeBytes = normalizedJpg.length()
                )
            }
        } finally {
            decoded.recycle()
        }
    }

    private suspend fun prepareVideoSource(
        uri: Uri,
        displayName: String,
        fallbackMime: String,
        extractWaveforms: Boolean
    ): ImportedMediaSource? {
        var durationMs = 0L
        var width = 1080
        var height = 1920
        var rotation = 0
        var hasAudio = false
        var mime = fallbackMime.ifBlank { "video/mp4" }
        var thumbUriStr: String? = null

        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val hasAudioMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            hasAudio = hasAudioMeta.equals("yes", ignoreCase = true) || hasAudioMeta == "true"
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.takeIf { it.isNotBlank() }?.let {
                mime = it
            }

            // Generate cached thumbnail frame
            val thumbsDir = File(context.filesDir, "studio_thumbs").apply { mkdirs() }
            val thumbFile = File(thumbsDir, "vid_${stableShortHash(uri.toString())}.jpg")
            if (!thumbFile.exists() || thumbFile.length() < 256L) {
                val frameBmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(150_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 640)
                } else {
                    retriever.getFrameAtTime(150_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                if (frameBmp != null) {
                    runCatching {
                        thumbFile.outputStream().use { frameBmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    }
                    frameBmp.recycle()
                }
            }
            if (thumbFile.exists() && thumbFile.length() > 256L) {
                thumbUriStr = Uri.fromFile(thumbFile).toString()
            }
        } catch (_: Exception) {
            // Fall through to MediaExtractor stream probe
        } finally {
            runCatching { retriever.release() }
        }

        // Secondary MediaExtractor stream probe for MKV / WEBM / AVI / TS or missing audio flags
        val extractor = MediaExtractor()
        var foundAnyTrack = false
        try {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                extractor.setDataSource(uri.path!!)
            } else {
                extractor.setDataSource(context, uri, null)
            }
            foundAnyTrack = extractor.trackCount > 0
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val trackMime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (trackMime.startsWith("audio/")) {
                    hasAudio = true
                }
                if (trackMime.startsWith("video/")) {
                    if (width <= 0 && fmt.containsKey(MediaFormat.KEY_WIDTH)) {
                        width = fmt.getInteger(MediaFormat.KEY_WIDTH)
                    }
                    if (height <= 0 && fmt.containsKey(MediaFormat.KEY_HEIGHT)) {
                        height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    if (durationMs <= 0L && fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMs = fmt.getLong(MediaFormat.KEY_DURATION) / 1000L
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore if already inspected via retriever
        } finally {
            runCatching { extractor.release() }
        }

        if (durationMs <= 0L && !foundAnyTrack) {
            // Verify URI is at least readable
            val readable = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } ?: false
            }.getOrDefault(false)
            if (!readable && uri.scheme != "file") return null
            durationMs = 4_000L
        }

        val effectiveW = if (rotation == 90 || rotation == 270) height else width
        val effectiveH = if (rotation == 90 || rotation == 270) width else height

        val peaks = if (extractWaveforms && hasAudio) {
            runCatching { audioTools.analyzeAudioTrack(uri.toString(), maxDecodedSeconds = 25).beatAnalysis.peakEnvelope }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }

        return ImportedMediaSource(
            uri = uri.toString(),
            displayName = displayName,
            mediaKind = MediaKind.VIDEO,
            durationMs = durationMs.coerceAtLeast(300L),
            width = effectiveW.coerceAtLeast(64),
            height = effectiveH.coerceAtLeast(64),
            waveformPeaks = peaks,
            hasAudioTrack = hasAudio,
            rotationDegreesMeta = rotation,
            mimeType = mime,
            thumbnailUri = thumbUriStr
        )
    }

    private suspend fun prepareAudioSource(
        uri: Uri,
        displayName: String,
        fallbackMime: String,
        extractWaveforms: Boolean
    ): ImportedMediaSource? {
        var durationMs = 0L
        var mime = fallbackMime.ifBlank { "audio/mpeg" }

        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.takeIf { it.isNotBlank() }?.let {
                mime = it
            }
        } catch (_: Exception) {
            // Fallback to MediaExtractor
        } finally {
            runCatching { retriever.release() }
        }

        if (durationMs <= 0L) {
            val extractor = MediaExtractor()
            try {
                if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                    extractor.setDataSource(uri.path!!)
                } else {
                    extractor.setDataSource(context, uri, null)
                }
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMs = max(durationMs, fmt.getLong(MediaFormat.KEY_DURATION) / 1000L)
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { extractor.release() }
            }
        }

        val peaks = if (extractWaveforms) {
            runCatching { audioTools.analyzeAudioTrack(uri.toString(), maxDecodedSeconds = 30).beatAnalysis.peakEnvelope }
                .getOrDefault(emptyList())
        } else emptyList()

        return ImportedMediaSource(
            uri = uri.toString(),
            displayName = displayName,
            mediaKind = MediaKind.AUDIO,
            durationMs = durationMs.coerceAtLeast(1_000L),
            width = 0,
            height = 0,
            waveformPeaks = peaks,
            hasAudioTrack = true,
            mimeType = mime
        )
    }

    /**
     * Scans device MediaStore for Videos, Photos, and Audio tracks so the in-app Media Browser
     * supports instant multi-tab selection.
     */
    suspend fun scanDeviceMediaLibrary(maxPerCategory: Int = 60): List<DeviceMediaLibraryItem> = withContext(Dispatchers.IO) {
        val limitPerKind = maxPerCategory
        val results = mutableListOf<DeviceMediaLibraryItem>()
        val resolver = context.contentResolver

        // 1. Videos
        runCatching {
            val proj = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.MIME_TYPE
            )
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                proj,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val szCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                var count = 0
                while (c.moveToNext() && count < limitPerKind) {
                    val id = c.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    val dur = c.getLong(durCol)
                    if (dur >= 200L) {
                        results.add(
                            DeviceMediaLibraryItem(
                                uri = contentUri.toString(),
                                displayName = c.getString(nameCol) ?: "Video_$id.mp4",
                                mediaKind = MediaKind.VIDEO,
                                durationMs = dur,
                                width = c.getInt(wCol),
                                height = c.getInt(hCol),
                                sizeBytes = c.getLong(szCol),
                                mimeType = c.getString(mimeCol).orEmpty()
                            )
                        )
                        count++
                    }
                }
            }
        }

        // 2. Photos / Images
        runCatching {
            val proj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE
            )
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                proj,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val szCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                var count = 0
                while (c.moveToNext() && count < limitPerKind) {
                    val id = c.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    results.add(
                        DeviceMediaLibraryItem(
                            uri = contentUri.toString(),
                            displayName = c.getString(nameCol) ?: "Photo_$id.jpg",
                            mediaKind = MediaKind.IMAGE,
                            durationMs = 4_000L,
                            width = c.getInt(wCol),
                            height = c.getInt(hCol),
                            sizeBytes = c.getLong(szCol),
                            mimeType = c.getString(mimeCol).orEmpty()
                        )
                    )
                    count++
                }
            }
        }

        // 3. Audio / Music
        runCatching {
            val proj = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE
            )
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                proj,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val szCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                var count = 0
                while (c.moveToNext() && count < limitPerKind) {
                    val id = c.getLong(idCol)
                    val dur = c.getLong(durCol)
                    if (dur >= 500L) {
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        results.add(
                            DeviceMediaLibraryItem(
                                uri = contentUri.toString(),
                                displayName = c.getString(nameCol) ?: "Audio_$id.mp3",
                                mediaKind = MediaKind.AUDIO,
                                durationMs = dur,
                                sizeBytes = c.getLong(szCol),
                                mimeType = c.getString(mimeCol).orEmpty()
                            )
                        )
                        count++
                    }
                }
            }
        }

        results
    }

    private fun tryPersistReadPermission(uri: Uri): Boolean {
        if (uri.scheme != "content") return true
        // MediaStore top-level Uris are persistent once media permission is granted
        val authority = uri.authority.orEmpty()
        if (authority == MediaStore.AUTHORITY && !uri.path.orEmpty().contains("picker")) {
            return true
        }
        return runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Copies transient picker URIs (such as Android Photo Picker `content://media/picker/...` or
     * WhatsApp/Downloads attachments that do not grant persistable permissions) into local studio
     * storage so project reloads and WorkManager background exports never fail with SecurityException.
     */
    private fun ensureStableMediaUri(
        uri: Uri,
        displayName: String,
        defaultExt: String,
        persistedOk: Boolean
    ): Uri {
        if (uri.scheme == "file" || persistedOk) return uri
        return runCatching {
            val dir = File(context.filesDir, "studio_media").apply { mkdirs() }
            val safeExt = defaultExt.lowercase(Locale.US).take(6).ifBlank { "mp4" }
            val dest = File(dir, "media_${stableShortHash("${uri}_$displayName")}.$safeExt")
            if (!dest.exists() || dest.length() == 0L) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out, bufferSize = 64 * 1024) }
                }
            }
            if (dest.exists() && dest.length() > 0L) Uri.fromFile(dest) else uri
        }.getOrDefault(uri)
    }

    private fun decodeAndNormalizeBitmap(uri: Uri, maxLongSide: Int = 1920): Bitmap? {
        val resolver = context.contentResolver
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                BitmapFactory.decodeFile(uri.path, boundsOpts)
            } else {
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            }
        }
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var sampleSize = 1
        while ((srcW / sampleSize) > maxLongSide * 2 || (srcH / sampleSize) > maxLongSide * 2) {
            sampleSize *= 2
        }

        val exifOrientation = runCatching {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                ExifInterface(uri.path!!).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } else {
                resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val rawBmp = runCatching {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                BitmapFactory.decodeFile(uri.path, decodeOpts)
            } else {
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            }
        }.getOrNull() ?: return null

        val matrix = Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }

        val oriented = if (!matrix.isIdentity) {
            val b = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
            if (b !== rawBmp) rawBmp.recycle()
            b
        } else {
            rawBmp
        }

        val longSide = max(oriented.width, oriented.height)
        if (longSide <= maxLongSide && oriented.width % 2 == 0 && oriented.height % 2 == 0) {
            return oriented
        }
        val scale = min(1f, maxLongSide.toFloat() / longSide.toFloat())
        val dstW = max(64, ((oriented.width * scale).toInt() / 2) * 2)
        val dstH = max(64, ((oriented.height * scale).toInt() / 2) * 2)
        val scaled = Bitmap.createScaledBitmap(oriented, dstW, dstH, true)
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    private fun queryDisplayName(uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.lastPathSegment ?: "Media_Clip"
        }
        var found: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (col >= 0) found = c.getString(col)
                }
            }
        }
        return found?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "Media_Clip"
    }

    private fun extractExtension(displayName: String, uri: Uri, mimeType: String): String {
        val fromName = displayName.substringAfterLast('.', "").lowercase(Locale.US)
        if (fromName.length in 2..5 && fromName.all { it.isLetterOrDigit() }) return fromName

        val fromPath = uri.path.orEmpty().substringAfterLast('.', "").lowercase(Locale.US)
        if (fromPath.length in 2..5 && fromPath.all { it.isLetterOrDigit() }) return fromPath

        if (mimeType.isNotBlank()) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.let { return it.lowercase(Locale.US) }
        }
        return ""
    }

    private fun classifyMediaKind(uri: Uri, mime: String, ext: String): MediaKind {
        if (mime.startsWith("image/") || ext in SUPPORTED_IMAGE_EXTENSIONS) return MediaKind.IMAGE
        if (mime.startsWith("audio/") || ext in SUPPORTED_AUDIO_EXTENSIONS) return MediaKind.AUDIO
        if (mime.startsWith("video/") || ext in SUPPORTED_VIDEO_EXTENSIONS) return MediaKind.VIDEO

        // Probe unknown / application/octet-stream URIs cleanly
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) return MediaKind.IMAGE

        val extractor = MediaExtractor()
        try {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                extractor.setDataSource(uri.path!!)
            } else {
                extractor.setDataSource(context, uri, null)
            }
            var sawAudio = false
            for (i in 0 until extractor.trackCount) {
                val trackMime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (trackMime.startsWith("video/")) return MediaKind.VIDEO
                if (trackMime.startsWith("audio/")) sawAudio = true
            }
            if (sawAudio) return MediaKind.AUDIO
        } catch (_: Exception) {
        } finally {
            runCatching { extractor.release() }
        }

        return MediaKind.VIDEO
    }

    private fun stableShortHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

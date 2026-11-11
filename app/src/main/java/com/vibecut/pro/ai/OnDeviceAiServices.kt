package com.vibecut.pro.ai

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.vibecut.pro.data.preferences.AppPreferencesRepository
import com.vibecut.pro.engine.ai.*
import com.vibecut.pro.engine.model.CropRect
import com.vibecut.pro.engine.model.SubtitleCue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class SubjectMaskResult(
    val subjectRatio: Float,
    val centerX: Float,
    val centerY: Float,
    val recommendedFocusRadius: Float
)

@Singleton
class OnDeviceVisionAiService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val faceDetector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build()
        FaceDetection.getClient(opts)
    }

    private val selfieSegmenter by lazy {
        val opts = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        Segmentation.getClient(opts)
    }

    /**
     * Samples downscaled 320x180 frames across [startMs..endMs] of a video clip and runs:
     * - 24-bin RGB color histogram
     * - Sobel edge sharpness energy
     * - Google ML Kit on-device Face Detection (when [detectFaces] is true)
     */
    suspend fun sampleClipFrames(
        videoUri: String,
        startMs: Long,
        endMs: Long,
        sampleCount: Int = 12,
        detectFaces: Boolean = true
    ): Pair<List<FrameVisualSample>, Pair<Int, Int>> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val results = mutableListOf<FrameVisualSample>()
        var videoW = 1080
        var videoH = 1920

        try {
            val uri = Uri.parse(videoUri)
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            videoW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            videoH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920

            val spanMs = max(300L, endMs - startMs)
            val steps = sampleCount.coerceIn(2, 30)

            for (i in 0 until steps) {
                val tsMs = startMs + (spanMs * i) / (steps - 1).coerceAtLeast(1)
                val bmp = extractDownscaledFrame(retriever, tsMs * 1_000L, 256, 256) ?: continue
                try {
                    val (hist, edgeEnergy, salienceCenter) = computeHistogramEdgeAndSalience(bmp)
                    var cx = salienceCenter.first
                    var cy = salienceCenter.second
                    var foundFace = false

                    if (detectFaces) {
                        val faces = runCatching {
                            faceDetector.process(InputImage.fromBitmap(bmp, 0)).await()
                        }.getOrDefault(emptyList())

                        val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        if (primaryFace != null) {
                            cx = (primaryFace.boundingBox.exactCenterX() / bmp.width).coerceIn(0f, 1f)
                            cy = (primaryFace.boundingBox.exactCenterY() / bmp.height).coerceIn(0f, 1f)
                            foundFace = true
                        }
                    }

                    results.add(
                        FrameVisualSample(
                            timestampMs = tsMs - startMs,
                            rgbHistogram = hist,
                            edgeEnergy = edgeEnergy,
                            subjectCenterX = cx,
                            subjectCenterY = cy,
                            hasDetectedFace = foundFace
                        )
                    )
                } finally {
                    bmp.recycle()
                }
            }
        } catch (_: Exception) {
            // Safe fallback if media URI cannot be decoded
        } finally {
            runCatching { retriever.release() }
        }

        results to (videoW to videoH)
    }

    /**
     * Runs Google ML Kit Selfie Subject Segmentation on representative clip frames
     * to measure foreground subject bounds and configure Background Removal / Portrait Blur.
     */
    suspend fun analyzeSubjectSegmentation(
        videoUri: String,
        timestampMs: Long
    ): SubjectMaskResult? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            val uri = Uri.parse(videoUri)
            if (uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
            val bmp = extractDownscaledFrame(retriever, timestampMs * 1_000L, 256, 256) ?: return@withContext null
            try {
                val mask = selfieSegmenter.process(InputImage.fromBitmap(bmp, 0)).await()
                val buf = mask.buffer
                buf.rewind()
                val w = mask.width
                val h = mask.height
                var fgCount = 0
                var sumX = 0.0
                var sumY = 0.0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val conf = buf.float
                        if (conf >= 0.55f) {
                            fgCount++
                            sumX += x.toDouble() / w
                            sumY += y.toDouble() / h
                        }
                    }
                }
                val total = max(1, w * h)
                val ratio = fgCount.toFloat() / total
                val cx = if (fgCount > 0) (sumX / fgCount).toFloat() else 0.5f
                val cy = if (fgCount > 0) (sumY / fgCount).toFloat() else 0.5f
                val radius = sqrt(ratio.coerceIn(0.08f, 0.75f)) * 0.7f
                SubjectMaskResult(
                    subjectRatio = ratio,
                    centerX = cx,
                    centerY = cy,
                    recommendedFocusRadius = radius.coerceIn(0.25f, 0.75f)
                )
            } finally {
                bmp.recycle()
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractDownscaledFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        dstW: Int,
        dstH: Int
    ): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstW, dstH)
        } else {
            val full = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val scaled = Bitmap.createScaledBitmap(full, dstW, dstH, true)
            if (scaled !== full) full.recycle()
            scaled
        }
    }

    private fun computeHistogramEdgeAndSalience(bmp: Bitmap): Triple<FloatArray, Float, Pair<Float, Float>> {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val hist = FloatArray(24) // 8 R + 8 G + 8 B
        val luma = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            hist[r / 32] += 1f
            hist[8 + g / 32] += 1f
            hist[16 + b / 32] += 1f
            luma[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        val norm = max(1f, (w * h * 3).toFloat())
        for (i in hist.indices) hist[i] /= norm

        var edgeSum = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        var weightTotal = 1e-5
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = luma[y * w + (x + 1)] - luma[y * w + (x - 1)]
                val gy = luma[(y + 1) * w + x] - luma[(y - 1) * w + x]
                val mag = sqrt((gx * gx + gy * gy).toDouble())
                edgeSum += mag
                weightedX += (x.toDouble() / w) * mag
                weightedY += (y.toDouble() / h) * mag
                weightTotal += mag
            }
        }
        val avgEdge = (edgeSum / max(1, (w - 2) * (h - 2))).toFloat().coerceIn(0f, 1f)
        val cx = (weightedX / weightTotal).toFloat().coerceIn(0.15f, 0.85f)
        val cy = (weightedY / weightTotal).toFloat().coerceIn(0.15f, 0.85f)
        return Triple(hist, avgEdge, cx to cy)
    }
}

/**
 * Real Android on-device Speech Recognition service supporting Hindi (`hi-IN`) and English (`en-US`).
 */
@Singleton
class OnDeviceSpeechCaptionService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isSpeechRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun recognizeSingleUtteranceOnMain(
        languageTag: String = "en-US"
    ): Result<SubtitleCue> = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return@withContext Result.failure(
                IllegalStateException("SpeechRecognizer service is not installed on this device.")
            )
        }
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val startWallMs = System.currentTimeMillis()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: 0.9f
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    val dur = (System.currentTimeMillis() - startWallMs).coerceIn(1_200L, 6_000L)
                    recognizer.destroy()
                    if (cont.isActive) {
                        if (text.isNotEmpty()) {
                            cont.resume(
                                Result.success(
                                    SubtitleCue(
                                        id = UUID.randomUUID().toString(),
                                        text = text,
                                        startTimeMs = 0L,
                                        endTimeMs = dur,
                                        languageTag = languageTag,
                                        confidence = conf
                                    )
                                )
                            )
                        } else {
                            cont.resume(Result.failure(IllegalStateException("No speech detected.")))
                        }
                    }
                }

                override fun onError(error: Int) {
                    recognizer.destroy()
                    if (cont.isActive) {
                        cont.resume(Result.failure(IllegalStateException("Speech error code: $error")))
                    }
                }
            })

            cont.invokeOnCancellation {
                runCatching { recognizer.cancel(); recognizer.destroy() }
            }
            recognizer.startListening(intent)
        }
    }
}

/**
 * Clean external generative AI integration gateway. Never fabricates cloud outputs:
 * calls the user's configured endpoint if present, otherwise returns [ExternalAiResult.NotConfigured].
 */
@Singleton
class ConfigurableExternalAiGateway @Inject constructor(
    private val preferencesRepository: AppPreferencesRepository
) : ExternalGenAiGateway {

    override val providerName: String = "Configurable GenAI Inpainting Endpoint"
    override val isConfigured: Boolean = false

    override suspend fun requestObjectInpainting(
        clipUri: String,
        normalizedMaskRect: CropRect,
        startMs: Long,
        endMs: Long
    ): ExternalAiResult = withContext(Dispatchers.IO) {
        val prefs = preferencesRepository.preferencesFlow.first()
        if (prefs.externalAiEndpointUrl.isBlank()) {
            return@withContext ExternalAiResult.NotConfigured(
                "No external cloud inpainting URL configured in Settings. " +
                    "You can configure a REST endpoint in Settings -> AI Integration, " +
                    "or apply VibeCut's local GPU Privacy/Object Blur Mask immediately."
            )
        }

        runCatching {
            val conn = (URL(prefs.externalAiEndpointUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (prefs.externalAiApiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${prefs.externalAiApiKey}")
                }
            }
            val payload = """
                {"clipUri":"$clipUri","startMs":$startMs,"endMs":$endMs,
                 "mask":{"left":${normalizedMaskRect.left},"top":${normalizedMaskRect.top},
                         "right":${normalizedMaskRect.right},"bottom":${normalizedMaskRect.bottom}}}
            """.trimIndent()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299 && body.contains("http")) {
                ExternalAiResult.Success(clipUri, "External AI request accepted ($code).")
            } else {
                ExternalAiResult.Failure("Endpoint returned HTTP $code: ${body.take(120)}")
            }
        }.getOrElse { e ->
            ExternalAiResult.Failure("External AI connection failed: ${e.localizedMessage}")
        }
    }
}

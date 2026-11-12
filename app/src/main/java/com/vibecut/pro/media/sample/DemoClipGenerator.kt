package com.vibecut.pro.media.sample

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.view.Surface
import com.vibecut.pro.engine.model.MediaKind
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Generates hardware-encoded H.264 MP4 video clips using [MediaCodec] Surface input + [MediaMuxer],
 * plus converts still photos / studio canvases into GPU-pipeline-ready H.264 MP4 carriers.
 */
@Singleton
class DemoClipGenerator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun generateSampleVideoClips(): List<ImportedMediaSource> = withContext(Dispatchers.IO) {
        val outDir = File(context.filesDir, "demo_clips").apply { mkdirs() }
        val specs = listOf(
            Triple("Neon_City_Scene_v2.mp4", 0xFF00E5FF.toInt() to 0xFF7C4DFF.toInt(), 4_000L),
            Triple("Golden_Sunset_Run_v2.mp4", 0xFFFF9100.toInt() to 0xFFFF1744.toInt(), 3_500L),
            Triple("Cyber_Studio_Beat_v2.mp4", 0xFF00E676.toInt() to 0xFF2979FF.toInt(), 4_000L)
        )

        specs.mapIndexedNotNull { idx, (fileName, colors, durationMs) ->
            val target = File(outDir, fileName)
            if (!target.exists() || target.length() < 1024L) {
                val ok = encodeSyntheticMp4(
                    outputFile = target,
                    width = 720,
                    height = 1280,
                    fps = 30,
                    durationMs = durationMs,
                    primaryColor = colors.first,
                    secondaryColor = colors.second,
                    sceneNumber = idx + 1
                )
                if (!ok) return@mapIndexedNotNull null
            }
            val peaks = List(60) { i ->
                (0.25f + 0.7f * kotlin.math.abs(sin((i + idx * 7) * 0.45))).toFloat()
            }
            ImportedMediaSource(
                uri = Uri.fromFile(target).toString(),
                displayName = fileName.removeSuffix("_v2.mp4").replace('_', ' '),
                mediaKind = MediaKind.VIDEO,
                durationMs = durationMs,
                width = 720,
                height = 1280,
                waveformPeaks = peaks,
                hasAudioTrack = false,
                mimeType = "video/mp4",
                fileSizeBytes = target.length()
            )
        }
    }

    /**
     * Generates a solid or gradient Studio Canvas clip (for intros, titles, B-roll backdrops).
     */
    suspend fun generateStudioCanvasClip(
        title: String,
        primaryColorArgb: Int = 0xFF141828.toInt(),
        secondaryColorArgb: Int = 0xFF00B8D4.toInt(),
        width: Int = 1080,
        height: Int = 1920,
        durationMs: Long = 5_000L
    ): ImportedMediaSource = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "studio_canvases").apply { mkdirs() }
        val safeSlug = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase()
        val outMp4 = File(dir, "canvas_${safeSlug}_${width}x${height}.mp4")
        val outThumb = File(dir, "canvas_${safeSlug}_thumb.jpg")

        val safeW = ((width + 8) / 16) * 16
        val safeH = ((height + 8) / 16) * 16

        var mp4Ready = outMp4.exists() && outMp4.length() >= 1024L
        val bmp = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, safeW.toFloat(), safeH.toFloat(),
                    primaryColorArgb, secondaryColorArgb,
                    Shader.TileMode.CLAMP
                )
            }
            c.drawRect(0f, 0f, safeW.toFloat(), safeH.toFloat(), p)
            outThumb.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }

            if (!mp4Ready) {
                mp4Ready = encodeBitmapToMp4(bmp, outMp4, durationMs = 10_000L, fps = 10)
            }
        } finally {
            bmp.recycle()
        }

        if (mp4Ready) {
            ImportedMediaSource(
                uri = Uri.fromFile(outMp4).toString(),
                displayName = title,
                mediaKind = MediaKind.VIDEO,
                durationMs = durationMs,
                width = safeW,
                height = safeH,
                hasAudioTrack = false,
                mimeType = "video/mp4",
                thumbnailUri = Uri.fromFile(outThumb).toString(),
                fileSizeBytes = outMp4.length()
            )
        } else {
            ImportedMediaSource(
                uri = Uri.fromFile(outThumb).toString(),
                displayName = title,
                mediaKind = MediaKind.IMAGE,
                durationMs = durationMs,
                width = safeW,
                height = safeH,
                hasAudioTrack = false,
                mimeType = "image/jpeg",
                thumbnailUri = Uri.fromFile(outThumb).toString(),
                fileSizeBytes = outThumb.length()
            )
        }
    }

    /**
     * Encodes a normalized still [Bitmap] into a clean 16px-aligned H.264 MP4 carrier stream
     * so imported photos (JPG, PNG, WEBP, HEIC, BMP) execute through the full OpenGL ES 2.0
     * shader, LUT, transition, keyframe, preview & export pipeline with zero format errors.
     */
    fun encodeBitmapToMp4(
        bitmap: Bitmap,
        outputFile: File,
        durationMs: Long = 12_000L,
        fps: Int = 10
    ): Boolean {
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        val safeW = max(64, ((bitmap.width + 8) / 16) * 16).coerceAtMost(1920)
        val safeH = max(64, ((bitmap.height + 8) / 16) * 16).coerceAtMost(1920)

        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, safeW, safeH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var videoTrackIndex = -1
            var emittedFrames = 0L

            val totalFrames = ((durationMs * fps) / 1000L).toInt().coerceAtLeast(10)
            val dstRect = Rect(0, 0, safeW, safeH)
            val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

            for (frameIdx in 0..totalFrames) {
                if (frameIdx < totalFrames) {
                    val surface = inputSurface
                    val canvas = runCatching { surface.lockHardwareCanvas() }
                        .getOrElse { surface.lockCanvas(null) }
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(bitmap, null, dstRect, drawPaint)
                    surface.unlockCanvasAndPost(canvas)
                } else {
                    encoder.signalEndOfInputStream()
                }

                while (true) {
                    val status = encoder.dequeueOutputBuffer(bufferInfo, 12_000L)
                    if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (frameIdx < totalFrames) break
                    } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (status >= 0) {
                        val encodedData = encoder.getOutputBuffer(status)
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted && !isConfig) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            bufferInfo.presentationTimeUs = (emittedFrames * 1_000_000L) / fps
                            emittedFrames++
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(status, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }
            muxerStarted && emittedFrames > 0L
        } catch (_: Exception) {
            false
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun encodeSyntheticMp4(
        outputFile: File,
        width: Int,
        height: Int,
        fps: Int,
        durationMs: Long,
        primaryColor: Int,
        secondaryColor: Int,
        sceneNumber: Int
    ): Boolean {
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var videoTrackIndex = -1
            var emittedFrames = 0L

            val totalFrames = ((durationMs * fps) / 1000L).toInt().coerceAtLeast(15)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 58f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xDDFFFFFF.toInt()
                textSize = 30f
                typeface = Typeface.MONOSPACE
                textAlign = Paint.Align.CENTER
            }

            for (frameIdx in 0..totalFrames) {
                if (frameIdx < totalFrames) {
                    val surface = inputSurface
                    val canvas = runCatching { surface.lockHardwareCanvas() }
                        .getOrElse { surface.lockCanvas(null) }
                    val t = frameIdx.toFloat() / totalFrames
                    paint.shader = LinearGradient(
                        0f,
                        height * t * 0.4f,
                        width.toFloat(),
                        height.toFloat(),
                        primaryColor,
                        secondaryColor,
                        Shader.TileMode.MIRROR
                    )
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                    paint.shader = null

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3f
                    paint.color = 0x44FFFFFF
                    for (gx in 80 until width step 120) {
                        canvas.drawLine(gx.toFloat(), 0f, gx.toFloat(), height.toFloat(), paint)
                    }
                    for (gy in 100 until height step 120) {
                        canvas.drawLine(0f, gy.toFloat(), width.toFloat(), gy.toFloat(), paint)
                    }

                    val orbX = width * (0.5f + 0.28f * cos(2.0 * PI * t).toFloat())
                    val orbY = height * (0.46f + 0.16f * sin(4.0 * PI * t).toFloat())
                    paint.style = Paint.Style.FILL
                    paint.color = Color.WHITE
                    canvas.drawCircle(orbX, orbY, 72f, paint)
                    paint.color = primaryColor
                    canvas.drawCircle(orbX, orbY, 44f, paint)

                    canvas.drawText("SCENE 0$sceneNumber", width / 2f, height * 0.76f, titlePaint)
                    canvas.drawText(
                        String.format("FRAME %03d / %03d", frameIdx + 1, totalFrames),
                        width / 2f,
                        height * 0.81f,
                        subPaint
                    )
                    surface.unlockCanvasAndPost(canvas)
                } else {
                    encoder.signalEndOfInputStream()
                }

                while (true) {
                    val status = encoder.dequeueOutputBuffer(bufferInfo, 12_000L)
                    if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (frameIdx < totalFrames) break
                    } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (status >= 0) {
                        val encodedData = encoder.getOutputBuffer(status)
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted && !isConfig) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            bufferInfo.presentationTimeUs = (emittedFrames * 1_000_000L) / fps
                            emittedFrames++
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(status, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }
            muxerStarted && emittedFrames > 0L
        } catch (_: Exception) {
            false
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}

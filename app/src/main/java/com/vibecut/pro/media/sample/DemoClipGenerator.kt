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
import kotlin.math.sin

/**
 * Generates genuine hardware-encoded H.264 MP4 video clips using [MediaCodec] Surface input + [MediaMuxer].
 * Ensures users can immediately test real multi-track editing, GPU shaders, splitting, sharpening,
 * transitions, and 4K export even on a brand-new emulator with an empty gallery.
 */
@Singleton
class DemoClipGenerator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun generateSampleVideoClips(): List<ImportedMediaSource> = withContext(Dispatchers.IO) {
        val outDir = File(context.filesDir, "demo_clips").apply { mkdirs() }
        val specs = listOf(
            Triple("Neon_City_Scene.mp4", 0xFF00E5FF.toInt() to 0xFF7C4DFF.toInt(), 4_000L),
            Triple("Golden_Sunset_Run.mp4", 0xFFFF9100.toInt() to 0xFFFF1744.toInt(), 3_500L),
            Triple("Cyber_Studio_Beat.mp4", 0xFF00E676.toInt() to 0xFF2979FF.toInt(), 4_000L)
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
                displayName = fileName.removeSuffix(".mp4").replace('_', ' '),
                mediaKind = MediaKind.VIDEO,
                durationMs = durationMs,
                width = 720,
                height = 1280,
                waveformPeaks = peaks
            )
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
                    val canvas = inputSurface.lockHardwareCanvas()
                    val t = frameIdx.toFloat() / totalFrames
                    // Animated diagonal gradient
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

                    // Crisp geometric grid & moving subject orb (ideal for testing Sharpen, Motion Tracking & Blur!)
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
                    inputSurface.unlockCanvasAndPost(canvas)
                } else {
                    encoder.signalEndOfInputStream()
                }

                // Drain encoded H.264 NAL packets
                while (true) {
                    val status = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                    if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (frameIdx < totalFrames) break
                    } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (status >= 0) {
                        val encodedData = encoder.getOutputBuffer(status)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                bufferInfo.presentationTimeUs = (frameIdx * 1_000_000L) / fps
                                muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(status, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }
            true
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

package com.vibecut.pro.media.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import com.vibecut.pro.engine.audio.AudioDspEngine
import com.vibecut.pro.engine.audio.BeatAnalysisResult
import com.vibecut.pro.engine.audio.BuiltInSfxPreset
import com.vibecut.pro.engine.subtitles.SubtitleCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

data class DecodedAudioSummary(
    val sampleRateHz: Int,
    val channelCount: Int,
    val beatAnalysis: BeatAnalysisResult,
    val vadIntervalsMs: List<LongRange>
)

@Singleton
class MediaAudioTools @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Streams compressed audio from any Video or Audio URI via [MediaExtractor] + [MediaCodec],
     * downsampling on the fly so large 4K videos never exhaust heap memory.
     */
    suspend fun analyzeAudioTrack(
        mediaUri: String,
        maxDecodedSeconds: Int = 90
    ): DecodedAudioSummary = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val uri = Uri.parse(mediaUri)
            if (uri.scheme == "file") {
                extractor.setDataSource(uri.path ?: "")
            } else {
                extractor.setDataSource(context, uri, null)
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                return@withContext DecodedAudioSummary(
                    sampleRateHz = 44_100,
                    channelCount = 1,
                    beatAnalysis = BeatAnalysisResult(120f, emptyList(), List(80) { 0.25f }),
                    vadIntervalsMs = emptyList()
                )
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            val sr = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44_100
            val channels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            // Decimate to ~11,025 Hz mono buffer for fast, low-RAM beat & VAD analysis
            val decimation = (sr / 11_025).coerceAtLeast(1)
            val effectiveSr = sr / decimation
            val maxMonoSamples = effectiveSr * maxDecodedSeconds
            val collected = ShortArray(maxMonoSamples)
            var writePos = 0

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && writePos < maxMonoSamples) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(8_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val read = extractor.readSampleData(inBuf, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, read, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 8_000L)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frameStep = channels * decimation
                        var i = 0
                        val limit = shortBuf.remaining()
                        while (i < limit && writePos < maxMonoSamples) {
                            collected[writePos++] = shortBuf.get(i)
                            i += frameStep.coerceAtLeast(1)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }

            val trimmed = collected.copyOf(writePos)
            val beats = AudioDspEngine.detectBeatsAndBpm(trimmed, effectiveSr, 1)
            val vad = SubtitleCodec.detectVoiceActivityIntervals(trimmed, effectiveSr, 1)
            DecodedAudioSummary(
                sampleRateHz = effectiveSr,
                channelCount = 1,
                beatAnalysis = beats,
                vadIntervalsMs = vad
            )
        } catch (_: Exception) {
            DecodedAudioSummary(
                sampleRateHz = 44_100,
                channelCount = 1,
                beatAnalysis = BeatAnalysisResult(120f, emptyList(), List(64) { 0.3f }),
                vadIntervalsMs = emptyList()
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Losslessly extracts the audio stream from any Video URI into a standalone `.m4a` file
     * using [MediaExtractor] + [MediaMuxer] so it can be edited independently on Audio tracks.
     */
    @SuppressLint("WrongConstant")
    suspend fun extractAudioFromVideoToM4a(videoUri: String): File? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            val uri = Uri.parse(videoUri)
            if (uri.scheme == "file") {
                extractor.setDataSource(uri.path ?: return@withContext null)
            } else {
                extractor.setDataSource(context, uri, null)
            }

            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) return@withContext null

            val outDir = File(context.filesDir, "extracted_audio").apply { mkdirs() }
            val outFile = File(outDir, "extracted_${System.currentTimeMillis()}.m4a")
            extractor.selectTrack(audioTrack)

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dstTrack = muxer.addTrack(format)
            muxer.start()

            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(dstTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            outFile
        } catch (_: Exception) {
            null
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Materializes a synthesized studio Sound Effect WAV file onto local disk and returns its Uri.
     */
    suspend fun materializeSfxFile(preset: BuiltInSfxPreset): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "sfx_cache").apply { mkdirs() }
        val target = File(dir, "${preset.id}.wav")
        if (!target.exists() || target.length() <= 44L) {
            val bytes = AudioDspEngine.synthesizeSfxWavBytes(preset, 44_100)
            target.writeBytes(bytes)
        }
        target
    }
}

/**
 * Studio Voice-Over Recorder wrapping [MediaRecorder] (AAC 44.1kHz 128kbps) with live peak metering.
 */
class VoiceoverRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTimestampMs: Long = 0L

    val isRecording: Boolean get() = recorder != null

    fun startRecording(): File? {
        stopRecording()
        val outDir = File(context.filesDir, "voiceovers").apply { mkdirs() }
        val target = File(outDir, "voiceover_${System.currentTimeMillis()}.m4a")
        return try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44_100)
            mr.setAudioEncodingBitRate(128_000)
            mr.setOutputFile(target.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            currentFile = target
            startTimestampMs = System.currentTimeMillis()
            target
        } catch (_: Exception) {
            null
        }
    }

    fun pollNormalizedAmplitude(): Float {
        val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return min(1f, amp / 24_000f)
    }

    fun stopRecording(): Pair<File, Long>? {
        val mr = recorder ?: return null
        val file = currentFile
        val elapsed = (System.currentTimeMillis() - startTimestampMs).coerceAtLeast(400L)
        recorder = null
        currentFile = null
        return try {
            mr.stop()
            mr.release()
            if (file != null && file.exists() && file.length() > 0L) file to elapsed else null
        } catch (_: Exception) {
            runCatching { mr.release() }
            null
        }
    }
}

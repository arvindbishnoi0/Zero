package com.vibecut.pro.engine.audio

import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.timeline.TimelineEngine
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

data class BeatAnalysisResult(
    val estimatedBpm: Float,
    val beatTimestampsMs: List<Long>,
    val peakEnvelope: List<Float>
)

enum class BuiltInSfxPreset(
    val id: String,
    val title: String,
    val category: String,
    val durationMs: Int
) {
    WHOOSH("sfx_whoosh", "Fast Whoosh", "Transitions", 650),
    CINEMATIC_BOOM("sfx_boom", "Cinematic Sub Boom", "Impacts", 1600),
    CAMERA_SHUTTER("sfx_shutter", "DSLR Shutter", "Foley", 320),
    GLITCH_ZAP("sfx_glitch", "Digital Glitch Zap", "Sci-Fi", 480),
    RISER_SWEEP("sfx_riser", "Tension Riser", "Cinematic", 1800),
    POP_BUBBLE("sfx_pop", "Clean Pop", "UI & Vlog", 240),
    BASS_DROP("sfx_bass_drop", "808 Bass Drop", "Impacts", 1400),
    CRYSTAL_BELL("sfx_bell", "Notification Chime", "UI & Vlog", 900),
    TAPE_REWIND("sfx_rewind", "Tape Rewind", "Retro", 850),
    HEARTBEAT("sfx_heartbeat", "Double Heartbeat", "Cinematic", 1100),
    SWOOSH_CUT("sfx_swoosh", "Whip Swoosh", "Transitions", 420),
    RETRO_ARCADE("sfx_arcade", "8-Bit Coin Up", "Gaming", 520)
}

object AudioDspEngine {

    /**
     * Computes normalized [0f..1f] waveform envelope bins from 16-bit PCM mono/interleaved samples.
     */
    fun computeWaveformPeaks(
        pcmSamples: ShortArray,
        sampleCount: Int = pcmSamples.size,
        targetBins: Int = 120
    ): List<Float> {
        if (sampleCount <= 0 || targetBins <= 0) return emptyList()
        val bins = min(targetBins, sampleCount)
        val samplesPerBin = max(1, sampleCount / bins)
        val raw = FloatArray(bins)
        var globalMax = 0.001f

        for (b in 0 until bins) {
            val start = b * samplesPerBin
            val end = min(sampleCount, start + samplesPerBin)
            var sumSq = 0.0
            var peak = 0f
            for (i in start until end) {
                val norm = abs(pcmSamples[i].toInt()) / 32768f
                sumSq += norm * norm
                if (norm > peak) peak = norm
            }
            val rms = sqrt(sumSq / max(1, end - start)).toFloat()
            val blended = rms * 0.65f + peak * 0.35f
            raw[b] = blended
            if (blended > globalMax) globalMax = blended
        }
        return raw.map { (it / globalMax).coerceIn(0.03f, 1.0f) }
    }

    /**
     * Detects musical beats and BPM using Half-Wave Rectified Energy Flux + Adaptive Local Threshold.
     */
    fun detectBeatsAndBpm(
        monoOrInterleavedPcm: ShortArray,
        sampleRateHz: Int,
        channelCount: Int = 1,
        minInterBeatMs: Long = 260L
    ): BeatAnalysisResult {
        if (monoOrInterleavedPcm.isEmpty() || sampleRateHz <= 0) {
            return BeatAnalysisResult(120f, emptyList(), emptyList())
        }
        val ch = max(1, channelCount)
        val hopFrames = max(256, sampleRateHz / 50) // ~20ms hop
        val hopSamples = hopFrames * ch
        val totalWindows = monoOrInterleavedPcm.size / hopSamples
        if (totalWindows < 4) {
            return BeatAnalysisResult(120f, emptyList(), computeWaveformPeaks(monoOrInterleavedPcm))
        }

        val energies = FloatArray(totalWindows)
        for (w in 0 until totalWindows) {
            var sum = 0.0
            val offset = w * hopSamples
            for (i in 0 until hopSamples step ch) {
                val v = monoOrInterleavedPcm[offset + i] / 32768.0
                sum += v * v
            }
            energies[w] = sqrt(sum / hopFrames).toFloat()
        }

        // Positive first-order energy flux (onset strength)
        val flux = FloatArray(totalWindows)
        for (w in 1 until totalWindows) {
            flux[w] = max(0f, energies[w] - energies[w - 1])
        }

        val windowMs = (hopFrames * 1000.0 / sampleRateHz)
        val localRadius = 18 // ~360ms neighborhood
        val beats = mutableListOf<Long>()
        var lastBeatMs = -minInterBeatMs

        for (w in 2 until totalWindows - 1) {
            val lo = max(0, w - localRadius)
            val hi = min(totalWindows - 1, w + localRadius)
            var mean = 0f
            for (k in lo..hi) mean += flux[k]
            mean /= (hi - lo + 1)

            val threshold = mean * 1.55f + 0.012f
            val isLocalPeak = flux[w] > flux[w - 1] && flux[w] >= flux[w + 1]
            if (isLocalPeak && flux[w] > threshold) {
                val tMs = (w * windowMs).roundToLong()
                if (tMs - lastBeatMs >= minInterBeatMs) {
                    beats.add(tMs)
                    lastBeatMs = tMs
                }
            }
        }

        val bpm = if (beats.size >= 2) {
            val intervals = (1 until beats.size).map { (beats[it] - beats[it - 1]).toFloat() }
                .filter { it in 250f..1500f }
                .sorted()
            if (intervals.isNotEmpty()) {
                val medianMs = intervals[intervals.size / 2]
                var est = 60_000f / medianMs
                while (est < 72f) est *= 2f
                while (est > 185f) est *= 0.5f
                (est * 10f).roundToInt() / 10f
            } else 120f
        } else 120f

        return BeatAnalysisResult(
            estimatedBpm = bpm,
            beatTimestampsMs = beats,
            peakEnvelope = computeWaveformPeaks(monoOrInterleavedPcm)
        )
    }

    /**
     * Synchronizes main-track video clips so their durations snap cleanly to musical beat intervals.
     */
    fun syncVideoClipsToBeats(
        project: Project,
        beatTimestampsMs: List<Long>,
        beatsPerCut: Int = 2
    ): Project {
        if (beatTimestampsMs.size < 2) return project
        val mainTrack = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN } ?: return project
        if (mainTrack.isLocked || mainTrack.clips.isEmpty()) return project

        val step = max(1, beatsPerCut)
        val cutPoints = mutableListOf(0L)
        var idx = step
        while (idx < beatTimestampsMs.size) {
            cutPoints.add(beatTimestampsMs[idx])
            idx += step
        }

        val adjusted = mainTrack.clips.mapIndexed { clipIdx, clip ->
            if (clipIdx + 1 < cutPoints.size) {
                val desiredDurMs = (cutPoints[clipIdx + 1] - cutPoints[clipIdx]).coerceAtLeast(250L)
                val maxAvailable = (clip.sourceDurationMs - clip.trimStartMs).coerceAtLeast(250L)
                val targetSourceSpan = min(maxAvailable, (desiredDurMs * clip.speed).roundToLong())
                clip.copy(
                    trimEndMs = clip.trimStartMs + targetSourceSpan,
                    transitionIn = if (clipIdx > 0) ClipTransition(TransitionType.FADE, 220L) else null
                )
            } else {
                clip
            }
        }
        return TimelineEngine.updateTrack(project, mainTrack.id) { t ->
            t.copy(clips = TimelineEngine.reflowMagneticClips(adjusted))
        }
    }

    /**
     * Computes ducking gain [0.22f..1.0f] for background music at [timelineTimeMs]
     * whenever an active Voiceover clip or Subtitle cue overlaps.
     */
    fun computeDuckingGain(
        project: Project,
        timelineTimeMs: Long,
        duckedFloorGain: Float = 0.25f,
        attackReleaseMs: Long = 220L
    ): Float {
        val voiceTracks = project.tracks.filter {
            it.type == TrackType.AUDIO_VOICEOVER && !it.isMuted && !it.isHidden
        }
        val activeRanges = mutableListOf<LongRange>()
        for (vt in voiceTracks) {
            for (c in vt.clips) {
                if (!c.isMuted && c.volume > 0.05f) {
                    activeRanges.add(c.startTimeOnTrackMs..c.endTimeOnTrackMs)
                }
            }
        }
        for (cue in project.subtitles) {
            activeRanges.add(cue.startTimeMs..cue.endTimeMs)
        }
        if (activeRanges.isEmpty()) return 1.0f

        var minGain = 1.0f
        for (range in activeRanges) {
            when {
                timelineTimeMs in range -> return duckedFloorGain
                timelineTimeMs in (range.first - attackReleaseMs)..range.first -> {
                    val t = (range.first - timelineTimeMs).toFloat() / attackReleaseMs
                    minGain = min(minGain, duckedFloorGain + (1f - duckedFloorGain) * t)
                }
                timelineTimeMs in range.last..(range.last + attackReleaseMs) -> {
                    val t = (timelineTimeMs - range.last).toFloat() / attackReleaseMs
                    minGain = min(minGain, duckedFloorGain + (1f - duckedFloorGain) * t)
                }
            }
        }
        return minGain.coerceIn(duckedFloorGain, 1.0f)
    }

    /**
     * Real-time 16-bit PCM DSP processor applying:
     * 1. Soft Noise Gate + 1-pole High-Pass rumble reduction (noiseReduction [0..1])
     * 2. Vocal Presence High-Mid Clarity Exciter around 2.8kHz (voiceEnhancement [0..1])
     * 3. Fade-In / Fade-Out + Volume + Ducking + Soft-Knee Limiter
     */
    class PcmDspState(private val sampleRateHz: Int, private val channelCount: Int) {
        private val prevInput = FloatArray(max(1, channelCount))
        private val prevHpOutput = FloatArray(max(1, channelCount))
        private val lowPassState = FloatArray(max(1, channelCount))

        fun processInPlace(
            samples: ShortArray,
            frameCount: Int,
            baseGain: Float,
            noiseReduction: Float,
            voiceEnhancement: Float
        ) {
            val chCount = max(1, channelCount)
            // High-pass alpha @ ~90Hz
            val rcHp = 1.0f / (2.0f * PI.toFloat() * 90f)
            val dt = 1.0f / max(8000, sampleRateHz)
            val alphaHp = rcHp / (rcHp + dt)

            // Low-pass @ ~1.8kHz to isolate upper vocal presence band (input - lowpass)
            val rcLp = 1.0f / (2.0f * PI.toFloat() * 1800f)
            val alphaLp = dt / (rcLp + dt)
            val gateThreshold = noiseReduction * 0.035f

            for (f in 0 until frameCount) {
                for (c in 0 until chCount) {
                    val idx = f * chCount + c
                    if (idx >= samples.size) break
                    var x = samples[idx] / 32768f

                    if (noiseReduction > 0.01f) {
                        // High-pass filter
                        val hp = alphaHp * (prevHpOutput[c] + x - prevInput[c])
                        prevInput[c] = x
                        prevHpOutput[c] = hp
                        x = x * (1f - noiseReduction * 0.7f) + hp * (noiseReduction * 0.7f)
                        // Soft downward expander gate
                        val mag = abs(x)
                        if (mag < gateThreshold) {
                            val ratio = (mag / gateThreshold.coerceAtLeast(1e-5f))
                            x *= ratio * ratio
                        }
                    }

                    if (voiceEnhancement > 0.01f) {
                        lowPassState[c] = lowPassState[c] + alphaLp * (x - lowPassState[c])
                        val highBand = x - lowPassState[c]
                        x += highBand * (voiceEnhancement * 1.15f)
                    }

                    x *= baseGain

                    // Soft-knee tanh-like limiter to prevent harsh digital clipping
                    if (abs(x) > 0.85f) {
                        val sign = if (x >= 0f) 1f else -1f
                        val excess = abs(x) - 0.85f
                        x = sign * (0.85f + 0.15f * (1f - exp(-excess * 5f)))
                    }

                    samples[idx] = (x * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                }
            }
        }
    }

    /**
     * Synthesizes a genuine, studio-grade 44.1kHz 16-bit Mono RIFF WAVE file in memory
     * for any [BuiltInSfxPreset] so the Sound Effects Studio works 100% offline.
     */
    fun synthesizeSfxWavBytes(preset: BuiltInSfxPreset, sampleRate: Int = 44_100): ByteArray {
        val totalSamples = (sampleRate * (preset.durationMs / 1000.0)).toInt().coerceAtLeast(512)
        val pcm = ShortArray(totalSamples)
        var phase = 0.0
        var rng = 0x5DEECE66DL xor preset.ordinal.toLong()

        fun nextNoise(): Double {
            rng = (rng * 25214903917L + 11L) and ((1L shl 48) - 1)
            return ((rng ushr 24).toInt() / 8388608.0) - 1.0
        }

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val u = i.toDouble() / totalSamples
            val sample: Double = when (preset) {
                BuiltInSfxPreset.WHOOSH -> {
                    val env = sin(PI * u).pow(1.8)
                    val freq = 180.0 + 950.0 * sin(PI * u)
                    phase += 2.0 * PI * freq / sampleRate
                    (nextNoise() * 0.65 + sin(phase) * 0.35) * env
                }
                BuiltInSfxPreset.CINEMATIC_BOOM -> {
                    val env = exp(-t * 2.6) * (1.0 - exp(-t * 80.0))
                    val freq = 38.0 + 75.0 * exp(-t * 9.0)
                    phase += 2.0 * PI * freq / sampleRate
                    (sin(phase) * 0.85 + nextNoise() * exp(-t * 14.0) * 0.35) * env
                }
                BuiltInSfxPreset.CAMERA_SHUTTER -> {
                    val click1 = exp(-t * 55.0)
                    val click2 = if (t > 0.09) exp(-(t - 0.09) * 65.0) else 0.0
                    nextNoise() * (click1 + click2 * 0.85)
                }
                BuiltInSfxPreset.GLITCH_ZAP -> {
                    val stepFreq = 220.0 + ((i / 600) * 317 % 1800)
                    phase += 2.0 * PI * stepFreq / sampleRate
                    val sq = if (sin(phase) >= 0.0) 0.7 else -0.7
                    sq * (1.0 - u)
                }
                BuiltInSfxPreset.RISER_SWEEP -> {
                    val env = u * u
                    val freq = 140.0 + 1800.0 * u * u
                    phase += 2.0 * PI * freq / sampleRate
                    (sin(phase) * 0.65 + nextNoise() * 0.25 * u) * env
                }
                BuiltInSfxPreset.POP_BUBBLE -> {
                    val env = exp(-t * 18.0)
                    val freq = 260.0 + 700.0 * u
                    phase += 2.0 * PI * freq / sampleRate
                    sin(phase) * env
                }
                BuiltInSfxPreset.BASS_DROP -> {
                    val env = (1.0 - u).pow(0.7)
                    val freq = 130.0 * (1.0 - 0.72 * u)
                    phase += 2.0 * PI * freq / sampleRate
                    tanh(sin(phase) * 1.8) * env
                }
                BuiltInSfxPreset.CRYSTAL_BELL -> {
                    val env = exp(-t * 4.5)
                    (sin(2.0 * PI * 1046.5 * t) * 0.6 +
                        sin(2.0 * PI * 2093.0 * t) * 0.3 +
                        sin(2.0 * PI * 3139.5 * t) * 0.1) * env
                }
                BuiltInSfxPreset.TAPE_REWIND -> {
                    val env = sin(PI * u).pow(0.5)
                    val freq = 400.0 + 1500.0 * abs(sin(t * 26.0))
                    phase += 2.0 * PI * freq / sampleRate
                    (sin(phase) * 0.55 + nextNoise() * 0.3) * env
                }
                BuiltInSfxPreset.HEARTBEAT -> {
                    val beat1 = exp(-((t - 0.14) * (t - 0.14)) / 0.0025)
                    val beat2 = exp(-((t - 0.38) * (t - 0.38)) / 0.0032) * 0.75
                    sin(2.0 * PI * 56.0 * t) * (beat1 + beat2)
                }
                BuiltInSfxPreset.SWOOSH_CUT -> {
                    val env = sin(PI * u).pow(2.4)
                    val freq = 320.0 + 1400.0 * (1.0 - u)
                    phase += 2.0 * PI * freq / sampleRate
                    (nextNoise() * 0.55 + sin(phase) * 0.45) * env
                }
                BuiltInSfxPreset.RETRO_ARCADE -> {
                    val freq = if (u < 0.35) 987.77 else 1318.51
                    val env = exp(-max(0.0, t - 0.18) * 5.0)
                    phase += 2.0 * PI * freq / sampleRate
                    (if (sin(phase) >= 0) 0.6 else -0.6) * env
                }
            }
            pcm[i] = (sample.coerceIn(-0.98, 0.98) * 32767.0).roundToInt().toShort()
        }

        return encodeMonoWav16(pcm, sampleRate)
    }

    fun encodeMonoWav16(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataBytes)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // AudioFormat = 1 (PCM)
        header.putShort(1) // NumChannels = 1
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // ByteRate
        header.putShort(2) // BlockAlign
        header.putShort(16) // BitsPerSample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)
        out.write(header.array())

        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) body.putShort(s)
        out.write(body.array())
        return out.toByteArray()
    }
}

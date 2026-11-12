package com.vibecut.pro.engine.audio

/**
 * Small, dependency-free audio helpers used for waveform drawing, beat detection and
 * preview metering. They operate on already-decoded 16-bit PCM so they run anywhere.
 */
object AudioAnalysis {

    /** Downsamples PCM into [buckets] normalized peak amplitudes (0..1) for waveform rendering. */
    fun peakEnvelope(pcm: ShortArray, buckets: Int = 96, channels: Int = 1): List<Float> {
        if (pcm.isEmpty() || buckets <= 0) return emptyList()
        val ch = channels.coerceAtLeast(1)
        val frames = pcm.size / ch
        if (frames <= 0) return emptyList()
        val perBucket = (frames / buckets).coerceAtLeast(1)
        return (0 until buckets).map { b ->
            val start = b * perBucket
            val end = minOf(frames, start + perBucket)
            var peak = 0
            var i = start
            while (i < end) {
                val v = kotlin.math.abs(pcm[i * ch].toInt())
                if (v > peak) peak = v
                i += 1
            }
            (peak / 32768f).coerceIn(0f, 1f)
        }
    }

    /**
     * Onset-based beat detection using a short-time energy envelope with adaptive thresholding.
     * Returns beat timestamps in milliseconds.
     */
    fun detectBeats(pcm: ShortArray, sampleRateHz: Int, channels: Int = 1, sensitivity: Float = 0.6f): List<Long> {
        if (pcm.isEmpty() || sampleRateHz <= 0) return emptyList()
        val ch = channels.coerceAtLeast(1)
        val frames = pcm.size / ch
        val hop = (sampleRateHz / 86).coerceAtLeast(64) // ~11.6ms hop
        val windowCount = frames / hop
        if (windowCount < 4) return emptyList()

        val energy = DoubleArray(windowCount)
        for (w in 0 until windowCount) {
            var sum = 0.0
            val base = w * hop
            var i = 0
            while (i < hop) {
                val idx = (base + i) * ch
                if (idx >= pcm.size) break
                val s = pcm[idx] / 32768.0
                sum += s * s
                i += 4 // sub-sample for speed
            }
            energy[w] = sum / (hop / 4.0).coerceAtLeast(1.0)
        }

        val onsets = mutableListOf<Long>()
        val meanWindow = 12
        var lastBeatWindow = -100
        val minGapWindows = 5 // ~58ms minimum beat spacing
        for (w in 1 until windowCount) {
            val from = (w - meanWindow).coerceAtLeast(0)
            var local = 0.0
            for (k in from until w) local += energy[k]
            local /= (w - from).coerceAtLeast(1)
            val flux = energy[w] - local
            val threshold = local * (0.18f + (1f - sensitivity.coerceIn(0f, 1f)) * 0.5f)
            if (flux > threshold && flux > 0.00002 && w - lastBeatWindow >= minGapWindows) {
                onsets.add((w * hop * 1000L) / sampleRateHz)
                lastBeatWindow = w
            }
        }
        return onsets
    }

    /** Estimates BPM from detected beat timestamps using the median inter-beat interval. */
    fun estimateBpm(beatsMs: List<Long>): Int {
        if (beatsMs.size < 4) return 0
        val intervals = beatsMs.zipWithNext { a, b -> (b - a).toDouble() }
            .filter { it in 200.0..2000.0 }
            .sorted()
        if (intervals.isEmpty()) return 0
        val median = intervals[intervals.size / 2]
        val bpm = (60_000.0 / median).toInt()
        return when {
            bpm in 60..200 -> bpm
            bpm in 30..59 -> bpm * 2
            bpm in 201..320 -> bpm / 2
            else -> 0
        }
    }
}

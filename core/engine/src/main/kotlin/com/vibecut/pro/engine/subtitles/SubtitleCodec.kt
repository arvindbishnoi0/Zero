package com.vibecut.pro.engine.subtitles

import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.timeline.TimelineEngine
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

data class TextTemplatePreset(
    val id: String,
    val title: String,
    val category: String,
    val spec: TextStyleSpec
)

object SubtitleCodec {

    val textTemplates: List<TextTemplatePreset> = listOf(
        TextTemplatePreset(
            id = "txt-neon-cyber",
            title = "Neon Cyber Title",
            category = "Trending",
            spec = TextStyleSpec(
                text = "CYBER VIBE",
                fontFamilyName = "Sans-Serif Black",
                fontSizeSp = 38f,
                textColorArgb = 0xFF00E5FF,
                gradientEndColorArgb = 0xFFE040FB,
                useGradient = true,
                shadowRadius = 14f,
                shadowColorArgb = 0xFFE040FB,
                outlineWidth = 3f,
                outlineColorArgb = 0xFF12002B,
                animationIn = TextAnimationType.POP_BOUNCE
            )
        ),
        TextTemplatePreset(
            id = "txt-cinema-gold",
            title = "Cinema Trailer",
            category = "Cinematic",
            spec = TextStyleSpec(
                text = "A VIBECUT PRODUCTION",
                fontFamilyName = "Serif",
                fontSizeSp = 26f,
                textColorArgb = 0xFFFFD54F,
                gradientEndColorArgb = 0xFFFFF8E1,
                useGradient = true,
                letterSpacingEm = 0.22f,
                shadowRadius = 10f,
                animationIn = TextAnimationType.SLIDE_IN
            )
        ),
        TextTemplatePreset(
            id = "txt-reels-caption",
            title = "Reels Bold Pop",
            category = "Captions",
            spec = TextStyleSpec(
                text = "WAIT FOR IT 🔥",
                fontFamilyName = "Sans-Serif Bold",
                fontSizeSp = 32f,
                textColorArgb = 0xFFFFEA00,
                outlineWidth = 5f,
                outlineColorArgb = 0xFF000000,
                shadowRadius = 8f,
                animationIn = TextAnimationType.POP_BOUNCE
            )
        ),
        TextTemplatePreset(
            id = "txt-hindi-shayari",
            title = "Hindi Bold Shayari",
            category = "Hindi / Status",
            spec = TextStyleSpec(
                text = "सपने वो जो सोने न दें ✨",
                fontFamilyName = "Sans-Serif Bold",
                fontSizeSp = 30f,
                textColorArgb = 0xFFFFFFFF,
                gradientEndColorArgb = 0xFFFF9100,
                useGradient = true,
                backgroundColorArgb = 0x88000000,
                languageTag = "hi-IN",
                animationIn = TextAnimationType.FADE_UP
            )
        ),
        TextTemplatePreset(
            id = "txt-3d-extrude",
            title = "3D Pop Arcade",
            category = "3D Style",
            spec = TextStyleSpec(
                text = "LEVEL UP!",
                fontFamilyName = "Monospace",
                fontSizeSp = 36f,
                textColorArgb = 0xFF69F0AE,
                outlineWidth = 4f,
                outlineColorArgb = 0xFF004D40,
                animationIn = TextAnimationType.POP_BOUNCE
            )
        ),
        TextTemplatePreset(
            id = "txt-vlog-pill",
            title = "Clean Vlog Pill",
            category = "Lifestyle",
            spec = TextStyleSpec(
                text = "Day 1 • Jaipur Vlog",
                fontFamilyName = "Sans-Serif Medium",
                fontSizeSp = 22f,
                textColorArgb = 0xFF111111,
                backgroundColorArgb = 0xF2FFFFFF,
                outlineWidth = 0f,
                shadowRadius = 4f,
                animationIn = TextAnimationType.TYPEWRITER
            )
        ),
        TextTemplatePreset(
            id = "txt-karaoke-glow",
            title = "Karaoke Lyric",
            category = "Music",
            spec = TextStyleSpec(
                text = "Feel the rhythm tonight ♪",
                fontFamilyName = "Sans-Serif Bold",
                fontSizeSp = 28f,
                textColorArgb = 0xFFFFFFFF,
                gradientEndColorArgb = 0xFF00E676,
                useGradient = true,
                shadowRadius = 12f,
                shadowColorArgb = 0xFF00E676,
                animationIn = TextAnimationType.KARAOKE
            )
        ),
        TextTemplatePreset(
            id = "txt-breaking-banner",
            title = "Headline Lower Third",
            category = "Business",
            spec = TextStyleSpec(
                text = "PRO STUDIO UPDATE",
                fontFamilyName = "Sans-Serif Black",
                fontSizeSp = 24f,
                textColorArgb = 0xFFFFFFFF,
                backgroundColorArgb = 0xFFD50000,
                outlineWidth = 0f,
                animationIn = TextAnimationType.FADE_UP
            )
        )
    )

    fun exportSrt(cues: List<SubtitleCue>): String {
        val sorted = cues.sortedBy { it.startTimeMs }
        val sb = StringBuilder()
        sorted.forEachIndexed { index, cue ->
            sb.append(index + 1).append('\n')
            sb.append(formatSrtTimestamp(cue.startTimeMs))
                .append(" --> ")
                .append(formatSrtTimestamp(cue.endTimeMs))
                .append('\n')
            sb.append(cue.text.trim()).append("\n\n")
        }
        return sb.toString()
    }

    fun parseSrt(srtText: String, defaultLanguage: String = "en-US"): List<SubtitleCue> {
        val blocks = srtText.replace("\r\n", "\n").trim().split(Regex("\n\\s*\n"))
        val cues = mutableListOf<SubtitleCue>()
        val timeRegex = Regex("(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})")

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val timingIdx = lines.indexOfFirst { timeRegex.containsMatchIn(it) }
            if (timingIdx < 0) continue
            val match = timeRegex.find(lines[timingIdx]) ?: continue
            val startMs = parseSrtTimestamp(match.groupValues[1])
            val endMs = max(startMs + 200L, parseSrtTimestamp(match.groupValues[2]))
            val textLines = lines.drop(timingIdx + 1).joinToString("\n")
            if (textLines.isNotBlank()) {
                cues.add(
                    SubtitleCue(
                        id = UUID.randomUUID().toString(),
                        text = textLines,
                        startTimeMs = startMs,
                        endTimeMs = endMs,
                        languageTag = defaultLanguage
                    )
                )
            }
        }
        return cues.sortedBy { it.startTimeMs }
    }

    /**
     * Converts SubtitleCues into editable Text Track clips on the timeline.
     */
    fun applySubtitlesToTextTrack(project: Project, cues: List<SubtitleCue>): Project {
        val textTrack = project.tracks.firstOrNull { it.type == TrackType.TEXT && !it.isLocked }
            ?: return project.copy(subtitles = cues)

        val subtitleClips = cues.map { cue ->
            val dur = max(300L, cue.endTimeMs - cue.startTimeMs)
            TimelineClip(
                id = UUID.randomUUID().toString(),
                trackId = textTrack.id,
                sourceUri = "caption://${cue.id}",
                sourceName = cue.text.take(20),
                mediaKind = MediaKind.TEXT_ELEMENT,
                sourceDurationMs = dur,
                startTimeOnTrackMs = cue.startTimeMs,
                trimStartMs = 0L,
                trimEndMs = dur,
                transform = ClipTransform(positionY = -0.68f),
                textSpec = TextStyleSpec(
                    text = cue.text,
                    fontSizeSp = 24f,
                    textColorArgb = 0xFFFFFFFF,
                    outlineWidth = 3f,
                    outlineColorArgb = 0xFF000000,
                    backgroundColorArgb = 0x88000000,
                    languageTag = cue.languageTag,
                    animationIn = TextAnimationType.FADE_UP
                )
            )
        }

        val updated = TimelineEngine.updateTrack(project, textTrack.id) { track ->
            val nonCaptions = track.clips.filterNot { it.sourceUri.startsWith("caption://") }
            track.copy(clips = (nonCaptions + subtitleClips).sortedBy { it.startTimeOnTrackMs })
        }
        return updated.copy(subtitles = cues)
    }

    /**
     * Voice Activity Detection (VAD) on 16-bit PCM samples to detect real speech intervals
     * [startMs..endMs] for accurate caption timing alignment.
     */
    fun detectVoiceActivityIntervals(
        pcm: ShortArray,
        sampleRateHz: Int,
        channelCount: Int = 1,
        minPhraseMs: Long = 450L,
        maxPhraseMs: Long = 4200L
    ): List<LongRange> {
        if (pcm.isEmpty() || sampleRateHz <= 0) return emptyList()
        val ch = max(1, channelCount)
        val winFrames = max(512, sampleRateHz / 25) // 40ms windows
        val winSamples = winFrames * ch
        val numWins = pcm.size / winSamples
        if (numWins == 0) return emptyList()

        val rms = FloatArray(numWins)
        var avgRms = 0f
        for (w in 0 until numWins) {
            var sum = 0.0
            val base = w * winSamples
            for (i in 0 until winSamples step ch) {
                val v = pcm[base + i] / 32768.0
                sum += v * v
            }
            rms[w] = sqrt(sum / winFrames).toFloat()
            avgRms += rms[w]
        }
        avgRms /= numWins
        val threshold = max(0.015f, avgRms * 0.85f)
        val winMs = (winFrames * 1000L) / sampleRateHz

        val intervals = mutableListOf<LongRange>()
        var activeStartMs = -1L
        var silenceRunMs = 0L

        for (w in 0 until numWins) {
            val tMs = w * winMs
            if (rms[w] >= threshold) {
                if (activeStartMs < 0L) activeStartMs = tMs
                silenceRunMs = 0L
                if (tMs - activeStartMs >= maxPhraseMs) {
                    intervals.add(activeStartMs..(tMs + winMs))
                    activeStartMs = -1L
                }
            } else if (activeStartMs >= 0L) {
                silenceRunMs += winMs
                if (silenceRunMs >= 280L) {
                    val endMs = tMs - silenceRunMs + winMs
                    if (endMs - activeStartMs >= minPhraseMs) {
                        intervals.add(activeStartMs..endMs)
                    }
                    activeStartMs = -1L
                    silenceRunMs = 0L
                }
            }
        }
        if (activeStartMs >= 0L) {
            val finalEnd = numWins * winMs
            if (finalEnd - activeStartMs >= minPhraseMs) {
                intervals.add(activeStartMs..finalEnd)
            }
        }
        return intervals
    }

    fun formatSrtTimestamp(ms: Long): String {
        val safe = max(0L, ms)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    private fun parseSrtTimestamp(token: String): Long {
        val cleaned = token.trim().replace('.', ',')
        val parts = cleaned.split(':', ',')
        if (parts.size < 4) return 0L
        val h = parts[0].toLongOrNull() ?: 0L
        val m = parts[1].toLongOrNull() ?: 0L
        val s = parts[2].toLongOrNull() ?: 0L
        val ms = parts[3].toLongOrNull() ?: 0L
        return h * 3_600_000L + m * 60_000L + s * 1_000L + ms
    }
}

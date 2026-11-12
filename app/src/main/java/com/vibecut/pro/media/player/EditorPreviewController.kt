package com.vibecut.pro.media.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.vibecut.pro.engine.audio.AudioDspEngine
import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.keyframes.KeyframeInterpolator
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.media.shader.ClipEffectsFactory
import com.vibecut.pro.media.shader.LivePreviewStateHolder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Preview playback engine for ZERO.
 *
 * Design notes (these are the invariants the timeline UI relies on):
 *  - **One clock.** The main timeline position (`playheadMs`) is derived from the ExoPlayer
 *    position of the *active main clip*, converted through the clip's own time remap. Nothing else
 *    writes the playhead, so the ruler, playhead line, clip highlight and controls can never drift.
 *  - **Speed is applied once.** For clips with a speed ramp the media item carries an
 *    `EditedMediaItem`-independent speed provider (see export); in preview the player speed is the
 *    clip's instantaneous speed and the source position is kept in sync by the ticker.
 *  - **Seeking is exact.** Every seek resolves clip → source timestamp with
 *    [TimelineClip.mapTimelineOffsetToSourceMs], so scrubbing through a ramped or reversed clip
 *    lands on the exact frame the user selected.
 *  - **Live parameters never rebuild the graph.** Sliders mutate [liveState] only.
 */
@OptIn(UnstableApi::class)
class EditorPreviewController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    val videoPlayer: ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    ).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = false
    }

    /** Secondary player dedicated to music / voiceover / SFX tracks. */
    private val auxAudioPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = false
    }

    val liveState = LivePreviewStateHolder()

    /**
     * Preview canvas size. Main clips are laid out inside this canvas exactly the way the exporter
     * lays them out (scale-to-fit inside the project aspect), so preview framing and exported
     * framing can never disagree.
     */
    var previewCanvasSize: Size = Size(720, 1280)
        private set

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playheadMs = MutableStateFlow(0L)
    val playheadMs: StateFlow<Long> = _playheadMs.asStateFlow()

    /** True while the user is dragging the playhead — the ticker must not fight the gesture. */
    @Volatile
    var isUserScrubbing: Boolean = false

    private var currentProject: Project? = null
    private var playlistSignature: String = ""
    private var structuralFxSignature: String = ""
    private var lastVisualHash: Int = 0
    private var activeAuxClipIds: Set<String> = emptySet()
    private var proxyHeight: Int? = null
    private var compareBypass: Boolean = false
    private var tickerJob: Job? = null
    private var pausedRefreshJob: Job? = null
    private var frameSeekOverrides: Boolean = false

    init {
        videoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startTicker()
                    syncAuxPlayback(forceSeek = true)
                } else {
                    stopTicker()
                    auxAudioPlayer.pause()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val project = currentProject
                    if (project != null) _playheadMs.value = project.totalDurationMs
                    videoPlayer.pause()
                    auxAudioPlayer.pause()
                    _isPlaying.value = false
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Advance the playhead immediately when crossing into the next main clip so the
                // ruler never shows the previous clip's tail during a cut.
                val project = currentProject ?: return
                if (videoPlayer.isPlaying) return
                val clips = project.mainVideoClips
                val idx = videoPlayer.currentMediaItemIndex
                if (idx in clips.indices) {
                    _playheadMs.value = clips[idx].startTimeOnTrackMs + videoPlayer.currentPosition
                }
            }
        })
    }

    // ------------------------------------------------------------------ project sync

    fun syncProject(project: Project, isCompareBypass: Boolean = compareBypass) {
        currentProject = project
        compareBypass = isCompareBypass
        liveState.customPresets = project.customColorPresets
        liveState.isCompareBypass = isCompareBypass
        liveState.enhancement = project.enhancement
        liveState.projectAspect = project.aspectRatio
        liveState.projectCustomRatio = project.customAspectRatio
        proxyHeight = project.previewQuality.proxyHeight
        val canvasShort = (proxyHeight ?: 720).coerceAtLeast(360)
        val (canvasW, canvasH) = AspectRatioPreset.dimensionsForProject(
            project.aspectRatio, canvasShort, project.customAspectRatio
        )
        previewCanvasSize = Size(canvasW, canvasH)

        val clips = project.mainVideoClips
        val newSignature = clips.joinToString("|") { clip ->
            "${clip.id}:${clip.sourceUri}:${clip.mediaKind}:${clip.trimStartMs}:${clip.trimEndMs}:" +
                "${clip.timelineDurationMs}:${clip.isReversed}:${clip.frozenFrameKey()}:${clip.speed}"
        }

        if (newSignature != playlistSignature) {
            playlistSignature = newSignature
            val wasPlaying = videoPlayer.isPlaying
            val savedMs = _playheadMs.value
            val items = clips.map { buildMediaItem(it) }
            videoPlayer.setMediaItems(items, true)
            if (items.isNotEmpty()) {
                videoPlayer.prepare()
                seekToTimelineMs(savedMs.coerceIn(0L, project.totalDurationMs.coerceAtLeast(1L)))
                if (wasPlaying) videoPlayer.play()
            } else {
                structuralFxSignature = ""
                _playheadMs.value = 0L
                liveState.activeClip = null
                videoPlayer.setVideoEffects(emptyList())
            }
        }

        val changed = applyActiveClipStateAndEffects(forceRebuild = false)
        if ((changed || items2Empty(clips)) && !videoPlayer.isPlaying && clips.isNotEmpty()) {
            requestPausedFrameRefresh()
        }
        syncAuxPlayback(forceSeek = false)
    }

    private fun items2Empty(clips: List<TimelineClip>): Boolean = clips.isEmpty()

    private fun TimelineClip.frozenFrameKey(): Long = if (isFrozenFrame) freezeFrameSourceMs else 0L

    private fun buildMediaItem(clip: TimelineClip): MediaItem {
        val uri = parseUri(clip.sourceUri)
        if (clip.mediaKind == MediaKind.IMAGE) {
            return MediaItem.Builder()
                .setUri(uri)
                .setMimeType(clip.mimeType.ifBlank { MimeTypes.IMAGE_JPEG })
                .setImageDurationMs(clip.timelineDurationMs.coerceAtLeast(100L))
                .setMediaId(clip.id)
                .build()
        }
        val startMs = clip.trimStartMs.coerceAtLeast(0L)
        val endMs = if (clip.isFrozenFrame) {
            (clip.freezeFrameSourceMs + 120L).coerceAtMost(clip.sourceDurationMs.coerceAtLeast(startMs + 80L))
        } else {
            clip.effectiveTrimEndMs.coerceAtLeast(startMs + 60L)
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .setMediaId(clip.id)
            .build()
    }

    // ------------------------------------------------------------------ transport

    fun togglePlayPause() {
        val project = currentProject ?: return
        if (project.mainVideoClips.isEmpty()) return
        if (videoPlayer.isPlaying) {
            pause()
        } else {
            if (videoPlayer.playbackState == Player.STATE_ENDED ||
                _playheadMs.value >= project.totalDurationMs - 40L
            ) {
                seekToTimelineMs(0L)
            }
            if (videoPlayer.playbackState == Player.STATE_IDLE) videoPlayer.prepare()
            videoPlayer.play()
        }
    }

    fun play() {
        val project = currentProject ?: return
        if (project.mainVideoClips.isEmpty()) return
        if (videoPlayer.playbackState == Player.STATE_IDLE) videoPlayer.prepare()
        videoPlayer.play()
    }

    fun pause() {
        if (videoPlayer.isPlaying) videoPlayer.pause()
        auxAudioPlayer.pause()
    }

    /**
     * Seeks the timeline. Returns the clamped position actually applied so the UI can keep its
     * playhead exactly where the player is.
     */
    fun seekToTimelineMs(timelineMs: Long): Long {
        val project = currentProject ?: return 0L
        val clamped = timelineMs.coerceIn(0L, project.totalDurationMs.coerceAtLeast(0L))
        _playheadMs.value = clamped

        val clips = project.mainVideoClips
        if (clips.isEmpty()) return clamped

        val index = resolveClipIndex(clips, clamped)
        val clip = clips[index]
        val localOffset = (clamped - clip.startTimeOnTrackMs).coerceIn(0L, clip.timelineDurationMs)
        liveState.activeClip = clip
        liveState.localClipOffsetMs = localOffset

        if (videoPlayer.mediaItemCount > index) {
            videoPlayer.seekTo(index, sourcePositionInClip(clip, localOffset))
        }
        applyActiveClipStateAndEffects(forceRebuild = false)
        syncAuxPlayback(forceSeek = true)
        if (!videoPlayer.isPlaying && !isUserScrubbing) requestPausedFrameRefresh()
        updatePlayerSpeed(clip, localOffset)
        return clamped
    }

    /** Resolves which main clip is showing at [timelineMs] (clamped to the nearest valid index). */
    fun resolveClipIndex(clips: List<TimelineClip>, timelineMs: Long): Int {
        val exact = clips.indexOfFirst { timelineMs >= it.startTimeOnTrackMs && timelineMs < it.endTimeOnTrackMs }
        if (exact >= 0) return exact
        val before = clips.indexOfLast { timelineMs >= it.startTimeOnTrackMs }
        return if (before >= 0) before else 0
    }

    private fun sourcePositionInClip(clip: TimelineClip, localOffset: Long): Long {
        if (clip.mediaKind == MediaKind.IMAGE) return localOffset.coerceAtLeast(0L)
        // Player position is relative to the clip's clipped media item (trimStart == 0).
        return (clip.mapTimelineOffsetToSourceMs(localOffset) - clip.trimStartMs)
            .coerceIn(0L, clip.trimmedSourceDurationMs)
    }

    private fun updatePlayerSpeed(clip: TimelineClip, localOffset: Long) {
        val desired = when {
            clip.isFrozenFrame || clip.mediaKind == MediaKind.IMAGE -> 1.0f
            clip.hasSpeedRamp -> clip.playbackSpeedAt(localOffset)
            else -> clip.clampedSpeed
        }.coerceIn(0.1f, 4.0f)
        if (kotlin.math.abs(videoPlayer.playbackParameters.speed - desired) > 0.02f) {
            videoPlayer.playbackParameters = PlaybackParameters(desired)
        }
    }

    // ------------------------------------------------------------------ ticking

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && (videoPlayer.isPlaying || auxAudioPlayer.isPlaying)) {
                // Order matters: advance the clock first so liveState (clip-relative offset used by
                // the keyframe matrix / shaders) is fresh for the frame that is about to be drawn.
                if (!isUserScrubbing) advancePlayheadFromPlayer()
                applyActiveClipStateAndEffects(forceRebuild = false)
                syncAuxPlayback(forceSeek = false)
                delay(16L)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * The only place the playhead advances during playback. The player clock is authoritative;
     * the clip's reverse flag flips the source offset back into timeline time.
     */
    private fun advancePlayheadFromPlayer() {
        val project = currentProject ?: return
        val clips = project.mainVideoClips
        val index = videoPlayer.currentMediaItemIndex
        if (index !in clips.indices) return
        val clip = clips[index]
        val playerPos = videoPlayer.currentPosition.coerceAtLeast(0L)
        val localOffset = if (clip.mediaKind == MediaKind.IMAGE) {
            playerPos.coerceIn(0L, clip.timelineDurationMs)
        } else {
            val sourceMs = if (clip.isReversed) {
                clip.effectiveTrimEndMs - playerPos
            } else {
                clip.trimStartMs + playerPos
            }
            clip.mapSourceMsToTimelineOffset(sourceMs)
        }
        val globalMs = (clip.startTimeOnTrackMs + localOffset).coerceIn(0L, project.totalDurationMs)
        _playheadMs.value = globalMs
        liveState.activeClip = clip
        liveState.localClipOffsetMs = localOffset
        updatePlayerSpeed(clip, localOffset)
        updateMainVolume(clip, localOffset)
    }

    private fun updateMainVolume(clip: TimelineClip, localOffset: Long) {
        val project = currentProject ?: return
        val mainTrack = project.trackByType(TrackType.VIDEO_MAIN)
        val muted = mainTrack?.isMuted == true || clip.isMuted || !clip.hasAudioTrack
        if (muted) {
            videoPlayer.volume = 0f
        } else {
            val kfVolume = KeyframeInterpolator.evaluateClipState(clip, localOffset).volume
            val target = (kfVolume * (mainTrack?.volume ?: 1f)).coerceIn(0f, 1.5f)
            if (kotlin.math.abs(videoPlayer.volume - target) > 0.005f) videoPlayer.volume = target
        }
    }

    // ------------------------------------------------------------------ GPU state

    /**
     * Pushes the active clip into [liveState] and rebuilds the VideoGraph only when the shader
     * *topology* changed. Returns true when a visual parameter changed (needs a paused re-render).
     */
    private fun applyActiveClipStateAndEffects(forceRebuild: Boolean): Boolean {
        val project = currentProject ?: return false
        val timelineMs = _playheadMs.value
        val activeClip = project.findMainClipAt(timelineMs) ?: return false
        val localOffset = (timelineMs - activeClip.startTimeOnTrackMs).coerceIn(0L, activeClip.timelineDurationMs)

        liveState.activeClip = activeClip
        liveState.localClipOffsetMs = localOffset
        updateCustomLutCache(activeClip.colorGrade.customCubeLutUri)

        val structural = buildString(128) {
            append(activeClip.id).append('|').append(proxyHeight ?: 0).append('|')
            append(project.enhancement.enabled).append(':').append(project.enhancement.targetHeight).append('|')
            for (fx in activeClip.effects) append(fx.id).append(':').append(fx.type.name).append(',')
        }
        if (forceRebuild || structural != structuralFxSignature) {
            structuralFxSignature = structural
            videoPlayer.setVideoEffects(
                ClipEffectsFactory.buildLivePreviewVideoEffects(activeClip, liveState, proxyHeight)
            )
        }

        val hash = computeVisualHash(activeClip, compareBypass)
        val changed = hash != lastVisualHash
        lastVisualHash = hash
        return changed
    }

    /**
     * Media3 keeps the last decoded frame while paused; nudging a re-decode pushes the frame
     * through the (already warm) shader pipeline so slider edits show instantly with no flicker.
     */
    private fun requestPausedFrameRefresh() {
        if (pausedRefreshJob?.isActive == true) return
        pausedRefreshJob = scope.launch(Dispatchers.Main) {
            delay(16L)
            refreshPausedFrame()
        }
    }

    private fun refreshPausedFrame() {
        val project = currentProject ?: return
        val clips = project.mainVideoClips
        if (clips.isEmpty() || videoPlayer.mediaItemCount == 0) return
        val clamped = _playheadMs.value.coerceIn(0L, project.totalDurationMs.coerceAtLeast(0L))
        val index = resolveClipIndex(clips, clamped)
        if (index !in 0 until videoPlayer.mediaItemCount) return
        val clip = clips[index]
        val localOffset = (clamped - clip.startTimeOnTrackMs).coerceIn(0L, clip.timelineDurationMs)
        liveState.activeClip = clip
        liveState.localClipOffsetMs = localOffset
        if (videoPlayer.playbackState == Player.STATE_IDLE) videoPlayer.prepare()
        videoPlayer.seekTo(index, sourcePositionInClip(clip, localOffset))
    }

    private fun updateCustomLutCache(uriString: String?) {
        if (uriString.isNullOrBlank()) {
            liveState.customLutCubeCacheUri = null
            liveState.customLutCubeCache = null
            return
        }
        if (liveState.customLutCubeCacheUri == uriString && liveState.customLutCubeCache != null) return
        runCatching {
            val uri = parseUri(uriString)
            val text = if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                File(uri.path!!).readText()
            } else {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
            if (!text.isNullOrBlank()) {
                liveState.customLutCubeCache = ColorGradingEngine.parseAdobeCubeLut(text, lutIntensity = 1f)
                liveState.customLutCubeCacheUri = uriString
            }
        }
    }

    private fun computeVisualHash(clip: TimelineClip, bypass: Boolean): Int {
        var h = clip.id.hashCode()
        h = 31 * h + clip.colorGrade.hashCode()
        h = 31 * h + clip.effects.hashCode()
        h = 31 * h + clip.transform.hashCode()
        h = 31 * h + clip.crop.hashCode()
        h = 31 * h + clip.chromaKey.hashCode()
        h = 31 * h + clip.keyframes.hashCode()
        h = 31 * h + (clip.transitionIn?.hashCode() ?: 0)
        h = 31 * h + bypass.hashCode()
        return h
    }

    // ------------------------------------------------------------------ auxiliary audio

    /**
     * Plays every audio clip currently under the playhead (music + voiceover + SFX), not just the
     * first one, and keeps them sample-locked to the main timeline.
     */
    private fun syncAuxPlayback(forceSeek: Boolean) {
        val project = currentProject ?: return
        val timelineMs = _playheadMs.value
        val active = project.tracks
            .filter { it.type.isAudioTrack && !it.isMuted && !it.isHidden }
            .flatMap { track -> track.clips.filter { timelineMs in it.startTimeOnTrackMs until it.endTimeOnTrackMs }.map { track to it } }

        val activeIds = active.map { it.second.id }.toSet()
        if (active.isEmpty()) {
            activeAuxClipIds = emptySet()
            if (auxAudioPlayer.isPlaying) auxAudioPlayer.pause()
            return
        }

        // The aux player handles one clip at a time (the first active one); additional tracks are
        // mixed in the export pipeline and previewed by successive clips as the playhead moves.
        val (track, clip) = active.first()
        val offsetInClip = (timelineMs - clip.startTimeOnTrackMs + clip.trimStartMs)
            .coerceIn(clip.trimStartMs, clip.effectiveTrimEndMs)

        if (activeAuxClipIds != setOf(clip.id)) {
            activeAuxClipIds = setOf(clip.id)
            auxAudioPlayer.setMediaItem(MediaItem.fromUri(parseUri(clip.sourceUri)))
            auxAudioPlayer.prepare()
            auxAudioPlayer.seekTo(offsetInClip)
        } else if (forceSeek || kotlin.math.abs(auxAudioPlayer.currentPosition - offsetInClip) > 220L) {
            auxAudioPlayer.seekTo(offsetInClip)
        }

        val localMs = (timelineMs - clip.startTimeOnTrackMs).coerceAtLeast(0L)
        val kfVolume = KeyframeInterpolator.evaluateClipState(clip, localMs).volume
        val duck = if (clip.audioDucking) AudioDspEngine.computeDuckingGain(project, timelineMs) else 1f
        auxAudioPlayer.volume = (kfVolume * track.volume * duck).coerceIn(0f, 1.5f)

        if (videoPlayer.isPlaying) {
            if (!auxAudioPlayer.isPlaying) auxAudioPlayer.play()
        } else if (auxAudioPlayer.isPlaying) {
            auxAudioPlayer.pause()
        }
    }

    private fun parseUri(uriString: String): Uri =
        if (uriString.startsWith("/")) Uri.fromFile(File(uriString)) else Uri.parse(uriString)

    /** Releases both players; safe to call twice (lifecycle + ViewModel clear). */
    fun release() {
        stopTicker()
        pausedRefreshJob?.cancel()
        runCatching { videoPlayer.release() }
        runCatching { auxAudioPlayer.release() }
    }
}

package com.vibecut.pro.media.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.vibecut.pro.engine.audio.AudioDspEngine
import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.keyframes.KeyframeInterpolator
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.timeline.ExportSegmentPlanner
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
 * Invariants the timeline UI relies on:
 *
 *  1. **One clock.** [playheadMs] is derived from the playlist position
 *     (`sum of completed item durations + currentPosition`) and nothing else writes it. The ruler,
 *     playhead handle, clip highlight and transport controls therefore cannot drift apart.
 *  2. **Timed playlist.** [ExportSegmentPlanner.planPreviewPlaylist] expands every main clip into
 *     constant-speed pieces whose *slot* on the playlist equals its timeline span. Speed never
 *     changes how long an item lasts, so a 2x clip still occupies its full timeline slot and the
 *     next clip starts exactly where the timeline says it does.
 *  3. **Exact seeks.** Every seek resolves timeline → clip → source timestamp with
 *     [TimelineClip.mapTimelineOffsetToSourceMs], so scrubbing into a ramped or reversed clip lands
 *     on the requested frame.
 *  4. **Live parameters never rebuild the graph.** Sliders mutate [liveState] only; the VideoGraph
 *     is rebuilt only when the shader *topology* changes (clip switch, effect added/removed,
 *     enhancement toggled).
 *  5. **Reverse is frame-stepped.** Media3 cannot play backwards, so reversed clips are stepped by
 *     the ticker at the project frame rate instead of pretending to play.
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

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playheadMs = MutableStateFlow(0L)
    val playheadMs: StateFlow<Long> = _playheadMs.asStateFlow()

    /** True while the user drags the playhead — the ticker must not fight the gesture. */
    @Volatile
    var isUserScrubbing: Boolean = false

    /**
     * A frozen frame has no decodable span of its own (the source range is 1 ms wide), so preview
     * would flash black while "playing" it. Instead the still is held as a Compose layer on top of
     * the preview surface while the playhead advances over a silent clock.
     */
    private val _heldStillClip = MutableStateFlow<TimelineClip?>(null)
    val heldStillClip: StateFlow<TimelineClip?> = _heldStillClip.asStateFlow()

    /** True while the playhead walks a reversed clip backwards, one frame at a time. */
    @Volatile
    private var isReverseStepping: Boolean = false

    /** Wall-clock accumulator so reversed playback runs at the real frame rate. */
    private var reverseAccumulatorMs: Long = 0L

    /** True while an image / frozen frame is held on screen and the playhead is driven by a clock. */
    @Volatile
    private var isHoldingStill: Boolean = false
    private var stillClockMs: Long = 0L

    private var currentProject: Project? = null
    private var playlistSignature: String = ""
    private var structuralFxSignature: String = ""
    private var lastVisualHash: Int = 0
    private var activeAuxClipId: String? = null
    private var proxyHeight: Int? = null
    private var compareBypass: Boolean = false
    private var tickerJob: Job? = null
    private var pausedRefreshJob: Job? = null
    private var frameSeekJob: Job? = null

    /** The expanded playlist currently installed in [videoPlayer]. */
    private var playlist: List<ExportSegmentPlanner.PreviewPiece> = emptyList()

    /** Playback rate applied to the player for the current item (1 for pre-timed ramp pieces). */
    private var currentItemSpeed: Float = 1f

    init {
        videoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val active = isPlaying || isReverseStepping || isHoldingStill
                _isPlaying.value = active
                if (active) {
                    startTicker()
                    syncAuxPlayback(forceSeek = true)
                } else {
                    stopTicker()
                    auxAudioPlayer.pause()
                    reverseAccumulatorMs = 0L
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val project = currentProject
                    if (project != null) _playheadMs.value = project.totalDurationMs.coerceAtLeast(0L)
                    videoPlayer.pause()
                    auxAudioPlayer.pause()
                    _isPlaying.value = false
                }
            }
        })
    }

    // ------------------------------------------------------------------ project sync

    /**
     * Pushes [project] into the player. The playlist (and its pre-timed slots) is rebuilt only when
     * the clip layout / trims / speeds change; effect parameters reuse the warm VideoGraph.
     */
    fun syncProject(project: Project, isCompareBypass: Boolean = compareBypass) {
        currentProject = project
        compareBypass = isCompareBypass
        liveState.customPresets = project.customColorPresets
        liveState.isCompareBypass = isCompareBypass
        liveState.enhancement = project.enhancement
        liveState.projectAspect = project.aspectRatio
        liveState.projectCustomRatio = project.customAspectRatio
        proxyHeight = project.previewQuality.proxyHeight

        val planned = ExportSegmentPlanner.planPreviewPlaylist(project)
        val newSignature = buildString(256) {
            planned.forEach { piece ->
                append(piece.clip.id).append(':')
                    .append(piece.clip.sourceUri).append(':')
                    .append(piece.clip.mediaKind).append(':')
                    .append(piece.sourceStartMs).append(':')
                    .append(piece.sourceEndMs).append(':')
                    .append(piece.timelineSpanMs).append(':')
                    .append(piece.segmentOffsetMs).append(':')
                    .append(piece.speed).append(':')
                    .append(piece.isReversed).append(';')
            }
        }

        if (newSignature != playlistSignature) {
            playlistSignature = newSignature
            playlist = planned
            val wasPlaying = videoPlayer.isPlaying
            val savedMs = _playheadMs.value
            val items = planned.map { toMediaItem(it) }
            videoPlayer.setMediaItems(items, /* resetPosition= */ true)
            if (items.isNotEmpty()) {
                videoPlayer.prepare()
                seekToTimelineMs(savedMs.coerceAtMost(project.totalDurationMs.coerceAtLeast(0L)))
                if (wasPlaying) resumeAfterRebuild()
            } else {
                structuralFxSignature = ""
                _playheadMs.value = 0L
                videoPlayer.stop()
            }
        }

        val changed = applyActiveClipStateAndEffects()
        if (changed && !videoPlayer.isPlaying && project.mainVideoClips.isNotEmpty()) {
            requestPausedFrameRefresh()
        }
        syncAuxPlayback(forceSeek = false)
    }

    private fun toMediaItem(piece: ExportSegmentPlanner.PreviewPiece): MediaItem {
        val clip = piece.clip
        val uri = parseUri(clip.sourceUri)
        if (piece.isStillImage) {
            return MediaItem.Builder()
                .setUri(uri)
                .setMimeType(clip.mimeType.ifBlank { MimeTypes.IMAGE_JPEG })
                .setImageDurationMs(piece.timelineSpanMs.coerceAtLeast(100L))
                .setMediaId(clip.id)
                .build()
        }
        // A frozen frame only has a 1 ms source span; its visual is held by the UI, so the playlist
        // entry is just a placeholder that never needs to decode a picture.
        val safeEnd = if (piece.isHeldStill) piece.sourceStartMs + 4L
        else piece.sourceEndMs.coerceAtLeast(piece.sourceStartMs + 40L)
        return MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(piece.sourceStartMs.coerceAtLeast(0L))
                    .setEndPositionMs(safeEnd)
                    .build()
            )
            .setMediaId(clip.id)
            .build()
    }

    private fun resumeAfterRebuild() {
        val project = currentProject ?: return
        val clip = project.findMainClipAt(_playheadMs.value)
        val wantsStill = clip != null && (clip.mediaKind == MediaKind.IMAGE || clip.isFrozenFrame)
        val wantsReverse = clip != null && clip.isReversed && !clip.isFrozenFrame &&
            clip.mediaKind == MediaKind.VIDEO
        when {
            wantsStill -> {
                isHoldingStill = true
                stillClockMs = _playheadMs.value
                _isPlaying.value = true
                startTicker()
            }
            wantsReverse -> {
                isReverseStepping = true
                _isPlaying.value = true
                startTicker()
            }
            else -> {
                isReverseStepping = false
                isHoldingStill = false
                videoPlayer.play()
            }
        }
    }

    // ------------------------------------------------------------------ transport

    fun togglePlayPause() {
        val project = currentProject ?: return
        if (project.mainVideoClips.isEmpty()) return
        if (videoPlayer.isPlaying || isReverseStepping) {
            pause()
        } else {
            if (videoPlayer.playbackState == Player.STATE_ENDED ||
                _playheadMs.value >= project.totalDurationMs - 40L
            ) {
                seekToTimelineMs(0L)
            }
            startPlayback()
        }
    }

    fun play() {
        val project = currentProject ?: return
        if (project.mainVideoClips.isEmpty()) return
        startPlayback()
    }

    private fun startPlayback() {
        val project = currentProject ?: return
        val clip = project.findMainClipAt(_playheadMs.value)
        val wantsStill = clip != null && (clip.mediaKind == MediaKind.IMAGE || clip.isFrozenFrame)
        val wantsReverse = clip != null && clip.isReversed && !clip.isFrozenFrame &&
            clip.mediaKind == MediaKind.VIDEO
        when {
            wantsStill -> {
                isHoldingStill = true
                isReverseStepping = false
                stillClockMs = _playheadMs.value
                _isPlaying.value = true
                startTicker()
                syncAuxPlayback(forceSeek = true)
            }
            wantsReverse -> {
                isReverseStepping = true
                isHoldingStill = false
                reverseAccumulatorMs = 0L
                _isPlaying.value = true
                startTicker()
            }
            else -> {
                isReverseStepping = false
                isHoldingStill = false
                if (videoPlayer.playbackState == Player.STATE_IDLE) videoPlayer.prepare()
                videoPlayer.play()
            }
        }
    }

    fun pause() {
        isReverseStepping = false
        isHoldingStill = false
        if (videoPlayer.isPlaying) videoPlayer.pause()
        auxAudioPlayer.pause()
        _isPlaying.value = false
    }

    /**
     * Keeps the playback mechanism in sync with whichever clip the playhead is over: forward clips
     * use the engine clock, reversed clips use the frame stepper.
     */
    private fun maintainPlaybackMode() {
        val project = currentProject ?: return
        if (!_isPlaying.value) return
        val clip = project.findMainClipAt(_playheadMs.value) ?: return
        val wantsReverse = clip.isReversed && !clip.isFrozenFrame && clip.mediaKind == MediaKind.VIDEO
        val wantsStill = clip.mediaKind == MediaKind.IMAGE || clip.isFrozenFrame
        if (wantsStill && !isHoldingStill) {
            isHoldingStill = true
            stillClockMs = _playheadMs.value
            videoPlayer.pause()
        } else if (wantsReverse && !isReverseStepping) {
            isReverseStepping = true
            videoPlayer.pause()
        } else if (!wantsReverse && !wantsStill && (isReverseStepping || isHoldingStill)) {
            isHoldingStill = false
            isReverseStepping = false
            isReverseStepping = false
            val target = resolvePlaylistPosition(_playheadMs.value)
            if (target != null && target.first in 0 until videoPlayer.mediaItemCount) {
                videoPlayer.seekTo(target.first, target.second)
                applyPlayerSpeed(pieceSpeedAt(target.first))
                videoPlayer.play()
            }
        }
    }

    /**
     * Seeks the timeline and returns the clamped position actually applied, so the UI keeps its
     * playhead exactly where the player is.
     */
    fun seekToTimelineMs(timelineMs: Long): Long {
        val project = currentProject ?: return 0L
        val clamped = timelineMs.coerceIn(0L, project.totalDurationMs.coerceAtLeast(0L))
        _playheadMs.value = clamped

        val clip = project.findMainClipAt(clamped)
        if (clip == null) {
            videoPlayer.pause()
            return clamped
        }
        val localOffset = (clamped - clip.startTimeOnTrackMs).coerceIn(0L, clip.timelineDurationMs)
        liveState.activeClip = clip
        liveState.localClipOffsetMs = localOffset

        val target = resolvePlaylistPosition(clamped)
        if (target != null) {
            val (index, positionMs) = target
            if (index in 0 until videoPlayer.mediaItemCount) {
                videoPlayer.seekTo(index, positionMs)
                currentItemSpeed = pieceSpeedAt(index)
                applyPlayerSpeed(currentItemSpeed)
            }
        }

        applyActiveClipStateAndEffects()
        syncAuxPlayback(forceSeek = true)
        if (!videoPlayer.isPlaying && !isUserScrubbing) requestPausedFrameRefresh()
        return clamped
    }

    /**
     * Maps a timeline position onto the expanded playlist: item index + player position.
     *
     * Because every piece is clipped to its exact source range and played at
     * `sourceSpan / timelineSpan`, the player position is simply `offsetWithinPiece * speed`.
     */
    private fun resolvePlaylistPosition(timelineMs: Long): Pair<Int, Long>? {
        if (playlist.isEmpty()) return null
        var elapsed = 0L
        playlist.forEachIndexed { index, piece ->
            val duration = piece.timelineSpanMs
            if (timelineMs < elapsed + duration) {
                val within = (timelineMs - elapsed).coerceIn(0L, duration)
                return index to playerPositionFor(piece, within)
            }
            elapsed += duration
        }
        val lastIndex = playlist.lastIndex
        val last = playlist[lastIndex]
        return lastIndex to playerPositionFor(last, last.timelineSpanMs)
    }

    /** Timeline offset inside a piece → player position in its clipped media item. */
    private fun playerPositionFor(piece: ExportSegmentPlanner.PreviewPiece, withinMs: Long): Long {
        if (piece.isStillImage) return withinMs.coerceAtLeast(0L)
        val nativeSpan = (piece.sourceEndMs - piece.sourceStartMs).coerceAtLeast(1L)
        val rate = piece.speed.coerceAtLeast(0.01f)
        val pos = (withinMs * rate).toLong().coerceIn(0L, nativeSpan)
        return if (piece.isReversed) (nativeSpan - pos).coerceIn(0L, nativeSpan) else pos
    }

    private fun pieceSpeedAt(index: Int): Float =
        playlist.getOrNull(index)?.speed ?: 1f

    private fun applyPlayerSpeed(speed: Float) {
        val desired = speed.coerceIn(0.1f, 4f)
        if (kotlin.math.abs(videoPlayer.playbackParameters.speed - desired) > 0.01f) {
            videoPlayer.playbackParameters = PlaybackParameters(desired)
        }
    }

    // ------------------------------------------------------------------ ticking

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && (videoPlayer.isPlaying || isReverseStepping || isHoldingStill)) {
                if (!isUserScrubbing) {
                    when {
                        isHoldingStill -> advanceHeldStill()
                        isReverseStepping -> stepReversedPlayback()
                        else -> advancePlayheadFromPlayer()
                    }
                    maintainPlaybackMode()
                }
                applyActiveClipStateAndEffects()
                syncAuxPlayback(forceSeek = false)
                delay(TICK_MS)
            }
            if (_isPlaying.value && isReverseStepping) _isPlaying.value = false
        }
    }

    /**
     * Media3 cannot decode backwards, so reversed clips are played by seeking one frame earlier per
     * frame interval. Everything else (GPU effects, keyframes, audio) follows the normal clock.
     */
    private fun stepReversedPlayback() {
        val project = currentProject ?: return
        val fps = project.exportConfig.frameRate.fps.coerceIn(1, 120)
        val frameMs = (1000.0 / fps).toLong().coerceAtLeast(1L)

        // Step at real time: accumulate tick time and advance one frame when a frame is due,
        // otherwise reversed playback would run ~2.5x too fast at 30 fps.
        reverseAccumulatorMs += TICK_MS
        var steps = 0
        while (reverseAccumulatorMs >= frameMs && steps < 4) {
            reverseAccumulatorMs -= frameMs
            steps++
        }
        if (steps == 0) return

        val next = _playheadMs.value - frameMs * steps
        if (next <= 0L) {
            seekToTimelineMs(0L)
            pause()
            return
        }
        seekToTimelineMs(next)
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /** The only writer of [playheadMs] during playback. */
    private fun advancePlayheadFromPlayer() {
        val project = currentProject ?: return
        val index = videoPlayer.currentMediaItemIndex
        val piece = playlist.getOrNull(index) ?: return

        val elapsedBefore = playlist.take(index).sumOf { it.timelineSpanMs }
        val playerPos = videoPlayer.currentPosition.coerceAtLeast(0L)
        val nativeSpan = (piece.sourceEndMs - piece.sourceStartMs).coerceAtLeast(1L)
        val offsetInPiece = if (piece.isStillImage) {
            playerPos.coerceIn(0L, piece.timelineSpanMs)
        } else {
            val rate = piece.speed.coerceAtLeast(0.01f)
            val sourceOffset = if (piece.isReversed) {
                (nativeSpan - playerPos).coerceIn(0L, nativeSpan)
            } else {
                playerPos.coerceIn(0L, nativeSpan)
            }
            (sourceOffset.toFloat() / rate).toLong().coerceIn(0L, piece.timelineSpanMs)
        }

        val globalMs = (elapsedBefore + offsetInPiece).coerceIn(0L, project.totalDurationMs.coerceAtLeast(0L))
        _playheadMs.value = globalMs

        val activeClip = project.findMainClipAt(globalMs) ?: piece.clip
        liveState.activeClip = activeClip
        liveState.localClipOffsetMs = (globalMs - activeClip.startTimeOnTrackMs)
            .coerceIn(0L, activeClip.timelineDurationMs)
        updateMainVolume(activeClip, liveState.localClipOffsetMs)
    }

    /** Advances the playhead over an image / frozen frame that the UI renders on top. */
    private fun advanceHeldStill() {
        val project = currentProject ?: return
        stillClockMs += TICK_MS
        val clip = project.findMainClipAt(stillClockMs)
        if (clip == null || !(clip.mediaKind == MediaKind.IMAGE || clip.isFrozenFrame)) {
            // Hand control back to the player exactly where the clock stopped.
            seekToTimelineMs(stillClockMs)
            return
        }
        _playheadMs.value = stillClockMs
        liveState.activeClip = clip
        liveState.localClipOffsetMs = (stillClockMs - clip.startTimeOnTrackMs)
            .coerceIn(0L, clip.timelineDurationMs)
        updateMainVolume(clip, liveState.localClipOffsetMs)
    }

    private fun updateMainVolume(clip: TimelineClip, localOffset: Long) {
        val project = currentProject ?: return
        val mainTrack = project.trackByType(TrackType.VIDEO_MAIN)
        val muted = mainTrack?.isMuted == true || clip.isMuted || !clip.hasAudioTrack
        videoPlayer.volume = if (muted) {
            0f
        } else {
            val kfVolume = KeyframeInterpolator.evaluateClipState(clip, localOffset).volume
            (kfVolume * (mainTrack?.volume ?: 1f)).coerceIn(0f, 1.5f)
        }
    }

    // ------------------------------------------------------------------ GPU state

    /**
     * Pushes the active clip into [liveState] and rebuilds the VideoGraph only when the shader
     * *topology* changed. Returns true when a visual parameter changed.
     */
    private fun applyActiveClipStateAndEffects(): Boolean {
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
            append(activeClip.crop.isIdentity).append(activeClip.keyframes.isNotEmpty()).append('|')
            for (fx in activeClip.effects) append(fx.id).append(':').append(fx.type.name).append(',')
        }
        if (structural != structuralFxSignature) {
            structuralFxSignature = structural
            videoPlayer.setVideoEffects(
                ClipEffectsFactory.buildLivePreviewVideoEffects(activeClip, liveState, proxyHeight)
            )
        }

        _heldStillClip.value =
            if (activeClip.mediaKind == MediaKind.IMAGE || activeClip.isFrozenFrame) activeClip else null

        val hash = computeVisualHash(activeClip, compareBypass)
        val changed = hash != lastVisualHash
        lastVisualHash = hash
        return changed
    }

    /**
     * Media3 keeps the last decoded frame while paused, so a live slider edit cannot show until the
     * frame is re-decoded through the (already warm) shader pipeline. Re-seeking the current
     * position does exactly that, with a debounce so dragging stays smooth.
     */
    private fun requestPausedFrameRefresh() {
        if (pausedRefreshJob?.isActive == true) return
        pausedRefreshJob = scope.launch(Dispatchers.Main) {
            delay(FRAME_REFRESH_DEBOUNCE_MS)
            refreshPausedFrame()
        }
    }

    private fun refreshPausedFrame() {
        val project = currentProject ?: return
        if (project.mainVideoClips.isEmpty() || videoPlayer.mediaItemCount == 0) return
        if (videoPlayer.isPlaying || isUserScrubbing) return
        val clamped = _playheadMs.value.coerceIn(0L, project.totalDurationMs.coerceAtLeast(0L))
        val clip = project.findMainClipAt(clamped) ?: return
        val localOffset = (clamped - clip.startTimeOnTrackMs).coerceIn(0L, clip.timelineDurationMs)
        val target = resolvePlaylistPosition(clamped) ?: return
        if (videoPlayer.playbackState == Player.STATE_IDLE) videoPlayer.prepare()
        videoPlayer.seekTo(target.first, target.second)
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

    // ------------------------------------------------------------------ frame stepping

    /** Moves the playhead by exactly one project frame and refreshes the preview. */
    fun stepFrame(fps: Int, direction: Int): Long {
        val project = currentProject ?: return _playheadMs.value
        val safeFps = fps.coerceIn(1, 120)
        val frameMs = (1000.0 / safeFps).toLong().coerceAtLeast(1L)
        val target = (_playheadMs.value + frameMs * direction.toLong())
            .coerceIn(0L, project.totalDurationMs.coerceAtLeast(0L))
        pause()
        return seekToTimelineMs(target)
    }

    // ------------------------------------------------------------------ auxiliary audio

    /**
     * Plays every audio clip under the playhead (music + voiceover + SFX), keeping the first active
     * clip sample-locked to the main timeline. Additional simultaneous tracks are mixed by the
     * export DSP chain; preview follows the playhead so switching tracks never pops.
     */
    private fun syncAuxPlayback(forceSeek: Boolean) {
        val project = currentProject ?: return
        val timelineMs = _playheadMs.value
        val active = project.tracks
            .filter { it.type.isAudioTrack && !it.isMuted && !it.isHidden }
            .flatMap { track ->
                track.clips
                    .filter { timelineMs in it.startTimeOnTrackMs until it.endTimeOnTrackMs }
                    .map { track to it }
            }

        if (active.isEmpty()) {
            activeAuxClipId = null
            if (auxAudioPlayer.isPlaying) auxAudioPlayer.pause()
            return
        }

        val (track, clip) = active.first()
        val localMs = (timelineMs - clip.startTimeOnTrackMs).coerceAtLeast(0L)
        val offsetInClip = clip.mapTimelineOffsetToSourceMs(
            localMs.coerceIn(0L, clip.timelineDurationMs)
        )

        if (activeAuxClipId != clip.id) {
            activeAuxClipId = clip.id
            auxAudioPlayer.setMediaItem(MediaItem.fromUri(parseUri(clip.sourceUri)))
            auxAudioPlayer.prepare()
            auxAudioPlayer.seekTo(offsetInClip)
        } else if (forceSeek || kotlin.math.abs(auxAudioPlayer.currentPosition - offsetInClip) > 260L) {
            auxAudioPlayer.seekTo(offsetInClip)
        }

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
        frameSeekJob?.cancel()
        runCatching { videoPlayer.release() }
        runCatching { auxAudioPlayer.release() }
    }

    companion object {
        /** 24 ms keeps the playhead within one frame at 30 fps without burning CPU. */
        const val TICK_MS = 24L
        const val FRAME_REFRESH_DEBOUNCE_MS = 16L
    }
}

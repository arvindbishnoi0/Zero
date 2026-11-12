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
import com.vibecut.pro.media.shader.ClipEffectsFactory
import com.vibecut.pro.media.shader.LivePreviewStateHolder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.abs

/**
 * Real-time GPU-accelerated preview & multi-track audio synchronization controller.
 *
 * Key upgrades:
 * - Uses [LivePreviewStateHolder] so Filter, Sharpen/Detail, Adjust, Transform, Crop, Transition,
 *   Before/After Compare, and Effect parameter sliders update OpenGL uniforms & the 2D-unwrapped
 *   3D Color LUT in-place without tearing down Media3's `VideoGraph`.
 * - Coalesced 30fps paused-frame GPU re-render (`requestPausedFrameRefresh`) ensures dragging any
 *   slider while paused immediately updates the visible video/photo frame with zero black flash.
 * - Supports both H.264 carrier clips and `MediaKind.IMAGE` fallback items.
 */
@OptIn(UnstableApi::class)
class EditorPreviewController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    val videoPlayer: ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    ).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = false
    }

    private val auxAudioPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = false
    }

    val liveState = LivePreviewStateHolder()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playheadMs = MutableStateFlow(0L)
    val playheadMs: StateFlow<Long> = _playheadMs.asStateFlow()

    private var currentProject: Project? = null
    private var playlistSignature: String = ""
    private var appliedStructuralFxSignature: String = ""
    private var lastVisualParamSignature: Int = 0
    private var activeAuxClipId: String? = null
    private var lowResProxyMode: Boolean = false
    private var compareBypass: Boolean = false
    private var tickerJob: Job? = null
    private var pausedRefreshJob: Job? = null

    init {
        videoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startTicker()
                    syncAuxAudioPlayback(forceSeek = true)
                } else {
                    stopTicker()
                    auxAudioPlayer.pause()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    videoPlayer.pause()
                    auxAudioPlayer.pause()
                    _isPlaying.value = false
                }
            }
        })
    }

    fun syncProject(
        project: Project,
        lowResProxy: Boolean = lowResProxyMode,
        isCompareBypass: Boolean = compareBypass
    ) {
        val prevProject = currentProject
        currentProject = project
        val proxyChanged = lowResProxy != lowResProxyMode
        val compareChanged = isCompareBypass != compareBypass
        lowResProxyMode = lowResProxy
        compareBypass = isCompareBypass

        liveState.customPresets = project.customColorPresets
        liveState.isCompareBypass = isCompareBypass

        val mainClips = project.mainVideoClips
        val newPlaylistSig = mainClips.joinToString("|") {
            "${it.id}:${it.sourceUri}:${it.mediaKind}:${it.trimStartMs}:${it.trimEndMs}:${it.timelineDurationMs}:${it.isReversed}"
        }

        val playlistRebuilt = newPlaylistSig != playlistSignature
        if (playlistRebuilt) {
            playlistSignature = newPlaylistSig
            val wasPlaying = videoPlayer.isPlaying
            val savedTimelineMs = _playheadMs.value

            val items = mainClips.map { clip -> buildMediaItemForClip(clip) }

            videoPlayer.setMediaItems(items, /* resetPosition= */ true)
            if (items.isNotEmpty()) {
                videoPlayer.prepare()
                seekToTimelineMs(savedTimelineMs.coerceIn(0L, project.totalDurationMs.coerceAtLeast(1L)))
                if (wasPlaying) videoPlayer.play()
            } else {
                appliedStructuralFxSignature = ""
            }
        }

        // Apply live state & check if structural or parameter refresh is needed
        val visualChanged = applyActiveClipStateAndEffects(forceRebuild = proxyChanged || playlistRebuilt)
        if ((visualChanged || compareChanged || prevProject == null) && !videoPlayer.isPlaying && mainClips.isNotEmpty()) {
            requestPausedFrameRefresh()
        }
        syncAuxAudioPlayback(forceSeek = false)
    }

    private fun buildMediaItemForClip(clip: TimelineClip): MediaItem {
        val uri = parseClipUri(clip.sourceUri)
        if (clip.mediaKind == MediaKind.IMAGE) {
            return MediaItem.Builder()
                .setUri(uri)
                .setMimeType(clip.mimeType.ifBlank { MimeTypes.IMAGE_JPEG })
                .setImageDurationMs(clip.timelineDurationMs.coerceAtLeast(100L))
                .setMediaId(clip.id)
                .build()
        }

        val endMs = if (clip.isFrozenFrame) {
            (clip.trimStartMs + 66L).coerceAtMost(clip.sourceDurationMs.coerceAtLeast(100L))
        } else {
            clip.effectiveTrimEndMs.coerceAtLeast(clip.trimStartMs + 50L)
        }

        return MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs.coerceAtLeast(0L))
                    .setEndPositionMs(endMs)
                    .build()
            )
            .setMediaId(clip.id)
            .build()
    }

    fun togglePlayPause() {
        val proj = currentProject ?: return
        if (proj.mainVideoClips.isEmpty()) return
        if (videoPlayer.isPlaying) {
            videoPlayer.pause()
            auxAudioPlayer.pause()
        } else {
            if (videoPlayer.playbackState == Player.STATE_ENDED ||
                (proj.totalDurationMs > 0L && _playheadMs.value >= proj.totalDurationMs - 40L)
            ) {
                seekToTimelineMs(0L)
            }
            if (videoPlayer.playbackState == Player.STATE_IDLE) {
                videoPlayer.prepare()
            }
            videoPlayer.play()
        }
    }

    fun pause() {
        if (videoPlayer.isPlaying) {
            videoPlayer.pause()
        }
        auxAudioPlayer.pause()
    }

    fun seekToTimelineMs(timelineMs: Long) {
        val proj = currentProject ?: return
        val clamped = timelineMs.coerceIn(0L, proj.totalDurationMs.coerceAtLeast(0L))
        _playheadMs.value = clamped

        val mainClips = proj.mainVideoClips
        if (mainClips.isEmpty()) return

        var targetIndex = mainClips.indexOfFirst { clamped >= it.startTimeOnTrackMs && clamped < it.endTimeOnTrackMs }
        if (targetIndex < 0) {
            targetIndex = mainClips.indexOfLast { clamped >= it.startTimeOnTrackMs }.coerceAtLeast(0)
        }
        val clip = mainClips[targetIndex]
        val localTimelineOffset = (clamped - clip.startTimeOnTrackMs).coerceIn(0L, clip.timelineDurationMs)
        liveState.activeClip = clip
        liveState.localClipOffsetMs = localTimelineOffset

        val sourcePosInClipMs = if (clip.mediaKind == MediaKind.IMAGE) {
            localTimelineOffset
        } else if (clip.isFrozenFrame) {
            0L
        } else {
            (clip.mapTimelineOffsetToSourceMs(localTimelineOffset) - clip.trimStartMs)
                .coerceIn(0L, clip.trimmedSourceDurationMs)
        }

        if (videoPlayer.mediaItemCount > targetIndex) {
            videoPlayer.seekTo(targetIndex, sourcePosInClipMs)
        }
        applyActiveClipStateAndEffects(forceRebuild = false)
        syncAuxAudioPlayback(forceSeek = true)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Main) {
            while (isActive && videoPlayer.isPlaying) {
                updatePlayheadFromPlayer()
                applyActiveClipStateAndEffects(forceRebuild = false)
                syncAuxAudioPlayback(forceSeek = false)
                delay(33L)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun updatePlayheadFromPlayer() {
        val proj = currentProject ?: return
        val mainClips = proj.mainVideoClips
        val idx = videoPlayer.currentMediaItemIndex
        if (idx !in mainClips.indices) return

        val clip = mainClips[idx]
        val playerPosMs = videoPlayer.currentPosition.coerceAtLeast(0L)

        val currentSpeed = clip.instantaneousSpeedAt(0.5f)
        val timelineOffset = if (clip.mediaKind == MediaKind.IMAGE) {
            playerPosMs.coerceAtMost(clip.timelineDurationMs)
        } else {
            (playerPosMs / currentSpeed).toLong().coerceAtMost(clip.timelineDurationMs)
        }

        val globalMs = clip.startTimeOnTrackMs + timelineOffset
        _playheadMs.value = globalMs
        liveState.activeClip = clip
        liveState.localClipOffsetMs = timelineOffset

        // Live speed ramp & audio volume/ducking modulation
        val normPos = if (clip.timelineDurationMs > 0) {
            timelineOffset.toFloat() / clip.timelineDurationMs.toFloat()
        } else 0f
        val desiredSpeed = if (clip.isFrozenFrame || clip.mediaKind == MediaKind.IMAGE) {
            1.0f
        } else {
            clip.instantaneousSpeedAt(normPos).coerceIn(0.2f, 4.0f)
        }
        if (abs(videoPlayer.playbackParameters.speed - desiredSpeed) > 0.04f) {
            videoPlayer.playbackParameters = PlaybackParameters(desiredSpeed)
        }

        val mainTrack = proj.trackByType(TrackType.VIDEO_MAIN)
        val trackMuted = mainTrack?.isMuted == true || clip.isMuted || !clip.hasAudioTrack
        if (trackMuted) {
            videoPlayer.volume = 0f
        } else {
            val kfVol = KeyframeInterpolator.evaluateClipState(clip, timelineOffset).volume
            val duck = if (clip.audioDucking) AudioDspEngine.computeDuckingGain(proj, globalMs) else 1f
            videoPlayer.volume = (kfVol * (mainTrack?.volume ?: 1f) * duck).coerceIn(0f, 1.5f)
        }
    }

    /**
     * Updates [liveState] immediately and rebuilds Media3's VideoGraph ONLY when structural shader
     * topology changes (clip change, effect added/removed/reordered, or proxy mode toggled).
     * Returns `true` if any visual parameter changed since the last frame.
     */
    private fun applyActiveClipStateAndEffects(forceRebuild: Boolean): Boolean {
        val proj = currentProject ?: return false
        val t = _playheadMs.value
        val activeClip = proj.findMainClipAt(t) ?: return false
        val localOffset = (t - activeClip.startTimeOnTrackMs).coerceIn(0L, activeClip.timelineDurationMs)

        liveState.activeClip = activeClip
        liveState.localClipOffsetMs = localOffset
        updateCustomLutCacheIfNeeded(activeClip.colorGrade.customCubeLutUri, activeClip.colorGrade.lutIntensity)

        val structuralSig = buildString(96) {
            append(activeClip.id).append('|')
            append(lowResProxyMode).append('|')
            append(activeClip.upscaleTargetHeight ?: 0).append('|')
            for (fx in activeClip.effects) {
                append(fx.id).append(':').append(fx.type.name).append(',')
            }
        }

        if (forceRebuild || structuralSig != appliedStructuralFxSignature) {
            appliedStructuralFxSignature = structuralSig
            val effects = ClipEffectsFactory.buildLivePreviewVideoEffects(
                activeClip = activeClip,
                liveState = liveState,
                lowResProxyMode = lowResProxyMode
            )
            videoPlayer.setVideoEffects(effects)
        }

        val visualHash = computeVisualHash(activeClip, proj.customColorPresets.size, compareBypass)
        val changed = visualHash != lastVisualParamSignature
        lastVisualParamSignature = visualHash
        return changed
    }

    /**
     * Pushes the paused decoder frame through the warm OpenGL shader pipeline at up to 30fps
     * so slider drags on paused video or photo clips show immediate live changes with zero flicker.
     */
    private fun requestPausedFrameRefresh() {
        if (pausedRefreshJob?.isActive == true) return
        pausedRefreshJob = scope.launch(Dispatchers.Main) {
            refreshCurrentPausedFrameNow()
            delay(33L)
        }
    }

    private fun refreshCurrentPausedFrameNow() {
        val proj = currentProject ?: return
        val mainClips = proj.mainVideoClips
        if (mainClips.isEmpty() || videoPlayer.mediaItemCount == 0) return

        val clamped = _playheadMs.value.coerceIn(0L, proj.totalDurationMs.coerceAtLeast(0L))
        var targetIndex = mainClips.indexOfFirst { clamped >= it.startTimeOnTrackMs && clamped < it.endTimeOnTrackMs }
        if (targetIndex < 0) {
            targetIndex = mainClips.indexOfLast { clamped >= it.startTimeOnTrackMs }.coerceAtLeast(0)
        }
        if (targetIndex !in 0 until videoPlayer.mediaItemCount) return

        val clip = mainClips[targetIndex]
        val localOffset = (clamped - clip.startTimeOnTrackMs).coerceIn(0L, clip.timelineDurationMs)
        liveState.activeClip = clip
        liveState.localClipOffsetMs = localOffset

        val sourcePosInClipMs = if (clip.mediaKind == MediaKind.IMAGE) {
            localOffset
        } else if (clip.isFrozenFrame) {
            0L
        } else {
            (clip.mapTimelineOffsetToSourceMs(localOffset) - clip.trimStartMs)
                .coerceIn(0L, clip.trimmedSourceDurationMs)
        }

        if (videoPlayer.playbackState == Player.STATE_IDLE) {
            videoPlayer.prepare()
        }
        videoPlayer.seekTo(targetIndex, sourcePosInClipMs)
    }

    private fun updateCustomLutCacheIfNeeded(uriStr: String?, intensity: Float) {
        if (uriStr.isNullOrBlank()) {
            liveState.customLutCubeCacheUri = null
            liveState.customLutCubeCache = null
            return
        }
        if (liveState.customLutCubeCacheUri == uriStr && liveState.customLutCubeCache != null) {
            return
        }
        runCatching {
            val u = parseClipUri(uriStr)
            val text = if (u.scheme == "file" && !u.path.isNullOrBlank()) {
                File(u.path!!).readText()
            } else {
                context.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() }
            }
            if (!text.isNullOrBlank()) {
                liveState.customLutCubeCache = ColorGradingEngine.parseAdobeCubeLut(text, lutIntensity = 1.0f)
                liveState.customLutCubeCacheUri = uriStr
            }
        }
    }

    private fun computeVisualHash(clip: TimelineClip, presetCount: Int, bypass: Boolean): Int {
        var h = clip.id.hashCode()
        h = 31 * h + clip.colorGrade.hashCode()
        h = 31 * h + clip.effects.hashCode()
        h = 31 * h + clip.transform.hashCode()
        h = 31 * h + clip.crop.hashCode()
        h = 31 * h + clip.keyframes.hashCode()
        h = 31 * h + (clip.transitionIn?.hashCode() ?: 0)
        h = 31 * h + (clip.transitionOut?.hashCode() ?: 0)
        h = 31 * h + presetCount
        h = 31 * h + bypass.hashCode()
        return h
    }

    private fun syncAuxAudioPlayback(forceSeek: Boolean) {
        val proj = currentProject ?: return
        val t = _playheadMs.value
        val activeAudioPair = proj.tracks
            .filter { it.type.isAudioTrack && !it.isMuted }
            .flatMap { track -> track.clips.map { track to it } }
            .firstOrNull { (_, clip) -> !clip.isMuted && t in clip.startTimeOnTrackMs until clip.endTimeOnTrackMs }

        if (activeAudioPair == null) {
            activeAuxClipId = null
            if (auxAudioPlayer.isPlaying) auxAudioPlayer.pause()
            return
        }

        val (track, audioClip) = activeAudioPair
        val offsetInAudioMs = (t - audioClip.startTimeOnTrackMs + audioClip.trimStartMs)
            .coerceIn(0L, audioClip.effectiveTrimEndMs)

        if (activeAuxClipId != audioClip.id) {
            activeAuxClipId = audioClip.id
            auxAudioPlayer.setMediaItem(MediaItem.fromUri(parseClipUri(audioClip.sourceUri)))
            auxAudioPlayer.prepare()
            auxAudioPlayer.seekTo(offsetInAudioMs)
        } else if (forceSeek || abs(auxAudioPlayer.currentPosition - offsetInAudioMs) > 250L) {
            auxAudioPlayer.seekTo(offsetInAudioMs)
        }

        val localMs = (t - audioClip.startTimeOnTrackMs).coerceAtLeast(0L)
        val kfVol = KeyframeInterpolator.evaluateClipState(audioClip, localMs).volume
        val duck = if (audioClip.audioDucking) AudioDspEngine.computeDuckingGain(proj, t) else 1f
        auxAudioPlayer.volume = (kfVol * track.volume * duck).coerceIn(0f, 1.5f)

        if (videoPlayer.isPlaying && !auxAudioPlayer.isPlaying) {
            auxAudioPlayer.play()
        } else if (!videoPlayer.isPlaying && auxAudioPlayer.isPlaying) {
            auxAudioPlayer.pause()
        }
    }

    private fun parseClipUri(uriString: String): Uri {
        return if (uriString.startsWith("/")) {
            Uri.fromFile(File(uriString))
        } else {
            Uri.parse(uriString)
        }
    }

    fun release() {
        stopTicker()
        pausedRefreshJob?.cancel()
        videoPlayer.release()
        auxAudioPlayer.release()
    }
}

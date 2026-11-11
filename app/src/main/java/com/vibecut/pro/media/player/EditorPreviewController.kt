package com.vibecut.pro.media.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.vibecut.pro.engine.audio.AudioDspEngine
import com.vibecut.pro.engine.keyframes.KeyframeInterpolator
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.media.shader.ClipEffectsFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinates live multi-track playback using Media3 [ExoPlayer] + real-time OpenGL ES 2.0
 * `setVideoEffects(...)` shaders and synchronized auxiliary audio playback.
 */
@OptIn(UnstableApi::class)
class EditorPreviewController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    val videoPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
    }

    private val auxAudioPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _playheadMs = MutableStateFlow(0L)
    val playheadMs: StateFlow<Long> = _playheadMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var currentProject: Project = Project()
    private var loadedMediaSignature: String = ""
    private var activeEffectClipSignature: String = ""
    private var activeAuxClipId: String? = null
    private var tickerJob: Job? = null

    init {
        videoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                if (!playing && auxAudioPlayer.isPlaying) {
                    auxAudioPlayer.pause()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshActiveClipEffects(force = true)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _isPlaying.value = false
                    auxAudioPlayer.pause()
                }
            }
        })

        tickerJob = scope.launch {
            while (isActive) {
                if (videoPlayer.isPlaying) {
                    val globalMs = computeGlobalPlayheadFromPlayer()
                    _playheadMs.value = globalMs
                    updateLivePlaybackDynamics(globalMs)
                }
                delay(33L)
            }
        }
    }

    fun syncWithProject(project: Project, lowResProxyMode: Boolean = false) {
        currentProject = project
        val mainTrack = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN && !it.isHidden }
        val mainClips = mainTrack?.clips.orEmpty()

        val mediaSig = mainClips.joinToString("|") {
            "${it.id}:${it.sourceUri}:${it.trimStartMs}:${it.trimEndMs}:${it.isFrozenFrame}:${it.freezeFrameSourceMs}"
        }

        if (mediaSig != loadedMediaSignature) {
            val savedPlayhead = _playheadMs.value
            val wasPlaying = videoPlayer.isPlaying
            loadedMediaSignature = mediaSig

            if (mainClips.isEmpty()) {
                videoPlayer.stop()
                videoPlayer.clearMediaItems()
            } else {
                val items = mainClips.map { clip ->
                    val startMs = if (clip.isFrozenFrame) clip.freezeFrameSourceMs else clip.trimStartMs
                    val endMs = if (clip.isFrozenFrame) (clip.freezeFrameSourceMs + 120L) else clip.trimEndMs
                    MediaItem.Builder()
                        .setMediaId(clip.id)
                        .setUri(Uri.parse(clip.sourceUri))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startMs)
                                .setEndPositionMs(endMs.coerceAtLeast(startMs + 80L))
                                .build()
                        )
                        .build()
                }
                videoPlayer.setMediaItems(items, /* resetPosition= */ false)
                videoPlayer.prepare()
                seekToTimelineMs(savedPlayhead.coerceIn(0L, project.totalDurationMs))
                if (wasPlaying) videoPlayer.play()
            }
        }

        refreshActiveClipEffects(force = false, lowResProxyMode = lowResProxyMode)
    }

    fun togglePlayPause() {
        if (currentProject.totalDurationMs <= 0L) return
        if (videoPlayer.isPlaying) {
            videoPlayer.pause()
            auxAudioPlayer.pause()
        } else {
            if (_playheadMs.value >= currentProject.totalDurationMs - 80L) {
                seekToTimelineMs(0L)
            }
            videoPlayer.play()
        }
    }

    fun pause() {
        videoPlayer.pause()
        auxAudioPlayer.pause()
    }

    fun seekToTimelineMs(targetMs: Long) {
        val clamped = targetMs.coerceIn(0L, currentProject.totalDurationMs)
        _playheadMs.value = clamped

        val mainClips = currentProject.tracks
            .firstOrNull { it.type == TrackType.VIDEO_MAIN && !it.isHidden }
            ?.clips.orEmpty()
        if (mainClips.isEmpty()) return

        val targetIndex = mainClips.indexOfFirst { clamped < it.endTimeOnTrackMs }
            .let { if (it < 0) mainClips.lastIndex else it }
        val clip = mainClips[targetIndex]
        val offsetInClipMs = (clamped - clip.startTimeOnTrackMs).coerceIn(0L, clip.timelineDurationMs)
        val playerPosMs = if (clip.isFrozenFrame) 0L else (offsetInClipMs * clip.speed).toLong()

        if (targetIndex in 0 until videoPlayer.mediaItemCount) {
            videoPlayer.seekTo(targetIndex, playerPosMs)
        }
        refreshActiveClipEffects(force = false)
    }

    private fun computeGlobalPlayheadFromPlayer(): Long {
        val mainClips = currentProject.tracks
            .firstOrNull { it.type == TrackType.VIDEO_MAIN && !it.isHidden }
            ?.clips.orEmpty()
        if (mainClips.isEmpty()) return 0L
        val idx = videoPlayer.currentMediaItemIndex.coerceIn(0, mainClips.lastIndex)
        val clip = mainClips[idx]
        val posInClip = (videoPlayer.currentPosition.coerceAtLeast(0L) / clip.speed.coerceAtLeast(0.1f)).toLong()
        return (clip.startTimeOnTrackMs + posInClip).coerceIn(0L, currentProject.totalDurationMs)
    }

    private fun refreshActiveClipEffects(force: Boolean, lowResProxyMode: Boolean = false) {
        val mainTrack = currentProject.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN } ?: return
        val clips = mainTrack.clips
        if (clips.isEmpty()) return

        val idx = videoPlayer.currentMediaItemIndex.coerceIn(0, clips.lastIndex)
        val activeClip = clips[idx]
        val sig = buildString {
            append(activeClip.id)
            append(':').append(activeClip.crop)
            append(':').append(activeClip.transform)
            append(':').append(activeClip.effects)
            append(':').append(activeClip.colorGrade)
            append(':').append(activeClip.transitionIn)
            append(':').append(activeClip.transitionOut)
            append(':').append(activeClip.keyframes.size)
            append(':').append(lowResProxyMode)
        }
        if (!force && sig == activeEffectClipSignature) return
        activeEffectClipSignature = sig

        val proxyHeight = if (lowResProxyMode) 480 else activeClip.upscaleTargetHeight
        val effects = ClipEffectsFactory.buildVideoEffectsList(
            context = context,
            clip = activeClip,
            project = currentProject,
            includeSceneOverlays = false, // Live Compose overlay handles interactive gizmos in preview
            targetExportHeight = proxyHeight
        )
        videoPlayer.setVideoEffects(effects)
    }

    private fun updateLivePlaybackDynamics(globalMs: Long) {
        val mainTrack = currentProject.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN } ?: return
        val clips = mainTrack.clips
        if (clips.isNotEmpty()) {
            val idx = videoPlayer.currentMediaItemIndex.coerceIn(0, clips.lastIndex)
            val clip = clips[idx]
            val localMs = (globalMs - clip.startTimeOnTrackMs).coerceIn(0L, clip.timelineDurationMs)

            // Live speed curve or constant speed
            val targetSpeed = when {
                clip.isFrozenFrame -> 0.1f
                clip.speedCurvePoints.size >= 2 -> {
                    val u = localMs.toFloat() / clip.timelineDurationMs.coerceAtLeast(1L)
                    TimelineClip.sampleSpeedCurve(clip.speedCurvePoints.sortedBy { it.normalizedPosition }, u)
                }
                clip.speedCurvePreset != SpeedCurvePreset.NONE -> {
                    val u = localMs.toFloat() / clip.timelineDurationMs.coerceAtLeast(1L)
                    TimelineClip.sampleSpeedCurve(clip.speedCurvePreset.defaultPoints, u)
                }
                else -> clip.speed
            }.coerceIn(0.15f, 6.0f)

            if (kotlin.math.abs(videoPlayer.playbackParameters.speed - targetSpeed) > 0.04f) {
                videoPlayer.playbackParameters = PlaybackParameters(targetSpeed)
            }

            // Live clip + track + keyframe volume
            val kfVol = KeyframeInterpolator.evaluateClipState(clip, localMs).volume
            val effectiveVol = if (mainTrack.isMuted || clip.isMuted) 0f else (kfVol * mainTrack.volume).coerceIn(0f, 1f)
            videoPlayer.volume = effectiveVol
        }

        // Sync top-priority active auxiliary audio clip (Music / Voiceover / SFX)
        val audioTracks = currentProject.tracks.filter {
            (it.type == TrackType.AUDIO_MUSIC || it.type == TrackType.AUDIO_VOICEOVER || it.type == TrackType.AUDIO_SFX) &&
                !it.isMuted && !it.isHidden
        }
        val activeAudioPair = audioTracks.firstNotNullOfOrNull { tr ->
            tr.clips.firstOrNull { globalMs in it.startTimeOnTrackMs until it.endTimeOnTrackMs }
                ?.let { tr to it }
        }

        if (activeAudioPair == null) {
            if (auxAudioPlayer.isPlaying) auxAudioPlayer.pause()
            activeAuxClipId = null
        } else {
            val (track, audioClip) = activeAudioPair
            val localAudioMs = (globalMs - audioClip.startTimeOnTrackMs).coerceAtLeast(0L)
            if (activeAuxClipId != audioClip.id) {
                activeAuxClipId = audioClip.id
                val item = MediaItem.Builder()
                    .setUri(Uri.parse(audioClip.sourceUri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(audioClip.trimStartMs)
                            .setEndPositionMs(audioClip.trimEndMs)
                            .build()
                    )
                    .build()
                auxAudioPlayer.setMediaItem(item)
                auxAudioPlayer.prepare()
                auxAudioPlayer.seekTo(localAudioMs)
                auxAudioPlayer.play()
            } else if (!auxAudioPlayer.isPlaying && videoPlayer.isPlaying) {
                auxAudioPlayer.seekTo(localAudioMs)
                auxAudioPlayer.play()
            }

            val duck = if (audioClip.audioDucking) {
                AudioDspEngine.computeDuckingGain(currentProject, globalMs)
            } else 1f
            auxAudioPlayer.volume = (audioClip.volume * track.volume * duck).coerceIn(0f, 1f)
        }
    }

    fun release() {
        tickerJob?.cancel()
        videoPlayer.release()
        auxAudioPlayer.release()
    }
}

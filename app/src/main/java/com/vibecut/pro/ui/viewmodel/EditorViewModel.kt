package com.vibecut.pro.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibecut.pro.data.preferences.AppPreferencesRepository
import com.vibecut.pro.data.repository.ProjectRepository
import com.vibecut.pro.engine.audio.AudioDspEngine
import com.vibecut.pro.engine.audio.BuiltInSfxPreset
import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.subtitles.SubtitleCodec
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import com.vibecut.pro.engine.timeline.TimelineEngine
import com.vibecut.pro.engine.timeline.TimelineSessionManager
import com.vibecut.pro.media.audio.MediaAudioTools
import com.vibecut.pro.media.audio.VoiceoverRecorder
import com.vibecut.pro.media.export.ExportProgressState
import com.vibecut.pro.media.export.VibeCutExportEngine
import com.vibecut.pro.media.loader.MediaImportProgress
import com.vibecut.pro.media.loader.MediaSupportManager
import com.vibecut.pro.media.player.EditorPreviewController
import com.vibecut.pro.media.sample.DemoClipGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** Tool sheets reachable from the editor dock. */
enum class EditorSheet {
    NONE,
    MEDIA,
    CLIP,
    TRANSFORM,
    SPEED,
    FILTERS,
    ADJUST,
    DETAIL,
    EFFECTS,
    TRANSITIONS,
    TEXT,
    AUDIO,
    KEYFRAMES,
    ENHANCE,
    EXPORT,
    PROJECT
}

data class EditorUiState(
    val project: Project = Project(),
    val selectedClipId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoCount: Int = 0,
    val redoCount: Int = 0,
    val isCompareOriginal: Boolean = false,
    val showMediaImporterSheet: Boolean = false,
    val mediaImportTargetTrack: TrackType = TrackType.VIDEO_MAIN,
    val deviceMediaLibrary: List<DeviceGalleryItem> = emptyList(),
    val isVoiceRecording: Boolean = false,
    val voiceAmplitude: Float = 0f,
    val isImporting: Boolean = false,
    val toastMessage: String? = null,
    val isHevcSupported: Boolean = false,
    /** Open bottom sheet; committed parameter edits are applied live, this only controls the UI. */
    val openSheet: EditorSheet = EditorSheet.NONE,
    val selectedTrackId: String? = null
) {
    val selectedClip: TimelineClip?
        get() = selectedClipId?.let { project.findClip(it) }

    val selectedTrackType: TrackType?
        get() = selectedClip?.let { project.findTrackForClip(it.id)?.type }

    fun activeVisualClip(playheadMs: Long): TimelineClip? {
        val sel = selectedClipId?.let { project.findClip(it) }
        val selTrack = sel?.let { project.findTrackForClip(it.id) }
        if (sel != null && selTrack != null) {
            if (selTrack.type == TrackType.VIDEO_PIP) return sel
            if (selTrack.type == TrackType.VIDEO_MAIN) {
                return project.findMainClipAt(playheadMs) ?: sel
            }
        }
        return project.findMainClipAt(playheadMs)
    }
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val mediaSupportManager: MediaSupportManager,
    private val mediaAudioTools: MediaAudioTools,
    private val demoClipGenerator: DemoClipGenerator,
    private val exportEngine: VibeCutExportEngine
) : ViewModel() {

    private val session = TimelineSessionManager()
    val previewController = EditorPreviewController(context, viewModelScope)
    private val voiceoverRecorder = VoiceoverRecorder(context)

    private val _uiState = MutableStateFlow(
        EditorUiState(isHevcSupported = exportEngine.isHevcHardwareSupported())
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    val playheadMs: StateFlow<Long> = previewController.playheadMs
    val isPlaying: StateFlow<Boolean> = previewController.isPlaying
    val heldStillClip: StateFlow<TimelineClip?> = previewController.heldStillClip
    val exportState: StateFlow<ExportProgressState> = exportEngine.exportState
    val importProgress: StateFlow<MediaImportProgress> = mediaSupportManager.importProgress

    private var autosaveJob: Job? = null
    private var voiceMeterJob: Job? = null

    init {
        viewModelScope.launch {
            preferencesRepository.preferencesFlow.collect { prefs ->
                previewController.syncProject(
                    project = session.currentProject,
                    isCompareBypass = _uiState.value.isCompareOriginal
                )
                restartAutosaveLoop(prefs.autosaveIntervalSec)
            }
        }
        // Keep the selection in step with the playhead while scrubbing the main track.
        viewModelScope.launch {
            previewController.playheadMs.collect { ms ->
                val proj = session.currentProject
                val curSelId = _uiState.value.selectedClipId
                val curTrack = curSelId?.let { proj.findTrackForClip(it) }
                val followMain = curSelId == null ||
                    curTrack?.type == TrackType.VIDEO_MAIN ||
                    curTrack == null
                if (!followMain) return@collect
                val clipAtPlayhead = proj.findMainClipAt(ms) ?: return@collect
                if (clipAtPlayhead.id != curSelId) {
                    _uiState.update { it.copy(selectedClipId = clipAtPlayhead.id) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ project lifecycle

    fun loadProject(projectId: String) {
        viewModelScope.launch {
            val loaded = projectRepository.loadProject(projectId)
                ?: projectRepository.createNewProject("Untitled Edit", AspectRatioPreset.REELS_9_16)
            session.reset(loaded)
            publishSessionState(selectedClipId = loaded.findMainClipAt(0L)?.id)
            pushProjectToPreview()
        }
    }

    private fun pushProjectToPreview() {
        previewController.syncProject(
            project = session.currentProject,
            isCompareBypass = _uiState.value.isCompareOriginal
        )
    }

    private fun restartAutosaveLoop(intervalSec: Int) {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            while (isActive) {
                delay(intervalSec.coerceIn(3, 60) * 1_000L)
                if (session.hasUnsavedChanges) {
                    projectRepository.saveProject(session.currentProject)
                    session.markSaved()
                }
            }
        }
    }

    fun saveImmediately(summary: String? = null) {
        viewModelScope.launch {
            projectRepository.saveProject(session.currentProject, summary)
            session.markSaved()
            if (summary != null) notifyToast(summary)
        }
    }

    private fun applyMutation(
        actionLabel: String,
        newSelectedClipId: String? = _uiState.value.selectedClipId,
        transform: (Project) -> Project
    ) {
        val next = session.execute(actionLabel, transform)
        val validSelection = newSelectedClipId?.takeIf { next.findClip(it) != null }
            ?: next.findMainClipAt(playheadMs.value)?.id
        publishSessionState(selectedClipId = validSelection)
        pushProjectToPreview()
    }

    private fun applyCoalescedMutation(
        coalesceKey: String,
        actionLabel: String,
        newSelectedClipId: String? = _uiState.value.selectedClipId,
        transform: (Project) -> Project
    ) {
        val next = session.executeCoalesced(coalesceKey, actionLabel, windowMs = 650L, transform = transform)
        val validSelection = newSelectedClipId?.takeIf { next.findClip(it) != null }
            ?: next.findMainClipAt(playheadMs.value)?.id
        publishSessionState(selectedClipId = validSelection)
        pushProjectToPreview()
    }

    private fun publishSessionState(selectedClipId: String? = _uiState.value.selectedClipId) {
        _uiState.update { state ->
            state.copy(
                project = session.currentProject,
                selectedClipId = selectedClipId,
                canUndo = session.canUndo,
                canRedo = session.canRedo,
                undoCount = session.undoDepth,
                redoCount = session.redoDepth
            )
        }
    }

    /** Resolves the clip a visual tool should edit: explicit selection, else the clip under the playhead. */
    private fun resolveTargetVisualClip(): TimelineClip? {
        val target = _uiState.value.activeVisualClip(playheadMs.value) ?: return null
        if (_uiState.value.selectedClipId != target.id) {
            _uiState.update { it.copy(selectedClipId = target.id) }
        }
        return target
    }

    /** The clip any tool sheet operates on (falls back to the clip under the playhead). */
    private fun targetClip(): TimelineClip? =
        _uiState.value.selectedClip ?: _uiState.value.project.findMainClipAt(playheadMs.value)

    // ------------------------------------------------------------------ selection & sheets

    fun selectClip(clipId: String?) {
        _uiState.update { it.copy(selectedClipId = clipId) }
        if (clipId == null) return
        val clip = session.currentProject.findClip(clipId) ?: return
        val track = session.currentProject.findTrackForClip(clipId)
        _uiState.update { it.copy(selectedTrackId = track?.id) }
        if (track?.type == TrackType.VIDEO_MAIN) {
            val curMs = playheadMs.value
            if (curMs < clip.startTimeOnTrackMs || curMs >= clip.endTimeOnTrackMs) {
                previewController.seekToTimelineMs(clip.startTimeOnTrackMs + 10L)
            }
        }
    }

    fun openSheet(sheet: EditorSheet) {
        _uiState.update { it.copy(openSheet = sheet) }
        if (sheet in VISUAL_SHEETS) resolveTargetVisualClip()
    }

    fun closeSheet() {
        _uiState.update { it.copy(openSheet = EditorSheet.NONE) }
    }

    // ------------------------------------------------------------------ transport

    fun togglePlayPause() = previewController.togglePlayPause()

    fun play() = previewController.play()

    fun pause() = previewController.pause()

    fun seekTo(ms: Long) {
        previewController.seekToTimelineMs(ms)
    }

    fun beginScrub() {
        previewController.isUserScrubbing = true
        previewController.pause()
    }

    fun scrubbingTo(ms: Long) {
        previewController.seekToTimelineMs(ms)
    }

    fun endScrub() {
        previewController.isUserScrubbing = false
        previewController.seekToTimelineMs(playheadMs.value)
    }

    fun stepFrames(deltaFrames: Int) {
        val fps = session.currentProject.exportConfig.frameRate.fps
        previewController.stepFrame(fps, if (deltaFrames >= 0) 1 else -1)
        if (kotlin.math.abs(deltaFrames) > 1) {
            repeat(kotlin.math.abs(deltaFrames) - 1) {
                previewController.stepFrame(fps, if (deltaFrames > 0) 1 else -1)
            }
        }
    }

    fun undo() {
        val prev = session.undo() ?: return
        publishSessionState(selectedClipId = prev.findMainClipAt(playheadMs.value)?.id)
        pushProjectToPreview()
    }

    fun redo() {
        val next = session.redo() ?: return
        publishSessionState(selectedClipId = next.findMainClipAt(playheadMs.value)?.id)
        pushProjectToPreview()
    }

    fun setCompareOriginal(compareBypass: Boolean) {
        _uiState.update { it.copy(isCompareOriginal = compareBypass) }
        pushProjectToPreview()
    }

    fun toggleCompareOriginal() = setCompareOriginal(!_uiState.value.isCompareOriginal)

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun notifyToast(msg: String) {
        _uiState.update { it.copy(toastMessage = msg) }
    }

    // ------------------------------------------------------------------ project settings

    fun setAspectRatio(preset: AspectRatioPreset, customRatio: Float? = null) {
        applyMutation("Aspect Ratio") { proj ->
            val next = TimelineEngine.setProjectAspectRatio(proj, preset, customRatio ?: proj.customAspectRatio)
            next.copy(backgroundColorArgb = proj.backgroundColorArgb)
        }
    }

    fun setPreviewQuality(quality: PreviewQuality) {
        applyMutation("Preview Quality") { it.copy(previewQuality = quality) }
    }

    fun setProjectBackgroundColor(argb: Long) {
        applyCoalescedMutation("bg_color", "Canvas Color") { it.copy(backgroundColorArgb = argb) }
    }

    fun renameProject(newTitle: String) {
        applyMutation("Rename Project") { it.copy(title = newTitle.ifBlank { "Untitled Edit" }) }
    }

    fun toggleMagneticTimeline() {
        applyMutation("Magnetic Timeline") { it.copy(magneticTimeline = !it.magneticTimeline) }
    }

    fun toggleSnapping() {
        applyMutation("Snapping") { it.copy(snappingEnabled = !it.snappingEnabled) }
    }

    // ------------------------------------------------------------------ media import

    fun setShowMediaImporterSheet(visible: Boolean, target: TrackType = TrackType.VIDEO_MAIN) {
        _uiState.update { it.copy(showMediaImporterSheet = visible, mediaImportTargetTrack = target) }
        if (visible) refreshDeviceMediaLibrary()
    }

    fun refreshDeviceMediaLibrary() {
        viewModelScope.launch {
            val entries = mediaSupportManager.scanDeviceMediaLibrary(maxPerCategory = 45)
            _uiState.update { state ->
                state.copy(
                    deviceMediaLibrary = entries.map { e ->
                        DeviceGalleryItem(
                            uri = e.uri,
                            displayName = e.displayName,
                            durationMs = e.durationMs,
                            mediaKind = e.mediaKind,
                            width = e.width,
                            height = e.height,
                            sizeBytes = e.sizeBytes,
                            mimeType = e.mimeType
                        )
                    }
                )
            }
        }
    }

    fun importMediaUris(uris: List<Uri>, targetTrackType: TrackType = TrackType.VIDEO_MAIN) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            try {
                val forceAudio = targetTrackType.isAudioTrack
                val batch = mediaSupportManager.importMediaBatch(uris, forceAudio = forceAudio)
                if (batch.imported.isEmpty()) {
                    notifyToast(batch.failures.firstOrNull() ?: "Unsupported or unreadable media file")
                    return@launch
                }

                val visualItems = batch.imported.filter { it.mediaKind != MediaKind.AUDIO }
                val audioItems = batch.imported.filter { it.mediaKind == MediaKind.AUDIO }

                var newlyAddedId: String? = null
                applyMutation("Import ${batch.imported.size} Media") { proj ->
                    var working = proj
                    if (visualItems.isNotEmpty()) {
                        val dest = if (targetTrackType.isVisualTrack) targetTrackType else TrackType.VIDEO_MAIN
                        val visualStart = if (dest == TrackType.VIDEO_MAIN && working.magneticTimeline) {
                            working.trackByType(TrackType.VIDEO_MAIN)?.durationMs ?: 0L
                        } else playheadMs.value
                        working = TimelineEngine.importMediaClips(working, visualItems, dest, visualStart)
                        newlyAddedId = working.findTrackForClipVisual(visualItems.map { it.uri })
                    }
                    if (audioItems.isNotEmpty()) {
                        val dest = if (targetTrackType.isAudioTrack) targetTrackType else TrackType.AUDIO_MUSIC
                        working = TimelineEngine.importMediaClips(working, audioItems, dest, playheadMs.value)
                    }
                    working
                }
                if (newlyAddedId != null) selectClip(newlyAddedId)
                saveImmediately()

                val msg = if (batch.failures.isEmpty()) {
                    "Imported ${batch.imported.size} item(s)"
                } else {
                    "Imported ${batch.imported.size} (${batch.failures.size} skipped)"
                }
                notifyToast(msg)
                _uiState.update { it.copy(showMediaImporterSheet = false) }

                enrichWaveforms(batch.imported)
            } finally {
                _uiState.update { it.copy(isImporting = false) }
            }
        }
    }

    private fun Project.findTrackForClipVisual(uris: List<String>): String? =
        tracks.filter { it.type.isVisual }
            .flatMap { it.clips }
            .lastOrNull { it.sourceUri in uris }?.id

    /** Background audio analysis so track lanes can draw real waveforms and beat markers. */
    private suspend fun enrichWaveforms(imported: List<ImportedMediaSource>) {
        for (src in imported) {
            if (!src.hasAudioTrack) continue
            if (src.mediaKind != MediaKind.VIDEO && src.mediaKind != MediaKind.AUDIO) continue
            runCatching {
                val summary = mediaAudioTools.analyzeAudioTrack(src.uri, maxDecodedSeconds = 45)
                val matching = session.currentProject.tracks
                    .flatMap { it.clips }
                    .lastOrNull { it.sourceUri == src.uri && it.waveformPeaks.isEmpty() }
                    ?: return@runCatching
                if (summary.beatAnalysis.peakEnvelope.isEmpty()) return@runCatching
                val updated = TimelineEngine.updateClip(session.currentProject, matching.id) { c ->
                    c.copy(
                        waveformPeaks = summary.beatAnalysis.peakEnvelope,
                        beatMarkersMs = summary.beatAnalysis.beatTimestampsMs
                    )
                }
                session.executeCoalesced("waveform_${matching.id}", "Waveform Analysis") { updated }
                publishSessionState()
                pushProjectToPreview()
            }
        }
    }

    fun generateAndImportSampleClips() {
        viewModelScope.launch {
            notifyToast("Rendering studio sample clips...")
            val samples = demoClipGenerator.generateSampleVideoClips()
            applyMutation("Add Sample Clips") { proj ->
                TimelineEngine.importMediaClips(proj, samples, TrackType.VIDEO_MAIN)
            }
            saveImmediately()
            notifyToast("Added ${samples.size} sample clips")
            _uiState.update { it.copy(showMediaImporterSheet = false) }
        }
    }

    fun addStudioCanvasBackdrop(
        title: String,
        primaryColorArgb: Int,
        secondaryColorArgb: Int,
        durationMs: Long = 4_500L
    ) {
        viewModelScope.launch {
            val p = session.currentProject
            val dims = AspectRatioPreset.dimensionsForProject(p.aspectRatio, 720, p.customAspectRatio)
            val canvasClip = demoClipGenerator.generateStudioCanvasClip(
                title = title,
                primaryColorArgb = primaryColorArgb,
                secondaryColorArgb = secondaryColorArgb,
                width = dims.first,
                height = dims.second,
                durationMs = durationMs
            )
            applyMutation("Add '$title' Card") { proj ->
                TimelineEngine.importMediaClips(proj, listOf(canvasClip), TrackType.VIDEO_MAIN, proj.totalDurationMs)
            }
            saveImmediately()
            notifyToast("Added '$title' card")
            _uiState.update { it.copy(showMediaImporterSheet = false) }
        }
    }

    // ------------------------------------------------------------------ basic editing

    fun splitSelectedAtPlayhead() {
        val clip = targetClip() ?: return
        val before = session.currentProject.findTrackForClip(clip.id)?.clips?.size ?: 0
        val at = playheadMs.value
        applyMutation("Split") { TimelineEngine.splitClipAt(it, clip.id, at) }
        val after = session.currentProject.findTrackForClip(clip.id)?.clips?.size ?: 0
        if (after == before) notifyToast("Move the playhead inside the clip to split")
    }

    fun splitAllAtPlayhead() {
        var count = 0
        applyMutation("Split All Tracks") { proj ->
            var working = proj
            val at = playheadMs.value
            proj.tracks.forEach { track ->
                track.clips.forEach { clip ->
                    if (at > clip.startTimeOnTrackMs + 80L && at < clip.endTimeOnTrackMs - 80L) {
                        working = TimelineEngine.splitClipAt(working, clip.id, at)
                        count++
                    }
                }
            }
            working
        }
        if (count > 0) notifyToast("Split $count clip(s) at playhead")
    }

    fun trimClip(clipId: String, newTrimStartMs: Long, newTrimEndMs: Long) {
        applyCoalescedMutation("trim_$clipId", "Trim Clip", newSelectedClipId = clipId) {
            TimelineEngine.trimClip(it, clipId, newTrimStartMs, newTrimEndMs)
        }
    }

    fun trimSelectedClip(newTrimStartMs: Long, newTrimEndMs: Long) {
        val clip = targetClip() ?: return
        trimClip(clip.id, newTrimStartMs, newTrimEndMs)
    }

    /** Moves a clip (used by both the timeline drag and the arrange tools). */
    fun moveClip(clipId: String, newStartMs: Long) {
        applyCoalescedMutation("move_$clipId", "Move Clip", newSelectedClipId = clipId) {
            TimelineEngine.moveClipToTimestamp(it, clipId, newStartMs)
        }
    }

    fun nudgeSelectedClipPositionMs(deltaMs: Long) {
        val clip = targetClip() ?: return
        applyMutation("Nudge Clip", newSelectedClipId = clip.id) {
            TimelineEngine.nudgeClipPosition(it, clip.id, deltaMs)
        }
    }

    fun setPhotoOrClipDurationMs(targetDurationMs: Long) {
        val clip = targetClip() ?: return
        applyMutation("Set Duration", newSelectedClipId = clip.id) {
            TimelineEngine.setClipTimelineDuration(it, clip.id, targetDurationMs)
        }
    }

    fun cutMiddleTwentyPercentOfSelected() {
        val clip = targetClip() ?: return
        val cutStartOnTrack = clip.startTimeOnTrackMs + clip.timelineDurationMs * 4 / 10
        val cutEndOnTrack = clip.startTimeOnTrackMs + clip.timelineDurationMs * 6 / 10
        applyMutation("Cut Middle", newSelectedClipId = clip.id) {
            TimelineEngine.cutRangeFromClip(it, clip.id, cutStartOnTrack, cutEndOnTrack)
        }
    }

    fun duplicateSelectedClip() {
        val clip = targetClip() ?: return
        val before = session.currentProject.tracks.flatMap { it.clips }.map { it.id }.toSet()
        applyMutation("Duplicate", newSelectedClipId = clip.id) {
            TimelineEngine.duplicateClip(it, clip.id)
        }
        val duplicate = session.currentProject.tracks.flatMap { it.clips }.firstOrNull { it.id !in before }
        duplicate?.let { selectClip(it.id) }
        notifyToast("Clip duplicated")
    }

    fun deleteSelectedClip() {
        val clip = targetClip() ?: return
        applyMutation("Delete Clip", newSelectedClipId = null) {
            TimelineEngine.deleteClip(it, clip.id)
        }
        notifyToast("Clip deleted")
    }

    fun moveSelectedClipOrder(delta: Int) {
        val clip = targetClip() ?: return
        applyMutation("Reorder Clip", newSelectedClipId = clip.id) {
            TimelineEngine.moveClipOrder(it, clip.id, delta)
        }
    }

    fun mergeAdjacentOnCurrentTrack() {
        val clip = targetClip() ?: return
        val track = session.currentProject.findTrackForClip(clip.id) ?: return
        val idx = track.clips.indexOfFirst { it.id == clip.id }
        val neighbour = track.clips.getOrNull(idx + 1) ?: track.clips.getOrNull(idx - 1) ?: return
        applyMutation("Merge Clips", newSelectedClipId = clip.id) {
            TimelineEngine.mergeClips(it, listOf(clip.id, neighbour.id))
        }
    }

    fun setCropOnSelected(cropRect: CropRect) {
        val clip = targetClip() ?: return
        applyCoalescedMutation("crop_${clip.id}", "Crop", newSelectedClipId = clip.id) {
            TimelineEngine.setCrop(it, clip.id, cropRect)
        }
    }

    fun rotateSelected90(clockwise: Boolean = true) {
        val clip = targetClip() ?: return
        applyMutation("Rotate", newSelectedClipId = clip.id) {
            TimelineEngine.rotateClipQuarterTurn(it, clip.id, clockwise)
        }
    }

    fun flipSelectedHorizontal() {
        val clip = targetClip() ?: return
        applyMutation("Flip Horizontal", newSelectedClipId = clip.id) {
            TimelineEngine.toggleFlipHorizontal(it, clip.id)
        }
    }

    fun flipSelectedVertical() {
        val clip = targetClip() ?: return
        applyMutation("Flip Vertical", newSelectedClipId = clip.id) {
            TimelineEngine.toggleFlipVertical(it, clip.id)
        }
    }

    fun toggleReverseSelected() {
        val clip = targetClip() ?: return
        applyMutation("Reverse Clip", newSelectedClipId = clip.id) {
            TimelineEngine.toggleReverseClip(it, clip.id)
        }
    }

    fun updateSelectedTransform(transform: ClipTransform) {
        val clip = targetClip() ?: return
        applyCoalescedMutation("transform_${clip.id}", "Transform", newSelectedClipId = clip.id) {
            TimelineEngine.setClipTransform(it, clip.id, transform)
        }
    }

    fun dragSelectedOverlayOnCanvas(dxNdc: Float, dyNdc: Float) {
        val clip = _uiState.value.selectedClip ?: return
        val current = clip.transform
        updateSelectedTransform(
            current.copy(
                positionX = (current.positionX + dxNdc).coerceIn(-1f, 1f),
                positionY = (current.positionY + dyNdc).coerceIn(-1f, 1f)
            )
        )
    }

    fun setBlendModeOnSelected(mode: BlendMode) {
        val clip = targetClip() ?: return
        applyCoalescedMutation("blend_${clip.id}", "Blend Mode", newSelectedClipId = clip.id) {
            TimelineEngine.setBlendMode(it, clip.id, mode)
        }
    }

    fun setChromaKeyOnSelected(config: ChromaKeyConfig) {
        val clip = targetClip() ?: return
        applyCoalescedMutation("chroma_${clip.id}", "Chroma Key", newSelectedClipId = clip.id) {
            TimelineEngine.setChromaKey(it, clip.id, config)
        }
    }

    /**
     * Sets the constant speed of the selected clip, or — when [curvePreset] is supplied — installs
     * that ramp preset (its default points) on top of it. Passing `null` keeps whatever ramp the
     * clip already has, so dragging the constant-speed slider never silently drops a ramp.
     */
    fun updateSelectedSpeed(
        speed: Float,
        curvePreset: SpeedCurvePreset? = null,
        curvePoints: List<SpeedPoint> = emptyList()
    ) {
        val clip = targetClip() ?: return
        val nextPreset = curvePreset ?: clip.speedCurvePreset
        val nextPoints = if (curvePreset != null) {
            curvePoints.ifEmpty { curvePreset.defaultPoints }
        } else {
            clip.speedCurvePoints
        }
        applyCoalescedMutation("speed_${clip.id}", "Speed", newSelectedClipId = clip.id) {
            TimelineEngine.setClipSpeed(it, clip.id, speed, nextPreset, nextPoints)
        }
    }

    fun insertFreezeFrame() {
        val clip = targetClip() ?: return
        val before = session.currentProject.tracks.flatMap { it.clips }.map { it.id }.toSet()
        applyMutation("Freeze Frame", newSelectedClipId = clip.id) {
            TimelineEngine.insertFreezeFrame(it, clip.id, playheadMs.value)
        }
        val frozen = session.currentProject.tracks.flatMap { it.clips }
            .firstOrNull { it.id !in before && it.isFrozenFrame }
        if (frozen != null) selectClip(frozen.id)
        notifyToast("Inserted freeze frame")
    }

    fun addTrack(type: TrackType) {
        applyMutation("Add ${type.label} Track") { TimelineEngine.addTrack(it, type) }
    }

    fun toggleTrackLock(trackId: String) {
        applyMutation("Lock Track") { TimelineEngine.toggleTrackLock(it, trackId) }
    }

    fun toggleTrackHidden(trackId: String) {
        applyMutation("Hide Track") { TimelineEngine.toggleTrackHidden(it, trackId) }
    }

    fun toggleTrackMute(trackId: String) {
        applyMutation("Mute Track") { TimelineEngine.toggleTrackMute(it, trackId) }
    }

    fun setTrackVolume(trackId: String, volume: Float) {
        applyCoalescedMutation("track_vol_$trackId", "Track Volume") {
            TimelineEngine.setTrackVolume(it, trackId, volume)
        }
    }

    // ------------------------------------------------------------------ effects

    fun addEffectToSelected(type: EffectType) {
        val clip = targetClip() ?: run {
            notifyToast("Select a video or photo clip first")
            return
        }
        // Defaults chosen so every effect is visible the moment it is added.
        val effect = AppliedEffect(
            type = type,
            enabled = true,
            intensity = type.defaultIntensity,
            secondaryParam = type.defaultSecondary,
            tertiaryParam = type.defaultTertiary
        )
        applyMutation("Add ${type.label}", newSelectedClipId = clip.id) {
            TimelineEngine.addOrUpdateEffect(it, clip.id, effect)
        }
        notifyToast("${type.label} applied")
    }

    fun updateEffectOnSelected(updated: AppliedEffect) {
        val clip = targetClip() ?: return
        applyCoalescedMutation("fx_${updated.id}", "Adjust ${updated.type.label}", newSelectedClipId = clip.id) {
            TimelineEngine.addOrUpdateEffect(it, clip.id, updated)
        }
    }

    fun toggleEffectEnabled(effectId: String, enabled: Boolean) {
        val clip = targetClip() ?: return
        applyMutation("Toggle Effect", newSelectedClipId = clip.id) {
            TimelineEngine.setEffectEnabled(it, clip.id, effectId, enabled)
        }
    }

    fun reorderEffectOnSelected(effectId: String, delta: Int) {
        val clip = targetClip() ?: return
        applyMutation("Reorder Effect", newSelectedClipId = clip.id) {
            TimelineEngine.reorderEffect(it, clip.id, effectId, delta)
        }
    }

    fun removeEffectFromSelected(effectId: String) {
        val clip = targetClip() ?: return
        applyMutation("Remove Effect", newSelectedClipId = clip.id) {
            TimelineEngine.removeEffect(it, clip.id, effectId)
        }
    }

    fun clearEffectsOnSelected() {
        val clip = targetClip() ?: return
        applyMutation("Clear Effects", newSelectedClipId = clip.id) {
            TimelineEngine.clearClipEffects(it, clip.id)
        }
    }

    fun applyEffectsToAllClips() {
        val clip = targetClip() ?: return
        applyMutation("Apply Effects to All", newSelectedClipId = clip.id) {
            TimelineEngine.applyEffectsToAllMainClips(it, clip.effects)
        }
        notifyToast("Applied ${clip.effects.size} effect(s) to all clips")
    }

    // ------------------------------------------------------------------ colour grading

    fun updateColorGradeOnSelected(grade: ColorGradeConfig, coalesceKey: String = "grade") {
        val clip = targetClip() ?: run {
            notifyToast("Add a video or photo first")
            return
        }
        applyCoalescedMutation(
            coalesceKey = "${coalesceKey}_${clip.id}",
            actionLabel = "Adjust Look",
            newSelectedClipId = clip.id
        ) {
            TimelineEngine.setColorGrade(it, clip.id, grade)
        }
    }

    fun applyColorPresetOnSelected(preset: ColorGradePreset?) {
        val clip = targetClip() ?: return
        val grade = if (preset == null) {
            clip.colorGrade.copy(lutPresetId = null, lutIntensity = 1f)
        } else {
            clip.colorGrade.copy(lutPresetId = preset.id, lutIntensity = 1f)
        }
        applyMutation("Filter: ${preset?.name ?: "Original"}", newSelectedClipId = clip.id) {
            TimelineEngine.setColorGrade(it, clip.id, grade)
        }
    }

    fun updateFilterIntensityOnSelected(intensity: Float) {
        val clip = targetClip() ?: return
        applyCoalescedMutation("lut_mix_${clip.id}", "Filter Strength", newSelectedClipId = clip.id) {
            TimelineEngine.setColorGrade(it, clip.id, clip.colorGrade.copy(lutIntensity = intensity))
        }
    }

    fun resetColorSectionOnSelected(section: String) {
        val clip = targetClip() ?: return
        val g = clip.colorGrade
        val reset = when (section) {
            "light" -> g.copy(exposure = 0f, brightness = 0f, contrast = 0f, highlights = 0f, shadows = 0f, whites = 0f, blacks = 0f, gamma = 0f, fade = 0f)
            "color" -> g.copy(saturation = 0f, vibrance = 0f, temperature = 0f, tint = 0f, hueShift = 0f)
            "detail" -> g.resetDetailOnly()
            "curves" -> g.copy(curves = RgbCurvesConfig(), colorWheels = ColorWheelsConfig())
            "hsl" -> g.copy(hslBands = HslBand.entries.map { HslBandAdjustment(it) })
            else -> ColorGradeConfig()
        }
        applyMutation("Reset ${section.replaceFirstChar { it.uppercase() }}", newSelectedClipId = clip.id) {
            TimelineEngine.setColorGrade(it, clip.id, reset)
        }
    }

    fun resetAllGradingOnSelected() {
        val clip = targetClip() ?: return
        applyMutation("Reset All Color", newSelectedClipId = clip.id) {
            TimelineEngine.setColorGrade(it, clip.id, ColorGradeConfig())
        }
        notifyToast("Color reset")
    }

    fun applyColorGradeToAllClips() {
        val clip = targetClip() ?: return
        applyMutation("Apply Look to All", newSelectedClipId = clip.id) {
            TimelineEngine.applyColorGradeToAllMainClips(it, clip.colorGrade)
        }
        notifyToast("Look applied to all clips")
    }

    fun importCubeLutUriOnSelected(uri: Uri) {
        val clip = targetClip() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: return@runCatching null
                ColorGradingEngine.parseAdobeCubeLut(text)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (parsed == null) {
                    notifyToast("Could not read that .cube LUT")
                    return@withContext
                }
                applyMutation("Load LUT", newSelectedClipId = clip.id) {
                    TimelineEngine.setColorGrade(
                        it, clip.id,
                        clip.colorGrade.copy(customCubeLutUri = uri.toString(), lutPresetId = null, lutIntensity = 1f)
                    )
                }
                notifyToast("LUT loaded: ${parsed.title} (${parsed.size}³)")
            }
        }
    }

    fun saveCustomColorPreset(name: String) {
        val clip = targetClip() ?: return
        val preset = ColorGradePreset(
            id = "custom-${UUID.randomUUID()}",
            name = name.ifBlank { "My Look" },
            category = "Custom",
            config = clip.colorGrade.copy(lutPresetId = null, customCubeLutUri = null),
            isUserCustom = true
        )
        applyMutation("Save Preset") { TimelineEngine.addCustomColorPreset(it, preset) }
        notifyToast("Saved '${preset.name}'")
    }

    fun deleteCustomColorPreset(presetId: String) {
        applyMutation("Delete Preset") { TimelineEngine.deleteCustomColorPreset(it, presetId) }
    }

    // ------------------------------------------------------------------ transitions & keyframes

    fun setTransitionOnSelected(transition: ClipTransition?) {
        val clip = targetClip() ?: return
        applyMutation(if (transition == null) "Remove Transition" else "Transition: ${transition.type.label}",
            newSelectedClipId = clip.id) {
            TimelineEngine.setClipTransition(it, clip.id, transition)
        }
    }

    fun applyTransitionToAllClips(transition: ClipTransition) {
        applyMutation("Transition to All") { TimelineEngine.applyTransitionToAllMainClips(it, transition) }
        notifyToast("${transition.type.label} applied to all clips")
    }

    fun upsertKeyframeAtPlayhead(
        property: KeyframeProperty,
        value: Float,
        easing: EasingCurve = EasingCurve.EASE_IN_OUT
    ) {
        val clip = targetClip() ?: return
        val localOffset = (playheadMs.value - clip.startTimeOnTrackMs).coerceIn(0L, clip.timelineDurationMs)
        // Replacing an existing keyframe at the same time makes the button behave like an "add/update".
        val existing = clip.keyframes.firstOrNull {
            it.property == property && kotlin.math.abs(it.timeOffsetMs - localOffset) < 40L
        }
        val kf = Keyframe(
            id = existing?.id ?: UUID.randomUUID().toString(),
            property = property,
            timeOffsetMs = localOffset,
            value = value,
            easing = easing
        )
        applyMutation("Keyframe ${property.label}", newSelectedClipId = clip.id) {
            val cleaned = if (existing != null) {
                TimelineEngine.removeKeyframe(it, clip.id, existing.id)
            } else it
            TimelineEngine.upsertKeyframe(cleaned, clip.id, kf)
        }
        notifyToast("${property.label} keyframe @ ${localOffset / 1000f}s")
    }

    fun removeKeyframe(keyframeId: String) {
        val clip = targetClip() ?: return
        applyMutation("Delete Keyframe", newSelectedClipId = clip.id) {
            TimelineEngine.removeKeyframe(it, clip.id, keyframeId)
        }
    }

    fun clearKeyframesOnSelected() {
        val clip = targetClip() ?: return
        applyMutation("Clear Keyframes", newSelectedClipId = clip.id) {
            TimelineEngine.clearKeyframes(it, clip.id)
        }
    }

    // ------------------------------------------------------------------ audio

    fun extractAudioFromSelectedClip() {
        val clip = targetClip() ?: return
        if (!clip.hasAudioTrack) {
            notifyToast("This clip has no audio track")
            return
        }
        applyMutation("Extract Audio") { proj ->
            TimelineEngine.importMediaClips(
                proj,
                listOf(
                    ImportedMediaSource(
                        uri = clip.sourceUri,
                        displayName = "${clip.sourceName} (audio)",
                        mediaKind = MediaKind.AUDIO,
                        durationMs = clip.trimmedSourceDurationMs,
                        mimeType = clip.mimeType,
                        hasAudioTrack = true
                    )
                ),
                TrackType.AUDIO_MUSIC,
                clip.startTimeOnTrackMs
            )
        }
        notifyToast("Audio extracted to Music track")
    }

    fun toggleVoiceoverRecording() {
        if (_uiState.value.isVoiceRecording) {
            val result = voiceoverRecorder.stopRecording()
            voiceMeterJob?.cancel()
            _uiState.update { it.copy(isVoiceRecording = false, voiceAmplitude = 0f) }
            val file = result?.first
            val recordedMs = result?.second ?: 0L
            if (file != null && file.exists() && file.length() > 1024) {
                val startAt = playheadMs.value
                viewModelScope.launch {
                    val imported = mediaSupportManager.importSingleUri(Uri.fromFile(file))
                        .getOrNull()
                        ?.let { it.copy(durationMs = recordedMs.coerceAtLeast(500L), hasAudioTrack = true) }
                    if (imported != null) {
                        applyMutation("Add Voiceover") { proj ->
                            TimelineEngine.importMediaClips(
                                proj, listOf(imported), TrackType.AUDIO_VOICEOVER, startAt
                            )
                        }
                        saveImmediately()
                        notifyToast("Voiceover added (${recordedMs / 1000f}s)")
                    } else {
                        notifyToast("Voiceover recorded but could not be imported")
                    }
                }
            } else {
                notifyToast("Recording too short")
            }
        } else {
            val startedFile = voiceoverRecorder.startRecording()
            if (startedFile == null) {
                notifyToast("Microphone permission required")
                return
            }
            _uiState.update { it.copy(isVoiceRecording = true) }
            voiceMeterJob?.cancel()
            voiceMeterJob = viewModelScope.launch {
                while (isActive) {
                    _uiState.update { it.copy(voiceAmplitude = voiceoverRecorder.pollNormalizedAmplitude()) }
                    delay(90L)
                }
            }
        }
    }

    fun addSynthesizedSfx(preset: BuiltInSfxPreset) {
        viewModelScope.launch {
            val path = mediaAudioTools.materializeSfxFile(preset)
            if (path == null) {
                notifyToast("Could not generate SFX")
                return@launch
            }
            val source = ImportedMediaSource(
                uri = path.absolutePath,
                displayName = preset.title,
                mediaKind = MediaKind.GENERATED_SFX,
                durationMs = preset.durationMs.toLong(),
                mimeType = "audio/wav",
                hasAudioTrack = true
            )
            applyMutation("Add ${preset.title}") {
                TimelineEngine.importMediaClips(it, listOf(source), TrackType.AUDIO_SFX, playheadMs.value)
            }
            notifyToast("${preset.title} added")
        }
    }

    fun updateSelectedAudioDsp(
        volume: Float? = null,
        fadeInMs: Long? = null,
        fadeOutMs: Long? = null,
        noiseReduction: Float? = null,
        voiceEnhancement: Float? = null,
        audioDucking: Boolean? = null,
        isMuted: Boolean? = null
    ) {
        val clip = targetClip() ?: return
        applyCoalescedMutation("audio_${clip.id}", "Audio Mix", newSelectedClipId = clip.id) { proj ->
            TimelineEngine.updateClip(proj, clip.id) { c ->
                c.copy(
                    volume = volume ?: c.volume,
                    fadeInMs = fadeInMs ?: c.fadeInMs,
                    fadeOutMs = fadeOutMs ?: c.fadeOutMs,
                    noiseReduction = noiseReduction ?: c.noiseReduction,
                    voiceEnhancement = voiceEnhancement ?: c.voiceEnhancement,
                    audioDucking = audioDucking ?: c.audioDucking,
                    isMuted = isMuted ?: c.isMuted
                )
            }
        }
    }

    fun detectBeatsAndAutoSync() {
        viewModelScope.launch {
            val musicClip = session.currentProject.tracks
                .firstOrNull { it.type == TrackType.AUDIO_MUSIC }?.clips?.firstOrNull()
                ?: _uiState.value.selectedClip
            if (musicClip == null) {
                notifyToast("Add a music clip first")
                return@launch
            }
            notifyToast("Analyzing transients...")
            val summary = mediaAudioTools.analyzeAudioTrack(musicClip.sourceUri)
            val beats = summary.beatAnalysis.beatTimestampsMs
            if (beats.size < 2) {
                notifyToast("Estimated ${summary.beatAnalysis.estimatedBpm} BPM")
                return@launch
            }
            applyMutation("Beat Sync (${summary.beatAnalysis.estimatedBpm} BPM)") { proj ->
                val marked = TimelineEngine.updateClip(proj, musicClip.id) {
                    it.copy(beatMarkersMs = beats, waveformPeaks = summary.beatAnalysis.peakEnvelope)
                }
                AudioDspEngine.syncVideoClipsToBeats(marked, beats, beatsPerCut = 2)
            }
            notifyToast("Synced to ${beats.size} beats (${summary.beatAnalysis.estimatedBpm} BPM)")
        }
    }

    // ------------------------------------------------------------------ text / stickers / subtitles

    fun addTextClip(spec: TextStyleSpec = TextStyleSpec()) {
        var addedId: String? = null
        applyMutation("Add Text") { proj ->
            val next = TimelineEngine.addTextClip(proj, spec, playheadMs.value, 3_000L)
            addedId = next.trackByType(TrackType.TEXT)?.clips?.lastOrNull()?.id
            next
        }
        addedId?.let { selectClip(it) }
    }

    fun updateSelectedTextSpec(spec: TextStyleSpec) {
        val clip = _uiState.value.selectedClip ?: return
        applyCoalescedMutation("text_${clip.id}", "Edit Text", newSelectedClipId = clip.id) {
            TimelineEngine.updateClip(it, clip.id) { c -> c.copy(textSpec = spec) }
        }
    }

    fun addSticker(sticker: StickerSpec) {
        applyMutation("Add Sticker") {
            TimelineEngine.addStickerClip(it, sticker, playheadMs.value)
        }
    }

    fun addOrUpdateSubtitleCue(cue: SubtitleCue) {
        applyMutation("Subtitle") { TimelineEngine.upsertSubtitleCue(it, cue) }
    }

    fun deleteSubtitleCue(cueId: String) {
        applyMutation("Delete Subtitle") { TimelineEngine.deleteSubtitleCue(it, cueId) }
    }

    fun importSrtUri(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                notifyToast("Could not read subtitle file")
                return@launch
            }
            val cues = SubtitleCodec.parseSrt(text)
            if (cues.isEmpty()) {
                notifyToast("No cues found in that file")
                return@launch
            }
            applyMutation("Import ${cues.size} Subtitles") { proj ->
                cues.fold(proj) { acc, cue -> TimelineEngine.upsertSubtitleCue(acc, cue) }
            }
            notifyToast("Imported ${cues.size} subtitle cues")
        }
    }

    fun createExportSrtShareIntent(): Intent? {
        val cues = session.currentProject.subtitles
        if (cues.isEmpty()) return null
        val srtText = SubtitleCodec.exportSrt(cues)
        val file = File(context.cacheDir, "${session.currentProject.title.take(20)}_subtitles.srt")
        file.writeText(srtText)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ------------------------------------------------------------------ keyframe editing

    /** Changes the value of an existing keyframe (the old UI could only add and delete). */
    fun updateKeyframeValue(keyframeId: String, value: Float) {
        val clip = targetClip() ?: return
        val existing = clip.keyframes.firstOrNull { it.id == keyframeId } ?: return
        applyCoalescedMutation("kf_$keyframeId", "Keyframe ${existing.property.label}", clip.id) { proj ->
            TimelineEngine.upsertKeyframe(
                proj, clip.id,
                existing.copy(value = value.coerceIn(existing.property.minValue, existing.property.maxValue))
            )
        }
    }

    /** Changes the easing curve of an existing keyframe. */
    fun updateKeyframeEasing(keyframeId: String, easing: EasingCurve) {
        val clip = targetClip() ?: return
        val existing = clip.keyframes.firstOrNull { it.id == keyframeId } ?: return
        applyMutation("Keyframe easing: ${easing.label}", newSelectedClipId = clip.id) { proj ->
            TimelineEngine.upsertKeyframe(proj, clip.id, existing.copy(easing = easing))
        }
    }

    /** Slides a keyframe along the clip's own timeline. */
    fun moveKeyframeTime(keyframeId: String, newOffsetMs: Long) {
        val clip = targetClip() ?: return
        val existing = clip.keyframes.firstOrNull { it.id == keyframeId } ?: return
        val clamped = newOffsetMs.coerceIn(0L, clip.timelineDurationMs)
        applyCoalescedMutation("kft_$keyframeId", "Move keyframe", clip.id) { proj ->
            TimelineEngine.upsertKeyframe(proj, clip.id, existing.copy(timeOffsetMs = clamped))
        }
    }

    /** Slow push-in across the whole clip — the classic photo-montage move. */
    fun applyKenBurnsPreset() {
        val clip = targetClip() ?: return
        val end = clip.timelineDurationMs
        applyMutation("Ken Burns", newSelectedClipId = clip.id) { proj ->
            var next = TimelineEngine.clearKeyframes(proj, clip.id)
            next = TimelineEngine.upsertKeyframe(next, clip.id, Keyframe(property = KeyframeProperty.SCALE, timeOffsetMs = 0L, value = 1.0f, easing = EasingCurve.LINEAR))
            next = TimelineEngine.upsertKeyframe(next, clip.id, Keyframe(property = KeyframeProperty.SCALE, timeOffsetMs = end, value = 1.18f, easing = EasingCurve.LINEAR))
            next = TimelineEngine.upsertKeyframe(next, clip.id, Keyframe(property = KeyframeProperty.POSITION_X, timeOffsetMs = 0L, value = -0.04f, easing = EasingCurve.LINEAR))
            TimelineEngine.upsertKeyframe(next, clip.id, Keyframe(property = KeyframeProperty.POSITION_X, timeOffsetMs = end, value = 0.04f, easing = EasingCurve.LINEAR))
        }
        notifyToast("Ken Burns added")
    }

    fun applyFadePreset(fadeIn: Boolean) {
        val clip = targetClip() ?: return
        val span = minOf(600L, clip.timelineDurationMs / 3).coerceAtLeast(120L)
        applyMutation(if (fadeIn) "Fade in" else "Fade out", newSelectedClipId = clip.id) { proj ->
            val a = if (fadeIn) 0L else clip.timelineDurationMs - span
            val b = if (fadeIn) span else clip.timelineDurationMs
            var next = TimelineEngine.upsertKeyframe(proj, clip.id, Keyframe(property = KeyframeProperty.OPACITY, timeOffsetMs = a, value = if (fadeIn) 0f else 1f, easing = EasingCurve.EASE_IN_OUT))
            next = TimelineEngine.upsertKeyframe(next, clip.id, Keyframe(property = KeyframeProperty.OPACITY, timeOffsetMs = b, value = if (fadeIn) 1f else 0f, easing = EasingCurve.EASE_IN_OUT))
            next
        }
    }

    fun applyPopInPreset() {
        val clip = targetClip() ?: return
        applyMutation("Pop in", newSelectedClipId = clip.id) { proj ->
            var next = TimelineEngine.upsertKeyframe(proj, clip.id, Keyframe(property = KeyframeProperty.SCALE, timeOffsetMs = 0L, value = 0.6f, easing = EasingCurve.SNAP))
            next = TimelineEngine.upsertKeyframe(next, clip.id, Keyframe(property = KeyframeProperty.SCALE, timeOffsetMs = 320L, value = 1f, easing = EasingCurve.SNAP))
            next = TimelineEngine.upsertKeyframe(next, clip.id, Keyframe(property = KeyframeProperty.OPACITY, timeOffsetMs = 0L, value = 0f, easing = EasingCurve.EASE_OUT))
            TimelineEngine.upsertKeyframe(next, clip.id, Keyframe(property = KeyframeProperty.OPACITY, timeOffsetMs = 220L, value = 1f, easing = EasingCurve.EASE_OUT))
        }
    }

    // ------------------------------------------------------------------ smart editing tools

    /** Drops one transition on every cut of the main track in a single action. */
    fun applyTransitionToAllCuts(type: TransitionType) {
        val cuts = (session.currentProject.mainVideoClips.size - 1).coerceAtLeast(0)
        if (cuts == 0) {
            notifyToast("Need at least two clips")
            return
        }
        applyMutation("${type.label} on all cuts") {
            TimelineEngine.applyTransitionToAllCuts(it, type)
        }
        notifyToast("${type.label} applied to $cuts cut${if (cuts == 1) "" else "s"}")
    }

    fun clearAllTransitions() {
        applyMutation("Clear transitions") { TimelineEngine.clearAllTransitions(it) }
        notifyToast("Transitions cleared")
    }

    /**
     * **Auto-cut silence.** Decodes the clip's audio, turns the peak envelope into a dBFS
     * loudness curve and removes every quiet gap, so talking-head footage tightens up in one tap.
     */
    fun autoCutSilenceOnSelected() {
        val clip = targetClip() ?: run { notifyToast("Select a clip first"); return }
        if (!clip.hasAudioTrack) {
            notifyToast("This clip has no audio")
            return
        }
        viewModelScope.launch {
            notifyToast("Listening for silence...")
            val summary = mediaAudioTools.analyzeAudioTrack(clip.sourceUri)
            val levelsDb = summary.beatAnalysis.peakEnvelope.map { peak ->
                if (peak <= 0.0001f) -90f else (20.0 * kotlin.math.log10(peak.toDouble())).toFloat()
            }
            if (levelsDb.size < 8) {
                notifyToast("Could not read the audio")
                return@launch
            }
            val before = session.currentProject.findTrackForClip(clip.id)?.clips?.size ?: 0
            applyMutation("Auto-cut silence", newSelectedClipId = clip.id) { proj ->
                TimelineEngine.autoCutSilence(proj, clip.id, levelsDb)
            }
            val after = session.currentProject.findTrackForClip(clip.id)?.clips?.size ?: before
            if (after > before) {
                notifyToast("Removed ${after - before} silent gap${if (after - before == 1) "" else "s"}")
            } else {
                notifyToast("No silence long enough to cut")
            }
            saveImmediately("Auto-cut silence")
        }
    }

    /** Nudges every cut on the main track onto the nearest detected beat. */
    fun snapCutsToBeats() {
        val beats = session.currentProject.tracks
            .flatMap { it.clips }
            .firstOrNull { it.beatMarkersMs.size >= 2 }
            ?.beatMarkersMs
        if (beats == null) {
            notifyToast("Run 'Detect beats' first")
            return
        }
        applyMutation("Snap cuts to beats") { TimelineEngine.snapCutsToBeats(it, beats) }
        notifyToast("Cuts snapped to the beat")
    }

    /**
     * **Auto captions.** Uses the voice-activity windows from the decoder to lay real, editable
     * text clips on the TEXT track.
     */
    fun generateAutoCaptions() {
        val clip = targetClip() ?: session.currentProject.mainVideoClips.firstOrNull()
        if (clip == null) {
            notifyToast("Add a clip first")
            return
        }
        viewModelScope.launch {
            notifyToast("Detecting speech...")
            val summary = mediaAudioTools.analyzeAudioTrack(clip.sourceUri)
            val cues = summary.vadIntervalsMs
                .filter { it.last - it.first >= 400L }
                .mapIndexed { index, range ->
                    SubtitleCue(
                        text = "Caption ${index + 1}",
                        startTimeMs = clip.startTimeOnTrackMs + range.first,
                        endTimeMs = clip.startTimeOnTrackMs + range.last
                    )
                }
            if (cues.isEmpty()) {
                notifyToast("No speech detected")
                return@launch
            }
            applyMutation("Auto captions") { proj ->
                TimelineEngine.buildCaptionClips(
                    proj,
                    cues,
                    TextStyleSpec(
                        templateId = "subtitle",
                        fontSizeSp = 22f,
                        outlineWidth = 2f,
                        outlineColorArgb = 0xCC000000
                    )
                ).copy(subtitles = cues)
            }
            notifyToast("${cues.size} caption placeholders added — tap each to edit")
        }
    }

    // ------------------------------------------------------------------ copy / paste attributes

    private var copiedAttributes: TimelineEngine.ClipAttributes? = null

    val hasCopiedAttributes: Boolean get() = copiedAttributes != null

    fun copySelectedClipAttributes() {
        val clip = targetClip() ?: run { notifyToast("Select a clip first"); return }
        copiedAttributes = TimelineEngine.copyClipAttributes(session.currentProject, clip.id)
        notifyToast(if (copiedAttributes != null) "Look copied from ${clip.sourceName}" else "Nothing to copy")
    }

    fun pasteAttributesOnSelected() {
        val attrs = copiedAttributes ?: run { notifyToast("Copy a clip's look first"); return }
        val clip = targetClip() ?: return
        applyMutation("Paste look", newSelectedClipId = clip.id) {
            TimelineEngine.pasteClipAttributes(it, listOf(clip.id), attrs)
        }
        notifyToast("Look pasted")
    }

    fun pasteAttributesToAllClips() {
        val attrs = copiedAttributes ?: run { notifyToast("Copy a clip's look first"); return }
        val ids = session.currentProject.mainVideoClips.map { it.id }
        if (ids.isEmpty()) return
        applyMutation("Paste look to all") {
            TimelineEngine.pasteClipAttributes(it, ids, attrs)
        }
        notifyToast("Look pasted to ${ids.size} clips")
    }

    // ------------------------------------------------------------------ track solo

    private val soloedTrackIds = MutableStateFlow<Set<String>>(emptySet())

    fun isTrackSoloed(trackId: String): Boolean = trackId in soloedTrackIds.value

    /**
     * Real solo: muting every other audio track, and restoring them when solo is released.
     * (The Solo chip used to be wired to an empty lambda.)
     */
    fun toggleTrackSolo(trackId: String) {
        val wasSoloed = trackId in soloedTrackIds.value
        val next = if (wasSoloed) emptySet() else setOf(trackId)
        soloedTrackIds.value = next
        applyMutation(if (wasSoloed) "Unsolo track" else "Solo track") { proj ->
            proj.copy(
                tracks = proj.tracks.map { track ->
                    if (!track.type.isAudioTrack) track
                    else track.copy(isMuted = next.isNotEmpty() && track.id !in next)
                }
            )
        }
    }

    // ------------------------------------------------------------------ enhancement

    fun updateEnhancement(config: EnhancementConfig) {
        applyCoalescedMutation("enhancement", "Enhancement") { it.copy(enhancement = config) }
    }

    // ------------------------------------------------------------------ export

    fun updateExportConfig(config: ExportConfig) {
        applyMutation("Export Settings") { it.copy(exportConfig = config) }
    }

    fun startExport() {
        previewController.pause()
        val cfg = session.currentProject.exportConfig
        val currentPlayhead = playheadMs.value
        viewModelScope.launch {
            projectRepository.saveProject(session.currentProject, "Pre-export save")
            session.markSaved()
            exportEngine.exportProject(session.currentProject, cfg, currentPlayhead)
        }
    }

    fun quickCaptureGradedPhotoFrame() {
        previewController.pause()
        val photoCfg = session.currentProject.exportConfig.copy(
            exportAsPhotoFrame = true,
            audioOnly = false,
            saveToGallery = true
        )
        val currentPlayhead = playheadMs.value
        viewModelScope.launch {
            val outcome = exportEngine.exportProject(session.currentProject, photoCfg, currentPlayhead)
            when (outcome) {
                is ExportProgressState.Completed -> {
                    notifyToast("Saved photo frame to Gallery")
                    exportEngine.resetState()
                }
                is ExportProgressState.Error -> {
                    notifyToast(outcome.message)
                    exportEngine.resetState()
                }
                else -> Unit
            }
        }
    }

    fun cancelExport() = exportEngine.cancelActiveExport()

    fun resetExportStatus() = exportEngine.resetState()

    fun buildShareExportIntent(
        path: String,
        audioOnly: Boolean,
        isPhotoFrame: Boolean = false
    ): Intent? = exportEngine.buildShareIntent(path, audioOnly, isPhotoFrame)

    fun buildViewExportIntent(
        path: String,
        audioOnly: Boolean,
        isPhotoFrame: Boolean = false
    ): Intent? = exportEngine.buildViewIntent(path, audioOnly, isPhotoFrame)

    override fun onCleared() {
        super.onCleared()
        runCatching { voiceoverRecorder.stopRecording() }
        previewController.release()
    }

    companion object {
        private val VISUAL_SHEETS = setOf(
            EditorSheet.FILTERS,
            EditorSheet.DETAIL,
            EditorSheet.ADJUST,
            EditorSheet.EFFECTS,
            EditorSheet.TRANSITIONS,
            EditorSheet.TRANSFORM,
            EditorSheet.KEYFRAMES
        )
    }
}


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
import com.vibecut.pro.engine.enhance.EnhancementPlan
import com.vibecut.pro.engine.enhance.EnhancementPlanner
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
import com.vibecut.pro.ui.editor.timeline.EditorSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** A gallery/video item discovered on the device, ready for one-tap import. */
data class DeviceGalleryItem(
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val mediaKind: MediaKind,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val mimeType: String = ""
)

data class EditorUiState(
    val project: Project = Project(),
    val selectedClipId: String? = null,
    val openSheet: EditorSheet = EditorSheet.NONE,
    val mediaTargetTrack: TrackType = TrackType.VIDEO_MAIN,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoCount: Int = 0,
    val redoCount: Int = 0,
    val isCompareOriginal: Boolean = false,
    val deviceMediaLibrary: List<DeviceGalleryItem> = emptyList(),
    val isLibraryLoading: Boolean = false,
    val isVoiceRecording: Boolean = false,
    val voiceAmplitude: Float = 0f,
    val toastMessage: String? = null,
    val isHevcSupported: Boolean = false,
    val showExportSheet: Boolean = false,
    val customPresetDialogVisible: Boolean = false
) {
    val selectedClip: TimelineClip?
        get() = selectedClipId?.let { project.findClip(it) }

    val selectedTrack: Track?
        get() = selectedClipId?.let { project.findTrackForClip(it) }

    /** Visual clip that filter/effect/grade edits apply to. */
    fun activeVisualClip(playheadMs: Long): TimelineClip? {
        val sel = selectedClip
        val selTrack = selectedTrack
        if (sel != null && selTrack != null) {
            if (selTrack.type == TrackType.VIDEO_PIP) return sel
            if (selTrack.type == TrackType.VIDEO_MAIN) {
                val underPlayhead = project.findMainClipAt(playheadMs)
                if (underPlayhead != null && underPlayhead.id != sel.id) return underPlayhead
                return sel
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
    val exportState: StateFlow<ExportProgressState> = exportEngine.exportState
    val importProgress: StateFlow<MediaImportProgress> = mediaSupportManager.importProgress

    private var autosaveJob: Job? = null
    private var voiceMeterJob: Job? = null
    /**
     * Manual proxy switch from the project sheet. The controller derives the decode proxy from
     * `project.previewQuality`, so applying it is a normal mutation that rebuilds the pipeline once.
     */
    private fun applyProxyPreference(enabled: Boolean) {
        val target = if (enabled) PreviewQuality.PERFORMANCE else PreviewQuality.HIGH
        if (session.currentProject.previewQuality == target) return
        applyMutation("Preview Quality") { it.copy(previewQuality = target) }
    }

    init {
        viewModelScope.launch {
            preferencesRepository.preferencesFlow.collect { prefs ->
                applyProxyPreference(prefs.lowEndProxyPreview)
                restartAutosaveLoop(prefs.autosaveIntervalSec)
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
            previewController.syncProject(loaded, _uiState.value.isCompareOriginal)
        }
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
        previewController.syncProject(next, _uiState.value.isCompareOriginal)
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
        previewController.syncProject(next, _uiState.value.isCompareOriginal)
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

    private fun resolveTargetVisualClip(): TimelineClip? {
        val target = _uiState.value.activeVisualClip(playheadMs.value) ?: return null
        if (_uiState.value.selectedClipId != target.id) {
            _uiState.update { it.copy(selectedClipId = target.id) }
        }
        return target
    }

    private fun notifyToast(msg: String) {
        _uiState.update { it.copy(toastMessage = msg) }
    }

    fun clearToast() = _uiState.update { it.copy(toastMessage = null) }

    // ------------------------------------------------------------------ transport & selection

    fun togglePlayPause() = previewController.togglePlayPause()

    /** Called by the timeline on every scrub tick — pauses playback and seeks exactly. */
    fun scrubTo(ms: Long) {
        previewController.isUserScrubbing = true
        previewController.pause()
        previewController.seekToTimelineMs(ms)
    }

    fun endScrub(ms: Long) {
        previewController.isUserScrubbing = false
        previewController.seekToTimelineMs(ms)
    }

    fun seekTo(ms: Long) = previewController.seekToTimelineMs(ms)

    fun stepFrames(deltaFrames: Int) {
        previewController.pause()
        val fps = session.currentProject.exportConfig.frameRate.fps
        val nextMs = TimelineEngine.stepPlayheadByFrames(
            playheadMs = playheadMs.value,
            fps = fps,
            deltaFrames = deltaFrames,
            maxDurationMs = session.currentProject.totalDurationMs
        )
        previewController.seekToTimelineMs(nextMs)
    }

    fun selectClip(clipId: String?) {
        _uiState.update { it.copy(selectedClipId = clipId) }
        if (clipId == null) return
        val clip = session.currentProject.findClip(clipId) ?: return
        val track = session.currentProject.findTrackForClip(clipId)
        if (track?.type == TrackType.VIDEO_MAIN) {
            val curMs = playheadMs.value
            if (curMs < clip.startTimeOnTrackMs || curMs >= clip.endTimeOnTrackMs) {
                previewController.seekToTimelineMs(clip.startTimeOnTrackMs + 10L)
            }
        }
    }

    fun openSheet(sheet: EditorSheet, targetTrack: TrackType = TrackType.VIDEO_MAIN) {
        _uiState.update { it.copy(openSheet = sheet, mediaTargetTrack = targetTrack) }
        if (sheet == EditorSheet.MEDIA) refreshDeviceMediaLibrary()
        if (sheet in VISUAL_SHEETS) resolveTargetVisualClip()
    }

    fun closeSheet() = _uiState.update { it.copy(openSheet = EditorSheet.NONE) }

    fun setCompareOriginal(compareBypass: Boolean) {
        _uiState.update { it.copy(isCompareOriginal = compareBypass) }
        previewController.syncProject(session.currentProject, compareBypass)
    }

    fun toggleCompareOriginal() = setCompareOriginal(!_uiState.value.isCompareOriginal)

    // ------------------------------------------------------------------ undo / redo

    fun undo() {
        val prev = session.undo() ?: return notifyToast("Nothing to undo")
        publishSessionState(selectedClipId = prev.findMainClipAt(playheadMs.value)?.id)
        previewController.syncProject(prev, _uiState.value.isCompareOriginal)
        notifyToast("Undo")
    }

    fun redo() {
        val next = session.redo() ?: return notifyToast("Nothing to redo")
        publishSessionState(selectedClipId = next.findMainClipAt(playheadMs.value)?.id)
        previewController.syncProject(next, _uiState.value.isCompareOriginal)
        notifyToast("Redo")
    }

    // ------------------------------------------------------------------ project settings

    fun setAspectRatio(preset: AspectRatioPreset) {
        applyMutation("Aspect ${preset.label}") { TimelineEngine.setProjectAspectRatio(it, preset) }
    }

    fun setCustomAspectRatio(ratio: Float) {
        applyMutation("Custom Aspect") {
            TimelineEngine.setProjectAspectRatio(it, AspectRatioPreset.CUSTOM, ratio)
        }
    }

    fun setPreviewQuality(quality: PreviewQuality) {
        applyMutation("Preview Quality ${quality.label}") { it.copy(previewQuality = quality) }
    }

    fun renameProject(newTitle: String) {
        val clean = newTitle.trim().ifBlank { "Untitled Edit" }
        applyMutation("Rename Project") { it.copy(title = clean) }
        saveImmediately("Renamed project")
    }

    fun toggleMagneticTimeline() {
        applyMutation("Toggle Magnetic") { it.copy(magneticTimeline = !it.magneticTimeline) }
    }

    fun toggleSnapping() {
        applyMutation("Toggle Snapping") { it.copy(snappingEnabled = !it.snappingEnabled) }
    }

    // ------------------------------------------------------------------ media import

    fun refreshDeviceMediaLibrary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLibraryLoading = true) }
            val entries = runCatching { mediaSupportManager.scanDeviceMediaLibrary(maxPerCategory = 60) }
                .getOrDefault(emptyList())
            _uiState.update { state ->
                state.copy(
                    isLibraryLoading = false,
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
            val forceAudio = targetTrackType.isAudioTrack
            val batch = mediaSupportManager.importMediaBatch(uris, forceAudio = forceAudio)
            if (batch.imported.isEmpty()) {
                notifyToast(batch.failures.firstOrNull() ?: "Unsupported or unreadable media file")
                return@launch
            }
            val visualItems = batch.imported.filter { it.mediaKind != MediaKind.AUDIO }
            val audioItems = batch.imported.filter { it.mediaKind == MediaKind.AUDIO }
            var newlyAddedMainClipId: String? = null

            applyMutation("Import ${batch.imported.size} Media") { proj ->
                var working = proj
                if (visualItems.isNotEmpty()) {
                    val destTrack = if (targetTrackType.isVisualTrack) targetTrackType else TrackType.VIDEO_MAIN
                    working = TimelineEngine.importMediaClips(working, visualItems, destTrack, playheadMs.value)
                    newlyAddedMainClipId = working.mainVideoClips.lastOrNull()?.id
                }
                if (audioItems.isNotEmpty()) {
                    val destAudio = if (targetTrackType.isAudioTrack) targetTrackType else TrackType.AUDIO_MUSIC
                    working = TimelineEngine.importMediaClips(working, audioItems, destAudio, playheadMs.value)
                }
                working
            }
            if (newlyAddedMainClipId != null) selectClip(newlyAddedMainClipId)
            saveImmediately()
            notifyToast(
                if (batch.failures.isEmpty()) "Imported ${batch.imported.size} item(s)"
                else "Imported ${batch.imported.size} (${batch.failures.size} skipped)"
            )
            closeSheet()

            // Waveform + beat enrichment runs in the background so the UI never blocks.
            for (src in batch.imported) {
                if (src.hasAudioTrack && (src.mediaKind == MediaKind.VIDEO || src.mediaKind == MediaKind.AUDIO)) {
                    runCatching {
                        val audioSummary = mediaAudioTools.analyzeAudioTrack(src.uri, maxDecodedSeconds = 45)
                        val peaks = audioSummary.beatAnalysis.peakEnvelope
                        if (peaks.isEmpty()) return@runCatching
                        val matchingClip = session.currentProject.allClips
                            .lastOrNull { it.sourceUri == src.uri && it.waveformPeaks.isEmpty() }
                        if (matchingClip != null) {
                            session.executeCoalesced("waveform_${matchingClip.id}", "Waveform") { proj ->
                                TimelineEngine.updateClip(proj, matchingClip.id) { c ->
                                    c.copy(
                                        waveformPeaks = peaks,
                                        beatMarkersMs = audioSummary.beatAnalysis.beatTimestampsMs
                                    )
                                }
                            }
                            publishSessionState()
                            previewController.syncProject(session.currentProject, _uiState.value.isCompareOriginal)
                        }
                    }
                }
            }
        }
    }

    fun generateAndImportSampleClips() {
        viewModelScope.launch {
            notifyToast("Generating demo clips…")
            val samples = demoClipGenerator.generateSampleVideoClips()
            applyMutation("Add Demo Clips") { proj ->
                TimelineEngine.importMediaClips(proj, samples, TrackType.VIDEO_MAIN)
            }
            saveImmediately()
            notifyToast("Added ${samples.size} demo clips")
            closeSheet()
        }
    }

    fun addStudioCanvasBackdrop(title: String, primaryColorArgb: Int, secondaryColorArgb: Int) {
        viewModelScope.launch {
            val dims = session.currentProject.aspectRatio.computeCodecSafeDimensions(720)
            val canvasClip = demoClipGenerator.generateStudioCanvasClip(
                title = title,
                primaryColorArgb = primaryColorArgb,
                secondaryColorArgb = secondaryColorArgb,
                width = dims.first,
                height = dims.second,
                durationMs = 4_500L
            )
            applyMutation("Add '$title'") { proj ->
                TimelineEngine.importMediaClips(proj, listOf(canvasClip), TrackType.VIDEO_MAIN, playheadMs.value)
            }
            saveImmediately()
            notifyToast("Added '$title' backdrop")
            closeSheet()
        }
    }

    // ------------------------------------------------------------------ clip operations

    fun splitSelectedAtPlayhead() {
        val clip = _uiState.value.selectedClip ?: resolveTargetVisualClip() ?: return
        val before = session.currentProject.findTrackForClip(clip.id)?.clips?.size ?: 0
        applyMutation("Split Clip") { TimelineEngine.splitClipAt(it, clip.id, playheadMs.value) }
        val after = session.currentProject.findTrackForClip(clip.id)?.clips?.size ?: 0
        if (after == before) notifyToast("Move the playhead inside the clip to split")
    }

    fun splitAllAtPlayhead() {
        val ms = playheadMs.value
        val targets = session.currentProject.allClips.filter {
            ms > it.startTimeOnTrackMs + 120L && ms < it.endTimeOnTrackMs - 120L
        }
        if (targets.isEmpty()) return notifyToast("No clip under the playhead")
        applyMutation("Split ${targets.size} Clips") { proj ->
            targets.fold(proj) { acc, c -> TimelineEngine.splitClipAt(acc, c.id, ms) }
        }
        notifyToast("Split ${targets.size} clip(s) at ${formatClock(ms)}")
    }

    fun trimSelectedClip(newTrimStartMs: Long, newTrimEndMs: Long) {
        val clip = _uiState.value.selectedClip ?: return
        applyCoalescedMutation("trim_${clip.id}", "Trim Clip") {
            TimelineEngine.trimClip(it, clip.id, newTrimStartMs, newTrimEndMs)
        }
    }

    fun trimClipById(clipId: String, newTrimStartMs: Long, newTrimEndMs: Long) {
        applyCoalescedMutation("trim_$clipId", "Trim Clip", newSelectedClipId = clipId) {
            TimelineEngine.trimClip(it, clipId, newTrimStartMs, newTrimEndMs)
        }
    }

    fun moveClipById(clipId: String, newStartMs: Long) {
        applyCoalescedMutation("move_$clipId", "Move Clip", newSelectedClipId = clipId) {
            TimelineEngine.moveClipToTimestamp(it, clipId, newStartMs, playheadMs.value)
        }
    }

    fun duplicateSelectedClip() {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Duplicate Clip") { TimelineEngine.duplicateClip(it, clip.id) }
    }

    fun deleteSelectedClip() {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Delete Clip", newSelectedClipId = null) { TimelineEngine.deleteClip(it, clip.id) }
        notifyToast("Clip deleted")
    }

    fun cutMiddleTwentyPercentOfSelected() {
        val clip = _uiState.value.selectedClip ?: return
        val dur = clip.timelineDurationMs
        if (dur < 800L) return notifyToast("Clip too short to cut middle")
        val cutStart = clip.startTimeOnTrackMs + (dur * 0.40).toLong()
        val cutEnd = clip.startTimeOnTrackMs + (dur * 0.60).toLong()
        applyMutation("Cut Segment") { TimelineEngine.cutRangeFromClip(it, clip.id, cutStart, cutEnd) }
    }

    fun moveSelectedClipOrder(delta: Int) {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Reorder Clip") { TimelineEngine.moveClipOrder(it, clip.id, delta) }
    }

    fun mergeAdjacentOnCurrentTrack() {
        val clip = _uiState.value.selectedClip ?: return
        val track = session.currentProject.findTrackForClip(clip.id) ?: return
        if (track.clips.size < 2) return notifyToast("Need at least 2 clips on this track")
        applyMutation("Merge Clips") { TimelineEngine.mergeClips(it, track.clips.map { c -> c.id }) }
        notifyToast("Merged clips on ${track.name}")
    }

    fun setCropOnSelected(cropRect: CropRect) {
        val clip = resolveTargetVisualClip() ?: return
        applyCoalescedMutation("crop_${clip.id}", "Crop", newSelectedClipId = clip.id) {
            TimelineEngine.setCrop(it, clip.id, cropRect)
        }
    }

    fun rotateSelected90(clockwise: Boolean = true) {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Rotate 90°", newSelectedClipId = clip.id) {
            TimelineEngine.rotateClip90(it, clip.id, clockwise)
        }
    }

    fun flipSelectedHorizontal() {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Flip H", newSelectedClipId = clip.id) {
            TimelineEngine.toggleFlipHorizontal(it, clip.id)
        }
    }

    fun flipSelectedVertical() {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Flip V", newSelectedClipId = clip.id) {
            TimelineEngine.toggleFlipVertical(it, clip.id)
        }
    }

    fun toggleReverseSelected() {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Reverse", newSelectedClipId = clip.id) {
            TimelineEngine.toggleReverseClip(it, clip.id)
        }
    }

    fun updateSelectedTransform(transform: ClipTransform) {
        val clip = _uiState.value.selectedClip ?: resolveTargetVisualClip() ?: return
        applyCoalescedMutation("transform_${clip.id}", "Transform", newSelectedClipId = clip.id) {
            TimelineEngine.setClipTransform(it, clip.id, transform)
        }
    }

    fun updateSelectedSpeed(
        speed: Float,
        curvePreset: SpeedCurvePreset = SpeedCurvePreset.NONE,
        customPoints: List<SpeedPoint> = emptyList()
    ) {
        val clip = resolveTargetVisualClip() ?: return
        applyCoalescedMutation("speed_${clip.id}", "Speed", newSelectedClipId = clip.id) {
            TimelineEngine.setClipSpeed(it, clip.id, speed, curvePreset, customPoints)
        }
    }

    fun setPhotoOrClipDurationMs(targetDurationMs: Long) {
        val clip = resolveTargetVisualClip() ?: return
        val clamped = targetDurationMs.coerceIn(300L, clip.sourceDurationMs.coerceAtLeast(15_000L))
        if (clamped <= clip.sourceDurationMs) {
            val newEnd = (clip.trimStartMs + clamped).coerceAtMost(clip.sourceDurationMs)
            applyCoalescedMutation("dur_${clip.id}", "Duration") {
                TimelineEngine.trimClip(it, clip.id, clip.trimStartMs, newEnd)
            }
        } else {
            val slow = (clip.trimmedSourceDurationMs.toFloat() / clamped.toFloat()).coerceIn(0.15f, 1f)
            applyCoalescedMutation("dur_${clip.id}", "Duration") {
                TimelineEngine.setClipSpeed(it, clip.id, slow, SpeedCurvePreset.NONE, emptyList())
            }
        }
    }

    fun insertFreezeFrame(durationMs: Long = 2_000L) {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Freeze Frame", newSelectedClipId = clip.id) {
            TimelineEngine.insertFreezeFrame(it, clip.id, playheadMs.value, durationMs)
        }
        notifyToast("Inserted ${durationMs / 1000f}s freeze frame")
    }

    // ------------------------------------------------------------------ tracks

    fun toggleTrackLock(trackId: String) {
        applyMutation("Lock Track") { TimelineEngine.toggleTrackLock(it, trackId) }
    }

    fun toggleTrackHidden(trackId: String) {
        applyMutation("Hide Track") { TimelineEngine.toggleTrackHidden(it, trackId) }
    }

    fun toggleTrackMute(trackId: String) {
        applyMutation("Mute Track") { TimelineEngine.toggleTrackMute(it, trackId) }
    }

    fun addTrack(type: TrackType) {
        applyMutation("Add ${type.label} Track") { TimelineEngine.addTrack(it, type) }
    }

    // ------------------------------------------------------------------ effects

    fun addEffectToSelected(type: EffectType) {
        val clip = resolveTargetVisualClip() ?: return
        val existing = clip.effects.firstOrNull { it.type == type }
        val fx = existing?.copy(enabled = true) ?: AppliedEffect(type = type)
        applyMutation("Add ${type.label}", newSelectedClipId = clip.id) {
            TimelineEngine.addOrUpdateEffect(it, clip.id, fx)
        }
    }

    fun updateEffectOnSelected(updated: AppliedEffect) {
        val clip = resolveTargetVisualClip() ?: return
        applyCoalescedMutation("fx_${clip.id}_${updated.id}", "Adjust ${updated.type.label}", newSelectedClipId = clip.id) {
            TimelineEngine.addOrUpdateEffect(it, clip.id, updated)
        }
    }

    fun reorderEffectOnSelected(effectId: String, delta: Int) {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Reorder Effect", newSelectedClipId = clip.id) {
            TimelineEngine.reorderEffect(it, clip.id, effectId, delta)
        }
    }

    fun removeEffectFromSelected(effectId: String) {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Remove Effect", newSelectedClipId = clip.id) {
            TimelineEngine.removeEffect(it, clip.id, effectId)
        }
    }

    fun clearEffectsOnSelected() {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Clear Effects", newSelectedClipId = clip.id) {
            TimelineEngine.clearClipEffects(it, clip.id)
        }
        notifyToast("Cleared all effects")
    }

    fun applyEffectsToAllClips() {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Apply Effects to All", newSelectedClipId = clip.id) {
            TimelineEngine.applyEffectsToAllMainClips(it, clip.effects)
        }
        notifyToast("Applied ${clip.effects.size} effect(s) to all main clips")
    }

    // ------------------------------------------------------------------ color & detail

    fun updateColorGradeOnSelected(grade: ColorGradeConfig, coalesceKey: String = "grade") {
        val clip = resolveTargetVisualClip() ?: return
        applyCoalescedMutation("${coalesceKey}_${clip.id}", "Adjust Look", newSelectedClipId = clip.id) {
            TimelineEngine.setColorGrade(it, clip.id, grade)
        }
    }

    fun applyColorPresetOnSelected(preset: ColorGradePreset?) {
        val clip = resolveTargetVisualClip() ?: return
        val strength = if (clip.colorGrade.lutPresetId == null) 1f else clip.colorGrade.lutIntensity.coerceIn(0.15f, 1f)
        val nextCfg = if (preset == null) {
            clip.colorGrade.copy(lutPresetId = null, customCubeLutUri = null)
        } else {
            clip.colorGrade.copy(lutPresetId = preset.id, lutIntensity = strength)
        }
        applyMutation(
            actionLabel = if (preset == null) "Remove Filter" else "Filter '${preset.name}'",
            newSelectedClipId = clip.id
        ) {
            TimelineEngine.setColorGrade(it, clip.id, nextCfg)
        }
    }

    fun updateFilterIntensityOnSelected(intensity: Float) {
        val clip = resolveTargetVisualClip() ?: return
        updateColorGradeOnSelected(clip.colorGrade.copy(lutIntensity = intensity.coerceIn(0f, 1f)), "lut_intensity")
    }

    fun applyDetailQuickPreset(presetKey: String) {
        val clip = resolveTargetVisualClip() ?: return
        val g = clip.colorGrade
        val next = when (presetKey) {
            "crisp_hd" -> g.copy(sharpness = 0.42f, sharpenRadius = 0.35f, sharpenThreshold = 0.16f, clarity = 0.22f, structure = 0.18f, denoise = 0.08f)
            "ultra_4k" -> g.copy(sharpness = 0.68f, sharpenRadius = 0.28f, sharpenThreshold = 0.14f, clarity = 0.36f, structure = 0.42f, dehaze = 0.15f, denoise = 0.10f)
            "portrait_soft" -> g.copy(sharpness = 0.18f, sharpenRadius = 0.25f, sharpenThreshold = 0.35f, clarity = -0.22f, structure = 0f, denoise = 0.28f)
            "low_light_clean" -> g.copy(sharpness = 0.24f, sharpenRadius = 0.30f, sharpenThreshold = 0.32f, clarity = 0.12f, structure = 0.08f, denoise = 0.65f)
            "analog_grain" -> g.copy(sharpness = 0.16f, clarity = 0.10f, vignette = 0.28f, vignetteFeather = 0.65f, grain = 0.36f)
            else -> g.resetDetailOnly()
        }
        applyMutation("Detail Preset", newSelectedClipId = clip.id) {
            TimelineEngine.setColorGrade(it, clip.id, next)
        }
    }

    fun resetColorSectionOnSelected(section: String) {
        val clip = resolveTargetVisualClip() ?: return
        val g = clip.colorGrade
        val next = when (section) {
            "filter" -> g.copy(lutPresetId = null, customCubeLutUri = null)
            "light" -> g.resetLight()
            "color" -> g.resetColor()
            "detail" -> g.resetDetailOnly()
            "hsl" -> g.copy(hslBands = ColorGradeConfig.defaultHslBands())
            "curves" -> g.copy(curves = RgbCurvesConfig())
            "wheels" -> g.copy(colorWheels = ColorWheelsConfig())
            else -> ColorGradeConfig()
        }
        applyMutation("Reset $section", newSelectedClipId = clip.id) {
            TimelineEngine.setColorGrade(it, clip.id, next)
        }
    }

    fun applyColorGradeToAllClips() {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Apply Look to All", newSelectedClipId = clip.id) {
            TimelineEngine.applyColorGradeToAllMainClips(it, clip.colorGrade)
        }
        notifyToast("Applied look to all main clips")
    }

    fun importCubeLutUriOnSelected(uri: Uri) {
        val clip = resolveTargetVisualClip() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (content.isNullOrBlank()) return@launch notifyToast("Could not read .cube file")
            val valid = runCatching { ColorGradingEngine.parseAdobeCubeLut(content) }.getOrNull()
                ?: return@launch notifyToast("Invalid .cube 3D LUT")
            val dir = File(context.filesDir, "luts").apply { mkdirs() }
            val saved = File(dir, "lut_${System.currentTimeMillis()}.cube")
            saved.writeText(content)
            launch(Dispatchers.Main) {
                applyMutation("Import LUT '${valid.title}'", newSelectedClipId = clip.id) { proj ->
                    TimelineEngine.setColorGrade(
                        proj, clip.id,
                        clip.colorGrade.copy(customCubeLutUri = Uri.fromFile(saved).toString())
                    )
                }
                notifyToast("Applied LUT: ${valid.title} (${valid.size}³)")
            }
        }
    }

    fun saveCustomColorPreset(name: String) {
        val clip = resolveTargetVisualClip() ?: return
        val g = clip.colorGrade
        val activePreset = ColorGradingEngine.findPresetById(g.lutPresetId, session.currentProject.customColorPresets)
        val merged = if (activePreset != null) {
            val s = g.lutIntensity.coerceIn(0f, 1f)
            val p = activePreset.config
            g.copy(
                lutPresetId = null,
                lutIntensity = 1f,
                exposure = (g.exposure + p.exposure * s).coerceIn(-2f, 2f),
                brightness = (g.brightness + p.brightness * s).coerceIn(-1f, 1f),
                contrast = (g.contrast + p.contrast * s).coerceIn(-1f, 1f),
                saturation = (g.saturation + p.saturation * s).coerceIn(-1f, 1f),
                vibrance = (g.vibrance + p.vibrance * s).coerceIn(-1f, 1f),
                temperature = (g.temperature + p.temperature * s).coerceIn(-1f, 1f),
                tint = (g.tint + p.tint * s).coerceIn(-1f, 1f),
                highlights = (g.highlights + p.highlights * s).coerceIn(-1f, 1f),
                shadows = (g.shadows + p.shadows * s).coerceIn(-1f, 1f),
                whites = (g.whites + p.whites * s).coerceIn(-1f, 1f),
                blacks = (g.blacks + p.blacks * s).coerceIn(-1f, 1f),
                dehaze = (g.dehaze + p.dehaze * s).coerceIn(-0.5f, 1f),
                sharpness = (g.sharpness + p.sharpness * s).coerceIn(0f, 1f),
                clarity = (g.clarity + p.clarity * s).coerceIn(-1f, 1f),
                structure = (g.structure + p.structure * s).coerceIn(0f, 1f),
                vignette = (g.vignette + p.vignette * s).coerceIn(-1f, 1f),
                grain = (g.grain + p.grain * s).coerceIn(0f, 1f),
                fade = (g.fade + p.fade * s).coerceIn(0f, 1f)
            )
        } else {
            g.copy(lutPresetId = null, lutIntensity = 1f)
        }
        val preset = ColorGradePreset(
            name = name.trim().ifBlank { "My Look" },
            category = "Custom",
            accentHex = 0xFF6EF2B5,
            secondaryHex = 0xFF9B8CFF,
            config = merged,
            isUserCustom = true
        )
        applyMutation("Save Custom Look", newSelectedClipId = clip.id) { proj ->
            val withPreset = proj.copy(customColorPresets = proj.customColorPresets + preset)
            TimelineEngine.setColorGrade(withPreset, clip.id, ColorGradeConfig(lutPresetId = preset.id, lutIntensity = 1f))
        }
        notifyToast("Saved custom look '${preset.name}'")
    }

    fun deleteCustomPreset(presetId: String) {
        applyMutation("Delete Look") { TimelineEngine.deleteCustomColorPreset(it, presetId) }
    }

    // ------------------------------------------------------------------ transitions & keyframes

    fun setTransitionOnSelected(transition: ClipTransition?) {
        val clip = resolveTargetVisualClip() ?: return
        applyCoalescedMutation("trans_${clip.id}", "Transition", newSelectedClipId = clip.id) {
            TimelineEngine.setClipTransition(it, clip.id, transition, clip.transitionOut)
        }
    }

    fun applyTransitionToAllClips(transition: ClipTransition) {
        applyMutation("Apply Transition to All") { TimelineEngine.applyTransitionToAllMainClips(it, transition) }
        notifyToast("Applied ${transition.type.label} to all cuts")
    }

    fun upsertKeyframeAtPlayhead(property: KeyframeProperty, value: Float, easing: EasingCurve = EasingCurve.EASE_IN_OUT) {
        val clip = _uiState.value.selectedClip ?: resolveTargetVisualClip() ?: return
        val localOffset = (playheadMs.value - clip.startTimeOnTrackMs).coerceIn(0L, clip.timelineDurationMs)
        val kf = Keyframe(property = property, timeOffsetMs = localOffset, value = value, easing = easing)
        applyCoalescedMutation("kf_${clip.id}_${property.name}", "Keyframe ${property.label}", newSelectedClipId = clip.id) {
            TimelineEngine.upsertKeyframe(it, clip.id, kf)
        }
    }

    fun removeKeyframe(keyframeId: String) {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Delete Keyframe", newSelectedClipId = clip.id) {
            TimelineEngine.removeKeyframe(it, clip.id, keyframeId)
        }
    }

    fun clearKeyframes() {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Clear Keyframes", newSelectedClipId = clip.id) {
            TimelineEngine.clearKeyframes(it, clip.id)
        }
    }

    // ------------------------------------------------------------------ audio

    fun updateSelectedAudioDsp(
        volume: Float? = null,
        fadeInMs: Long? = null,
        fadeOutMs: Long? = null,
        noiseReduction: Float? = null,
        voiceEnhancement: Float? = null,
        audioDucking: Boolean? = null,
        isMuted: Boolean? = null
    ) {
        val clip = _uiState.value.selectedClip ?: return
        applyCoalescedMutation("audio_${clip.id}", "Audio Mix") { proj ->
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

    fun extractAudioFromSelectedClip() {
        val clip = resolveTargetVisualClip() ?: return
        if (!clip.hasAudioTrack) return notifyToast("This clip has no audio stream")
        viewModelScope.launch {
            val extractedFile = mediaAudioTools.extractAudioFromVideoToM4a(clip.sourceUri)
                ?: return@launch notifyToast("Could not extract audio")
            val summary = mediaAudioTools.analyzeAudioTrack(Uri.fromFile(extractedFile).toString())
            val src = ImportedMediaSource(
                uri = Uri.fromFile(extractedFile).toString(),
                displayName = "${clip.sourceName} (Audio)",
                mediaKind = MediaKind.AUDIO,
                durationMs = clip.trimmedSourceDurationMs,
                hasAudioTrack = true,
                waveformPeaks = summary.beatAnalysis.peakEnvelope
            )
            applyMutation("Extract Audio") { proj ->
                val withAudio = TimelineEngine.importMediaClips(proj, listOf(src), TrackType.AUDIO_MUSIC, clip.startTimeOnTrackMs)
                TimelineEngine.updateClip(withAudio, clip.id) { it.copy(isMuted = true) }
            }
            notifyToast("Extracted audio to Music track")
        }
    }

    fun toggleVoiceoverRecording() {
        if (_uiState.value.isVoiceRecording) {
            voiceMeterJob?.cancel()
            val recorded = voiceoverRecorder.stopRecording()
            _uiState.update { it.copy(isVoiceRecording = false, voiceAmplitude = 0f) }
            if (recorded != null) {
                val (file, durMs) = recorded
                viewModelScope.launch {
                    val summary = runCatching {
                        mediaAudioTools.analyzeAudioTrack(Uri.fromFile(file).toString())
                    }.getOrNull()
                    val src = ImportedMediaSource(
                        uri = Uri.fromFile(file).toString(),
                        displayName = "Voiceover",
                        mediaKind = MediaKind.AUDIO,
                        durationMs = durMs,
                        hasAudioTrack = true,
                        waveformPeaks = summary?.beatAnalysis?.peakEnvelope.orEmpty()
                    )
                    applyMutation("Record Voiceover") { proj ->
                        TimelineEngine.importMediaClips(proj, listOf(src), TrackType.AUDIO_VOICEOVER, playheadMs.value)
                    }
                    notifyToast("Added ${"%.1f".format(durMs / 1000f)}s voiceover")
                }
            }
        } else {
            previewController.pause()
            val out = voiceoverRecorder.startRecording()
                ?: return notifyToast("Microphone unavailable or permission needed")
            _uiState.update { it.copy(isVoiceRecording = true) }
            voiceMeterJob = viewModelScope.launch {
                while (isActive && voiceoverRecorder.isRecording) {
                    _uiState.update { it.copy(voiceAmplitude = voiceoverRecorder.pollNormalizedAmplitude()) }
                    delay(60L)
                }
            }
        }
    }

    fun addSynthesizedSfx(preset: BuiltInSfxPreset) {
        viewModelScope.launch {
            val wavFile = mediaAudioTools.materializeSfxFile(preset)
            val src = ImportedMediaSource(
                uri = Uri.fromFile(wavFile).toString(),
                displayName = preset.title,
                mediaKind = MediaKind.GENERATED_SFX,
                durationMs = preset.durationMs.toLong(),
                hasAudioTrack = true,
                waveformPeaks = List(24) { i -> 1f - (i / 24f) * 0.7f }
            )
            applyMutation("Add SFX '${preset.title}'") { proj ->
                TimelineEngine.importMediaClips(proj, listOf(src), TrackType.AUDIO_SFX, playheadMs.value)
            }
            notifyToast("Added SFX: ${preset.title}")
        }
    }

    fun detectBeatsAndAutoSync() {
        viewModelScope.launch {
            val musicClip = session.currentProject.trackByType(TrackType.AUDIO_MUSIC)?.clips?.firstOrNull()
                ?: _uiState.value.selectedClip
            if (musicClip == null) return@launch notifyToast("Add a video or music track first")
            notifyToast("Analyzing transients & BPM…")
            val summary = mediaAudioTools.analyzeAudioTrack(musicClip.sourceUri)
            val beats = summary.beatAnalysis.beatTimestampsMs
            if (beats.size < 2) {
                return@launch notifyToast("Estimated ${summary.beatAnalysis.estimatedBpm} BPM (subtle transients)")
            }
            applyMutation("Beat Sync (${summary.beatAnalysis.estimatedBpm} BPM)") { proj ->
                val marked = TimelineEngine.updateClip(proj, musicClip.id) {
                    it.copy(beatMarkersMs = beats, waveformPeaks = summary.beatAnalysis.peakEnvelope)
                }
                AudioDspEngine.syncVideoClipsToBeats(marked, beats, beatsPerCut = 2)
            }
            notifyToast("Synced cuts to ${beats.size} beats")
        }
    }

    // ------------------------------------------------------------------ text & subtitles

    fun addTextClip(spec: TextStyleSpec = TextStyleSpec()) {
        var addedId: String? = null
        applyMutation("Add Text") { proj ->
            val next = TimelineEngine.addTextClip(proj, spec, playheadMs.value, 3_000L)
            addedId = next.trackByType(TrackType.TEXT)?.clips?.lastOrNull()?.id
            next
        }
        if (addedId != null) selectClip(addedId)
    }

    fun updateSelectedTextSpec(spec: TextStyleSpec) {
        val clip = _uiState.value.selectedClip ?: return
        if (clip.textSpec == null) return
        applyCoalescedMutation("text_${clip.id}", "Edit Text", newSelectedClipId = clip.id) { proj ->
            TimelineEngine.updateClip(proj, clip.id) {
                it.copy(textSpec = spec, sourceName = spec.text.take(24))
            }
        }
    }

    fun addSticker(sticker: StickerSpec) {
        var addedId: String? = null
        applyMutation("Add Sticker") { proj ->
            val next = TimelineEngine.addStickerClip(proj, sticker, playheadMs.value, 2_500L)
            addedId = next.trackByType(TrackType.STICKER)?.clips?.lastOrNull()?.id
            next
        }
        if (addedId != null) selectClip(addedId)
    }

    fun addOrUpdateSubtitleCue(cue: SubtitleCue) {
        applyMutation("Update Subtitle") { proj ->
            val next = proj.subtitles.filterNot { it.id == cue.id } + cue
            SubtitleCodec.applySubtitlesToTextTrack(proj, next.sortedBy { it.startTimeMs })
        }
    }

    fun deleteSubtitleCue(cueId: String) {
        applyMutation("Delete Subtitle") { proj ->
            val next = proj.subtitles.filterNot { it.id == cueId }
            SubtitleCodec.applySubtitlesToTextTrack(proj, next)
        }
    }

    fun importSrtUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: return@launch
            val cues = SubtitleCodec.parseSrt(content)
            if (cues.isEmpty()) return@launch notifyToast("No cues found in that .srt file")
            launch(Dispatchers.Main) {
                applyMutation("Import SRT (${cues.size} cues)") { proj ->
                    SubtitleCodec.applySubtitlesToTextTrack(proj, cues)
                }
                notifyToast("Imported ${cues.size} subtitles")
            }
        }
    }

    fun createExportSrtShareIntent(): Intent? {
        val cues = session.currentProject.subtitles
        if (cues.isEmpty()) return null
        val file = File(context.cacheDir, "${session.currentProject.title.take(20)}_subtitles.srt")
        file.writeText(SubtitleCodec.exportSrt(cues))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ------------------------------------------------------------------ 4K enhancement

    fun updateEnhancement(config: EnhancementConfig) {
        applyCoalescedMutation("enhancement", "Enhancement") { it.copy(enhancement = config) }
    }

    fun enhancementPlan(targetHeight: Int): EnhancementPlan {
        val p = session.currentProject
        val source = p.mainVideoClips.firstOrNull()
        return EnhancementPlanner.plan(
            config = p.enhancement.copy(targetHeight = targetHeight),
            aspect = p.aspectRatio,
            customRatio = p.customAspectRatio,
            sourceWidth = source?.sourceWidth ?: 1080,
            sourceHeight = source?.sourceHeight ?: 1920
        )
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
            val result = exportEngine.exportProject(session.currentProject, cfg, currentPlayhead)
            if (result is ExportProgressState.Completed) saveImmediately()
        }
    }

    fun quickCaptureGradedPhotoFrame() {
        previewController.pause()
        val photoCfg = session.currentProject.exportConfig.copy(
            exportAsPhotoFrame = true, audioOnly = false, saveToGallery = true
        )
        val pos = playheadMs.value
        viewModelScope.launch {
            val outcome = exportEngine.exportProject(session.currentProject, photoCfg, pos)
            when (outcome) {
                is ExportProgressState.Completed -> {
                    notifyToast("Saved photo frame to gallery")
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

    fun buildShareExportIntent(path: String, audioOnly: Boolean, isPhotoFrame: Boolean = false): Intent? =
        exportEngine.buildShareIntent(path, audioOnly, isPhotoFrame)

    fun deviceVideoEncoders(): List<String> = exportEngine.listDeviceVideoEncoders()

    override fun onCleared() {
        super.onCleared()
        runCatching { voiceoverRecorder.stopRecording() }
        previewController.release()
    }

    companion object {
        private val VISUAL_SHEETS = setOf(
            EditorSheet.ADJUST, EditorSheet.DETAIL, EditorSheet.FILTERS,
            EditorSheet.EFFECTS, EditorSheet.TRANSITIONS, EditorSheet.TRANSFORM
        )

        /** `m:ss` clock used in toast messages. */
        fun formatClock(ms: Long): String {
            val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
            return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
        }
    }
}

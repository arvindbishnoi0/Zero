package com.vibecut.pro.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibecut.pro.ai.OnDeviceSpeechCaptionService
import com.vibecut.pro.ai.OnDeviceVisionAiService
import com.vibecut.pro.data.preferences.AppPreferencesRepository
import com.vibecut.pro.data.repository.ProjectRepository
import com.vibecut.pro.engine.ai.*
import com.vibecut.pro.engine.audio.AudioDspEngine
import com.vibecut.pro.engine.audio.BuiltInSfxPreset
import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.subtitles.SubtitleCodec
import com.vibecut.pro.engine.templates.TemplateCatalog
import com.vibecut.pro.engine.templates.TemplateCategory
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

enum class EditorInspectorTab(val label: String) {
    EDIT("Clip"),
    FILTERS("Filters"),
    DETAIL("Sharpen"),
    ADJUST("Adjust"),
    EFFECTS("Effects"),
    TRANSITIONS("Trans"),
    AUDIO("Audio"),
    TEXT("Text"),
    KEYFRAMES("Motion"),
    AI("AI Magic")
}

data class EditorUiState(
    val project: Project = Project(),
    val selectedClipId: String? = null,
    val activeTab: EditorInspectorTab = EditorInspectorTab.EDIT,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoCount: Int = 0,
    val redoCount: Int = 0,
    val isCompareOriginal: Boolean = false,
    val showMediaImporterSheet: Boolean = false,
    val deviceMediaLibrary: List<DeviceGalleryItem> = emptyList(),
    val isVoiceRecording: Boolean = false,
    val voiceAmplitude: Float = 0f,
    val isProcessingAi: Boolean = false,
    val aiStatusText: String? = null,
    val latestSummaryReport: VideoSummaryReport? = null,
    val toastMessage: String? = null,
    val isHevcSupported: Boolean = false
) {
    val selectedClip: TimelineClip?
        get() = selectedClipId?.let { project.findClip(it) }
            ?: project.findMainClipAt(0L)

    fun activeVisualClip(playheadMs: Long): TimelineClip? {
        val sel = selectedClipId?.let { project.findClip(it) }
        val selTrack = sel?.let { project.findTrackForClip(it.id) }
        if (sel != null && selTrack != null) {
            if (selTrack.type == TrackType.VIDEO_PIP || selTrack.type == TrackType.OVERLAY) {
                return sel
            }
            if (selTrack.type == TrackType.VIDEO_MAIN) {
                val underPlayhead = project.findMainClipAt(playheadMs)
                return underPlayhead ?: sel
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
    private val visionAiService: OnDeviceVisionAiService,
    private val speechCaptionService: OnDeviceSpeechCaptionService,
    private val externalGenAiGateway: ExternalGenAiGateway,
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
    private var proxyModeEnabled: Boolean = false

    init {
        viewModelScope.launch {
            preferencesRepository.preferencesFlow.collect { prefs ->
                proxyModeEnabled = prefs.lowEndProxyPreview
                previewController.syncProject(
                    project = session.currentProject,
                    lowResProxy = proxyModeEnabled,
                    isCompareBypass = _uiState.value.isCompareOriginal
                )
                restartAutosaveLoop(prefs.autosaveIntervalSec)
            }
        }
        // Keep selected main clip synchronized when scrubbing across multi-clip timelines
        viewModelScope.launch {
            previewController.playheadMs.collect { ms ->
                val proj = session.currentProject
                val curSelId = _uiState.value.selectedClipId
                val curTrack = curSelId?.let { proj.findTrackForClip(it) }
                if (curSelId == null || curTrack?.type == TrackType.VIDEO_MAIN) {
                    val clipAtPlayhead = proj.findMainClipAt(ms)
                    if (clipAtPlayhead != null && clipAtPlayhead.id != curSelId) {
                        _uiState.update { it.copy(selectedClipId = clipAtPlayhead.id) }
                    }
                }
            }
        }
    }

    fun loadProject(projectId: String) {
        viewModelScope.launch {
            val loaded = projectRepository.loadProject(projectId)
                ?: projectRepository.createNewProject("Untitled Edit", AspectRatioPreset.REELS_9_16)
            session.reset(loaded)
            val defaultSel = loaded.findMainClipAt(0L)?.id
            publishSessionState(selectedClipId = defaultSel)
            previewController.syncProject(
                project = loaded,
                lowResProxy = proxyModeEnabled,
                isCompareBypass = _uiState.value.isCompareOriginal
            )
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
            if (summary != null) notifyToast("Saved: $summary")
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
        previewController.syncProject(
            project = next,
            lowResProxy = proxyModeEnabled,
            isCompareBypass = _uiState.value.isCompareOriginal
        )
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
        previewController.syncProject(
            project = next,
            lowResProxy = proxyModeEnabled,
            isCompareBypass = _uiState.value.isCompareOriginal
        )
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

    /**
     * Resolves the visual clip currently visible at [playheadMs] (or selected PIP/Overlay clip),
     * auto-switching selection away from Audio/Text/Sticker tracks so Filter/Sharpen/Adjust/Effect
     * edits ALWAYS apply visibly to the active video or photo frame!
     */
    private fun resolveTargetVisualClip(): TimelineClip? {
        val target = _uiState.value.activeVisualClip(playheadMs.value) ?: return null
        if (_uiState.value.selectedClipId != target.id) {
            _uiState.update { it.copy(selectedClipId = target.id) }
        }
        return target
    }

    fun selectClip(clipId: String?) {
        _uiState.update { it.copy(selectedClipId = clipId) }
        if (clipId != null) {
            val clip = session.currentProject.findClip(clipId)
            val track = session.currentProject.findTrackForClip(clipId)
            if (clip != null && track?.type == TrackType.VIDEO_MAIN) {
                val curMs = playheadMs.value
                if (curMs < clip.startTimeOnTrackMs || curMs >= clip.endTimeOnTrackMs) {
                    previewController.seekToTimelineMs(clip.startTimeOnTrackMs + 10L)
                }
            }
        }
    }

    fun selectTab(tab: EditorInspectorTab) {
        _uiState.update { it.copy(activeTab = tab) }
        val visualTabs = setOf(
            EditorInspectorTab.FILTERS,
            EditorInspectorTab.DETAIL,
            EditorInspectorTab.ADJUST,
            EditorInspectorTab.EFFECTS,
            EditorInspectorTab.TRANSITIONS
        )
        if (tab in visualTabs) {
            resolveTargetVisualClip()
        }
    }

    fun setCompareOriginal(compareBypass: Boolean) {
        _uiState.update { it.copy(isCompareOriginal = compareBypass) }
        previewController.syncProject(
            project = session.currentProject,
            lowResProxy = proxyModeEnabled,
            isCompareBypass = compareBypass
        )
    }

    fun toggleCompareOriginal() {
        setCompareOriginal(!_uiState.value.isCompareOriginal)
    }

    fun setShowMediaImporterSheet(visible: Boolean) {
        _uiState.update { it.copy(showMediaImporterSheet = visible) }
        if (visible) {
            refreshDeviceMediaLibrary()
        }
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

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun clearSummaryReport() {
        _uiState.update { it.copy(latestSummaryReport = null) }
    }

    private fun notifyToast(msg: String) {
        _uiState.update { it.copy(toastMessage = msg) }
    }

    // --- Undo / Redo & Transport ---
    fun undo() {
        val prev = session.undo() ?: return
        publishSessionState(selectedClipId = prev.findMainClipAt(playheadMs.value)?.id)
        previewController.syncProject(prev, proxyModeEnabled, _uiState.value.isCompareOriginal)
        notifyToast("Undo")
    }

    fun redo() {
        val next = session.redo() ?: return
        publishSessionState(selectedClipId = next.findMainClipAt(playheadMs.value)?.id)
        previewController.syncProject(next, proxyModeEnabled, _uiState.value.isCompareOriginal)
        notifyToast("Redo")
    }

    fun togglePlayPause() = previewController.togglePlayPause()

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

    fun setTimelineZoom(zoom: Float) {
        applyCoalescedMutation("timeline_zoom", "Zoom") {
            TimelineEngine.setTimelineZoom(it, zoom)
        }
    }

    fun setAspectRatio(preset: AspectRatioPreset) {
        applyMutation("Aspect Ratio ${preset.label}") {
            TimelineEngine.setProjectAspectRatio(it, preset)
        }
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

    // --- Universal Media Supporter & Loader (Video, Photo, Audio, Studio Backdrops) ---
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
                    val destAudioTrack = if (targetTrackType.isAudioTrack) targetTrackType else TrackType.AUDIO_MUSIC
                    working = TimelineEngine.importMediaClips(working, audioItems, destAudioTrack, playheadMs.value)
                }
                working
            }
            if (newlyAddedMainClipId != null) {
                selectClip(newlyAddedMainClipId)
            }
            saveImmediately()
            val summaryMsg = if (batch.failures.isEmpty()) {
                "Imported ${batch.imported.size} media item(s)"
            } else {
                "Imported ${batch.imported.size} (${batch.failures.size} skipped)"
            }
            notifyToast(summaryMsg)
            _uiState.update { it.copy(showMediaImporterSheet = false) }

            // Background beat & detailed waveform enrichment for long video/audio tracks
            for (src in batch.imported) {
                if (src.hasAudioTrack && (src.mediaKind == MediaKind.VIDEO || src.mediaKind == MediaKind.AUDIO)) {
                    runCatching {
                        val audioSummary = mediaAudioTools.analyzeAudioTrack(src.uri, maxDecodedSeconds = 45)
                        val matchingClip = session.currentProject.tracks
                            .flatMap { it.clips }
                            .lastOrNull { it.sourceUri == src.uri && it.beatMarkersMs.isEmpty() }
                        if (matchingClip != null && audioSummary.beatAnalysis.peakEnvelope.isNotEmpty()) {
                            val updated = TimelineEngine.updateClip(session.currentProject, matchingClip.id) { c ->
                                c.copy(
                                    waveformPeaks = audioSummary.beatAnalysis.peakEnvelope,
                                    beatMarkersMs = audioSummary.beatAnalysis.beatTimestampsMs
                                )
                            }
                            session.executeCoalesced("waveform_${matchingClip.id}", "Waveform Analysis") { updated }
                            publishSessionState()
                        }
                    }
                }
            }
        }
    }

    fun generateAndImportSampleClips() {
        viewModelScope.launch {
            notifyToast("Generating studio H.264 sample clips...")
            val samples = demoClipGenerator.generateSampleVideoClips()
            applyMutation("Add Sample Clips") { proj ->
                TimelineEngine.importMediaClips(proj, samples, TrackType.VIDEO_MAIN)
            }
            saveImmediately("Added sample clips")
            notifyToast("Added ${samples.size} studio sample clips!")
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
            val dims = session.currentProject.aspectRatio.computeCodecSafeDimensions(720)
            val canvasClip = demoClipGenerator.generateStudioCanvasClip(
                title = title,
                primaryColorArgb = primaryColorArgb,
                secondaryColorArgb = secondaryColorArgb,
                width = dims.first,
                height = dims.second,
                durationMs = durationMs
            )
            applyMutation("Add '$title' Backdrop") { proj ->
                TimelineEngine.importMediaClips(proj, listOf(canvasClip), TrackType.VIDEO_MAIN, playheadMs.value)
            }
            saveImmediately()
            notifyToast("Added '$title' backdrop (${durationMs / 1000f}s)")
            _uiState.update { it.copy(showMediaImporterSheet = false) }
        }
    }

    // --- Core Clip Editing Operations ---
    fun splitSelectedAtPlayhead() {
        val clip = _uiState.value.selectedClip ?: resolveTargetVisualClip() ?: return
        val beforeCount = session.currentProject.findTrackForClip(clip.id)?.clips?.size ?: 0
        applyMutation("Split Clip") { TimelineEngine.splitClipAt(it, clip.id, playheadMs.value) }
        val afterCount = session.currentProject.findTrackForClip(clip.id)?.clips?.size ?: 0
        if (afterCount == beforeCount) {
            notifyToast("Move playhead inside the clip to split")
        }
    }

    fun trimSelectedClip(newTrimStartMs: Long, newTrimEndMs: Long) {
        val clip = _uiState.value.selectedClip ?: resolveTargetVisualClip() ?: return
        applyCoalescedMutation("trim_${clip.id}", "Trim Clip") {
            TimelineEngine.trimClip(it, clip.id, newTrimStartMs, newTrimEndMs)
        }
    }

    fun setPhotoOrClipDurationMs(targetDurationMs: Long) {
        val clip = resolveTargetVisualClip() ?: return
        val clamped = targetDurationMs.coerceIn(300L, clip.sourceDurationMs.coerceAtLeast(15_000L))
        if (clamped <= clip.sourceDurationMs) {
            val newEnd = (clip.trimStartMs + clamped).coerceAtMost(clip.sourceDurationMs)
            applyCoalescedMutation("dur_${clip.id}", "Clip Duration") {
                TimelineEngine.trimClip(it, clip.id, clip.trimStartMs, newEnd)
            }
        } else {
            val slowFactor = (clip.trimmedSourceDurationMs.toFloat() / clamped.toFloat()).coerceIn(0.15f, 1.0f)
            applyCoalescedMutation("dur_${clip.id}", "Clip Duration") {
                TimelineEngine.setClipSpeed(it, clip.id, slowFactor, SpeedCurvePreset.NONE, emptyList())
            }
        }
    }

    fun cutMiddleTwentyPercentOfSelected() {
        val clip = _uiState.value.selectedClip ?: resolveTargetVisualClip() ?: return
        val dur = clip.timelineDurationMs
        if (dur < 800L) {
            notifyToast("Clip too short to cut middle")
            return
        }
        val cutStart = clip.startTimeOnTrackMs + (dur * 0.40).toLong()
        val cutEnd = clip.startTimeOnTrackMs + (dur * 0.60).toLong()
        applyMutation("Cut Middle Segment") {
            TimelineEngine.cutRangeFromClip(it, clip.id, cutStart, cutEnd)
        }
    }

    fun duplicateSelectedClip() {
        val clip = _uiState.value.selectedClip ?: resolveTargetVisualClip() ?: return
        applyMutation("Duplicate Clip") { TimelineEngine.duplicateClip(it, clip.id) }
    }

    fun deleteSelectedClip() {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Delete Clip", newSelectedClipId = null) {
            TimelineEngine.deleteClip(it, clip.id)
        }
    }

    fun moveSelectedClipOrder(delta: Int) {
        val clip = _uiState.value.selectedClip ?: resolveTargetVisualClip() ?: return
        val track = session.currentProject.findTrackForClip(clip.id) ?: return
        val idx = track.clips.indexOfFirst { it.id == clip.id }
        val target = (idx + delta).coerceIn(0, track.clips.lastIndex)
        if (idx != target) {
            applyMutation("Reorder Clip") { TimelineEngine.reorderClips(it, track.id, idx, target) }
        }
    }

    fun nudgeClipPositionMs(deltaMs: Long) {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Move Clip") {
            TimelineEngine.moveClipToTimestamp(
                project = it,
                clipId = clip.id,
                requestedStartMs = (clip.startTimeOnTrackMs + deltaMs).coerceAtLeast(0L),
                playheadMs = playheadMs.value
            )
        }
    }

    fun mergeAdjacentOnCurrentTrack() {
        val clip = _uiState.value.selectedClip ?: return
        val track = session.currentProject.findTrackForClip(clip.id) ?: return
        if (track.clips.size < 2) {
            notifyToast("Need at least 2 clips on track to merge")
            return
        }
        val ids = track.clips.map { it.id }
        applyMutation("Merge Track Clips") { TimelineEngine.mergeClips(it, ids) }
        notifyToast("Merged clips on ${track.name}")
    }

    fun setCropOnSelected(cropRect: CropRect) {
        val clip = resolveTargetVisualClip() ?: return
        applyCoalescedMutation("crop_${clip.id}", "Crop Clip", newSelectedClipId = clip.id) {
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
        applyMutation("Flip Horizontal", newSelectedClipId = clip.id) {
            TimelineEngine.toggleFlipHorizontal(it, clip.id)
        }
    }

    fun flipSelectedVertical() {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Flip Vertical", newSelectedClipId = clip.id) {
            TimelineEngine.toggleFlipVertical(it, clip.id)
        }
    }

    fun toggleReverseSelected() {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Toggle Reverse", newSelectedClipId = clip.id) {
            TimelineEngine.toggleReverseClip(it, clip.id)
        }
    }

    fun updateSelectedTransform(transform: ClipTransform) {
        val clip = _uiState.value.selectedClip ?: resolveTargetVisualClip() ?: return
        applyCoalescedMutation("transform_${clip.id}", "Transform Clip", newSelectedClipId = clip.id) {
            TimelineEngine.setClipTransform(it, clip.id, transform)
        }
    }

    fun updateSelectedSpeed(
        speed: Float,
        curvePreset: SpeedCurvePreset = SpeedCurvePreset.NONE,
        customPoints: List<SpeedPoint> = emptyList()
    ) {
        val clip = resolveTargetVisualClip() ?: return
        applyCoalescedMutation("speed_${clip.id}", "Speed ${String.format("%.2fx", speed)}", newSelectedClipId = clip.id) {
            TimelineEngine.setClipSpeed(it, clip.id, speed, curvePreset, customPoints)
        }
    }

    fun insertFreezeFrame() {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Freeze Frame", newSelectedClipId = clip.id) {
            TimelineEngine.insertFreezeFrame(it, clip.id, playheadMs.value, 2_000L)
        }
        notifyToast("Inserted 2.0s Freeze Frame")
    }

    // --- Multi-Track Management ---
    fun addTrack(type: TrackType) {
        applyMutation("Add ${type.label} Track") { TimelineEngine.addTrack(it, type) }
    }

    fun toggleTrackLock(trackId: String) {
        applyMutation("Toggle Track Lock") { TimelineEngine.toggleTrackLock(it, trackId) }
    }

    fun toggleTrackHidden(trackId: String) {
        applyMutation("Toggle Track Visibility") { TimelineEngine.toggleTrackHidden(it, trackId) }
    }

    fun toggleTrackMute(trackId: String) {
        applyMutation("Toggle Track Mute") { TimelineEngine.toggleTrackMute(it, trackId) }
    }

    // --- GPU Effects Library (All 32 Effects + 3-Axis Parameter Customization + Multi-Effect Stack) ---
    fun addEffectToSelected(type: EffectType) {
        val clip = resolveTargetVisualClip() ?: run {
            notifyToast("Import a video or photo clip first")
            return
        }
        val existing = clip.effects.firstOrNull { it.type == type }
        val fx = existing?.copy(enabled = true) ?: AppliedEffect(type = type)
        applyMutation("Add ${type.label}", newSelectedClipId = clip.id) {
            TimelineEngine.addOrUpdateEffect(it, clip.id, fx)
        }
    }

    fun updateEffectOnSelected(updated: AppliedEffect) {
        val clip = resolveTargetVisualClip() ?: return
        applyCoalescedMutation(
            coalesceKey = "fx_${clip.id}_${updated.id}",
            actionLabel = "Adjust ${updated.type.label}",
            newSelectedClipId = clip.id
        ) {
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
        notifyToast("Cleared effects on clip")
    }

    fun applyEffectsToAllClips() {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Apply Effects to All Clips", newSelectedClipId = clip.id) {
            TimelineEngine.applyEffectsToAllMainClips(it, clip.effects)
        }
        notifyToast("Applied ${clip.effects.size} effect(s) to all clips")
    }

    // --- Pro Filters, Sharpen/Detail & Color Grading ---
    fun updateColorGradeOnSelected(grade: ColorGradeConfig, coalesceKey: String = "grade") {
        val clip = resolveTargetVisualClip() ?: run {
            notifyToast("Import a video or photo clip first")
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

    /**
     * Applies or removes any built-in/custom Filter Preset WITHOUT wiping out manual Light, Color,
     * Sharpness, Detail, HSL, Curves, or Color Wheel adjustments!
     */
    fun applyColorPresetOnSelected(preset: ColorGradePreset?) {
        val clip = resolveTargetVisualClip() ?: run {
            notifyToast("Import a video or photo clip first")
            return
        }
        val currentStrength = if (clip.colorGrade.lutPresetId == null) 1.0f else clip.colorGrade.lutIntensity.coerceIn(0.15f, 1.0f)
        val nextCfg = if (preset == null) {
            clip.colorGrade.copy(lutPresetId = null, customCubeLutUri = null)
        } else {
            clip.colorGrade.copy(lutPresetId = preset.id, lutIntensity = currentStrength)
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
        val next = clip.colorGrade.copy(lutIntensity = intensity.coerceIn(0f, 1f))
        updateColorGradeOnSelected(next, coalesceKey = "lut_intensity")
    }

    fun applyDetailQuickPreset(presetKey: String) {
        val clip = resolveTargetVisualClip() ?: return
        val g = clip.colorGrade
        val next = when (presetKey) {
            "crisp_hd" -> g.copy(
                sharpness = 0.42f, sharpenRadius = 0.35f, sharpenThreshold = 0.16f,
                clarity = 0.22f, structure = 0.18f, denoise = 0.08f
            )
            "ultra_4k" -> g.copy(
                sharpness = 0.68f, sharpenRadius = 0.28f, sharpenThreshold = 0.14f,
                clarity = 0.36f, structure = 0.42f, dehaze = 0.15f, denoise = 0.10f
            )
            "portrait_soft" -> g.copy(
                sharpness = 0.18f, sharpenRadius = 0.25f, sharpenThreshold = 0.35f,
                clarity = -0.22f, structure = 0f, denoise = 0.28f
            )
            "low_light_clean" -> g.copy(
                sharpness = 0.24f, sharpenRadius = 0.30f, sharpenThreshold = 0.32f,
                clarity = 0.12f, structure = 0.08f, denoise = 0.65f
            )
            "analog_grain" -> g.copy(
                sharpness = 0.16f, clarity = 0.10f, vignette = 0.28f,
                vignetteFeather = 0.65f, grain = 0.36f
            )
            else -> g.resetDetailAndOptics()
        }
        applyMutation("Detail Preset", newSelectedClipId = clip.id) {
            TimelineEngine.setColorGrade(it, clip.id, next)
        }
    }

    fun resetColorSectionOnSelected(section: String) {
        val clip = resolveTargetVisualClip() ?: return
        val g = clip.colorGrade
        val next = when (section) {
            "filter" -> g.resetFilter()
            "light" -> g.resetLight()
            "color" -> g.resetColor()
            "detail" -> g.resetDetailAndOptics()
            "hsl" -> g.copy(hslBands = ColorGradeConfig.defaultHslBands())
            "curves" -> g.copy(curves = RgbCurvesConfig())
            "wheels" -> g.copy(colorWheels = ColorWheelsConfig())
            else -> ColorGradeConfig()
        }
        applyMutation("Reset ${section.replaceFirstChar { it.uppercase() }}", newSelectedClipId = clip.id) {
            TimelineEngine.setColorGrade(it, clip.id, next)
        }
    }

    fun applyColorGradeToAllClips() {
        val clip = resolveTargetVisualClip() ?: return
        applyMutation("Apply Grade to All Clips", newSelectedClipId = clip.id) {
            TimelineEngine.applyColorGradeToAllMainClips(it, clip.colorGrade)
        }
        notifyToast("Applied filter, sharpen & adjustments to all clips!")
    }

    fun importCubeLutUriOnSelected(uri: Uri) {
        val clip = resolveTargetVisualClip() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (content.isNullOrBlank()) {
                notifyToast("Could not read .cube file")
                return@launch
            }
            val valid = runCatching { ColorGradingEngine.parseAdobeCubeLut(content) }.getOrNull()
            if (valid == null) {
                notifyToast("Invalid .cube 3D LUT format")
                return@launch
            }
            val savedDir = File(context.filesDir, "luts").apply { mkdirs() }
            val savedFile = File(savedDir, "lut_${System.currentTimeMillis()}.cube")
            savedFile.writeText(content)
            viewModelScope.launch {
                applyMutation("Import 3D LUT '${valid.title}'", newSelectedClipId = clip.id) { proj ->
                    TimelineEngine.setColorGrade(
                        proj,
                        clip.id,
                        clip.colorGrade.copy(customCubeLutUri = Uri.fromFile(savedFile).toString())
                    )
                }
                notifyToast("Applied 3D LUT: ${valid.title} (${valid.size}³)")
            }
        }
    }

    fun saveCustomColorPreset(name: String) {
        val clip = resolveTargetVisualClip() ?: return
        val g = clip.colorGrade
        // Merge active preset parameters into a standalone custom preset so it never double-applies
        val activePreset = ColorGradingEngine.findPresetById(g.lutPresetId, session.currentProject.customColorPresets)
        val mergedConfig = if (activePreset != null) {
            val s = g.lutIntensity.coerceIn(0f, 1f)
            val p = activePreset.config
            g.copy(
                lutPresetId = null,
                lutIntensity = 1.0f,
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
            g.copy(lutPresetId = null, lutIntensity = 1.0f)
        }

        val preset = ColorGradePreset(
            name = name.trim().ifBlank { "My Look" },
            category = "Custom",
            accentHex = 0xFF00E5FF,
            secondaryHex = 0xFF7C4DFF,
            config = mergedConfig,
            isUserCustom = true
        )
        applyMutation("Save Custom Look", newSelectedClipId = clip.id) { proj ->
            val withPreset = proj.copy(customColorPresets = proj.customColorPresets + preset)
            TimelineEngine.setColorGrade(
                withPreset,
                clip.id,
                ColorGradeConfig(lutPresetId = preset.id, lutIntensity = 1.0f)
            )
        }
        notifyToast("Saved custom filter '${preset.name}'")
    }

    // --- Transitions & Keyframe Animation ---
    fun setTransitionOnSelected(transition: ClipTransition?) {
        val clip = resolveTargetVisualClip() ?: return
        applyCoalescedMutation("trans_${clip.id}", "Set Transition", newSelectedClipId = clip.id) {
            TimelineEngine.setClipTransition(it, clip.id, transition, clip.transitionOut)
        }
    }

    fun applyTransitionToAllClips(transition: ClipTransition) {
        applyMutation("Apply Transition to All") {
            TimelineEngine.applyTransitionToAllMainClips(it, transition)
        }
        notifyToast("Applied ${transition.type.label} across main timeline")
    }

    fun upsertKeyframeAtPlayhead(
        property: KeyframeProperty,
        value: Float,
        easing: EasingCurve = EasingCurve.EASE_IN_OUT
    ) {
        val clip = _uiState.value.selectedClip ?: resolveTargetVisualClip() ?: return
        val localOffset = (playheadMs.value - clip.startTimeOnTrackMs)
            .coerceIn(0L, clip.timelineDurationMs)
        val kf = Keyframe(
            property = property,
            timeOffsetMs = localOffset,
            value = value,
            easing = easing
        )
        applyCoalescedMutation("kf_${clip.id}_${property.name}", "Keyframe ${property.label}", newSelectedClipId = clip.id) {
            TimelineEngine.upsertKeyframe(it, clip.id, kf)
        }
    }

    fun removeKeyframe(keyframeId: String) {
        val clip = _uiState.value.selectedClip ?: resolveTargetVisualClip() ?: return
        applyMutation("Delete Keyframe", newSelectedClipId = clip.id) {
            TimelineEngine.removeKeyframe(it, clip.id, keyframeId)
        }
    }

    // --- Audio Studio (Extract Audio, Voiceover, SFX, DSP, Beat Sync) ---
    fun extractAudioFromSelectedClip() {
        val clip = resolveTargetVisualClip() ?: return
        if (!clip.hasAudioTrack) {
            notifyToast("This clip has no audio stream to extract")
            return
        }
        viewModelScope.launch {
            val extractedFile = mediaAudioTools.extractAudioFromVideoToM4a(clip.sourceUri)
            if (extractedFile == null) {
                notifyToast("Clip has no audio stream to extract")
                return@launch
            }
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
                val withAudio = TimelineEngine.importMediaClips(
                    proj,
                    listOf(src),
                    TrackType.AUDIO_MUSIC,
                    clip.startTimeOnTrackMs
                )
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
                    val summary = mediaAudioTools.analyzeAudioTrack(Uri.fromFile(file).toString())
                    val src = ImportedMediaSource(
                        uri = Uri.fromFile(file).toString(),
                        displayName = "Voiceover",
                        mediaKind = MediaKind.AUDIO,
                        durationMs = durMs,
                        hasAudioTrack = true,
                        waveformPeaks = summary.beatAnalysis.peakEnvelope
                    )
                    applyMutation("Record Voiceover") { proj ->
                        TimelineEngine.importMediaClips(
                            proj,
                            listOf(src),
                            TrackType.AUDIO_VOICEOVER,
                            playheadMs.value
                        )
                    }
                    notifyToast("Added ${String.format("%.1fs", durMs / 1000f)} Voiceover")
                }
            }
        } else {
            previewController.pause()
            val out = voiceoverRecorder.startRecording()
            if (out == null) {
                notifyToast("Microphone unavailable or permission needed")
                return
            }
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

    fun updateSelectedAudioDsp(
        volume: Float? = null,
        fadeInMs: Long? = null,
        fadeOutMs: Long? = null,
        noiseReduction: Float? = null,
        voiceEnhancement: Float? = null,
        audioDucking: Boolean? = null,
        isMuted: Boolean? = null
    ) {
        val clip = _uiState.value.selectedClip ?: resolveTargetVisualClip() ?: return
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

    fun detectBeatsAndAutoSync() {
        viewModelScope.launch {
            val musicClip = session.currentProject.tracks
                .firstOrNull { it.type == TrackType.AUDIO_MUSIC }?.clips?.firstOrNull()
                ?: _uiState.value.selectedClip
            if (musicClip == null) {
                notifyToast("Add a video or music track first")
                return@launch
            }
            notifyToast("Analyzing audio transients & BPM...")
            val summary = mediaAudioTools.analyzeAudioTrack(musicClip.sourceUri)
            val beats = summary.beatAnalysis.beatTimestampsMs
            if (beats.size < 2) {
                notifyToast("Estimated ${summary.beatAnalysis.estimatedBpm} BPM (subtle transients)")
                return@launch
            }
            applyMutation("Beat Sync (${summary.beatAnalysis.estimatedBpm} BPM)") { proj ->
                val marked = TimelineEngine.updateClip(proj, musicClip.id) {
                    it.copy(beatMarkersMs = beats, waveformPeaks = summary.beatAnalysis.peakEnvelope)
                }
                AudioDspEngine.syncVideoClipsToBeats(marked, beats, beatsPerCut = 2)
            }
            notifyToast("Synced cuts to ${beats.size} beats (${summary.beatAnalysis.estimatedBpm} BPM)")
        }
    }

    // --- Text, Stickers & Subtitles (Hindi hi-IN + English en-US) ---
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
        applyMutation("Add Sticker ${sticker.content}") { proj ->
            TimelineEngine.addStickerClip(proj, sticker, playheadMs.value, 2_500L)
        }
    }

    fun dictateCaptionWithSpeechRecognizer(languageTag: String) {
        viewModelScope.launch {
            previewController.pause()
            notifyToast("Listening (${if (languageTag.startsWith("hi")) "Hindi हिन्दी" else "English"})...")
            val startAt = playheadMs.value
            val result = speechCaptionService.recognizeSingleUtteranceOnMain(languageTag)
            result.onSuccess { cue ->
                val timed = cue.copy(
                    startTimeMs = startAt,
                    endTimeMs = startAt + (cue.endTimeMs - cue.startTimeMs)
                )
                applyMutation("Auto Caption ($languageTag)") { proj ->
                    SubtitleCodec.applySubtitlesToTextTrack(proj, proj.subtitles + timed)
                }
                notifyToast("Recognized: ${timed.text}")
            }.onFailure { err ->
                notifyToast(err.localizedMessage ?: "Speech recognition unavailable")
            }
        }
    }

    fun generateVadTimedCaptionSlots(languageTag: String) {
        val mainClip = resolveTargetVisualClip() ?: return
        viewModelScope.launch {
            notifyToast("Scanning speech activity intervals...")
            val summary = mediaAudioTools.analyzeAudioTrack(mainClip.sourceUri)
            val intervals = summary.vadIntervalsMs
            if (intervals.isEmpty()) {
                notifyToast("No clear voice intervals detected in clip audio")
                return@launch
            }
            val cues = intervals.mapIndexed { idx, range ->
                SubtitleCue(
                    id = UUID.randomUUID().toString(),
                    text = if (languageTag.startsWith("hi")) "संवाद #${idx + 1}" else "Caption #${idx + 1}",
                    startTimeMs = mainClip.startTimeOnTrackMs + range.first,
                    endTimeMs = mainClip.startTimeOnTrackMs + range.last,
                    languageTag = languageTag
                )
            }
            applyMutation("Auto-Segment ${cues.size} Captions") { proj ->
                SubtitleCodec.applySubtitlesToTextTrack(proj, cues)
            }
            notifyToast("Detected ${cues.size} speech timing segments")
        }
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
            if (cues.isNotEmpty()) {
                viewModelScope.launch {
                    applyMutation("Import SRT (${cues.size} cues)") { proj ->
                        SubtitleCodec.applySubtitlesToTextTrack(proj, cues)
                    }
                    notifyToast("Imported ${cues.size} SRT subtitles")
                }
            }
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

    // --- Modular AI Video Editing Suite (All 17 Features) ---
    fun executeAiTool(tool: AiToolType, scriptInput: String = "") {
        val clip = resolveTargetVisualClip()
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingAi = true, aiStatusText = "Running ${tool.title}...") }
            try {
                when (tool) {
                    AiToolType.AUTO_EDIT -> {
                        val beats = if (clip != null && clip.hasAudioTrack) {
                            mediaAudioTools.analyzeAudioTrack(clip.sourceUri).beatAnalysis.beatTimestampsMs
                        } else emptyList()
                        applyMutation("AI Auto Edit") { LocalAiAlgorithms.applyAiAutoEdit(it, beats) }
                        notifyToast("AI Auto Edit applied filter, sharpen & transitions")
                    }

                    AiToolType.SCENE_DETECTION -> {
                        if (clip == null) { notifyToast("Select a video clip first"); return@launch }
                        val (samples, _) = visionAiService.sampleClipFrames(
                            clip.sourceUri, clip.trimStartMs, clip.effectiveTrimEndMs, sampleCount = 16, detectFaces = false
                        )
                        val cuts = LocalAiAlgorithms.detectSceneCutTimestamps(samples)
                        if (cuts.isEmpty()) {
                            notifyToast("Single continuous scene detected (no hard cuts found)")
                        } else {
                            applyMutation("AI Scene Split (${cuts.size} cuts)") { proj ->
                                var working = proj
                                for (cutOffset in cuts.sortedDescending()) {
                                    val targetClip = working.mainVideoClips
                                        .firstOrNull { (clip.startTimeOnTrackMs + cutOffset) in (it.startTimeOnTrackMs + 150L) until (it.endTimeOnTrackMs - 150L) }
                                    if (targetClip != null) {
                                        working = TimelineEngine.splitClipAt(working, targetClip.id, clip.startTimeOnTrackMs + cutOffset)
                                    }
                                }
                                working
                            }
                            notifyToast("Detected & split ${cuts.size} visual scene cut(s)")
                        }
                    }

                    AiToolType.VIDEO_HIGHLIGHTS -> {
                        if (clip == null) { notifyToast("Select a video clip first"); return@launch }
                        val (samples, _) = visionAiService.sampleClipFrames(
                            clip.sourceUri, clip.trimStartMs, clip.effectiveTrimEndMs, sampleCount = 14
                        )
                        val audio = if (clip.hasAudioTrack) mediaAudioTools.analyzeAudioTrack(clip.sourceUri) else null
                        val highlights = LocalAiAlgorithms.extractHighlightSegments(
                            samples = samples,
                            audioPeaks = audio?.beatAnalysis?.peakEnvelope.orEmpty(),
                            sourceDurationMs = clip.trimmedSourceDurationMs,
                            windowDurationMs = minOf(3_000L, clip.trimmedSourceDurationMs),
                            maxHighlights = 1
                        )
                        val best = highlights.firstOrNull()
                        if (best != null) {
                            applyMutation("AI Highlight Trim", newSelectedClipId = clip.id) { proj ->
                                TimelineEngine.trimClip(
                                    proj,
                                    clip.id,
                                    clip.trimStartMs + best.startMs,
                                    clip.trimStartMs + best.endMs
                                )
                            }
                            notifyToast("Trimmed to peak highlight (${best.reason})")
                        }
                    }

                    AiToolType.BEAT_SYNC -> detectBeatsAndAutoSync()

                    AiToolType.SMART_CROP, AiToolType.AUTO_REFRAME, AiToolType.FACE_TRACKING, AiToolType.MOTION_TRACKING -> {
                        if (clip == null) { notifyToast("Select a video clip first"); return@launch }
                        val needFaces = tool != AiToolType.MOTION_TRACKING
                        val (samples, dims) = visionAiService.sampleClipFrames(
                            clip.sourceUri, clip.trimStartMs, clip.effectiveTrimEndMs, sampleCount = 10, detectFaces = needFaces
                        )
                        val srcAspect = dims.first.toFloat() / dims.second.coerceAtLeast(1).toFloat()
                        val dstAspect = session.currentProject.aspectRatio.ratioFloat
                        val kfs = LocalAiAlgorithms.generateTrackingKeyframes(
                            clipDurationMs = clip.timelineDurationMs,
                            samples = samples,
                            sourceAspect = srcAspect,
                            targetAspect = dstAspect
                        )
                        applyMutation(tool.title, newSelectedClipId = clip.id) { proj ->
                            TimelineEngine.updateClip(proj, clip.id) { c ->
                                val kept = c.keyframes.filterNot {
                                    it.property in setOf(KeyframeProperty.POSITION_X, KeyframeProperty.POSITION_Y, KeyframeProperty.SCALE)
                                }
                                c.copy(keyframes = (kept + kfs).sortedBy { it.timeOffsetMs })
                            }
                        }
                        val facesFound = samples.count { it.hasDetectedFace }
                        notifyToast("${tool.title}: generated ${kfs.size} keyframes ($facesFound face samples)")
                    }

                    AiToolType.BACKGROUND_REMOVAL -> {
                        if (clip == null) { notifyToast("Select a video clip first"); return@launch }
                        val seg = visionAiService.analyzeSubjectSegmentation(
                            clip.sourceUri,
                            clip.trimStartMs + clip.trimmedSourceDurationMs / 2
                        )
                        val focusRadius = seg?.recommendedFocusRadius ?: 0.45f
                        val bgFx = AppliedEffect(
                            type = EffectType.BACKGROUND_BLUR,
                            intensity = 0.88f,
                            secondaryParam = focusRadius
                        )
                        applyMutation("AI Subject Background Isolation", newSelectedClipId = clip.id) { proj ->
                            TimelineEngine.addOrUpdateEffect(proj, clip.id, bgFx)
                        }
                        val pct = ((seg?.subjectRatio ?: 0.35f) * 100).toInt()
                        notifyToast("ML Kit Subject Mask isolated ($pct% foreground area)")
                    }

                    AiToolType.SUBTITLE_GENERATOR -> generateVadTimedCaptionSlots("en-US")

                    AiToolType.VOICE_ENHANCEMENT -> {
                        updateSelectedAudioDsp(voiceEnhancement = 0.78f)
                        notifyToast("Enabled 2.8kHz Vocal Clarity DSP Exciter")
                    }

                    AiToolType.NOISE_REMOVAL -> {
                        updateSelectedAudioDsp(noiseReduction = 0.75f)
                        notifyToast("Enabled 90Hz High-Pass + Spectral Noise Gate")
                    }

                    AiToolType.VIDEO_UPSCALING -> {
                        if (clip == null) { notifyToast("Select a video clip first"); return@launch }
                        val sharp = AppliedEffect(type = EffectType.SHARPEN, intensity = 0.62f, secondaryParam = 0.45f, tertiaryParam = 0.25f)
                        applyMutation("AI 4K Detail Upscale", newSelectedClipId = clip.id) { proj ->
                            val p1 = TimelineEngine.addOrUpdateEffect(proj, clip.id, sharp)
                            TimelineEngine.updateClip(p1, clip.id) { it.copy(upscaleTargetHeight = 2160) }
                        }
                        notifyToast("Configured 2160p 4K upscale + adaptive GPU unsharp kernel")
                    }

                    AiToolType.IMAGE_TO_VIDEO -> {
                        applyMutation("AI 3D Ken-Burns Motion") {
                            LocalAiAlgorithms.applyImageToVideoMotion(it, clip?.id)
                        }
                        notifyToast("Applied 3D parallax pan/zoom keyframes")
                    }

                    AiToolType.SCRIPT_TO_VIDEO -> {
                        val textToUse = scriptInput.ifBlank {
                            "Hook: Every frame tells a story\nBuild momentum with sharp cuts\nColor grade for maximum emotion\nExport in crisp 4K 60fps"
                        }
                        val existingClips = session.currentProject.mainVideoClips
                        val fallbackMedia = if (existingClips.isEmpty()) {
                            demoClipGenerator.generateSampleVideoClips()
                        } else emptyList()
                        applyMutation("AI Script to Video") { proj ->
                            LocalAiAlgorithms.buildScriptToVideoProject(proj, textToUse, fallbackMedia)
                        }
                        notifyToast("Built multi-scene timeline & captions from script")
                    }

                    AiToolType.VIDEO_SUMMARIZATION -> {
                        val samples = if (clip != null) {
                            visionAiService.sampleClipFrames(clip.sourceUri, clip.trimStartMs, clip.effectiveTrimEndMs, 10, true).first
                        } else emptyList()
                        val report = LocalAiAlgorithms.summarizeProject(session.currentProject, samples)
                        _uiState.update { it.copy(latestSummaryReport = report) }
                    }

                    AiToolType.OBJECT_REMOVAL -> {
                        if (clip == null) { notifyToast("Select a video clip first"); return@launch }
                        val outcome = externalGenAiGateway.requestObjectInpainting(
                            clipUri = clip.sourceUri,
                            normalizedMaskRect = CropRect(0.35f, 0.35f, 0.65f, 0.65f),
                            startMs = clip.trimStartMs,
                            endMs = clip.effectiveTrimEndMs
                        )
                        when (outcome) {
                            is ExternalAiResult.Success -> notifyToast(outcome.message)
                            is ExternalAiResult.NotConfigured -> {
                                val blurFx = AppliedEffect(type = EffectType.GAUSSIAN_BLUR, intensity = 0.45f, secondaryParam = 0.5f)
                                applyMutation("Local Object Blur Mask", newSelectedClipId = clip.id) {
                                    TimelineEngine.addOrUpdateEffect(it, clip.id, blurFx)
                                }
                                notifyToast("No Cloud Inpaint API set in Settings; applied local GPU Blur")
                            }
                            is ExternalAiResult.Failure -> notifyToast(outcome.errorMessage)
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(isProcessingAi = false, aiStatusText = null) }
            }
        }
    }

    // --- Save as Template & Professional Export (Video MP4, Photo Frame JPG, Audio M4A) ---
    fun saveCurrentProjectAsTemplate(name: String, category: TemplateCategory) {
        viewModelScope.launch {
            val tpl = TemplateCatalog.extractTemplateFromProject(session.currentProject, name, category)
            projectRepository.saveCustomTemplate(tpl)
            notifyToast("Saved '${tpl.name}' to Templates!")
        }
    }

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
            // Run single deterministic export pass (avoiding dual WorkManager + Foreground encoder collision!)
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
                    notifyToast("Saved graded photo frame to Gallery!")
                    exportEngine.resetState()
                }
                is ExportProgressState.Error -> {
                    notifyToast(outcome.message)
                    exportEngine.resetState()
                }
                else -> {}
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

    override fun onCleared() {
        super.onCleared()
        voiceoverRecorder.stopRecording()
        previewController.release()
    }
}

package com.vibecut.pro.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
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
import com.vibecut.pro.media.export.ExportWorker
import com.vibecut.pro.media.export.VibeCutExportEngine
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
    EDIT("Edit"),
    EFFECTS("Effects"),
    COLOR("Color"),
    TRANSITIONS("Trans"),
    KEYFRAMES("Keyframes"),
    AUDIO("Audio"),
    TEXT("Text"),
    AI("AI Studio")
}

data class EditorUiState(
    val project: Project = Project(),
    val selectedClipId: String? = null,
    val activeTab: EditorInspectorTab = EditorInspectorTab.EDIT,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoCount: Int = 0,
    val redoCount: Int = 0,
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
            ?: project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN }?.clips?.firstOrNull()
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val preferencesRepository: AppPreferencesRepository,
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

    private var autosaveJob: Job? = null
    private var voiceMeterJob: Job? = null
    private var proxyModeEnabled: Boolean = false

    init {
        viewModelScope.launch {
            preferencesRepository.preferencesFlow.collect { prefs ->
                proxyModeEnabled = prefs.lowEndProxyPreview
                previewController.syncWithProject(session.currentProject, proxyModeEnabled)
                restartAutosaveLoop(prefs.autosaveIntervalSec)
            }
        }
    }

    fun loadProject(projectId: String) {
        viewModelScope.launch {
            val loaded = projectRepository.loadProject(projectId)
                ?: projectRepository.createNewProject("Untitled Edit", AspectRatioPreset.REELS_9_16)
            session.reset(loaded)
            val defaultSel = loaded.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN }?.clips?.firstOrNull()?.id
            publishSessionState(selectedClipId = defaultSel)
            previewController.syncWithProject(loaded, proxyModeEnabled)
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
            ?: next.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN }?.clips?.firstOrNull()?.id
        publishSessionState(selectedClipId = validSelection)
        previewController.syncWithProject(next, proxyModeEnabled)
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

    fun selectClip(clipId: String?) {
        _uiState.update { it.copy(selectedClipId = clipId) }
    }

    fun selectTab(tab: EditorInspectorTab) {
        _uiState.update { it.copy(activeTab = tab) }
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
        publishSessionState()
        previewController.syncWithProject(prev, proxyModeEnabled)
        notifyToast("Undo")
    }

    fun redo() {
        val next = session.redo() ?: return
        publishSessionState()
        previewController.syncWithProject(next, proxyModeEnabled)
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
        val next = TimelineEngine.setTimelineZoom(session.currentProject, zoom)
        session.execute("Zoom") { next }
        publishSessionState()
    }

    fun setAspectRatio(preset: AspectRatioPreset) {
        applyMutation("Aspect Ratio ${preset.label}") { it.copy(aspectRatio = preset) }
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

    // --- Media Import & Sample Generation ---
    fun importMediaUris(uris: List<Uri>, targetTrackType: TrackType = TrackType.VIDEO_MAIN) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            // Take persistable read permission when offered by Android Photo Picker / SAF
            for (u in uris) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            val sources = uris.map {
                HomeViewModel.inspectMediaUri(context, it, forceAudio = targetTrackType.isAudio && !targetTrackType.isVisual)
            }
            applyMutation("Import Media") { proj ->
                TimelineEngine.importMediaClips(proj, sources, targetTrackType, playheadMs.value)
            }
            saveImmediately()

            // Asynchronously extract real PCM waveforms & beats for imported clips
            for (src in sources) {
                if (src.mediaKind == MediaKind.VIDEO || src.mediaKind == MediaKind.AUDIO) {
                    val audioSummary = mediaAudioTools.analyzeAudioTrack(src.uri, maxDecodedSeconds = 45)
                    val matchingClip = session.currentProject.tracks
                        .flatMap { it.clips }
                        .lastOrNull { it.sourceUri == src.uri && it.waveformPeaks.isEmpty() }
                    if (matchingClip != null) {
                        applyMutation("Waveform Analysis") { proj ->
                            TimelineEngine.updateClip(proj, matchingClip.id) { c ->
                                c.copy(
                                    waveformPeaks = audioSummary.beatAnalysis.peakEnvelope,
                                    beatMarkersMs = audioSummary.beatAnalysis.beatTimestampsMs
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun generateAndImportSampleClips() {
        viewModelScope.launch {
            notifyToast("Encoding H.264 sample clips...")
            val samples = demoClipGenerator.generateSampleVideoClips()
            applyMutation("Add Sample Clips") { proj ->
                TimelineEngine.importMediaClips(proj, samples, TrackType.VIDEO_MAIN)
            }
            saveImmediately("Added sample clips")
            notifyToast("Added ${samples.size} sample clips!")
        }
    }

    // --- Core Clip Editing Operations ---
    fun splitSelectedAtPlayhead() {
        val clip = _uiState.value.selectedClip ?: return
        val beforeCount = session.currentProject.findTrackForClip(clip.id)?.clips?.size ?: 0
        applyMutation("Split Clip") { TimelineEngine.splitClipAt(it, clip.id, playheadMs.value) }
        val afterCount = session.currentProject.findTrackForClip(clip.id)?.clips?.size ?: 0
        if (afterCount == beforeCount) {
            notifyToast("Move playhead inside the clip to split")
        }
    }

    fun trimSelectedClip(newTrimStartMs: Long, newTrimEndMs: Long) {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Trim Clip") {
            TimelineEngine.trimClip(it, clip.id, newTrimStartMs, newTrimEndMs)
        }
    }

    fun cutMiddleTwentyPercentOfSelected() {
        val clip = _uiState.value.selectedClip ?: return
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
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Duplicate Clip") { TimelineEngine.duplicateClip(it, clip.id) }
    }

    fun deleteSelectedClip() {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Delete Clip", newSelectedClipId = null) {
            TimelineEngine.deleteClip(it, clip.id)
        }
    }

    fun moveSelectedClipOrder(delta: Int) {
        val clip = _uiState.value.selectedClip ?: return
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
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Crop Clip") { TimelineEngine.setCrop(it, clip.id, cropRect) }
    }

    fun rotateSelected90(clockwise: Boolean = true) {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Rotate 90°") { TimelineEngine.rotateClip90(it, clip.id, clockwise) }
    }

    fun flipSelectedHorizontal() {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Flip Horizontal") { TimelineEngine.toggleFlipHorizontal(it, clip.id) }
    }

    fun flipSelectedVertical() {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Flip Vertical") { TimelineEngine.toggleFlipVertical(it, clip.id) }
    }

    fun toggleReverseSelected() {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Toggle Reverse") { TimelineEngine.toggleReverseClip(it, clip.id) }
    }

    fun updateSelectedTransform(transform: ClipTransform) {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Transform Clip") { TimelineEngine.setClipTransform(it, clip.id, transform) }
    }

    fun updateSelectedSpeed(
        speed: Float,
        curvePreset: SpeedCurvePreset = SpeedCurvePreset.NONE,
        customPoints: List<SpeedPoint> = emptyList()
    ) {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Speed ${String.format("%.2fx", speed)}") {
            TimelineEngine.setClipSpeed(it, clip.id, speed, curvePreset, customPoints)
        }
    }

    fun insertFreezeFrame() {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Freeze Frame") {
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

    // --- GPU Effects Library (All 22 Effects + Multi-Effect Stack) ---
    fun addEffectToSelected(type: EffectType) {
        val clip = _uiState.value.selectedClip ?: return
        val fx = AppliedEffect(type = type)
        applyMutation("Add ${type.label}") { TimelineEngine.addOrUpdateEffect(it, clip.id, fx) }
    }

    fun updateEffectOnSelected(updated: AppliedEffect) {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Adjust ${updated.type.label}") {
            TimelineEngine.addOrUpdateEffect(it, clip.id, updated)
        }
    }

    fun removeEffectFromSelected(effectId: String) {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Remove Effect") { TimelineEngine.removeEffect(it, clip.id, effectId) }
    }

    // --- Professional Color Grading ---
    fun updateColorGradeOnSelected(grade: ColorGradeConfig) {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Color Grade") { TimelineEngine.setColorGrade(it, clip.id, grade) }
    }

    fun applyColorPresetOnSelected(preset: ColorGradePreset) {
        val clip = _uiState.value.selectedClip ?: return
        val nextCfg = preset.config.copy(lutPresetId = preset.id)
        applyMutation("Preset ${preset.name}") { TimelineEngine.setColorGrade(it, clip.id, nextCfg) }
    }

    fun importCubeLutUriOnSelected(uri: Uri) {
        val clip = _uiState.value.selectedClip ?: return
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
                applyMutation("Import 3D LUT '${valid.title}'") { proj ->
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
        val clip = _uiState.value.selectedClip ?: return
        val preset = ColorGradePreset(
            name = name.trim().ifBlank { "My Look" },
            category = "Custom",
            accentHex = 0xFF00E5FF,
            config = clip.colorGrade,
            isUserCustom = true
        )
        applyMutation("Save Color Preset") { proj ->
            proj.copy(customColorPresets = proj.customColorPresets + preset)
        }
        notifyToast("Saved preset '${preset.name}'")
    }

    // --- Transitions & Keyframe Animation ---
    fun setTransitionOnSelected(transition: ClipTransition?) {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Set Transition") {
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
        val clip = _uiState.value.selectedClip ?: return
        val localOffset = (playheadMs.value - clip.startTimeOnTrackMs)
            .coerceIn(0L, clip.timelineDurationMs)
        val kf = Keyframe(
            property = property,
            timeOffsetMs = localOffset,
            value = value,
            easing = easing
        )
        applyMutation("Keyframe ${property.label}") {
            TimelineEngine.upsertKeyframe(it, clip.id, kf)
        }
    }

    fun removeKeyframe(keyframeId: String) {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Delete Keyframe") {
            TimelineEngine.removeKeyframe(it, clip.id, keyframeId)
        }
    }

    // --- Audio Studio (Extract Audio, Voiceover, SFX, DSP, Beat Sync) ---
    fun extractAudioFromSelectedClip() {
        val clip = _uiState.value.selectedClip ?: return
        if (clip.mediaKind != MediaKind.VIDEO) {
            notifyToast("Select a video clip to extract audio")
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
                    notifyToast("Added ${durMs / 1000f}s Voiceover")
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
        audioDucking: Boolean? = null
    ) {
        val clip = _uiState.value.selectedClip ?: return
        applyMutation("Audio Mix") { proj ->
            TimelineEngine.updateClip(proj, clip.id) { c ->
                c.copy(
                    volume = volume ?: c.volume,
                    fadeInMs = fadeInMs ?: c.fadeInMs,
                    fadeOutMs = fadeOutMs ?: c.fadeOutMs,
                    noiseReduction = noiseReduction ?: c.noiseReduction,
                    voiceEnhancement = voiceEnhancement ?: c.voiceEnhancement,
                    audioDucking = audioDucking ?: c.audioDucking
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
        applyMutation("Add Text") { proj ->
            TimelineEngine.addTextClip(proj, spec, playheadMs.value, 3_000L)
        }
    }

    fun updateSelectedTextSpec(spec: TextStyleSpec) {
        val clip = _uiState.value.selectedClip ?: return
        if (clip.textSpec == null) return
        applyMutation("Edit Text") { proj ->
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
        val mainClip = _uiState.value.selectedClip ?: return
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
        val clip = _uiState.value.selectedClip
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingAi = true, aiStatusText = "Running ${tool.title}...") }
            try {
                when (tool) {
                    AiToolType.AUTO_EDIT -> {
                        val beats = if (clip != null) {
                            mediaAudioTools.analyzeAudioTrack(clip.sourceUri).beatAnalysis.beatTimestampsMs
                        } else emptyList()
                        applyMutation("AI Auto Edit") { LocalAiAlgorithms.applyAiAutoEdit(it, beats) }
                        notifyToast("AI Auto Edit applied color grade, sharpen & transitions")
                    }

                    AiToolType.SCENE_DETECTION -> {
                        if (clip == null) { notifyToast("Select a video clip first"); return@launch }
                        val (samples, _) = visionAiService.sampleClipFrames(
                            clip.sourceUri, clip.trimStartMs, clip.trimEndMs, sampleCount = 16, detectFaces = false
                        )
                        val cuts = LocalAiAlgorithms.detectSceneCutTimestamps(samples)
                        if (cuts.isEmpty()) {
                            notifyToast("Single continuous scene detected (no hard cuts found)")
                        } else {
                            applyMutation("AI Scene Split (${cuts.size} cuts)") { proj ->
                                var working = proj
                                for (cutOffset in cuts.sortedDescending()) {
                                    val targetClip = working.tracks.first { it.type == TrackType.VIDEO_MAIN }
                                        .clips.firstOrNull { (clip.startTimeOnTrackMs + cutOffset) in (it.startTimeOnTrackMs + 150L) until (it.endTimeOnTrackMs - 150L) }
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
                            clip.sourceUri, clip.trimStartMs, clip.trimEndMs, sampleCount = 14
                        )
                        val audio = mediaAudioTools.analyzeAudioTrack(clip.sourceUri)
                        val highlights = LocalAiAlgorithms.extractHighlightSegments(
                            samples = samples,
                            audioPeaks = audio.beatAnalysis.peakEnvelope,
                            sourceDurationMs = clip.trimmedSourceDurationMs,
                            windowDurationMs = minOf(3_000L, clip.trimmedSourceDurationMs),
                            maxHighlights = 1
                        )
                        val best = highlights.firstOrNull()
                        if (best != null) {
                            applyMutation("AI Highlight Trim") { proj ->
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
                            clip.sourceUri, clip.trimStartMs, clip.trimEndMs, sampleCount = 10, detectFaces = needFaces
                        )
                        val srcAspect = dims.first.toFloat() / dims.second.coerceAtLeast(1).toFloat()
                        val dstAspect = session.currentProject.aspectRatio.ratioFloat
                        val kfs = LocalAiAlgorithms.generateTrackingKeyframes(
                            clipDurationMs = clip.timelineDurationMs,
                            samples = samples,
                            sourceAspect = srcAspect,
                            targetAspect = dstAspect
                        )
                        applyMutation(tool.title) { proj ->
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
                        applyMutation("AI Subject Background Isolation") { proj ->
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
                        val sharp = AppliedEffect(type = EffectType.SHARPEN, intensity = 0.62f, secondaryParam = 0.45f)
                        applyMutation("AI 4K Detail Upscale") { proj ->
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
                        val existingClips = session.currentProject.tracks.first { it.type == TrackType.VIDEO_MAIN }.clips
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
                            visionAiService.sampleClipFrames(clip.sourceUri, clip.trimStartMs, clip.trimEndMs, 10, true).first
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
                            endMs = clip.trimEndMs
                        )
                        when (outcome) {
                            is ExternalAiResult.Success -> notifyToast(outcome.message)
                            is ExternalAiResult.NotConfigured -> {
                                // Apply local GPU Soft Blur Mask as honest on-device privacy/object cover
                                val blurFx = AppliedEffect(type = EffectType.GAUSSIAN_BLUR, intensity = 0.45f, secondaryParam = 0.5f)
                                applyMutation("Local Object Blur Mask") {
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

    // --- Save as Template & Professional Export ---
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
        viewModelScope.launch {
            projectRepository.saveProject(session.currentProject, "Pre-export save")
            session.markSaved()
            if (cfg.runInBackground) {
                val req = OneTimeWorkRequestBuilder<ExportWorker>()
                    .setInputData(workDataOf(ExportWorker.KEY_PROJECT_ID to session.currentProject.id))
                    .build()
                WorkManager.getInstance(context).enqueue(req)
                notifyToast("Queued background export via WorkManager")
            }
            exportEngine.exportProject(session.currentProject, cfg)
        }
    }

    fun cancelExport() = exportEngine.cancelActiveExport()

    fun resetExportStatus() = exportEngine.resetState()

    fun buildShareExportIntent(path: String, audioOnly: Boolean): Intent? =
        exportEngine.buildShareIntent(path, audioOnly)

    override fun onCleared() {
        super.onCleared()
        voiceoverRecorder.stopRecording()
        previewController.release()
    }
}

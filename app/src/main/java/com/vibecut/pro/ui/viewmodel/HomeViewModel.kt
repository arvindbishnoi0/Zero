package com.vibecut.pro.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibecut.pro.data.local.ProjectRevisionEntity
import com.vibecut.pro.data.preferences.AppPreferencesRepository
import com.vibecut.pro.data.preferences.StudioPreferences
import com.vibecut.pro.data.repository.ProjectRepository
import com.vibecut.pro.data.repository.ProjectSummary
import com.vibecut.pro.engine.model.AspectRatioPreset
import com.vibecut.pro.engine.model.MediaKind
import com.vibecut.pro.engine.model.TrackType
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import com.vibecut.pro.engine.timeline.TimelineEngine
import com.vibecut.pro.media.export.VibeCutExportEngine
import com.vibecut.pro.media.loader.MediaImportProgress
import com.vibecut.pro.media.loader.MediaSupportManager
import com.vibecut.pro.media.sample.DemoClipGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceGalleryItem(
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val mediaKind: MediaKind,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val mimeType: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val demoClipGenerator: DemoClipGenerator,
    private val mediaSupportManager: MediaSupportManager,
    private val exportEngine: VibeCutExportEngine
) : ViewModel() {

    val projects: StateFlow<List<ProjectSummary>> = projectRepository.projectSummariesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val preferences: StateFlow<StudioPreferences> = preferencesRepository.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudioPreferences())

    private val _galleryItems = MutableStateFlow<List<DeviceGalleryItem>>(emptyList())
    val galleryItems: StateFlow<List<DeviceGalleryItem>> = _galleryItems.asStateFlow()

    val importProgress: StateFlow<MediaImportProgress> = mediaSupportManager.importProgress

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    val hardwareEncoders: List<String> by lazy { exportEngine.listDeviceVideoEncoders() }
    val isHevcSupported: Boolean by lazy { exportEngine.isHevcHardwareSupported() }

    init {
        refreshCacheSize()
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun completeOnboarding() {
        viewModelScope.launch { preferencesRepository.setOnboardingCompleted(true) }
    }

    fun createEmptyProject(
        title: String,
        aspectRatio: AspectRatioPreset,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            val p = projectRepository.createNewProject(title.ifBlank { "Untitled Edit" }, aspectRatio)
            onCreated(p.id)
        }
    }

    fun createProjectWithUris(
        title: String,
        aspectRatio: AspectRatioPreset,
        uris: List<Uri>,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (uris.isEmpty()) {
                createEmptyProject(title, aspectRatio, onCreated)
                return@launch
            }
            _isBusy.value = true
            try {
                val batch = mediaSupportManager.importMediaBatch(uris)
                if (batch.imported.isEmpty()) {
                    _statusBanner.value = batch.failures.firstOrNull() ?: "Could not load selected media"
                    return@launch
                }
                var project = projectRepository.createNewProject(title.ifBlank { "Untitled Edit" }, aspectRatio)
                val visualSources = batch.imported.filter { it.mediaKind != MediaKind.AUDIO }
                val audioSources = batch.imported.filter { it.mediaKind == MediaKind.AUDIO }

                if (visualSources.isNotEmpty()) {
                    project = TimelineEngine.importMediaClips(project, visualSources, TrackType.VIDEO_MAIN)
                } else {
                    val dims = AspectRatioPreset.dimensionsForProject(aspectRatio, 720, aspectRatio.ratioFloat)
                    val canvasClip = demoClipGenerator.generateStudioCanvasClip(
                        title = "Audio Backdrop",
                        width = dims.first,
                        height = dims.second,
                        durationMs = audioSources.sumOf { it.durationMs }.coerceIn(4_000L, 15_000L)
                    )
                    project = TimelineEngine.importMediaClips(project, listOf(canvasClip), TrackType.VIDEO_MAIN)
                }
                if (audioSources.isNotEmpty()) {
                    project = TimelineEngine.importMediaClips(project, audioSources, TrackType.AUDIO_MUSIC)
                }
                projectRepository.saveProject(project, "Imported ${batch.imported.size} media item(s)")
                if (batch.failures.isNotEmpty()) {
                    _statusBanner.value = "Imported ${batch.imported.size}; skipped ${batch.failures.size}"
                }
                onCreated(project.id)
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun createStudioCanvasProject(
        title: String,
        aspectRatio: AspectRatioPreset,
        primaryArgb: Int = 0xFF141828.toInt(),
        secondaryArgb: Int = 0xFF00B8D4.toInt(),
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val dims = AspectRatioPreset.dimensionsForProject(aspectRatio, 720, aspectRatio.ratioFloat)
                val canvasClip = demoClipGenerator.generateStudioCanvasClip(
                    title = title.ifBlank { "Studio Canvas" },
                    primaryColorArgb = primaryArgb,
                    secondaryColorArgb = secondaryArgb,
                    width = dims.first,
                    height = dims.second,
                    durationMs = 5_000L
                )
                var project = projectRepository.createNewProject(title.ifBlank { "Canvas Edit" }, aspectRatio)
                project = TimelineEngine.importMediaClips(project, listOf(canvasClip), TrackType.VIDEO_MAIN)
                projectRepository.saveProject(project, "Created Studio Canvas")
                onCreated(project.id)
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun createDemoProject(
        aspectRatio: AspectRatioPreset,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val demoClips = demoClipGenerator.generateSampleVideoClips()
                var project = projectRepository.createNewProject("VibeCut Demo Reel", aspectRatio)
                project = TimelineEngine.importMediaClips(project, demoClips, TrackType.VIDEO_MAIN)
                projectRepository.saveProject(project, "Generated ${demoClips.size} sample clips")
                onCreated(project.id)
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun renameProject(projectId: String, newTitle: String) {
        viewModelScope.launch { projectRepository.renameProject(projectId, newTitle) }
    }

    fun duplicateProject(projectId: String) {
        viewModelScope.launch {
            val dup = projectRepository.duplicateProject(projectId)
            if (dup != null) _statusBanner.value = "Duplicated '${dup.title}'"
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch { projectRepository.deleteProject(projectId) }
    }

    fun observeRevisions(projectId: String): Flow<List<ProjectRevisionEntity>> =
        projectRepository.observeProjectRevisions(projectId)

    fun restoreRevision(revision: ProjectRevisionEntity, onRestored: (String) -> Unit) {
        viewModelScope.launch {
            val restored = projectRepository.restoreRevision(revision)
            if (restored != null) onRestored(restored.id)
        }
    }

    fun loadDeviceGalleryMedia() {
        viewModelScope.launch {
            val entries = mediaSupportManager.scanDeviceMediaLibrary(maxPerCategory = 50)
            _galleryItems.value = entries.map { e ->
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
        }
    }

    fun updateAutosaveInterval(sec: Int) {
        viewModelScope.launch { preferencesRepository.setAutosaveIntervalSec(sec) }
    }

    fun updateLowEndProxyMode(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setLowEndProxyPreview(enabled) }
    }

    fun updateDefaultExport(resName: String, fpsName: String) {
        viewModelScope.launch { preferencesRepository.setDefaultExport(resName, fpsName) }
    }

    fun refreshCacheSize() {
        viewModelScope.launch { _cacheSizeBytes.value = projectRepository.computeCacheBytes() }
    }

    fun clearCache() {
        viewModelScope.launch {
            projectRepository.clearStudioCache()
            _cacheSizeBytes.value = projectRepository.computeCacheBytes()
            _statusBanner.value = "Studio cache cleared"
        }
    }

    companion object {
        suspend fun inspectMediaUri(
            context: Context,
            uri: Uri,
            forceAudio: Boolean = false
        ): ImportedMediaSource {
            val mgr = MediaSupportManager(context)
            return mgr.importSingleUri(uri, forceAudio = forceAudio).getOrElse {
                ImportedMediaSource(
                    uri = uri.toString(),
                    displayName = uri.lastPathSegment ?: "Media",
                    mediaKind = if (forceAudio) MediaKind.AUDIO else MediaKind.VIDEO,
                    durationMs = 4_000L
                )
            }
        }
    }
}

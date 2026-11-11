package com.vibecut.pro.ui.viewmodel

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import com.vibecut.pro.engine.templates.ProjectTemplate
import com.vibecut.pro.engine.templates.TemplateCatalog
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import com.vibecut.pro.engine.timeline.TimelineEngine
import com.vibecut.pro.media.export.VibeCutExportEngine
import com.vibecut.pro.media.sample.DemoClipGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DeviceGalleryItem(
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val mediaKind: MediaKind
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val demoClipGenerator: DemoClipGenerator,
    private val exportEngine: VibeCutExportEngine
) : ViewModel() {

    val projects: StateFlow<List<ProjectSummary>> = projectRepository.projectSummariesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val templates: StateFlow<List<ProjectTemplate>> = projectRepository.allTemplatesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TemplateCatalog.builtInTemplates)

    val preferences: StateFlow<StudioPreferences> = preferencesRepository.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudioPreferences())

    private val _galleryItems = MutableStateFlow<List<DeviceGalleryItem>>(emptyList())
    val galleryItems: StateFlow<List<DeviceGalleryItem>> = _galleryItems.asStateFlow()

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
        viewModelScope.launch {
            preferencesRepository.setOnboardingCompleted(true)
        }
    }

    fun createEmptyProject(
        title: String,
        aspectRatio: AspectRatioPreset,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            val p = projectRepository.createNewProject(title, aspectRatio)
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
            _isBusy.value = true
            try {
                var project = projectRepository.createNewProject(title, aspectRatio)
                val sources = uris.map { inspectMediaUri(context, it) }
                project = TimelineEngine.importMediaClips(project, sources, TrackType.VIDEO_MAIN)
                projectRepository.saveProject(project, "Imported ${sources.size} media item(s)")
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
                projectRepository.saveProject(project, "Generated 3 H.264 sample clips")
                onCreated(project.id)
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun applyMarketplaceTemplate(
        template: ProjectTemplate,
        selectedUris: List<Uri>,
        headline: String,
        subtitle: String,
        musicUri: Uri?,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val sources = if (selectedUris.isNotEmpty()) {
                    selectedUris.map { inspectMediaUri(context, it) }
                } else {
                    demoClipGenerator.generateSampleVideoClips()
                }
                val musicSource = musicUri?.let { inspectMediaUri(context, it, forceAudio = true) }
                val project = TemplateCatalog.applyTemplate(
                    template = template,
                    sources = sources,
                    customHeadline = headline,
                    customSubtitle = subtitle,
                    musicSource = musicSource
                )
                projectRepository.saveProject(project, "Applied template '${template.name}'")
                onCreated(project.id)
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun renameProject(projectId: String, newTitle: String) {
        viewModelScope.launch {
            projectRepository.renameProject(projectId, newTitle)
        }
    }

    fun duplicateProject(projectId: String) {
        viewModelScope.launch {
            val dup = projectRepository.duplicateProject(projectId)
            if (dup != null) _statusBanner.value = "Duplicated '${dup.title}'"
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            projectRepository.deleteProject(projectId)
        }
    }

    fun deleteSavedTemplate(templateId: String) {
        viewModelScope.launch {
            projectRepository.deleteCustomTemplate(templateId)
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            val found = mutableListOf<DeviceGalleryItem>()
            runCatching {
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION
                )
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    var count = 0
                    while (cursor.moveToNext() && count < 60) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Video_$id"
                        val dur = cursor.getLong(durCol).coerceAtLeast(1_000L)
                        val contentUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                        found.add(DeviceGalleryItem(contentUri.toString(), name, dur, MediaKind.VIDEO))
                        count++
                    }
                }
            }
            _galleryItems.value = found
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

    fun updateExternalAiSettings(endpoint: String, apiKey: String) {
        viewModelScope.launch {
            preferencesRepository.setExternalAiConfig(endpoint, apiKey)
            _statusBanner.value = "Saved AI Integration settings"
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSizeBytes.value = projectRepository.computeCacheBytes()
        }
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
        ): ImportedMediaSource = withContext(Dispatchers.IO) {
            var displayName = uri.lastPathSegment ?: "Media"
            val mimeType = context.contentResolver.getType(uri).orEmpty()

            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            displayName = c.getString(0) ?: displayName
                        }
                    }
            }

            val kind = when {
                forceAudio || mimeType.startsWith("audio/") -> MediaKind.AUDIO
                mimeType.startsWith("image/") -> MediaKind.IMAGE
                else -> MediaKind.VIDEO
            }

            var durationMs = if (kind == MediaKind.IMAGE) 3_500L else 5_000L
            var width = 1080
            var height = 1920

            if (kind != MediaKind.IMAGE) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (uri.scheme == "file") {
                        retriever.setDataSource(uri.path)
                    } else {
                        retriever.setDataSource(context, uri)
                    }
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.coerceAtLeast(300L) ?: 5_000L
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 1080
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 1920
                } catch (_: Exception) {
                } finally {
                    runCatching { retriever.release() }
                }
            }

            ImportedMediaSource(
                uri = uri.toString(),
                displayName = displayName,
                mediaKind = kind,
                durationMs = durationMs,
                width = width,
                height = height
            )
        }
    }
}

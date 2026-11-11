package com.vibecut.pro.data.repository

import android.content.Context
import com.vibecut.pro.data.local.ProjectEntity
import com.vibecut.pro.data.local.ProjectRevisionEntity
import com.vibecut.pro.data.local.SavedTemplateEntity
import com.vibecut.pro.data.local.VibeCutDao
import com.vibecut.pro.engine.model.AspectRatioPreset
import com.vibecut.pro.engine.model.Project
import com.vibecut.pro.engine.model.TrackType
import com.vibecut.pro.engine.serialization.ProjectJsonCodec
import com.vibecut.pro.engine.templates.ProjectTemplate
import com.vibecut.pro.engine.templates.TemplateCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ProjectSummary(
    val id: String,
    val title: String,
    val aspectRatio: AspectRatioPreset,
    val durationMs: Long,
    val clipCount: Int,
    val thumbnailUri: String?,
    val updatedAtMs: Long,
    val revision: Int
)

@Singleton
class ProjectRepository @Inject constructor(
    private val dao: VibeCutDao,
    @ApplicationContext private val context: Context
) {
    val projectSummariesFlow: Flow<List<ProjectSummary>> = dao.observeAllProjects().map { list ->
        list.map { entity ->
            ProjectSummary(
                id = entity.id,
                title = entity.title,
                aspectRatio = AspectRatioPreset.fromId(entity.aspectRatioId),
                durationMs = entity.durationMs,
                clipCount = entity.clipCount,
                thumbnailUri = entity.thumbnailUri,
                updatedAtMs = entity.updatedAtMs,
                revision = entity.revision
            )
        }
    }

    val allTemplatesFlow: Flow<List<ProjectTemplate>> = dao.observeSavedTemplates().map { saved ->
        val userTemplates = saved.mapNotNull {
            runCatching { ProjectJsonCodec.decodeTemplate(it.templateJson) }.getOrNull()
        }
        userTemplates + TemplateCatalog.builtInTemplates
    }

    suspend fun createNewProject(
        title: String,
        aspectRatio: AspectRatioPreset
    ): Project = withContext(Dispatchers.IO) {
        val project = Project(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "Untitled Edit" },
            aspectRatio = aspectRatio
        )
        saveProject(project, recordRevisionSummary = "Created project")
        project
    }

    suspend fun loadProject(projectId: String): Project? = withContext(Dispatchers.IO) {
        val entity = dao.getProjectById(projectId) ?: return@withContext null
        runCatching { ProjectJsonCodec.decodeProject(entity.projectJson) }.getOrNull()
    }

    suspend fun saveProject(
        project: Project,
        recordRevisionSummary: String? = null
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val mainClips = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN }?.clips.orEmpty()
        val thumb = project.thumbnailUri ?: mainClips.firstOrNull()?.sourceUri
        val stamped = project.copy(updatedAtMs = now, thumbnailUri = thumb)
        val encoded = ProjectJsonCodec.encodeProject(stamped)
        val totalClips = stamped.tracks.sumOf { it.clips.size }

        dao.upsertProject(
            ProjectEntity(
                id = stamped.id,
                title = stamped.title,
                aspectRatioId = stamped.aspectRatio.id,
                durationMs = stamped.totalDurationMs,
                clipCount = totalClips,
                thumbnailUri = thumb,
                projectJson = encoded,
                createdAtMs = stamped.createdAtMs,
                updatedAtMs = now,
                revision = stamped.revision
            )
        )

        if (recordRevisionSummary != null) {
            dao.insertRevision(
                ProjectRevisionEntity(
                    projectId = stamped.id,
                    revisionNumber = stamped.revision,
                    actionSummary = recordRevisionSummary,
                    projectJson = encoded,
                    savedAtMs = now
                )
            )
            dao.pruneOldRevisions(stamped.id)
        }
    }

    suspend fun renameProject(projectId: String, newTitle: String) = withContext(Dispatchers.IO) {
        val trimmed = newTitle.trim().ifBlank { "Untitled Edit" }
        val loaded = loadProject(projectId)
        if (loaded != null) {
            saveProject(loaded.copy(title = trimmed), recordRevisionSummary = "Renamed to '$trimmed'")
        } else {
            dao.renameProject(projectId, trimmed, System.currentTimeMillis())
        }
    }

    suspend fun duplicateProject(projectId: String): Project? = withContext(Dispatchers.IO) {
        val original = loadProject(projectId) ?: return@withContext null
        val now = System.currentTimeMillis()
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            title = "${original.title} (Copy)",
            createdAtMs = now,
            updatedAtMs = now,
            revision = 1
        )
        saveProject(copy, recordRevisionSummary = "Duplicated from '${original.title}'")
        copy
    }

    suspend fun deleteProject(projectId: String) = withContext(Dispatchers.IO) {
        dao.deleteProject(projectId)
    }

    fun observeProjectRevisions(projectId: String): Flow<List<ProjectRevisionEntity>> =
        dao.observeRevisionsForProject(projectId)

    suspend fun restoreRevision(revision: ProjectRevisionEntity): Project? = withContext(Dispatchers.IO) {
        val decoded = runCatching { ProjectJsonCodec.decodeProject(revision.projectJson) }.getOrNull()
            ?: return@withContext null
        val restored = decoded.copy(
            updatedAtMs = System.currentTimeMillis(),
            revision = decoded.revision + 1
        )
        saveProject(restored, recordRevisionSummary = "Restored rev #${revision.revisionNumber}")
        restored
    }

    suspend fun saveCustomTemplate(template: ProjectTemplate) = withContext(Dispatchers.IO) {
        val json = ProjectJsonCodec.encodeTemplate(template)
        dao.upsertTemplate(
            SavedTemplateEntity(
                id = template.id,
                name = template.name,
                categoryName = template.category.name,
                templateJson = json,
                createdAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteCustomTemplate(templateId: String) = withContext(Dispatchers.IO) {
        dao.deleteTemplate(templateId)
    }

    suspend fun computeCacheBytes(): Long = withContext(Dispatchers.IO) {
        fun folderSize(dir: File?): Long {
            if (dir == null || !dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        folderSize(context.cacheDir) + folderSize(File(context.filesDir, "sfx_cache"))
    }

    suspend fun clearStudioCache() = withContext(Dispatchers.IO) {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}

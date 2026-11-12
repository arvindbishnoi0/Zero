package com.vibecut.pro.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VibeCutDao {

    @Query("SELECT * FROM projects ORDER BY updatedAtMs DESC")
    fun observeAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :projectId LIMIT 1")
    suspend fun getProjectById(projectId: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(entity: ProjectEntity)

    @Query("UPDATE projects SET title = :newTitle, updatedAtMs = :updatedAtMs WHERE id = :projectId")
    suspend fun renameProject(projectId: String, newTitle: String, updatedAtMs: Long)

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRevision(revision: ProjectRevisionEntity)

    @Query("SELECT * FROM project_revisions WHERE projectId = :projectId ORDER BY savedAtMs DESC LIMIT 25")
    fun observeRevisionsForProject(projectId: String): Flow<List<ProjectRevisionEntity>>

    @Query("DELETE FROM project_revisions WHERE projectId = :projectId AND revisionId NOT IN (SELECT revisionId FROM project_revisions WHERE projectId = :projectId ORDER BY savedAtMs DESC LIMIT 25)")
    suspend fun pruneOldRevisions(projectId: String)
}

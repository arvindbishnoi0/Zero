package com.vibecut.pro.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val aspectRatioId: String,
    val durationMs: Long,
    val clipCount: Int,
    val thumbnailUri: String?,
    val projectJson: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val revision: Int
)

@Entity(
    tableName = "project_revisions",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class ProjectRevisionEntity(
    @PrimaryKey(autoGenerate = true) val revisionId: Long = 0L,
    val projectId: String,
    val revisionNumber: Int,
    val actionSummary: String,
    val projectJson: String,
    val savedAtMs: Long
)

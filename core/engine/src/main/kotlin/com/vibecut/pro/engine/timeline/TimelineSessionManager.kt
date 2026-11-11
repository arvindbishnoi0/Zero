package com.vibecut.pro.engine.timeline

import com.vibecut.pro.engine.model.Project

data class HistoryEntry(
    val actionLabel: String,
    val snapshot: Project
)

/**
 * Manages immutable Project state transitions with bounded Undo / Redo stacks
 * and dirty-state tracking for automatic background persistence.
 */
class TimelineSessionManager(
    initialProject: Project = Project(),
    private val maxHistoryDepth: Int = 100
) {
    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()

    var currentProject: Project = initialProject
        private set

    var lastSavedRevision: Int = initialProject.revision
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size
    val redoDepth: Int get() = redoStack.size
    val lastUndoLabel: String? get() = undoStack.lastOrNull()?.actionLabel
    val lastRedoLabel: String? get() = redoStack.lastOrNull()?.actionLabel
    val hasUnsavedChanges: Boolean get() = currentProject.revision != lastSavedRevision

    fun reset(project: Project) {
        undoStack.clear()
        redoStack.clear()
        currentProject = project
        lastSavedRevision = project.revision
    }

    fun execute(actionLabel: String, transform: (Project) -> Project): Project {
        val before = currentProject
        val after = transform(before)
        if (after === before || after == before) {
            return currentProject
        }
        undoStack.addLast(HistoryEntry(actionLabel = actionLabel, snapshot = before))
        while (undoStack.size > maxHistoryDepth) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        currentProject = after
        return currentProject
    }

    fun undo(): Project? {
        if (undoStack.isEmpty()) return null
        val entry = undoStack.removeLast()
        redoStack.addLast(HistoryEntry(actionLabel = entry.actionLabel, snapshot = currentProject))
        currentProject = entry.snapshot
        return currentProject
    }

    fun redo(): Project? {
        if (redoStack.isEmpty()) return null
        val entry = redoStack.removeLast()
        undoStack.addLast(HistoryEntry(actionLabel = entry.actionLabel, snapshot = currentProject))
        currentProject = entry.snapshot
        return currentProject
    }

    fun markSaved() {
        lastSavedRevision = currentProject.revision
    }
}

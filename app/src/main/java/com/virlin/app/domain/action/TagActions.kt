package com.virlin.app.domain.action

import com.virlin.app.domain.model.ClearedSteps
import com.virlin.app.domain.model.ProjectTag
import com.virlin.app.domain.model.TagLink
import com.virlin.app.domain.model.TagTargetType
import com.virlin.app.domain.model.TaskStep
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.time.VirlinClock

/**
 * TAGS AND TASK STEPS.
 *
 * Tags label; they never own. Deleting one takes the label off, not the thing it labelled, and
 * renaming one keeps its id so every existing link survives. Every id a caller passes is checked
 * against the project it claims to belong to, so one project's vocabulary can never reach
 * another's work.
 *
 * Steps are the checkboxes inside a task. They are not tasks: no attention state, no execution
 * mode, and no effect on progress. Ticking them all does not complete the task, because that is
 * the Action Layer's decision and Virlin has no rule saying otherwise.
 */
class TagActions(
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
    private val ids: IdProvider
) {

    // ------------------------------------------------------------------ tags

    suspend fun createTag(projectId: String, name: String): ActionResult<ProjectTag> = tx {
        getProject(projectId) ?: return@tx ActionResult.Rejected(DomainError.ProjectNotFound(projectId))
        val clean = name.trim()
        if (clean.isEmpty()) return@tx ActionResult.Rejected(DomainError.EmptyTitle)
        // Case-insensitively unique within the project: "#Release" and "#release" are one tag.
        allTags().firstOrNull { it.projectId == projectId && it.key == clean.lowercase() }
            ?.let { return@tx ActionResult.Success(it) }
        val now = clock.now()
        val tag = ProjectTag(ids.newId("tag"), projectId, clean, now, now)
        saveTag(tag)
        ActionResult.Success(tag)
    }

    /** The id survives: every item already carrying this tag keeps carrying it. */
    suspend fun renameTag(tagId: String, name: String): ActionResult<ProjectTag> = tx {
        val tag = getTag(tagId) ?: return@tx ActionResult.NotFound(tagId)
        val clean = name.trim()
        if (clean.isEmpty()) return@tx ActionResult.Rejected(DomainError.EmptyTitle)
        val clash = allTags().firstOrNull {
            it.projectId == tag.projectId && it.key == clean.lowercase() && it.id != tag.id
        }
        if (clash != null) return@tx ActionResult.Rejected(DomainError.DuplicateTagName(clean))
        val renamed = tag.copy(name = clean, updatedAt = clock.now())
        saveTag(renamed)
        ActionResult.Success(renamed)
    }

    /**
     * MERGE: everything labelled [fromTagId] becomes labelled [intoTagId] and the old tag goes.
     * One transaction, so no item is ever left holding a tag that no longer exists.
     */
    suspend fun mergeTags(fromTagId: String, intoTagId: String): ActionResult<ProjectTag> = tx {
        if (fromTagId == intoTagId) return@tx ActionResult.Rejected(DomainError.OwnershipMismatch)
        val from = getTag(fromTagId) ?: return@tx ActionResult.NotFound(fromTagId)
        val into = getTag(intoTagId) ?: return@tx ActionResult.NotFound(intoTagId)
        if (from.projectId != into.projectId) return@tx ActionResult.Rejected(DomainError.OwnershipMismatch)
        reassignLinks(fromTagId, intoTagId)
        deleteTag(fromTagId)
        ActionResult.Success(into)
    }

    /** Removes the label. Nothing that carried it is deleted. */
    suspend fun deleteTag(tagId: String): ActionResult<String> = tx {
        getTag(tagId) ?: return@tx ActionResult.NotFound(tagId)
        deleteTag(tagId)
        ActionResult.Success(tagId)
    }

    /**
     * Apply [tagIds] to captures and tasks, and remove the project's other tags from them, in ONE
     * transaction. Every id must belong to [projectId]: a tag, capture or task from elsewhere
     * rejects the whole call rather than being quietly skipped.
     */
    suspend fun setTags(
        projectId: String,
        tagIds: Set<String>,
        captureIds: Set<String>,
        taskIds: Set<String>
    ): ActionResult<Int> = tx {
        getProject(projectId) ?: return@tx ActionResult.Rejected(DomainError.ProjectNotFound(projectId))
        val projectTags = allTags().filter { it.projectId == projectId }
        val chosen = tagIds.map { id ->
            projectTags.firstOrNull { it.id == id }
                ?: return@tx ActionResult.Rejected(DomainError.OwnershipMismatch)
        }
        val now = clock.now()
        var written = 0

        for (captureId in captureIds) {
            val capture = getCapture(captureId) ?: return@tx ActionResult.NotFound(captureId)
            if (capture.projectId != projectId) return@tx ActionResult.Rejected(DomainError.OwnershipMismatch)
            written += applyTo(TagTargetType.CAPTURE, captureId, chosen.map { it.id }.toSet(), projectTags, now)
        }
        for (taskId in taskIds) {
            val task = getTask(taskId) ?: return@tx ActionResult.Rejected(DomainError.TaskNotFound(taskId))
            if (task.projectId != projectId) return@tx ActionResult.Rejected(DomainError.OwnershipMismatch)
            written += applyTo(TagTargetType.TASK, taskId, chosen.map { it.id }.toSet(), projectTags, now)
        }
        ActionResult.Success(written)
    }

    private suspend fun WorkStreamWriter.applyTo(
        type: TagTargetType,
        targetId: String,
        wanted: Set<String>,
        projectTags: List<ProjectTag>,
        now: java.time.Instant
    ): Int {
        val existing = linksOf(type, targetId)
        // Only this project's tags are touched; a link from elsewhere is left exactly as it is.
        val scoped = existing.filter { link -> projectTags.any { it.id == link.tagId } }
        scoped.filterNot { it.tagId in wanted }.forEach { deleteLink(it.tagId, type, targetId) }
        var added = 0
        wanted.filterNot { id -> existing.any { it.tagId == id } }.forEach { id ->
            saveLink(TagLink(id, type, targetId, now)); added++
        }
        return added
    }

    // ------------------------------------------------------------------ task steps

    suspend fun addStep(taskId: String, text: String): ActionResult<TaskStep> = tx {
        getTask(taskId) ?: return@tx ActionResult.Rejected(DomainError.TaskNotFound(taskId))
        val clean = text.trim()
        if (clean.isEmpty()) return@tx ActionResult.Rejected(DomainError.EmptyTitle)
        val now = clock.now()
        val order = (stepsOf(taskId).maxOfOrNull { it.order } ?: -1) + 1
        val step = TaskStep(ids.newId("step"), taskId, clean, false, order, now, now)
        saveStep(step)
        ActionResult.Success(step)
    }

    /** Toggling a step changes the step. It never completes or reopens the task. */
    suspend fun setStepDone(stepId: String, done: Boolean): ActionResult<TaskStep> = tx {
        val step = stepsOfAll().firstOrNull { it.id == stepId } ?: return@tx ActionResult.NotFound(stepId)
        val updated = step.copy(done = done, updatedAt = clock.now())
        saveStep(updated)
        ActionResult.Success(updated)
    }

    suspend fun editStep(stepId: String, text: String): ActionResult<TaskStep> = tx {
        val step = stepsOfAll().firstOrNull { it.id == stepId } ?: return@tx ActionResult.NotFound(stepId)
        val clean = text.trim()
        if (clean.isEmpty()) return@tx ActionResult.Rejected(DomainError.EmptyTitle)
        val updated = step.copy(text = clean, updatedAt = clock.now())
        saveStep(updated)
        ActionResult.Success(updated)
    }

    suspend fun deleteStep(stepId: String): ActionResult<String> = tx {
        stepsOfAll().firstOrNull { it.id == stepId } ?: return@tx ActionResult.NotFound(stepId)
        deleteStep(stepId)
        ActionResult.Success(stepId)
    }

    /** Reorder within one task: the whole list is renumbered so the order is never ambiguous. */
    suspend fun moveStep(stepId: String, newIndex: Int): ActionResult<List<TaskStep>> = tx {
        val step = stepsOfAll().firstOrNull { it.id == stepId } ?: return@tx ActionResult.NotFound(stepId)
        val siblings = stepsOf(step.taskId).toMutableList()
        val from = siblings.indexOfFirst { it.id == stepId }
        if (from < 0) return@tx ActionResult.NotFound(stepId)
        val to = newIndex.coerceIn(0, siblings.lastIndex)
        if (from == to) return@tx ActionResult.Success(siblings)
        siblings.add(to, siblings.removeAt(from))
        val now = clock.now()
        val renumbered = siblings.mapIndexed { index, s -> s.copy(order = index, updatedAt = now) }
        renumbered.forEach { saveStep(it) }
        ActionResult.Success(renumbered)
    }

    /**
     * Reorder by a STABLE ANCHOR rather than an index: put [stepId] immediately in front of
     * [beforeStepId], or at the end when that is null.
     *
     * An index computed in the UI means whatever the list looked like when the finger went down;
     * by the time the drop commits another edit may have shifted it. Resolving the anchor to a
     * position INSIDE this transaction means the step lands next to the row the user actually
     * pointed at, or the call is refused because that row is gone.
     */
    suspend fun moveStepBefore(stepId: String, beforeStepId: String?): ActionResult<List<TaskStep>> = tx {
        val step = stepsOfAll().firstOrNull { it.id == stepId } ?: return@tx ActionResult.NotFound(stepId)
        if (beforeStepId == stepId) return@tx ActionResult.Rejected(DomainError.EmptyTitle)
        val siblings = stepsOf(step.taskId)
        val without = siblings.filter { it.id != stepId }
        val at = when (beforeStepId) {
            null -> without.size
            else -> without.indexOfFirst { it.id == beforeStepId }
                .takeIf { it >= 0 } ?: return@tx ActionResult.NotFound(beforeStepId)
        }
        val ordered = ArrayList<TaskStep>(siblings.size)
        ordered.addAll(without.take(at))
        ordered.add(step)
        ordered.addAll(without.drop(at))
        // Already exactly there: a no-op writes nothing at all. This compares the RESULTING
        // arrangement, because `at` indexes the list with the source removed while the step's
        // current position indexes the list with it still in - comparing those two directly
        // makes every downward move look like a no-op.
        if (ordered.map { it.id } == siblings.map { it.id }) return@tx ActionResult.Success(siblings)
        val now = clock.now()
        val renumbered = ordered.mapIndexed { index, s -> s.copy(order = index, updatedAt = now) }
        renumbered.forEach { if (it.order != siblings.first { s -> s.id == it.id }.order || it.id == stepId) saveStep(it) }
        ActionResult.Success(renumbered)
    }

    /**
     * Remove every completed step of one task in ONE transaction and hand back exactly what was
     * removed, so the caller can offer a truthful Undo. The survivors are renumbered contiguously.
     */
    suspend fun clearCompletedSteps(taskId: String): ActionResult<ClearedSteps> = tx {
        getTask(taskId) ?: return@tx ActionResult.Rejected(DomainError.TaskNotFound(taskId))
        val siblings = stepsOf(taskId)
        val removed = siblings.filter { it.done }
        if (removed.isEmpty()) return@tx ActionResult.Success(ClearedSteps(taskId, emptyList(), siblings.map { it.id }))
        removed.forEach { deleteStep(it.id) }
        val now = clock.now()
        val survivors = siblings.filterNot { it.done }
        survivors.forEachIndexed { index, s -> if (s.order != index) saveStep(s.copy(order = index, updatedAt = now)) }
        ActionResult.Success(ClearedSteps(taskId, removed, survivors.map { it.id }))
    }

    /**
     * Put cleared steps back exactly as they were - same ids, text, done flags and positions.
     *
     * It refuses rather than approximating: if any of those ids exists again, or if the surviving
     * rows are no longer the ones that survived the clear, the list has moved on and a faithful
     * restoration is impossible. An Undo that quietly produced a DIFFERENT list would be worse
     * than one that declines.
     */
    suspend fun restoreSteps(cleared: ClearedSteps): ActionResult<List<TaskStep>> = tx {
        if (cleared.removed.isEmpty()) return@tx ActionResult.Success(stepsOf(cleared.taskId))
        val current = stepsOf(cleared.taskId)
        if (current.map { it.id } != cleared.survivorIds) {
            return@tx ActionResult.Rejected(
                DomainError.PlacementRejected("This list changed after the clear, so it cannot be put back exactly.")
            )
        }
        val now = clock.now()
        // Rebuild the pre-clear arrangement from the stored order values of both sets.
        val merged = (current + cleared.removed).sortedWith(compareBy({ it.order }, { it.id }))
        merged.forEachIndexed { index, s -> saveStep(s.copy(order = index, updatedAt = now)) }
        ActionResult.Success(stepsOf(cleared.taskId))
    }

    /** Every step in the store — the writer only exposes per-task reads. */
    private suspend fun WorkStreamWriter.stepsOfAll(): List<TaskStep> = repository.taskSteps.value

    private suspend fun <T> tx(block: suspend WorkStreamWriter.() -> ActionResult<T>): ActionResult<T> =
        try { repository.transaction(block) } catch (e: Exception) { ActionResult.Failure(e) }
}

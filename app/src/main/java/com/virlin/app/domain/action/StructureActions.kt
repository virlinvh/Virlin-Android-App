package com.virlin.app.domain.action

import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.EffectiveExecutionMode
import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.ExecutionModeResolver
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.ProjectStatus
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskHierarchy
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamEvent
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.domain.time.VirlinClock
import java.time.Duration
import java.time.Instant

/**
 * Project / Task / active-task operations. Composed INTO [DefaultVirlinActions] so
 * [VirlinActions] stays the single product-facing facade. Same shape as the attention
 * actions: one transaction, validate against a consistent staged view, save, event.
 */
internal class StructureActions(
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
    private val ids: IdProvider
) {

    // ------------------------------------------------------------------ Project

    suspend fun createProject(r: CreateProject): ActionResult<Project> = tx {
        if (r.title.isBlank()) return@tx ActionResult.Rejected(DomainError.EmptyTitle)
        r.estimatedEffort?.let { if (it.isNegative || it.isZero) return@tx ActionResult.Rejected(DomainError.InvalidEffort) }
        val now = clock.now()
        val p = Project(
            id = r.id ?: ids.newId("proj"), title = r.title.trim(), description = r.description,
            priority = r.priority, dueAt = r.dueAt, estimatedEffort = r.estimatedEffort,
            defaultExecutionMode = r.defaultExecutionMode,
            createdAt = now, updatedAt = now
        )
        saveProject(p)
        ActionResult.Success(p)
    }

    suspend fun updateProject(id: String, u: ProjectUpdate): ActionResult<Project> = tx {
        val p = getProject(id) ?: return@tx ActionResult.Rejected(DomainError.ProjectNotFound(id))
        if (p.status == ProjectStatus.DONE || p.status == ProjectStatus.ARCHIVED) return@tx ActionResult.Rejected(DomainError.ProjectAlreadyDone)
        val title = u.title.applyTo(p.title)?.trim()
        if (title.isNullOrBlank()) return@tx ActionResult.Rejected(DomainError.EmptyTitle)
        val nextDefault = u.defaultExecutionMode.applyTo(p.defaultExecutionMode) ?: p.defaultExecutionMode
        if (nextDefault != p.defaultExecutionMode) {
            rejectProjectDefaultIfProcessingBecomesHuman(p.id, nextDefault)?.let { return@tx it }
        }
        val updated = p.copy(
            title = title,
            description = u.description.applyTo(p.description),
            priority = u.priority.applyTo(p.priority) ?: p.priority,
            dueAt = u.dueAt.applyTo(p.dueAt),
            estimatedEffort = u.estimatedEffort.applyTo(p.estimatedEffort),
            defaultExecutionMode = nextDefault,
            iconPath = u.iconPath.applyTo(p.iconPath)?.takeIf { it.isNotBlank() },
            iconId = u.iconId.applyTo(p.iconId)?.takeIf { com.virlin.app.domain.model.ProjectIconCatalog.isKnown(it) },
            updatedAt = clock.now()
        )
        saveProject(updated)
        ActionResult.Success(updated)
    }

    suspend fun completeProject(id: String): ActionResult<Project> = tx {
        val p = getProject(id) ?: return@tx ActionResult.Rejected(DomainError.ProjectNotFound(id))
        if (p.status == ProjectStatus.DONE) return@tx ActionResult.Rejected(DomainError.ProjectAlreadyDone)
        val now = clock.now()
        val updated = p.copy(status = ProjectStatus.DONE, completedAt = now, updatedAt = now)
        saveProject(updated)
        ActionResult.Success(updated)
    }

    // ------------------------------------------------------------------ Task

    suspend fun createTask(r: CreateTask): ActionResult<Task> = tx { createTaskIn(this, r) }

    /** Writer-scoped so other actions (capture conversion) can create a Task inside THEIR transaction. */
    suspend fun createTaskIn(w: WorkStreamWriter, r: CreateTask): ActionResult<Task> = with(w) {
        if (r.title.isBlank()) return@with ActionResult.Rejected(DomainError.EmptyTitle)
        r.estimatedEffort?.let { if (it.isNegative || it.isZero) return@with ActionResult.Rejected(DomainError.InvalidEffort) }
        val all = allTasks()
        val now = clock.now()

        // Resolve ownership deterministically.
        val projectId: String?
        val workStreamId: String?
        when {
            r.parentTaskId != null -> {
                if (r.id != null && r.id == r.parentTaskId) return@with ActionResult.Rejected(DomainError.SelfParent)
                val parent = getTask(r.parentTaskId) ?: return@with ActionResult.Rejected(DomainError.TaskNotFound(r.parentTaskId))
                // A closed (done/cancelled) parent takes no new children (Pass 9): stale picker choices are rejected here.
                if (parent.status.isTerminal) return@with ActionResult.Rejected(DomainError.TaskAlreadyClosed)
                if (r.projectId != null && r.projectId != parent.projectId) return@with ActionResult.Rejected(DomainError.OwnershipMismatch)
                if (r.workStreamId != null && r.workStreamId != parent.workStreamId) return@with ActionResult.Rejected(DomainError.OwnershipMismatch)
                projectId = parent.projectId; workStreamId = parent.workStreamId
            }
            r.workStreamId != null -> {
                // Project is OPTIONAL: a projectless WorkStream owns tasks just fine.
                val ws = getStream(r.workStreamId) ?: return@with ActionResult.NotFound(r.workStreamId)
                if (r.projectId != null && r.projectId != ws.projectId) return@with ActionResult.Rejected(DomainError.OwnershipMismatch)
                projectId = ws.projectId; workStreamId = ws.id
            }
            else -> {
                val pid = r.projectId ?: return@with ActionResult.Rejected(DomainError.OwnershipMismatch)
                getProject(pid) ?: return@with ActionResult.Rejected(DomainError.ProjectNotFound(pid))
                projectId = pid; workStreamId = null
            }
        }
        val id = r.id ?: ids.newId("task")
        if (r.parentTaskId != null && TaskHierarchy.wouldCycle(all, id, r.parentTaskId)) return@with ActionResult.Rejected(DomainError.CyclicParent)

        val siblings = all.filter { it.parentTaskId == r.parentTaskId && it.workStreamId == workStreamId && it.projectId == projectId }
        val task = Task(
            id = id, title = r.title.trim(), description = r.description, projectId = projectId,
            workStreamId = workStreamId, parentTaskId = r.parentTaskId,
            order = r.order ?: (siblings.maxOfOrNull { it.order }?.plus(1) ?: 0),
            estimatedEffort = r.estimatedEffort, dueAt = r.dueAt, reminderAt = r.reminderAt,
            priority = r.priority, executionPreference = r.executionPreference,
            createdAt = now, updatedAt = now
        )
        saveTask(task)
        workStreamId?.let { streamEvent(it, EventType.TASK_CREATED, now, task.id) }
        ActionResult.Success(task)
    }

    suspend fun addSubtask(parentTaskId: String, title: String, estimatedEffort: Duration?): ActionResult<Task> =
        createTask(CreateTask(title = title, parentTaskId = parentTaskId, estimatedEffort = estimatedEffort))

    suspend fun updateTask(id: String, u: TaskUpdate): ActionResult<Task> = tx {
        val t = getTask(id) ?: return@tx ActionResult.Rejected(DomainError.TaskNotFound(id))
        if (t.status.isTerminal) return@tx ActionResult.Rejected(DomainError.TaskAlreadyClosed)
        val title = u.title.applyTo(t.title)?.trim()
        if (title.isNullOrBlank()) return@tx ActionResult.Rejected(DomainError.EmptyTitle)
        val effort = u.estimatedEffort.applyTo(t.estimatedEffort)
        if (effort != null && (effort.isNegative || effort.isZero)) return@tx ActionResult.Rejected(DomainError.InvalidEffort)
        val now = clock.now()
        val nextPref = u.executionPreference.applyTo(t.executionPreference) ?: t.executionPreference
        if (nextPref != t.executionPreference) {
            rejectProcessingHuman(t.workStreamId, proposedTask = t.copy(executionPreference = nextPref))
                ?.let { return@tx it }
        }
        val status = when (u.inProgress.applyTo(t.status == TaskStatus.IN_PROGRESS)) {
            true -> TaskStatus.IN_PROGRESS; false -> TaskStatus.TODO; null -> t.status
        }
        val updated = t.copy(
            title = title, description = u.description.applyTo(t.description), notes = u.notes.applyTo(t.notes),
            estimatedEffort = effort, dueAt = u.dueAt.applyTo(t.dueAt), reminderAt = u.reminderAt.applyTo(t.reminderAt),
            priority = u.priority.applyTo(t.priority) ?: t.priority, order = u.order.applyTo(t.order) ?: t.order,
            status = status,
            executionPreference = nextPref,
            updatedAt = now
        )
        saveTask(updated)
        t.workStreamId?.let { streamEvent(it, EventType.TASK_UPDATED, now, t.id) }
        ActionResult.Success(updated)
    }

    // ------------------------------------------------------------------ Execution preference

    suspend fun setProjectExecutionDefault(id: String, mode: EffectiveExecutionMode): ActionResult<Project> = tx {
        val p = getProject(id) ?: return@tx ActionResult.Rejected(DomainError.ProjectNotFound(id))
        if (p.status == ProjectStatus.DONE || p.status == ProjectStatus.ARCHIVED) return@tx ActionResult.Rejected(DomainError.ProjectAlreadyDone)
        if (p.defaultExecutionMode == mode) return@tx ActionResult.Success(p)
        rejectProjectDefaultIfProcessingBecomesHuman(id, mode)?.let { return@tx it }
        val updated = p.copy(defaultExecutionMode = mode, updatedAt = clock.now())
        saveProject(updated)
        ActionResult.Success(updated)
    }

    suspend fun setWorkStreamExecutionPreference(streamId: String, preference: ExecutionPreference): ActionResult<WorkStream> = tx {
        val ws = getStream(streamId) ?: return@tx ActionResult.NotFound(streamId)
        if (ws.state.isTerminal) return@tx ActionResult.Rejected(DomainError.StreamAlreadyDone)
        if (preference == ExecutionPreference.INHERIT && ws.projectId == null) {
            return@tx ActionResult.Rejected(DomainError.InheritRequiresProject)
        }
        if (ws.executionPreference == preference) return@tx ActionResult.Success(ws)
        val proposed = ws.copy(executionPreference = preference)
        rejectIfProcessingWouldBecomeHuman(proposed)?.let { return@tx it }
        val updated = proposed.copy(updatedAt = clock.now())
        saveStream(updated)
        ActionResult.Success(updated)
    }

    suspend fun resetWorkStreamExecutionPreference(streamId: String): ActionResult<WorkStream> =
        setWorkStreamExecutionPreference(streamId, ExecutionPreference.INHERIT)

    suspend fun setTaskExecutionPreference(taskId: String, preference: ExecutionPreference): ActionResult<Task> = tx {
        val t = getTask(taskId) ?: return@tx ActionResult.Rejected(DomainError.TaskNotFound(taskId))
        if (t.status.isTerminal) return@tx ActionResult.Rejected(DomainError.TaskAlreadyClosed)
        if (t.executionPreference == preference) return@tx ActionResult.Success(t)
        rejectProcessingHuman(t.workStreamId, proposedTask = t.copy(executionPreference = preference))
            ?.let { return@tx it }
        val updated = t.copy(executionPreference = preference, updatedAt = clock.now())
        saveTask(updated)
        t.workStreamId?.let { streamEvent(it, EventType.TASK_UPDATED, clock.now(), t.id) }
        ActionResult.Success(updated)
    }

    suspend fun resetTaskExecutionPreference(taskId: String): ActionResult<Task> =
        setTaskExecutionPreference(taskId, ExecutionPreference.INHERIT)

    /**
     * If [streamId] is PROCESSING and applying overlays would make resolveCurrent HUMAN, reject.
     */
    private suspend fun WorkStreamWriter.rejectProcessingHuman(
        streamId: String?,
        proposedTask: Task? = null,
        proposedStream: WorkStream? = null,
        proposedProjectDefault: EffectiveExecutionMode? = null
    ): ActionResult.Rejected? {
        val id = streamId ?: return null
        val ws = proposedStream ?: getStream(id) ?: return null
        if (ws.state != WorkStreamState.PROCESSING) return null
        val projectDefault = proposedProjectDefault
            ?: ws.projectId?.let { getProject(it)?.defaultExecutionMode }
        val tasks = allTasks().associateBy { it.id }.toMutableMap()
        proposedTask?.let { tasks[it.id] = it }
        val effective = ExecutionModeResolver.resolveCurrent(ws, tasks, projectDefault)
        return if (effective == EffectiveExecutionMode.HUMAN)
            ActionResult.Rejected(DomainError.CannotChangeExecutionWhileProcessing) else null
    }

    /**
     * Project default change must not leave any PROCESSING descendant resolving HUMAN via inheritance.
     * Explicit EXTERNAL overrides on WorkStream/Task still allow the parent change.
     */
    private suspend fun WorkStreamWriter.rejectProjectDefaultIfProcessingBecomesHuman(
        projectId: String,
        proposedDefault: EffectiveExecutionMode
    ): ActionResult.Rejected? {
        val tasks = allTasks().associateBy { it.id }
        for (ws in allStreams()) {
            if (ws.projectId != projectId || ws.state != WorkStreamState.PROCESSING) continue
            val effective = ExecutionModeResolver.resolveCurrent(ws, tasks, proposedDefault)
            if (effective == EffectiveExecutionMode.HUMAN) {
                return ActionResult.Rejected(DomainError.CannotChangeExecutionWhileProcessing)
            }
        }
        return null
    }

    private suspend fun WorkStreamWriter.rejectIfProcessingWouldBecomeHuman(proposed: WorkStream): ActionResult.Rejected? =
        rejectProcessingHuman(proposed.id, proposedStream = proposed)

    // ------------------------------------------------------------------ Active task

    suspend fun completeTask(id: String): ActionResult<Task> = close(id, TaskStatus.DONE, EventType.TASK_COMPLETED)

    /** CANCELLED: terminal, not completed, no completedAt. */
    suspend fun cancelTask(id: String): ActionResult<Task> = close(id, TaskStatus.CANCELLED, EventType.TASK_UPDATED)

    private suspend fun close(id: String, status: TaskStatus, eventType: EventType): ActionResult<Task> = tx {
        val t = getTask(id) ?: return@tx ActionResult.Rejected(DomainError.TaskNotFound(id))
        if (t.status.isTerminal) return@tx ActionResult.Rejected(DomainError.TaskAlreadyClosed)
        val now = clock.now()
        val closed = t.copy(status = status, completedAt = if (status.isCompleted) now else null, updatedAt = now)
        saveTask(closed)
        t.workStreamId?.let { wsId ->
            streamEvent(wsId, eventType, now, t.id)
            // Clear — never auto-advance. Callers use nextTaskCandidate() explicitly.
            val ws = getStream(wsId)
            if (ws != null && ws.activeTaskId == t.id) {
                // Commit the open FocusSession exactly once before clearing attribution so
                // COMPLETE while focusing preserves invested time (same as LEAVE).
                if (ws.state == WorkStreamState.FOCUS) {
                    getOpenFocusSession(wsId)?.let { open ->
                        if (open.isOpen) saveFocusSession(open.copy(endedAt = now))
                    }
                }
                saveStream(ws.copy(activeTaskId = null, updatedAt = now))
                streamEvent(wsId, EventType.ACTIVE_TASK_CLEARED, now, t.id)
            }
        }
        ActionResult.Success(closed)
    }

    // ------------------------------------------------------------------ Active task

    suspend fun setActiveTask(streamId: String, taskId: String?): ActionResult<WorkStream> = tx {
        val ws = getStream(streamId) ?: return@tx ActionResult.NotFound(streamId)
        if (ws.state.isTerminal) return@tx ActionResult.Rejected(DomainError.StreamAlreadyDone)
        val now = clock.now()
        if (taskId == null) {
            if (ws.activeTaskId == null) return@tx ActionResult.Success(ws)
            val cleared = ws.copy(activeTaskId = null, updatedAt = now)
            saveStream(cleared); streamEvent(streamId, EventType.ACTIVE_TASK_CLEARED, now, ws.activeTaskId)
            return@tx ActionResult.Success(cleared)
        }
        val t = getTask(taskId) ?: return@tx ActionResult.Rejected(DomainError.TaskNotFound(taskId))
        if (t.workStreamId != streamId) return@tx ActionResult.Rejected(DomainError.TaskNotInWorkStream)
        if (t.status.isTerminal) return@tx ActionResult.Rejected(DomainError.TaskAlreadyClosed)
        if (TaskHierarchy.ancestry(allTasks(), taskId) == null) return@tx ActionResult.Rejected(DomainError.CyclicParent)
        val updated = ws.copy(activeTaskId = taskId, updatedAt = now)
        saveStream(updated)
        streamEvent(streamId, EventType.ACTIVE_TASK_SET, now, taskId)
        ActionResult.Success(updated)
    }

    suspend fun activePath(streamId: String): ActionResult<List<Task>> {
        val ws = repository.getStream(streamId) ?: return ActionResult.NotFound(streamId)
        val id = ws.activeTaskId ?: return ActionResult.Success(emptyList())
        val path = repository.getAncestry(id) ?: return ActionResult.Rejected(DomainError.TaskNotFound(id))
        return ActionResult.Success(path)
    }

    suspend fun nextTaskCandidate(streamId: String): ActionResult<Task?> {
        repository.getStream(streamId) ?: return ActionResult.NotFound(streamId)
        val tasks = repository.getTasksByWorkStream(streamId)
        val hasChild = tasks.mapNotNull { it.parentTaskId }.toHashSet()
        // First open leaf in depth-first sibling order from the top-level tasks.
        val byParent = tasks.groupBy { it.parentTaskId }
        fun walk(parent: String?): Task? {
            for (t in byParent[parent].orEmpty().sortedBy { it.order }) {
                if (t.id !in hasChild) { if (!t.status.isTerminal) return t } else walk(t.id)?.let { return it }
            }
            return null
        }
        return ActionResult.Success(walk(null))
    }

    // ------------------------------------------------------------------ Shared

    private suspend fun <T> tx(block: suspend WorkStreamWriter.() -> ActionResult<T>): ActionResult<T> =
        try { repository.transaction { block() } } catch (e: Exception) { ActionResult.Failure(e) }

    private suspend fun WorkStreamWriter.streamEvent(streamId: String, type: EventType, at: Instant, detail: String?) {
        appendEvent(WorkStreamEvent(ids.newId("ev"), streamId, type, at, detail = detail))
    }
}

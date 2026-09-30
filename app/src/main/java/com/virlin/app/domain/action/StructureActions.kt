package com.virlin.app.domain.action

import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.EffectiveExecutionMode
import com.virlin.app.domain.activity.TaskEventDetail
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
import com.virlin.app.domain.structure.HierarchyRules
import com.virlin.app.domain.structure.NodeKind
import com.virlin.app.domain.structure.Placement
import com.virlin.app.domain.structure.PlacementCheck
import com.virlin.app.domain.structure.PlacementCommit
import com.virlin.app.domain.structure.PlacementOperation
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
        workStreamId?.let {
            streamEvent(it, EventType.TASK_CREATED, now,
                TaskEventDetail.encode(task.id, task.title, toStatus = task.status))
        }
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
        t.workStreamId?.let {
            streamEvent(it, EventType.TASK_UPDATED, now,
                TaskEventDetail.encode(t.id, updated.title, t.status, updated.status))
        }
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
        t.workStreamId?.let {
            streamEvent(it, EventType.TASK_UPDATED, clock.now(),
                TaskEventDetail.encode(t.id, updated.title, t.status, updated.status))
        }
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

    /**
     * PLACE: move an existing task between a WorkStream of its project and the project's
     * standalone list. The task keeps its id, its status, its history and its place in progress
     * — only the ownership association changes, so nothing is copied and no total shifts merely
     * because the task is shown somewhere else.
     *
     * A task's subtree inherits its ownership, so the descendants move with it; otherwise a
     * child would claim a WorkStream its parent no longer belongs to. Only top-level tasks can
     * be placed: a subtask lives wherever its parent does.
     */
    suspend fun placeTask(taskId: String, workStreamId: String?): ActionResult<Task> = tx {
        val task = getTask(taskId) ?: return@tx ActionResult.Rejected(DomainError.TaskNotFound(taskId))
        if (task.parentTaskId != null) return@tx ActionResult.Rejected(DomainError.OwnershipMismatch)
        val projectId = task.projectId ?: return@tx ActionResult.Rejected(DomainError.OwnershipMismatch)
        val target = workStreamId?.let { id ->
            val ws = getStream(id) ?: return@tx ActionResult.NotFound(id)
            // Never across projects: a project's task can only be placed inside that project.
            if (ws.projectId != projectId) return@tx ActionResult.Rejected(DomainError.OwnershipMismatch)
            ws
        }
        if (task.workStreamId == target?.id) return@tx ActionResult.Success(task)

        val now = clock.now()
        val all = allTasks()
        val subtree = (TaskHierarchy.descendants(all, task.id).map { it.id } + task.id).toSet()
        val moved = task.copy(workStreamId = target?.id, projectId = projectId, updatedAt = now)
        saveTask(moved)
        all.asSequence()
            .filter { it.id in subtree && it.id != task.id }
            .forEach { saveTask(it.copy(workStreamId = target?.id, projectId = projectId, updatedAt = now)) }

        // If it was the stream's active task, that stream no longer owns it.
        task.workStreamId?.let { previous ->
            val ws = getStream(previous)
            if (ws != null && ws.activeTaskId in subtree) {
                saveStream(ws.copy(activeTaskId = null, updatedAt = now))
                streamEvent(previous, EventType.ACTIVE_TASK_CLEARED, now,
                    TaskEventDetail.encode(task.id, task.title))
            }
            streamEvent(previous, EventType.TASK_UPDATED, now,
                TaskEventDetail.encode(task.id, task.title, task.status, task.status))
        }
        target?.let {
            streamEvent(it.id, EventType.TASK_UPDATED, now,
                TaskEventDetail.encode(task.id, task.title, task.status, task.status))
        }
        ActionResult.Success(moved)
    }

    // ------------------------------------------------------------------ hierarchy placement

    /**
     * THE structural placement command: move or copy a whole branch anywhere it may legally go
     * inside one project, in ONE transaction.
     *
     * This is what the mind map's Move / Copy / Paste calls, and it is the only way the map
     * changes a parent. It supersedes nothing: [placeTask] and [moveTasks] remain for the
     * narrower jobs they already do.
     *
     * A MOVE keeps every id and every piece of task metadata - status, notes, execution
     * preference, estimates - and re-homes the whole subtree, rewriting the workStreamId and
     * projectId the schema stores redundantly on each descendant so no descendant claims a
     * stream its parent left. A COPY allocates fresh ids parent-first and deliberately drops
     * what belongs to the original alone: completion, the active-task marker and history.
     *
     * One activity event is recorded for the whole operation.
     */
    suspend fun placeBranch(projectId: String, request: Placement): ActionResult<PlacementCommit> = tx {
        val project = getProject(projectId) ?: return@tx ActionResult.Rejected(DomainError.ProjectNotFound(projectId))
        val all = allTasks()
        val streams = allStreams()
        val nodes = HierarchyRules.nodesOf(project.id, streams, all)
        val checked = HierarchyRules.validate(nodes, request, HierarchyRules.revisionOf(nodes))
        val allowed = when (checked) {
            is PlacementCheck.Rejected -> return@tx ActionResult.Rejected(DomainError.PlacementRejected(checked.reason))
            is PlacementCheck.Allowed -> checked
        }
        val now = clock.now()

        // A workstream's only legal move is a reorder under the project root (the validator has
        // already refused anything else). Since v14 that order is a stored column, so this is a
        // real edit rather than a refusal.
        if (allowed.source.kind == NodeKind.WORKSTREAM) {
            val stream = streams.first { it.id == allowed.source.id }
            val previousBefore = streams
                .filter { it.projectId == stream.projectId }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .dropWhile { it.id != stream.id }.drop(1).firstOrNull()?.id
            reorderStreams(streams, stream, request.insertBeforeId, now)
            return@tx ActionResult.Success(
                PlacementCommit(
                    affectedIds = listOf(stream.id),
                    clonedIds = emptyMap(),
                    previousParentTaskId = null,
                    previousWorkStreamId = null,
                    previousInsertBeforeId = previousBefore,
                    targetParentId = allowed.target.id,
                )
            )
        }

        val target = allowed.target
        val newParentTaskId = if (target.kind == NodeKind.TASK) target.id else null
        val newStreamId = when (target.kind) {
            NodeKind.WORKSTREAM -> target.id
            NodeKind.PROJECT -> null
            NodeKind.TASK -> all.first { it.id == target.id }.workStreamId
        }

        val source = all.first { it.id == allowed.source.id }
        val commit = when (request.operation) {
            PlacementOperation.MOVE ->
                moveBranch(allowed, all, source, newParentTaskId, newStreamId, project.id, request.insertBeforeId, now)
            PlacementOperation.COPY ->
                copyBranch(allowed, all, newParentTaskId, newStreamId, project.id, request.insertBeforeId, now)
        }

        // One event for the whole operation, on the stream that received the branch. A
        // standalone landing has no stream to carry it.
        newStreamId?.let {
            streamEvent(
                it, EventType.TASK_UPDATED, now,
                TaskEventDetail.encode(source.id, source.title, source.status, source.status)
            )
        }
        ActionResult.Success(commit)
    }

    private suspend fun WorkStreamWriter.moveBranch(
        allowed: PlacementCheck.Allowed,
        all: List<Task>,
        source: Task,
        newParentTaskId: String?,
        newStreamId: String?,
        projectId: String,
        insertBeforeId: String?,
        now: Instant,
    ): PlacementCommit {
        val subtree = allowed.subtreeIds.toSet()
        val previousSibling = siblingAfter(all, source)
        // Every descendant follows its root into the new stream; ids and metadata are untouched.
        all.asSequence().filter { it.id in subtree && it.id != source.id }.forEach { child ->
            saveTask(child.copy(workStreamId = newStreamId, projectId = projectId, updatedAt = now))
        }
        val relocated = source.copy(
            parentTaskId = newParentTaskId, workStreamId = newStreamId,
            projectId = projectId, updatedAt = now
        )
        saveTask(relocated)
        renumberSiblings(all, relocated, subtree, newParentTaskId, newStreamId, insertBeforeId, now)
        // The group the branch LEFT has a hole in it now. Closing it keeps every sibling group
        // contiguous from zero, so the next insertion index means the same thing everywhere and
        // repeated moves cannot drift the numbering apart.
        if (source.parentTaskId != newParentTaskId || source.workStreamId != newStreamId) {
            compactSiblings(all, subtree, source.parentTaskId, source.workStreamId, now)
        }

        // A stream cannot keep pointing at a task that has left it.
        source.workStreamId?.takeIf { it != newStreamId }?.let { previous ->
            val ws = getStream(previous)
            if (ws != null && ws.activeTaskId in subtree) {
                saveStream(ws.copy(activeTaskId = null, updatedAt = now))
                streamEvent(
                    previous, EventType.ACTIVE_TASK_CLEARED, now,
                    TaskEventDetail.encode(source.id, source.title)
                )
            }
        }
        return PlacementCommit(
            affectedIds = allowed.subtreeIds,
            clonedIds = emptyMap(),
            previousParentTaskId = source.parentTaskId,
            previousWorkStreamId = source.workStreamId,
            previousInsertBeforeId = previousSibling,
            targetParentId = allowed.target.id,
        )
    }

    private suspend fun WorkStreamWriter.copyBranch(
        allowed: PlacementCheck.Allowed,
        all: List<Task>,
        newParentTaskId: String?,
        newStreamId: String?,
        projectId: String,
        insertBeforeId: String?,
        now: Instant,
    ): PlacementCommit {
        // Every id is allocated before anything is written, so the clone's internal parent
        // links can be remapped without a second pass.
        val cloned = allowed.subtreeIds.associateWith { ids.newId("task") }
        val byId = all.associateBy { it.id }
        var root: Task? = null
        // Parent-first: the validator returns the subtree in that order.
        allowed.subtreeIds.forEach { id ->
            val original = byId.getValue(id)
            val copy = original.copy(
                id = cloned.getValue(id),
                parentTaskId = if (id == allowed.source.id) newParentTaskId
                else cloned.getValue(original.parentTaskId!!),
                workStreamId = newStreamId,
                projectId = projectId,
                // A copy has done nothing yet: completion belongs to the original alone. Notes,
                // estimate and execution preference are part of the work and travel with it.
                status = if (original.status == TaskStatus.CANCELLED) TaskStatus.CANCELLED else TaskStatus.TODO,
                completedAt = null,
                createdAt = now, updatedAt = now
            )
            saveTask(copy)
            if (id == allowed.source.id) root = copy
        }
        val placed = root!!
        renumberSiblings(all, placed, cloned.values.toSet(), newParentTaskId, newStreamId, insertBeforeId, now)
        return PlacementCommit(
            affectedIds = allowed.subtreeIds,
            clonedIds = cloned,
            previousParentTaskId = null,
            previousWorkStreamId = null,
            previousInsertBeforeId = null,
            targetParentId = allowed.target.id,
        )
    }

    /**
     * Puts [placed] among its new siblings at [insertBeforeId] and renumbers that one sibling
     * list from zero. No other list is touched.
     */
    private suspend fun WorkStreamWriter.renumberSiblings(
        all: List<Task>,
        placed: Task,
        exclude: Set<String>,
        newParentTaskId: String?,
        newStreamId: String?,
        insertBeforeId: String?,
        now: Instant,
    ) {
        val siblings = all
            .filter {
                it.parentTaskId == newParentTaskId && it.workStreamId == newStreamId &&
                    it.id !in exclude && it.id != placed.id
            }
            .sortedWith(compareBy({ it.order }, { it.id }))
            .toMutableList()
        val at = insertBeforeId
            ?.let { id -> siblings.indexOfFirst { it.id == id }.takeIf { position -> position >= 0 } }
            ?: siblings.size
        val ordered = ArrayList<Task>(siblings.size + 1)
        ordered.addAll(siblings.take(at))
        ordered.add(placed)
        ordered.addAll(siblings.drop(at))
        ordered.forEachIndexed { index, task ->
            if (task.order != index || task.id == placed.id) {
                saveTask(task.copy(order = index, updatedAt = now))
            }
        }
    }

    /**
     * Writes a workstream's new position under its project, renumbering the whole group to a
     * contiguous 0-based sequence in the same transaction. The moved stream is removed from the
     * list BEFORE the insertion index is taken, so moving one downward lands it where the user
     * pointed rather than one place short.
     */
    private suspend fun WorkStreamWriter.reorderStreams(
        all: List<WorkStream>,
        moved: WorkStream,
        insertBeforeId: String?,
        now: Instant,
    ) {
        val siblings = all
            .filter { it.projectId == moved.projectId && it.id != moved.id }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .toMutableList()
        val at = insertBeforeId
            ?.let { id -> siblings.indexOfFirst { it.id == id }.takeIf { position -> position >= 0 } }
            ?: siblings.size
        val ordered = ArrayList<WorkStream>(siblings.size + 1)
        ordered.addAll(siblings.take(at))
        ordered.add(moved)
        ordered.addAll(siblings.drop(at))
        ordered.forEachIndexed { index, stream ->
            if (stream.sortOrder != index) saveStream(stream.copy(sortOrder = index, updatedAt = now))
        }
    }

    /**
     * Closes the gap a departing branch leaves behind, preserving the remaining order exactly.
     * Only rows whose number actually changes are written.
     */
    private suspend fun WorkStreamWriter.compactSiblings(
        all: List<Task>,
        exclude: Set<String>,
        parentTaskId: String?,
        streamId: String?,
        now: Instant,
    ) {
        all.filter {
            it.parentTaskId == parentTaskId && it.workStreamId == streamId && it.id !in exclude
        }
            .sortedWith(compareBy({ it.order }, { it.id }))
            .forEachIndexed { index, task ->
                if (task.order != index) saveTask(task.copy(order = index, updatedAt = now))
            }
    }

    /** The sibling that followed [task] where it used to be - the undo's insertion point. */
    private fun siblingAfter(all: List<Task>, task: Task): String? = all
        .filter { it.parentTaskId == task.parentTaskId && it.workStreamId == task.workStreamId }
        .sortedWith(compareBy({ it.order }, { it.id }))
        .dropWhile { it.id != task.id }
        .drop(1)
        .firstOrNull()?.id

    /**
     * MOVE: re-parent and re-position tasks inside one WorkStream, in ONE transaction.
     *
     * The tasks keep their ids, their children, their history and any current marker — only
     * `parentTaskId` and sibling order change. A task may not move into itself or into one of
     * its own descendants, and every id must already belong to [withinStreamId]; either fault
     * rejects the whole call rather than moving part of it.
     *
     * [afterId] names the sibling the moved tasks follow, or null to place them first.
     */
    suspend fun moveTasks(
        taskIds: List<String>,
        newParentId: String?,
        afterId: String?,
        withinStreamId: String
    ): ActionResult<List<Task>> = tx {
        if (taskIds.isEmpty()) return@tx ActionResult.Success(emptyList())
        val stream = getStream(withinStreamId) ?: return@tx ActionResult.NotFound(withinStreamId)
        val all = allTasks()
        val moving = taskIds.map { id ->
            val task = all.firstOrNull { it.id == id }
                ?: return@tx ActionResult.Rejected(DomainError.TaskNotFound(id))
            if (task.workStreamId != stream.id) return@tx ActionResult.Rejected(DomainError.OwnershipMismatch)
            task
        }
        val parent = newParentId?.let { id ->
            val p = all.firstOrNull { it.id == id }
                ?: return@tx ActionResult.Rejected(DomainError.TaskNotFound(id))
            if (p.workStreamId != stream.id) return@tx ActionResult.Rejected(DomainError.OwnershipMismatch)
            p
        }
        // Into itself, or into its own subtree, would orphan the branch from the tree.
        if (parent != null) {
            val forbidden = moving.flatMap { task ->
                listOf(task.id) + TaskHierarchy.descendants(all, task.id).map { it.id }
            }.toSet()
            if (parent.id in forbidden) return@tx ActionResult.Rejected(DomainError.CyclicParent)
        }

        val now = clock.now()
        val movedIds = moving.map { it.id }.toSet()
        // Siblings at the destination, with the moved tasks taken out, in their stored order.
        val destination = all
            .filter { it.parentTaskId == newParentId && it.workStreamId == stream.id && it.id !in movedIds }
            .sortedWith(compareBy({ it.order }, { it.id }))
            .toMutableList()
        val at = when (afterId) {
            null -> 0
            else -> destination.indexOfFirst { it.id == afterId }.let { if (it < 0) destination.size else it + 1 }
        }
        val reordered = ArrayList<Task>(destination.size + moving.size)
        reordered.addAll(destination.take(at))
        reordered.addAll(moving)
        reordered.addAll(destination.drop(at))

        val saved = reordered.mapIndexed { index, task ->
            val updated = task.copy(
                parentTaskId = if (task.id in movedIds) newParentId else task.parentTaskId,
                order = index,
                updatedAt = now
            )
            saveTask(updated)
            updated
        }
        streamEvent(stream.id, EventType.TASK_UPDATED, now,
            TaskEventDetail.encode(moving.first().id, moving.first().title,
                moving.first().status, moving.first().status))
        ActionResult.Success(saved.filter { it.id in movedIds })
    }

    /**
     * DUPLICATE: a new task beside the original, with its whole subtree copied under new ids.
     * The copy is a separate record: it starts open, carries no current marker, and nothing
     * that pointed at the original points at it.
     */
    suspend fun duplicateTask(taskId: String): ActionResult<Task> = tx {
        val all = allTasks()
        val source = all.firstOrNull { it.id == taskId }
            ?: return@tx ActionResult.Rejected(DomainError.TaskNotFound(taskId))
        val now = clock.now()
        val copyId = ids.newId("task")
        val copy = source.copy(
            id = copyId,
            title = source.title + " (copy)",
            status = TaskStatus.TODO,
            completedAt = null,
            order = source.order + 1,
            createdAt = now,
            updatedAt = now
        )
        saveTask(copy)
        // Everything after the original shifts down so the copy sits directly beside it.
        all.filter {
            it.parentTaskId == source.parentTaskId && it.workStreamId == source.workStreamId &&
                it.id != source.id && it.order > source.order
        }.forEach { saveTask(it.copy(order = it.order + 1, updatedAt = now)) }

        // The subtree, breadth-first, so a child is always copied after its new parent exists.
        val remap = HashMap<String, String>().apply { put(source.id, copyId) }
        TaskHierarchy.descendants(all, source.id).forEach { child ->
            val newId = ids.newId("task")
            remap[child.id] = newId
            saveTask(
                child.copy(
                    id = newId,
                    parentTaskId = remap[child.parentTaskId] ?: copyId,
                    status = TaskStatus.TODO,
                    completedAt = null,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        source.workStreamId?.let {
            streamEvent(it, EventType.TASK_CREATED, now, TaskEventDetail.encode(copyId, copy.title, toStatus = copy.status))
        }
        ActionResult.Success(copy)
    }

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
            streamEvent(wsId, eventType, now,
                TaskEventDetail.encode(t.id, t.title, t.status, closed.status))
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
                streamEvent(wsId, EventType.ACTIVE_TASK_CLEARED, now,
                    TaskEventDetail.encode(t.id, t.title))
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

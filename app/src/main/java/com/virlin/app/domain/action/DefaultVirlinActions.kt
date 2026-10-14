package com.virlin.app.domain.action
import com.virlin.app.domain.model.ExternalTiming
import com.virlin.app.domain.model.ExecutionEligibility

import com.virlin.app.domain.attention.InMemoryPriorityPreferences
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.attention.PriorityPreference
import com.virlin.app.domain.attention.PriorityPreferences
import com.virlin.app.domain.attention.PriorityScope
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.ContextSnapshot
import com.virlin.app.domain.model.Cycle
import com.virlin.app.domain.model.EffectiveExecutionMode
import com.virlin.app.domain.model.ExternalStage
import com.virlin.app.domain.model.ExternalStageStatus
import com.virlin.app.domain.model.ExternalStages
import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.ExecutionModeResolver
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.activity.TaskEventDetail
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.executionStream
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamEvent
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.*
import com.virlin.app.domain.model.WorkStreamTransitions
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.domain.time.VirlinClock
import java.time.Duration
import java.time.Instant

/**
 * Reference implementation of [VirlinActions] over a [WorkStreamRepository].
 *
 * Every mutating action runs in ONE repository transaction and follows the same shape:
 * load → validate (transition table + intent-specific rules) → close any open FocusSession
 * → snapshot context → save the new stream → append semantic events. Timestamps come only
 * from [clock]; ids only from [ids].
 */
class DefaultVirlinActions(
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
    private val ids: IdProvider,
    /** Phase 04 priority preferences (in memory; persistence-ready behind the interface). */
    private val preferences: PriorityPreferences = InMemoryPriorityPreferences()
) : VirlinActions {

    private val structure = StructureActions(repository, clock, ids)
    private val capture = CaptureActions(repository, clock, ids, structure)
    private val notes = NoteActions(repository, clock, ids, capture)
    private val prompts = PromptActions(repository, clock, ids, capture)
    private val attachments = AttachmentActions(repository, clock, ids, capture)
    private val voices = VoiceActions(repository, clock, ids, capture)
    private suspend fun activityById(id: String) = repository.getTask(id)?.executionStream() ?: repository.getStream(id)

    // ------------------------------------------------------------------ Structure (delegated)
    override suspend fun deleteWorkStream(id: String): ActionResult<ProjectDeletion> = deleteBranch(id, false)
    override suspend fun deleteTask(id: String): ActionResult<ProjectDeletion> = deleteBranch(id, true)
    private suspend fun deleteBranch(id: String, task: Boolean): ActionResult<ProjectDeletion> = try {
        repository.transaction {
            if ((if (task) getTask(id) else getStream(id)) == null) return@transaction ActionResult.NotFound(id)
            val before = allStreams()
            val report = if (task) deleteTaskTree(id) else deleteWorkStreamTree(id)
            before.filter { it.id !in report.streamIds && it.activeTaskId in report.taskIds }.forEach { previous ->
                val now = clock.now()
                getOpenFocusSession(previous.id)?.let { saveFocusSession(it.copy(endedAt = now)) }
                previous.currentCycleId?.let { getCycle(it) }?.let { saveCycle(it.copy(endedAt = now)) }
                saveStream(previous.copy(activeTaskId = null, state = if (previous.state.isTerminal) previous.state else WorkStreamState.READY,
                    processingStartedAt = null, externalTrackingPaused = false, checkAt = null,
                    snoozedUntil = null, snoozeReason = null, blockerReason = null, currentCycleId = null, updatedAt = now))
            }
            ActionResult.Success(report)
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (e: Exception) { ActionResult.Failure(e) }
    override suspend fun deleteProject(projectId: String): ActionResult<ProjectDeletion> = try {
        repository.transaction {
            if (getProject(projectId) == null) return@transaction ActionResult.NotFound(projectId)
            ActionResult.Success(deleteProjectTree(projectId))
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (e: Exception) { ActionResult.Failure(e) }
    override suspend fun createProject(request: CreateProject) = structure.createProject(request)
    override suspend fun updateProject(projectId: String, update: ProjectUpdate) = structure.updateProject(projectId, update)
    override suspend fun completeProject(projectId: String) = structure.completeProject(projectId)
    override suspend fun createWorkStream(request: CreateWorkStream): ActionResult<WorkStream> = try {
        repository.transaction { createWorkStreamIn(this, request) }
    } catch (e: Exception) { ActionResult.Failure(e) }

    private suspend fun createWorkStreamIn(writer: WorkStreamWriter, request: CreateWorkStream): ActionResult<WorkStream> = with(writer) {
            if (request.title.isBlank()) return ActionResult.Rejected(DomainError.EmptyTitle)
            request.projectId?.let { pid -> getProject(pid) ?: return ActionResult.Rejected(DomainError.ProjectNotFound(pid)) }
            if (request.executionPreference == ExecutionPreference.INHERIT && request.projectId == null) {
                return ActionResult.Rejected(DomainError.InheritRequiresProject)
            }
            val now = clock.now()
            val stream = WorkStream(
                id = request.id ?: ids.newId("ws"), title = request.title.trim(), projectId = request.projectId,
                tool = request.tool?.takeIf { it.isNotBlank() },
                executionPreference = request.executionPreference, state = READY,
                priority = request.priority, nextHumanAction = request.nextHumanAction?.takeIf { it.isNotBlank() },
                createdAt = now, updatedAt = now,
                // New work joins the end of its project's list rather than jumping into the
                // middle of an arrangement the user made.
                sortOrder = allStreams().count { it.projectId == request.projectId }
            )
            persist(stream)
            event(stream, EventType.STREAM_CREATED, now, to = READY, cycleId = null, detail = request.executionPreference.name)
            ActionResult.Success(stream)
    }

    override suspend fun bulkCreateTasks(json: String, destination: CreateTask): ActionResult<Int> = try {
        val tree = BulkHierarchy.parse(json)
        require(destination.parentTaskId != null || destination.workStreamId != null || destination.projectId != null) { "Select a destination." }
        repository.transaction {
            createBulkTasksIn(this, tree, destination)
            ActionResult.Success(tree.sumOf { it.count })
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (e: Exception) { ActionResult.Failure(e) }

    private suspend fun createBulkTasksIn(writer: WorkStreamWriter, nodes: List<BulkNode>, destination: CreateTask) {
        nodes.forEach { node ->
            val result = structure.createTaskIn(writer, destination.copy(title = node.name, id = null, order = null))
            check(result is ActionResult.Success) { "Import refused: the destination may be closed, active, or no longer available. Nothing was added." }
            // Only root items use the caller's defaults. Descendants inherit their new parent.
            createBulkTasksIn(writer, node.children, CreateTask("", parentTaskId = result.value.id))
        }
    }

    override suspend fun bulkCreateNames(json: String, projectId: String?): ActionResult<Int> = try {
        val tree = BulkHierarchy.parse(json)
        repository.transaction {
            if (projectId != null) {
                val project = getProject(projectId) ?: error("The destination project no longer exists.")
                require(project.status == com.virlin.app.domain.model.ProjectStatus.ACTIVE) { "Choose an active project." }
            }
            suspend fun createStreams(nodes: List<BulkNode>, ownerProjectId: String) {
                nodes.forEach { node ->
                    val stream = createWorkStreamIn(this, CreateWorkStream(node.name, ownerProjectId))
                    check(stream is ActionResult.Success) { "Could not create workstream. Nothing was added." }
                    createBulkTasksIn(this, node.children, CreateTask("", projectId = ownerProjectId, workStreamId = stream.value.id))
                }
            }
            if (projectId != null) createStreams(tree, projectId)
            else tree.forEach { node ->
                val project = structure.createProjectIn(this, CreateProject(node.name))
                check(project is ActionResult.Success) { "Could not create project. Nothing was added." }
                createStreams(node.children, project.value.id)
            }
            ActionResult.Success(tree.sumOf { it.count })
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (e: Exception) { ActionResult.Failure(e) }

    override suspend fun setProjectExecutionDefault(projectId: String, mode: EffectiveExecutionMode) =
        structure.setProjectExecutionDefault(projectId, mode)
    override suspend fun setWorkStreamExecutionPreference(streamId: String, preference: ExecutionPreference) =
        structure.setWorkStreamExecutionPreference(streamId, preference)
    override suspend fun resetWorkStreamExecutionPreference(streamId: String) =
        structure.resetWorkStreamExecutionPreference(streamId)
    override suspend fun setTaskExecutionPreference(taskId: String, preference: ExecutionPreference) =
        structure.setTaskExecutionPreference(taskId, preference)
    override suspend fun resetTaskExecutionPreference(taskId: String) =
        structure.resetTaskExecutionPreference(taskId)

    override suspend fun createCapture(request: CreateCapture) = capture.createCapture(request)
    override suspend fun updateCapture(id: String, update: CaptureUpdate) = capture.updateCapture(id, update)
    override suspend fun attachCapture(id: String, context: CaptureContext) = capture.attachCapture(id, context)
    override suspend fun attachCaptures(ids: Set<String>, context: CaptureContext, withinProjectId: String) =
        capture.attachCaptures(ids, context, withinProjectId)

    override suspend fun createTextNote(
        title: String?,
        blocks: List<com.virlin.app.domain.model.NoteBlock>,
        context: CaptureContext,
        captureId: String?,
        noteId: String?
    ) = notes.createTextNote(title, blocks, context, captureId, noteId)

    override suspend fun saveTextNote(
        captureItemId: String,
        title: String?,
        blocks: List<com.virlin.app.domain.model.NoteBlock>
    ) = notes.saveTextNote(captureItemId, title, blocks)

    override suspend fun getOrHydrateTextNote(captureItemId: String) = notes.getOrHydrateTextNote(captureItemId)

    override suspend fun getNoteByCaptureId(captureItemId: String) = notes.getNoteDocumentByCaptureId(captureItemId)

    override suspend fun createPrompt(
        title: String?,
        description: String?,
        tags: List<String>,
        blocks: List<com.virlin.app.domain.model.NoteBlock>,
        context: CaptureContext,
        captureId: String?,
        promptId: String?,
        sourceText: String,
        mode: com.virlin.app.domain.model.PromptContentMode,
        language: String?,
        responseText: String?
    ) = prompts.createPrompt(title, description, tags, blocks, context, captureId, promptId, sourceText, mode, language, responseText)

    override suspend fun savePrompt(
        captureItemId: String,
        title: String?,
        description: String?,
        tags: List<String>,
        blocks: List<com.virlin.app.domain.model.NoteBlock>,
        sourceText: String,
        mode: com.virlin.app.domain.model.PromptContentMode,
        language: String?,
        responseText: String?
    ) = prompts.savePrompt(captureItemId, title, description, tags, blocks, sourceText, mode, language, responseText)

    override suspend fun getOrHydratePrompt(captureItemId: String) = prompts.getOrHydratePrompt(captureItemId)

    override suspend fun getPromptByCaptureId(captureItemId: String) =
        prompts.getPromptDocumentByCaptureId(captureItemId)

    override suspend fun createAttachment(
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        relativePath: String,
        kind: com.virlin.app.domain.model.AttachmentKind,
        context: CaptureContext,
        captureId: String?,
        attachmentId: String?
    ) = attachments.createAttachment(displayName, mimeType, sizeBytes, relativePath, kind, context, captureId, attachmentId)

    override suspend fun saveAttachment(
        captureItemId: String,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        relativePath: String,
        kind: com.virlin.app.domain.model.AttachmentKind
    ) = attachments.saveAttachment(captureItemId, displayName, mimeType, sizeBytes, relativePath, kind)

    override suspend fun getAttachmentByCaptureId(captureItemId: String) =
        attachments.getAttachmentByCaptureId(captureItemId)

    override suspend fun createVoice(
        title: String?,
        clips: List<com.virlin.app.domain.model.VoiceClip>,
        context: CaptureContext,
        captureId: String?,
        voiceId: String?
    ) = voices.createVoice(title, clips, context, captureId, voiceId)

    override suspend fun saveVoice(
        captureItemId: String,
        title: String?,
        clips: List<com.virlin.app.domain.model.VoiceClip>
    ) = voices.saveVoice(captureItemId, title, clips)

    override suspend fun getVoiceByCaptureId(captureItemId: String) =
        voices.getVoiceByCaptureId(captureItemId)

    override suspend fun archiveCapture(id: String) = capture.archiveCapture(id)
    override suspend fun restoreCapture(id: String) = capture.restoreCapture(id)
    override suspend fun convertCaptureToTask(id: String, target: CaptureTaskTarget) = capture.convertCaptureToTask(id, target)

    override suspend fun createTask(request: CreateTask) = structure.createTask(request)
    override suspend fun addSubtask(parentTaskId: String, title: String, estimatedEffort: Duration?) = structure.addSubtask(parentTaskId, title, estimatedEffort)
    override suspend fun updateTask(taskId: String, update: TaskUpdate) = structure.updateTask(taskId, update)
    override suspend fun completeTask(taskId: String) = structure.completeTask(taskId)
    override suspend fun restoreTask(taskId: String) = structure.restoreTask(taskId)
    override suspend fun cancelTask(taskId: String) = structure.cancelTask(taskId)
    override suspend fun placeTask(taskId: String, workStreamId: String?) = structure.placeTask(taskId, workStreamId)
    override suspend fun moveTasks(
        taskIds: List<String>, newParentId: String?, afterId: String?, withinStreamId: String
    ) = structure.moveTasks(taskIds, newParentId, afterId, withinStreamId)
    override suspend fun duplicateTask(taskId: String) = structure.duplicateTask(taskId)

    override suspend fun placeBranch(
        projectId: String,
        request: com.virlin.app.domain.structure.Placement
    ) = structure.placeBranch(projectId, request)

    override suspend fun hierarchyRevision(projectId: String): Long =
        com.virlin.app.domain.structure.HierarchyRules.revisionOf(
            com.virlin.app.domain.structure.HierarchyRules.nodesOf(
                projectId, repository.streams.value, repository.tasks.value
            )
        )

    private val tagging = TagActions(repository, clock, ids)
    override suspend fun createTag(projectId: String, name: String) = tagging.createTag(projectId, name)
    override suspend fun renameTag(tagId: String, name: String) = tagging.renameTag(tagId, name)
    override suspend fun mergeTags(fromTagId: String, intoTagId: String) = tagging.mergeTags(fromTagId, intoTagId)
    override suspend fun deleteTag(tagId: String) = tagging.deleteTag(tagId)
    override suspend fun setTags(
        projectId: String, tagIds: Set<String>, captureIds: Set<String>, taskIds: Set<String>
    ) = tagging.setTags(projectId, tagIds, captureIds, taskIds)
    override suspend fun addStep(taskId: String, text: String) = tagging.addStep(taskId, text)
    override suspend fun bulkAddSteps(taskId: String, names: List<String>) = tagging.bulkAddSteps(taskId, names)
    override suspend fun setStepDone(stepId: String, done: Boolean) = tagging.setStepDone(stepId, done)
    override suspend fun editStep(stepId: String, text: String) = tagging.editStep(stepId, text)
    override suspend fun deleteStep(stepId: String) = tagging.deleteStep(stepId)
    override suspend fun moveStep(stepId: String, newIndex: Int) = tagging.moveStep(stepId, newIndex)
    override suspend fun moveStepBefore(stepId: String, beforeStepId: String?) =
        tagging.moveStepBefore(stepId, beforeStepId)
    override suspend fun clearCompletedSteps(taskId: String) = tagging.clearCompletedSteps(taskId)
    override suspend fun restoreSteps(cleared: com.virlin.app.domain.model.ClearedSteps) =
        tagging.restoreSteps(cleared)

    private val mapPreviews = MapPreviewActions(repository, clock)
    override suspend fun loadMapNodePreview(ownerId: String) = mapPreviews.load(ownerId)
    override suspend fun loadAllMapNodePreviews() = mapPreviews.loadAll()
    override suspend fun saveMapNodePreview(
        ownerId: String,
        description: com.virlin.app.domain.mappreview.FormattedText,
        images: List<com.virlin.app.domain.mappreview.MapPreviewImage>,
        expanded: Boolean,
    ) = mapPreviews.save(ownerId, description, images, expanded)
    override suspend fun clearMapNodePreview(ownerId: String) = mapPreviews.clear(ownerId)
    override suspend fun setMapNodePreviewExpanded(ownerId: String, expanded: Boolean) =
        mapPreviews.setExpanded(ownerId, expanded)

    private val noteDocs = NoteDocActions(repository, clock, ids, capture)
    override suspend fun loadNoteDoc(ownerKey: String) = noteDocs.loadNoteDoc(ownerKey)
    override suspend fun duplicateCaptureNote(captureId: String, title: String) = noteDocs.duplicateCaptureNote(captureId, title)
    override suspend fun saveNoteDoc(
        ownerKey: String,
        title: String?,
        blocks: List<com.virlin.app.domain.notedoc.NoteDocBlock>
    ) = noteDocs.saveNoteDoc(ownerKey, title, blocks)
    override suspend fun setActiveTask(streamId: String, taskId: String?) = structure.setActiveTask(streamId, taskId)
    override suspend fun activePath(streamId: String) = structure.activePath(streamId)
    override suspend fun nextTaskCandidate(streamId: String) = structure.nextTaskCandidate(streamId)

    // ------------------------------------------------------------------ Focus

    override suspend fun focusStream(streamId: String): ActionResult<FocusOutcome> = run(streamId) { target ->
        focusWithin(target)
    }

    /** The focus transition itself, inside an EXISTING transaction (shared by focusStream and startFocus). */
    private suspend fun WorkStreamWriter.focusWithin(target: WorkStream): ActionResult<FocusOutcome> = run {
        if (repository.taskExecutionEnabled && !ExecutionEligibility.isLeaf(target.id, allTasks()))
            return@run ActionResult.Rejected(DomainError.PlacementRejected("Select a leaf task to focus; this item contains children."))
        if (repository.taskExecutionEnabled && target.executionPreference == ExecutionPreference.EXTERNAL &&
            target.snoozeReason != SnoozeReason.EXTERNAL_RESULT_READY)
            return@run ActionResult.Rejected(DomainError.NotExternalExecution)
        if (target.externalTrackingPaused) return@run ActionResult.Rejected(DomainError.NotExternalExecution)
        if (target.state == FOCUS) return@run ActionResult.Rejected(DomainError.AlreadyFocused)
        requireTransition(target, FOCUS)?.let { return@run it }

        val now = clock.now()
        // Single human Focus: displace the current one in the same transaction.
        val previous = getActiveFocus()?.takeIf { it.id != target.id }
        val displaced = previous?.let { prev ->
            closeOpenSession(prev.id, now)
            saveSnapshot(prev, now, reason = READY)
            val moved = prev.copy(state = if (getTask(prev.id) != null) PAUSED else READY, updatedAt = now)
            persist(moved)
            event(prev, EventType.FOCUS_LEFT, now, from = FOCUS, to = moved.state, detail = "displaced by ${target.id}")
            moved
        }

        // A cycle is one human → process → human loop. Returning to Focus after a hand-off
        // closes that loop and begins the next; a cycle never handed off simply continues.
        val current = getCurrentCycle(target.id)
        val cycle = when {
            current == null -> newCycle(target, now)
            current.handedOffAt != null -> { saveCycle(current.copy(endedAt = now)); newCycle(target, now) }
            else -> current
        }
        saveFocusSession(FocusSession(ids.newId("fs"), target.id, cycle.id, startedAt = now, taskId = target.activeTaskId))
        // Being in Focus implies no external processing and no pending return.
        val focused = target.copy(
            state = FOCUS,
            currentCycleId = cycle.id,
            cycleCount = maxOf(target.cycleCount, cycle.number),
            externalTrackedSeconds = trackedExternalSeconds(target, now),
            processingStartedAt = null,
            checkAt = null,
            snoozedUntil = null,
            snoozeReason = if (repository.taskExecutionEnabled && target.snoozeReason == SnoozeReason.EXTERNAL_RESULT_READY)
                SnoozeReason.EXTERNAL_RESULT_READY else null,
            updatedAt = now
        )
        persist(focused)
        if (target.state == PAUSED || target.state == SNOOZED || target.state == BLOCKED) {
            event(target, EventType.RESUMED, now, from = target.state, to = FOCUS)
        }
        event(target, EventType.FOCUS_STARTED, now, from = target.state, to = FOCUS, cycleId = cycle.id)
        ActionResult.Success(FocusOutcome(focused, displaced))
    }

    // ------------------------------------------------------------------ Hand-off

    // ------------------------------------------------------------------ Phase 09: focus a WORK ITEM

    override suspend fun resolveFocusTarget(workItemId: String): ActionResult<FocusTarget> = try {
        repository.transaction { (if (repository.taskExecutionEnabled) TaskExecutionWriter(this) else this).resolveTarget(workItemId) }
    } catch (e: Exception) { ActionResult.Failure(e) }

    override suspend fun startFocus(workItemId: String): ActionResult<FocusTargetOutcome> = try {
        repository.transaction { with(if (repository.taskExecutionEnabled) TaskExecutionWriter(this) else this) {
            val target = when (val r = resolveTarget(workItemId)) {
                is ActionResult.Success -> r.value
                else -> return@transaction r as ActionResult<FocusTargetOutcome>
            }
            // The exact item first, then the stream: focusStream closes the displaced session,
            // snapshots context and enforces the single-Focus invariant in this same transaction.
            if (!repository.taskExecutionEnabled) saveStream(getStream(target.workStreamId)!!.copy(
                activeTaskId = target.workItem.id, updatedAt = clock.now()))
            val stream = getStream(if (repository.taskExecutionEnabled) target.workItem.id else target.workStreamId)!!
            if (stream.state == FOCUS) {
                // Repeated taps on the same exact task must not split a live session or
                // discard its fractional second. Legacy stream task switches still split.
                if (getOpenFocusSession(stream.id)?.taskId == target.workItem.id) {
                    return@transaction ActionResult.Success(FocusTargetOutcome(target, stream, null))
                }
                // Already the focused stream: the exact work item changed, so the old session is
                // committed and a NEW one starts for the new item (investment follows the item).
                val now = clock.now()
                getOpenFocusSession(stream.id)?.let { open -> if (open.isOpen) saveFocusSession(open.copy(endedAt = now)) }
                saveFocusSession(FocusSession(ids.newId("fs"), stream.id, stream.currentCycleId, startedAt = now, taskId = target.workItem.id))
                event(stream, EventType.FOCUS_STARTED, now, from = FOCUS, to = FOCUS, detail = target.workItem.id)
                ActionResult.Success(FocusTargetOutcome(target, getStream(stream.id)!!, null))
            } else when (val focus = focusWithin(stream)) {
                is ActionResult.Success -> ActionResult.Success(FocusTargetOutcome(target, focus.value.focused, focus.value.displaced))
                else -> focus as ActionResult<FocusTargetOutcome>
            }
        } }
    } catch (e: Exception) { ActionResult.Failure(e) }

    override suspend fun focusNext(streamId: String): ActionResult<FocusTargetOutcome?> {
        val next = when (val r = nextTaskCandidate(streamId)) {
            is ActionResult.Success -> r.value ?: return ActionResult.Success(null)
            else -> return r as ActionResult<FocusTargetOutcome?>
        }
        return when (val started = startFocus(next.id)) {
            is ActionResult.Success -> ActionResult.Success(started.value)
            else -> started as ActionResult<FocusTargetOutcome?>
        }
    }

    /**
     * Container → first OPEN leaf, leaf → itself. Never mutates: resolving is a pure read so the
     * switch confirmation can preview it. Terminal items and containers whose leaves are all
     * terminal are rejected rather than silently reopened.
     */
    private suspend fun WorkStreamWriter.resolveTarget(workItemId: String): ActionResult<FocusTarget> {
        val item = getTask(workItemId) ?: return ActionResult.Rejected(DomainError.TaskNotFound(workItemId))
        val all = allTasks()
        val children = all.filter { it.parentTaskId == item.id }
        if (repository.taskExecutionEnabled && children.isNotEmpty())
            return ActionResult.Rejected(DomainError.PlacementRejected("Select the specific leaf task; containers cannot start work."))
        val resolved = if (children.isEmpty()) {
            if (item.status.isTerminal) return ActionResult.Rejected(DomainError.TaskAlreadyClosed)
            item
        } else {
            firstOpenLeaf(all, item.id) ?: return ActionResult.Rejected(DomainError.TaskAlreadyClosed)
        }
        val streamId = resolved.workStreamId ?: return ActionResult.Rejected(DomainError.OwnershipMismatch)
        val current = getActiveFocus()
        val identity = if (repository.taskExecutionEnabled) resolved.id else streamId
        return ActionResult.Success(
            FocusTarget(
                workItem = resolved, workStreamId = streamId,
                displacedStreamId = current?.id?.takeIf { it != identity || current.activeTaskId != resolved.id },
                displacedWorkItemId = current?.activeTaskId?.takeIf { current.id != identity || it != resolved.id }
            )
        )
    }

    /** Depth-first, sibling order, first non-terminal leaf beneath [rootId]. */
    private fun firstOpenLeaf(all: List<Task>, rootId: String): Task? {
        val byParent = all.groupBy { it.parentTaskId }
        val hasChild = all.mapNotNull { it.parentTaskId }.toHashSet()
        fun walk(parent: String): Task? {
            for (t in byParent[parent].orEmpty().sortedBy { it.order }) {
                if (t.id !in hasChild) { if (!t.status.isTerminal) return t } else walk(t.id)?.let { return it }
            }
            return null
        }
        return walk(rootId)
    }

    override suspend fun handOffStream(
        streamId: String, waitingFor: String?, nextHumanAction: String?, checkAt: Instant?
    ): ActionResult<WorkStream> = run(streamId) { stream ->
        if (stream.state != FOCUS) return@run ActionResult.Rejected(DomainError.NotInFocus)
        val projectDefault = stream.projectId?.let { getProject(it)?.defaultExecutionMode }
        val tasksById = allTasks().associateBy { it.id }
        val effective = ExecutionModeResolver.resolveCurrent(stream, tasksById, projectDefault)
        if (effective != EffectiveExecutionMode.EXTERNAL) {
            return@run ActionResult.Rejected(DomainError.NotExternalExecution)
        }
        requireTransition(stream, PROCESSING)?.let { return@run it }

        val now = clock.now()
        closeOpenSession(stream.id, now)
        val cycle = (getCurrentCycle(stream.id) ?: newCycle(stream, now)).copy(handedOffAt = now)
        saveCycle(cycle)

        val updated = stream.copy(
            state = PROCESSING,
            processingStartedAt = now,
            checkAt = checkAt,
            snoozedUntil = null,
            snoozeReason = null,
            waitingFor = waitingFor ?: stream.waitingFor,
            nextHumanAction = nextHumanAction ?: stream.nextHumanAction,
            currentCycleId = cycle.id,
            updatedAt = now
        )
        saveSnapshot(updated, now, reason = PROCESSING)
        persist(updated)
        event(stream, EventType.HANDOFF, now, from = FOCUS, to = PROCESSING, cycleId = cycle.id,
            detail = waitingFor ?: updated.waitingFor)
        event(stream, EventType.PROCESSING_STARTED, now, cycleId = cycle.id, detail = checkAt?.toString())
        ActionResult.Success(updated)
    }

    // ------------------------------------------------------------------ Check

    override suspend fun checkStream(streamId: String): ActionResult<CheckOutcome> = run(streamId) { stream ->
        if (stream.state.isTerminal) return@run ActionResult.Rejected(DomainError.StreamAlreadyDone)
        val now = clock.now()
        event(stream, EventType.CHECKED, now, cycleId = stream.currentCycleId)
        ActionResult.Success(CheckOutcome(stream, getLatestSnapshot(stream.id)))
    }

    override suspend fun checkDue(streamId: String): ActionResult<WorkStream> = run(streamId) { stream ->
        if (stream.state == PAUSED && !stream.externalTrackingPaused) return@run ActionResult.Rejected(DomainError.NotExternalExecution)
        requireTransition(stream, CHECK)?.let { return@run it }
        val now = clock.now()
        val updated = stream.copy(
            state = CHECK,
            // A scheduled external window ends at checkAt even if Android delivers the alarm
            // late. Needs You measures review waiting separately from that point onward.
            externalTrackedSeconds = trackedExternalSeconds(stream, now),
            processingStartedAt = null,
            snoozedUntil = null,
            updatedAt = now
        )
        persist(updated)
        event(stream, EventType.CHECK_DUE, now, from = stream.state, to = CHECK, cycleId = stream.currentCycleId)
        // Phase 04 policy application: an item with an ACTIVE preference re-enters at its preferred
        // position instead of appending. Ordering still happens only through the queue engine.
        applyPreferenceOnEntry(streamId, now)
        ActionResult.Success(updated)
    }

    override suspend fun continueProcessing(streamId: String, checkAt: Instant): ActionResult<WorkStream> = run(streamId) { stream ->
        if (stream.externalTrackingPaused) return@run ActionResult.Rejected(DomainError.NotExternalExecution)
        val now = clock.now()
        if (!checkAt.isAfter(now)) return@run ActionResult.Rejected(DomainError.InvalidSnoozeTime)
        when (stream.state) {
            PROCESSING -> Unit // updating the check time in place is not a transition
            else -> requireTransition(stream, PROCESSING)?.let { return@run it }
        }
        val startNewWindow = stream.state != PROCESSING ||
            stream.checkAt?.let { !now.isBefore(it) } == true
        val updated = stream.copy(
            state = PROCESSING,
            externalTrackedSeconds = if (startNewWindow) trackedExternalSeconds(stream, now)
                else stream.externalTrackedSeconds,
            processingStartedAt = if (startNewWindow) now else stream.processingStartedAt ?: now,
            checkAt = checkAt,
            snoozedUntil = null,
            updatedAt = now
        )
        persist(updated)
        event(stream, EventType.PROCESSING_STARTED, now, from = stream.state, to = PROCESSING,
            cycleId = stream.currentCycleId, detail = checkAt.toString())
        ActionResult.Success(updated)
    }

    // ------------------------------------------------------------------ Snooze / Ready / Pause / Block

    override suspend fun snoozeStream(streamId: String, until: Instant): ActionResult<WorkStream> = run(streamId) { stream ->
        val now = clock.now()
        if (!until.isAfter(now)) return@run ActionResult.Rejected(DomainError.InvalidSnoozeTime)
        requireTransition(stream, SNOOZED)?.let { return@run it }
        closeOpenSession(stream.id, now)
        val updated = stream.copy(
            state = SNOOZED, snoozedUntil = until, checkAt = until, processingStartedAt = null,
            snoozeReason = stream.snoozeReason ?: SnoozeReason.HUMAN_RETURN, updatedAt = now
        )
        saveSnapshot(updated, now, reason = SNOOZED)
        persist(updated)
        event(stream, EventType.SNOOZED, now, from = stream.state, to = SNOOZED, detail = until.toString())
        ActionResult.Success(updated)
    }

    // ------------------------------------------------------------------ Finalized attention exits

    override suspend fun leaveFocus(streamId: String, returnAt: Instant?): ActionResult<WorkStream> = run(streamId) { stream ->
        if (stream.state != FOCUS) return@run ActionResult.Rejected(DomainError.NotInFocus)
        val now = clock.now()
        val target = if (returnAt == null) READY else SNOOZED
        if (returnAt != null && !returnAt.isAfter(now)) return@run ActionResult.Rejected(DomainError.InvalidSnoozeTime)
        requireTransition(stream, target)?.let { return@run it }

        closeOpenSession(stream.id, now)
        // Nothing is processing: the human simply stopped. activeTaskId and context are kept.
        val updated = stream.copy(
            state = target,
            processingStartedAt = null,
            checkAt = returnAt,
            snoozedUntil = returnAt,
            snoozeReason = if (returnAt != null) SnoozeReason.HUMAN_RETURN else null,
            updatedAt = now
        )
        saveSnapshot(updated, now, reason = target)
        persist(updated)
        event(stream, EventType.LEFT, now, from = FOCUS, to = target, detail = returnAt?.toString())
        if (target == SNOOZED) event(stream, EventType.SNOOZED, now, from = FOCUS, to = SNOOZED, detail = returnAt.toString())
        else event(stream, EventType.READY, now, from = FOCUS, to = READY)
        ActionResult.Success(updated)
    }

    override suspend fun stillRunning(streamId: String, checkAt: Instant): ActionResult<WorkStream> {
        val stream = activityById(streamId) ?: return ActionResult.NotFound(streamId)
        if (stream.state != PROCESSING && !(stream.state == CHECK && stream.snoozeReason == null))
            return ActionResult.Rejected(DomainError.NotAnExternalCheck)
        // continueProcessing validates the time and keeps processing continuity; no FocusSession.
        return continueProcessing(streamId, checkAt)
    }

    override suspend fun resultReadyNow(streamId: String): ActionResult<FocusOutcome> {
        val stream = activityById(streamId) ?: return ActionResult.NotFound(streamId)
        if (stream.state != CHECK && stream.state != PROCESSING) return ActionResult.Rejected(DomainError.NotAnExternalCheck)
        // focusStream clears processingStartedAt/checkAt, opens a FocusSession attributed to
        // the active task and applies single-Focus displacement.
        return focusStream(streamId).also { r ->
            if (r is ActionResult.Success) repository.transaction {
                event(r.value.focused, EventType.RESULT_READY, clock.now(), from = stream.state, to = FOCUS)
            }
        }
    }

    override suspend fun resultReadyLater(streamId: String, returnAt: Instant): ActionResult<WorkStream> = run(streamId) { stream ->
        val now = clock.now()
        if (stream.state != CHECK && stream.state != PROCESSING) return@run ActionResult.Rejected(DomainError.NotAnExternalCheck)
        if (!returnAt.isAfter(now)) return@run ActionResult.Rejected(DomainError.InvalidSnoozeTime)
        val from = stream.state
        if (from == PROCESSING) requireTransition(stream, CHECK)?.let { return@run it }   // must be checkable
        requireTransition(stream.copy(state = CHECK), SNOOZED)?.let { return@run it }
        // The external work is FINISHED: no processing fields survive. The task and context do.
        val updated = stream.copy(
            state = SNOOZED,
            externalTrackedSeconds = trackedExternalSeconds(stream, now), externalTrackingPaused = false,
            processingStartedAt = null,
            checkAt = returnAt,
            snoozedUntil = returnAt,
            snoozeReason = SnoozeReason.EXTERNAL_RESULT_READY,
            updatedAt = now
        )
        saveSnapshot(updated, now, reason = SNOOZED)
        persist(updated)
        event(stream, EventType.RESULT_READY, now, from = from, to = SNOOZED, detail = returnAt.toString())
        event(stream, EventType.SNOOZED, now, from = from, to = SNOOZED, detail = returnAt.toString())
        ActionResult.Success(updated)
    }

    override suspend fun deferReturn(streamId: String, returnAt: Instant): ActionResult<WorkStream> = run(streamId) { stream ->
        val now = clock.now()
        if (!returnAt.isAfter(now)) return@run ActionResult.Rejected(DomainError.InvalidSnoozeTime)
        val reason = stream.snoozeReason ?: return@run ActionResult.Rejected(DomainError.NotAReturn)
        if (stream.state != SNOOZED && stream.state != CHECK) return@run ActionResult.Rejected(DomainError.NotAReturn)
        if (stream.state == CHECK) requireTransition(stream, SNOOZED)?.let { return@run it }
        // Same reason, later time. Never "restarts" processing.
        val updated = stream.copy(
            state = SNOOZED, checkAt = returnAt, snoozedUntil = returnAt, snoozeReason = reason,
            processingStartedAt = null, updatedAt = now
        )
        persist(updated)
        event(stream, EventType.RETURN_DEFERRED, now, from = stream.state, to = SNOOZED, detail = returnAt.toString())
        ActionResult.Success(updated)
    }

    override suspend fun markReady(streamId: String): ActionResult<WorkStream> = run(streamId) { stream ->
        requireTransition(stream, READY)?.let { return@run it }
        val now = clock.now()
        closeOpenSession(stream.id, now)
        val updated = stream.copy(
            state = READY, processingStartedAt = null, externalTrackingPaused = false, checkAt = null, snoozedUntil = null,
            snoozeReason = null, externalTrackedSeconds = stream.externalTrackedSeconds +
                (stream.processingStartedAt?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0), updatedAt = now
        )
        saveSnapshot(updated, now, reason = READY)
        persist(updated)
        event(stream, EventType.READY, now, from = stream.state, to = READY)
        ActionResult.Success(updated)
    }

    override suspend fun pauseAllTimers(projectId: String?): ActionResult<Int> =
        pauseTimers(projectId, "All timers paused")

    override suspend fun pauseTimersForOrganize(projectId: String): ActionResult<Int> =
        pauseTimers(projectId, "Organize timers paused")

    private suspend fun pauseTimers(projectId: String?, provenance: String): ActionResult<Int> = try {
        repository.transaction {
            val now = clock.now()
            val writer = if (repository.taskExecutionEnabled) TaskExecutionWriter(this) else this
            // Include legacy/container activity as well as every independently stored task.
            val items = allStreams() + if (repository.taskExecutionEnabled)
                allTasks().filter { it.execution != null }.map { it.executionStream() } else emptyList()
            var count = 0
            with(writer) {
                for (item in items.distinctBy { it.id }) {
                    if (projectId != null && !belongsToProject(item, projectId)) continue
                    if (item.state.isTerminal || (item.state != FOCUS && item.processingStartedAt == null)) continue
                    closeOpenSession(item.id, now)
                    val external = item.processingStartedAt != null
                    val segment = item.processingStartedAt?.let {
                        Duration.between(it, now).seconds.coerceAtLeast(0)
                    } ?: 0L
                    val updated = item.copy(state = PAUSED, processingStartedAt = null,
                        externalTrackedSeconds = item.externalTrackedSeconds + segment,
                        externalTrackingPaused = external || item.externalTrackingPaused,
                        checkAt = null, snoozedUntil = null, snoozeReason = null, updatedAt = now)
                    saveSnapshot(updated, now, PAUSED)
                    persist(updated)
                    event(item, EventType.PAUSED, now, from = item.state, to = PAUSED,
                        detail = "$provenance;remaining=${item.checkAt?.let { Duration.between(now, it).toMillis().coerceAtLeast(0) } ?: -1};reason=${item.snoozeReason?.name ?: "none"}")
                    count++
                }
            }
            ActionResult.Success(count)
        }
    } catch (e: Exception) { ActionResult.Failure(e) }

    override suspend fun resumeAllTimers(projectId: String?): ActionResult<Int> =
        resumeTimers(projectId, "All timers paused")

    override suspend fun resumeTimersForOrganize(projectId: String): ActionResult<Int> =
        resumeTimers(projectId, "Organize timers paused")

    private suspend fun resumeTimers(projectId: String?, provenance: String): ActionResult<Int> = try {
        repository.transaction {
            val now = clock.now()
            val writer = if (repository.taskExecutionEnabled) TaskExecutionWriter(this) else this
            val items = allStreams() + if (repository.taskExecutionEnabled)
                allTasks().filter { !it.status.isTerminal && it.execution != null }.map { it.executionStream() } else emptyList()
            var count = 0
            with(writer) {
                var humanRunning = items.any { it.state == FOCUS }
                if (provenance == "Organize timers paused" && humanRunning && items.any { item ->
                        (projectId == null || belongsToProject(item, projectId)) &&
                            item.state == PAUSED && !item.externalTrackingPaused &&
                            executionEvents(item.id).lastOrNull()?.let { pause ->
                                pause.type == EventType.PAUSED && pause.at == item.updatedAt &&
                                    pause.detail?.contains(provenance) == true
                            } == true
                    }) return@transaction ActionResult.Rejected(DomainError.AnotherTaskIsExecuting)
                for (item in items.distinctBy { it.id }) {
                    if (projectId != null && !belongsToProject(item, projectId)) continue
                    if (item.state != PAUSED) continue
                    if (repository.taskExecutionEnabled && getTask(item.id) != null &&
                        !ExecutionEligibility.isLeaf(item.id, allTasks())) continue
                    val pause = executionEvents(item.id).lastOrNull() ?: continue
                    // Persisted provenance survives app restarts. Never resume an unrelated manual pause.
                    if (pause.type != EventType.PAUSED || pause.at != item.updatedAt ||
                        pause.detail?.contains(provenance) != true) continue
                    val external = item.externalTrackingPaused
                    if (!external && humanRunning) continue
                    val remaining = Regex("remaining=(-?\\d+)").find(pause.detail!!)?.groupValues?.get(1)?.toLongOrNull()
                    val deadline = remaining?.takeIf { it >= 0 }?.let { now.plusMillis(it) }
                    val reason = Regex("reason=([A-Z_]+)").find(pause.detail!!)?.groupValues?.get(1)
                        ?.let { runCatching { SnoozeReason.valueOf(it) }.getOrNull() }
                    val state = if (external) {
                        if (pause.fromState == CHECK || remaining == 0L) CHECK else PROCESSING
                    } else FOCUS
                    if (!external) {
                        saveFocusSession(FocusSession(ids.newId("fs"), item.id, item.currentCycleId,
                            startedAt = now, taskId = item.activeTaskId))
                        humanRunning = true
                    }
                    val updated = item.copy(state = state, processingStartedAt = if (external) now else null,
                        externalTrackingPaused = false, checkAt = if (state == CHECK) deadline ?: now else deadline,
                        snoozedUntil = null, snoozeReason = reason, updatedAt = now)
                    saveSnapshot(updated, now, state)
                    persist(updated)
                    event(item, EventType.RESUMED, now, from = PAUSED, to = state, detail = "Master timers resumed")
                    count++
                }
            }
            ActionResult.Success(count)
        }
    } catch (e: Exception) { ActionResult.Failure(e) }

    override suspend fun pauseStream(streamId: String): ActionResult<WorkStream> = run(streamId) { stream ->
        if (stream.processingStartedAt != null) return@run ActionResult.Rejected(DomainError.NotExternalExecution)
        requireTransition(stream, PAUSED)?.let { return@run it }
        val now = clock.now()
        closeOpenSession(stream.id, now)
        val updated = stream.copy(state = PAUSED, checkAt = null, snoozedUntil = null, updatedAt = now)
        saveSnapshot(updated, now, reason = PAUSED)
        persist(updated)
        event(stream, EventType.PAUSED, now, from = stream.state, to = PAUSED)
        ActionResult.Success(updated)
    }

    override suspend fun pauseExternalTracking(streamId: String, returnAt: Instant?): ActionResult<WorkStream> = run(streamId) { stream ->
        val alreadyPaused = stream.externalTrackingPaused && stream.state in setOf(PAUSED, CHECK, SNOOZED)
        if (!alreadyPaused && (stream.state !in setOf(PROCESSING, CHECK) || stream.processingStartedAt == null))
            return@run ActionResult.Rejected(DomainError.NotExternalExecution)
        if (stream.state != PAUSED) requireTransition(stream, PAUSED)?.let { return@run it }
        val now = clock.now()
        if (returnAt != null && !returnAt.isAfter(now)) return@run ActionResult.Rejected(DomainError.NotExternalExecution)
        // One transaction closes the active segment and cancels the old check. No human session changes.
        val seconds = stream.processingStartedAt?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0L
        val updated = stream.copy(state = PAUSED, processingStartedAt = null,
            externalTrackedSeconds = stream.externalTrackedSeconds + seconds, externalTrackingPaused = true,
            checkAt = returnAt, snoozedUntil = null, snoozeReason = SnoozeReason.HUMAN_RETURN, updatedAt = now)
        saveSnapshot(updated, now, reason = PAUSED)
        persist(updated)
        event(stream, EventType.PAUSED, now, from = stream.state, to = PAUSED, detail = "External tracking paused")
        ActionResult.Success(updated)
    }

    override suspend fun resumeExternalTracking(streamId: String, checkAt: Instant?): ActionResult<WorkStream> = run(streamId) { stream ->
        if (!stream.externalTrackingPaused || stream.state !in setOf(PAUSED, CHECK, SNOOZED))
            return@run ActionResult.Rejected(DomainError.NotExternalExecution)
        requireTransition(stream, PROCESSING)?.let { return@run it }
        val now = clock.now()
        if (checkAt != null && !checkAt.isAfter(now)) return@run ActionResult.Rejected(DomainError.NotExternalExecution)
        val updated = stream.copy(state = PROCESSING, processingStartedAt = now, externalTrackingPaused = false,
            checkAt = checkAt, snoozedUntil = null, snoozeReason = null, updatedAt = now)
        saveSnapshot(updated, now, reason = PROCESSING)
        persist(updated)
        event(stream, EventType.PROCESSING_STARTED, now, from = stream.state, to = PROCESSING, detail = "External tracking resumed")
        ActionResult.Success(updated)
    }

    override suspend fun blockStream(streamId: String, reason: String?): ActionResult<WorkStream> = run(streamId) { stream ->
        requireTransition(stream, BLOCKED)?.let { return@run it }
        val now = clock.now()
        closeOpenSession(stream.id, now)
        val updated = stream.copy(
            state = BLOCKED, blockerReason = reason ?: stream.blockerReason, externalTrackingPaused = false,
            externalTrackedSeconds = trackedExternalSeconds(stream, now),
            processingStartedAt = null, checkAt = null, snoozedUntil = null, snoozeReason = null, updatedAt = now
        )
        saveSnapshot(updated, now, reason = BLOCKED)
        persist(updated)
        event(stream, EventType.BLOCKED, now, from = stream.state, to = BLOCKED, detail = reason)
        ActionResult.Success(updated)
    }

    override suspend fun unblockStream(streamId: String): ActionResult<WorkStream> = run(streamId) { stream ->
        if (stream.state != BLOCKED) return@run ActionResult.Rejected(DomainError.NotBlocked)
        val now = clock.now()
        val updated = stream.copy(state = READY, blockerReason = null, updatedAt = now)
        persist(updated)
        event(stream, EventType.UNBLOCKED, now, from = BLOCKED, to = READY)
        ActionResult.Success(updated)
    }

    // ------------------------------------------------------------------ Complete

    override suspend fun completeStream(streamId: String): ActionResult<WorkStream> = run(streamId, allowContainer = true) { stream ->
        requireTransition(stream, DONE)?.let { return@run it }
        val now = clock.now()
        // A real workstream completion closes its entire owned hierarchy atomically.
        // Exact task ids keep unrelated workstreams and historical attribution intact.
        if (getTask(stream.id) == null) {
            allTasks().filter { it.workStreamId == stream.id && !it.status.isTerminal }.forEach { task ->
                val activity = task.executionStream()
                closeOpenSession(task.id, now)
                task.execution?.currentCycleId?.let { getCycle(it) }?.let { saveCycle(it.copy(endedAt = now)) }
                saveTask(task.copy(status = TaskStatus.DONE, completedAt = now, updatedAt = now,
                    execution = task.execution?.copy(state = DONE,
                        externalTrackedSeconds = trackedExternalSeconds(activity, now),
                        processingStartedAt = null, externalTrackingPaused = false,
                        checkAt = null, snoozeReason = null, blockerReason = null, currentCycleId = null)))
                event(activity.copy(id = stream.id), EventType.TASK_COMPLETED, now,
                    detail = TaskEventDetail.encode(task.id, task.title, task.status, TaskStatus.DONE))
            }
        }
        closeOpenSession(stream.id, now)
        getCurrentCycle(stream.id)?.let { saveCycle(it.copy(endedAt = now)) }
        val updated = stream.copy(
            state = DONE, completedAt = now, externalTrackingPaused = false,
            externalTrackedSeconds = trackedExternalSeconds(stream, now),
            processingStartedAt = null, checkAt = null, snoozedUntil = null, snoozeReason = null,
            activeTaskId = null, currentCycleId = null, updatedAt = now
        )
        saveSnapshot(updated, now, reason = DONE)
        persist(updated)
        event(stream, EventType.COMPLETED, now, from = stream.state, to = DONE, cycleId = stream.currentCycleId)
        ActionResult.Success(updated)
    }

    // ------------------------------------------------------------------ Context / notes

    override suspend fun updateContext(streamId: String, update: ContextUpdate): ActionResult<WorkStream> = run(streamId, allowContainer = true) { stream ->
        if (update.title != Field.Keep && update.title.applyTo(stream.title).isNullOrBlank())
            return@run ActionResult.Rejected(DomainError.EmptyTitle)
        if (update.title != Field.Keep && update.copy(title = Field.Keep).isEmpty) {
            val renamed = stream.copy(title = update.title.applyTo(stream.title)!!.trim())
            saveStream(renamed)
            return@run ActionResult.Success(renamed)
        }
        if (stream.state.isTerminal) return@run ActionResult.Rejected(DomainError.StreamAlreadyDone)
        if (update.blockerReason != Field.Keep && stream.state != BLOCKED) return@run ActionResult.Rejected(DomainError.NotBlocked)
        val now = clock.now()
        val updated = stream.copy(
            title = update.title.applyTo(stream.title)?.trim() ?: stream.title,
            lastHumanAction = update.lastHumanAction.applyTo(stream.lastHumanAction),
            waitingFor = update.waitingFor.applyTo(stream.waitingFor),
            nextHumanAction = update.nextHumanAction.applyTo(stream.nextHumanAction),
            blockerReason = update.blockerReason.applyTo(stream.blockerReason),
            updatedAt = now
        )
        saveSnapshot(updated, now, reason = updated.state, note = update.note)
        persist(updated)
        event(stream, EventType.CONTEXT_UPDATED, now, cycleId = stream.currentCycleId)
        update.note?.takeIf { it.isNotBlank() }?.let { event(stream, EventType.NOTE_ADDED, now, detail = it.trim()) }
        ActionResult.Success(updated)
    }

    override suspend fun addNote(streamId: String, text: String): ActionResult<WorkStream> = run(streamId, allowContainer = true) { stream ->
        if (text.isBlank()) return@run ActionResult.Rejected(DomainError.EmptyNote)
        if (stream.state.isTerminal) return@run ActionResult.Rejected(DomainError.StreamAlreadyDone)
        val now = clock.now()
        event(stream, EventType.NOTE_ADDED, now, cycleId = stream.currentCycleId, detail = text.trim())
        ActionResult.Success(stream)
    }

    // ------------------------------------------------------------------ Needs You priority

    override suspend fun reorderNeedsYou(streamId: String, position: Int): ActionResult<List<WorkStream>> = run(streamId) { stream ->
        if (stream.state != CHECK) return@run ActionResult.Rejected(DomainError.NotInNeedsYou)
        val move = NeedsYouOrder.planMove(allStreams(), streamId, position)
            ?: return@run ActionResult.Rejected(DomainError.NotInNeedsYou)
        // Ranks only — `updatedAt` is deliberately untouched so waiting time / urgency never move.
        move.changed.forEach { saveStream(it) }
        ActionResult.Success(move.order)
    }

    override suspend fun setNeedsYouPriority(streamId: String, position: Int, scope: PriorityScope): ActionResult<List<WorkStream>> {
        val moved = reorderNeedsYou(streamId, position)
        if (moved !is ActionResult.Success) return moved
        // The stored position is what the user asked for, clamped to the queue that accepted it.
        val effective = NeedsYouOrder.effectiveRank(moved.value, streamId) ?: position
        when (scope) {
            // "This time" is a move now: it never creates a durable policy, and (Phase 07) it never
            // deletes one either — an existing Always/Until keeps applying on the NEXT re-entry.
            PriorityScope.OneTime -> Unit
            else -> preferences.save(PriorityPreference(streamId, effective, scope, clock.now()))
        }
        return moved
    }

    override suspend fun priorityPreference(streamId: String): PriorityPreference? = preferences.get(streamId)

    override suspend fun clearPriorityPreference(streamId: String) = preferences.remove(streamId)

    /** Start-up hygiene: drop expired policies without needing any screen to be open. */
    override suspend fun cleanupExpiredPriorityPreferences(): Int = preferences.cleanupExpired(clock.now())

    /**
     * Re-entry policy: place a returning item at its preferred position when its preference is
     * still active, then drop an expired one. Conflicts are resolved by the queue itself — the
     * item being inserted gets the position it asks for and everyone else shifts, so two items may
     * both prefer position 1 while effective ranks stay unique.
     */
    private suspend fun WorkStreamWriter.applyPreferenceOnEntry(streamId: String, now: Instant) {
        // `activeFor` also drops an expired policy, so expiry never needs the UI.
        val pref = preferences.activeFor(streamId, now) ?: return
        val move = NeedsYouOrder.planMove(allStreams(), streamId, pref.preferredPosition) ?: return
        move.changed.forEach { saveStream(it) }
        val scope = pref.scope as? PriorityScope.NextReturns ?: return
        if (scope.remaining == 1) preferences.remove(streamId)
        else preferences.save(pref.copy(scope = scope.copy(remaining = scope.remaining - 1)))
    }


    // ================================================================ External work (Phase 10)

    override suspend fun startExternalWork(request: StartExternalWork): ActionResult<WorkStream> =
        run(if (repository.taskExecutionEnabled) request.workItemId ?: request.workStreamId else request.workStreamId) { stream ->
            if (repository.taskExecutionEnabled && ExecutionModeResolver.resolveCurrent(stream, allTasks().associateBy { it.id },
                    stream.projectId?.let { getProject(it)?.defaultExecutionMode }) != EffectiveExecutionMode.EXTERNAL)
                return@run ActionResult.Rejected(DomainError.NotExternalExecution)
            if (stream.state.isTerminal) return@run ActionResult.Rejected(DomainError.StreamAlreadyDone)
            if (stream.state != PROCESSING) requireTransition(stream, PROCESSING)?.let { return@run it }
            val now = clock.now()

            // The work item is identified by a stable id and must belong to this stream.
            val workItemId = request.workItemId
            if (workItemId != null && stream.activeTaskId != null && workItemId != stream.activeTaskId &&
                stream.state !in setOf(READY, DONE)) return@run ActionResult.Rejected(DomainError.AnotherTaskIsExecuting)
            if (workItemId != null) {
                val task = getTask(workItemId) ?: return@run ActionResult.Rejected(DomainError.TaskNotFound(workItemId))
                if (task.workStreamId != request.workStreamId && task.id != request.workStreamId)
                    return@run ActionResult.Rejected(DomainError.TaskNotInWorkStream)
                if (task.status.isTerminal) return@run ActionResult.Rejected(DomainError.TaskAlreadyClosed)
                val owner = task.workStreamId?.let { getStream(it) } ?: return@run ActionResult.Rejected(DomainError.OwnershipMismatch)
                if (repository.taskExecutionEnabled && ExecutionModeResolver.resolveTask(task, allTasks().associateBy { it.id }, owner,
                        task.projectId?.let { getProject(it)?.defaultExecutionMode }) != EffectiveExecutionMode.EXTERNAL)
                    return@run ActionResult.Rejected(DomainError.NotExternalExecution)
            }
            if (request.stages.any { it.title.isBlank() }) return@run ActionResult.Rejected(DomainError.EmptyTitle)

            // Stages describe the external process. The first one starts with the run.
            val created = request.stages.mapIndexed { index, s ->
                ExternalStage(
                    id = ids.newId("stg"),
                    workStreamId = stream.id,
                    title = s.title.trim(),
                    order = index,
                    expectedMinutes = s.expectedMinutes,
                    status = if (index == 0) ExternalStageStatus.IN_PROGRESS else ExternalStageStatus.PENDING,
                    startedAt = if (index == 0) now else null
                )
            }
            created.forEach { saveStage(it) }

            // checkAt is derived once, from timestamps - never a stored countdown.
            val checkAt = request.checkAt
                ?: request.checkInMinutes?.let { now.plus(Duration.ofMinutes(it)) }
                ?: created.firstOrNull()?.expected?.let { now.plus(it) }

            closeOpenSession(stream.id, now)
            val cycle = (getCurrentCycle(stream.id) ?: newCycle(stream, now)).copy(handedOffAt = now)
            saveCycle(cycle)

            val updated = stream.copy(
                state = PROCESSING,
                externalActorId = request.actor?.id ?: stream.externalActorId,
                tool = request.actor?.displayName ?: stream.tool,
                waitingFor = request.instruction ?: stream.waitingFor,
                activeTaskId = workItemId ?: stream.activeTaskId,
                externalTrackedSeconds = trackedExternalSeconds(stream, now),
                processingStartedAt = now,
                externalTrackingPaused = false,
                checkAt = checkAt,
                snoozedUntil = null,
                snoozeReason = null,
                currentCycleId = cycle.id,
                updatedAt = now
            )
            saveSnapshot(updated, now, reason = PROCESSING)
            persist(updated)
            event(stream, EventType.PROCESSING_STARTED, now, from = stream.state, to = PROCESSING,
                cycleId = cycle.id, detail = request.instruction ?: updated.waitingFor)
            ActionResult.Success(updated)
        }

    override suspend fun scheduleExternalCheck(streamId: String, checkAt: Instant): ActionResult<WorkStream> =
        continueProcessing(streamId, checkAt)

    override suspend fun markExternalStillRunning(streamId: String, checkAt: Instant): ActionResult<WorkStream> =
        stillRunning(streamId, checkAt)

    override suspend fun markExternalBlocked(streamId: String, reason: String?): ActionResult<WorkStream> =
        blockStream(streamId, reason)

    override suspend fun deferReadyResult(streamId: String, returnAt: Instant): ActionResult<WorkStream> =
        resultReadyLater(streamId, returnAt)

    override suspend fun markExternalResultReady(streamId: String): ActionResult<WorkStream> = run(streamId) { stream ->
        if (stream.state != CHECK && stream.state != PROCESSING) return@run ActionResult.Rejected(DomainError.NotAnExternalCheck)
        val now = clock.now()
        if (stream.state == PROCESSING) requireTransition(stream, CHECK)?.let { return@run it }
        // The external stage is finished; the human work item is NOT touched.
        ExternalStages.current(stagesOf(stream.id), stream.id)?.let { current ->
            saveStage(current.copy(status = ExternalStageStatus.DONE, completedAt = now))
        }
        val updated = stream.copy(
            state = CHECK,
            externalTrackedSeconds = trackedExternalSeconds(stream, now), externalTrackingPaused = false,
            processingStartedAt = null,
            checkAt = stream.checkAt ?: now,      // keep the due moment so the overdue timer stays honest
            snoozedUntil = null,
            snoozeReason = SnoozeReason.EXTERNAL_RESULT_READY,
            updatedAt = now
        )
        saveSnapshot(updated, now, reason = CHECK)
        persist(updated)
        event(stream, EventType.RESULT_READY, now, from = stream.state, to = CHECK)
        ActionResult.Success(updated)
    }

    override suspend fun focusExternalResult(streamId: String): ActionResult<WorkStream> {
        val stream = activityById(streamId) ?: return ActionResult.NotFound(streamId)
        val workItemId = stream.activeTaskId
        // Same work item, same Phase 09 focus rules - no second task, no second switching system.
        return if (workItemId != null) {
            when (val r = startFocus(workItemId)) {
                is ActionResult.Success -> ActionResult.Success(r.value.focused)
                is ActionResult.Rejected -> ActionResult.Rejected(r.reason)
                is ActionResult.NotFound -> ActionResult.NotFound(r.streamId)
                is ActionResult.Failure -> ActionResult.Failure(r.cause)
            }
        } else when (val r = resultReadyNow(streamId)) {
            is ActionResult.Success -> ActionResult.Success(r.value.focused)
            is ActionResult.Rejected -> ActionResult.Rejected(r.reason)
            is ActionResult.NotFound -> ActionResult.NotFound(r.streamId)
            is ActionResult.Failure -> ActionResult.Failure(r.cause)
        }
    }

    override suspend fun startNextExternalStage(streamId: String, checkAt: Instant?): ActionResult<WorkStream> =
        run(streamId) { stream ->
            if (stream.state.isTerminal) return@run ActionResult.Rejected(DomainError.StreamAlreadyDone)
            val now = clock.now()
            val all = stagesOf(stream.id)
            if (all.isEmpty()) return@run ActionResult.Rejected(DomainError.NotExternalWork)
            // The stage to start is the one that is merely PLANNED; a stage still running is
            // completed first and its successor begins. Nothing is ever skipped.
            val current = ExternalStages.current(all, stream.id)
            val next = when {
                current == null -> null
                current.status == ExternalStageStatus.PENDING -> current
                else -> ExternalStages.next(all, stream.id, current)
            } ?: return@run ActionResult.Rejected(DomainError.NoNextStage)
            if (stream.state != PROCESSING) requireTransition(stream, PROCESSING)?.let { return@run it }

            current?.takeIf { it.id != next.id && it.status != ExternalStageStatus.DONE }
                ?.let { saveStage(it.copy(status = ExternalStageStatus.DONE, completedAt = now)) }
            saveStage(next.copy(status = ExternalStageStatus.IN_PROGRESS, startedAt = now))

            val due = checkAt ?: next.expected?.let { now.plus(it) }
            closeOpenSession(stream.id, now)
            val updated = stream.copy(
                state = PROCESSING,
                externalTrackedSeconds = trackedExternalSeconds(stream, now),
                processingStartedAt = now,
                externalTrackingPaused = false,
                checkAt = due,
                snoozedUntil = null,
                snoozeReason = null,
                updatedAt = now
            )
            saveSnapshot(updated, now, reason = PROCESSING)
            persist(updated)
            event(stream, EventType.PROCESSING_STARTED, now, from = stream.state, to = PROCESSING, detail = next.title)
            ActionResult.Success(updated)
        }

    override suspend fun externalStages(streamId: String): List<ExternalStage> = repository.getStages(streamId)

    // ------------------------------------------------------------------ Shared mechanics

    /**
     * The one save path for attention transitions: an explicit Needs You rank belongs to the current
     * CHECK membership, so any stream saved in another state leaves unranked (a later return enters
     * Needs You at its longest-waiting position).
     */
    private suspend fun WorkStreamWriter.persist(stream: WorkStream) {
        val leaving = stream.state != CHECK
        saveStream(if (leaving && stream.attentionRank != null) stream.copy(attentionRank = null) else stream)
        // An item leaving Needs You closes the gap it left: the remaining ranked block re-densifies
        // to 1..k. Effective ranks (positions) are always dense anyway; this keeps the stored keys
        // dense too, so the persisted queue and what the user sees can never drift apart.
        if (leaving) NeedsYouOrder.normalize(allStreams()).forEach { saveStream(it) }
    }

    /** Load → run inside one transaction → map unexpected throwables to [ActionResult.Failure]. */
    private suspend fun <T> run(
        streamId: String,
        allowContainer: Boolean = false,
        block: suspend WorkStreamWriter.(WorkStream) -> ActionResult<T>
    ): ActionResult<T> = try {
        repository.transaction {
            val writer = if (repository.taskExecutionEnabled) TaskExecutionWriter(this) else this
            val stream = writer.getStream(streamId) ?: return@transaction ActionResult.NotFound(streamId)
            if (repository.taskExecutionEnabled && getTask(streamId)?.let { it.workStreamId == null } == true)
                return@transaction ActionResult.Rejected(DomainError.OwnershipMismatch)
            if (repository.taskExecutionEnabled && !allowContainer && !ExecutionEligibility.isLeaf(streamId, writer.allTasks()))
                return@transaction ActionResult.Rejected(DomainError.PlacementRejected("This item contains children. Select a leaf task to manage execution."))
            writer.block(stream)
        }
    } catch (e: Exception) {
        ActionResult.Failure(e)
    }

    private fun requireTransition(stream: WorkStream, to: WorkStreamState): ActionResult<Nothing>? = when {
        stream.state.isTerminal -> ActionResult.Rejected(DomainError.StreamAlreadyDone)
        !WorkStreamTransitions.canTransition(stream.state, to) ->
            ActionResult.Rejected(DomainError.InvalidTransition(stream.state, to))
        else -> null
    }

    private suspend fun WorkStreamWriter.closeOpenSession(streamId: String, now: Instant) {
        getOpenFocusSession(streamId)?.let { saveFocusSession(it.copy(endedAt = now)) }
    }

    private suspend fun WorkStreamWriter.belongsToProject(item: WorkStream, projectId: String): Boolean {
        val task = getTask(item.id)
        val owner = task?.workStreamId?.let { getStream(it) }
        return (owner?.projectId ?: task?.projectId ?: item.projectId) == projectId
    }

    private suspend fun WorkStreamWriter.newCycle(stream: WorkStream, now: Instant): Cycle {
        val cycle = Cycle(ids.newId("cyc"), stream.id, number = stream.cycleCount + 1, startedAt = now)
        saveCycle(cycle)
        return cycle
    }

    private suspend fun WorkStreamWriter.saveSnapshot(
        stream: WorkStream, now: Instant, reason: WorkStreamState, note: String? = null
    ) {
        saveSnapshot(
            ContextSnapshot(
                id = ids.newId("snap"),
                workStreamId = stream.id,
                cycleId = stream.currentCycleId,
                createdAt = now,
                reason = reason,
                lastHumanAction = stream.lastHumanAction,
                waitingFor = stream.waitingFor,
                nextHumanAction = stream.nextHumanAction,
                checkAt = stream.checkAt,
                contextLabel = stream.title,
                note = note,
                taskId = stream.activeTaskId
            )
        )
    }

    private fun trackedExternalSeconds(stream: WorkStream, now: Instant): Long =
        ExternalTiming.seconds(stream, now)

    private suspend fun WorkStreamWriter.event(
        stream: WorkStream, type: EventType, now: Instant,
        from: WorkStreamState? = null, to: WorkStreamState? = null,
        cycleId: String? = stream.currentCycleId, detail: String? = null
    ) {
        appendEvent(WorkStreamEvent(ids.newId("ev"), stream.id, type, now, cycleId, from, to, detail))
    }
}

package com.virlin.app.domain.action

import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.ContextSnapshot
import com.virlin.app.domain.model.Cycle
import com.virlin.app.domain.model.EffectiveExecutionMode
import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.ExecutionModeResolver
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
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
    private val ids: IdProvider
) : VirlinActions {

    private val structure = StructureActions(repository, clock, ids)
    private val capture = CaptureActions(repository, clock, ids, structure)
    private val notes = NoteActions(repository, clock, ids, capture)
    private val prompts = PromptActions(repository, clock, ids, capture)
    private val attachments = AttachmentActions(repository, clock, ids, capture)
    private val voices = VoiceActions(repository, clock, ids, capture)

    // ------------------------------------------------------------------ Structure (delegated)
    override suspend fun createProject(request: CreateProject) = structure.createProject(request)
    override suspend fun updateProject(projectId: String, update: ProjectUpdate) = structure.updateProject(projectId, update)
    override suspend fun completeProject(projectId: String) = structure.completeProject(projectId)
    override suspend fun createWorkStream(request: CreateWorkStream): ActionResult<WorkStream> = try {
        repository.transaction {
            if (request.title.isBlank()) return@transaction ActionResult.Rejected(DomainError.EmptyTitle)
            request.projectId?.let { pid -> getProject(pid) ?: return@transaction ActionResult.Rejected(DomainError.ProjectNotFound(pid)) }
            if (request.executionPreference == ExecutionPreference.INHERIT && request.projectId == null) {
                return@transaction ActionResult.Rejected(DomainError.InheritRequiresProject)
            }
            val now = clock.now()
            val stream = WorkStream(
                id = request.id ?: ids.newId("ws"), title = request.title.trim(), projectId = request.projectId,
                tool = request.tool?.takeIf { it.isNotBlank() },
                executionPreference = request.executionPreference, state = READY,
                priority = request.priority, nextHumanAction = request.nextHumanAction?.takeIf { it.isNotBlank() },
                createdAt = now, updatedAt = now
            )
            persist(stream)
            event(stream, EventType.STREAM_CREATED, now, to = READY, cycleId = null, detail = request.executionPreference.name)
            ActionResult.Success(stream)
        }
    } catch (e: Exception) { ActionResult.Failure(e) }

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
        promptId: String?
    ) = prompts.createPrompt(title, description, tags, blocks, context, captureId, promptId)

    override suspend fun savePrompt(
        captureItemId: String,
        title: String?,
        description: String?,
        tags: List<String>,
        blocks: List<com.virlin.app.domain.model.NoteBlock>
    ) = prompts.savePrompt(captureItemId, title, description, tags, blocks)

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
    override suspend fun cancelTask(taskId: String) = structure.cancelTask(taskId)
    override suspend fun setActiveTask(streamId: String, taskId: String?) = structure.setActiveTask(streamId, taskId)
    override suspend fun activePath(streamId: String) = structure.activePath(streamId)
    override suspend fun nextTaskCandidate(streamId: String) = structure.nextTaskCandidate(streamId)

    // ------------------------------------------------------------------ Focus

    override suspend fun focusStream(streamId: String): ActionResult<FocusOutcome> = run(streamId) { target ->
        if (target.state == FOCUS) return@run ActionResult.Rejected(DomainError.AlreadyFocused)
        requireTransition(target, FOCUS)?.let { return@run it }

        val now = clock.now()
        // Single human Focus: displace the current one in the same transaction.
        val previous = getActiveFocus()?.takeIf { it.id != target.id }
        val displaced = previous?.let { prev ->
            closeOpenSession(prev.id, now)
            saveSnapshot(prev, now, reason = READY)
            val moved = prev.copy(state = READY, updatedAt = now)
            persist(moved)
            event(prev, EventType.FOCUS_LEFT, now, from = FOCUS, to = READY, detail = "displaced by ${target.id}")
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
            processingStartedAt = null,
            checkAt = null,
            snoozedUntil = null,
            snoozeReason = null,
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
        requireTransition(stream, CHECK)?.let { return@run it }
        val now = clock.now()
        val updated = stream.copy(state = CHECK, snoozedUntil = null, updatedAt = now)
        persist(updated)
        event(stream, EventType.CHECK_DUE, now, from = stream.state, to = CHECK, cycleId = stream.currentCycleId)
        ActionResult.Success(updated)
    }

    override suspend fun continueProcessing(streamId: String, checkAt: Instant): ActionResult<WorkStream> = run(streamId) { stream ->
        val now = clock.now()
        if (!checkAt.isAfter(now)) return@run ActionResult.Rejected(DomainError.InvalidSnoozeTime)
        when (stream.state) {
            PROCESSING -> Unit // updating the check time in place is not a transition
            else -> requireTransition(stream, PROCESSING)?.let { return@run it }
        }
        val updated = stream.copy(
            state = PROCESSING,
            processingStartedAt = stream.processingStartedAt ?: now,
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
        val stream = repository.getStream(streamId) ?: return ActionResult.NotFound(streamId)
        if (stream.state != PROCESSING && !(stream.state == CHECK && stream.snoozeReason == null))
            return ActionResult.Rejected(DomainError.NotAnExternalCheck)
        // continueProcessing validates the time and keeps processing continuity; no FocusSession.
        return continueProcessing(streamId, checkAt)
    }

    override suspend fun resultReadyNow(streamId: String): ActionResult<FocusOutcome> {
        val stream = repository.getStream(streamId) ?: return ActionResult.NotFound(streamId)
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
            state = READY, processingStartedAt = null, checkAt = null, snoozedUntil = null, updatedAt = now
        )
        saveSnapshot(updated, now, reason = READY)
        persist(updated)
        event(stream, EventType.READY, now, from = stream.state, to = READY)
        ActionResult.Success(updated)
    }

    override suspend fun pauseStream(streamId: String): ActionResult<WorkStream> = run(streamId) { stream ->
        requireTransition(stream, PAUSED)?.let { return@run it }
        val now = clock.now()
        closeOpenSession(stream.id, now)
        val updated = stream.copy(state = PAUSED, checkAt = null, snoozedUntil = null, updatedAt = now)
        saveSnapshot(updated, now, reason = PAUSED)
        persist(updated)
        event(stream, EventType.PAUSED, now, from = stream.state, to = PAUSED)
        ActionResult.Success(updated)
    }

    override suspend fun blockStream(streamId: String, reason: String?): ActionResult<WorkStream> = run(streamId) { stream ->
        requireTransition(stream, BLOCKED)?.let { return@run it }
        val now = clock.now()
        closeOpenSession(stream.id, now)
        val updated = stream.copy(
            state = BLOCKED, blockerReason = reason ?: stream.blockerReason,
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

    override suspend fun completeStream(streamId: String): ActionResult<WorkStream> = run(streamId) { stream ->
        requireTransition(stream, DONE)?.let { return@run it }
        val now = clock.now()
        closeOpenSession(stream.id, now)
        getCurrentCycle(stream.id)?.let { saveCycle(it.copy(endedAt = now)) }
        val updated = stream.copy(
            state = DONE, completedAt = now,
            processingStartedAt = null, checkAt = null, snoozedUntil = null, updatedAt = now
        )
        saveSnapshot(updated, now, reason = DONE)
        persist(updated)
        event(stream, EventType.COMPLETED, now, from = stream.state, to = DONE, cycleId = stream.currentCycleId)
        ActionResult.Success(updated)
    }

    // ------------------------------------------------------------------ Context / notes

    override suspend fun updateContext(streamId: String, update: ContextUpdate): ActionResult<WorkStream> = run(streamId) { stream ->
        if (stream.state.isTerminal) return@run ActionResult.Rejected(DomainError.StreamAlreadyDone)
        val now = clock.now()
        val updated = stream.copy(
            lastHumanAction = update.lastHumanAction.applyTo(stream.lastHumanAction),
            waitingFor = update.waitingFor.applyTo(stream.waitingFor),
            nextHumanAction = update.nextHumanAction.applyTo(stream.nextHumanAction),
            updatedAt = now
        )
        saveSnapshot(updated, now, reason = updated.state, note = update.note)
        persist(updated)
        event(stream, EventType.CONTEXT_UPDATED, now, cycleId = stream.currentCycleId)
        update.note?.takeIf { it.isNotBlank() }?.let { event(stream, EventType.NOTE_ADDED, now, detail = it.trim()) }
        ActionResult.Success(updated)
    }

    override suspend fun addNote(streamId: String, text: String): ActionResult<WorkStream> = run(streamId) { stream ->
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

    // ------------------------------------------------------------------ Shared mechanics

    /**
     * The one save path for attention transitions: an explicit Needs You rank belongs to the current
     * CHECK membership, so any stream saved in another state leaves unranked (a later return enters
     * Needs You at its longest-waiting position).
     */
    private suspend fun WorkStreamWriter.persist(stream: WorkStream) =
        saveStream(if (stream.state == CHECK || stream.attentionRank == null) stream else stream.copy(attentionRank = null))

    /** Load → run inside one transaction → map unexpected throwables to [ActionResult.Failure]. */
    private suspend fun <T> run(
        streamId: String,
        block: suspend WorkStreamWriter.(WorkStream) -> ActionResult<T>
    ): ActionResult<T> = try {
        repository.transaction {
            val stream = getStream(streamId) ?: return@transaction ActionResult.NotFound(streamId)
            block(stream)
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

    private suspend fun WorkStreamWriter.event(
        stream: WorkStream, type: EventType, now: Instant,
        from: WorkStreamState? = null, to: WorkStreamState? = null,
        cycleId: String? = stream.currentCycleId, detail: String? = null
    ) {
        appendEvent(WorkStreamEvent(ids.newId("ev"), stream.id, type, now, cycleId, from, to, detail))
    }
}

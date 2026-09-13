package com.virlin.app.domain.action

import com.virlin.app.domain.model.ContextSnapshot
import com.virlin.app.domain.model.Priority
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import java.time.Duration
import java.time.Instant

/**
 * # The unified Virlin Action Layer
 *
 * The ONE product-facing API for changing WorkStream state. The Now UI, Agent Control,
 * notifications, a future deterministic command interpreter, voice, and AI all call these
 * same operations. Each action expresses user INTENT, validates the transition centrally,
 * preserves context, records history, and returns a structured [ActionResult].
 *
 * There is deliberately no `setState(id, state)`.
 */
interface VirlinActions {

    /**
     * Human attention moves to this stream. Enforces the single-Focus invariant: any stream
     * currently in FOCUS is moved to READY (its session closed, its context snapshotted)
     * in the same transaction.
     */
    suspend fun focusStream(streamId: String): ActionResult<FocusOutcome>

    /**
     * The human is done for now and hands the work to an external tool/process.
     * Requires FOCUS. Closes the FocusSession, updates the current Cycle, snapshots context,
     * moves to PROCESSING, and frees human Focus.
     */
    suspend fun handOffStream(
        streamId: String,
        waitingFor: String? = null,
        nextHumanAction: String? = null,
        checkAt: Instant? = null
    ): ActionResult<WorkStream>

    /**
     * The user is looking at an external process. Read-oriented: returns the stream and its
     * latest context and records a CHECKED event. Does NOT assume the result is ready — the
     * user's next intent (still running / ready / blocked) is a separate action.
     */
    suspend fun checkStream(streamId: String): ActionResult<CheckOutcome>

    /** A planned check time has arrived (PROCESSING/SNOOZED → CHECK). Scheduling hook. */
    suspend fun checkDue(streamId: String): ActionResult<WorkStream>

    /**
     * After a check: "still running" — let the external process keep going and look again
     * at [checkAt]. Valid from CHECK (→ PROCESSING) and from PROCESSING (updates the check
     * time). Distinct from [handOffStream], which is the human leaving FOCUS.
     */
    suspend fun continueProcessing(streamId: String, checkAt: Instant): ActionResult<WorkStream>

    /** Intentionally defer attention until [until] (must be in the future). */
    suspend fun snoozeStream(streamId: String, until: Instant): ActionResult<WorkStream>

    // ---------------------------------------------------------------- Finalized attention exits

    /**
     * LEAVE — the human stops paying attention here WITHOUT handing work to a process.
     * Requires FOCUS. Closes the FocusSession, snapshots context (incl. the active task),
     * keeps `activeTaskId`, frees the human Focus slot. `returnAt == null` → READY;
     * `returnAt` in the future → SNOOZED with [SnoozeReason.HUMAN_RETURN]. Never PROCESSING.
     */
    suspend fun leaveFocus(streamId: String, returnAt: Instant? = null): ActionResult<WorkStream>

    /**
     * After an external check: "still running" — keep PROCESSING and look again at [checkAt].
     * Requires a due external check (CHECK without a snooze reason) or PROCESSING. Opens no
     * FocusSession.
     */
    suspend fun stillRunning(streamId: String, checkAt: Instant): ActionResult<WorkStream>

    /**
     * After an external check: the result is ready and the human looks now → FOCUS.
     * Clears the processing fields; single-Focus displacement applies.
     */
    suspend fun resultReadyNow(streamId: String): ActionResult<FocusOutcome>

    /**
     * After an external check: the result is ready but the human will look at [returnAt] →
     * SNOOZED with [SnoozeReason.EXTERNAL_RESULT_READY]. Processing fields cleared: the
     * external work is finished. Never back to PROCESSING.
     */
    suspend fun resultReadyLater(streamId: String, returnAt: Instant): ActionResult<WorkStream>

    /**
     * Push a timed return later WITHOUT changing why it exists (human return or result
     * ready). Valid while SNOOZED or once that return is due (CHECK carrying a snooze reason).
     */
    suspend fun deferReturn(streamId: String, returnAt: Instant): ActionResult<WorkStream>

    /** No active processing; resumable when useful. Clears obsolete timers. */
    suspend fun markReady(streamId: String): ActionResult<WorkStream>

    /** "I am choosing not to work on this now." No wake time. */
    suspend fun pauseStream(streamId: String): ActionResult<WorkStream>

    /** "I cannot meaningfully proceed." */
    suspend fun blockStream(streamId: String, reason: String? = null): ActionResult<WorkStream>

    /** Blocker resolved → READY. Never auto-focuses. */
    suspend fun unblockStream(streamId: String): ActionResult<WorkStream>

    /** Finished. Terminal. Closes session and cycle, clears timers, keeps history. */
    suspend fun completeStream(streamId: String): ActionResult<WorkStream>

    /** Partial update of external working memory; omitted fields are preserved. */
    suspend fun updateContext(streamId: String, update: ContextUpdate): ActionResult<WorkStream>

    /** Additive note → NOTE_ADDED history entry. */
    suspend fun addNote(streamId: String, text: String): ActionResult<WorkStream>

    // ================================================================ Structure: Project

    suspend fun createProject(request: CreateProject): ActionResult<Project>
    suspend fun updateProject(projectId: String, update: ProjectUpdate): ActionResult<Project>
    suspend fun completeProject(projectId: String): ActionResult<Project>

    // ================================================================ Capture (Pass 10)

    /** Preserve something verbatim. Touches no WorkStream, Focus, task or alarm. */
    suspend fun createCapture(request: CreateCapture): ActionResult<CaptureItem>
    suspend fun updateCapture(id: String, update: CaptureUpdate): ActionResult<CaptureItem>
    /** ATTACH: explicit context; the item stays a capture. */
    suspend fun attachCapture(id: String, context: CaptureContext): ActionResult<CaptureItem>
    suspend fun archiveCapture(id: String): ActionResult<CaptureItem>
    suspend fun restoreCapture(id: String): ActionResult<CaptureItem>
    /** CONVERT: real Task via the structure rules + capture ORGANIZED, atomically; once only. */
    suspend fun convertCaptureToTask(id: String, target: CaptureTaskTarget): ActionResult<Task>

    // ================================================================ Structure: WorkStream

    /**
     * Create a WorkStream (Pass 9). Project is optional; [CreateWorkStream.mode] is explicit —
     * never inferred from the title or a tool name. New streams enter READY: creation is not
     * focus and not a hand-off.
     */
    suspend fun createWorkStream(request: CreateWorkStream): ActionResult<WorkStream>

    // ================================================================ Structure: Task

    /**
     * Create a Task. Ownership: [CreateTask.parentTaskId] wins (project/stream inherited and
     * must match if supplied); else [CreateTask.workStreamId] (project derived from the
     * stream); else a standalone Project task.
     */
    suspend fun createTask(request: CreateTask): ActionResult<Task>
    /** Convenience: create a child under [parentTaskId], inheriting its ownership. */
    suspend fun addSubtask(parentTaskId: String, title: String, estimatedEffort: Duration? = null): ActionResult<Task>
    suspend fun updateTask(taskId: String, update: TaskUpdate): ActionResult<Task>
    /**
     * Close a leaf or parent task. If it was a WorkStream's active task, `activeTaskId` is
     * cleared (never auto-advanced). Parent status is NOT mutated — parent completeness is
     * derived by [com.virlin.app.domain.progress.ProgressCalculator].
     */
    suspend fun completeTask(taskId: String): ActionResult<Task>
    /**
     * Abandon a task: terminal but NOT completed. Removed from active planned scope (excluded
     * from progress numerator and denominator). Clears an active task; never auto-advances.
     */
    suspend fun cancelTask(taskId: String): ActionResult<Task>

    // ================================================================ Structure: Active task

    /** Point a WorkStream at the exact Task being worked on. Validated; null clears. */
    suspend fun setActiveTask(streamId: String, taskId: String?): ActionResult<WorkStream>
    suspend fun clearActiveTask(streamId: String): ActionResult<WorkStream> = setActiveTask(streamId, null)

    /** Derived active path: the task, then its parents. Empty if no active task. */
    suspend fun activePath(streamId: String): ActionResult<List<Task>>
    /** Deterministic candidate: first open leaf in sibling order under the stream. */
    suspend fun nextTaskCandidate(streamId: String): ActionResult<Task?>
}

// ================================================================ Capture (Pass 10)

/** Explicit optional context. All null = global Inbox. Never inferred from content. */
data class CaptureContext(val projectId: String? = null, val workStreamId: String? = null, val taskId: String? = null) {
    val isEmpty: Boolean get() = projectId == null && workStreamId == null && taskId == null
    companion object { val None = CaptureContext() }
}

data class CreateCapture(
    val type: CaptureType,
    /** NOTE/PROMPT text (verbatim, line breaks kept); LINK's optional note. */
    val content: String = "",
    val title: String? = null,
    /** LINK only. Stored, never fetched. */
    val sourceUrl: String? = null,
    val context: CaptureContext = CaptureContext.None,
    val id: String? = null
)

data class CaptureUpdate(
    val content: Field<String> = Field.Keep,
    val title: Field<String> = Field.Keep,
    val sourceUrl: Field<String> = Field.Keep
)

/** Where a converted capture's Task goes: exactly like CREATE mode (WorkStream / Project / parent Task). */
data class CaptureTaskTarget(val workStreamId: String? = null, val projectId: String? = null, val parentTaskId: String? = null)

data class CreateProject(
    val title: String,
    val description: String? = null,
    val priority: Priority = Priority.NORMAL,
    val dueAt: Instant? = null,
    val estimatedEffort: Duration? = null,
    /** Optional explicit id (tests / seeding). */
    val id: String? = null
)

data class ProjectUpdate(
    val title: Field<String> = Field.Keep,
    val description: Field<String> = Field.Keep,
    val priority: Field<Priority> = Field.Keep,
    val dueAt: Field<Instant> = Field.Keep,
    val estimatedEffort: Field<Duration> = Field.Keep
)

data class CreateWorkStream(
    val title: String,
    val projectId: String? = null,
    val mode: WorkStreamMode = WorkStreamMode.HUMAN,
    /** External tool / working context label, e.g. "Claude". Display metadata only. */
    val tool: String? = null,
    val nextHumanAction: String? = null,
    val priority: Priority = Priority.NORMAL,
    /** Optional explicit id (tests / seeding). */
    val id: String? = null
)

data class CreateTask(
    val title: String,
    val projectId: String? = null,
    val workStreamId: String? = null,
    val parentTaskId: String? = null,
    val description: String? = null,
    val estimatedEffort: Duration? = null,
    val dueAt: Instant? = null,
    val reminderAt: Instant? = null,
    val priority: Priority = Priority.NORMAL,
    val order: Int? = null,
    val id: String? = null
)

data class TaskUpdate(
    val title: Field<String> = Field.Keep,
    val description: Field<String> = Field.Keep,
    val notes: Field<String> = Field.Keep,
    val estimatedEffort: Field<Duration> = Field.Keep,
    val dueAt: Field<Instant> = Field.Keep,
    val reminderAt: Field<Instant> = Field.Keep,
    val priority: Field<Priority> = Field.Keep,
    val order: Field<Int> = Field.Keep,
    /** TODO ↔ IN_PROGRESS only; use completeTask to close. */
    val inProgress: Field<Boolean> = Field.Keep
)

data class FocusOutcome(
    val focused: WorkStream,
    /** The stream that was in FOCUS before and was moved to READY, if any. */
    val displaced: WorkStream?
)

data class CheckOutcome(
    val stream: WorkStream,
    val latestSnapshot: ContextSnapshot?
)

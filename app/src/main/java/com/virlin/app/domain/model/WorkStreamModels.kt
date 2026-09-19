package com.virlin.app.domain.model

import java.time.Duration
import java.time.Instant

/**
 * # Virlin domain model
 *
 * The central persistent object is the [WorkStream]: an ongoing chain of work that passes
 * through many human ↔ external-process [Cycle]s. It is NOT a task. Human attention is
 * modelled as [FocusSession]s (timestamps, never counters); mental state is preserved as
 * [ContextSnapshot]s; history is an append-only stream of semantic [WorkStreamEvent]s.
 *
 * The authoritative current state is the [WorkStream] row. Events are history, not
 * event-sourcing.
 */

/** Canonical attention states. See docs/DEVELOPMENT_STATUS.md for semantics. */
enum class WorkStreamState {
    /** A human is actively working on it. Normally at most ONE stream is here. */
    FOCUS,
    /** An external tool/process is working. Consumes NO human attention. Many allowed. */
    PROCESSING,
    /** The planned check time has arrived / attention is requested. */
    CHECK,
    /** Can be worked on when appropriate. */
    READY,
    /** Intentionally deferred until a time. */
    SNOOZED,
    /** Cannot advance because of a blocker. */
    BLOCKED,
    /** Intentionally inactive with no immediate intention to continue. */
    PAUSED,
    /** Finished. Terminal. */
    DONE;

    val isTerminal: Boolean get() = this == DONE
}

enum class Priority { LOW, NORMAL, HIGH }

/**
 * @deprecated Use [EffectiveExecutionMode]. Kept as a typealias so existing Create/command
 * call sites that mean HUMAN/EXTERNAL continue to compile during the execution-responsibility
 * migration. Prefer [ExecutionPreference] for stored fields and [ExecutionModeResolver] for
 * eligibility.
 */
@Deprecated(
    message = "Use EffectiveExecutionMode",
    replaceWith = ReplaceWith("EffectiveExecutionMode", "com.virlin.app.domain.model.EffectiveExecutionMode")
)
typealias WorkStreamMode = EffectiveExecutionMode

/**
 * Why a stream is SNOOZED — decides the wording and actions when the time is due.
 * HUMAN_RETURN: "I'll come back to this" (Leave with a return time).
 * EXTERNAL_RESULT_READY: the external work has FINISHED; the human chose to look later.
 */
enum class SnoozeReason { HUMAN_RETURN, EXTERNAL_RESULT_READY }

data class WorkStream(
    val id: String,
    val title: String,
    val projectId: String? = null,
    /** External tool / working context, e.g. "Claude", "Codex". */
    val tool: String? = null,
    /**
     * Stored execution preference (INHERIT / HUMAN / EXTERNAL). Resolve with
     * [ExecutionModeResolver] — never treat this alone as current Hand Off eligibility when
     * an active Task may override.
     */
    val executionPreference: ExecutionPreference = ExecutionPreference.HUMAN,
    val state: WorkStreamState,
    val priority: Priority = Priority.NORMAL,
    val pinned: Boolean = false,

    // ---- External working memory (also captured in ContextSnapshot on every exit)
    val lastHumanAction: String? = null,
    val waitingFor: String? = null,
    val nextHumanAction: String? = null,
    val blockerReason: String? = null,

    // ---- Timing (timestamps are the source of truth; there are no counters here)
    val processingStartedAt: Instant? = null,
    /** When to look again. Meaningful for PROCESSING and SNOOZED. */
    val checkAt: Instant? = null,
    /** Set only while SNOOZED. Distinct from a PROCESSING check time. */
    val snoozedUntil: Instant? = null,
    /** Set while SNOOZED (and kept through the due CHECK) so the return is understood. */
    val snoozeReason: SnoozeReason? = null,

    val currentCycleId: String? = null,
    val cycleCount: Int = 0,

    /**
     * The exact Task being worked on, if any. Source of truth for the active path — the
     * ancestry is DERIVED from Task.parentTaskId, never stored. Independent of attention
     * state: a PROCESSING stream still remembers which Task the external work concerns.
     */
    val activeTaskId: String? = null,

    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null
)

/** One human ↔ external-process loop inside a WorkStream. */
data class Cycle(
    val id: String,
    val workStreamId: String,
    val number: Int,
    val startedAt: Instant,
    /** Set when the human hands the work off to the external process. */
    val handedOffAt: Instant? = null,
    /** Set when the loop closes (result reviewed / stream completed). */
    val endedAt: Instant? = null
)

/** Real human-attention time. Duration is derived from timestamps, never persisted ticks. */
data class FocusSession(
    val id: String,
    val workStreamId: String,
    val cycleId: String?,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    /** Captured when the session begins; historical attribution never rewrites. */
    val taskId: String? = null
) {
    val isOpen: Boolean get() = endedAt == null

    /** Duration so far, or the closed duration. */
    fun duration(now: Instant): Duration = Duration.between(startedAt, endedAt ?: now)
}

/**
 * Reconstructs mental state on return:
 * where am I · what happened last · what am I waiting for · when to look again · what next.
 */
data class ContextSnapshot(
    val id: String,
    val workStreamId: String,
    val cycleId: String?,
    val createdAt: Instant,
    /** Why the snapshot was taken — the state the stream moved INTO. */
    val reason: WorkStreamState,
    val lastHumanAction: String?,
    val waitingFor: String?,
    val nextHumanAction: String?,
    val checkAt: Instant?,
    val contextLabel: String? = null,
    val note: String? = null,
    /** The exact work item to return to. Only the id — the path is derived. */
    val taskId: String? = null
)

enum class EventType {
    STREAM_CREATED,
    FOCUS_STARTED,
    FOCUS_LEFT,
    HANDOFF,
    PROCESSING_STARTED,
    /** Human left Focus (Leave). */
    LEFT,
    /** External check outcome: the process has finished. */
    RESULT_READY,
    /** A return time was pushed later without changing why. */
    RETURN_DEFERRED,
    CHECKED,
    CHECK_DUE,
    SNOOZED,
    READY,
    BLOCKED,
    UNBLOCKED,
    PAUSED,
    RESUMED,
    CONTEXT_UPDATED,
    NOTE_ADDED,
    COMPLETED,
    // ---- Structure (Task / active-task) history on the owning WorkStream
    TASK_CREATED,
    TASK_UPDATED,
    TASK_COMPLETED,
    ACTIVE_TASK_SET,
    ACTIVE_TASK_CLEARED
}

/** Append-only semantic history. Not event sourcing. */
data class WorkStreamEvent(
    val id: String,
    val workStreamId: String,
    val type: EventType,
    val at: Instant,
    val cycleId: String? = null,
    val fromState: WorkStreamState? = null,
    val toState: WorkStreamState? = null,
    /** Free text where the event carries one (a note, a blocker reason, a check time). */
    val detail: String? = null
)

/**
 * Pure attention projection for a moment in time. Does NOT mutate anything: a PROCESSING or
 * SNOOZED stream whose check time has passed *presents* as CHECK. Future scheduling logic
 * will make that transition real via the Action Layer.
 */
fun effectiveAttentionState(stream: WorkStream, now: Instant): WorkStreamState {
    val due = stream.checkAt?.let { !now.isBefore(it) } ?: false
    return when (stream.state) {
        WorkStreamState.PROCESSING, WorkStreamState.SNOOZED -> if (due) WorkStreamState.CHECK else stream.state
        else -> stream.state
    }
}

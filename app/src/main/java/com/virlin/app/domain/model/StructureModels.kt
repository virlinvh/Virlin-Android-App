package com.virlin.app.domain.model

import java.time.Duration
import java.time.Instant

/**
 * # Work-structure hierarchy
 *
 * ```
 * PROJECT ── WORKSTREAM ── TASK ── TASK ── TASK …
 *        └── TASK (standalone)
 * ```
 *
 * [Project] is an organizational container with planning metadata. It never takes part in
 * attention state. [WorkStream] stays the runtime/attention object. [Task] is a recursively
 * nestable planning/execution node — there are no Stage/Step/Substep types; every level is
 * a Task with a [Task.parentTaskId].
 */

enum class ProjectStatus { ACTIVE, PAUSED, DONE, ARCHIVED }

data class Project(
    val id: String,
    val title: String,
    val description: String? = null,
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val priority: Priority = Priority.NORMAL,
    val dueAt: Instant? = null,
    val estimatedEffort: Duration? = null,
    /**
     * Default execution mode for descendants that INHERIT. Projects never INHERIT —
     * they are the hierarchy root default. Not an attention/executable state.
     */
    val defaultExecutionMode: EffectiveExecutionMode = EffectiveExecutionMode.HUMAN,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null
)

/** Deliberately simpler than WorkStream state: no PROCESSING/CHECK/SNOOZED/BLOCKED here. */
enum class TaskStatus {
    TODO, IN_PROGRESS, DONE, CANCELLED;

    /** No further work will happen. DONE and CANCELLED. */
    val isTerminal: Boolean get() = this == DONE || this == CANCELLED
    /** Successfully completed work. ONLY DONE. Cancelled is terminal but never "completed". */
    val isCompleted: Boolean get() = this == DONE
    /** Still part of the active planned scope (counts in progress denominators). */
    val isActivePlanned: Boolean get() = this != CANCELLED
}

data class Task(
    val id: String,
    val title: String,
    val description: String? = null,
    /**
     * Ownership. A task is rooted EITHER in a WorkStream ([workStreamId] != null) OR directly
     * in a Project (standalone: [workStreamId] == null, [projectId] != null). Never ownerless.
     *
     * For WorkStream tasks [projectId] is a DERIVED convenience copy of the stream's optional
     * project at creation (null when the stream has no project) — the WorkStream relationship
     * is the source of truth and Task.projectId may never disagree with it.
     */
    val projectId: String? = null,
    /** Non-null = belongs to that WorkStream (Project optional). Null = standalone Project task. */
    val workStreamId: String? = null,
    /** Null = top-level. Non-null = nested; the parent's ownership is inherited. */
    val parentTaskId: String? = null,
    val status: TaskStatus = TaskStatus.TODO,
    /** Sibling ordering. */
    val order: Int = 0,
    val estimatedEffort: Duration? = null,
    val dueAt: Instant? = null,
    /** Planning metadata only. No scheduling is performed in this pass. */
    val reminderAt: Instant? = null,
    val priority: Priority = Priority.NORMAL,
    val notes: String? = null,
    /**
     * Execution preference for this Task. INHERIT walks parent Tasks → WorkStream → Project.
     * Standalone Project Tasks may store a preference for structure; they are not independently
     * focusable attention objects today.
     */
    val executionPreference: ExecutionPreference = ExecutionPreference.INHERIT,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null
) {
    val isStandalone: Boolean get() = workStreamId == null && parentTaskId == null
}

// ---------------------------------------------------------------------------------- Progress

enum class ProgressMode {
    /** completed executable leaves / total executable leaves. */
    COUNT_BASED,
    /** sum(effort of completed leaves) / sum(effort of all leaves); only when EVERY leaf has one. */
    EFFORT_WEIGHTED
}

/**
 * Progress of a Task, WorkStream or Project. Never fabricates a percentage: a scope with no
 * executable leaves is [Unstructured], not 0/0.
 */
sealed interface ProgressResult {
    /** No executable leaves at all. */
    data object Unstructured : ProgressResult
    /** Leaves exist but every one is CANCELLED: cancelled scope, NOT successful completion. */
    data object NoActiveWork : ProgressResult

    data class Structured(
        val mode: ProgressMode,
        /** Completed / total in the mode's unit (leaf count, or effort minutes). Cancelled leaves are excluded from both. */
        val completed: Long,
        val total: Long,
        val completedLeaves: Int,
        /** Active planned leaves (cancelled excluded). */
        val totalLeaves: Int,
        val cancelledLeaves: Int = 0
    ) : ProgressResult {
        /** 0..1, rounded half-up to 4 decimal places (i.e. 27.78% == 0.2778). */
        val fraction: Double
            get() = if (total == 0L) 0.0 else
                java.math.BigDecimal(completed).divide(java.math.BigDecimal(total), 4, java.math.RoundingMode.HALF_UP).toDouble()
        val percent: Double get() = fraction * 100.0
        val isComplete: Boolean get() = total > 0 && completed == total
    }
}

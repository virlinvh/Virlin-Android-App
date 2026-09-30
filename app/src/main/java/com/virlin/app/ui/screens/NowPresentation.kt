package com.virlin.app.ui.screens

import com.virlin.app.domain.model.ExecutionModeResolver
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState

/**
 * What the Now Current Focus card shows about the focused WorkStream's hierarchy.
 * One-way projection from domain objects. Rules:
 *  - [projectTitle] is null when the stream has no Project (the row is simply omitted).
 *  - [activeTaskTitle] is EXACTLY `WorkStream.activeTaskId` resolved to its Task — the deepest
 *    active task, never an ancestor; null when there is no active task or the id is stale.
 *  - [nextHumanAction] comes from the WorkStream's context only; nothing is invented and
 *    `nextTaskCandidate` is never used for it.
 */
data class CurrentFocusProjection(
    val streamId: String,
    val projectTitle: String?,
    val workStreamTitle: String,
    val activeTaskId: String?,
    val activeTaskTitle: String?,
    val nextHumanAction: String?,
    val state: WorkStreamState,
    /** From effective execution of deepest active node — decides LEAVE/COMPLETE vs LEAVE/HAND OFF. */
    val isExternal: Boolean = false
)

/**
 * Why a stream is asking for attention. Derived from domain state + [SnoozeReason] only —
 * the three are worded and actioned differently on Now.
 */
enum class AttentionKind {
    /** An external process's check time arrived: Still running / Result ready / Blocked. */
    CHECK_DUE,
    /** A human return reminder is due: Resume / defer. */
    RETURN_DUE,
    /** The external work finished earlier and the chosen look-again time is due: Focus now / defer. */
    RESULT_READY
}

object NowPresentation {

    /** The single FOCUS stream projected for the card, or null when nothing is in Focus. */
    fun currentFocus(projects: List<Project>, streams: List<WorkStream>, tasks: List<Task>): CurrentFocusProjection? {
        val stream = streams.firstOrNull { it.state == WorkStreamState.FOCUS } ?: return null
        return project(stream, projects, tasks)
    }

    fun project(stream: WorkStream, projects: List<Project>, tasks: List<Task>): CurrentFocusProjection {
        val task = stream.activeTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
        return CurrentFocusProjection(
            streamId = stream.id,
            projectTitle = stream.projectId?.let { id -> projects.firstOrNull { it.id == id }?.title },
            workStreamTitle = stream.title,
            activeTaskId = task?.id,
            activeTaskTitle = task?.title,
            nextHumanAction = stream.nextHumanAction?.takeIf { it.isNotBlank() },
            state = stream.state,
            isExternal = ExecutionModeResolver.resolveCurrent(stream, projects, tasks) ==
                com.virlin.app.domain.model.EffectiveExecutionMode.EXTERNAL
        )
    }

    /**
     * When each CHECK stream started waiting for the user — the persisted timestamp that the
     * Needs You timer, ordering and (later) urgency all derive from. Pure.
     *
     * - CHECK_DUE (came from PROCESSING): `checkAt`, the scheduled check moment — the item has been
     *   waiting since the check fell due, even if the app noticed later on reopen.
     * - RETURN_DUE / RESULT_READY (came from SNOOZED): `checkDue` clears `snoozedUntil`, so the
     *   transition stamp `updatedAt` is the moment it became due (any `checkAt` left over from an
     *   earlier cycle is deliberately ignored).
     * - Fallback in every case: `updatedAt` (always present, persisted in Room).
     */
    fun waitingSince(streams: List<WorkStream>): Map<String, java.time.Instant> =
        streams.filter { it.state == WorkStreamState.CHECK }.associate { s -> s.id to com.virlin.app.domain.attention.NeedsYouOrder.waitingSince(s) }

    /** Streams currently in CHECK, classified. Pure; nothing is scheduled or mutated here. */
    fun attention(streams: List<WorkStream>): Map<String, AttentionKind> =
        streams.filter { it.state == WorkStreamState.CHECK }.associate { s ->
            s.id to when (s.snoozeReason) {
                SnoozeReason.HUMAN_RETURN -> AttentionKind.RETURN_DUE
                SnoozeReason.EXTERNAL_RESULT_READY -> AttentionKind.RESULT_READY
                null -> AttentionKind.CHECK_DUE
            }
        }
}

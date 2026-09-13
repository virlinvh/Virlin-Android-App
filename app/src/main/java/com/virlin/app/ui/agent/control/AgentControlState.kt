package com.virlin.app.ui.agent.control

import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.hierarchy.HierarchyPresentation
import com.virlin.app.ui.hierarchy.TaskRow
import java.time.Duration
import java.time.Instant

/**
 * What the Agent's CONTROL mode can act on. A pure, deterministic projection of persisted
 * domain state — no MockData, no Room entities, no joins in Compose. The Agent is an action
 * surface over existing work, not a second dashboard: each item carries only the identity
 * and the structured actions that are valid for its current attention state.
 */
enum class ControlKind {
    FOCUS_HUMAN, FOCUS_EXTERNAL,
    RETURN_DUE, CHECK_DUE, RESULT_READY_DUE,
    RETURN_PENDING, RESULT_READY_PENDING,
    PROCESSING, READY, BLOCKED
}

/** Structured controls. Every one maps to exactly one existing VirlinActions path. */
enum class ControlAction(val label: String) {
    LEAVE("LEAVE"), HAND_OFF("HAND OFF"), COMPLETE("COMPLETE"),
    FOCUS("FOCUS"), RESUME("RESUME"), FOCUS_NOW("FOCUS NOW"), DEFER("DEFER"), CHECK("CHECK"), TASKS("TASKS")
}

data class ControlItem(
    val streamId: String,
    val title: String,
    val projectTitle: String?,
    /** Exact active task title (never an ancestor); null when none. */
    val taskTitle: String?,
    val kind: ControlKind,
    /** Short status line, e.g. "Ready to continue", "Processing 08:14 · check in 2m". */
    val detail: String,
    val actions: List<ControlAction>
)

data class AgentControlState(
    val currentFocus: ControlItem?,
    val needsAttention: List<ControlItem>,
    val processing: List<ControlItem>,
    val ready: List<ControlItem>,
    /** Stream whose task tree is open in the picker, if any. */
    val selectedStreamId: String? = null,
    val selectedStreamTitle: String? = null,
    val taskRows: List<TaskRow> = emptyList(),
    val selectedTaskId: String? = null,
    /** Task offered after a completion — offered only, never auto-selected. */
    val nextCandidate: Task? = null,
    /** Task awaiting explicit cancel confirmation. */
    val pendingCancelTaskId: String? = null,
    /** Row expanded in Recent / Suggested (UI only). */
    val expandedItemId: String? = null
) {
    /** Recent / Suggested order: current FOCUS · needs you · working for you · ready — all real state. */
    val suggested: List<ControlItem> get() = listOfNotNull(currentFocus) + needsAttention + processing + ready
}

object AgentControlPresentation {

    fun kindOf(s: WorkStream): ControlKind? = when (s.state) {
        WorkStreamState.FOCUS -> if (s.mode == WorkStreamMode.EXTERNAL) ControlKind.FOCUS_EXTERNAL else ControlKind.FOCUS_HUMAN
        WorkStreamState.CHECK -> when (s.snoozeReason) {
            SnoozeReason.HUMAN_RETURN -> ControlKind.RETURN_DUE
            SnoozeReason.EXTERNAL_RESULT_READY -> ControlKind.RESULT_READY_DUE
            null -> ControlKind.CHECK_DUE
        }
        WorkStreamState.SNOOZED -> if (s.snoozeReason == SnoozeReason.EXTERNAL_RESULT_READY) ControlKind.RESULT_READY_PENDING else ControlKind.RETURN_PENDING
        WorkStreamState.PROCESSING -> ControlKind.PROCESSING
        WorkStreamState.READY -> ControlKind.READY
        WorkStreamState.BLOCKED -> ControlKind.BLOCKED
        WorkStreamState.PAUSED, WorkStreamState.DONE -> null
    }

    fun actionsFor(kind: ControlKind): List<ControlAction> = when (kind) {
        ControlKind.FOCUS_HUMAN -> listOf(ControlAction.LEAVE, ControlAction.COMPLETE, ControlAction.TASKS)
        ControlKind.FOCUS_EXTERNAL -> listOf(ControlAction.LEAVE, ControlAction.HAND_OFF, ControlAction.COMPLETE, ControlAction.TASKS)
        ControlKind.RETURN_DUE, ControlKind.RETURN_PENDING -> listOf(ControlAction.RESUME, ControlAction.DEFER, ControlAction.TASKS)
        ControlKind.CHECK_DUE -> listOf(ControlAction.CHECK, ControlAction.TASKS)
        ControlKind.RESULT_READY_DUE, ControlKind.RESULT_READY_PENDING -> listOf(ControlAction.FOCUS_NOW, ControlAction.DEFER, ControlAction.TASKS)
        ControlKind.PROCESSING -> listOf(ControlAction.CHECK, ControlAction.TASKS)
        ControlKind.READY, ControlKind.BLOCKED -> listOf(ControlAction.FOCUS, ControlAction.TASKS)
    }

    private fun mmss(d: Duration): String { val s = d.seconds.coerceAtLeast(0); return "%02d:%02d".format(s / 60, s % 60) }
    private fun inMin(now: Instant, at: Instant): String { val m = Duration.between(now, at).toMinutes(); return if (m <= 0) "now" else "${m}m" }

    fun detail(s: WorkStream, kind: ControlKind, now: Instant): String = when (kind) {
        ControlKind.FOCUS_HUMAN, ControlKind.FOCUS_EXTERNAL -> "In focus"
        ControlKind.RETURN_DUE -> "Ready to continue"
        ControlKind.CHECK_DUE -> "Check due"
        ControlKind.RESULT_READY_DUE -> "Result ready"
        ControlKind.RETURN_PENDING -> "Back in " + (s.snoozedUntil?.let { inMin(now, it) } ?: "—")
        ControlKind.RESULT_READY_PENDING -> "Result ready · remind in " + (s.snoozedUntil?.let { inMin(now, it) } ?: "—")
        ControlKind.PROCESSING -> listOfNotNull(
            s.processingStartedAt?.let { "Processing ${mmss(Duration.between(it, now))}" } ?: "Processing",
            s.checkAt?.let { "check in ${inMin(now, it)}" } ?: "no check"
        ).joinToString(" · ")
        ControlKind.READY -> "Ready"
        ControlKind.BLOCKED -> s.blockerReason?.let { "Blocked · $it" } ?: "Blocked"
    }

    fun item(s: WorkStream, projects: List<Project>, tasks: List<Task>, now: Instant): ControlItem? {
        val kind = kindOf(s) ?: return null
        return ControlItem(
            streamId = s.id,
            title = s.title,
            projectTitle = s.projectId?.let { id -> projects.firstOrNull { it.id == id }?.title },
            taskTitle = s.activeTaskId?.let { id -> tasks.firstOrNull { it.id == id }?.title },
            kind = kind,
            detail = detail(s, kind, now),
            actions = actionsFor(kind)
        )
    }

    /** Ephemeral UI selection owned by the ViewModel (never domain truth). */
    data class Selection(
        val selectedStreamId: String? = null,
        val expanded: Set<String> = emptySet(),
        val selectedTaskId: String? = null,
        val nextCandidate: Task? = null,
        val pendingCancelTaskId: String? = null,
        /** Recent / Suggested row whose structured controls are shown (Stitch Control UI). Ephemeral. */
        val expandedItemId: String? = null
    )

    fun build(projects: List<Project>, streams: List<WorkStream>, tasks: List<Task>, now: Instant, ui: Selection = Selection()): AgentControlState {
        val items = streams.mapNotNull { item(it, projects, tasks, now) }
        val attention = setOf(ControlKind.RETURN_DUE, ControlKind.CHECK_DUE, ControlKind.RESULT_READY_DUE, ControlKind.BLOCKED)
        val selected = ui.selectedStreamId?.let { id -> streams.firstOrNull { it.id == id } }
        return AgentControlState(
            currentFocus = items.firstOrNull { it.kind == ControlKind.FOCUS_HUMAN || it.kind == ControlKind.FOCUS_EXTERNAL },
            needsAttention = items.filter { it.kind in attention },
            processing = items.filter { it.kind == ControlKind.PROCESSING },
            ready = items.filter { it.kind == ControlKind.READY || it.kind == ControlKind.RETURN_PENDING || it.kind == ControlKind.RESULT_READY_PENDING },
            selectedStreamId = selected?.id,
            selectedStreamTitle = selected?.title,
            taskRows = selected?.let { HierarchyPresentation.rows(tasks, it.id, it.activeTaskId, ui.expanded) } ?: emptyList(),
            selectedTaskId = ui.selectedTaskId?.takeIf { id -> tasks.any { it.id == id } },
            nextCandidate = ui.nextCandidate,
            pendingCancelTaskId = ui.pendingCancelTaskId?.takeIf { id -> tasks.any { it.id == id } },
            expandedItemId = ui.expandedItemId?.takeIf { id -> items.any { it.streamId == id } }
        )
    }
}

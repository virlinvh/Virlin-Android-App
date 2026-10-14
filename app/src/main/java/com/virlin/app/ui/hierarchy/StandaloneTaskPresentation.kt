package com.virlin.app.ui.hierarchy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.virlin.app.domain.model.*
import com.virlin.app.domain.progress.ProgressCalculator
import java.time.Instant

/** Display-only context; never persisted or passed to a workstream mutation. */
internal fun standaloneDisplayContext(project: Project) = WorkStream(
    id = "standalone:${project.id}", title = project.title, projectId = project.id, state = WorkStreamState.READY,
    createdAt = project.createdAt, updatedAt = project.updatedAt,
)

internal fun standaloneTiming(project: Project, tasks: List<Task>, sessions: List<FocusSession>, now: Instant): WorkStreamExecutionTiming {
    val context = standaloneDisplayContext(project)
    val own = tasks.filter { it.projectId == project.id && it.workStreamId == null }
    val ids = own.mapTo(hashSetOf()) { it.id }
    // Reuse the existing timing roll-up on transient copies. Stored task/session ownership stays null.
    return ExecutionTiming.workStream(context, own.map { it.copy(workStreamId = context.id) },
        sessions.filter { it.taskId in ids }.map { it.copy(workStreamId = context.id) }, now)
}

internal fun standaloneRows(project: Project, tasks: List<Task>, sessions: List<FocusSession>, now: Instant): List<StreamTaskUi> {
    val own = tasks.filter { it.projectId == project.id && it.workStreamId == null }
    val byId = tasks.associateBy { it.id }
    val timing = standaloneTiming(project, tasks, sessions, now)
    val external = ExternalTiming.inclusive(own, now)
    return own.map { task ->
        val children = own.count { it.parentTaskId == task.id }
        val progress = ProgressCalculator.ofTask(tasks, task.id) as? ProgressResult.Structured
        val preference = task.executionPreference
        val effective = ExecutionModeResolver.resolveTask(task, byId, null, project.defaultExecutionMode)
        val ancestor = TaskHierarchy.ancestry(tasks, task.id).orEmpty().drop(1)
            .firstOrNull { it.executionPreference != ExecutionPreference.INHERIT }
        StreamTaskUi(task.id, task.parentTaskId, task.title, task.order,
            completed = task.status.isCompleted, terminal = task.status.isTerminal,
            execution = when (preference) { ExecutionPreference.HUMAN -> StreamExecutionMode.HUMAN; ExecutionPreference.EXTERNAL -> StreamExecutionMode.EXTERNAL; else -> StreamExecutionMode.INHERIT },
            effectiveExecution = if (effective == EffectiveExecutionMode.EXTERNAL) StreamExecutionMode.EXTERNAL else StreamExecutionMode.HUMAN,
            inheritedFrom = if (preference == ExecutionPreference.INHERIT) ancestor?.title ?: project.title else null,
            childCount = children,
            progressPercent = if (children > 0 && progress != null) if (progress.total == 0L) 0 else ((progress.completed * 100L) / progress.total).toInt() else null,
            focusedLabel = timing.inclusiveTaskFocusSeconds[task.id]?.takeIf { it > 0 }?.let(FocusInvestment::formatCompact),
            externalLabel = external[task.id]?.takeIf { it > 0 }?.let(ExecutionTiming::formatClock),
            activityState = activityState(taskScope(own, task.id)))
    }
}

@Composable
internal fun ProjectStandaloneTaskCard(task: ProjectTaskUi, index: Int, actions: ProjectPageActions, modifier: Modifier = Modifier) {
    val row = task.row ?: StreamTaskUi(task.id, null, task.title, index, completed = task.completed, terminal = task.completed)
    TaskRow(row, index + 1, false, false, false, row.activityState,
        modifier.testTag(taskRowTag(task.id)),
        onOpen = { actions.onOpenTask(task.id) }, onComplete = { actions.onToggleTask(task.id) },
        onWorkControls = { actions.onTaskMenu(task.id) }, onMenu = { actions.onTaskMenu(task.id) }, onReorder = {})
}

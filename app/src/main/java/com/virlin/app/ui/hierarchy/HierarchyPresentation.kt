package com.virlin.app.ui.hierarchy

import com.virlin.app.domain.model.ProgressMode
import com.virlin.app.domain.model.ProgressResult
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskHierarchy
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.progress.ProgressCalculator
import java.time.Duration

/**
 * Pure, one-way projections from domain objects to what the hierarchy screens render.
 * No state is stored here; nothing is mutated. Progress comes ONLY from
 * [ProgressCalculator]; the current task comes ONLY from `WorkStream.activeTaskId`; the
 * breadcrumb comes ONLY from `parentTaskId` ancestry. All are recomputed from the domain.
 */

/** Human-facing rendering of a [ProgressResult]. Technical enum names are never shown. */
data class ProgressLabel(
    /** e.g. "68%", "3 / 5 · 60%", "No structured progress", "No active planned work". */
    val text: String,
    /** 0..1 for a bar, null when there is nothing to fill. */
    val fraction: Float?,
    val hint: String? = null
)

fun ProgressResult.toLabel(compact: Boolean = false): ProgressLabel = when (this) {
    ProgressResult.Unstructured -> ProgressLabel("No structured progress", null)
    ProgressResult.NoActiveWork -> ProgressLabel("No active planned work", null)
    is ProgressResult.Structured -> {
        val pct = (fraction * 100).toInt()
        when (mode) {
            ProgressMode.COUNT_BASED -> ProgressLabel(
                if (compact) "$pct%" else "$completedLeaves / $totalLeaves · $pct%", fraction.toFloat()
            )
            ProgressMode.EFFORT_WEIGHTED -> ProgressLabel(
                "$pct%", fraction.toFloat(), hint = if (compact) null else "estimated effort"
            )
        }
    }
}

fun Duration.toEffortLabel(): String {
    val h = toHours(); val m = toMinutes() % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/** One row in a flattened, expandable task tree. Stable [id] for lazy lists. */
data class TaskRow(
    val id: String,
    val task: Task,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
    val isCurrent: Boolean,
    /** Parent tasks only. */
    val progress: ProgressLabel?
) {
    val status: TaskStatus get() = task.status
    val accessibilityLabel: String
        get() = buildString {
            append(task.title)
            when {
                task.status == TaskStatus.DONE -> append(", completed")
                task.status == TaskStatus.CANCELLED -> append(", cancelled")
                isCurrent -> append(", current task")
                task.status == TaskStatus.IN_PROGRESS -> append(", in progress")
            }
            progress?.let { append(", ${it.text}") }
            if (hasChildren) append(if (expanded) ", expanded" else ", collapsed")
        }
}

object HierarchyPresentation {

    /**
     * Flatten a WorkStream's task forest (or a Task's subtree when [rootTaskId] is given)
     * into visible rows, honouring [expandedIds]. Depth-first, sibling order. O(n) per build.
     */
    fun rows(
        tasks: Collection<Task>,
        workStreamId: String?,
        activeTaskId: String?,
        expandedIds: Set<String>,
        rootTaskId: String? = null
    ): List<TaskRow> {
        val byParent = tasks.groupBy { it.parentTaskId }.mapValues { (_, v) -> v.sortedBy { it.order } }
        val hasChild = tasks.mapNotNull { it.parentTaskId }.toHashSet()
        val out = ArrayList<TaskRow>()
        fun walk(parent: String?, depth: Int) {
            val children = byParent[parent].orEmpty().let { list ->
                if (parent == null && workStreamId != null) list.filter { it.workStreamId == workStreamId } else list
            }
            for (t in children) {
                val kids = t.id in hasChild
                val expanded = kids && t.id in expandedIds
                out += TaskRow(
                    id = t.id, task = t, depth = depth, hasChildren = kids, expanded = expanded,
                    isCurrent = t.id == activeTaskId,
                    progress = if (kids) ProgressCalculator.ofTask(tasks, t.id).toLabel() else null
                )
                if (expanded) walk(t.id, depth + 1)
            }
        }
        walk(rootTaskId, 0)
        return out
    }

    /** Ancestor titles from the top down, excluding the task itself; plus stream and project. */
    data class Breadcrumb(val project: String?, val workStream: String?, val ancestors: List<Task>, val current: Task)

    fun breadcrumb(task: Task, tasks: Collection<Task>, stream: WorkStream?, project: Project?): Breadcrumb {
        val path = TaskHierarchy.ancestry(tasks, task.id).orEmpty()   // task first
        return Breadcrumb(
            project = project?.title,
            workStream = stream?.title,
            ancestors = path.drop(1).asReversed(),
            current = task
        )
    }

    /** IDs on the path to [taskId] — used to auto-expand so the current task is visible. */
    fun ancestorIds(tasks: Collection<Task>, taskId: String?): Set<String> =
        taskId?.let { TaskHierarchy.ancestry(tasks, it) }?.drop(1)?.map { it.id }?.toSet().orEmpty()
}

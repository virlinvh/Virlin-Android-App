package com.virlin.app.domain.progress

import com.virlin.app.domain.model.ProgressMode
import com.virlin.app.domain.model.ProgressResult
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskHierarchy
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState

/**
 * The single place progress is computed. Never in UI.
 *
 * Rules (documented in DEVELOPMENT_STATUS.md):
 * - Only EXECUTABLE LEAVES count. Parent tasks, WorkStreams and Projects are containers and
 *   are never counted as units, so the hierarchy is never double-counted.
 * - COUNT_BASED: completed leaves / total leaves. EFFORT_WEIGHTED: sum of completed leaf
 *   effort / sum of all leaf effort — used ONLY when every leaf has a positive estimate.
 *   Partial estimates fall back to COUNT_BASED; minutes and counts are never mixed.
 * - A scope with no leaves is [ProgressResult.Unstructured], never 0/0.
 * - Only DONE counts as completed. CANCELLED leaves are terminal but NOT completed: they are
 *   removed from the active planned scope — excluded from BOTH numerator and denominator in
 *   both modes. A scope whose leaves are all cancelled is [ProgressResult.NoActiveWork].
 * - Project roll-up: leaves under its WorkStreams plus its standalone task trees. A
 *   WorkStream with no Tasks counts as ONE executable unit (complete when DONE) in
 *   COUNT_BASED mode only; it has no effort, so any such stream forces COUNT_BASED.
 */
object ProgressCalculator {

    fun ofLeaves(leaves: List<Task>): ProgressResult {
        if (leaves.isEmpty()) return ProgressResult.Unstructured
        return build(leaves.map(::unitOf))
    }

    /** Progress of a Task = its executable leaves; a leaf task is its own unit. */
    fun ofTask(tasks: Collection<Task>, taskId: String): ProgressResult {
        val leaves = TaskHierarchy.leaves(tasks, taskId)
        if (leaves.isEmpty()) {
            val self = tasks.firstOrNull { it.id == taskId } ?: return ProgressResult.Unstructured
            return ofLeaves(listOf(self))
        }
        return ofLeaves(leaves)
    }

    /** Progress of a WorkStream = leaves of its top-level tasks. No tasks → Unstructured. */
    fun ofWorkStream(tasks: Collection<Task>, workStreamId: String): ProgressResult {
        val roots = tasks.filter { it.workStreamId == workStreamId && it.parentTaskId == null }
        return ofLeaves(roots.flatMap { leavesOrSelf(tasks, it) })
    }

    /** Project roll-up over executable work (see rules above). */
    fun ofProject(tasks: Collection<Task>, streams: Collection<WorkStream>, projectId: String): ProgressResult {
        val units = ArrayList<Unit>()
        streams.filter { it.projectId == projectId }.forEach { ws ->
            val roots = tasks.filter { it.workStreamId == ws.id && it.parentTaskId == null }
            if (roots.isEmpty()) units += Unit(ws.state == WorkStreamState.DONE, effortMinutes = null)
            else roots.flatMap { leavesOrSelf(tasks, it) }.forEach { units += unitOf(it) }
        }
        tasks.filter { it.projectId == projectId && it.workStreamId == null && it.parentTaskId == null }
            .flatMap { leavesOrSelf(tasks, it) }.forEach { units += unitOf(it) }
        if (units.isEmpty()) return ProgressResult.Unstructured
        return build(units)
    }

    // ---------------------------------------------------------------------------------

    private data class Unit(val complete: Boolean, val effortMinutes: Long?, val cancelled: Boolean = false)

    private fun unitOf(t: Task) = Unit(t.status.isCompleted, t.estimatedEffort?.toMinutes()?.takeIf { it > 0 }, cancelled = !t.status.isActivePlanned)

    private fun leavesOrSelf(tasks: Collection<Task>, root: Task): List<Task> =
        TaskHierarchy.leaves(tasks, root.id).ifEmpty { listOf(root) }

    private fun build(all: List<Unit>): ProgressResult {
        val cancelled = all.count { it.cancelled }
        val units = all.filter { !it.cancelled }
        if (units.isEmpty()) return ProgressResult.NoActiveWork
        val completedLeaves = units.count { it.complete }
        val allEstimated = units.all { it.effortMinutes != null }
        return if (allEstimated) ProgressResult.Structured(
            mode = ProgressMode.EFFORT_WEIGHTED,
            completed = units.filter { it.complete }.sumOf { it.effortMinutes!! },
            total = units.sumOf { it.effortMinutes!! },
            completedLeaves = completedLeaves, totalLeaves = units.size, cancelledLeaves = cancelled
        ) else ProgressResult.Structured(
            mode = ProgressMode.COUNT_BASED,
            completed = completedLeaves.toLong(), total = units.size.toLong(),
            completedLeaves = completedLeaves, totalLeaves = units.size, cancelledLeaves = cancelled
        )
    }
}

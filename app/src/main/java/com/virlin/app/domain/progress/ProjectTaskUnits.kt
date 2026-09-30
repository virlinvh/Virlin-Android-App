package com.virlin.app.domain.progress

import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskHierarchy
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState

/**
 * THE UNITS A PROJECT'S PROGRESS IS COUNTED FROM.
 *
 * `ProgressCalculator.ofProject` produces a number; this produces the very records that number
 * came from, in the same order, under the same rules:
 *
 * - a WorkStream with no tasks contributes ITSELF as one unit (complete when the stream is DONE),
 * - otherwise each of its root tasks contributes its executable leaves, or itself when it has none,
 * - the project's standalone tasks contribute the same way,
 * - a cancelled leaf is not active work: it is excluded from both sides of the ratio.
 *
 * Listing and counting therefore cannot disagree: the Task Index shows exactly what Overview
 * counted, because both read this.
 */
object ProjectTaskUnits {

    /** One counted unit: either an executable task, or a WorkStream that holds no tasks. */
    sealed interface Unit {
        val completed: Boolean

        data class TaskUnit(val task: Task, val workStream: WorkStream?) : Unit {
            override val completed: Boolean get() = task.status.isCompleted
        }

        /** A WorkStream with no tasks at all still counts as one piece of work. */
        data class EmptyWorkStream(val workStream: WorkStream) : Unit {
            override val completed: Boolean get() = workStream.state == WorkStreamState.DONE
        }
    }

    /**
     * Every unit of [projectId], in the order the project presents its work: each WorkStream's
     * units in task order, then the standalone tasks.
     */
    fun of(tasks: Collection<Task>, streams: Collection<WorkStream>, projectId: String): List<Unit> {
        val units = ArrayList<Unit>()
        streams.filter { it.projectId == projectId }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .forEach { stream ->
                val roots = tasks
                    .filter { it.workStreamId == stream.id && it.parentTaskId == null }
                    .sortedWith(compareBy({ it.order }, { it.id }))
                if (roots.isEmpty()) {
                    units += Unit.EmptyWorkStream(stream)
                } else {
                    roots.flatMap { leavesOrSelf(tasks, it) }
                        .forEach { units += Unit.TaskUnit(it, stream) }
                }
            }
        tasks.filter { it.projectId == projectId && it.workStreamId == null && it.parentTaskId == null }
            .sortedWith(compareBy({ it.order }, { it.id }))
            .flatMap { leavesOrSelf(tasks, it) }
            .forEach { units += Unit.TaskUnit(it, null) }
        // Cancelled work is not planned work; ProgressCalculator drops it from both sides, so
        // it must not appear in a list that claims to be what was counted.
        return units.filterNot { it is Unit.TaskUnit && !it.task.status.isActivePlanned }
    }

    private fun leavesOrSelf(tasks: Collection<Task>, root: Task): List<Task> =
        TaskHierarchy.leaves(tasks, root.id).ifEmpty { listOf(root) }
}

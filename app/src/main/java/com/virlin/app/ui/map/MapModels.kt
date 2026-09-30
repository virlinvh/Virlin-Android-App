package com.virlin.app.ui.map

import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.domain.model.ProgressResult

enum class MapKind { PROJECT, WORKSTREAM, TASK }
enum class MapStatus { NONE, TODO, CURRENT, DONE, BLOCKED }

enum class MapLayout(val label: String) {
    RIGHT_TREE("Right tree"), BALANCED("Balanced"), RADIAL("Radial"),
    TOP_DOWN("Top-down"), BRANCH_LANES("Branch lanes"),
    COMPACT_OUTLINE("Compact outline")
}

/**
 * One node of the map. It is a PROJECTION of an existing record, never a record of its own:
 * [id] is the entity's own stable id (titles repeat, so nothing here is keyed by title) and
 * [parentId] is its real owner. Standalone project tasks carry the project's id as parent.
 */
data class MapTopic(
    val id: String,
    val parentId: String?,
    val title: String,
    val detail: String = "",
    val kind: MapKind,
    val status: MapStatus = MapStatus.NONE,
    val order: Int = 0,
)

data class MapAppearance(
    val layout: MapLayout = MapLayout.RIGHT_TREE,
    val paletteIndex: Int = 0,
    /** ARGB keyed by stable entity id. Node override beats branch override beats palette. */
    val nodeColors: Map<String, Long> = emptyMap(),
    val branchColors: Map<String, Long> = emptyMap(),
)

/**
 * Builds the map from the real records. Every WorkStream of the project appears, including one
 * with no tasks; subtasks nest to whatever depth they actually have; nothing is created,
 * duplicated or reordered here.
 *
 * Counts and progress are shown only where the domain actually has them —
 * `ProgressCalculator` returns `Unstructured` for a stream with no executable work, and an empty
 * branch therefore reads as "no tasks yet", never as a finished one.
 */
fun projectMapTopics(
    project: Project,
    streams: List<WorkStream>,
    tasks: List<Task>,
): List<MapTopic> {
    val projectStreams = streams.filter { it.projectId == project.id }
        // The persisted sibling order (v14). Before that column existed this sorted by title,
        // which meant the map showed an order the user could not change.
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))
    val streamIds = projectStreams.map { it.id }.toSet()
    // A task belongs to this map when its stream does, or when it is standalone in the project.
    val own = tasks.filter { task ->
        task.workStreamId?.let { it in streamIds } ?: (task.projectId == project.id)
    }
    val childrenOf = own.groupBy { it.parentTaskId }

    val topics = ArrayList<MapTopic>(own.size + projectStreams.size + 1)
    val projectProgress = ProgressCalculator.ofProject(tasks, streams, project.id)
    topics += MapTopic(
        id = project.id, parentId = null, title = project.title,
        detail = progressDetail(projectProgress), kind = MapKind.PROJECT,
    )

    projectStreams.forEachIndexed { index, stream ->
        topics += MapTopic(
            id = stream.id, parentId = project.id, title = stream.title,
            detail = progressDetail(ProgressCalculator.ofWorkStream(tasks, stream.id)),
            kind = MapKind.WORKSTREAM, status = stream.mapStatus(), order = index,
        )
    }

    // Iterative descent: a deep subtask chain must not depend on the JVM stack.
    data class Pending(val task: Task, val parentId: String, val order: Int)

    val stack = ArrayDeque<Pending>()
    // Roots: standalone tasks hang off the project, stream tasks off their stream.
    // Root tasks belong to DIFFERENT parents - one per workstream, plus the project itself for
    // standalone tasks - so they must be numbered per parent. Numbering them in one project-wide
    // sweep gave a stream's first task an index of 1 or 2 simply because another stream's tasks
    // sorted ahead of it, which is not that group's sibling order.
    childrenOf[null].orEmpty()
        .groupBy { it.workStreamId ?: project.id }
        .forEach { (parentId, group) ->
            group.sortedWith(compareBy({ it.order }, { it.id }))
                .forEachIndexed { index, task -> stack.addLast(Pending(task, parentId, index)) }
        }
    val seen = HashSet<String>()
    while (stack.isNotEmpty()) {
        val (task, parentId, order) = stack.removeLast()
        if (!seen.add(task.id)) continue        // guards a malformed parent cycle
        topics += MapTopic(
            id = task.id, parentId = parentId, title = task.title,
            detail = subtaskDetail(childrenOf[task.id].orEmpty()),
            kind = MapKind.TASK, status = task.status.mapStatus(), order = order,
        )
        childrenOf[task.id].orEmpty()
            .sortedWith(compareBy({ it.order }, { it.id }))
            .forEachIndexed { index, child -> stack.addLast(Pending(child, task.id, index)) }
    }
    return topics
}

private fun progressDetail(progress: ProgressResult): String = when (progress) {
    is ProgressResult.Structured ->
        "${progress.completedLeaves}/${progress.totalLeaves} tasks"
    // The domain says there is nothing countable here; saying "0%" would be a claim it never made.
    ProgressResult.Unstructured -> "No tasks yet"
    ProgressResult.NoActiveWork -> "No active work"
}

private fun subtaskDetail(children: List<Task>): String {
    val active = children.filterNot { it.status == TaskStatus.CANCELLED }
    if (active.isEmpty()) return ""
    return "${active.count { it.status.isCompleted }}/${active.size} subtasks"
}

private fun WorkStream.mapStatus(): MapStatus = when (state) {
    WorkStreamState.FOCUS -> MapStatus.CURRENT
    WorkStreamState.BLOCKED -> MapStatus.BLOCKED
    WorkStreamState.DONE -> MapStatus.DONE
    else -> MapStatus.TODO
}

private fun TaskStatus.mapStatus(): MapStatus = when (this) {
    TaskStatus.DONE -> MapStatus.DONE
    TaskStatus.IN_PROGRESS -> MapStatus.CURRENT
    // Cancelled is terminal but NOT completed, so it must never read as done.
    TaskStatus.CANCELLED -> MapStatus.BLOCKED
    TaskStatus.TODO -> MapStatus.TODO
}

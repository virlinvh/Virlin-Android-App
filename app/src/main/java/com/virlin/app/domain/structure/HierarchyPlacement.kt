package com.virlin.app.domain.structure

import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream

/**
 * ONE structural placement command for the project hierarchy.
 *
 * Every structural edit — a map drag, the action sheet's Move / Copy / Paste, a future keyboard
 * command — becomes one of these and goes through the same validation and the same transaction.
 * The UI never writes a parent id itself.
 *
 * The node kinds mirror the app's real records: the PROJECT root, its WORKSTREAMs, and TASKs at
 * any depth. There are no freeform nodes in Virlin's map — it is a live projection of these
 * records, so nothing on the canvas exists that is not one of them. If freeform notes are ever
 * added they must carry their own kind and must never reparent a task by proximity.
 */
enum class NodeKind { PROJECT, WORKSTREAM, TASK }

enum class PlacementOperation { MOVE, COPY }

/** The hierarchy as the validator sees it: ids, kinds, parents and sibling order. */
data class HierarchyNode(
    val id: String,
    val projectId: String,
    val kind: NodeKind,
    val parentId: String?,
    val order: Int,
)

data class Placement(
    val sourceId: String,
    val targetParentId: String,
    /** The sibling the branch lands in front of; null appends at the end. */
    val insertBeforeId: String? = null,
    val operation: PlacementOperation = PlacementOperation.MOVE,
    /** What the caller believed the hierarchy was. A mismatch means someone else edited it. */
    val expectedRevision: Long,
)

/**
 * What one committed placement did — enough to describe it in the activity feed and enough to
 * build its exact inverse for Undo.
 */
data class PlacementCommit(
    /** The source and its descendants, in the tree they came from. */
    val affectedIds: List<String>,
    /** old id → new id for a COPY; empty for a MOVE. */
    val clonedIds: Map<String, String> = emptyMap(),
    val previousParentTaskId: String? = null,
    val previousWorkStreamId: String? = null,
    /** The sibling the branch sat in front of before it moved. */
    val previousInsertBeforeId: String? = null,
    val targetParentId: String,
)

sealed interface PlacementCheck {
    data class Allowed(
        val source: HierarchyNode,
        val target: HierarchyNode,
        /** The source and every descendant, parent-first. */
        val subtreeIds: List<String>,
    ) : PlacementCheck

    data class Rejected(val reason: String) : PlacementCheck
}

/**
 * The exact inverse of a committed MOVE, ready to send back through the same command with a
 * freshly read [revision] — so an undo is validated against the hierarchy as it is NOW and is
 * refused if someone has edited it since.
 *
 * Returns null when the operation cannot be undone safely:
 *  - a COPY, because reversing it would mean deleting the clones, and this app cancels rather
 *    than deletes; a cancelled copy is not the same as one that never existed.
 * Callers must not offer Undo when this is null.
 */
fun inverseOf(commit: PlacementCommit, projectId: String, revision: Long): Placement? {
    if (commit.clonedIds.isNotEmpty()) return null
    val sourceId = commit.affectedIds.firstOrNull() ?: return null
    return Placement(
        sourceId = sourceId,
        targetParentId = commit.previousParentTaskId
            ?: commit.previousWorkStreamId
            ?: projectId,
        insertBeforeId = commit.previousInsertBeforeId,
        operation = PlacementOperation.MOVE,
        expectedRevision = revision,
    )
}

object HierarchyRules {

    /**
     * The one refusal that is not a problem: the branch is already exactly where the drop asked
     * it to go, so there is nothing to write. It is named here rather than matched by its text so
     * a caller can tell "nothing to do" apart from "that is not allowed" without string
     * comparison, and so the sentence has one home.
     */
    const val ALREADY_IN_PLACE = "That branch is already there."


    /**
     * Projects one project's records into the node list the validator works on. The project's own
     * root is a node so a task can be dropped on it to become standalone.
     */
    fun nodesOf(projectId: String, streams: List<WorkStream>, tasks: List<Task>): List<HierarchyNode> {
        val own = streams.filter { it.projectId == projectId }
        val streamIds = own.mapTo(HashSet()) { it.id }
        val nodes = ArrayList<HierarchyNode>(own.size + tasks.size + 1)
        nodes += HierarchyNode(projectId, projectId, NodeKind.PROJECT, null, 0)
        // The user's own arrangement (v14), not the alphabet: the validator has to reason about
        // the same sequence the map draws, or an insertion point would mean two different things.
        own.sortedWith(compareBy({ it.sortOrder }, { it.id })).forEach { stream ->
            nodes += HierarchyNode(stream.id, projectId, NodeKind.WORKSTREAM, projectId, stream.sortOrder)
        }
        tasks.forEach { task ->
            val belongs = task.workStreamId?.let { it in streamIds } ?: (task.projectId == projectId)
            if (!belongs) return@forEach
            // A top-level task hangs off its stream, or off the project root when standalone.
            val parent = task.parentTaskId ?: task.workStreamId ?: projectId
            nodes += HierarchyNode(task.id, projectId, NodeKind.TASK, parent, task.order)
        }
        return nodes
    }

    /**
     * The project's hierarchy revision.
     *
     * Derived, not stored: it is a fold over exactly the facts a placement may change — which
     * nodes exist, who their parent is and in what order. It therefore changes when and only when
     * the hierarchy changes, needs no schema column that could drift out of step with the rows it
     * describes, and survives a restart because it is recomputed from the same records.
     */
    fun revisionOf(nodes: Collection<HierarchyNode>): Long {
        var revision = 1125899906842597L      // a large prime; the usual string-hash seed
        nodes.sortedBy { it.id }.forEach { node ->
            revision = 31 * revision + node.id.hashCode()
            revision = 31 * revision + (node.parentId?.hashCode() ?: 0)
            revision = 31 * revision + node.order
            revision = 31 * revision + node.kind.ordinal
        }
        return revision
    }

    /**
     * Validates a placement against a consistent view of the hierarchy. Pure: it reads, it never
     * writes, and the caller runs it inside the same transaction that applies the result.
     */
    fun validate(
        nodes: Collection<HierarchyNode>,
        request: Placement,
        actualRevision: Long,
    ): PlacementCheck {
        if (request.expectedRevision != actualRevision) {
            return PlacementCheck.Rejected("This project changed while you were editing. Refresh and try again.")
        }
        val byId = nodes.associateBy { it.id }
        if (byId.size != nodes.size) return PlacementCheck.Rejected("Duplicate node ids.")
        val source = byId[request.sourceId] ?: return PlacementCheck.Rejected("That branch no longer exists.")
        val target = byId[request.targetParentId] ?: return PlacementCheck.Rejected("That destination no longer exists.")
        if (source.kind == NodeKind.PROJECT) {
            return PlacementCheck.Rejected("A project is not a branch you can move.")
        }
        if (source.projectId != target.projectId) {
            return PlacementCheck.Rejected("Moving between projects needs its own confirmation, which this release does not have.")
        }
        if (source.id == target.id) return PlacementCheck.Rejected("A branch cannot be dropped on itself.")

        val legal = when (source.kind) {
            // A WorkStream reorders under the project root. It never becomes a task by a drop.
            NodeKind.WORKSTREAM -> target.kind == NodeKind.PROJECT
            // The project root (standalone), a workstream, or another task: all three are homes
            // a task already has in this schema.
            NodeKind.TASK -> true
            NodeKind.PROJECT -> false
        }
        if (!legal) {
            return PlacementCheck.Rejected(
                "A workstream cannot become a task by dropping it. Use an explicit conversion."
            )
        }
        if (request.operation == PlacementOperation.COPY && source.kind != NodeKind.TASK) {
            return PlacementCheck.Rejected("Only a task branch can be copied.")
        }

        // Iterative walk: a subtask chain has no depth limit, so neither has this.
        val children = nodes.groupBy { it.parentId }
        val visited = LinkedHashSet<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(source.id)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!visited.add(id)) return PlacementCheck.Rejected("This branch contains a cycle.")
            children[id].orEmpty().sortedWith(compareBy({ it.order }, { it.id }))
                .asReversed().forEach { stack.addLast(it.id) }
        }
        if (target.id in visited) {
            return PlacementCheck.Rejected("A branch cannot be moved beneath one of its own subtasks.")
        }
        if (request.insertBeforeId != null) {
            val sibling = byId[request.insertBeforeId]
                ?: return PlacementCheck.Rejected("That insertion point no longer exists.")
            if (sibling.parentId != target.id) {
                return PlacementCheck.Rejected("An insertion point must be another child of the destination.")
            }
            if (sibling.id in visited) {
                return PlacementCheck.Rejected("An insertion point cannot be inside the branch being moved.")
            }
        }
        // A no-op is refused, but "same parent" is NOT by itself a no-op: moving a branch to the
        // end of the group it already sits in is a real reorder, and it is the one a user asks
        // for most often. Only refuse when the branch would land exactly where it already is.
        if (request.operation == PlacementOperation.MOVE && source.parentId == target.id) {
            val group = nodes.filter { it.parentId == target.id && it.id !in visited }
                .sortedWith(compareBy({ it.order }, { it.id }))
            val currentlyAfter = nodes.filter { it.parentId == target.id }
                .sortedWith(compareBy({ it.order }, { it.id }))
                .dropWhile { it.id != source.id }.drop(1).firstOrNull()?.id
            val unchanged = when (request.insertBeforeId) {
                // Appending changes nothing only when the branch is already last.
                null -> currentlyAfter == null
                // Landing in front of the sibling that already follows it changes nothing.
                else -> request.insertBeforeId == currentlyAfter
            }
            if (unchanged || group.isEmpty() && request.insertBeforeId == null) {
                return PlacementCheck.Rejected(ALREADY_IN_PLACE)
            }
        }
        return PlacementCheck.Allowed(source, target, visited.toList())
    }
}

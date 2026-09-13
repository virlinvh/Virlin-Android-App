package com.virlin.app.domain.model

/**
 * Pure hierarchy helpers over an in-memory set of Tasks. Used by the Action Layer for
 * validation and by [ProgressCalculator]. Nothing here mutates.
 */
object TaskHierarchy {

    /** Direct children of [parentId], in sibling [Task.order]. */
    fun children(tasks: Collection<Task>, parentId: String): List<Task> =
        tasks.filter { it.parentTaskId == parentId }.sortedBy { it.order }

    /** All descendants (depth-first), excluding [rootId] itself. */
    fun descendants(tasks: Collection<Task>, rootId: String): List<Task> {
        val byParent = tasks.groupBy { it.parentTaskId }
        val out = ArrayList<Task>()
        fun walk(id: String) { byParent[id].orEmpty().sortedBy { it.order }.forEach { out += it; walk(it.id) } }
        walk(rootId)
        return out
    }

    /**
     * The derived active path: [taskId] first, then each parent up to the top-level task.
     * Returns null if any link is missing or a cycle is detected (defensive; cycles are
     * rejected at write time).
     */
    fun ancestry(tasks: Collection<Task>, taskId: String): List<Task>? {
        val byId = tasks.associateBy { it.id }
        val path = ArrayList<Task>()
        val seen = HashSet<String>()
        var cur = byId[taskId] ?: return null
        while (true) {
            if (!seen.add(cur.id)) return null
            path += cur
            val p = cur.parentTaskId ?: return path
            cur = byId[p] ?: return null
        }
    }

    /** Executable leaves under [rootId] (tasks with no children). Excludes the root. */
    fun leaves(tasks: Collection<Task>, rootId: String): List<Task> {
        val hasChild = tasks.mapNotNull { it.parentTaskId }.toHashSet()
        return descendants(tasks, rootId).filter { it.id !in hasChild }
    }

    /** Would setting [parentId] as parent of [taskId] create a cycle? */
    fun wouldCycle(tasks: Collection<Task>, taskId: String, parentId: String): Boolean {
        if (taskId == parentId) return true
        val byId = tasks.associateBy { it.id }
        var cur: String? = parentId
        val seen = HashSet<String>()
        while (cur != null) {
            if (cur == taskId) return true
            if (!seen.add(cur)) return true
            cur = byId[cur]?.parentTaskId
        }
        return false
    }
}

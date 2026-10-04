package com.virlin.app.domain.model

/** Structural rule: children (even completed children) make an item a container. */
object ExecutionEligibility {
    fun isLeaf(id: String, tasks: List<Task>): Boolean {
        val task = tasks.firstOrNull { it.id == id }
        return if (task != null) tasks.none { it.parentTaskId == id }
        else tasks.none { it.workStreamId == id }
    }
}

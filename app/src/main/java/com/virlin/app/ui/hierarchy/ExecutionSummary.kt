package com.virlin.app.ui.hierarchy

import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStreamState

internal fun activityState(tasks: Collection<Task>): WorkstreamState {
    val activities = tasks.filterNot { it.status.isTerminal }.mapNotNull { it.execution }
    val running = activities.count { it.state == WorkStreamState.FOCUS || it.processingStartedAt != null }
    return when {
        running >= 2 -> WorkstreamState.MULTI_RUN
        activities.any { it.state == WorkStreamState.FOCUS } -> WorkstreamState.HUMAN_FOCUS
        activities.any { it.processingStartedAt != null } -> WorkstreamState.EXTERNAL_RUNNING
        activities.any { it.state == WorkStreamState.CHECK } -> WorkstreamState.NEEDS_ATTENTION
        activities.any { it.state == WorkStreamState.SNOOZED } -> WorkstreamState.WAITING
        activities.any { it.state == WorkStreamState.PAUSED } -> WorkstreamState.PAUSED
        activities.any { it.state == WorkStreamState.BLOCKED } -> WorkstreamState.BLOCKED
        else -> WorkstreamState.READY
    }
}

internal fun activitySummary(tasks: Collection<Task>): String? {
    val records = tasks.filterNot { it.status.isTerminal }.mapNotNull { it.execution }
    return if (records.isEmpty()) null else buildList {
        records.count { it.state == WorkStreamState.FOCUS }.takeIf { it > 0 }?.let { add("$it focusing") }
        records.count { it.processingStartedAt != null }.takeIf { it > 0 }?.let { add("$it external") }
        records.count { it.state == WorkStreamState.CHECK }.takeIf { it > 0 }?.let { add("$it need attention") }
        records.count { it.state == WorkStreamState.SNOOZED }.takeIf { it > 0 }?.let { add("$it waiting") }
        records.count { it.state == WorkStreamState.PAUSED }.takeIf { it > 0 }?.let { add("$it paused") }
    }.joinToString(" · ").takeIf { it.isNotBlank() }
}

internal fun taskScope(tasks: Collection<Task>, root: String): List<Task> {
    return listOfNotNull(tasks.firstOrNull { it.id == root }) +
        com.virlin.app.domain.model.TaskHierarchy.descendants(tasks, root)
}

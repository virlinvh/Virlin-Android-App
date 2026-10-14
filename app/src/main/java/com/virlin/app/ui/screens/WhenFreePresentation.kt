package com.virlin.app.ui.screens

import com.virlin.app.domain.model.*
import java.time.Instant

/** Read-only identity/time presentation; never changes execution or estimates. */
object WhenFreePresentation {
    fun hierarchy(id: String, projects: List<Project>, tasks: List<Task>, streams: List<WorkStream>): String {
        val task = tasks.firstOrNull { it.id == id }
        val ancestry = task?.let { TaskHierarchy.ancestry(tasks, it.id) }.orEmpty()
        val owner = streams.firstOrNull { it.id == (task?.workStreamId ?: ancestry.firstOrNull { it.workStreamId != null }?.workStreamId ?: id) }
        val project = ProjectIdentity.resolveWorkItem(id, task?.projectId ?: owner?.projectId, projects, tasks, streams)
        return (ancestry.drop(1).map { it.title } + listOfNotNull(owner?.title.takeIf { task != null }, project?.title))
            .joinToString(" › ")
    }

    fun focusedLabel(id: String, tasks: List<Task>, sessions: List<FocusSession>, now: Instant): String {
        val unique = sessions.distinctBy { it.id }
        val seconds = if (tasks.any { it.id == id }) FocusInvestment.total(unique, id, now).seconds
            else unique.filter { it.workStreamId == id }.sumOf { session ->
                try { session.duration(now).seconds.coerceAtLeast(0L) } catch (_: Exception) { 0L }
            }
        return "Focused " + if (seconds == 0L) "0m" else FocusInvestment.formatCompact(seconds)
    }
}

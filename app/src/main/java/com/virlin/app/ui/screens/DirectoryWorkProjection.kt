package com.virlin.app.ui.screens

import com.virlin.app.domain.model.*
import java.time.Instant

data class DirectoryWorkItem(val work: WorkStream, val task: Task?, val project: Project?,
                             val hierarchy: String, val path: List<String>, val timing: String)

/** Exact leaves, never synthetic parent activity; all views share this projection. */
object DirectoryWorkProjection {
    fun matches(work: WorkStream, filter: ProjectDirectoryFilter, now: Instant): Boolean {
        val state = effectiveAttentionState(work, now)
        return when (filter) {
            ProjectDirectoryFilter.ALL -> true
            ProjectDirectoryFilter.NEED_YOU -> state == WorkStreamState.CHECK
            ProjectDirectoryFilter.PROCESSING -> !work.externalTrackingPaused &&
                (work.state == WorkStreamState.PROCESSING || (work.state == WorkStreamState.CHECK && work.processingStartedAt != null))
            ProjectDirectoryFilter.READY -> state == WorkStreamState.READY && !work.externalTrackingPaused
            ProjectDirectoryFilter.FREE_FOCUS -> state == WorkStreamState.FOCUS
            ProjectDirectoryFilter.PAUSED -> work.externalTrackingPaused || state == WorkStreamState.PAUSED
            ProjectDirectoryFilter.SNOOZED -> state == WorkStreamState.SNOOZED
            ProjectDirectoryFilter.BLOCKED -> state == WorkStreamState.BLOCKED
        }
    }

    fun items(projects: List<Project>, streams: List<WorkStream>, tasks: List<Task>,
              sessions: List<FocusSession>, filter: ProjectDirectoryFilter, query: String, now: Instant): List<DirectoryWorkItem> {
        val parents = tasks.mapNotNull { it.parentTaskId }.toSet()
        val ownerIds = tasks.mapNotNull { it.workStreamId }.toSet()
        val candidates = tasks.filter { !it.status.isTerminal && it.id !in parents }.map { it.executionStream() to it } +
            streams.filter { !it.state.isTerminal && it.id !in ownerIds }.map { it to null }
        return candidates.mapNotNull { (work, task) ->
            if (!matches(work, filter, now)) return@mapNotNull null
            val project = ProjectIdentity.resolveWorkItem(work.id, work.projectId, projects, tasks, streams)
            val hierarchy = WhenFreePresentation.hierarchy(work.id, projects, tasks, streams)
            if (query.isNotBlank() && listOf(work.title, hierarchy, project?.title.orEmpty()).none { it.contains(query, true) }) return@mapNotNull null
            val seconds = com.virlin.app.domain.model.ExternalTiming.seconds(work, now)
            val timing = if (work.processingStartedAt != null || work.externalTrackingPaused || seconds > 0)
                "External ${if (seconds == 0L) "0m" else FocusInvestment.formatCompact(seconds)}"
                else WhenFreePresentation.focusedLabel(work.id, tasks, sessions, now)
            DirectoryWorkItem(work, task, project, hierarchy,
                task?.let { TaskHierarchy.ancestry(tasks, it.id)?.reversed()?.map { node -> node.id } }.orEmpty(), timing)
        }.sortedWith(compareBy({ it.project?.title.orEmpty() }, { it.task?.workStreamId ?: it.work.id }, { it.task?.order ?: 0 }, { it.work.id }))
    }
}

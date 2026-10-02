package com.virlin.app.domain.model

import java.time.Duration
import java.time.Instant

/**
 * Read-only execution and timing projection shared by Project, WorkStream and future Task detail
 * surfaces. Persisted timestamps remain the source of truth; this object never stores counters.
 *
 * A focus session is attributed to one exact Task. Parent time is a live roll-up of descendant
 * sessions, WorkStream time is the sum of its sessions, and Project time is the sum of its
 * WorkStreams. Reordering or renaming therefore cannot rewrite history.
 */
data class WorkStreamExecutionTiming(
    val stateName: String,
    val summary: String,
    val currentSessionSeconds: Long,
    val totalFocusSeconds: Long,
    val exactTaskFocusSeconds: Map<String, Long>,
    val inclusiveTaskFocusSeconds: Map<String, Long>
)

object ExecutionTiming {
    fun workStream(
        stream: WorkStream,
        tasks: Collection<Task>,
        sessions: Collection<FocusSession>,
        now: Instant
    ): WorkStreamExecutionTiming {
        val mine = sessions.filter { it.workStreamId == stream.id }
        val exact = mine.mapNotNull { session ->
            session.taskId?.let { it to safeSeconds(session, now) }
        }.groupingBy { it.first }.fold(0L) { total, pair -> total + pair.second }

        val streamTasks = tasks.filter { it.workStreamId == stream.id }
        val children = streamTasks.groupBy { it.parentTaskId }
        val inclusive = streamTasks.associate { task ->
            task.id to descendantFocus(task.id, children, exact, mutableSetOf())
        }
        val open = mine.lastOrNull { it.isOpen }
        val current = open?.let { safeSeconds(it, now) } ?: 0L
        val total = mine.sumOf { safeSeconds(it, now) }
        val activeTitle = stream.activeTaskId?.let { id -> streamTasks.firstOrNull { it.id == id }?.title }
        val stateName = when (stream.state) {
            WorkStreamState.FOCUS -> "Focusing"
            WorkStreamState.PROCESSING -> "Running"
            WorkStreamState.CHECK -> "Needs you"
            WorkStreamState.READY -> "Ready"
            WorkStreamState.SNOOZED -> "Waiting"
            WorkStreamState.BLOCKED -> "Blocked"
            WorkStreamState.PAUSED -> "Paused"
            WorkStreamState.DONE -> "Complete"
        }
        val timing = when (stream.state) {
            WorkStreamState.FOCUS -> formatClock(current)
            WorkStreamState.PROCESSING -> stream.processingStartedAt?.let { elapsedSeconds(it, now) }
                ?.let { "${FocusInvestment.formatCompact(it)} running" }
            WorkStreamState.SNOOZED -> stream.checkAt?.let { Duration.between(now, it).seconds.coerceAtLeast(0L) }
                ?.let { "${FocusInvestment.formatCompact(it)} left" }
            WorkStreamState.DONE -> total.takeIf { it > 0L }
                ?.let { "${FocusInvestment.formatCompact(it)} focused" }
            else -> null
        }
        val summary = listOfNotNull(stateName, activeTitle, timing).joinToString(" · ")
        return WorkStreamExecutionTiming(stateName, summary, current, total, exact, inclusive)
    }

    fun projectFocusSeconds(
        projectId: String,
        streams: Collection<WorkStream>,
        sessions: Collection<FocusSession>,
        now: Instant
    ): Long {
        val ids = streams.asSequence().filter { it.projectId == projectId }.map { it.id }.toSet()
        return sessions.asSequence().filter { it.workStreamId in ids }.sumOf { safeSeconds(it, now) }
    }

    fun formatClock(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3_600L
        val minutes = (safe % 3_600L) / 60L
        val secs = safe % 60L
        return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, secs)
        else "%02d:%02d".format(minutes, secs)
    }

    private fun descendantFocus(
        taskId: String,
        children: Map<String?, List<Task>>,
        exact: Map<String, Long>,
        visiting: MutableSet<String>
    ): Long {
        if (!visiting.add(taskId)) return 0L
        val total = exact.getOrDefault(taskId, 0L) + children[taskId].orEmpty()
            .sumOf { descendantFocus(it.id, children, exact, visiting) }
        visiting.remove(taskId)
        return total
    }

    private fun elapsedSeconds(start: Instant, now: Instant): Long =
        runCatching { Duration.between(start, now).seconds.coerceAtLeast(0L) }.getOrDefault(0L)

    private fun safeSeconds(session: FocusSession, now: Instant): Long =
        runCatching { session.duration(now).seconds.coerceAtLeast(0L) }.getOrDefault(0L)
}

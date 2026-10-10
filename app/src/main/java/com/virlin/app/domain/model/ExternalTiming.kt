package com.virlin.app.domain.model

import java.time.Duration
import java.time.Instant

/** Saved closed segments plus the live segment. Check deadlines never measure run duration.
 * Parallel runs add as task-time (two ten-minute runs contribute twenty minutes).
 * Inclusive task totals are display-only: never persist them back onto parent executions.
 */
object ExternalTiming {
    fun seconds(stream: WorkStream, now: Instant): Long = seconds(TaskExecution.of(stream), now)
    fun seconds(execution: TaskExecution?, now: Instant): Long {
        if (execution == null) return 0
        val live = execution.processingStartedAt?.takeUnless { execution.externalTrackingPaused }
            ?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0
        return execution.externalTrackedSeconds.coerceAtLeast(0) + live
    }
    fun hasTime(execution: TaskExecution?): Boolean = execution != null &&
        (execution.externalTrackedSeconds > 0 || execution.processingStartedAt != null)
    fun inclusive(tasks: Collection<Task>, now: Instant): Map<String, Long> {
        val byId = tasks.associateBy { it.id }
        val totals = byId.keys.associateWith { 0L }.toMutableMap()
        // Walk ancestors iteratively: arbitrary nesting cannot overflow the call stack.
        byId.values.forEach { task ->
            val time = seconds(task.execution, now)
            var current: Task? = task
            val visited = mutableSetOf<String>()
            while (current != null && visited.add(current.id)) {
                totals[current.id] = totals.getValue(current.id) + time
                current = current.parentTaskId?.let(byId::get)
            }
        }
        return totals
    }
    fun workStream(stream: WorkStream, tasks: Collection<Task>, now: Instant): Long =
        seconds(stream, now) + tasks.filter { it.workStreamId == stream.id }.distinctBy { it.id }
            .sumOf { seconds(it.execution, now) }
    fun project(projectId: String, streams: Collection<WorkStream>, tasks: Collection<Task>, now: Instant): Long {
        val owned = streams.filter { it.projectId == projectId }.distinctBy { it.id }
        val ids = owned.mapTo(hashSetOf()) { it.id }
        // Older task records may lack projectId; a workstream is their canonical owner.
        return owned.sumOf { seconds(it, now) } + tasks.filter {
            if (it.workStreamId != null) it.workStreamId in ids else it.projectId == projectId
        }.distinctBy { it.id }.sumOf { seconds(it.execution, now) }
    }
}

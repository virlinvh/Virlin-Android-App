package com.virlin.app.domain.model

import java.time.Instant
import java.time.ZoneId

data class SavingsEstimate(val seconds: Long, val estimatedTasks: Int, val missingEstimates: Int) {
    val displayValue: String get() = when {
        estimatedTasks == 0 && missingEstimates == 0 -> "—"
        estimatedTasks == 0 -> "No estimate"
        else -> DailyFocus.label(seconds)
    }
    val displayUnit: String get() = when {
        estimatedTasks == 0 && missingEstimates == 0 -> "est. saved"
        estimatedTasks == 0 -> "saved today"
        missingEstimates > 0 -> "partial est."
        else -> "est. saved"
    }
}

/** Estimated avoided manual effort, credited on completion day, not external wall-clock time. */
object DailySavings {
    fun calculate(tasks: Collection<Task>, sessions: Collection<FocusSession>, now: Instant, zone: ZoneId): SavingsEstimate {
        val unique = tasks.distinctBy { it.id }
        val parents = unique.mapNotNull { it.parentTaskId }.toSet()
        val today = now.atZone(zone).toLocalDate()
        val eligible = unique.filter { task ->
            task.status == TaskStatus.DONE && task.id !in parents &&
                task.completedAt?.let { it <= now && it.atZone(zone).toLocalDate() == today } == true &&
                (task.execution?.externalTrackedSeconds ?: 0L) > 0L
        }
        val human = sessions.distinctBy { it.id }.groupBy { it.taskId }
        var missing = 0
        var estimated = 0
        val seconds = eligible.sumOf { task ->
            val effort = task.estimatedEffort?.seconds?.takeIf { it > 0 }
            if (effort == null) { missing++; 0L }
            else {
                estimated++
                val end = requireNotNull(task.completedAt)
                val spent = human[task.id].orEmpty().sumOf { session ->
                    val stop = minOf(session.endedAt ?: end, end)
                    java.time.Duration.between(session.startedAt, stop).seconds.coerceAtLeast(0)
                }
                (effort - spent).coerceAtLeast(0)
            }
        }
        return SavingsEstimate(seconds, estimated, missing)
    }
}

package com.virlin.app.domain.action

import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.WorkStreamRepository
import java.time.Duration
import java.time.Instant

/** End legacy container activity without choosing a child, losing totals, or rewriting history. */
suspend fun reconcileContainerExecution(repository: WorkStreamRepository, now: Instant) {
    if (!repository.taskExecutionEnabled) return
    repository.transaction {
        val tasks = allTasks()
        fun TaskExecution.stopped() = copy(state = WorkStreamState.READY,
            externalTrackedSeconds = externalTrackedSeconds + (processingStartedAt?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0),
            processingStartedAt = null, externalTrackingPaused = false, checkAt = null, snoozeReason = null,
            blockerReason = null, currentCycleId = null)
        val owners = allStreams().associateBy { it.id }
        val byId = tasks.associateBy { it.id }
        tasks.filter { task ->
            val activity = task.execution ?: return@filter false
            if (task.status.isTerminal) return@filter false
            val owner = owners[task.workStreamId]
            val mode = owner?.let { ExecutionModeResolver.resolveTask(task, byId, it,
                task.projectId?.let { id -> getProject(id)?.defaultExecutionMode }) }
            !ExecutionEligibility.isLeaf(task.id, tasks) ||
                (mode == EffectiveExecutionMode.EXTERNAL && activity.snoozeReason != SnoozeReason.EXTERNAL_RESULT_READY &&
                    !activity.externalTrackingPaused && activity.processingStartedAt == null && activity.state in setOf(WorkStreamState.FOCUS, WorkStreamState.PAUSED, WorkStreamState.SNOOZED)) ||
                (mode == EffectiveExecutionMode.HUMAN && (activity.processingStartedAt != null || activity.externalTrackingPaused))
        }.forEach { task ->
            val activity = task.execution!!
            if (activity.state != WorkStreamState.READY || activity.checkAt != null || activity.processingStartedAt != null) {
                task.workStreamId?.let { getOpenFocusSession(it) }?.takeIf { it.taskId == task.id }?.let { saveFocusSession(it.copy(endedAt = now)) }
                activity.currentCycleId?.let { getCycle(it) }?.let { saveCycle(it.copy(endedAt = now)) }
                saveTask(task.copy(execution = activity.stopped(), updatedAt = now))
            }
        }
        allStreams().filter { !ExecutionEligibility.isLeaf(it.id, tasks) && !it.state.isTerminal }.forEach { stream ->
            if (stream.state != WorkStreamState.READY || stream.checkAt != null || stream.processingStartedAt != null) {
                getOpenFocusSession(stream.id)?.takeIf { it.taskId == null || !ExecutionEligibility.isLeaf(it.taskId, tasks) }
                    ?.let { saveFocusSession(it.copy(endedAt = now)) }
                val stopped = TaskExecution.of(stream).stopped()
                saveStream(stream.copy(state = WorkStreamState.READY, activeTaskId = null,
                    processingStartedAt = null, externalTrackedSeconds = stopped.externalTrackedSeconds,
                    externalTrackingPaused = false, checkAt = null, snoozedUntil = null, snoozeReason = null,
                    blockerReason = null, currentCycleId = null, updatedAt = now))
            }
        }
    }
}

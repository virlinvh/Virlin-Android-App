package com.virlin.app.domain.model

import java.time.Instant

/** Independent activity of one exact task. Preference/inheritance is intentionally separate. */
data class TaskExecution(
    val state: WorkStreamState = WorkStreamState.READY,
    val processingStartedAt: Instant? = null,
    val externalTrackedSeconds: Long = 0,
    val externalTrackingPaused: Boolean = false,
    val checkAt: Instant? = null,
    val snoozeReason: SnoozeReason? = null,
    val blockerReason: String? = null,
    val currentCycleId: String? = null,
    val cycleCount: Int = 0,
    val actorId: String? = null,
    val tool: String? = null,
    val instruction: String? = null,
) {
    companion object {
        fun of(stream: WorkStream) = TaskExecution(stream.state, stream.processingStartedAt,
            stream.externalTrackedSeconds, stream.externalTrackingPaused, stream.checkAt,
            stream.snoozeReason, stream.blockerReason, stream.currentCycleId, stream.cycleCount,
            stream.externalActorId, stream.tool, stream.waitingFor)
    }
}

/** Read/action adapter only; never inserted into the workstreams table. Identity is the task id. */
fun Task.executionStream(): WorkStream {
    val activity = execution ?: TaskExecution(if (status.isTerminal) WorkStreamState.DONE else WorkStreamState.READY)
    return WorkStream(id, title, projectId = projectId, executionPreference = executionPreference,
        state = activity.state, activeTaskId = id, createdAt = createdAt, updatedAt = updatedAt,
        processingStartedAt = activity.processingStartedAt, externalTrackedSeconds = activity.externalTrackedSeconds,
        externalTrackingPaused = activity.externalTrackingPaused, checkAt = activity.checkAt,
        snoozedUntil = activity.checkAt.takeIf { activity.state == WorkStreamState.SNOOZED },
        snoozeReason = activity.snoozeReason, blockerReason = activity.blockerReason,
        currentCycleId = activity.currentCycleId, cycleCount = activity.cycleCount,
        externalActorId = activity.actorId, tool = activity.tool, waitingFor = activity.instruction)
}

/** Live queues contain exact task executions plus explicit task-less compatibility activity. */
fun executionItems(streams: List<WorkStream>, tasks: List<Task>): List<WorkStream> =
    streams.filter { stream -> (ExecutionEligibility.isLeaf(stream.id, tasks) || tasks.none { it.workStreamId == stream.id && it.execution != null }) && (stream.state != WorkStreamState.READY || tasks.none {
        it.workStreamId == stream.id && it.execution != null && !it.status.isTerminal
    }) } + tasks.filter { it.execution != null && ExecutionEligibility.isLeaf(it.id, tasks) }.map { task ->
        task.executionStream().copy(title = streams.firstOrNull { it.id == task.workStreamId }?.title ?: task.title)
    }

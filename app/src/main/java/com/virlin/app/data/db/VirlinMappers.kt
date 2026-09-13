package com.virlin.app.data.db

import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.ContextSnapshot
import com.virlin.app.domain.model.Cycle
import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.Priority
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.ProjectStatus
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamEvent
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState

/**
 * Entity ↔ domain mapping. Total and deterministic: every domain field has a column and
 * comes back unchanged (round-trip tested). Enums travel as their `name`.
 */
object VirlinMappers {

    fun Project.toEntity() = ProjectEntity(id, title, description, status.name, priority.name, dueAt, estimatedEffort, createdAt, updatedAt, completedAt)
    fun ProjectEntity.toDomain() = Project(id, title, description, ProjectStatus.valueOf(status), Priority.valueOf(priority), dueAt, estimatedEffort, createdAt, updatedAt, completedAt)

    fun WorkStream.toEntity() = WorkStreamEntity(
        id = id, title = title, projectId = projectId, tool = tool, mode = mode.name, state = state.name,
        priority = priority.name, pinned = pinned, lastHumanAction = lastHumanAction, waitingFor = waitingFor,
        nextHumanAction = nextHumanAction, blockerReason = blockerReason, processingStartedAt = processingStartedAt,
        checkAt = checkAt, snoozedUntil = snoozedUntil, snoozeReason = snoozeReason?.name, currentCycleId = currentCycleId,
        cycleCount = cycleCount, activeTaskId = activeTaskId, createdAt = createdAt, updatedAt = updatedAt, completedAt = completedAt
    )
    fun WorkStreamEntity.toDomain() = WorkStream(
        id = id, title = title, projectId = projectId, tool = tool, mode = WorkStreamMode.valueOf(mode),
        state = WorkStreamState.valueOf(state), priority = Priority.valueOf(priority), pinned = pinned,
        lastHumanAction = lastHumanAction, waitingFor = waitingFor, nextHumanAction = nextHumanAction,
        blockerReason = blockerReason, processingStartedAt = processingStartedAt, checkAt = checkAt,
        snoozedUntil = snoozedUntil, snoozeReason = snoozeReason?.let(SnoozeReason::valueOf),
        currentCycleId = currentCycleId, cycleCount = cycleCount, activeTaskId = activeTaskId,
        createdAt = createdAt, updatedAt = updatedAt, completedAt = completedAt
    )

    fun Task.toEntity() = TaskEntity(
        id = id, title = title, description = description, projectId = projectId, workStreamId = workStreamId,
        parentTaskId = parentTaskId, status = status.name, order = order, estimatedEffort = estimatedEffort,
        dueAt = dueAt, reminderAt = reminderAt, priority = priority.name, notes = notes,
        createdAt = createdAt, updatedAt = updatedAt, completedAt = completedAt
    )
    fun TaskEntity.toDomain() = Task(
        id = id, title = title, description = description, projectId = projectId, workStreamId = workStreamId,
        parentTaskId = parentTaskId, status = TaskStatus.valueOf(status), order = order, estimatedEffort = estimatedEffort,
        dueAt = dueAt, reminderAt = reminderAt, priority = Priority.valueOf(priority), notes = notes,
        createdAt = createdAt, updatedAt = updatedAt, completedAt = completedAt
    )

    fun CaptureItem.toEntity() = CaptureEntity(
        id = id, type = type.name, content = content, title = title, sourceUrl = sourceUrl, projectId = projectId,
        workStreamId = workStreamId, taskId = taskId, status = status.name, convertedTaskId = convertedTaskId,
        createdAt = createdAt, updatedAt = updatedAt, archivedAt = archivedAt
    )
    fun CaptureEntity.toDomain() = CaptureItem(
        id = id, type = CaptureType.valueOf(type), content = content, title = title, sourceUrl = sourceUrl, projectId = projectId,
        workStreamId = workStreamId, taskId = taskId, status = CaptureStatus.valueOf(status), convertedTaskId = convertedTaskId,
        createdAt = createdAt, updatedAt = updatedAt, archivedAt = archivedAt
    )

    fun Cycle.toEntity(seq: Long) = CycleEntity(id, workStreamId, number, startedAt, handedOffAt, endedAt, seq)
    fun CycleEntity.toDomain() = Cycle(id, workStreamId, number, startedAt, handedOffAt, endedAt)

    fun FocusSession.toEntity(seq: Long) = FocusSessionEntity(id, workStreamId, cycleId, startedAt, endedAt, taskId, seq)
    fun FocusSessionEntity.toDomain() = FocusSession(id, workStreamId, cycleId, startedAt, endedAt, taskId)

    fun ContextSnapshot.toEntity(seq: Long) = ContextSnapshotEntity(
        id, workStreamId, cycleId, createdAt, reason.name, lastHumanAction, waitingFor, nextHumanAction, checkAt, contextLabel, note, taskId, seq
    )
    fun ContextSnapshotEntity.toDomain() = ContextSnapshot(
        id, workStreamId, cycleId, createdAt, WorkStreamState.valueOf(reason), lastHumanAction, waitingFor, nextHumanAction, checkAt, contextLabel, note, taskId
    )

    fun WorkStreamEvent.toEntity(seq: Long) = EventEntity(id, workStreamId, type.name, at, cycleId, fromState?.name, toState?.name, detail, seq)
    fun EventEntity.toDomain() = WorkStreamEvent(
        id, workStreamId, EventType.valueOf(type), at, cycleId, fromState?.let(WorkStreamState::valueOf), toState?.let(WorkStreamState::valueOf), detail
    )
}

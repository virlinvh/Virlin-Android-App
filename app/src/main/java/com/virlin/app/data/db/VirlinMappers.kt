package com.virlin.app.data.db

import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.ContextSnapshot
import com.virlin.app.domain.model.Cycle
import com.virlin.app.domain.model.ExternalStage
import com.virlin.app.domain.model.ExternalStageStatus
import com.virlin.app.domain.model.EffectiveExecutionMode
import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.NoteDocument
import com.virlin.app.domain.model.Priority
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.ProjectStatus
import com.virlin.app.domain.model.AttachmentDocument
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.domain.model.PromptDocument
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.attention.PriorityPreference
import com.virlin.app.domain.attention.PriorityScope
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamEvent
import com.virlin.app.domain.model.WorkStreamState

/**
 * Entity ↔ domain mapping. Total and deterministic: every domain field has a column and
 * comes back unchanged (round-trip tested). Enums travel as their `name`.
 */
object VirlinMappers {

    fun Project.toEntity() = ProjectEntity(
        id, title, description, status.name, priority.name, dueAt, estimatedEffort,
        defaultExecutionMode.name, createdAt, updatedAt, completedAt, iconPath, iconId
    )
    fun ProjectEntity.toDomain() = Project(
        id, title, description, ProjectStatus.valueOf(status), Priority.valueOf(priority),
        dueAt, estimatedEffort, EffectiveExecutionMode.valueOf(defaultExecutionMode),
        createdAt, updatedAt, completedAt, iconPath, iconId
    )

    fun WorkStream.toEntity() = WorkStreamEntity(
        id = id, title = title, projectId = projectId, tool = tool,
        executionPreference = executionPreference.name, state = state.name,
        priority = priority.name, pinned = pinned, lastHumanAction = lastHumanAction, waitingFor = waitingFor,
        nextHumanAction = nextHumanAction, blockerReason = blockerReason, processingStartedAt = processingStartedAt,
        checkAt = checkAt, snoozedUntil = snoozedUntil, snoozeReason = snoozeReason?.name, currentCycleId = currentCycleId,
        cycleCount = cycleCount, activeTaskId = activeTaskId, createdAt = createdAt, updatedAt = updatedAt, completedAt = completedAt,
        attentionRank = attentionRank, externalActorId = externalActorId
    )
    fun WorkStreamEntity.toDomain() = WorkStream(
        id = id, title = title, projectId = projectId, tool = tool,
        executionPreference = ExecutionPreference.valueOf(executionPreference),
        state = WorkStreamState.valueOf(state), priority = Priority.valueOf(priority), pinned = pinned,
        lastHumanAction = lastHumanAction, waitingFor = waitingFor, nextHumanAction = nextHumanAction,
        blockerReason = blockerReason, processingStartedAt = processingStartedAt, checkAt = checkAt,
        snoozedUntil = snoozedUntil, snoozeReason = snoozeReason?.let(SnoozeReason::valueOf),
        currentCycleId = currentCycleId, cycleCount = cycleCount, activeTaskId = activeTaskId,
        createdAt = createdAt, updatedAt = updatedAt, completedAt = completedAt, attentionRank = attentionRank,
        externalActorId = externalActorId
    )

    fun ExternalStage.toEntity() = ExternalStageEntity(
        id = id, workStreamId = workStreamId, title = title, order = order,
        expectedMinutes = expectedMinutes, status = status.name, startedAt = startedAt, completedAt = completedAt
    )
    fun ExternalStageEntity.toDomain() = ExternalStage(
        id = id, workStreamId = workStreamId, title = title, order = order,
        expectedMinutes = expectedMinutes, status = ExternalStageStatus.valueOf(status),
        startedAt = startedAt, completedAt = completedAt
    )

    fun Task.toEntity() = TaskEntity(
        id = id, title = title, description = description, projectId = projectId, workStreamId = workStreamId,
        parentTaskId = parentTaskId, status = status.name, order = order, estimatedEffort = estimatedEffort,
        dueAt = dueAt, reminderAt = reminderAt, priority = priority.name, notes = notes,
        executionPreference = executionPreference.name,
        createdAt = createdAt, updatedAt = updatedAt, completedAt = completedAt
    )
    fun TaskEntity.toDomain() = Task(
        id = id, title = title, description = description, projectId = projectId, workStreamId = workStreamId,
        parentTaskId = parentTaskId, status = TaskStatus.valueOf(status), order = order, estimatedEffort = estimatedEffort,
        dueAt = dueAt, reminderAt = reminderAt, priority = Priority.valueOf(priority), notes = notes,
        executionPreference = ExecutionPreference.valueOf(executionPreference),
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

    fun NoteDocument.toEntity() = NoteDocumentEntity(
        id = id,
        captureItemId = captureItemId,
        title = title,
        documentJson = com.virlin.app.domain.note.NoteDocumentCodec.encodePayload(blocks),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    fun NoteDocumentEntity.toDomain() = com.virlin.app.domain.note.NoteDocumentCodec.decodeInto(
        com.virlin.app.domain.note.NoteDocumentCodec.NoteDocumentMeta(
            id = id, captureItemId = captureItemId, title = title, createdAt = createdAt, updatedAt = updatedAt
        ),
        documentJson
    )

    fun PromptDocument.toEntity() = PromptDocumentEntity(
        id = id,
        captureItemId = captureItemId,
        title = title,
        description = description,
        tagsJson = com.virlin.app.domain.prompt.PromptDocumentCodec.encodeTags(tags),
        documentJson = com.virlin.app.domain.prompt.PromptDocumentCodec.encodeBlocks(blocks),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    fun PromptDocumentEntity.toDomain() = com.virlin.app.domain.prompt.PromptDocumentCodec.decodeInto(
        com.virlin.app.domain.prompt.PromptDocumentCodec.Meta(
            id = id,
            captureItemId = captureItemId,
            title = title,
            description = description,
            tagsJson = tagsJson,
            createdAt = createdAt,
            updatedAt = updatedAt
        ),
        documentJson
    )

    fun AttachmentDocument.toEntity() = AttachmentDocumentEntity(
        id = id,
        captureItemId = captureItemId,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        relativePath = relativePath,
        kind = kind.name,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    fun AttachmentDocumentEntity.toDomain() = AttachmentDocument(
        id = id,
        captureItemId = captureItemId,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        relativePath = relativePath,
        kind = runCatching { AttachmentKind.valueOf(kind) }.getOrDefault(AttachmentKind.UNSUPPORTED),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    fun com.virlin.app.domain.model.VoiceDocument.toEntity() = VoiceDocumentEntity(
        id = id,
        captureItemId = captureItemId,
        title = title,
        clipsJson = com.virlin.app.domain.voice.VoiceDocumentCodec.encodeClips(clips),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    fun VoiceDocumentEntity.toDomain() = com.virlin.app.domain.voice.VoiceDocumentCodec.decodeInto(
        com.virlin.app.domain.voice.VoiceDocumentCodec.Meta(
            id = id,
            captureItemId = captureItemId,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt
        ),
        clipsJson
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

    fun WorkStreamEvent.toEntity(seq: Long) = EventEntity(
        id, workStreamId, type.name, at, cycleId, fromState?.name, toState?.name, detail, seq
    )
    fun EventEntity.toDomain() = WorkStreamEvent(
        id, workStreamId, EventType.valueOf(type), at, cycleId,
        fromState?.let(WorkStreamState::valueOf), toState?.let(WorkStreamState::valueOf), detail
    )

    // ---------------------------------------------------------------- priority preferences (v11)

    fun PriorityPreference.toEntity() = PriorityPreferenceEntity(
        streamId = streamId, preferredPosition = preferredPosition,
        scopeType = when (scope) {
            PriorityScope.Always -> "ALWAYS"
            PriorityScope.CurrentTerm -> "CURRENT_TERM"
            is PriorityScope.Until -> "UNTIL"
            PriorityScope.OneTime -> error("OneTime is transient and is never stored")
        },
        createdAt = createdAt,
        expiresAt = (scope as? PriorityScope.Until)?.expiresAt
    )

    /** Unknown/corrupt scope values read as null so a bad row can never crash attention. */
    fun PriorityPreferenceEntity.toDomain(): PriorityPreference? {
        val scope = when (scopeType) {
            "ALWAYS" -> PriorityScope.Always
            "CURRENT_TERM" -> PriorityScope.CurrentTerm
            "UNTIL" -> expiresAt?.let { PriorityScope.Until(it) }
            else -> null
        } ?: return null
        return PriorityPreference(streamId, preferredPosition, scope, createdAt)
    }
}

package com.virlin.app.domain.action

import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.WorkStreamWriter

/** Reuses the transition engine with independent task records, inside the SAME transaction. */
internal class TaskExecutionWriter(private val delegate: WorkStreamWriter) : WorkStreamWriter by delegate {
    override suspend fun getStream(id: String): WorkStream? {
        val task = delegate.getTask(id) ?: return delegate.getStream(id)
        val owner = task.workStreamId?.let { delegate.getStream(it) } ?: return task.executionStream()
        val mode = ExecutionModeResolver.resolveTask(task, delegate.allTasks().associateBy { it.id }, owner,
            task.projectId?.let { delegate.getProject(it)?.defaultExecutionMode })
        return task.executionStream().copy(executionPreference = if (mode == EffectiveExecutionMode.HUMAN) ExecutionPreference.HUMAN else ExecutionPreference.EXTERNAL)
    }

    override suspend fun allStreams(): List<WorkStream> = executionItems(delegate.allStreams(), delegate.allTasks())

    override suspend fun getActiveFocus(): WorkStream? =
        delegate.allTasks().firstOrNull { !it.status.isTerminal && it.execution?.state == WorkStreamState.FOCUS }
            ?.executionStream() ?: delegate.getActiveFocus()

    override suspend fun saveStream(stream: WorkStream) {
        val task = delegate.getTask(stream.id)
        if (task == null) delegate.saveStream(stream)
        else delegate.saveTask(task.copy(execution = TaskExecution.of(stream), updatedAt = stream.updatedAt))
    }

    override suspend fun getOpenFocusSession(streamId: String): FocusSession? {
        val task = delegate.getTask(streamId) ?: return delegate.getOpenFocusSession(streamId)
        return task.workStreamId?.let { delegate.getOpenFocusSession(it) }?.takeIf { it.taskId == task.id }
    }

    override suspend fun getCurrentCycle(streamId: String): Cycle? {
        val task = delegate.getTask(streamId) ?: return delegate.getCurrentCycle(streamId)
        return task.execution?.currentCycleId?.let { delegate.getCycle(it) }
    }

    override suspend fun saveFocusSession(session: FocusSession) {
        val task = delegate.getTask(session.workStreamId)
        delegate.saveFocusSession(if (task == null) session else session.copy(
            workStreamId = requireNotNull(task.workStreamId), taskId = task.id))
    }
    override suspend fun saveCycle(cycle: Cycle) {
        val task = delegate.getTask(cycle.workStreamId)
        delegate.saveCycle(if (task == null) cycle else cycle.copy(workStreamId = requireNotNull(task.workStreamId)))
    }
    override suspend fun saveSnapshot(snapshot: ContextSnapshot) {
        val task = delegate.getTask(snapshot.workStreamId)
        delegate.saveSnapshot(if (task == null) snapshot else snapshot.copy(
            workStreamId = requireNotNull(task.workStreamId), taskId = task.id))
    }
    override suspend fun appendEvent(event: WorkStreamEvent) {
        val task = delegate.getTask(event.workStreamId)
        delegate.appendEvent(if (task == null) event else event.copy(workStreamId = requireNotNull(task.workStreamId),
            detail = listOfNotNull("task:${task.id}", event.detail).joinToString(" · ")))
    }
    override suspend fun executionEvents(streamId: String): List<WorkStreamEvent> {
        val task = delegate.getTask(streamId)
        return if (task == null) delegate.executionEvents(streamId).filter { it.detail?.startsWith("task:") != true }
        else delegate.executionEvents(task.workStreamId ?: return emptyList()).filter {
            it.detail == "task:${task.id}" || it.detail?.startsWith("task:${task.id} · ") == true
        }
    }
}

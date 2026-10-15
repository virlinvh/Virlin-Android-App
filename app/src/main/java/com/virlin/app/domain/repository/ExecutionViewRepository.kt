package com.virlin.app.domain.repository

import com.virlin.app.domain.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Presentation/scheduling view. Structure continues to observe the original repository. */
class ExecutionViewRepository(private val inner: WorkStreamRepository, scope: CoroutineScope) : WorkStreamRepository by inner {
    override val streams = combine(inner.streams, inner.tasks) { streams, tasks ->
        if (inner.taskExecutionEnabled) executionItems(streams, tasks) else streams
    }.stateIn(scope, SharingStarted.Eagerly,
        if (inner.taskExecutionEnabled) executionItems(inner.streams.value, inner.tasks.value) else inner.streams.value)
    override suspend fun getStream(id: String) = inner.getTask(id)?.executionStream() ?: inner.getStream(id)
    override suspend fun getActiveFocus() = streams.value.firstOrNull { it.state == WorkStreamState.FOCUS }
    override suspend fun getOpenFocusSession(streamId: String): FocusSession? {
        val task = inner.getTask(streamId) ?: return inner.getOpenFocusSession(streamId)
        return inner.getOpenFocusSession(task.workStreamId ?: task.id)?.takeIf { it.taskId == task.id }
    }
    override suspend fun getFocusSessions(streamId: String): List<FocusSession> {
        val task = inner.getTask(streamId) ?: return inner.getFocusSessions(streamId)
        return inner.getFocusSessions(task.workStreamId ?: task.id).filter { it.taskId == task.id }
    }
}

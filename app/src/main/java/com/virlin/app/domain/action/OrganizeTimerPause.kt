package com.virlin.app.domain.action

import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamEvent
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.WorkStreamWriter
import java.time.Instant

internal const val ORGANIZE_TIMERS_PAUSED = "Organize timers paused"
internal const val ALL_TIMERS_PAUSED = "All timers paused"

/**
 * The pause [item] is still sitting in, when its most recent STATE CHANGE was a timer pause
 * recorded with [provenance]. Edits that change no state (renames, reorders, new children,
 * notes) never hide the pause, so organizing a project cannot strand its timers.
 * Task items must be read through a [TaskExecutionWriter] so only that task's events count.
 */
internal suspend fun WorkStreamWriter.pendingTimerPause(item: WorkStream, provenance: String): WorkStreamEvent? {
    if (item.state != WorkStreamState.PAUSED) return null
    val last = executionEvents(item.id).lastOrNull { it.toState != null } ?: return null
    return last.takeIf { it.type == EventType.PAUSED && it.detail?.contains(provenance) == true }
}

/**
 * Organize mode lets a paused leaf take children. A task with children never runs itself, so its
 * paused timer is ended here (PAUSED -> READY) inside the caller's transaction. Recorded focus
 * sessions and banked external time stay exactly as the pause left them.
 * Returns false when [id] is not sitting in an Organize pause (nothing is written).
 */
internal suspend fun WorkStreamWriter.endOrganizePauseForChildren(id: String, now: Instant, newEventId: () -> String): Boolean {
    val writer = this as? TaskExecutionWriter ?: TaskExecutionWriter(this)
    val item = writer.getStream(id) ?: return false
    if (writer.pendingTimerPause(item, ORGANIZE_TIMERS_PAUSED) == null) return false
    item.currentCycleId?.let { writer.getCycle(it) }?.takeIf { it.endedAt == null }
        ?.let { writer.saveCycle(it.copy(endedAt = now)) }
    writer.saveStream(item.copy(state = WorkStreamState.READY, processingStartedAt = null,
        externalTrackingPaused = false, checkAt = null, snoozedUntil = null, snoozeReason = null,
        currentCycleId = null, updatedAt = now))
    writer.appendEvent(WorkStreamEvent(newEventId(), item.id, EventType.READY, now, item.currentCycleId,
        WorkStreamState.PAUSED, WorkStreamState.READY,
        "Organize: paused timer ended so this item can hold subtasks; recorded time kept"))
    return true
}

/** True when [id] is sitting in an Organize pause and may therefore be given children. */
internal suspend fun WorkStreamWriter.isOrganizePaused(id: String): Boolean {
    val writer = this as? TaskExecutionWriter ?: TaskExecutionWriter(this)
    val item = writer.getStream(id) ?: return false
    return writer.pendingTimerPause(item, ORGANIZE_TIMERS_PAUSED) != null
}

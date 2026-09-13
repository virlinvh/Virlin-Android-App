package com.virlin.app.platform

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.schedule.ScheduleKind
import java.time.Duration
import java.time.Instant

/**
 * Executes a notification action through the SAME [VirlinActions] the in-app Needs You cards
 * use. Pure domain + repository; no Android types, so it is unit-tested with the in-memory
 * repository and a fake clock and runs identically from a receiver with no Activity alive.
 *
 * Room is truth: the action is applied only if the stream still carries the semantic
 * condition the notification described. Anything else (resumed in-app meanwhile, DONE,
 * kind changed, double tap) is [Outcome.Stale] and changes nothing.
 */
object NotificationActionHandler {

    sealed interface Outcome {
        data class Applied(val action: NotificationAction) : Outcome
        data class Stale(val reason: String) : Outcome
        data class Failed(val error: String) : Outcome
    }

    /** Does the stream currently present as [kind] (SNOOZED before due, or the surfaced CHECK)? */
    fun presentsAs(stream: com.virlin.app.domain.model.WorkStream, kind: ScheduleKind): Boolean = when (kind) {
        ScheduleKind.HUMAN_RETURN ->
            stream.state in setOf(WorkStreamState.SNOOZED, WorkStreamState.CHECK) && stream.snoozeReason == SnoozeReason.HUMAN_RETURN
        ScheduleKind.EXTERNAL_RESULT_READY ->
            stream.state in setOf(WorkStreamState.SNOOZED, WorkStreamState.CHECK) && stream.snoozeReason == SnoozeReason.EXTERNAL_RESULT_READY
        ScheduleKind.EXTERNAL_CHECK ->
            (stream.state == WorkStreamState.CHECK && stream.snoozeReason == null) || stream.state == WorkStreamState.PROCESSING
    }

    suspend fun execute(
        repository: WorkStreamRepository, actions: VirlinActions,
        streamId: String, kind: ScheduleKind, action: NotificationAction, now: Instant
    ): Outcome {
        val stream = repository.getStream(streamId) ?: return Outcome.Stale("stream missing")
        if (stream.state.isTerminal) return Outcome.Stale("stream is ${stream.state}")
        if (!presentsAs(stream, kind)) return Outcome.Stale("no longer $kind (${stream.state}/${stream.snoozeReason})")
        if (!action.mutates) return Outcome.Stale("$action is navigation only")
        val allowed = AttentionNotificationModel.build(stream, null, kind).actions
        if (action !in allowed) return Outcome.Stale("$action not valid for $kind")

        val r: ActionResult<*> = when (action) {
            // Same accepted focus path as Needs You RESUME / FOCUS NOW: displacement, session
            // attributed to activeTaskId, snooze/processing fields cleared, scheduler synced.
            NotificationAction.RESUME, NotificationAction.FOCUS_NOW -> actions.focusStream(streamId)
            NotificationAction.DEFER_5 -> actions.deferReturn(streamId, now.plus(FIVE))
            NotificationAction.CHECK_AGAIN_5 -> actions.stillRunning(streamId, now.plus(FIVE))
            NotificationAction.CHECK -> return Outcome.Stale("navigation only")
        }
        return when (r) {
            is ActionResult.Success -> Outcome.Applied(action)
            is ActionResult.Rejected -> Outcome.Stale("rejected: ${r.reason}")
            is ActionResult.NotFound -> Outcome.Stale("not found")
            is ActionResult.Failure -> Outcome.Failed(r.cause.message ?: "failure")
        }
    }

    private val FIVE: Duration = Duration.ofMinutes(5)
}

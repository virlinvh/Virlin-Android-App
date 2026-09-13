package com.virlin.app.domain.schedule

import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import java.time.Instant

/**
 * The only three future attention events Virlin schedules. Semantic identity travels with
 * the schedule so a wake-up can be validated against Room and worded correctly.
 */
enum class ScheduleKind { HUMAN_RETURN, EXTERNAL_CHECK, EXTERNAL_RESULT_READY }

/** One pending wake-up for one WorkStream. A stream has at most one at a time. */
data class AttentionSchedule(val streamId: String, val kind: ScheduleKind, val dueAt: Instant) {
    companion object {
        /**
         * Pure derivation from persisted state — Room is the truth, this is just its shadow.
         * Null means "nothing to wake up for": READY, FOCUS, BLOCKED, DONE, PAUSED, PROCESSING
         * without a check time, SNOOZED without a time, or a CHECK that is already due/surfaced.
         */
        fun of(stream: WorkStream): AttentionSchedule? = when (stream.state) {
            WorkStreamState.PROCESSING -> stream.checkAt?.let { AttentionSchedule(stream.id, ScheduleKind.EXTERNAL_CHECK, it) }
            WorkStreamState.SNOOZED -> {
                val at = stream.snoozedUntil ?: stream.checkAt
                val kind = when (stream.snoozeReason) {
                    SnoozeReason.EXTERNAL_RESULT_READY -> ScheduleKind.EXTERNAL_RESULT_READY
                    SnoozeReason.HUMAN_RETURN, null -> ScheduleKind.HUMAN_RETURN
                }
                at?.let { AttentionSchedule(stream.id, kind, it) }
            }
            else -> null
        }
    }
}

/**
 * Narrow wake-up infrastructure. It never holds state of its own and never decides product
 * behaviour: callers hand it the schedule derived from committed Room state, or ask it to
 * clear a stream. Scheduling the same logical event again must be idempotent (replace).
 */
interface AttentionScheduler {
    /** Ensure exactly this wake-up exists for the stream (replacing any previous one). */
    fun schedule(schedule: AttentionSchedule)
    /** Remove any pending wake-up for the stream. */
    fun cancel(streamId: String)

    /** Bring the platform in line with one committed stream: schedule its event or cancel. */
    fun sync(stream: WorkStream) {
        val s = AttentionSchedule.of(stream)
        if (s != null) schedule(s) else cancel(stream.id)
    }

    /** Startup / boot self-healing: make every persisted future event real again. */
    fun reconcileAll(streams: List<WorkStream>) = streams.forEach(::sync)

    object NoOp : AttentionScheduler {
        override fun schedule(schedule: AttentionSchedule) = Unit
        override fun cancel(streamId: String) = Unit
    }
}

/** Deterministic scheduler for tests: records the current schedule per stream plus a log. */
class FakeAttentionScheduler : AttentionScheduler {
    val current = LinkedHashMap<String, AttentionSchedule>()
    val log = mutableListOf<String>()
    var failNext = false

    override fun schedule(schedule: AttentionSchedule) {
        if (failNext) { failNext = false; throw IllegalStateException("scheduler unavailable") }
        current[schedule.streamId] = schedule
        log += "schedule ${schedule.streamId} ${schedule.kind} ${schedule.dueAt}"
    }
    override fun cancel(streamId: String) {
        if (current.remove(streamId) != null) log += "cancel $streamId"
    }
}

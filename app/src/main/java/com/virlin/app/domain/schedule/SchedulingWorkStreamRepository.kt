package com.virlin.app.domain.schedule

import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter

/**
 * Decorator that keeps the Android wake-ups in step with COMMITTED state. It changes nothing
 * about persistence or product rules: after a transaction succeeds it looks at which streams'
 * attention timing changed and asks the [AttentionScheduler] to schedule or cancel.
 *
 * Ordering guarantee: domain validation → Room commit → scheduler. If the commit fails nothing
 * is scheduled. If the scheduler fails afterwards, Room remains truth, the failure is reported
 * to [onSchedulerError], and startup `reconcileAll` heals it later.
 */
class SchedulingWorkStreamRepository(
    private val inner: WorkStreamRepository,
    private val scheduler: AttentionScheduler,
    private val onSchedulerError: (Throwable) -> Unit = {}
) : WorkStreamRepository by inner {

    override suspend fun <T> transaction(block: suspend WorkStreamWriter.() -> T): T {
        val before = inner.streams.value.associateBy { it.id }
        val result = inner.transaction(block)                     // throws → nothing below runs
        val after = inner.streams.value
        after.filter { s -> before[s.id]?.let { timingKey(it) != timingKey(s) } ?: (AttentionSchedule.of(s) != null) }
            .forEach { s -> runCatching { scheduler.sync(s) }.onFailure(onSchedulerError) }
        return result
    }

    /** The part of a stream that decides whether/when it wakes the user. */
    private fun timingKey(s: WorkStream) = listOf(s.state, s.checkAt, s.snoozedUntil, s.snoozeReason)
}

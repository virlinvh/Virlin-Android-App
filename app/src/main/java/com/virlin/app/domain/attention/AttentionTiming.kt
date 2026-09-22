package com.virlin.app.domain.attention

import com.virlin.app.domain.model.WorkStream
import java.time.Duration
import java.time.Instant

/**
 * ATTENTION TIMING (Phase 05) — the one place that answers "when does this need me?".
 *
 * The temporal source of truth is the persisted `WorkStream.checkAt` (`dueAt` in product language);
 * everything else is DERIVED from `dueAt − now`. Nothing counts down in memory, nothing is written
 * per second, so backgrounding, configuration changes and process recreation cannot drift: the
 * display is recomputed from two timestamps.
 *
 * Time is NOT priority. A #1 item may be due in 20 minutes while a #4 item is 2 minutes overdue —
 * timing never reorders the Phase 03 queue.
 */
enum class TimeState {
    /** `now < dueAt` — the moment has not arrived yet. */
    WAITING,
    /** `now == dueAt` (to the second) — it is due right now. */
    DUE,
    /** `now > dueAt` — waiting past its due time. */
    OVERDUE
}

object AttentionTiming {

    /** The item's temporal target, or null when it has none (nothing is invented). */
    fun dueAt(stream: WorkStream): Instant? = stream.checkAt

    /** Whole seconds until [dueAt] (positive) or since it (negative). */
    fun secondsUntil(dueAt: Instant, now: Instant): Long = Duration.between(now, dueAt).seconds

    fun state(dueAt: Instant?, now: Instant): TimeState = when {
        dueAt == null -> TimeState.DUE                       // an item in Needs You with no target is due now
        now.isBefore(dueAt) -> TimeState.WAITING
        now == dueAt || secondsUntil(dueAt, now) == 0L -> TimeState.DUE
        else -> TimeState.OVERDUE
    }

    fun state(stream: WorkStream, now: Instant): TimeState = state(dueAt(stream), now)

    /**
     * The approved card presentation:
     * - WAITING → `HH:MM:SS` remaining (`00:05:00`, `01:20:00`)
     * - DUE     → `00:00:00`
     * - OVERDUE → `+HH:MM:SS` elapsed past due (`+00:03:42`, `+27:15:42`)
     *
     * Hours accumulate — 27 hours reads `27:15:42`, never wrapped into `03:15:42` — and three-digit
     * hours (`125:08:17`) are supported. The leading `+` makes the direction unambiguous; a negative
     * countdown is never shown.
     */
    fun format(dueAt: Instant?, now: Instant): String {
        if (dueAt == null) return clock(0)
        val s = secondsUntil(dueAt, now)
        return if (s >= 0) clock(s) else "+" + clock(-s)
    }

    /** Spoken form for accessibility: "Due in 5 minutes" / "Due now" / "Overdue by 3 minutes 42 seconds". */
    fun describe(dueAt: Instant?, now: Instant): String = when (state(dueAt, now)) {
        TimeState.WAITING -> "Due in " + words(secondsUntil(dueAt!!, now))
        TimeState.DUE -> "Due now"
        TimeState.OVERDUE -> "Overdue by " + words(-secondsUntil(dueAt!!, now))
    }

    /** `HH:MM:SS` with accumulating, never-wrapping hours. */
    fun clock(totalSeconds: Long): String {
        val t = totalSeconds.coerceAtLeast(0)
        return "%02d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    }

    private fun words(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        val parts = buildList {
            if (h > 0) add(plural(h, "hour"))
            if (m > 0) add(plural(m, "minute"))
            if (sec > 0 || isEmpty()) add(plural(sec, "second"))
        }
        return parts.joinToString(" ")
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"

    /** The approved CHECK AGAIN presets, in minutes. Custom covers everything else. */
    val checkAgainPresets: List<Long> = listOf(3, 5, 10)

    /** `dueAt` for "check again in N minutes" — a timestamp, never a counter. */
    fun checkAgainAt(now: Instant, minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))
}

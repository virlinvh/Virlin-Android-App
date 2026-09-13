package com.virlin.app.domain.command.time

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * A typed point in time as the user expressed it (Pass 13). Two concepts stay distinct:
 * - [Relative]: "for 10 minutes" — a duration, evaluated against the clock AT EXECUTION.
 * - [Absolute]: "at 5 PM", "tomorrow morning" — a calendar target resolved in the user's zone
 *   at interpretation time, re-validated (must still be future) at execution.
 */
sealed interface TemporalIntent {
    data class Relative(val duration: Duration) : TemporalIntent
    /** [source] is the phrase as understood ("tomorrow morning"), kept for previews. */
    data class Absolute(val at: Instant, val source: String) : TemporalIntent
}

/**
 * One central place for the product's time-language defaults. Dayparts are explicit product
 * decisions, never inferred; relative durations are bounded; nothing else is scattered.
 */
data class TimeLanguagePolicy(
    val morning: LocalTime = LocalTime.of(9, 0),
    val afternoon: LocalTime = LocalTime.of(15, 0),
    val evening: LocalTime = LocalTime.of(19, 0),
    val night: LocalTime = LocalTime.of(20, 0),
    /** Longest relative wait ("in 2 hours" fine, "in 30 hours" not). */
    val maxRelative: Duration = Duration.ofHours(24),
    /** Furthest absolute target (one week covers "next Monday"). */
    val maxAhead: Duration = Duration.ofDays(8)
) {
    fun daypart(name: String): LocalTime? = when (name) {
        "morning" -> morning; "afternoon" -> afternoon; "evening" -> evening; "night", "tonight" -> night; else -> null
    }
    companion object { val Default = TimeLanguagePolicy() }
}

/** Human-readable local rendering for previews and feedback. Never shows raw instants. */
class TimeFormatter(private val zone: ZoneId) {
    private val clock12 = DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH)
    private val dayMonth = DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)

    fun clock(at: Instant): String = clock12.format(at.atZone(zone))

    /** "Today · 3:00 PM", "Tomorrow · 9:00 AM", "Monday · 5:00 PM", "Tue 22 Sep · 9:00 AM". */
    fun format(at: Instant, now: Instant): String {
        val target = at.atZone(zone); val today = now.atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, target.toLocalDate())
        val day = when {
            days == 0L -> "Today"
            days == 1L -> "Tomorrow"
            days in 2..6 -> target.dayOfWeek.displayName()
            else -> dayMonth.format(target)
        }
        return "$day · ${clock12.format(target)}"
    }

    fun format(intent: TemporalIntent, now: Instant): String = when (intent) {
        is TemporalIntent.Relative -> relative(intent.duration)
        is TemporalIntent.Absolute -> format(intent.at, now)
    }

    fun relative(d: Duration): String {
        val m = d.toMinutes()
        return when { m < 60 -> "${m}m"; m % 60 == 0L -> "${m / 60}h"; else -> "${m / 60}h ${m % 60}m" }
    }

    fun zoned(at: Instant): ZonedDateTime = at.atZone(zone)
}

internal fun DayOfWeek.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

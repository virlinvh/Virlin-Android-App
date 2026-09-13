package com.virlin.app.domain.command.time

import com.virlin.app.domain.time.VirlinClock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Why a time phrase could not be turned into one instant, and what to offer instead. */
data class TemporalClarification(
    val kind: Kind,
    val question: String,
    /** Concrete phrases the user can pick; each re-parses to exactly one instant. */
    val suggestions: List<Suggestion> = emptyList()
) {
    enum class Kind { TIME_REQUIRED, AM_PM_REQUIRED, TIME_ALREADY_PASSED, DAYPART_ALREADY_PASSED, INVALID_LOCAL_TIME, TOO_FAR }
    /** [phrase] is a full time expression ("tomorrow at 3:00 PM"); [label] is what to show. */
    data class Suggestion(val phrase: String, val label: String)
}

sealed interface TimeParse {
    data class Resolved(val intent: TemporalIntent) : TimeParse
    data class Clarify(val clarification: TemporalClarification) : TimeParse
    data class Unsupported(val reason: String) : TimeParse
}

/**
 * Deterministic calendar/time language (Pass 13). Resolves in the injected [zone] with the
 * injected [clock] — never the system clock directly, never a hardcoded offset; all calendar arithmetic is
 * java.time. Relative phrases stay [TemporalIntent.Relative] (evaluated at execution);
 * calendar phrases become [TemporalIntent.Absolute] and must be in the future NOW — a passed
 * time is a clarification with a concrete "tomorrow" suggestion, never a silent roll-forward.
 * Underspecified phrases ("tomorrow", "at 9", "Monday") ask; unsupported ones ("after lunch",
 * "next weekend", "every day") are refused. No recurrence, no dates beyond one week.
 */
class TimeExpressionParser(
    private val clock: VirlinClock,
    private val zone: ZoneId,
    private val policy: TimeLanguagePolicy = TimeLanguagePolicy.Default
) {
    private val formatter = TimeFormatter(zone)

    fun parse(text: String): TimeParse {
        val t = text.trim().lowercase().replace(Regex("\\s+"), " ").removePrefix("on ").trim()
        if (t.isEmpty()) return TimeParse.Unsupported("Which time?")
        relative(t)?.let { return it }
        unsupportedWords.firstOrNull { Regex("(^|\\s)" + Regex.escape(it.trim()) + "(\\s|$)").containsMatchIn(t) }?.let { return TimeParse.Unsupported("I don't understand \"$it\" yet — try a clock time, tomorrow morning, or a weekday") }
        val now = clock.now().atZone(zone)
        return calendar(t, now) ?: TimeParse.Unsupported("I only understand times like 3 PM, 15:30, tomorrow morning, Monday at 9 AM, or in 30 minutes")
    }

    // ------------------------------------------------------------------ relative ("in …", "for …")

    private fun relative(t: String): TimeParse? {
        val body = t.removePrefix("in ").removePrefix("for ").trim()
        val d = when (body) {
            "half an hour", "a half hour", "30 minutes" -> Duration.ofMinutes(30)
            "an hour", "one hour", "1 hour" -> Duration.ofHours(1)
            "an hour and a half", "one and a half hours" -> Duration.ofMinutes(90)
            else -> Regex("""^(?:an|one) hour and (\d{1,3}) ?(?:min|mins|minutes)$""").matchEntire(body)?.let { Duration.ofMinutes(60 + it.groupValues[1].toLong()) }
                ?: Regex("""^(\d{1,3}) hours? and (\d{1,3}) ?(?:min|mins|minutes)$""").matchEntire(body)?.let { Duration.ofHours(it.groupValues[1].toLong()).plusMinutes(it.groupValues[2].toLong()) }
                ?: com.virlin.app.domain.command.text.DurationParser.parse(body)
        } ?: return if (looksRelative(body)) TimeParse.Unsupported("That duration must be between 1 minute and ${policy.maxRelative.toHours()} hours") else null
        if (d.isZero || d.isNegative || d > policy.maxRelative) return TimeParse.Unsupported("That duration must be between 1 minute and ${policy.maxRelative.toHours()} hours")
        return TimeParse.Resolved(TemporalIntent.Relative(d))
    }
    private fun looksRelative(b: String) = Regex("""^-?\d+ ?(m|min|mins|minute|minutes|h|hr|hrs|hour|hours)$""").matches(b) || b.startsWith("-")

    // ------------------------------------------------------------------ calendar

    private val dayparts = setOf("morning", "afternoon", "evening", "night")
    private val unsupportedWords = listOf("after lunch", "sometime", "in a bit", "in a while", "when ", "after my", "weekend", "end of the day", "end of day", "asap", "every ", "daily", "weekdays", "next week", "next month", "noon", "midnight", "lunch")

    private fun calendar(t: String, now: ZonedDateTime): TimeParse? {
        val today = now.toLocalDate()
        // tonight / this <daypart> / later today
        if (t == "tonight" || t == "this night") return daypartOn(today, "night", "tonight", now, sameDay = true)
        Regex("""^this (morning|afternoon|evening)$""").matchEntire(t)?.let { return daypartOn(today, it.groupValues[1], t, now, sameDay = true) }
        Regex("""^(?:later )?today(?: at)? (.+)$""").matchEntire(t)?.let { m -> return clockOn(today, m.groupValues[1], "today", now, sameDay = true) }
        if (t == "today" || t == "later today") return ask(TemporalClarification.Kind.TIME_REQUIRED, "What time today?", listOf("this afternoon" to "This afternoon · ${fmt(policy.afternoon)}", "this evening" to "This evening · ${fmt(policy.evening)}", "tonight" to "Tonight · ${fmt(policy.night)}"))
        // tomorrow
        val tomorrow = today.plusDays(1)
        if (t == "tomorrow") return ask(TemporalClarification.Kind.TIME_REQUIRED, "What time tomorrow?", daypartSuggestions("tomorrow"))
        Regex("""^tomorrow (morning|afternoon|evening|night)$""").matchEntire(t)?.let { return daypartOn(tomorrow, it.groupValues[1], t, now, sameDay = false) }
        Regex("""^tomorrow(?: at)? (.+)$""").matchEntire(t)?.let { m -> return clockOn(tomorrow, m.groupValues[1], "tomorrow", now, sameDay = false) }
        // weekdays: "monday", "next monday", "monday at 5 pm", "next monday morning"
        Regex("""^(next )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?: (?:at )?(.+))?$""").matchEntire(t)?.let { m ->
            val next = m.groupValues[1].isNotEmpty(); val dow = DayOfWeek.valueOf(m.groupValues[2].uppercase()); val rest = m.groupValues[3]
            val date = weekdayDate(today, dow, next)
            val label = (if (next) "next " else "") + m.groupValues[2]
            if (rest.isEmpty()) return ask(TemporalClarification.Kind.TIME_REQUIRED, "What time on ${dow.displayName()}?", daypartSuggestions(label))
            return if (rest in dayparts) daypartOn(date, rest, t, now, sameDay = false) else clockOn(date, rest, label, now, sameDay = false)
        }
        // bare clock time: "at 3 pm", "3:30 pm", "15:30"
        Regex("""^(?:at )?(.+)$""").matchEntire(t)?.let { m -> if (clockPattern.matches(m.groupValues[1])) return clockOn(today, m.groupValues[1], "today", now, sameDay = true) }
        return null
    }

    private val clockPattern = Regex("""^(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?$""")

    /** Clock text on a given date. 12-hour without AM/PM (hour ≤ 12) is ambiguous → ask; ≥ 13 is 24-hour. */
    private fun clockOn(date: LocalDate, clockText: String, dayLabel: String, now: ZonedDateTime, sameDay: Boolean): TimeParse {
        val m = clockPattern.matchEntire(clockText.trim()) ?: return TimeParse.Unsupported("I only understand clock times like 3 PM, 3:30 PM or 15:30")
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt(); val ap = m.groupValues[3].replace(".", "")
        if (min !in 0..59 || h > 23) return TimeParse.Unsupported("That is not a valid clock time")
        val hour = when {
            ap == "am" -> if (h == 12) 0 else h
            ap == "pm" -> if (h == 12) 12 else h + 12
            h >= 13 || h == 0 -> h                                        // unambiguous 24-hour
            else -> return ask(TemporalClarification.Kind.AM_PM_REQUIRED, "$h${if (min > 0) ":%02d".format(min) else ""} AM or PM?",
                listOf("$dayLabel at $h:${"%02d".format(min)} am" to "${fmt(LocalTime.of(if (h == 12) 0 else h, min))}", "$dayLabel at $h:${"%02d".format(min)} pm" to "${fmt(LocalTime.of(if (h == 12) 12 else h + 12, min))}"))
        }
        if (hour > 23 || (ap.isEmpty() && h > 12 && h > 23)) return TimeParse.Unsupported("That is not a valid clock time")
        return at(date, LocalTime.of(hour, min), dayLabel, "$dayLabel at ${fmt(LocalTime.of(hour, min))}", now, sameDay)
    }

    private fun daypartOn(date: LocalDate, daypart: String, source: String, now: ZonedDateTime, sameDay: Boolean): TimeParse {
        val time = policy.daypart(daypart)!!
        val local = LocalDateTime.of(date, time)
        if (sameDay && !toZoned(local).isAfter(now)) {
            val name = if (daypart == "night") "Tonight's" else "This $daypart's"
            return ask(TemporalClarification.Kind.DAYPART_ALREADY_PASSED, "$name default time, ${fmt(time)}, has passed. Use tomorrow ${daypart}?",
                listOf("tomorrow $daypart" to "Tomorrow $daypart · ${fmt(time)}"))
        }
        return at(date, time, source, source, now, sameDay)
    }

    /** Final gate for every absolute target: valid local time, in the future, within [TimeLanguagePolicy.maxAhead]. */
    private fun at(date: LocalDate, time: LocalTime, dayLabel: String, source: String, now: ZonedDateTime, sameDay: Boolean): TimeParse {
        val local = LocalDateTime.of(date, time)
        if (zone.rules.getTransition(local)?.isGap == true) return ask(TemporalClarification.Kind.INVALID_LOCAL_TIME,
            "${fmt(time)} does not exist on that day (clock change). Pick another time.", emptyList())
        val zoned = toZoned(local)
        if (!zoned.isAfter(now)) {
            return if (sameDay) ask(TemporalClarification.Kind.TIME_ALREADY_PASSED, "${fmt(time)} has already passed today. Did you mean tomorrow at ${fmt(time)}?",
                listOf("tomorrow at ${fmt(time)}" to "Tomorrow · ${fmt(time)}"))
            else ask(TemporalClarification.Kind.TIME_ALREADY_PASSED, "${formatter.format(zoned.toInstant(), now.toInstant())} has already passed.", emptyList())
        }
        if (Duration.between(now, zoned) > policy.maxAhead) return ask(TemporalClarification.Kind.TOO_FAR, "That is more than ${policy.maxAhead.toDays()} days away — attention reminders stay within a week.", emptyList())
        return TimeParse.Resolved(TemporalIntent.Absolute(zoned.toInstant(), source))
    }

    /**
     * Weekday rule (documented, deterministic): "<weekday>" = the first such day strictly after
     * today (1–7 days ahead; today's weekday means next week's). "next <weekday>" = that weekday
     * in the NEXT calendar week (Monday–Sunday weeks), which may coincide with the bare form.
     */
    internal fun weekdayDate(today: LocalDate, dow: DayOfWeek, next: Boolean): LocalDate =
        if (!next) today.with(TemporalAdjusters.next(dow))
        else today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).let { nextMonday -> if (dow == DayOfWeek.MONDAY) nextMonday else nextMonday.with(TemporalAdjusters.nextOrSame(dow)) }

    private fun toZoned(local: LocalDateTime): ZonedDateTime = ZonedDateTime.ofLocal(local, zone, null)   // overlaps: earlier offset, per java.time
    private fun fmt(t: LocalTime) = java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(t)
    private fun daypartSuggestions(day: String) = listOf("$day morning" to "Morning · ${fmt(policy.morning)}", "$day afternoon" to "Afternoon · ${fmt(policy.afternoon)}", "$day evening" to "Evening · ${fmt(policy.evening)}")
    private fun ask(kind: TemporalClarification.Kind, q: String, s: List<Pair<String, String>>) =
        TimeParse.Clarify(TemporalClarification(kind, q, s.map { TemporalClarification.Suggestion(it.first, it.second) }))
}

internal fun DayOfWeek.displayName(locale: Locale): String = getDisplayName(TextStyle.FULL, locale)

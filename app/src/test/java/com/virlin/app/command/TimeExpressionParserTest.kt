package com.virlin.app.command

import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.command.time.TemporalClarification.Kind
import com.virlin.app.domain.command.time.TemporalIntent
import com.virlin.app.domain.command.time.TimeExpressionParser
import com.virlin.app.domain.command.time.TimeFormatter
import com.virlin.app.domain.command.time.TimeLanguagePolicy
import com.virlin.app.domain.command.time.TimeParse
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pass 13 §26–33 — calendar/time language on a FIXED clock and FIXED zone. Nothing here calls
 * Instant.now(); every expectation is an exact instant computed with java.time in the zone.
 */
class TimeExpressionParserTest {

    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")
    private val newYork: ZoneId = ZoneId.of("America/New_York")
    /** Saturday 12 Sep 2026, 10:00 local. */
    private fun at(zone: ZoneId, y: Int = 2026, mo: Int = 9, d: Int = 12, h: Int = 10, m: Int = 0): Instant = ZonedDateTime.of(y, mo, d, h, m, 0, 0, zone).toInstant()
    private fun parser(now: Instant, zone: ZoneId = kolkata) = TimeExpressionParser(FakeClock(now), zone)
    private fun local(zone: ZoneId, date: LocalDate, time: LocalTime): Instant = ZonedDateTime.of(date, time, zone).toInstant()

    private fun absolute(p: TimeExpressionParser, text: String): Instant = ((p.parse(text) as? TimeParse.Resolved)?.intent as? TemporalIntent.Absolute)?.at ?: error("not absolute: '$text' → ${p.parse(text)}")
    private fun relative(p: TimeExpressionParser, text: String): Duration = ((p.parse(text) as? TimeParse.Resolved)?.intent as? TemporalIntent.Relative)?.duration ?: error("not relative: '$text' → ${p.parse(text)}")
    private fun clarify(p: TimeExpressionParser, text: String) = (p.parse(text) as? TimeParse.Clarify)?.clarification ?: error("expected clarification: '$text' → ${p.parse(text)}")

    private val today = LocalDate.of(2026, 9, 12)
    private val tomorrow = today.plusDays(1)

    // ================================================================ §26 clock time

    @Test fun clock_times_today_future() {
        val p = parser(at(kolkata))
        assertEquals(local(kolkata, today, LocalTime.of(15, 0)), absolute(p, "at 3 pm"))
        assertEquals(local(kolkata, today, LocalTime.of(15, 30)), absolute(p, "at 3:30 PM"))
        assertEquals(local(kolkata, today, LocalTime.of(15, 30)), absolute(p, "15:30"))
        assertEquals(local(kolkata, today, LocalTime.of(15, 0)), absolute(p, "today at 3 pm"))
        assertEquals(local(kolkata, today, LocalTime.of(18, 0)), absolute(p, "later today at 6 pm"))
    }

    @Test fun past_clock_time_clarifies_with_tomorrow_suggestion_never_rolls() {
        val p = parser(at(kolkata, h = 16))
        val c = clarify(p, "at 3 pm")
        assertEquals(Kind.TIME_ALREADY_PASSED, c.kind); assertTrue(c.question.startsWith("3:00 PM has already passed today"))
        assertEquals(listOf("tomorrow at 3:00 PM"), c.suggestions.map { it.phrase })
        assertEquals(Kind.TIME_ALREADY_PASSED, clarify(p, "today at 8 am").kind)
    }

    @Test fun ambiguous_hour_asks_am_pm_never_guesses() {
        val p = parser(at(kolkata))
        val c = clarify(p, "at 9")
        assertEquals(Kind.AM_PM_REQUIRED, c.kind); assertEquals("9 AM or PM?", c.question)
        assertEquals(listOf("today at 9:00 am", "today at 9:00 pm"), c.suggestions.map { it.phrase })
        assertEquals(Kind.AM_PM_REQUIRED, clarify(p, "at 9:30").kind)
        assertEquals(local(kolkata, today, LocalTime.of(21, 0)), absolute(p, "at 9 pm"))
        assertEquals(Kind.TIME_ALREADY_PASSED, clarify(p, "at 9 am").kind)               // 09:00 < 10:00
        assertEquals(local(kolkata, tomorrow, LocalTime.of(9, 0)), absolute(p, "tomorrow at 9 am"))
        assertEquals(local(kolkata, today, LocalTime.of(0, 30)), absolute(parser(at(kolkata, h = 0, m = 5)), "0:30"))
        assertEquals(local(kolkata, today, LocalTime.of(12, 0)), absolute(p, "12 pm"))
    }

    // ================================================================ §27 tomorrow

    @Test fun tomorrow_forms() {
        val p = parser(at(kolkata))
        val c = clarify(p, "tomorrow"); assertEquals(Kind.TIME_REQUIRED, c.kind); assertEquals("What time tomorrow?", c.question)
        assertEquals(listOf("tomorrow morning", "tomorrow afternoon", "tomorrow evening"), c.suggestions.map { it.phrase })
        assertEquals(local(kolkata, tomorrow, LocalTime.of(9, 0)), absolute(p, "tomorrow at 9 am"))
        assertEquals(local(kolkata, tomorrow, LocalTime.of(9, 0)), absolute(p, "tomorrow morning"))
        assertEquals(local(kolkata, tomorrow, LocalTime.of(15, 0)), absolute(p, "tomorrow afternoon"))
        assertEquals(local(kolkata, tomorrow, LocalTime.of(19, 0)), absolute(p, "tomorrow evening"))
        assertEquals(local(kolkata, tomorrow, LocalTime.of(20, 0)), absolute(p, "tomorrow night"))
        assertEquals(Kind.AM_PM_REQUIRED, clarify(p, "tomorrow at 9").kind)
    }

    // ================================================================ §28 dayparts

    @Test fun dayparts_today_and_tonight() {
        val morning = parser(at(kolkata, h = 10))
        assertEquals(local(kolkata, today, LocalTime.of(15, 0)), absolute(morning, "this afternoon"))
        assertEquals(local(kolkata, today, LocalTime.of(19, 0)), absolute(morning, "this evening"))
        assertEquals(local(kolkata, today, LocalTime.of(20, 0)), absolute(morning, "tonight"))
        val late = parser(at(kolkata, h = 16))
        val c = clarify(late, "this afternoon"); assertEquals(Kind.DAYPART_ALREADY_PASSED, c.kind); assertEquals(listOf("tomorrow afternoon"), c.suggestions.map { it.phrase })
        val night = parser(at(kolkata, h = 21))
        val t = clarify(night, "tonight"); assertEquals(Kind.DAYPART_ALREADY_PASSED, t.kind)
        assertEquals("Tonight's default time, 8:00 PM, has passed. Use tomorrow night?", t.question)
        assertEquals(Kind.TIME_REQUIRED, clarify(morning, "today").kind)
    }

    @Test fun policy_defaults_are_the_documented_ones_and_injectable() {
        val d = TimeLanguagePolicy.Default
        assertEquals(LocalTime.of(9, 0), d.morning); assertEquals(LocalTime.of(15, 0), d.afternoon); assertEquals(LocalTime.of(19, 0), d.evening); assertEquals(LocalTime.of(20, 0), d.night)
        val custom = TimeExpressionParser(FakeClock(at(kolkata)), kolkata, TimeLanguagePolicy(morning = LocalTime.of(8, 30)))
        assertEquals(local(kolkata, tomorrow, LocalTime.of(8, 30)), absolute(custom, "tomorrow morning"))
    }

    // ================================================================ §29 weekdays

    @Test fun weekday_without_time_asks() {
        val c = clarify(parser(at(kolkata)), "monday")
        assertEquals(Kind.TIME_REQUIRED, c.kind); assertEquals("What time on Monday?", c.question)
        assertEquals(listOf("monday morning", "monday afternoon", "monday evening"), c.suggestions.map { it.phrase })
    }

    @Test fun weekday_and_next_weekday_rule() {
        // Saturday 12 Sep 2026. Bare weekday = first occurrence strictly after today; "next" = that day in the NEXT Mon–Sun week.
        val p = parser(at(kolkata))
        assertEquals(local(kolkata, LocalDate.of(2026, 9, 14), LocalTime.of(17, 0)), absolute(p, "monday at 5 pm"))        // Mon 14 Sep
        assertEquals(local(kolkata, LocalDate.of(2026, 9, 14), LocalTime.of(17, 0)), absolute(p, "next monday at 5 pm"))   // next week's Monday = same day
        assertEquals(local(kolkata, LocalDate.of(2026, 9, 19), LocalTime.of(9, 0)), absolute(p, "saturday at 9 am"))       // today is Saturday → a week ahead
        assertEquals(local(kolkata, LocalDate.of(2026, 9, 13), LocalTime.of(9, 0)), absolute(p, "on sunday at 9 am"))       // tomorrow
        assertEquals(local(kolkata, LocalDate.of(2026, 9, 18), LocalTime.of(9, 0)), absolute(p, "next friday morning"))     // Fri of next week (18th)
        // From Wednesday 9 Sep: "friday" = 11 Sep, "next friday" = 18 Sep (different days — the documented distinction).
        val wed = parser(at(kolkata, d = 9))
        assertEquals(LocalDate.of(2026, 9, 11), wed.weekdayDate(LocalDate.of(2026, 9, 9), DayOfWeek.FRIDAY, next = false))
        assertEquals(LocalDate.of(2026, 9, 18), wed.weekdayDate(LocalDate.of(2026, 9, 9), DayOfWeek.FRIDAY, next = true))
        assertEquals(Kind.TOO_FAR, clarify(wed, "next friday at 9 am").kind)                                              // 9 days ahead > policy.maxAhead
    }

    @Test fun weekday_across_month_and_year_boundaries() {
        val endOfMonth = parser(at(kolkata, mo = 9, d = 29))                                   // Tue 29 Sep 2026
        assertEquals(local(kolkata, LocalDate.of(2026, 10, 2), LocalTime.of(9, 0)), absolute(endOfMonth, "friday at 9 am"))
        val endOfYear = parser(at(kolkata, mo = 12, d = 30))                                   // Wed 30 Dec 2026
        assertEquals(local(kolkata, LocalDate.of(2027, 1, 1), LocalTime.of(9, 0)), absolute(endOfYear, "friday at 9 am"))
        assertEquals(local(kolkata, LocalDate.of(2027, 1, 4), LocalTime.of(9, 0)), absolute(endOfYear, "next monday at 9 am"))
    }

    // ================================================================ §30 relative

    @Test fun relative_forms_and_bounds() {
        val p = parser(at(kolkata))
        assertEquals(Duration.ofMinutes(30), relative(p, "in half an hour"))
        assertEquals(Duration.ofHours(1), relative(p, "in an hour"))
        assertEquals(Duration.ofHours(2), relative(p, "in 2 hours"))
        assertEquals(Duration.ofMinutes(90), relative(p, "in 90 minutes"))
        assertEquals(Duration.ofMinutes(90), relative(p, "in an hour and 30 minutes"))
        assertEquals(Duration.ofMinutes(150), relative(p, "in 2 hours and 30 minutes"))
        assertEquals(Duration.ofMinutes(5), relative(p, "for 5 minutes"))
        assertTrue(p.parse("in 0 minutes") is TimeParse.Unsupported)
        assertTrue(p.parse("in -5 minutes") is TimeParse.Unsupported)
        assertTrue(p.parse("in 30 hours") is TimeParse.Unsupported)                            // > maxRelative (24 h) is refused, not clamped
    }

    // ================================================================ §19 unsupported

    @Test fun unsupported_phrases_are_refused_not_guessed() {
        val p = parser(at(kolkata))
        listOf("after lunch", "sometime later", "in a bit", "when claude is done", "after my meeting", "next weekend", "end of the day", "asap", "every day", "every monday", "daily at 5", "next week").forEach {
            assertTrue(it, p.parse(it) is TimeParse.Unsupported)
        }
    }

    // ================================================================ §33 timezones + DST

    @Test fun same_phrase_yields_zone_correct_instants() {
        val kNow = at(kolkata); val nNow = at(newYork)
        val k = absolute(parser(kNow, kolkata), "tomorrow morning"); val n = absolute(parser(nNow, newYork), "tomorrow morning")
        assertEquals(ZonedDateTime.of(tomorrow, LocalTime.of(9, 0), kolkata).toInstant(), k)
        assertEquals(ZonedDateTime.of(tomorrow, LocalTime.of(9, 0), newYork).toInstant(), n)
        assertEquals(Duration.ofHours(9).plusMinutes(30), Duration.between(k, n))            // IST is UTC+5:30, EDT is UTC−4
        assertEquals("Tomorrow · 9:00 AM", TimeFormatter(newYork).format(n, nNow))
        assertEquals("Tomorrow · 9:00 AM", TimeFormatter(kolkata).format(k, kNow))
    }

    @Test fun dst_gap_is_a_clarification_not_a_silent_shift() {
        // New York, Sat 7 Mar 2026 10:00; clocks jump 02:00→03:00 on Sun 8 Mar 2026.
        val p = parser(ZonedDateTime.of(2026, 3, 7, 10, 0, 0, 0, newYork).toInstant(), newYork)
        val c = clarify(p, "tomorrow at 2:30 am")
        assertEquals(Kind.INVALID_LOCAL_TIME, c.kind)
        assertEquals(ZonedDateTime.of(2026, 3, 8, 9, 0, 0, 0, newYork).toInstant(), absolute(p, "tomorrow morning"))
    }

    @Test fun formatter_days() {
        val f = TimeFormatter(kolkata); val now = at(kolkata)
        assertEquals("Today · 3:00 PM", f.format(local(kolkata, today, LocalTime.of(15, 0)), now))
        assertEquals("Tomorrow · 9:00 AM", f.format(local(kolkata, tomorrow, LocalTime.of(9, 0)), now))
        assertEquals("Monday · 5:00 PM", f.format(local(kolkata, LocalDate.of(2026, 9, 14), LocalTime.of(17, 0)), now))
        assertEquals("10m", f.relative(Duration.ofMinutes(10))); assertEquals("1h 30m", f.relative(Duration.ofMinutes(90))); assertEquals("2h", f.relative(Duration.ofHours(2)))
    }

    @Test fun no_direct_now_in_time_package() {
        val src = java.io.File("src/main/java/com/virlin/app/domain/command/time").listFiles()!!.filter { it.extension == "kt" }
        assertTrue(src.isNotEmpty())
        src.forEach { f ->
            val t = f.readText()
            listOf("Instant.now(", "ZonedDateTime.now(", "LocalDate.now(", "LocalTime.now(", "System.currentTimeMillis", "ZoneId.systemDefault", "VirlinActions", "AlarmManager", "androidx.room", "Notification", "Asia/Kolkata")
                .forEach { forbidden -> assertFalse("${f.name} must not use $forbidden", t.contains(forbidden)) }
        }
    }
}

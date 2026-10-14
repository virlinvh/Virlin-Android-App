package com.virlin.app.domain

import com.virlin.app.domain.model.DailyFocus
import com.virlin.app.domain.model.FocusSession
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyFocusTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun session(id: String, start: String, end: String? = null) =
        FocusSession(id, "stream", null, Instant.parse(start), end?.let(Instant::parse), "task")

    @Test fun clipsMidnightDeduplicatesAndIncludesLiveSession() {
        val midnight = session("a", "2026-10-09T18:10:00Z", "2026-10-09T18:50:00Z")
        val closed = session("b", "2026-10-10T03:30:00Z", "2026-10-10T04:05:00Z")
        val live = session("c", "2026-10-10T04:30:00Z")
        assertEquals(67 * 60L, DailyFocus.seconds(listOf(midnight, midnight, closed, live),
            Instant.parse("2026-10-10T04:42:00Z"), zone))
    }
    @Test fun excludesPausedGapAndInvalidIntervals() {
        val sessions = listOf(session("a", "2026-10-10T03:30:00Z", "2026-10-10T03:35:00Z"),
            session("b", "2026-10-10T03:45:00Z", "2026-10-10T03:50:00Z"),
            session("bad", "2026-10-10T04:00:00Z", "2026-10-10T03:00:00Z"))
        assertEquals(600L, DailyFocus.seconds(sessions, Instant.parse("2026-10-10T05:00:00Z"), zone))
        assertEquals("0m", DailyFocus.label(0))
    }
    @Test fun usesActualDayBoundariesAcrossDaylightSaving() {
        val s = session("a", "2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z")
        assertEquals(23 * 3600L, DailyFocus.seconds(listOf(s), Instant.parse("2026-03-09T03:59:59Z"),
            ZoneId.of("America/New_York")) + 1)
    }
}

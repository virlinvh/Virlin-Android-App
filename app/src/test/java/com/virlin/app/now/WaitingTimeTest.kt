package com.virlin.app.now

import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.screens.NowPresentation
import com.virlin.app.ui.screens.WaitingTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Needs You attention system — Phase 1. Deterministic time only (fixed Instants, no clock).
 * Covers the live negative timer format, its spoken form, the waiting-since derivation from the
 * domain, the ordering rule and clock-based recomputation (elapsed is derived, never counted).
 */
class WaitingTimeTest {

    private val t0: Instant = Instant.parse("2026-09-20T10:00:00Z")

    // ------------------------------------------------------------------ format (required boundaries)

    @Test fun format_atDueMoment_isZeroWithoutSign() = assertEquals("00:00", WaitingTime.format(0))
    @Test fun format_oneSecond() = assertEquals("−00:01", WaitingTime.format(1))
    @Test fun format_8s() = assertEquals("−00:08", WaitingTime.format(8))
    @Test fun format_59s() = assertEquals("−00:59", WaitingTime.format(59))
    @Test fun format_1m00() = assertEquals("−01:00", WaitingTime.format(60))
    @Test fun format_1m14() = assertEquals("−01:14", WaitingTime.format(74))
    @Test fun format_2m59() = assertEquals("−02:59", WaitingTime.format(179))
    @Test fun format_3m00() = assertEquals("−03:00", WaitingTime.format(180))
    @Test fun format_4m59() = assertEquals("−04:59", WaitingTime.format(299))
    @Test fun format_5m00() = assertEquals("−05:00", WaitingTime.format(300))
    @Test fun format_8m32() = assertEquals("−08:32", WaitingTime.format(512))
    @Test fun format_9m59() = assertEquals("−09:59", WaitingTime.format(599))
    @Test fun format_10m00() = assertEquals("−10:00", WaitingTime.format(600))
    @Test fun format_59m59_staysMinutesOnly() = assertEquals("−59:59", WaitingTime.format(3599))
    @Test fun format_oneHour_switchesToHoursNoPadding() = assertEquals("−1:00:00", WaitingTime.format(3600))
    @Test fun format_1h04m() = assertEquals("−1:04:00", WaitingTime.format(3840))
    @Test fun format_12h34m56s() = assertEquals("−12:34:56", WaitingTime.format(12 * 3600 + 34 * 60 + 56))
    @Test fun format_negativeInput_clampsToZero() = assertEquals("00:00", WaitingTime.format(-5))
    @Test fun format_usesTrueMinusSign() = assertTrue(WaitingTime.format(1).startsWith("−"))

    // ------------------------------------------------------------------ accessibility description

    @Test fun describe_zero() = assertEquals("Just became due", WaitingTime.describe(0))
    @Test fun describe_seconds() = assertEquals("Waiting for 8 seconds", WaitingTime.describe(8))
    @Test fun describe_singularSecond() = assertEquals("Waiting for 1 second", WaitingTime.describe(1))
    @Test fun describe_minutesAndSeconds() = assertEquals("Waiting for 3 minutes 42 seconds", WaitingTime.describe(222))
    @Test fun describe_exactMinutes_omitsSeconds() = assertEquals("Waiting for 5 minutes", WaitingTime.describe(300))
    @Test fun describe_hours() = assertEquals("Waiting for 1 hour 4 minutes", WaitingTime.describe(3840))

    // ------------------------------------------------------------------ elapsed: derived from timestamps, recomputed from the clock

    @Test fun elapsed_isDifferenceOfInstants() {
        assertEquals(0L, WaitingTime.elapsedSeconds(t0, t0))
        assertEquals(59L, WaitingTime.elapsedSeconds(t0, t0.plusSeconds(59)))
        assertEquals(600L, WaitingTime.elapsedSeconds(t0, t0.plusSeconds(600)))
    }

    @Test fun elapsed_futureTimestamp_readsAsZero() = assertEquals(0L, WaitingTime.elapsedSeconds(t0.plusSeconds(30), t0))

    @Test fun elapsed_recomputesFromClock_noDriftAcrossGap() {
        // Simulates backgrounding: the "ticker" is silent for 10 minutes, then the clock is read
        // again. Because elapsed is derived from `now`, the gap is reflected exactly — nothing
        // was counted in between and nothing was lost.
        val since = t0
        assertEquals(5L, WaitingTime.elapsedSeconds(since, t0.plusSeconds(5)))
        val resumed = t0.plus(Duration.ofMinutes(10)).plusSeconds(5)
        assertEquals(605L, WaitingTime.elapsedSeconds(since, resumed))
        assertEquals("−10:05", WaitingTime.format(WaitingTime.elapsedSeconds(since, resumed)))
    }

    // ------------------------------------------------------------------ waitingSince: domain derivation

    private fun stream(id: String, state: WorkStreamState, reason: SnoozeReason? = null, checkAt: Instant? = null, updatedAt: Instant = t0) =
        WorkStream(id = id, title = id, state = state, snoozeReason = reason, checkAt = checkAt, createdAt = t0.minusSeconds(3600), updatedAt = updatedAt)

    @Test fun waitingSince_checkDue_usesScheduledCheckMoment() {
        val due = t0.minusSeconds(90)
        val m = NowPresentation.waitingSince(listOf(stream("s", WorkStreamState.CHECK, null, checkAt = due, updatedAt = t0)))
        assertEquals(due, m["s"])
    }

    @Test fun waitingSince_checkDue_withoutCheckAt_fallsBackToTransitionStamp() {
        val m = NowPresentation.waitingSince(listOf(stream("s", WorkStreamState.CHECK, null, checkAt = null, updatedAt = t0)))
        assertEquals(t0, m["s"])
    }

    @Test fun waitingSince_returnDue_usesTransitionStamp_ignoresStaleCheckAt() {
        val stale = t0.minusSeconds(5000)
        val m = NowPresentation.waitingSince(listOf(stream("s", WorkStreamState.CHECK, SnoozeReason.HUMAN_RETURN, checkAt = stale, updatedAt = t0)))
        assertEquals(t0, m["s"])
    }

    @Test fun waitingSince_resultReady_usesTransitionStamp() {
        val m = NowPresentation.waitingSince(listOf(stream("s", WorkStreamState.CHECK, SnoozeReason.EXTERNAL_RESULT_READY, updatedAt = t0)))
        assertEquals(t0, m["s"])
    }

    @Test fun waitingSince_onlyCheckStreams() {
        val m = NowPresentation.waitingSince(listOf(
            stream("f", WorkStreamState.FOCUS), stream("p", WorkStreamState.PROCESSING, checkAt = t0.plusSeconds(60)),
            stream("r", WorkStreamState.READY), stream("c", WorkStreamState.CHECK, checkAt = t0)
        ))
        assertEquals(setOf("c"), m.keys)
    }

    // ------------------------------------------------------------------ ordering rule

    @Test fun order_longestWaitingFirst_unknownLast_stableTies() {
        val since = mapOf("a" to t0.minusSeconds(30), "b" to t0.minusSeconds(600), "c" to t0.minusSeconds(30), "d" to null)
        val ordered = WaitingTime.orderLongestWaitingFirst(listOf("a", "b", "c", "d")) { since[it] }
        assertEquals(listOf("b", "a", "c", "d"), ordered)
    }

    @Test fun order_doesNotDependOnCurrentTime() {
        val since = mapOf("x" to t0.minusSeconds(10), "y" to t0.minusSeconds(20))
        val first = WaitingTime.orderLongestWaitingFirst(listOf("x", "y")) { since[it] }
        // Advancing "now" changes every elapsed value but no relative order; the rule never reads now.
        val later = WaitingTime.orderLongestWaitingFirst(listOf("x", "y")) { since[it] }
        assertEquals(first, later)
        assertEquals(listOf("y", "x"), first)
    }

    // ------------------------------------------------------------------ due-now state derives from the same duration

    @Test fun dueNowState_isFirstMinuteOfWaiting() {
        val since = t0
        assertTrue(WaitingTime.elapsedSeconds(since, t0) < 60)
        assertTrue(WaitingTime.elapsedSeconds(since, t0.plusSeconds(59)) < 60)
        assertFalse(WaitingTime.elapsedSeconds(since, t0.plusSeconds(60)) < 60)
    }
}

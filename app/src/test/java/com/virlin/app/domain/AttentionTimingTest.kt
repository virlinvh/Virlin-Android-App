package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.attention.AttentionTiming
import com.virlin.app.domain.attention.InMemoryPriorityPreferences
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.attention.PriorityScope
import com.virlin.app.domain.attention.TimeState
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState.CHECK
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Phase 05 — attention TIME: `dueAt` is the truth, WAITING / DUE / OVERDUE are derived, and
 * CHECK AGAIN writes a new `dueAt`. Deterministic: a FakeClock is advanced, never a real wait.
 */
class AttentionTimingTest {

    private val t0: Instant = Instant.parse("2026-09-22T10:00:00Z")
    private val clock = FakeClock(t0)

    private fun due(id: String, at: Instant) = WorkStream(
        id = id, title = id, state = CHECK, checkAt = at, createdAt = t0.minusSeconds(7200), updatedAt = t0.minusSeconds(7200)
    )
    private fun harness(streams: List<WorkStream>): Triple<InMemoryWorkStreamRepository, DefaultVirlinActions, InMemoryPriorityPreferences> {
        val repo = InMemoryWorkStreamRepository(seed = streams)
        val prefs = InMemoryPriorityPreferences()
        return Triple(repo, DefaultVirlinActions(repo, clock, SequentialIdProvider(), prefs), prefs)
    }
    private fun ids(repo: InMemoryWorkStreamRepository) = NeedsYouOrder.queue(repo.streams.value).map { it.stream.id }

    // ------------------------------------------------------------------ 1–3, 14: the three states

    @Test fun test1_futureDueAt_isWaiting() {
        val dueAt = t0.plus(Duration.ofMinutes(5))
        assertEquals(TimeState.WAITING, AttentionTiming.state(dueAt, t0))
        assertEquals("00:05:00", AttentionTiming.format(dueAt, t0))
        assertEquals("Due in 5 minutes", AttentionTiming.describe(dueAt, t0))
    }

    @Test fun test2_exactDueAt_isDue() {
        val dueAt = t0.plus(Duration.ofMinutes(5))
        val at = t0.plus(Duration.ofMinutes(5))
        assertEquals(TimeState.DUE, AttentionTiming.state(dueAt, at))
        assertEquals("00:00:00", AttentionTiming.format(dueAt, at))
        assertEquals("Due now", AttentionTiming.describe(dueAt, at))
    }

    @Test fun test3_pastDueAt_isOverdue() {
        val dueAt = t0
        val at = t0.plusSeconds(222)
        assertEquals(TimeState.OVERDUE, AttentionTiming.state(dueAt, at))
        assertEquals("+00:03:42", AttentionTiming.format(dueAt, at))
        assertEquals("Overdue by 3 minutes 42 seconds", AttentionTiming.describe(dueAt, at))
    }

    @Test fun test14_crossingZero_waitingThenDueThenOverdue() {
        val dueAt = t0.plusSeconds(2)
        assertEquals("00:00:02", AttentionTiming.format(dueAt, t0))
        assertEquals(TimeState.WAITING, AttentionTiming.state(dueAt, t0.plusSeconds(1)))
        assertEquals("00:00:01", AttentionTiming.format(dueAt, t0.plusSeconds(1)))
        assertEquals(TimeState.DUE, AttentionTiming.state(dueAt, t0.plusSeconds(2)))
        assertEquals("00:00:00", AttentionTiming.format(dueAt, t0.plusSeconds(2)))
        assertEquals(TimeState.OVERDUE, AttentionTiming.state(dueAt, t0.plusSeconds(3)))
        assertEquals("+00:00:01", AttentionTiming.format(dueAt, t0.plusSeconds(3)))
    }

    // ------------------------------------------------------------------ 4–6, 19: formatting

    @Test fun test4_hhmmssFormatting() {
        assertEquals("00:03:00", AttentionTiming.format(t0.plusSeconds(180), t0))
        assertEquals("00:02:59", AttentionTiming.format(t0.plusSeconds(179), t0))
        assertEquals("00:00:01", AttentionTiming.format(t0.plusSeconds(1), t0))
        assertEquals("01:20:00", AttentionTiming.format(t0.plusSeconds(4800), t0))
        assertEquals("12:45:19", AttentionTiming.format(t0.plusSeconds(45919), t0))
    }

    @Test fun test5_over24Hours_accumulatesAndNeverWraps() {
        assertEquals("27:15:42", AttentionTiming.format(t0.plusSeconds(98142), t0))
        assertEquals("+27:15:42", AttentionTiming.format(t0.minusSeconds(98142), t0))
    }

    @Test fun test6_and_19_over99Hours_andExtremeValues() {
        assertEquals("125:08:17", AttentionTiming.format(t0.plusSeconds(450497), t0))
        assertEquals("+125:08:17", AttentionTiming.format(t0.minusSeconds(450497), t0))
        assertEquals("8760:00:00", AttentionTiming.format(t0.plus(Duration.ofDays(365)), t0))   // a year, no overflow
        assertEquals("00:00:00", AttentionTiming.format(null, t0))                              // no target: due now
        assertEquals(TimeState.DUE, AttentionTiming.state(null, t0))
    }


    // ------------------------------------------------------------------ adaptive (compact) format

    /**
     * The Needs You card drops the hour segment while there is no hour to show, and gains it —
     * unpadded — the moment there is. Hours still accumulate past 24.
     */
    @Test fun adaptive_dropsTheHourSegmentUnderAnHour_andGainsItUnpaddedAfter() {
        fun waited(seconds: Long) = AttentionTiming.format(t0.minusSeconds(seconds), t0, adaptive = true)
        assertEquals("00:00", AttentionTiming.format(t0, t0, adaptive = true))
        assertEquals("+00:01", waited(1))
        assertEquals("+00:59", waited(59))
        assertEquals("+01:00", waited(60))
        assertEquals("+01:01", waited(61))
        assertEquals("+09:59", waited(9 * 60 + 59))
        assertEquals("+10:00", waited(10 * 60))
        assertEquals("+26:55", waited(26 * 60 + 55))
        assertEquals("+59:58", waited(59 * 60 + 58))
        assertEquals("+59:59", waited(59 * 60 + 59))
        assertEquals("+1:00:00", waited(3600))                  // the hour appears, nothing resets
        assertEquals("+1:00:01", waited(3601))
        assertEquals("+1:01:01", waited(3661))
        assertEquals("+1:02:05", waited(3725))
        assertEquals("+9:59:59", waited(9 * 3600 + 59 * 60 + 59))
        assertEquals("+10:00:00", waited(10 * 3600))
        assertEquals("+11:23:29", waited(11 * 3600 + 23 * 60 + 29))
        assertEquals("+25:04:08", waited(25 * 3600 + 4 * 60 + 8))   // past 24h, never wrapped
    }

    /** The live transition is one second wide: nothing in between, nothing reset. */
    @Test fun adaptive_transitionAcrossTheHourIsContinuous() {
        val waitingSince = t0.minusSeconds(3600)
        fun at(offset: Long) = AttentionTiming.format(waitingSince, t0.plusSeconds(offset - 3600), adaptive = true)
        assertEquals("+59:58", at(3598))
        assertEquals("+59:59", at(3599))
        assertEquals("+1:00:00", at(3600))
        assertEquals("+1:00:01", at(3601))
    }

    /** Accessibility never follows the compact visual string; it stays spoken. */
    @Test fun adaptive_doesNotChangeTheSpokenDescription() {
        assertEquals("Overdue by 26 minutes 55 seconds", AttentionTiming.describe(t0.minusSeconds(1615), t0))
        assertEquals("Overdue by 1 hour 2 minutes 5 seconds", AttentionTiming.describe(t0.minusSeconds(3725), t0))
    }

    // ------------------------------------------------------------------ 7–10, 15: CHECK AGAIN

    @Test fun test7_8_9_presetsProduceANewDueAt() = runBlocking {
        assertEquals(listOf(3L, 5L, 10L), AttentionTiming.checkAgainPresets)
        listOf(3L, 5L, 10L).forEach { minutes ->
            val (repo, actions, _) = harness(listOf(due("A", t0.minusSeconds(600))))
            clock.current = t0
            assertTrue(actions.continueProcessing("A", AttentionTiming.checkAgainAt(t0, minutes)) is ActionResult.Success)
            val dueAt = repo.getStream("A")!!.checkAt!!
            assertEquals(t0.plus(Duration.ofMinutes(minutes)), dueAt)
            assertEquals(AttentionTiming.clock(minutes * 60), AttentionTiming.format(dueAt, t0))
            assertEquals(TimeState.WAITING, AttentionTiming.state(dueAt, t0))
        }
    }

    @Test fun test10_customDuration() = runBlocking {
        val (repo, actions, _) = harness(listOf(due("A", t0.minusSeconds(60))))
        actions.continueProcessing("A", AttentionTiming.checkAgainAt(t0, 90))
        assertEquals("01:30:00", AttentionTiming.format(repo.getStream("A")!!.checkAt, t0))
    }

    @Test fun test15_checkAgainFromOverdue_returnsToWaiting() = runBlocking {
        val (repo, actions, _) = harness(listOf(due("A", t0.minusSeconds(4200))))
        assertEquals(TimeState.OVERDUE, AttentionTiming.state(repo.getStream("A")!!.checkAt, t0))
        assertEquals("+01:10:00", AttentionTiming.format(repo.getStream("A")!!.checkAt, t0))
        actions.continueProcessing("A", AttentionTiming.checkAgainAt(t0, 5))
        assertEquals(TimeState.WAITING, AttentionTiming.state(repo.getStream("A")!!.checkAt, t0))
        assertEquals("00:05:00", AttentionTiming.format(repo.getStream("A")!!.checkAt, t0))
    }

    // ------------------------------------------------------------------ 11, 12, 17: time ⟂ priority

    @Test fun test11_priorityReorderNeverTouchesDueAt() = runBlocking {
        val (repo, actions, _) = harness(listOf(due("A", t0.minusSeconds(300)), due("B", t0.minusSeconds(200)), due("C", t0.plusSeconds(900))))
        val before = repo.streams.value.associate { it.id to it.checkAt }
        actions.reorderNeedsYou("C", 1); actions.reorderNeedsYou("A", 3)
        repo.streams.value.forEach { assertEquals("dueAt ${it.id}", before[it.id], it.checkAt) }
        assertEquals("00:15:00", AttentionTiming.format(repo.getStream("C")!!.checkAt, t0))
    }

    @Test fun test12_checkAgainKeepsThePriorityPreference() = runBlocking {
        val (repo, actions, prefs) = harness(listOf(due("A", t0.minusSeconds(60)), due("B", t0.minusSeconds(50)), due("C", t0.minusSeconds(40))))
        actions.setNeedsYouPriority("C", 2, PriorityScope.Always)
        assertEquals(2, NeedsYouOrder.effectiveRank(repo.streams.value, "C"))
        actions.continueProcessing("C", AttentionTiming.checkAgainAt(t0, 10))
        assertEquals(2, prefs.get("C")!!.preferredPosition)                       // policy intact
        assertEquals(PriorityScope.Always, prefs.get("C")!!.scope)
        clock.current = t0.plus(Duration.ofMinutes(10))
        actions.checkDue("C")
        assertEquals(2, NeedsYouOrder.effectiveRank(repo.streams.value, "C"))     // and it re-enters at its position
    }

    @Test fun test17_tickingNeverMutatesRank() {
        val streams = listOf(due("A", t0.minusSeconds(300)), due("B", t0.plusSeconds(60)), due("C", t0.minusSeconds(30)))
        val order = NeedsYouOrder.queue(streams).map { it.stream.id }
        listOf(0L, 30L, 61L, 3600L, 86400L).forEach { advance ->
            assertEquals("order at +$advance", order, NeedsYouOrder.queue(streams).map { it.stream.id })
            // The queue is a function of persisted fields only — a clock advance changes nothing.
            assertTrue(AttentionTiming.state(streams[1].checkAt, t0.plusSeconds(advance)) in TimeState.entries)
        }
    }

    // ------------------------------------------------------------------ 13, 16, 18, 20: jumps, safety, one clock

    @Test fun test13_backgroundClockJump_showsTheRealElapsedTime() {
        val dueAt = t0.plus(Duration.ofMinutes(5))
        assertEquals("00:05:00", AttentionTiming.format(dueAt, t0))
        val afterSevenMinutes = t0.plus(Duration.ofMinutes(7))                    // the app was away
        assertEquals("+00:02:00", AttentionTiming.format(dueAt, afterSevenMinutes))
        assertEquals(TimeState.OVERDUE, AttentionTiming.state(dueAt, afterSevenMinutes))
    }

    @Test fun test16_checkAgainCreatesNoDuplicateItem() = runBlocking {
        val (repo, actions, _) = harness(listOf(due("A", t0.minusSeconds(60)), due("B", t0.minusSeconds(30))))
        val before = ids(repo)
        actions.continueProcessing("A", AttentionTiming.checkAgainAt(t0, 5))
        clock.current = t0.plus(Duration.ofMinutes(5))
        actions.checkDue("A")
        assertEquals(1, repo.streams.value.count { it.id == "A" })
        assertEquals(before.size, ids(repo).size)
        assertEquals(setOf("A", "B"), ids(repo).toSet())
    }

    @Test fun test18_unknownItem_isSafe() = runBlocking {
        val (repo, actions, _) = harness(listOf(due("A", t0)))
        assertTrue(actions.continueProcessing("ghost", AttentionTiming.checkAgainAt(t0, 5)) is ActionResult.NotFound)
        assertEquals(listOf("A"), ids(repo))
        assertNull(AttentionTiming.dueAt(due("X", t0).copy(checkAt = null)))
        assertNotNull(AttentionTiming.dueAt(due("A", t0)))
    }

    @Test fun test20_manyTimersDeriveFromOneClock() {
        val items = (1..25).map { due("i$it", t0.plusSeconds((it - 13) * 60L)) }   // a mix of future and past
        val rendered = items.map { AttentionTiming.format(it.checkAt, t0) to AttentionTiming.state(it.checkAt, t0) }
        assertEquals(25, rendered.size)
        assertEquals(12, rendered.count { it.second == TimeState.OVERDUE })
        assertEquals(1, rendered.count { it.second == TimeState.DUE })
        assertEquals(12, rendered.count { it.second == TimeState.WAITING })
        assertTrue(rendered.filter { it.second == TimeState.OVERDUE }.all { it.first.startsWith("+") })
        // Same clock instant → same strings, every time (no per-card state anywhere).
        assertEquals(rendered, items.map { AttentionTiming.format(it.checkAt, t0) to AttentionTiming.state(it.checkAt, t0) })
    }
}

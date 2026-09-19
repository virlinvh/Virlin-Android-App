package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.schedule.AttentionSchedule
import com.virlin.app.domain.schedule.FakeAttentionScheduler
import com.virlin.app.domain.schedule.ScheduleKind
import com.virlin.app.domain.schedule.SchedulingWorkStreamRepository
import com.virlin.app.platform.AttentionAlarmReceiver
import com.virlin.app.platform.AttentionAlarmReceiver.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Pass 6 — durable wake-ups follow committed state. Deterministic: fake clock, fake scheduler,
 * in-memory repository behind the same decorator production uses.
 */
class AttentionSchedulingTest {

    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")
    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeAttentionScheduler
    private lateinit var errors: MutableList<Throwable>
    private lateinit var repo: SchedulingWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions

    private fun ws(id: String, state: WorkStreamState, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = "T-$id", state = state, executionPreference = mode.toPreference(), activeTaskId = active, createdAt = t0, updatedAt = t0)

    @Before fun setUp() {
        clock = FakeClock(t0); scheduler = FakeAttentionScheduler(); errors = mutableListOf()
        repo = SchedulingWorkStreamRepository(InMemoryWorkStreamRepository(seed = listOf(
            ws("h", FOCUS, active = "q17"), ws("e", READY, WorkStreamMode.EXTERNAL, "nl"),
            ws("x", READY, WorkStreamMode.EXTERNAL), ws("y", READY, WorkStreamMode.EXTERNAL), ws("z", READY, WorkStreamMode.EXTERNAL)
        )), scheduler) { errors += it }
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
    }
    private fun at(min: Long) = clock.now().plus(Duration.ofMinutes(min))
    private suspend fun s(id: String) = repo.getStream(id)!!

    // ------------------------------------------------------------------ scheduling

    @Test fun humanReturn_schedulesAtReturnAt() = runTest {
        actions.leaveFocus("h", at(5))
        assertEquals(AttentionSchedule("h", ScheduleKind.HUMAN_RETURN, at(5)), scheduler.current["h"])
    }

    @Test fun externalCheck_schedulesAtCheckAt() = runTest {
        actions.focusStream("e"); actions.handOffStream("e", checkAt = at(5))
        assertEquals(AttentionSchedule("e", ScheduleKind.EXTERNAL_CHECK, at(5)), scheduler.current["e"])
    }

    @Test fun resultReadyReturn_schedulesAtReturnAt() = runTest {
        actions.focusStream("e"); actions.handOffStream("e", checkAt = at(1)); clock.advance(Duration.ofMinutes(2)); actions.checkDue("e")
        actions.resultReadyLater("e", at(5))
        assertEquals(AttentionSchedule("e", ScheduleKind.EXTERNAL_RESULT_READY, at(5)), scheduler.current["e"])
    }

    @Test fun ready_focus_noCheck_leaveWithoutReminder_scheduleNothing() = runTest {
        assertTrue(scheduler.current.isEmpty())                                  // FOCUS h, READY others: nothing
        actions.leaveFocus("h")                                                  // → READY, no reminder
        assertNull(scheduler.current["h"])
        actions.focusStream("e"); actions.handOffStream("e")                     // NO CHECK
        assertEquals(PROCESSING, s("e").state); assertNull(s("e").checkAt); assertNull(scheduler.current["e"])
        assertNull(AttentionSchedule.of(s("e")))
        actions.focusStream("x"); actions.blockStream("x")
        assertNull(scheduler.current["x"])
    }

    // ------------------------------------------------------------------ replacement

    @Test fun deferHumanReturn_replaces() = runTest {
        actions.leaveFocus("h", at(5))
        actions.deferReturn("h", at(10))
        assertEquals(at(10), scheduler.current["h"]!!.dueAt); assertEquals(ScheduleKind.HUMAN_RETURN, scheduler.current["h"]!!.kind)
        assertEquals(1, scheduler.current.size)
    }

    @Test fun stillRunning_replacesExternalCheck() = runTest {
        actions.focusStream("e"); actions.handOffStream("e", checkAt = at(5))
        clock.advance(Duration.ofMinutes(6)); actions.checkDue("e")
        assertNull(scheduler.current["e"])                                       // surfaced CHECK: nothing pending
        actions.stillRunning("e", at(10))
        assertEquals(AttentionSchedule("e", ScheduleKind.EXTERNAL_CHECK, at(10)), scheduler.current["e"])
    }

    @Test fun deferResultReady_replaces() = runTest {
        actions.focusStream("e"); actions.handOffStream("e", checkAt = at(1)); clock.advance(Duration.ofMinutes(2)); actions.checkDue("e")
        actions.resultReadyLater("e", at(5)); actions.deferReturn("e", at(10))
        assertEquals(AttentionSchedule("e", ScheduleKind.EXTERNAL_RESULT_READY, at(10)), scheduler.current["e"])
    }

    // ------------------------------------------------------------------ cancellation

    @Test fun resume_cancelsHumanReturn() = runTest {
        actions.leaveFocus("h", at(5)); assertNotNull(scheduler.current["h"])
        actions.focusStream("h")
        assertNull(scheduler.current["h"]); assertTrue("cancel h" in scheduler.log)
    }

    @Test fun resultReady_block_complete_cancelExternalCheck() = runTest {
        actions.focusStream("e"); actions.handOffStream("e", checkAt = at(5))
        clock.advance(Duration.ofMinutes(6)); actions.checkDue("e"); assertNull(scheduler.current["e"])
        actions.stillRunning("e", at(5)); assertNotNull(scheduler.current["e"])
        clock.advance(Duration.ofMinutes(6)); actions.checkDue("e"); actions.resultReadyNow("e")
        assertNull(scheduler.current["e"])
        actions.handOffStream("e", checkAt = at(5)); assertNotNull(scheduler.current["e"])
        actions.blockStream("e"); assertNull(scheduler.current["e"])
        actions.focusStream("x"); actions.handOffStream("x", checkAt = at(5)); assertNotNull(scheduler.current["x"])
        actions.completeStream("x"); assertNull(scheduler.current["x"])
    }

    @Test fun completingActiveTask_keepsPendingCheck() = runTest {
        // Task completion is not an attention change: the stream's own timing wins.
        actions.leaveFocus("h", at(5))
        repo.transaction { saveTask(com.virlin.app.domain.model.Task("q17", "Q17", workStreamId = "h", status = com.virlin.app.domain.model.TaskStatus.IN_PROGRESS, createdAt = t0, updatedAt = t0)) }
        actions.completeTask("q17")
        assertEquals(at(5), scheduler.current["h"]!!.dueAt)
    }

    // ------------------------------------------------------------------ stale alarms

    @Test fun staleAlarms_ignored_currentReArmed() = runTest {
        actions.leaveFocus("h", at(5)); actions.deferReturn("h", at(10))
        clock.advance(Duration.ofMinutes(5))
        val old = AttentionAlarmReceiver.validate(repo, "h", ScheduleKind.HUMAN_RETURN, t0.plus(Duration.ofMinutes(5)), clock.now())
        assertTrue(old is Verdict.Stale); assertEquals(at(5), (old as Verdict.Stale).current!!.dueAt)   // +10 from t0 = +5 from now
        assertEquals(SNOOZED, s("h").state)                                               // nothing surfaced

        actions.focusStream("e"); actions.handOffStream("e", checkAt = at(1)); actions.stillRunning("e", at(3))
        clock.advance(Duration.ofMinutes(1))
        assertTrue(AttentionAlarmReceiver.validate(repo, "e", ScheduleKind.EXTERNAL_CHECK, t0.plus(Duration.ofMinutes(6)), clock.now()) is Verdict.Stale)

        actions.focusStream("x"); actions.handOffStream("x", checkAt = at(1)); clock.advance(Duration.ofMinutes(2)); actions.checkDue("x")
        actions.resultReadyLater("x", at(5)); val first = at(5); actions.deferReturn("x", at(8))
        clock.advance(Duration.ofMinutes(5))
        val v = AttentionAlarmReceiver.validate(repo, "x", ScheduleKind.EXTERNAL_RESULT_READY, first, clock.now())
        assertTrue(v is Verdict.Stale)
        // kind mismatch and missing stream are stale too
        assertTrue(AttentionAlarmReceiver.validate(repo, "x", ScheduleKind.HUMAN_RETURN, at(3), clock.now()) is Verdict.Stale)
        assertTrue(AttentionAlarmReceiver.validate(repo, "nope", ScheduleKind.HUMAN_RETURN, at(3), clock.now()) is Verdict.Stale)
    }

    @Test fun freshAlarm_isDue_andTitleComesFromRoomNotPayload() = runTest {
        actions.leaveFocus("h", at(5)); clock.advance(Duration.ofMinutes(5))
        val v = AttentionAlarmReceiver.validate(repo, "h", ScheduleKind.HUMAN_RETURN, clock.now(), clock.now())
        assertEquals(Verdict.Due("T-h"), v)
        // slightly early (inexact delivery) still counts as due
        clock.advance(Duration.ofSeconds(-20))
        assertTrue(AttentionAlarmReceiver.validate(repo, "h", ScheduleKind.HUMAN_RETURN, t0.plus(Duration.ofMinutes(5)), clock.now()) is Verdict.Due)
    }

    // ------------------------------------------------------------------ invariants

    @Test fun dueTrigger_neverCreatesFocus_andKeepsReason() = runTest {
        actions.leaveFocus("h", at(5)); actions.focusStream("x")
        clock.advance(Duration.ofMinutes(5))
        actions.checkDue("h")                                                    // what the receiver does
        assertEquals(CHECK, s("h").state); assertEquals(SnoozeReason.HUMAN_RETURN, s("h").snoozeReason)
        assertEquals("x", repo.getActiveFocus()!!.id)
        assertEquals("q17", s("h").activeTaskId)
    }

    @Test fun multipleStreams_scheduleIndependently() = runTest {
        actions.leaveFocus("h", at(5))
        actions.focusStream("e"); actions.handOffStream("e", checkAt = at(3))
        actions.focusStream("x"); actions.leaveFocus("x", at(5))
        actions.focusStream("y"); actions.handOffStream("y", checkAt = at(1)); clock.advance(Duration.ofMinutes(2)); actions.checkDue("y"); actions.resultReadyLater("y", at(3))
        assertEquals(setOf("h", "e", "x", "y"), scheduler.current.keys)
        assertEquals(ScheduleKind.EXTERNAL_RESULT_READY, scheduler.current["y"]!!.kind)
        assertEquals(ScheduleKind.EXTERNAL_CHECK, scheduler.current["e"]!!.kind)
        actions.focusStream("x")                                                 // only x changes
        assertEquals(setOf("h", "e", "y"), scheduler.current.keys)
    }

    @Test fun tickerAndAlarm_noDuplicateDueEvent() = runTest {
        actions.leaveFocus("h", at(5)); clock.advance(Duration.ofMinutes(5))
        assertTrue(actions.checkDue("h") is ActionResult.Success)               // ticker
        assertTrue(actions.checkDue("h") is ActionResult.Rejected)              // alarm arrives too
        assertEquals(1, repo.getEvents("h").count { it.type == EventType.CHECK_DUE })
        assertEquals(CHECK, s("h").state)
    }

    @Test fun schedulerFailure_doesNotCorruptState_andIsReported() = runTest {
        scheduler.failNext = true
        val r = actions.leaveFocus("h", at(5))
        assertTrue(r is ActionResult.Success)
        assertEquals(SNOOZED, s("h").state); assertEquals(at(5), s("h").snoozedUntil)      // Room truth intact
        assertEquals(1, errors.size); assertNull(scheduler.current["h"])
        scheduler.reconcileAll(repo.streams.value)                                         // startup heals it
        assertEquals(at(5), scheduler.current["h"]!!.dueAt)
    }

    @Test fun reconcileAll_idempotent_andCancelsStaleOnes() = runTest {
        actions.leaveFocus("h", at(5))
        scheduler.current["x"] = AttentionSchedule("x", ScheduleKind.HUMAN_RETURN, at(1))     // leftover alarm for a READY stream
        scheduler.reconcileAll(repo.streams.value); scheduler.reconcileAll(repo.streams.value)
        assertEquals(setOf("h"), scheduler.current.keys)
    }
}

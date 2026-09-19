package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.*
import com.virlin.app.domain.model.effectiveAttentionState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Pass 4 — finalized attention-exit semantics: LEAVE (human) vs HAND OFF (external), the
 * external check outcomes, human-return vs result-ready snoozes, and single Focus.
 * Deterministic: fake clock, sequential ids.
 */
class AttentionExitTest {

    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")
    private lateinit var clock: FakeClock
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions

    private fun ws(id: String, state: WorkStreamState, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null,
                   checkAt: Instant? = null, processingStartedAt: Instant? = null) =
        WorkStream(id = id, title = id, state = state, executionPreference = mode.toPreference(), activeTaskId = active, checkAt = checkAt,
            processingStartedAt = processingStartedAt, nextHumanAction = "next-$id", waitingFor = "wait-$id",
            lastHumanAction = "last-$id", createdAt = t0, updatedAt = t0)

    @Before fun setUp() {
        clock = FakeClock(t0)
        repo = InMemoryWorkStreamRepository(seed = listOf(
            ws("psych", FOCUS, active = "q17"),
            ws("anti", PROCESSING, WorkStreamMode.EXTERNAL, active = "nl", checkAt = t0.plusSeconds(60), processingStartedAt = t0.minusSeconds(600)),
            ws("claude", PROCESSING, WorkStreamMode.EXTERNAL, active = "t1"),
            ws("notion", READY, WorkStreamMode.EXTERNAL, active = "t1")
        ))
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        // psych has an open FocusSession attributed to q17
        runTest { actions.leaveFocus("psych"); actions.focusStream("psych") }
    }

    private suspend fun s(id: String) = repo.getStream(id)!!
    private suspend fun events(id: String) = repo.getEvents(id).map { it.type }
    private fun <T> ActionResult<T>.rejected() = (this as ActionResult.Rejected).reason
    private val in5 get() = clock.now().plus(Duration.ofMinutes(5))

    // ------------------------------------------------------------------ LEAVE

    @Test fun leave_noReturn_toReady_closesSession_snapshots_keepsTask_noProcessing() = runTest {
        assertNotNull(repo.getOpenFocusSession("psych"))
        val r = actions.leaveFocus("psych")
        assertTrue(r is ActionResult.Success)
        val p = s("psych")
        assertEquals(READY, p.state)
        assertNull(repo.getOpenFocusSession("psych"))
        assertEquals("q17", p.activeTaskId)
        assertNull(p.checkAt); assertNull(p.snoozedUntil); assertNull(p.snoozeReason); assertNull(p.processingStartedAt)
        val snap = repo.getLatestSnapshot("psych")!!
        assertEquals("q17", snap.taskId); assertEquals(READY, snap.reason)
        assertEquals("next-psych", snap.nextHumanAction); assertEquals("wait-psych", snap.waitingFor); assertEquals("last-psych", snap.lastHumanAction)
        assertTrue(EventType.LEFT in events("psych"))
        assertNull(repo.getActiveFocus())                                    // Focus slot freed
    }

    @Test fun leave_withReturn_toSnoozedHumanReturn_storesReturnAt() = runTest {
        val at = in5
        actions.leaveFocus("psych", at)
        val p = s("psych")
        assertEquals(SNOOZED, p.state)
        assertEquals(SnoozeReason.HUMAN_RETURN, p.snoozeReason)
        assertEquals(at, p.snoozedUntil); assertEquals(at, p.checkAt)
        assertNull(p.processingStartedAt)
        assertEquals("q17", p.activeTaskId)
        assertNull(repo.getOpenFocusSession("psych"))
        assertEquals("q17", repo.getLatestSnapshot("psych")!!.taskId)
        assertNull(repo.getActiveFocus())
    }

    @Test fun leave_pastReturn_rejected_andRequiresFocus() = runTest {
        assertEquals(DomainError.InvalidSnoozeTime, actions.leaveFocus("psych", t0.minusSeconds(1)).rejected())
        assertEquals(DomainError.InvalidSnoozeTime, actions.leaveFocus("psych", t0).rejected())
        assertEquals(DomainError.NotInFocus, actions.leaveFocus("notion").rejected())
        assertEquals(FOCUS, s("psych").state)
    }

    @Test fun leave_neverProcessing_evenForExternalStream() = runTest {
        actions.focusStream("notion")               // external stream in Focus (writing a prompt)
        actions.leaveFocus("notion", in5)
        assertEquals(SNOOZED, s("notion").state)
        assertEquals(SnoozeReason.HUMAN_RETURN, s("notion").snoozeReason)
        assertNull(s("notion").processingStartedAt)
    }

    // ------------------------------------------------------------------ HAND OFF

    @Test fun handOff_toProcessing_withOptionalCheck_keepsTask_closesSession_snapshots() = runTest {
        actions.focusStream("notion")
        val at = in5
        actions.handOffStream("notion", checkAt = at)
        val c = s("notion")
        assertEquals(PROCESSING, c.state)
        assertEquals(clock.now(), c.processingStartedAt); assertEquals(at, c.checkAt)
        assertNull(c.snoozeReason); assertNull(c.snoozedUntil)
        assertEquals("t1", c.activeTaskId)
        assertNull(repo.getOpenFocusSession("notion"))
        assertEquals("t1", repo.getLatestSnapshot("notion")!!.taskId)
        assertTrue(EventType.HANDOFF in events("notion"))
    }

    @Test fun handOff_noCheck_isValid() = runTest {
        actions.focusStream("notion")
        actions.handOffStream("notion")
        val c = s("notion")
        assertEquals(PROCESSING, c.state); assertNotNull(c.processingStartedAt); assertNull(c.checkAt)
    }

    // ------------------------------------------------------------------ CHECK

    @Test fun dueProcessing_surfacesCheck_withoutFocus() = runTest {
        clock.advance(Duration.ofSeconds(61))
        assertEquals(CHECK, effectiveAttentionState(s("anti"), clock.now()))
        actions.checkDue("anti")
        assertEquals(CHECK, s("anti").state)
        assertNull(s("anti").snoozeReason)                                  // external check, not a return
        assertEquals("psych", repo.getActiveFocus()!!.id)                   // Focus not stolen
        assertNull(repo.getOpenFocusSession("anti"))
    }

    @Test fun stillRunning_backToProcessing_newCheck_noSession_keepsTask() = runTest {
        clock.advance(Duration.ofSeconds(61)); actions.checkDue("anti")
        val started = s("anti").processingStartedAt
        val at = in5
        assertTrue(actions.stillRunning("anti", at) is ActionResult.Success)
        val a = s("anti")
        assertEquals(PROCESSING, a.state); assertEquals(at, a.checkAt); assertEquals(started, a.processingStartedAt)
        assertEquals("nl", a.activeTaskId)
        assertNull(repo.getOpenFocusSession("anti"))
        assertEquals(DomainError.InvalidSnoozeTime, actions.stillRunning("anti", t0).rejected())
    }

    @Test fun stillRunning_rejectedForHumanReturn() = runTest {
        actions.leaveFocus("psych", in5); clock.advance(Duration.ofMinutes(6)); actions.checkDue("psych")
        assertEquals(CHECK, s("psych").state)
        assertEquals(DomainError.NotAnExternalCheck, actions.stillRunning("psych", in5).rejected())
    }

    @Test fun resultReadyNow_toFocus_clearsProcessing_sessionHasTask_displacesCurrent() = runTest {
        clock.advance(Duration.ofSeconds(61)); actions.checkDue("anti")
        val r = actions.resultReadyNow("anti")
        assertTrue(r is ActionResult.Success)
        val a = s("anti")
        assertEquals(FOCUS, a.state); assertNull(a.processingStartedAt); assertNull(a.checkAt); assertNull(a.snoozeReason)
        assertEquals("nl", a.activeTaskId)
        assertEquals("nl", repo.getOpenFocusSession("anti")!!.taskId)
        assertEquals(READY, s("psych").state)                               // single Focus: displaced
        assertEquals("anti", repo.getActiveFocus()!!.id)
        assertTrue(EventType.RESULT_READY in events("anti"))
    }

    @Test fun resultReadyLater_toSnoozedResultReady_clearsProcessing_keepsTask() = runTest {
        clock.advance(Duration.ofSeconds(61)); actions.checkDue("anti")
        val at = in5
        actions.resultReadyLater("anti", at)
        val a = s("anti")
        assertEquals(SNOOZED, a.state); assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, a.snoozeReason)
        assertNull(a.processingStartedAt); assertEquals(at, a.snoozedUntil); assertEquals(at, a.checkAt)
        assertEquals("nl", a.activeTaskId)
        assertEquals("nl", repo.getLatestSnapshot("anti")!!.taskId)
        assertEquals(DomainError.InvalidSnoozeTime, actions.resultReadyLater("claude", t0).rejected())
        assertEquals(DomainError.NotAnExternalCheck, actions.resultReadyLater("psych", in5).rejected())
    }

    @Test fun blocked_fromCheck_clearsProcessingFields_keepsTaskAndContext() = runTest {
        clock.advance(Duration.ofSeconds(61)); actions.checkDue("anti")
        actions.blockStream("anti", "waiting for credentials")
        val a = s("anti")
        assertEquals(BLOCKED, a.state); assertNull(a.checkAt); assertNull(a.processingStartedAt)
        assertEquals("nl", a.activeTaskId); assertEquals("next-anti", a.nextHumanAction)
        assertTrue(EventType.BLOCKED in events("anti"))
    }

    // ------------------------------------------------------------------ RETURN

    @Test fun humanReturn_due_resume_toFocus_sessionHasTask_fieldsCleared() = runTest {
        actions.leaveFocus("psych", in5); clock.advance(Duration.ofMinutes(6)); actions.checkDue("psych")
        assertEquals(SnoozeReason.HUMAN_RETURN, s("psych").snoozeReason)   // kept through the due CHECK
        actions.focusStream("psych")
        val p = s("psych")
        assertEquals(FOCUS, p.state); assertNull(p.snoozedUntil); assertNull(p.checkAt); assertNull(p.snoozeReason)
        assertEquals("q17", p.activeTaskId)
        assertEquals("q17", repo.getOpenFocusSession("psych")!!.taskId)
    }

    @Test fun resultReadyDue_focus_toFocus() = runTest {
        clock.advance(Duration.ofSeconds(61)); actions.checkDue("anti"); actions.resultReadyLater("anti", in5)
        clock.advance(Duration.ofMinutes(6)); actions.checkDue("anti")
        assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, s("anti").snoozeReason)
        actions.focusStream("anti")
        assertEquals(FOCUS, s("anti").state); assertNull(s("anti").snoozeReason)
        assertEquals("nl", repo.getOpenFocusSession("anti")!!.taskId)
    }

    @Test fun deferHumanReturn_staysHumanReturn() = runTest {
        actions.leaveFocus("psych", in5)
        actions.deferReturn("psych", clock.now().plus(Duration.ofMinutes(10)))
        assertEquals(SNOOZED, s("psych").state); assertEquals(SnoozeReason.HUMAN_RETURN, s("psych").snoozeReason)
        clock.advance(Duration.ofMinutes(11)); actions.checkDue("psych")
        actions.deferReturn("psych", in5)                                    // from the due CHECK too
        assertEquals(SNOOZED, s("psych").state); assertEquals(SnoozeReason.HUMAN_RETURN, s("psych").snoozeReason)
        assertNull(s("psych").processingStartedAt)
        assertTrue(EventType.RETURN_DEFERRED in events("psych"))
    }

    @Test fun deferResultReady_staysResultReady_neverProcessing() = runTest {
        clock.advance(Duration.ofSeconds(61)); actions.checkDue("anti"); actions.resultReadyLater("anti", in5)
        clock.advance(Duration.ofMinutes(6)); actions.checkDue("anti")
        actions.deferReturn("anti", in5)
        val a = s("anti")
        assertEquals(SNOOZED, a.state); assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, a.snoozeReason)
        assertNull(a.processingStartedAt)
        assertEquals(DomainError.NotAReturn, actions.deferReturn("claude", in5).rejected())   // processing is not a return
    }

    // ------------------------------------------------------------------ SINGLE FOCUS

    @Test fun resumedHumanWork_displacesCurrentFocus() = runTest {
        actions.leaveFocus("psych", in5)
        actions.focusStream("notion")
        clock.advance(Duration.ofMinutes(6)); actions.checkDue("psych")
        actions.focusStream("psych")
        assertEquals(FOCUS, s("psych").state); assertEquals(READY, s("notion").state)
        assertEquals(1, repo.streams.value.count { it.state == FOCUS })
        assertEquals(2, repo.streams.value.count { it.state == PROCESSING })  // many processing is normal
    }

    // ------------------------------------------------------------------ TASK CONTEXT across exits

    @Test fun activeTask_survivesEveryExit() = runTest {
        actions.leaveFocus("psych", in5); assertEquals("q17", s("psych").activeTaskId)
        actions.focusStream("notion"); actions.handOffStream("notion", checkAt = in5); assertEquals("t1", s("notion").activeTaskId)
        clock.advance(Duration.ofMinutes(6)); actions.checkDue("notion")
        actions.stillRunning("notion", in5); assertEquals("t1", s("notion").activeTaskId)
        clock.advance(Duration.ofMinutes(6)); actions.checkDue("notion")
        actions.resultReadyLater("notion", in5); assertEquals("t1", s("notion").activeTaskId)
        clock.advance(Duration.ofMinutes(6)); actions.checkDue("notion")
        actions.focusStream("notion")
        assertEquals("t1", repo.getOpenFocusSession("notion")!!.taskId)
    }
}

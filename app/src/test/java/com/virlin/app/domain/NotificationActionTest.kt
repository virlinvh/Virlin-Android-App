package com.virlin.app.domain

import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.schedule.FakeAttentionScheduler
import com.virlin.app.domain.schedule.ScheduleKind
import com.virlin.app.domain.schedule.SchedulingWorkStreamRepository
import com.virlin.app.platform.AttentionNotificationModel
import com.virlin.app.platform.NotificationAction
import com.virlin.app.platform.NotificationActionHandler
import com.virlin.app.platform.NotificationActionHandler.Outcome
import com.virlin.app.platform.NotificationTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Pass 7 — actionable notifications: model per kind, actions through VirlinActions, stale/idempotent safety. */
class NotificationActionTest {

    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")
    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeAttentionScheduler
    private lateinit var repo: SchedulingWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions

    private fun ws(id: String, title: String, state: WorkStreamState, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, mode = mode, activeTaskId = active, createdAt = t0, updatedAt = t0)

    @Before fun setUp() {
        clock = FakeClock(t0); scheduler = FakeAttentionScheduler()
        repo = SchedulingWorkStreamRepository(InMemoryWorkStreamRepository(
            seed = listOf(ws("h", "Psychology Unit 23", FOCUS, active = "q17"), ws("e", "Antigravity", READY, WorkStreamMode.EXTERNAL, "nl"), ws("x", "Notion", READY)),
            seedTasks = listOf(Task("q17", "Question 17", workStreamId = "h", status = TaskStatus.IN_PROGRESS, createdAt = t0, updatedAt = t0),
                Task("nl", "Natural Language", workStreamId = "e", status = TaskStatus.IN_PROGRESS, createdAt = t0, updatedAt = t0))
        ), scheduler)
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
    }
    private suspend fun s(id: String) = repo.getStream(id)!!
    private fun at(m: Long) = clock.now().plus(Duration.ofMinutes(m))
    private suspend fun run(id: String, kind: ScheduleKind, a: NotificationAction) =
        NotificationActionHandler.execute(repo, actions, id, kind, a, clock.now())

    /** h: leave with return, time passes, due CHECK (human return). */
    private suspend fun humanReturnDue() { actions.leaveFocus("h", at(5)); clock.advance(Duration.ofMinutes(5)); actions.checkDue("h") }
    /** e: handed off, check due (external check). */
    private suspend fun externalCheckDue() { actions.focusStream("e"); actions.handOffStream("e", checkAt = at(1)); clock.advance(Duration.ofMinutes(1)); actions.checkDue("e") }
    /** e: result ready later, due (result-ready return). */
    private suspend fun resultReadyDue() { externalCheckDue(); actions.resultReadyLater("e", at(5)); clock.advance(Duration.ofMinutes(5)); actions.checkDue("e") }

    // ------------------------------------------------------------------ models

    @Test fun models_perKind_actions_text_target() = runTest {
        humanReturnDue()
        val h = AttentionNotificationModel.build(s("h"), repo.getTask("q17"), ScheduleKind.HUMAN_RETURN)
        assertEquals(listOf(NotificationAction.RESUME, NotificationAction.DEFER_5), h.actions)
        assertEquals("Psychology Unit 23", h.title); assertEquals("Ready to continue · Question 17", h.text)
        assertEquals(NotificationTarget.WORKSTREAM_DETAIL, h.bodyTarget)
        assertEquals("+5 MIN", NotificationAction.DEFER_5.label); assertEquals("RESUME", NotificationAction.RESUME.label)

        val e = AttentionNotificationModel.build(s("e"), repo.getTask("nl"), ScheduleKind.EXTERNAL_RESULT_READY)
        assertEquals(listOf(NotificationAction.FOCUS_NOW, NotificationAction.DEFER_5), e.actions)
        assertEquals("Antigravity", e.title); assertEquals("Result ready · Natural Language", e.text)
        assertFalse(e.text.contains("Check"))

        val c = AttentionNotificationModel.build(s("e"), null, ScheduleKind.EXTERNAL_CHECK)
        assertEquals(listOf(NotificationAction.CHECK, NotificationAction.CHECK_AGAIN_5), c.actions)
        assertEquals("Check Antigravity", c.title); assertEquals("Check due", c.text)   // no task → no placeholder
        assertEquals(NotificationTarget.CHECK_FLOW, c.bodyTarget)
        assertFalse(NotificationAction.CHECK.mutates)
        // a task that is not the active one is never shown
        val other = AttentionNotificationModel.build(s("h"), repo.getTask("nl"), ScheduleKind.HUMAN_RETURN)
        assertNull(other.taskTitle)
    }

    // ------------------------------------------------------------------ HUMAN_RETURN

    @Test fun resume_focuses_keepsTask_cancelsSchedule_attributesSession() = runTest {
        humanReturnDue(); actions.focusStream("x")                                   // someone else has Focus
        val r = run("h", ScheduleKind.HUMAN_RETURN, NotificationAction.RESUME)
        assertEquals(Outcome.Applied(NotificationAction.RESUME), r)
        assertEquals(FOCUS, s("h").state); assertEquals("q17", s("h").activeTaskId); assertNull(s("h").snoozeReason)
        assertEquals("q17", repo.getOpenFocusSession("h")!!.taskId)
        assertEquals(READY, s("x").state)                                            // accepted displacement
        assertEquals(1, repo.streams.value.count { it.state == FOCUS })
        assertNull(scheduler.current["h"])
    }

    @Test fun defer5_staysHumanReturn_fromActionTime_replacesSchedule() = runTest {
        humanReturnDue(); clock.advance(Duration.ofMinutes(3))                       // user taps 3 min after due
        assertEquals(Outcome.Applied(NotificationAction.DEFER_5), run("h", ScheduleKind.HUMAN_RETURN, NotificationAction.DEFER_5))
        assertEquals(SNOOZED, s("h").state); assertEquals(SnoozeReason.HUMAN_RETURN, s("h").snoozeReason)
        assertEquals(clock.now().plus(Duration.ofMinutes(5)), s("h").snoozedUntil)
        assertEquals(clock.now().plus(Duration.ofMinutes(5)), scheduler.current["h"]!!.dueAt)
        assertTrue(EventType.RETURN_DEFERRED in repo.getEvents("h").map { it.type })
        assertNull(s("h").processingStartedAt)
    }

    @Test fun staleResume_ignored_andDoubleResumeIdempotent() = runTest {
        humanReturnDue()
        actions.focusStream("h")                                                     // resumed in-app first
        val sessions = repo.getFocusSessions("h").size
        assertTrue(run("h", ScheduleKind.HUMAN_RETURN, NotificationAction.RESUME) is Outcome.Stale)
        assertTrue(run("h", ScheduleKind.HUMAN_RETURN, NotificationAction.DEFER_5) is Outcome.Stale)
        assertEquals(sessions, repo.getFocusSessions("h").size); assertEquals(FOCUS, s("h").state)
        // double tap from the notification
        actions.leaveFocus("h", at(5)); clock.advance(Duration.ofMinutes(5)); actions.checkDue("h")
        val n = repo.getFocusSessions("h").size
        assertTrue(run("h", ScheduleKind.HUMAN_RETURN, NotificationAction.RESUME) is Outcome.Applied)
        assertTrue(run("h", ScheduleKind.HUMAN_RETURN, NotificationAction.RESUME) is Outcome.Stale)
        assertEquals(n + 1, repo.getFocusSessions("h").size)
        assertEquals(1, repo.getEvents("h").count { it.type == EventType.FOCUS_STARTED && it.at == clock.now() })
    }

    // ------------------------------------------------------------------ RESULT_READY

    @Test fun focusNow_toFocus_noProcessing_keepsTask() = runTest {
        resultReadyDue()
        assertEquals(Outcome.Applied(NotificationAction.FOCUS_NOW), run("e", ScheduleKind.EXTERNAL_RESULT_READY, NotificationAction.FOCUS_NOW))
        val e = s("e")
        assertEquals(FOCUS, e.state); assertNull(e.processingStartedAt); assertNull(e.checkAt); assertEquals("nl", e.activeTaskId)
        assertEquals("nl", repo.getOpenFocusSession("e")!!.taskId); assertNull(scheduler.current["e"])
    }

    @Test fun resultReadyDefer5_staysResultReady_notProcessing() = runTest {
        resultReadyDue()
        assertEquals(Outcome.Applied(NotificationAction.DEFER_5), run("e", ScheduleKind.EXTERNAL_RESULT_READY, NotificationAction.DEFER_5))
        assertEquals(SNOOZED, s("e").state); assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, s("e").snoozeReason)
        assertNull(s("e").processingStartedAt); assertEquals(ScheduleKind.EXTERNAL_RESULT_READY, scheduler.current["e"]!!.kind)
    }

    @Test fun staleFocusNow_ignored_kindMismatchIgnored() = runTest {
        resultReadyDue(); actions.deferReturn("e", at(10)); clock.advance(Duration.ofMinutes(10)); actions.checkDue("e")
        actions.focusStream("e")                                                     // handled in-app
        assertTrue(run("e", ScheduleKind.EXTERNAL_RESULT_READY, NotificationAction.FOCUS_NOW) is Outcome.Stale)
        assertEquals(FOCUS, s("e").state)
        actions.focusStream("h"); humanReturnDue()
        assertTrue(run("h", ScheduleKind.EXTERNAL_RESULT_READY, NotificationAction.FOCUS_NOW) is Outcome.Stale)   // wrong kind for h
        assertEquals(CHECK, s("h").state)
    }

    // ------------------------------------------------------------------ EXTERNAL_CHECK

    @Test fun check_isNavigationOnly_noFocus_andCheckAgain5_isStillRunning() = runTest {
        externalCheckDue()
        assertTrue(run("e", ScheduleKind.EXTERNAL_CHECK, NotificationAction.CHECK) is Outcome.Stale)   // never mutates
        assertEquals(CHECK, s("e").state); assertNull(repo.getOpenFocusSession("e"))
        assertEquals(NotificationTarget.CHECK_FLOW, AttentionNotificationModel.build(s("e"), null, ScheduleKind.EXTERNAL_CHECK).bodyTarget)
        val sessions = repo.getFocusSessions("e").size
        assertEquals(Outcome.Applied(NotificationAction.CHECK_AGAIN_5), run("e", ScheduleKind.EXTERNAL_CHECK, NotificationAction.CHECK_AGAIN_5))
        assertEquals(PROCESSING, s("e").state); assertEquals(clock.now().plus(Duration.ofMinutes(5)), s("e").checkAt)
        assertEquals("nl", s("e").activeTaskId); assertEquals(sessions, repo.getFocusSessions("e").size)
        assertEquals(ScheduleKind.EXTERNAL_CHECK, scheduler.current["e"]!!.kind)
        // RESUME is not a valid external-check action
        assertTrue(run("e", ScheduleKind.EXTERNAL_CHECK, NotificationAction.RESUME) is Outcome.Stale)
    }

    // ------------------------------------------------------------------ general

    @Test fun completedStream_andMissingStream_ignored() = runTest {
        humanReturnDue(); actions.focusStream("h"); actions.completeStream("h")
        assertTrue(run("h", ScheduleKind.HUMAN_RETURN, NotificationAction.RESUME) is Outcome.Stale)
        assertEquals(DONE, s("h").state)
        assertTrue(run("nope", ScheduleKind.HUMAN_RETURN, NotificationAction.RESUME) is Outcome.Stale)
    }

    @Test fun cancelledActiveTask_domainWins_onResume() = runTest {
        humanReturnDue(); actions.cancelTask("q17")                                  // cleared active task via another surface
        assertNull(s("h").activeTaskId)
        assertTrue(run("h", ScheduleKind.HUMAN_RETURN, NotificationAction.RESUME) is Outcome.Applied)
        assertEquals(FOCUS, s("h").state); assertNull(s("h").activeTaskId)           // not revived
        assertNull(repo.getOpenFocusSession("h")!!.taskId)
    }

    @Test fun bodyTapAndDismissal_mutateNothing_multipleIdsIndependent() = runTest {
        humanReturnDue(); externalCheckDue()
        val before = repo.streams.value
        // body tap = navigation target only; dismissal = nothing at all (no handler call exists for it)
        val h = AttentionNotificationModel.build(s("h"), null, ScheduleKind.HUMAN_RETURN)
        val e = AttentionNotificationModel.build(s("e"), null, ScheduleKind.EXTERNAL_CHECK)
        assertNotEquals(h.streamId, e.streamId); assertNotEquals(h.actions, e.actions)
        assertEquals(before, repo.streams.value)
        assertEquals(2, repo.streams.value.count { it.state == CHECK })
    }
}

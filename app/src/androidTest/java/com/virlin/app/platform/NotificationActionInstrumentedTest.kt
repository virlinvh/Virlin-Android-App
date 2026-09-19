package com.virlin.app.platform

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.virlin.app.MainActivity
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.schedule.ScheduleKind
import com.virlin.app.ui.hierarchy.WorkStreamDetailTag
import com.virlin.app.ui.screens.NowChooserTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * Pass 7 on the device: real notifications with their actions, the action receiver run with
 * NO Activity alive against the production Room graph, stale protection, per-stream identity,
 * and notification routing into the live app (WorkStream Detail / "What happened?").
 */
@RunWith(AndroidJUnit4::class)
class NotificationActionInstrumentedTest {

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val composeRule = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val nm get() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun stream(id: String) = runBlocking { VirlinGraph.repository.getStream(id)!! }
    private fun at(min: Long) = VirlinGraph.clock.now().plus(Duration.ofMinutes(min))
    private fun active(id: String) = nm.activeNotifications.firstOrNull { it.id == AttentionNotifications.notificationId(id) }

    @Before fun setUp() {
        VirlinGraph.init(ctx)
        runBlocking { VirlinGraph.ensureReady() }
        nm.cancelAll()
        runBlocking {
            listOf("s1", "s2", "s7", "s10").forEach { VirlinGraph.actions.leaveFocus(it); VirlinGraph.actions.markReady(it) }
            VirlinGraph.actions.focusStream("s1")
        }
    }

    private fun humanReturnDue(id: String) = runBlocking {
        VirlinGraph.actions.focusStream(id); VirlinGraph.actions.leaveFocus(id, at(1)); VirlinGraph.actions.deferReturn(id, VirlinGraph.clock.now().plusSeconds(1))
        Thread.sleep(1100); VirlinGraph.actions.checkDue(id)
    }
    private fun externalCheckDue(id: String) = runBlocking {
        VirlinGraph.actions.focusStream(id); VirlinGraph.actions.handOffStream(id, checkAt = VirlinGraph.clock.now().plusSeconds(1))
        Thread.sleep(1100); VirlinGraph.actions.checkDue(id)
    }
    private fun fire(id: String, kind: ScheduleKind, action: NotificationAction) {
        NotificationActionReceiver().onReceive(ctx, AttentionNotifications.actionIntent(ctx, AttentionNotificationModel.build(stream(id), null, kind), action))
        Thread.sleep(1500)
    }
    private fun postFromRoom(id: String, kind: ScheduleKind) = runBlocking {
        val s = stream(id); val t = s.activeTaskId?.let { VirlinGraph.repository.getTask(it) }
        AttentionNotifications.post(ctx, AttentionNotificationModel.build(s, t, kind)); Thread.sleep(500)
    }

    @Test fun humanReturn_notification_actions_resume_defer_stale() {
        humanReturnDue("s1")
        postFromRoom("s1", ScheduleKind.HUMAN_RETURN)
        val n = active("s1")!!.notification
        assertEquals("Psychology", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        // Text follows Room: the active task is shown only while it is still active (earlier classes may have completed it)
        val expectedText = stream("s1").activeTaskId?.let { runBlocking { VirlinGraph.repository.getTask(it) } }?.let { "Ready to continue · ${it.title}" } ?: "Ready to continue"
        assertEquals(expectedText, n.extras.getString(android.app.Notification.EXTRA_TEXT))
        assertEquals(listOf("RESUME", "+5 MIN"), n.actions.map { it.title.toString() })
        // +5 MIN with no Activity: Room updated, schedule replaced, notification gone
        fire("s1", ScheduleKind.HUMAN_RETURN, NotificationAction.DEFER_5)
        stream("s1").let { assertEquals(WorkStreamState.SNOOZED, it.state); assertEquals(SnoozeReason.HUMAN_RETURN, it.snoozeReason); assertTrue(it.snoozedUntil!!.isAfter(at(4))) }
        assertTrue(AndroidAttentionScheduler(ctx).isScheduled("s1")); assertNull(active("s1"))
        // RESUME from the due CHECK with no Activity: FOCUS, task kept, session attributed
        runBlocking { VirlinGraph.actions.deferReturn("s1", VirlinGraph.clock.now().plusSeconds(1)) }; Thread.sleep(1100); runBlocking { VirlinGraph.actions.checkDue("s1") }
        postFromRoom("s1", ScheduleKind.HUMAN_RETURN)
        fire("s1", ScheduleKind.HUMAN_RETURN, NotificationAction.RESUME)
        val activeBefore = stream("s1").activeTaskId
        stream("s1").let { assertEquals(WorkStreamState.FOCUS, it.state); assertEquals(activeBefore, it.activeTaskId) }
        assertEquals(activeBefore, runBlocking { VirlinGraph.repository.getOpenFocusSession("s1") }!!.taskId)
        assertNull(active("s1"))
        // stale: fire RESUME again → nothing changes, no second session
        val sessions = runBlocking { VirlinGraph.repository.getFocusSessions("s1") }.size
        fire("s1", ScheduleKind.HUMAN_RETURN, NotificationAction.RESUME)
        assertEquals(sessions, runBlocking { VirlinGraph.repository.getFocusSessions("s1") }.size)
        assertEquals(WorkStreamState.FOCUS, stream("s1").state)
    }

    @Test fun resultReady_notification_focusNow_defer() {
        externalCheckDue("s2")
        runBlocking { VirlinGraph.actions.resultReadyLater("s2", VirlinGraph.clock.now().plusSeconds(1)) }; Thread.sleep(1100); runBlocking { VirlinGraph.actions.checkDue("s2") }
        postFromRoom("s2", ScheduleKind.EXTERNAL_RESULT_READY)
        val n = active("s2")!!.notification
        assertEquals("Antigravity", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("Result ready", n.extras.getString(android.app.Notification.EXTRA_TEXT))
        assertEquals(listOf("FOCUS NOW", "+5 MIN"), n.actions.map { it.title.toString() })
        fire("s2", ScheduleKind.EXTERNAL_RESULT_READY, NotificationAction.DEFER_5)
        stream("s2").let { assertEquals(WorkStreamState.SNOOZED, it.state); assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, it.snoozeReason); assertNull(it.processingStartedAt) }
        runBlocking { VirlinGraph.actions.deferReturn("s2", VirlinGraph.clock.now().plusSeconds(1)) }; Thread.sleep(1100); runBlocking { VirlinGraph.actions.checkDue("s2") }
        fire("s2", ScheduleKind.EXTERNAL_RESULT_READY, NotificationAction.FOCUS_NOW)
        stream("s2").let { assertEquals(WorkStreamState.FOCUS, it.state); assertNull(it.checkAt) }
        assertEquals(WorkStreamState.READY, stream("s1").state)                        // displaced, single Focus
        assertNull(active("s2"))
    }

    @Test fun externalCheck_notification_checkIsNavigation_checkAgain5_and_twoIds() {
        externalCheckDue("s2"); humanReturnDue("s7")
        postFromRoom("s2", ScheduleKind.EXTERNAL_CHECK); postFromRoom("s7", ScheduleKind.HUMAN_RETURN)
        val c = active("s2")!!.notification; val h = active("s7")!!.notification
        assertEquals("Check Antigravity", c.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(listOf("CHECK", "+5 MIN"), c.actions.map { it.title.toString() })
        assertEquals(listOf("RESUME", "+5 MIN"), h.actions.map { it.title.toString() })
        assertNotEquals(AttentionNotifications.notificationId("s2"), AttentionNotifications.notificationId("s7"))
        // CHECK never mutates
        fire("s2", ScheduleKind.EXTERNAL_CHECK, NotificationAction.CHECK)
        assertEquals(WorkStreamState.CHECK, stream("s2").state); assertNull(runBlocking { VirlinGraph.repository.getOpenFocusSession("s2") })
        // +5 MIN = Still running
        fire("s2", ScheduleKind.EXTERNAL_CHECK, NotificationAction.CHECK_AGAIN_5)
        stream("s2").let { assertEquals(WorkStreamState.PROCESSING, it.state); assertNotNull(it.checkAt) }
        assertNull(active("s2")); assertNotNull(active("s7"))                          // only s2's went away
    }

    /** Now runs infinite transitions, so the Compose clock is pinned and pumped by hand. */
    private fun pumpUntil(timeoutMs: Long, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < end) {
            composeRule.mainClock.advanceTimeByFrame(); Thread.sleep(8)
            if (runCatching { condition() }.getOrDefault(false)) return
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    @Test fun bodyTap_navigates_withoutMutation_andCheckOpensFlow() {
        composeRule.mainClock.autoAdvance = false
        humanReturnDue("s7")
        val before = stream("s7")
        ActivityScenario.launch<MainActivity>(AttentionNotifications.bodyIntent(ctx, "s7", NotificationTarget.WORKSTREAM_DETAIL)).use {
            pumpUntil(10_000) { composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag(WorkStreamDetailTag)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, stream("s7"))                                         // navigation only
        }
        externalCheckDue("s2")
        ActivityScenario.launch<MainActivity>(AttentionNotifications.bodyIntent(ctx, "s2", NotificationTarget.CHECK_FLOW)).use {
            pumpUntil(10_000) { composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("chooser_still_running"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(WorkStreamState.CHECK, stream("s2").state)                   // not focused by opening
        }
    }

    @Test fun completedStream_staleAction_ignored_andPermissionSafety() {
        humanReturnDue("s10")
        runBlocking { VirlinGraph.actions.focusStream("s10"); VirlinGraph.actions.completeStream("s10") }
        fire("s10", ScheduleKind.HUMAN_RETURN, NotificationAction.RESUME)
        assertEquals(WorkStreamState.DONE, stream("s10").state)
        // posting/cancelling is always safe; handler is independent of the permission
        AttentionNotifications.post(ctx, AttentionNotificationModel.build(stream("s1"), null, ScheduleKind.HUMAN_RETURN))
        AttentionNotifications.cancel(ctx, "s1")
    }
}

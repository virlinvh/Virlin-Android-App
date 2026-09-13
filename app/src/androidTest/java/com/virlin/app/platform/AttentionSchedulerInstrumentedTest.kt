package com.virlin.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.schedule.AttentionSchedule
import com.virlin.app.domain.schedule.ScheduleKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * Pass 6 on the device: real AlarmManager PendingIntents, the real receiver against the
 * production Room graph, startup rescheduling, notification wording/permission safety.
 * Timing itself is proven deterministically in AttentionSchedulingTest; here we prove the
 * platform plumbing exists, is addressable per stream, and refuses stale triggers.
 */
@RunWith(AndroidJUnit4::class)
class AttentionSchedulerInstrumentedTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var android: AndroidAttentionScheduler
    private fun at(min: Long): Instant = VirlinGraph.clock.now().plus(Duration.ofMinutes(min))
    private fun stream(id: String) = runBlocking { VirlinGraph.repository.getStream(id)!! }

    @Before fun setUp() {
        VirlinGraph.init(ctx); VirlinGraph.start()
        android = AndroidAttentionScheduler(ctx)
        runBlocking {
            // Known baseline regardless of test order: s1/s2/s7 READY (via validated actions), then s1 in Focus.
            listOf("s1", "s2", "s7").forEach { id ->
                VirlinGraph.actions.leaveFocus(id); VirlinGraph.actions.markReady(id); android.cancel(id)
            }
            VirlinGraph.actions.focusStream("s1")
        }
    }

    @Test fun persistedFutureEvents_getRealAlarms_perStream_noCollision() { runBlocking {
        // 1. human return
        VirlinGraph.actions.leaveFocus("s1", at(30))
        assertTrue(android.isScheduled("s1"))
        // 2. external check on a different stream; 3. result-ready on a third — all coexist
        VirlinGraph.actions.focusStream("s2"); VirlinGraph.actions.handOffStream("s2", checkAt = at(20))
        assertTrue(android.isScheduled("s2")); assertTrue(android.isScheduled("s1"))
        VirlinGraph.actions.focusStream("s7"); VirlinGraph.actions.handOffStream("s7", checkAt = at(1))
        VirlinGraph.actions.checkDue("s7").let { /* may be rejected: not due yet */ }
        android.schedule(AttentionSchedule("s7", ScheduleKind.EXTERNAL_RESULT_READY, at(15)))
        assertTrue(android.isScheduled("s7"))
        // 7. cancelling one leaves the others
        android.cancel("s2")
        assertFalse(android.isScheduled("s2")); assertTrue(android.isScheduled("s1")); assertTrue(android.isScheduled("s7"))
        // cleanup
        VirlinGraph.actions.focusStream("s1")
        assertFalse(android.isScheduled("s1"))                                   // resume cancelled it via the decorator
    } }

    @Test fun noCheck_andLeaveWithoutReminder_createNoAlarm() { runBlocking {
        VirlinGraph.actions.leaveFocus("s1")                                     // 9.
        assertFalse(android.isScheduled("s1")); assertEquals(WorkStreamState.READY, stream("s1").state)
        VirlinGraph.actions.focusStream("s2"); VirlinGraph.actions.handOffStream("s2")   // 8.
        assertFalse(android.isScheduled("s2")); assertNull(stream("s2").checkAt)
        VirlinGraph.actions.focusStream("s1")
    } }

    @Test fun staleTrigger_readsRoom_refuses_andReArmsCurrent() { runBlocking {
        VirlinGraph.actions.leaveFocus("s1", at(5))
        val old = stream("s1").snoozedUntil!!
        VirlinGraph.actions.deferReturn("s1", at(10))                            // old alarm replaced in AlarmManager
        android.cancel("s1")                                                     // simulate scheduler loss
        // Deliver the OLD alarm's intent straight to the receiver
        val stale = Intent(ctx, AttentionAlarmReceiver::class.java).setAction(AttentionAlarmReceiver.ACTION)
            .setData(Uri.parse("virlin://attention/s1"))
            .putExtra(AttentionAlarmReceiver.EXTRA_STREAM, "s1")
            .putExtra(AttentionAlarmReceiver.EXTRA_KIND, ScheduleKind.HUMAN_RETURN.name)
            .putExtra(AttentionAlarmReceiver.EXTRA_DUE, old.toEpochMilli())
        val v = AttentionAlarmReceiver.validate(VirlinGraph.repository, "s1", ScheduleKind.HUMAN_RETURN, old, VirlinGraph.clock.now().plus(Duration.ofMinutes(6)))
        assertTrue(v is AttentionAlarmReceiver.Verdict.Stale)
        AttentionAlarmReceiver().onReceive(ctx, stale); Thread.sleep(1500)
        assertEquals(WorkStreamState.SNOOZED, stream("s1").state)                // not surfaced
        assertEquals(SnoozeReason.HUMAN_RETURN, stream("s1").snoozeReason)
        assertTrue(android.isScheduled("s1"))                                    // current +10 re-armed by the receiver
        VirlinGraph.actions.focusStream("s1")
    } }

    @Test fun startupRescheduling_isIdempotent_andRecreatesAfterLoss() { runBlocking {
        VirlinGraph.actions.leaveFocus("s1", at(30))
        VirlinGraph.actions.focusStream("s2"); VirlinGraph.actions.handOffStream("s2", checkAt = at(20))
        android.cancel("s1"); android.cancel("s2")                               // "process died / alarms lost"
        val n1 = VirlinGraph.rescheduleAll(); val n2 = VirlinGraph.rescheduleAll()
        assertEquals(n1, n2); assertTrue(n1 >= 2)
        assertTrue(android.isScheduled("s1")); assertTrue(android.isScheduled("s2"))
        // Boot path uses the same routine
        BootRescheduleReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED)); Thread.sleep(1000)
        assertTrue(android.isScheduled("s1")); assertTrue(android.isScheduled("s2"))
        VirlinGraph.actions.focusStream("s1")
    } }

    @Test fun notificationKinds_andPermissionSafety() {
        assertEquals("Psychology is ready to continue", AttentionNotifications.text(ScheduleKind.HUMAN_RETURN, "Psychology"))
        assertEquals("Check Antigravity", AttentionNotifications.text(ScheduleKind.EXTERNAL_CHECK, "Antigravity"))
        assertEquals("Antigravity result is ready", AttentionNotifications.text(ScheduleKind.EXTERNAL_RESULT_READY, "Antigravity"))
        assertEquals("Ready to continue", AttentionNotifications.text(ScheduleKind.HUMAN_RETURN, null))
        // Posting never throws whether or not POST_NOTIFICATIONS is granted; state untouched
        val before = stream("s1")
        AttentionNotifications.post(ctx, "s1", "Psychology", ScheduleKind.HUMAN_RETURN)
        AttentionNotifications.cancel(ctx, "s1")
        assertEquals(before, stream("s1"))
    }
}

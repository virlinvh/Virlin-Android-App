package com.virlin.app.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.virlin.app.domain.schedule.AttentionSchedule
import com.virlin.app.domain.schedule.AttentionScheduler
import com.virlin.app.domain.schedule.ScheduleKind

/**
 * Production [AttentionScheduler] on AlarmManager. Holds no state: every alarm is derived
 * from a committed WorkStream and re-derivable at startup/boot.
 *
 * Identity scheme: one PendingIntent per WorkStream, distinguished by the Intent's data URI
 * `virlin://attention/<streamId>` (Intent.filterEquals compares data, so streams never collide
 * and the request code can stay constant). A stream has at most one pending attention event,
 * so scheduling replaces (FLAG_UPDATE_CURRENT) and cancelling removes that one alarm. The
 * extras carry only identity for validation (streamId, kind, dueAt) — never state.
 *
 * Timing policy: exact-and-allow-while-idle when the user has granted exact alarms
 * (SCHEDULE_EXACT_ALARM, Android 12+; never requested automatically), otherwise
 * `setAndAllowWhileIdle` — the platform may deliver it minutes late under Doze. Virlin never
 * requires exact alarms: opening the app reconciles due state regardless.
 */
class AndroidAttentionScheduler(private val context: Context) : AttentionScheduler {

    private val alarms: AlarmManager get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun schedule(schedule: AttentionSchedule) {
        val pi = pendingIntent(schedule.streamId, schedule) ?: return
        val at = schedule.dueAt.toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT >= 31 && alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            Log.d(TAG, "scheduled ${schedule.streamId} ${schedule.kind} at ${schedule.dueAt}")
        } catch (e: SecurityException) {
            // Exact permission revoked between the check and the call: fall back, never crash.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Keep the notification in step with committed state: leaving CHECK (resume, defer, still
     *  running, block, complete — from any surface) dismisses the stream's attention notification. */
    override fun sync(stream: com.virlin.app.domain.model.WorkStream) {
        super.sync(stream)
        if (stream.state != com.virlin.app.domain.model.WorkStreamState.CHECK) AttentionNotifications.cancel(context, stream.id)
    }

    override fun cancel(streamId: String) {
        pendingIntent(streamId, null, create = false)?.let { alarms.cancel(it); it.cancel(); Log.d(TAG, "cancelled $streamId") }
    }

    private fun pendingIntent(streamId: String, schedule: AttentionSchedule?, create: Boolean = true): PendingIntent? {
        val intent = Intent(context, AttentionAlarmReceiver::class.java)
            .setAction(AttentionAlarmReceiver.ACTION)
            .setData(Uri.parse("virlin://attention/$streamId"))
        schedule?.let {
            intent.putExtra(AttentionAlarmReceiver.EXTRA_STREAM, it.streamId)
            intent.putExtra(AttentionAlarmReceiver.EXTRA_KIND, it.kind.name)
            intent.putExtra(AttentionAlarmReceiver.EXTRA_DUE, it.dueAt.toEpochMilli())
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** Whether a wake-up is currently registered for the stream (test/diagnostic only — never truth). */
    fun isScheduled(streamId: String): Boolean = pendingIntent(streamId, null, create = false) != null

    companion object {
        const val TAG = "AttentionScheduler"
        private const val REQUEST_CODE = 0
        fun kindOf(name: String?): ScheduleKind? = name?.let { runCatching { ScheduleKind.valueOf(it) }.getOrNull() }
    }
}

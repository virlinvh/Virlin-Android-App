package com.virlin.app.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.virlin.app.MainActivity
import com.virlin.app.domain.schedule.ScheduleKind

/**
 * Attention notifications (Pass 6 shell, Pass 7 actions). One channel; one notification per
 * WorkStream (stable id from the stream id, so a kind change updates in place); all Virlin
 * attention items share one group so several coexist without collapsing into one. Text and
 * actions come from [AttentionNotificationModel] built from persisted domain data — never
 * MockData, never ids, never notes/context. Body tap NAVIGATES only; action buttons go to
 * [NotificationActionReceiver]. Skipped silently without POST_NOTIFICATIONS.
 */
object AttentionNotifications {
    const val CHANNEL_ID = "attention"
    const val GROUP = "com.virlin.app.ATTENTION"
    const val EXTRA_STREAM = "virlin.streamId"
    const val EXTRA_TARGET = "virlin.target"
    private const val TAG = "AttentionNotify"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Attention returns & checks", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Your own return reminders and external-process checks"
                }
            )
        }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Legacy one-liner kept for wording tests. */
    fun text(kind: ScheduleKind, title: String?): String {
        val t = title?.takeIf { it.isNotBlank() }
        return when (kind) {
            ScheduleKind.HUMAN_RETURN -> t?.let { "$it is ready to continue" } ?: "Ready to continue"
            ScheduleKind.EXTERNAL_CHECK -> t?.let { "Check $it" } ?: "An external process needs a check"
            ScheduleKind.EXTERNAL_RESULT_READY -> t?.let { "$it result is ready" } ?: "A result is ready"
        }
    }

    /** Body tap intent: opens Virlin (single top) with a navigation target — no mutation. */
    fun bodyIntent(context: Context, streamId: String, target: NotificationTarget): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("virlin://open/$streamId/${target.name}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_STREAM, streamId)
            .putExtra(EXTRA_TARGET, target.name)

    /** Action-button intent: explicit broadcast to the non-exported action receiver. */
    fun actionIntent(context: Context, model: AttentionNotificationModel, action: NotificationAction): Intent =
        Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION)
            .setData(Uri.parse("virlin://attention-action/${model.streamId}/${action.name}"))
            .putExtra(NotificationActionReceiver.EXTRA_STREAM, model.streamId)
            .putExtra(NotificationActionReceiver.EXTRA_KIND, model.kind.name)
            .putExtra(NotificationActionReceiver.EXTRA_ACTION, action.name)

    fun post(context: Context, model: AttentionNotificationModel) {
        if (!canPost(context)) { Log.d(TAG, "notification permission not granted; skipped"); return }
        ensureChannel(context)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(model.title)
            .setContentText(model.text)
            .setContentIntent(PendingIntent.getActivity(context, 0, bodyIntent(context, model.streamId, model.bodyTarget), flags))
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setGroup(GROUP)
        model.actions.forEach { action ->
            val pi = if (action.mutates)
                PendingIntent.getBroadcast(context, 0, actionIntent(context, model, action), flags)
            else
                PendingIntent.getActivity(context, 0, bodyIntent(context, model.streamId, NotificationTarget.CHECK_FLOW), flags)
            builder.addAction(Notification.Action.Builder(null, action.label, pi).build())
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId(model.streamId), builder.build())
        nm.notify(SUMMARY_ID, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Virlin attention")
            .setGroup(GROUP).setGroupSummary(true).setAutoCancel(true).build())
    }

    /** Convenience for the alarm receiver: text + actions from persisted domain state. */
    fun post(context: Context, streamId: String, title: String?, kind: ScheduleKind) {
        val model = AttentionNotificationModel.build(
            com.virlin.app.domain.model.WorkStream(id = streamId, title = title ?: "Virlin", state = com.virlin.app.domain.model.WorkStreamState.CHECK,
                createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH), null, kind
        )
        post(context, model)
    }

    fun cancel(context: Context, streamId: String) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId(streamId))
    }

    /** Stable per-stream id (masked hash; only needs to be stable and distinct per stream). */
    fun notificationId(streamId: String) = 0x5600_0000 or (streamId.hashCode() and 0x00FF_FFFF)
    const val SUMMARY_ID = 0x5600_0000
}

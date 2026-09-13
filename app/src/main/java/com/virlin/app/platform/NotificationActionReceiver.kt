package com.virlin.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.schedule.ScheduleKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Notification action buttons land here (explicit, non-exported). Works with no Activity or
 * ViewModel alive: initialize the graph → validate against Room → VirlinActions → the
 * scheduling decorator syncs alarms → dismiss the notification. Extras are identity only and
 * are validated; nothing in them is trusted as state.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val streamId = intent.getStringExtra(EXTRA_STREAM) ?: return
        val kind = AndroidAttentionScheduler.kindOf(intent.getStringExtra(EXTRA_KIND)) ?: return
        val action = intent.getStringExtra(EXTRA_ACTION)?.let { runCatching { NotificationAction.valueOf(it) }.getOrNull() } ?: return
        val pending: PendingResult? = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                VirlinGraph.init(context.applicationContext)
                val outcome = NotificationActionHandler.execute(
                    VirlinGraph.repository, VirlinGraph.actions, streamId, kind, action, VirlinGraph.clock.now()
                )
                Log.d(TAG, "$action on $streamId ($kind): $outcome")
                // Applied → resolved; Stale → the notification no longer describes reality. Either way it goes.
                if (outcome !is NotificationActionHandler.Outcome.Failed) AttentionNotifications.cancel(context, streamId)
            } catch (e: Exception) {
                Log.e(TAG, "notification action failed for $streamId", e)
            } finally { pending?.finish() }
        }
    }

    companion object {
        const val ACTION = "com.virlin.app.ATTENTION_ACTION"
        const val EXTRA_STREAM = "streamId"
        const val EXTRA_KIND = "kind"
        const val EXTRA_ACTION = "action"
        private const val TAG = "AttentionAction"
    }
}

package com.virlin.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.schedule.AttentionSchedule
import com.virlin.app.domain.schedule.ScheduleKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Wake-up trigger. Does the minimum and terminates: read Room, validate this alarm is still
 * the current one, reconcile through the same validated action the ticker and startup use
 * (`checkDue`), post the attention notification. Never mutates state directly, never opens
 * the app, never trusts its own extras over Room.
 */
class AttentionAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val streamId = intent.getStringExtra(EXTRA_STREAM) ?: return
        val kind = AndroidAttentionScheduler.kindOf(intent.getStringExtra(EXTRA_KIND)) ?: return
        val dueAt = intent.getLongExtra(EXTRA_DUE, -1L).takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: return
        val pending: PendingResult? = goAsync()   // null when invoked directly (tests)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                VirlinGraph.init(context.applicationContext)
                when (val v = validate(VirlinGraph.repository, streamId, kind, dueAt, VirlinGraph.clock.now())) {
                    is Verdict.Due -> {
                        // Idempotent: if the ticker/startup already surfaced it, checkDue is rejected
                        // (CHECK → CHECK is not a transition) and no second event is written.
                        val r = VirlinGraph.actions.checkDue(streamId)
                        Log.d(TAG, "due $streamId $kind → ${r::class.simpleName}")
                        if (r is ActionResult.Success || r is ActionResult.Rejected) {
                            val stream = VirlinGraph.repository.getStream(streamId)
                            if (stream != null) {
                                val task = stream.activeTaskId?.let { VirlinGraph.repository.getTask(it) }
                                AttentionNotifications.post(context, AttentionNotificationModel.build(stream, task, kind))
                            }
                        }
                    }
                    is Verdict.Stale -> {
                        Log.d(TAG, "stale alarm ignored for $streamId ($kind @ $dueAt): ${v.reason}")
                        // Room may hold a newer future event for this stream — make sure it is armed.
                        v.current?.let { VirlinGraph.scheduler.schedule(it) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "trigger failed for $streamId", e)
            } finally {
                pending?.finish()
            }
        }
    }

    sealed interface Verdict {
        data class Due(val title: String) : Verdict
        data class Stale(val reason: String, val current: AttentionSchedule?) : Verdict
    }

    companion object {
        const val ACTION = "com.virlin.app.ATTENTION_DUE"
        const val EXTRA_STREAM = "streamId"
        const val EXTRA_KIND = "kind"
        const val EXTRA_DUE = "dueAt"
        private const val TAG = "AttentionAlarm"
        /** Inexact delivery can land a little early; treat "almost due" as due. */
        private val EARLY_TOLERANCE: Duration = Duration.ofSeconds(30)

        /**
         * Room decides. The alarm is honoured only if the stream still carries the SAME
         * semantic event (kind + due time) and that time has arrived.
         */
        suspend fun validate(repository: com.virlin.app.domain.repository.WorkStreamRepository, streamId: String, kind: ScheduleKind, dueAt: Instant, now: Instant): Verdict {
            val stream = repository.getStream(streamId) ?: return Verdict.Stale("stream missing", null)
            val current = AttentionSchedule.of(stream) ?: return Verdict.Stale("no pending attention event (${stream.state})", null)
            if (current.kind != kind) return Verdict.Stale("kind changed to ${current.kind}", current)
            if (current.dueAt != dueAt) return Verdict.Stale("due time changed to ${current.dueAt}", current)
            if (now.plus(EARLY_TOLERANCE).isBefore(dueAt)) return Verdict.Stale("not yet due", current)
            return Verdict.Due(stream.title)
        }
    }
}

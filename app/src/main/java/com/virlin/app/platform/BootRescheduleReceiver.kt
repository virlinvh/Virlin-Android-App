package com.virlin.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.virlin.app.domain.VirlinGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AlarmManager alarms do not survive a reboot (or a package update). This receiver re-derives
 * every pending attention wake-up from Room and arms it again. No UI, no service, finishes
 * immediately. Idempotent: re-arming an existing alarm replaces it.
 */
class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_LOCKED_BOOT_COMPLETED)) return
        val pending: PendingResult? = goAsync()   // null when invoked directly (tests)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                VirlinGraph.init(context.applicationContext)
                val n = VirlinGraph.rescheduleAll()
                Log.d(TAG, "${intent.action}: re-armed $n attention wake-ups")
            } catch (e: Exception) {
                Log.e(TAG, "reschedule failed", e)
            } finally { pending?.finish() }
        }
    }
    private companion object { const val TAG = "BootReschedule" }
}

package com.virlin.app.platform

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.model.FocusClockPause
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/**
 * The Focus timer only runs while Virlin is on screen. Moving between Virlin's own screens
 * changes nothing; when the whole app leaves the foreground (Home, recents, another app, closed)
 * the live Focus session ends at that moment, and it continues as the same Focus period when the
 * app comes back. Time stays in FocusSession timestamps; this only remembers what to continue.
 *
 * A process killed without warning (crash) cannot report when it stopped: the last moment Virlin
 * was seen on screen, refreshed every [HEARTBEAT], is used instead.
 */
object FocusAutoPause : DefaultLifecycleObserver {
    private const val TAG = "FocusAutoPause"
    private const val PREFS = "virlin_focus_clock"
    private const val KEY_SESSION = "paused_session"
    private const val KEY_OWNER = "paused_owner"
    private const val KEY_TASK = "paused_task"
    private const val KEY_SEEN = "last_foreground_at"
    private val HEARTBEAT = Duration.ofSeconds(30)
    /** Shorter absences are not treated as time away when there is nothing paused to continue. */
    private val AWAY = Duration.ofSeconds(5)

    private var context: Context? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val order = Mutex()
    private var heartbeat: Job? = null

    /** The Focus session stopped while the app is away, if any: the display keeps its period. */
    @Volatile var pausedSessionId: String? = null
        private set

    fun install(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        pausedSessionId = prefs()?.getString(KEY_SESSION, null)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        scope.launch {
            order.withLock { runCatching { comeBack() }.onFailure { Log.w(TAG, "resume failed", it) } }
        }
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT.toMillis())
                prefs()?.edit()?.putLong(KEY_SEEN, VirlinGraph.clock.now().toEpochMilli())?.apply()
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        heartbeat?.cancel()
        heartbeat = null
        val at = VirlinGraph.clock.now()
        scope.launch {
            order.withLock { runCatching { goAway(at) }.onFailure { Log.w(TAG, "pause failed", it) } }
        }
    }

    private suspend fun goAway(at: Instant) {
        VirlinGraph.ensureReady()
        val pause = (VirlinGraph.actions.suspendFocusClock(at) as? ActionResult.Success)?.value
        val edit = prefs()?.edit()?.putLong(KEY_SEEN, at.toEpochMilli())
        if (pause != null) {
            edit?.putString(KEY_SESSION, pause.sessionId)?.putString(KEY_OWNER, pause.ownerId)
                ?.putString(KEY_TASK, pause.taskId)
            pausedSessionId = pause.sessionId
        }
        edit?.commit()
    }

    private suspend fun comeBack() {
        VirlinGraph.ensureReady()
        val prefs = prefs() ?: return
        val actions = VirlinGraph.actions
        val stored = prefs.getString(KEY_SESSION, null)?.let { id ->
            prefs.getString(KEY_OWNER, null)?.let { FocusClockPause(id, it, prefs.getString(KEY_TASK, null)) }
        }
        val resumed = stored?.let { (actions.resumeFocusClock(it) as? ActionResult.Success)?.value } == true
        if (!resumed) {
            // Nothing to continue, yet a session may have run on while the app was away: one
            // started from a notification, or one a killed process never stopped.
            val seen = prefs.getLong(KEY_SEEN, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)
            if (seen != null && Duration.between(seen, VirlinGraph.clock.now()) > AWAY) {
                (actions.suspendFocusClock(seen) as? ActionResult.Success)?.value
                    ?.let { actions.resumeFocusClock(it) }
            }
        }
        prefs.edit().remove(KEY_SESSION).remove(KEY_OWNER).remove(KEY_TASK)
            .putLong(KEY_SEEN, VirlinGraph.clock.now().toEpochMilli()).commit()
        pausedSessionId = null
    }

    private fun prefs() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

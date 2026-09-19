package com.virlin.app.domain.model

import java.time.Duration
import java.time.Instant

/**
 * Cumulative human focus invested in a Task, derived from persisted [FocusSession]
 * timestamps. No parallel counter — each closed session contributes exactly once;
 * an open session contributes its live [FocusSession.duration].
 *
 * [DisplayStream.focusInvestedSec] remains the **current session** only (split-flap).
 * Live total = prior closed sessions + current session elapsed.
 *
 * Malformed sessions (negative duration, missing endedAt, etc.) are skipped — never
 * throw into startup / display projection.
 */
object FocusInvestment {

    /** Sum of all FocusSessions attributed to [taskId] (closed + open). */
    fun total(sessions: List<FocusSession>, taskId: String?, now: Instant): Duration {
        if (taskId == null) return Duration.ZERO
        return sessions
            .asSequence()
            .filter { it.taskId == taskId }
            .fold(Duration.ZERO) { acc, s ->
                acc.plus(safeDuration(s, now))
            }
    }

    /**
     * Closed sessions only for [taskId]. Pair with the live current-session counter
     * (`focusInvestedSec`) for a ticking total without double-counting the open session.
     */
    fun priorClosedSeconds(sessions: List<FocusSession>, taskId: String?): Long {
        if (taskId == null) return 0L
        return sessions
            .asSequence()
            .filter { it.taskId == taskId && !it.isOpen }
            .sumOf { s ->
                val end = s.endedAt ?: return@sumOf 0L
                safeDuration(s, end).seconds.coerceAtLeast(0L)
            }
    }

    /** Live total seconds = prior closed + current open session elapsed. */
    fun liveTotalSeconds(priorClosedSec: Long, currentSessionSec: Int): Long =
        priorClosedSec.coerceAtLeast(0L) + currentSessionSec.coerceAtLeast(0).toLong()

    /**
     * Compact productivity label without "invested":
     * `<1m` · `1m` · `25m` · `59m` · `1h` · `1h 05m` · `2h 18m`
     */
    fun formatCompact(totalSeconds: Long): String {
        val sec = totalSeconds.coerceAtLeast(0L)
        if (sec < 60L) return "<1m"
        val totalMinutes = sec / 60L
        if (totalMinutes < 60L) return "${totalMinutes}m"
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (minutes == 0L) "${hours}h" else "${hours}h ${minutes.toString().padStart(2, '0')}m"
    }

    /** e.g. `25m invested`, `<1m invested`, `1h 05m invested`. */
    fun formatInvested(totalSeconds: Long): String = "${formatCompact(totalSeconds)} invested"

    private fun safeDuration(session: FocusSession, now: Instant): Duration {
        return try {
            val d = session.duration(now)
            if (d.isNegative) Duration.ZERO else d
        } catch (_: Exception) {
            Duration.ZERO
        }
    }
}

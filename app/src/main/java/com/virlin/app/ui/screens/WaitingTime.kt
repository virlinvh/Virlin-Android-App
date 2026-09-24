package com.virlin.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.time.VirlinClock
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * Needs You waiting time (Phase 1 of the attention system).
 *
 * The ONLY source of truth is a persisted domain timestamp — the moment a stream started
 * waiting for the user (see `NowPresentation.waitingSince`). Everything shown on a card
 * (the live "−mm:ss" timer, its spoken description and, later, its urgency) is a pure
 * function of `now − waitingSince`. Nothing here counts; it recomputes from the clock, so
 * backgrounding, configuration changes and process recreation cannot drift or reset it.
 */
object WaitingTime {

    /** Whole seconds a stream has been waiting; never negative (a future timestamp reads as 0). */
    fun elapsedSeconds(since: Instant, now: Instant): Long =
        Duration.between(since, now).seconds.coerceAtLeast(0)

    /**
     * Compact live timer: `00:00` at the due moment, then a NEGATIVE elapsed reading —
     * `−00:08`, `−00:59`, `−01:14`, `−08:32`, and `−1:04:00` from one hour (no zero-padded hours).
     * The sign is U+2212 MINUS SIGN, matching the design.
     */
    fun format(elapsedSeconds: Long): String {
        val s = elapsedSeconds.coerceAtLeast(0)
        if (s == 0L) return "00:00"
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val seconds = s % 60
        return if (hours > 0) "−%d:%02d:%02d".format(hours, minutes, seconds)
        else "−%02d:%02d".format(minutes, seconds)
    }

    /** Human-readable equivalent for accessibility: "Waiting for 3 minutes 42 seconds". */
    fun describe(elapsedSeconds: Long): String {
        val s = elapsedSeconds.coerceAtLeast(0)
        if (s == 0L) return "Just became due"
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val seconds = s % 60
        val parts = buildList {
            if (hours > 0) add(plural(hours, "hour"))
            if (minutes > 0) add(plural(minutes, "minute"))
            if (seconds > 0 || isEmpty()) add(plural(seconds, "second"))
        }
        return "Waiting for " + parts.joinToString(" ")
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"

    /**
     * Needs You order rule: the item waiting LONGEST first (earliest `since`); items with no
     * timestamp last; ties keep their incoming order (stable). Depends only on persisted
     * timestamps — never on the current time — so a tick can never re-sort the list.
     */
    fun <T> orderLongestWaitingFirst(items: List<T>, since: (T) -> Instant?): List<T> =
        items.sortedBy { since(it) ?: Instant.MAX }
}

/**
 * ONE shared per-second time source for a section of live timers (not one coroutine per card).
 *
 * - Value is `clock.now()` re-read on every tick — elapsed time is derived, never incremented.
 * - Ticks are aligned to wall-clock second boundaries, so there is no accumulated drift.
 * - Lifecycle-aware: runs only while the host is RESUMED; on resume it immediately re-reads
 *   the clock, so time spent in the background is reflected correctly.
 * - Only composables that READ the returned state recompose each second.
 */
@Composable
fun rememberSecondTicker(clock: VirlinClock = VirlinGraph.clock): State<Instant> {
    val lifecycleOwner = LocalLifecycleOwner.current
    return produceState(initialValue = clock.now(), lifecycleOwner, clock) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = clock.now()
                value = now
                delay(1_000L - (now.toEpochMilli() % 1_000L))
            }
        }
    }
}

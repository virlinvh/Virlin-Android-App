package com.virlin.app.mock

import com.virlin.app.domain.VirlinGraph
import com.virlin.app.model.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DEMO display ticker. Advances the per-second counters the frozen Now UI animates
 * (`focusInvestedSec`, `processingElapsedSec`, `checkInRemainingSec`, `isOverdue`).
 *
 * It no longer changes any stream's STATE itself. When a PROCESSING countdown reaches zero
 * it asks the Action Layer for the `checkDue` transition — acting as a stand-in for the
 * future scheduler — and the domain decides. The domain's own time model is timestamp
 * based (FocusSession / checkAt); these counters are display-only.
 */
object MockTimerEngine {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(1000)
                tick()
            }
        }
    }

    private suspend fun tick() {
        val dueForCheck = mutableListOf<String>()
        MockData.mutate { list ->
            list.map { stream ->
                when (stream.state) {
                    StreamState.FOCUS -> stream.copy(focusInvestedSec = stream.focusInvestedSec + 1)
                    StreamState.PROCESSING -> {
                        val remaining = stream.checkInRemainingSec?.minus(1)
                        if (remaining != null && remaining <= 0) dueForCheck += stream.id
                        stream.copy(
                            processingElapsedSec = stream.processingElapsedSec + 1,
                            checkInRemainingSec = remaining
                        )
                    }
                    // In-app return ticking (stand-in for durable scheduling, see docs).
                    StreamState.SNOOZED -> {
                        val remaining = stream.checkInRemainingSec?.minus(1)
                        if (remaining != null && remaining <= 0) dueForCheck += stream.id
                        stream.copy(checkInRemainingSec = remaining)
                    }
                    StreamState.NEEDS_YOU -> {
                        val remaining = stream.checkInRemainingSec
                        if (remaining != null && remaining <= 0) {
                            stream.copy(checkInRemainingSec = remaining - 1, isOverdue = true)
                        } else stream
                    }
                    else -> stream
                }
            }
        }
        // Scheduling stand-in: the domain performs (and validates) the actual transition.
        dueForCheck.forEach { VirlinGraph.actions.checkDue(it) }
    }
}

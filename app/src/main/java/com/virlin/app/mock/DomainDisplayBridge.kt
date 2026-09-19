package com.virlin.app.mock

import com.virlin.app.debug.VirlinStartup
import com.virlin.app.domain.model.FocusInvestment
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import com.virlin.app.model.StreamState
import com.virlin.app.model.WorkStream as DisplayStream
import com.virlin.app.domain.model.WorkStream as DomainStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * ONE-WAY projection: domain repository → MockData display list.
 *
 * Source of truth for a stream's STATE and context is the domain repository. `MockData`
 * keeps only DEMO DISPLAY DATA — titles, subtitles and the per-second display counters the
 * frozen Now UI animates (`focusInvestedSec`, `processingElapsedSec`, `checkInRemainingSec`).
 * The bridge never reads state back from MockData, so there is a single mutable owner.
 *
 * [DisplayStream.focusInvestedSec] = current open [FocusSession] elapsed (split-flap).
 * [DisplayStream.priorFocusInvestedSec] = closed sessions for the active task (cumulative base).
 * Live total invested = prior + current session; never pass the total into SplitFlapTimer.
 */
object DomainDisplayBridge {

    private var job: Job? = null

    fun start(scope: CoroutineScope, repository: WorkStreamRepository, clock: VirlinClock) {
        if (job?.isActive == true) return
        job = scope.launch {
            repository.streams.collect { domainStreams ->
                val byId = domainStreams.associateBy { it.id }
                // Focus time is derived from the persisted open FocusSession, never from a
                // stored counter — so it is right again after a process restart.
                val focusStreams = domainStreams.filter { it.state == WorkStreamState.FOCUS }
                // Fail soft: a bad FocusSession row must never kill display projection.
                val openSessions = focusStreams.associate { s ->
                    s.id to runCatching { repository.getOpenFocusSession(s.id) }.getOrNull()
                }
                val priorByStream = focusStreams.associate { s ->
                    s.id to runCatching {
                        val tAgg = android.os.SystemClock.elapsedRealtime()
                        val sessions = repository.getFocusSessions(s.id)
                        val prior = FocusInvestment.priorClosedSeconds(sessions, s.activeTaskId)
                            .coerceIn(0L, Int.MAX_VALUE.toLong())
                            .toInt()
                        val dt = android.os.SystemClock.elapsedRealtime() - tAgg
                        VirlinStartup.mark(
                            "FocusInvestment_AGG",
                            "stream=${s.id} sessionRows=${sessions.size} durationMs=$dt"
                        )
                        prior
                    }.getOrDefault(0)
                }
                MockData.mutate { display ->
                    val known = display.map { it.id }.toHashSet()
                    // Streams created through VirlinActions after the seed get a display row so
                    // Now/Streams (still MockData-driven) show them at once. Titles are the persisted ones.
                    val added = domainStreams.filter { it.id !in known }.map { s ->
                        DisplayStream(
                            id = s.id,
                            title = s.title,
                            subtitle = s.nextHumanAction ?: "",
                            projectId = s.projectId,
                            state = toDisplayState(s.state),
                            nextAction = s.nextHumanAction
                        )
                    }
                    (display + added).map { d ->
                        byId[d.id]?.let {
                            project(d, it, clock.now(), openSessions[d.id], priorByStream[d.id] ?: 0)
                        } ?: d
                    }
                }
                VirlinStartup.markOnceMockEmit(
                    "domainStreams=${domainStreams.size} focus=${focusStreams.size}"
                )
            }
        }
    }

    /** Test-only: stop collectors so a fresh [start] can run after graph reset. */
    internal fun stopForTests() {
        job?.cancel()
        job = null
    }

    /** Seed the domain from the initial mock list — once, at start-up. */
    fun seedFromDisplay(display: List<DisplayStream>, now: Instant): List<DomainStream> = display
        .filter { it.id.isNotBlank() && it.id != "s" } // MockData pads the list with placeholder rows
        .map { d ->
            DomainStream(
                id = d.id,
                title = d.title,
                projectId = d.projectId,
                tool = d.title.takeIf { it.contains(" · ") }?.substringBefore(" · "),
                // Demo seed only: streams that name an external tool are EXTERNAL. Real data will
                // carry the preference explicitly; the UI resolves via ExecutionModeResolver.
                executionPreference = if (d.title.contains(" · ") || d.state == StreamState.PROCESSING || d.state == StreamState.NEEDS_YOU)
                    com.virlin.app.domain.model.ExecutionPreference.EXTERNAL else com.virlin.app.domain.model.ExecutionPreference.HUMAN,
                state = toDomainState(d.state),
                nextHumanAction = d.nextAction,
                blockerReason = d.blockerReason,
                processingStartedAt = if (d.state == StreamState.PROCESSING) now.minusSeconds(d.processingElapsedSec.toLong()) else null,
                checkAt = d.checkInRemainingSec?.let { now.plusSeconds(it.toLong()) },
                createdAt = now,
                updatedAt = now
            )
        }

    private fun project(
        display: DisplayStream,
        domain: DomainStream,
        now: Instant,
        openSession: FocusSession? = null,
        priorFocusInvestedSec: Int = 0
    ): DisplayStream {
        val newState = toDisplayState(domain.state)
        val checkIn = domain.checkAt?.let { Duration.between(now, it).seconds.toInt() }
        val focusSec = openSession?.let { Duration.between(it.startedAt, now).seconds.toInt().coerceAtLeast(0) }
        val processingSec = domain.processingStartedAt?.let { Duration.between(it, now).seconds.toInt().coerceAtLeast(0) }
        return display.copy(
            state = newState,
            nextAction = domain.nextHumanAction ?: display.nextAction,
            blockerReason = domain.blockerReason,
            checkInRemainingSec = when (domain.state) {
                WorkStreamState.PROCESSING, WorkStreamState.SNOOZED, WorkStreamState.CHECK -> checkIn ?: display.checkInRemainingSec
                else -> null
            },
            processingElapsedSec = processingSec ?: display.processingElapsedSec,
            // Open FocusSession is authoritative for the current session. Otherwise keep the
            // display counter (MockTimerEngine). Seed starts at 0 so first paint is not a fake 32:35.
            focusInvestedSec = focusSec ?: display.focusInvestedSec,
            priorFocusInvestedSec = if (domain.state == WorkStreamState.FOCUS) priorFocusInvestedSec else 0,
            isOverdue = if (newState == StreamState.PROCESSING) false else display.isOverdue
        )
    }

    fun toDisplayState(s: WorkStreamState): StreamState = when (s) {
        WorkStreamState.FOCUS -> StreamState.FOCUS
        WorkStreamState.PROCESSING -> StreamState.PROCESSING
        WorkStreamState.CHECK -> StreamState.NEEDS_YOU
        WorkStreamState.READY -> StreamState.READY
        WorkStreamState.SNOOZED -> StreamState.SNOOZED
        WorkStreamState.BLOCKED -> StreamState.BLOCKED
        WorkStreamState.PAUSED -> StreamState.PAUSED
        WorkStreamState.DONE -> StreamState.DONE
    }

    fun toDomainState(s: StreamState): WorkStreamState = when (s) {
        StreamState.FOCUS -> WorkStreamState.FOCUS
        StreamState.PROCESSING -> WorkStreamState.PROCESSING
        StreamState.NEEDS_YOU -> WorkStreamState.CHECK
        StreamState.READY -> WorkStreamState.READY
        StreamState.SNOOZED -> WorkStreamState.SNOOZED
        StreamState.BLOCKED -> WorkStreamState.BLOCKED
        StreamState.PAUSED -> WorkStreamState.PAUSED
        StreamState.DONE -> WorkStreamState.DONE
    }
}

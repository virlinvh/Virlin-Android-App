package com.virlin.app.ui.pulse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.model.Cycle
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.ExternalRunInterval
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.WorkStreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * PULSE reads what the app already records and nothing else:
 *
 * - a FOCUS interval is a [FocusSession] — real human attention, from its own timestamps,
 * - an EXTERNAL interval is a [Cycle]'s hand-off window: from `handedOffAt` until the cycle
 *   ended, which is when an external process was actually running,
 * - a session or cycle still open has no end yet and is measured up to now, not to the end of
 *   the period being viewed.
 *
 * Nothing is inferred from a 24-hour day, and untracked time is never presented as lost.
 */
class PulseViewModel(
    private val repository: WorkStreamRepository = VirlinGraph.repository
) : ViewModel() {

    private val _input = MutableStateFlow(PulseInput(emptyList(), emptyList(), emptyList(), emptyList()))
    val input: StateFlow<PulseInput> = _input.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        // Re-read whenever the records the report is built from change.
        viewModelScope.launch {
            val refresh = flow { while (true) { emit(Unit); delay(15_000L) } }
            combine(repository.streams, repository.projects, repository.tasks, repository.captures, refresh) { s, p, t, c, _ ->
                PulseSources(s, p, t, c)
            }
                .stateIn(viewModelScope, SharingStarted.Eagerly,
                    PulseSources(repository.streams.value, repository.projects.value,
                        repository.tasks.value, repository.captures.value))
                .collect { (streams, projects, tasks, captures) ->
                    // Sessions and cycles are read and folded off the main thread: a long
                    // history is a lot of rows, and this runs on every change.
                    _input.value = withContext(Dispatchers.IO) {
                        build(repository.allFocusSessions(), repository.allCycles(),
                            repository.externalRunIntervals(), streams, projects, tasks, captures)
                    }
                    _loading.value = false
                }
        }
    }

    private fun build(
        sessions: List<FocusSession>,
        cycles: List<Cycle>,
        runs: List<ExternalRunInterval>,
        streams: List<WorkStream>,
        projects: List<com.virlin.app.domain.model.Project>,
        tasks: List<Task>,
        captures: List<com.virlin.app.domain.model.CaptureItem>
    ): PulseInput {
        val streamById = streams.associateBy { it.id }
        val taskById = tasks.associateBy { it.id }
        val intervals = ArrayList<PulseInterval>(sessions.size + cycles.size + runs.size)

        sessions.forEach { session ->
            val stream = streamById[session.workStreamId]
            intervals += PulseInterval(
                id = "focus:" + session.id,
                start = session.startedAt,
                end = session.endedAt ?: session.startedAt,
                kind = PulseKind.FOCUS,
                projectId = stream?.projectId,
                workstreamId = session.workStreamId,
                taskId = session.taskId,
                open = session.endedAt == null
            )
        }

        cycles.forEach { cycle ->
            // A cycle only becomes external work once the human hands it off.
            val handedOff = cycle.handedOffAt ?: return@forEach
            val stream = streamById[cycle.workStreamId]
            val end = cycle.endedAt
            intervals += PulseInterval(
                id = "external:" + cycle.id,
                start = handedOff,
                end = end ?: handedOff,
                kind = PulseKind.EXTERNAL,
                projectId = stream?.projectId,
                workstreamId = cycle.workStreamId,
                // The external run belongs to whatever the stream was working on.
                taskId = stream?.activeTaskId,
                open = end == null
            )
        }

        // New execution timers use persisted external-run history as well as live processing
        // state. Union arithmetic in calculatePulse prevents a legacy Cycle covering the same
        // hand-off from being counted twice.
        fun addRun(run: ExternalRunInterval, id: String) {
            if (run.end <= run.start) return
            val task = taskById[run.taskId ?: run.ownerId]
            val stream = streamById[run.ownerId] ?: task?.workStreamId?.let(streamById::get)
            intervals += PulseInterval(id, run.start, run.end, PulseKind.EXTERNAL,
                projectId = stream?.projectId ?: task?.projectId,
                workstreamId = stream?.id, taskId = run.taskId ?: task?.id)
        }
        runs.forEach { run -> addRun(run, "run:${run.ownerId}:${run.start.toEpochMilli()}") }
        val now = java.time.Instant.now()
        streams.forEach { stream ->
            val start = stream.processingStartedAt
            if (stream.state == WorkStreamState.PROCESSING && !stream.externalTrackingPaused && start != null) {
                addRun(ExternalRunInterval(stream.id, stream.activeTaskId, start,
                    minOf(now, stream.checkAt ?: now)), "live-stream:${stream.id}")
            }
        }
        tasks.forEach { task ->
            val execution = task.execution
            val start = execution?.processingStartedAt
            if (execution?.state == WorkStreamState.PROCESSING && !execution.externalTrackingPaused && start != null) {
                addRun(ExternalRunInterval(task.id, task.id, start,
                    minOf(now, execution.checkAt ?: now)), "live-task:${task.id}")
            }
        }

        return PulseInput(
            intervals = intervals,
            projects = projects.map { PulseProject(it.id, it.title) },
            workstreams = streams.mapNotNull { stream ->
                stream.projectId?.let { PulseWorkstream(stream.id, it, stream.title) }
            },
            tasks = tasks.map { task ->
                PulseTask(
                    id = task.id,
                    title = task.title,
                    workstreamId = task.workStreamId,
                    completedAt = task.completedAt,
                    projectId = task.projectId
                )
            },
            captures = captures.map { PulseCapture(it.id, it.createdAt, it.projectId) }
        )
    }
}

private data class PulseSources(
    val streams: List<WorkStream>,
    val projects: List<com.virlin.app.domain.model.Project>,
    val tasks: List<Task>,
    val captures: List<com.virlin.app.domain.model.CaptureItem>
)

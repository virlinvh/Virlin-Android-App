package com.virlin.app.ui.pulse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.model.Cycle
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.repository.WorkStreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
            combine(repository.streams, repository.projects, repository.tasks) { s, p, t -> Triple(s, p, t) }
                .stateIn(viewModelScope, SharingStarted.Eagerly,
                    Triple(repository.streams.value, repository.projects.value, repository.tasks.value))
                .collect { (streams, projects, tasks) ->
                    _loading.value = true
                    // Sessions and cycles are read and folded off the main thread: a long
                    // history is a lot of rows, and this runs on every change.
                    _input.value = withContext(Dispatchers.IO) {
                        build(repository.allFocusSessions(), repository.allCycles(), streams, projects, tasks)
                    }
                    _loading.value = false
                }
        }
    }

    private fun build(
        sessions: List<FocusSession>,
        cycles: List<Cycle>,
        streams: List<WorkStream>,
        projects: List<com.virlin.app.domain.model.Project>,
        tasks: List<Task>
    ): PulseInput {
        val streamById = streams.associateBy { it.id }
        val intervals = ArrayList<PulseInterval>(sessions.size + cycles.size)

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
            }
        )
    }
}

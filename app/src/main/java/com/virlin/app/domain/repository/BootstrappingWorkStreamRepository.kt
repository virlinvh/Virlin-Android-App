package com.virlin.app.domain.repository

import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.ContextSnapshot
import com.virlin.app.domain.model.Cycle
import com.virlin.app.domain.model.ExternalStage
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stable [WorkStreamRepository] façade for cold start: StateFlows are always safe to collect
 * (empty until [bind]). Writes and suspend reads require a bound inner repository — callers
 * that need persisted truth must [com.virlin.app.domain.VirlinGraph.ensureReady] first.
 */
class BootstrappingWorkStreamRepository : WorkStreamRepository {

    private val bindLock = Mutex()
    @Volatile private var inner: WorkStreamRepository? = null
    private var forwardJobs: List<Job> = emptyList()

    private val _streams = MutableStateFlow<List<WorkStream>>(emptyList())
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    private val _captures = MutableStateFlow<List<CaptureItem>>(emptyList())
    private val _stages = MutableStateFlow<List<ExternalStage>>(emptyList())

    override val stages: StateFlow<List<ExternalStage>> = _stages.asStateFlow()
    override suspend fun getStages(workStreamId: String) = inner?.getStages(workStreamId) ?: emptyList()

    override val streams: StateFlow<List<WorkStream>> = _streams.asStateFlow()
    override val projects: StateFlow<List<Project>> = _projects.asStateFlow()
    override val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()
    override val captures: StateFlow<List<CaptureItem>> = _captures.asStateFlow()

    val isBound: Boolean get() = inner != null

    /**
     * Attach the hydrated Room/in-memory repository once. Forwards future StateFlow updates
     * into the stable outer flows so existing collectors keep working.
     */
    suspend fun bind(ready: WorkStreamRepository, scope: CoroutineScope) {
        bindLock.withLock {
            if (inner != null) return
            inner = ready
            _streams.value = ready.streams.value
            _projects.value = ready.projects.value
            _tasks.value = ready.tasks.value
            _captures.value = ready.captures.value
            _stages.value = ready.stages.value
            forwardJobs = listOf(
                scope.launch { ready.streams.collect { _streams.value = it } },
                scope.launch { ready.projects.collect { _projects.value = it } },
                scope.launch { ready.tasks.collect { _tasks.value = it } },
                scope.launch { ready.captures.collect { _captures.value = it } },
                scope.launch { ready.stages.collect { _stages.value = it } },
            )
        }
    }

    /** Test-only: clear bind so the next [bind] can attach a fresh repository. */
    internal suspend fun resetForTests() {
        bindLock.withLock {
            forwardJobs.forEach { it.cancel() }
            forwardJobs = emptyList()
            inner = null
            _streams.value = emptyList()
            _projects.value = emptyList()
            _tasks.value = emptyList()
            _captures.value = emptyList()
            _stages.value = emptyList()
        }
    }

    private fun requireInner(): WorkStreamRepository =
        inner ?: error("Repository not ready — call VirlinGraph.ensureReady() first")

    override suspend fun getCapture(id: String) = inner?.getCapture(id)
    override suspend fun getNoteByCaptureId(captureItemId: String) = inner?.getNoteByCaptureId(captureItemId)
    override suspend fun getNoteDocument(id: String) = inner?.getNoteDocument(id)
    override suspend fun getPromptByCaptureId(captureItemId: String) = inner?.getPromptByCaptureId(captureItemId)
    override suspend fun getPromptDocument(id: String) = inner?.getPromptDocument(id)
    override suspend fun getAttachmentByCaptureId(captureItemId: String) = inner?.getAttachmentByCaptureId(captureItemId)
    override suspend fun getAttachmentDocument(id: String) = inner?.getAttachmentDocument(id)
    override suspend fun getVoiceByCaptureId(captureItemId: String) = inner?.getVoiceByCaptureId(captureItemId)
    override suspend fun getVoiceDocument(id: String) = inner?.getVoiceDocument(id)

    override suspend fun getStream(id: String) = inner?.getStream(id)
    override suspend fun getActiveFocus() = inner?.getActiveFocus()
    override suspend fun getOpenFocusSession(streamId: String) = inner?.getOpenFocusSession(streamId)
    override suspend fun getCurrentCycle(streamId: String) = inner?.getCurrentCycle(streamId)
    override suspend fun getLatestSnapshot(streamId: String) = inner?.getLatestSnapshot(streamId)
    override suspend fun getEvents(streamId: String) = inner?.getEvents(streamId) ?: emptyList()
    override suspend fun getFocusSessions(streamId: String) = inner?.getFocusSessions(streamId) ?: emptyList()
    override suspend fun getCycles(streamId: String) = inner?.getCycles(streamId) ?: emptyList()

    override suspend fun getProject(id: String) = inner?.getProject(id)
    override suspend fun getTask(id: String) = inner?.getTask(id)
    override suspend fun getStreamsByProject(projectId: String) = inner?.getStreamsByProject(projectId) ?: emptyList()
    override suspend fun getTasksByProject(projectId: String) = inner?.getTasksByProject(projectId) ?: emptyList()
    override suspend fun getTasksByWorkStream(workStreamId: String) = inner?.getTasksByWorkStream(workStreamId) ?: emptyList()
    override suspend fun getChildren(taskId: String) = inner?.getChildren(taskId) ?: emptyList()
    override suspend fun getAncestry(taskId: String) = inner?.getAncestry(taskId)

    override suspend fun <T> transaction(block: suspend WorkStreamWriter.() -> T): T =
        requireInner().transaction(block)
}

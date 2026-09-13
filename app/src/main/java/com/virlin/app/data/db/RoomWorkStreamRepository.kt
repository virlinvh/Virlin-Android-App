package com.virlin.app.data.db

import androidx.room.withTransaction
import com.virlin.app.data.db.VirlinMappers.toDomain
import com.virlin.app.data.db.VirlinMappers.toEntity
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.ContextSnapshot
import com.virlin.app.domain.model.Cycle
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskHierarchy
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamEvent
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Room-backed [WorkStreamRepository]. Same contract as the in-memory repository, so no action
 * changes: every write happens inside ONE Room transaction (`withTransaction`) serialized by a
 * [Mutex]; if the block throws, Room rolls back and nothing is published.
 *
 * Observability is publish-on-commit: the repository is the only writer, so after a
 * successful transaction it re-reads the affected tables and updates its StateFlows. No
 * polling, no Room `Flow` needed, and observers never see a half-applied hand-off.
 *
 * Nothing here decides product behaviour. Nothing here writes ticks: durations are derived
 * from the persisted timestamps by whoever displays them.
 */
class RoomWorkStreamRepository(private val db: VirlinDatabase) : WorkStreamRepository {

    private val lock = Mutex()

    private val _streams = MutableStateFlow(runBlocking { db.workStreams().all().map { it.toDomain() } })
    private val _projects = MutableStateFlow(runBlocking { db.projects().all().map { it.toDomain() } })
    private val _tasks = MutableStateFlow(runBlocking { db.tasks().all().map { it.toDomain() } })
    override val streams: StateFlow<List<WorkStream>> = _streams.asStateFlow()
    override val projects: StateFlow<List<Project>> = _projects.asStateFlow()
    override val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()
    private val _captures = MutableStateFlow(runBlocking { db.captures().all().map { it.toDomain() } })
    override val captures: StateFlow<List<CaptureItem>> = _captures.asStateFlow()
    override suspend fun getCapture(id: String) = db.captures().byId(id)?.toDomain()

    override suspend fun getStream(id: String) = db.workStreams().byId(id)?.toDomain()
    override suspend fun getActiveFocus() = db.workStreams().activeFocus()?.toDomain()
    override suspend fun getOpenFocusSession(streamId: String) = db.focusSessions().open(streamId)?.toDomain()
    override suspend fun getCurrentCycle(streamId: String) = db.cycles().current(streamId)?.toDomain()
    override suspend fun getLatestSnapshot(streamId: String) = db.snapshots().latest(streamId)?.toDomain()
    override suspend fun getEvents(streamId: String) = db.events().byWorkStream(streamId).map { it.toDomain() }
    override suspend fun getFocusSessions(streamId: String) = db.focusSessions().byWorkStream(streamId).map { it.toDomain() }
    override suspend fun getCycles(streamId: String) = db.cycles().byWorkStream(streamId).map { it.toDomain() }

    override suspend fun getProject(id: String) = db.projects().byId(id)?.toDomain()
    override suspend fun getTask(id: String) = db.tasks().byId(id)?.toDomain()
    override suspend fun getStreamsByProject(projectId: String) = db.workStreams().byProject(projectId).map { it.toDomain() }
    override suspend fun getTasksByProject(projectId: String) = db.tasks().byProject(projectId).map { it.toDomain() }
    override suspend fun getTasksByWorkStream(workStreamId: String) = db.tasks().byWorkStream(workStreamId).map { it.toDomain() }
    override suspend fun getChildren(taskId: String) = db.tasks().children(taskId).map { it.toDomain() }
    override suspend fun getAncestry(taskId: String): List<Task>? =
        TaskHierarchy.ancestry(db.tasks().all().map { it.toDomain() }, taskId)

    /** Streams whose check/return time has passed (startup reconciliation query). */
    suspend fun dueBy(now: java.time.Instant): List<WorkStream> = db.workStreams().dueBy(now.toEpochMilli()).map { it.toDomain() }

    override suspend fun <T> transaction(block: suspend WorkStreamWriter.() -> T): T = lock.withLock {
        val writer = Writer()
        val result = db.withTransaction { writer.block() }   // throws → rolled back, nothing published
        // Commit succeeded: publish. Re-read only what the block touched.
        if (writer.touchedStreams) _streams.value = db.workStreams().all().map { it.toDomain() }
        if (writer.touchedProjects) _projects.value = db.projects().all().map { it.toDomain() }
        if (writer.touchedTasks) _tasks.value = db.tasks().all().map { it.toDomain() }
        if (writer.touchedCaptures) _captures.value = db.captures().all().map { it.toDomain() }
        result
    }

    /** Reads inside the transaction see the transaction's own writes — Room guarantees that. */
    private inner class Writer : WorkStreamWriter {
        var touchedStreams = false; var touchedProjects = false; var touchedTasks = false; var touchedCaptures = false

        override suspend fun getCapture(id: String) = db.captures().byId(id)?.toDomain()
        override suspend fun saveCapture(capture: CaptureItem) { db.captures().upsert(capture.toEntity()); touchedCaptures = true }

        override suspend fun getStream(id: String) = db.workStreams().byId(id)?.toDomain()
        override suspend fun getActiveFocus() = db.workStreams().activeFocus()?.toDomain()
        override suspend fun getOpenFocusSession(streamId: String) = db.focusSessions().open(streamId)?.toDomain()
        override suspend fun getCurrentCycle(streamId: String) = db.cycles().current(streamId)?.toDomain()
        override suspend fun getLatestSnapshot(streamId: String) = db.snapshots().latest(streamId)?.toDomain()
        override suspend fun getProject(id: String) = db.projects().byId(id)?.toDomain()
        override suspend fun getTask(id: String) = db.tasks().byId(id)?.toDomain()
        override suspend fun allTasks() = db.tasks().all().map { it.toDomain() }

        override suspend fun saveProject(project: Project) { db.projects().upsert(project.toEntity()); touchedProjects = true }
        override suspend fun saveTask(task: Task) { db.tasks().upsert(task.toEntity()); touchedTasks = true }
        override suspend fun saveStream(stream: WorkStream) { db.workStreams().upsert(stream.toEntity()); touchedStreams = true }
        override suspend fun saveCycle(cycle: Cycle) {
            val seq = db.cycles().byId(cycle.id)?.seq ?: (db.cycles().maxSeq() + 1)
            db.cycles().upsert(cycle.toEntity(seq))
        }
        override suspend fun saveFocusSession(session: FocusSession) {
            val seq = db.focusSessions().byId(session.id)?.seq ?: (db.focusSessions().maxSeq() + 1)
            db.focusSessions().upsert(session.toEntity(seq))
        }
        override suspend fun saveSnapshot(snapshot: ContextSnapshot) { db.snapshots().insert(snapshot.toEntity(db.snapshots().maxSeq() + 1)) }
        override suspend fun appendEvent(event: WorkStreamEvent) { db.events().insert(event.toEntity(db.events().maxSeq() + 1)) }
    }
}

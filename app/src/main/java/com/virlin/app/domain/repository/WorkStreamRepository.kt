package com.virlin.app.domain.repository

import com.virlin.app.domain.model.ContextSnapshot
import com.virlin.app.domain.model.Cycle
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamEvent
import kotlinx.coroutines.flow.StateFlow

/**
 * The persistence boundary the Action Layer uses. One cohesive repository (rather than five)
 * because a hand-off writes a stream, a cycle, a session, a snapshot and an event together
 * and must do so atomically.
 *
 * All writes happen inside [transaction], which yields a [WorkStreamWriter]. An in-memory
 * implementation backs V1; a Room implementation can honour the same contract with
 * `withTransaction` later without changing any action.
 */
interface WorkStreamRepository : CaptureRepository {
    /** Observable current state of every stream. UI observes this; it never writes to it. */
    val streams: StateFlow<List<WorkStream>>

    suspend fun getStream(id: String): WorkStream?
    suspend fun getActiveFocus(): WorkStream?
    suspend fun getOpenFocusSession(streamId: String): FocusSession?
    suspend fun getCurrentCycle(streamId: String): Cycle?
    suspend fun getLatestSnapshot(streamId: String): ContextSnapshot?
    suspend fun getEvents(streamId: String): List<WorkStreamEvent>
    suspend fun getFocusSessions(streamId: String): List<FocusSession>
    suspend fun getCycles(streamId: String): List<Cycle>

    // ---- Structure (Project / Task). Same boundary so hierarchy writes are atomic with streams.
    val projects: StateFlow<List<Project>>
    val tasks: StateFlow<List<Task>>
    suspend fun getProject(id: String): Project?
    suspend fun getTask(id: String): Task?
    suspend fun getStreamsByProject(projectId: String): List<WorkStream>
    suspend fun getTasksByProject(projectId: String): List<Task>
    suspend fun getTasksByWorkStream(workStreamId: String): List<Task>
    suspend fun getChildren(taskId: String): List<Task>
    /** [taskId] first, then parents up to the top. Null if broken. */
    suspend fun getAncestry(taskId: String): List<Task>?

    /** Atomic unit of work. Either every write in [block] is published, or none is. */
    suspend fun <T> transaction(block: suspend WorkStreamWriter.() -> T): T
}

/** Write surface available only inside a [WorkStreamRepository.transaction]. */
interface WorkStreamWriter : CaptureWriter {
    suspend fun getStream(id: String): WorkStream?
    suspend fun getActiveFocus(): WorkStream?
    suspend fun getOpenFocusSession(streamId: String): FocusSession?
    suspend fun getCurrentCycle(streamId: String): Cycle?
    suspend fun getLatestSnapshot(streamId: String): ContextSnapshot?
    suspend fun getProject(id: String): Project?
    suspend fun getTask(id: String): Task?
    /** Consistent view of every task including staged writes (hierarchy validation). */
    suspend fun allTasks(): List<Task>
    /** Consistent view of every WorkStream including staged writes. */
    suspend fun allStreams(): List<WorkStream>

    suspend fun saveProject(project: Project)
    suspend fun saveTask(task: Task)
    suspend fun saveStream(stream: WorkStream)
    suspend fun saveCycle(cycle: Cycle)
    suspend fun saveFocusSession(session: FocusSession)
    suspend fun saveSnapshot(snapshot: ContextSnapshot)
    suspend fun appendEvent(event: WorkStreamEvent)
}

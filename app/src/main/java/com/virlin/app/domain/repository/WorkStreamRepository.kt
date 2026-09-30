package com.virlin.app.domain.repository

import com.virlin.app.domain.model.ContextSnapshot
import com.virlin.app.domain.model.Cycle
import com.virlin.app.domain.model.ExternalStage
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.ProjectTag
import com.virlin.app.domain.model.TagLink
import com.virlin.app.domain.model.TagTargetType
import com.virlin.app.domain.model.TaskStep
import com.virlin.app.domain.model.TaskPageBlock
import com.virlin.app.domain.notedoc.VirlinNoteDoc
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

    /**
     * One batched, paginated read of the append-only history of several streams at once —
     * newest first. Project Activity needs every stream of a project in ONE query; it must
     * never fan out into a query per row.
     */
    suspend fun getEventsForStreams(
        streamIds: List<String>,
        limit: Int,
        offset: Int = 0
    ): List<WorkStreamEvent>
    suspend fun getFocusSessions(streamId: String): List<FocusSession>
    /** Every recorded focus session. Pulse clips these to the period it is showing. */
    suspend fun allFocusSessions(): List<FocusSession>
    /** Every recorded cycle. A cycle's hand-off window is the external processing interval. */
    suspend fun allCycles(): List<Cycle>
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

    // ---- External work stages (Phase 10). Tracking metadata for an external run; never Tasks.
    val stages: StateFlow<List<ExternalStage>>
    suspend fun getStages(workStreamId: String): List<ExternalStage>

    // ---- Tags and task steps (schema v13). Observed like everything else the UI reads.
    val tags: StateFlow<List<ProjectTag>>
    val tagLinks: StateFlow<List<TagLink>>
    val taskSteps: StateFlow<List<TaskStep>>
    val taskPageBlocks: StateFlow<List<TaskPageBlock>>
    suspend fun getTag(id: String): ProjectTag?
    suspend fun getTaskSteps(taskId: String): List<TaskStep>
    suspend fun getTaskPageBlocks(taskId: String): List<TaskPageBlock>

    /**
     * The Notes page's own document, filed under an opaque [ownerKey].
     *
     * Self-contained on purpose: this is not a capture note, not a [NoteDocument] and not the
     * legacy plain `tasks.notes` string. Read-on-open only, so there is no StateFlow for it.
     */
    suspend fun getNoteDoc(ownerKey: String): VirlinNoteDoc?

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
    /** Consistent view of one stream's stages including staged writes. */
    suspend fun stagesOf(workStreamId: String): List<ExternalStage>
    suspend fun saveStage(stage: ExternalStage)
    suspend fun appendEvent(event: WorkStreamEvent)

    // ---- Tags and task steps, inside the same transaction as everything else.
    suspend fun allTags(): List<ProjectTag>
    suspend fun getTag(id: String): ProjectTag?
    suspend fun saveTag(tag: ProjectTag)
    suspend fun deleteTag(id: String)
    suspend fun linksOf(targetType: TagTargetType, targetId: String): List<TagLink>
    suspend fun allLinks(): List<TagLink>
    suspend fun saveLink(link: TagLink)
    suspend fun deleteLink(tagId: String, targetType: TagTargetType, targetId: String)
    suspend fun reassignLinks(fromTagId: String, intoTagId: String)
    suspend fun stepsOf(taskId: String): List<TaskStep>
    suspend fun saveStep(step: TaskStep)
    suspend fun deleteStep(id: String)
    suspend fun pageBlocksOf(taskId: String): List<TaskPageBlock>
    suspend fun getPageBlock(id: String): TaskPageBlock?
    suspend fun savePageBlock(block: TaskPageBlock)
    suspend fun deletePageBlock(id: String)

    /**
     * Consistent view of a Notes-page document including staged writes.
     *
     * Reads from inside a transaction MUST come through here, never from the repository: the
     * in-memory repository guards its own reads with the same non-reentrant mutex the
     * transaction already holds, so a repository read inside a transaction self-deadlocks.
     */
    suspend fun noteDocOf(ownerKey: String): VirlinNoteDoc?
    suspend fun saveNoteDoc(doc: VirlinNoteDoc)
}

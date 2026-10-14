package com.virlin.app.data.db

import androidx.room.withTransaction
import com.virlin.app.data.db.VirlinMappers.toDomain
import com.virlin.app.data.db.VirlinMappers.toEntity
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.ContextSnapshot
import com.virlin.app.domain.model.Cycle
import com.virlin.app.domain.model.ExternalStage
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskHierarchy
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamEvent
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.domain.repository.newestFirst
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Initial table reads happen in [create] (suspend / IO) — never via `runBlocking` on the
 * main thread during construction.
 */
class RoomWorkStreamRepository private constructor(
    private val db: VirlinDatabase,
    initialStreams: List<WorkStream>,
    initialProjects: List<Project>,
    initialTasks: List<Task>,
    initialCaptures: List<CaptureItem>,
    initialStages: List<ExternalStage>,
    initialTags: List<com.virlin.app.domain.model.ProjectTag>,
    initialLinks: List<com.virlin.app.domain.model.TagLink>,
    initialSteps: List<com.virlin.app.domain.model.TaskStep>,
    initialPageBlocks: List<com.virlin.app.domain.model.TaskPageBlock>
) : WorkStreamRepository {

    private val lock = Mutex()
    // Process-local fences stop an already-open editor's delayed autosave after deletion.
    private val deletedPreviewOwners = mutableSetOf<String>()
    private val deletedNoteOwners = mutableSetOf<String>()
    override suspend fun pendingProjectDeletions() = db.meta().pendingProjectDeletions().map {
        com.virlin.app.domain.action.ProjectDeletion.decode(it.value)
    }

    private val _streams = MutableStateFlow(initialStreams)
    private val _projects = MutableStateFlow(initialProjects)
    private val _tasks = MutableStateFlow(initialTasks)
    override val streams: StateFlow<List<WorkStream>> = _streams.asStateFlow()
    override val projects: StateFlow<List<Project>> = _projects.asStateFlow()
    override val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()
    private val _captures = MutableStateFlow(initialCaptures)
    override val captures: StateFlow<List<CaptureItem>> = _captures.asStateFlow()
    private val _stages = MutableStateFlow(initialStages)
    override val stages: StateFlow<List<ExternalStage>> = _stages.asStateFlow()
    private val _tags = MutableStateFlow(initialTags)
    override val tags: StateFlow<List<com.virlin.app.domain.model.ProjectTag>> = _tags.asStateFlow()
    private val _tagLinks = MutableStateFlow(initialLinks)
    override val tagLinks: StateFlow<List<com.virlin.app.domain.model.TagLink>> = _tagLinks.asStateFlow()
    private val _taskSteps = MutableStateFlow(initialSteps)
    override val taskSteps: StateFlow<List<com.virlin.app.domain.model.TaskStep>> = _taskSteps.asStateFlow()
    private val _taskPageBlocks = MutableStateFlow(initialPageBlocks)
    override val taskPageBlocks: StateFlow<List<com.virlin.app.domain.model.TaskPageBlock>> = _taskPageBlocks.asStateFlow()
    override suspend fun getTag(id: String) = db.projectTags().byId(id)?.toDomain()
    override suspend fun getTaskSteps(taskId: String) = db.taskSteps().byTask(taskId).map { it.toDomain() }
    override suspend fun getTaskPageBlocks(taskId: String) = db.taskPageBlocks().byTask(taskId).map { it.toDomain() }

    override suspend fun getNoteDoc(ownerKey: String) = db.virlinNotes().byOwner(ownerKey)?.toDomain()

    override suspend fun getMapNodePreview(ownerId: String) =
        db.mapNodePreviews().byOwner(ownerId)?.let {
            com.virlin.app.domain.mappreview.MapPreviewCodec.decode(it.ownerKey, it.documentJson)
                .copy(createdAt = it.createdAt, updatedAt = it.updatedAt)
        }

    override suspend fun allMapNodePreviews() =
        db.mapNodePreviews().all().associate { row ->
            row.ownerKey to com.virlin.app.domain.mappreview.MapPreviewCodec
                .decode(row.ownerKey, row.documentJson)
                .copy(createdAt = row.createdAt, updatedAt = row.updatedAt)
        }
    override suspend fun getStages(workStreamId: String) = db.externalStages().byStream(workStreamId).map { it.toDomain() }
    override suspend fun getCapture(id: String) = db.captures().byId(id)?.toDomain()
    override suspend fun getNoteByCaptureId(captureItemId: String) =
        db.noteDocuments().byCaptureId(captureItemId)?.toDomain()
    override suspend fun getNoteDocument(id: String) = db.noteDocuments().byId(id)?.toDomain()
    override suspend fun getPromptByCaptureId(captureItemId: String) =
        db.promptDocuments().byCaptureId(captureItemId)?.toDomain()
    override suspend fun getPromptDocument(id: String) = db.promptDocuments().byId(id)?.toDomain()
    override suspend fun getAttachmentByCaptureId(captureItemId: String) =
        db.attachmentDocuments().byCaptureId(captureItemId)?.toDomain()
    override suspend fun getAttachmentDocument(id: String) = db.attachmentDocuments().byId(id)?.toDomain()
    override suspend fun getVoiceByCaptureId(captureItemId: String) =
        db.voiceDocuments().byCaptureId(captureItemId)?.toDomain()
    override suspend fun getVoiceDocument(id: String) = db.voiceDocuments().byId(id)?.toDomain()

    override suspend fun getNotesByCaptureIds(captureItemIds: List<String>) =
        if (captureItemIds.isEmpty()) emptyMap()
        else db.noteDocuments().byCaptureIds(captureItemIds).associate { it.captureItemId to it.toDomain() }
    override suspend fun getPromptsByCaptureIds(captureItemIds: List<String>) =
        if (captureItemIds.isEmpty()) emptyMap()
        else db.promptDocuments().byCaptureIds(captureItemIds).associate { it.captureItemId to it.toDomain() }
    override suspend fun getAttachmentsByCaptureIds(captureItemIds: List<String>) =
        if (captureItemIds.isEmpty()) emptyMap()
        else db.attachmentDocuments().byCaptureIds(captureItemIds).associate { it.captureItemId to it.toDomain() }
    override suspend fun getVoicesByCaptureIds(captureItemIds: List<String>) =
        if (captureItemIds.isEmpty()) emptyMap()
        else db.voiceDocuments().byCaptureIds(captureItemIds).associate { it.captureItemId to it.toDomain() }

    override suspend fun getStream(id: String) = db.workStreams().byId(id)?.toDomain()
    override val taskExecutionEnabled = true
    override suspend fun getActiveFocus() = db.workStreams().activeFocus()?.toDomain()
    override suspend fun getOpenFocusSession(streamId: String) = db.focusSessions().open(streamId)?.toDomain()
    override suspend fun getCurrentCycle(streamId: String) = db.cycles().current(streamId)?.toDomain()
    override suspend fun getLatestSnapshot(streamId: String) = db.snapshots().latest(streamId)?.toDomain()
    override suspend fun getSnapshots(streamId: String) = db.snapshots().byWorkStream(streamId).map { it.toDomain() }
    override suspend fun getEvents(streamId: String) = db.events().byWorkStream(streamId).map { it.toDomain() }
    override suspend fun getEventsForStreams(streamIds: List<String>, limit: Int, offset: Int) =
        if (streamIds.isEmpty()) emptyList()
        else db.events().byWorkStreams(streamIds, limit, offset).map { it.toDomain() }
    override suspend fun allFocusSessions() = db.focusSessions().all().map { it.toDomain() }
    override suspend fun externalRunIntervals() = db.externalRuns().all().map {
        com.virlin.app.domain.model.ExternalRunInterval(it.ownerId, it.taskId,
            java.time.Instant.ofEpochMilli(it.startedAt), java.time.Instant.ofEpochMilli(it.endedAt))
    }
    override suspend fun allCycles() = db.cycles().all().map { it.toDomain() }
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
        writer.deletedProjects.forEach {
            deletedPreviewOwners += it.ownerIds
            deletedNoteOwners += it.noteOwners
        }
        // Commit succeeded: publish. Re-read only what the block touched.
        if (writer.touchedStreams) _streams.value = db.workStreams().all().map { it.toDomain() }
        if (writer.touchedProjects) _projects.value = db.projects().all().map { it.toDomain() }
        if (writer.touchedTasks) _tasks.value = db.tasks().all().map { it.toDomain() }
        if (writer.touchedStages) _stages.value = db.externalStages().all().map { it.toDomain() }
        if (writer.touchedCaptures) _captures.value = db.captures().all().map { it.toDomain() }.newestFirst()
        if (writer.touchedTags) _tags.value = db.projectTags().all().map { it.toDomain() }
        if (writer.touchedLinks) _tagLinks.value = db.tagLinks().all().map { it.toDomain() }
        if (writer.touchedSteps) _taskSteps.value = db.taskSteps().all().map { it.toDomain() }
        if (writer.touchedPageBlocks) _taskPageBlocks.value = db.taskPageBlocks().all().map { it.toDomain() }
        // Previews are read when the map opens rather than observed per node, so there is no
        // StateFlow to republish; the flag exists so a future observer has one place to hook.
        if (writer.touchedMapPreviews) Unit
        result
    }

    /** Reads inside the transaction see the transaction's own writes — Room guarantees that. */
    private inner class Writer : WorkStreamWriter {
        val deletedProjects = mutableListOf<com.virlin.app.domain.action.ProjectDeletion>()
        override suspend fun acknowledgeProjectDeletion(projectId: String) {
            db.meta().delete(com.virlin.app.domain.action.ProjectDeletion.KEY_PREFIX + projectId)
        }
        override suspend fun deleteProjectTree(projectId: String): com.virlin.app.domain.action.ProjectDeletion {
            check(db.projects().byId(projectId) != null) { "Project not found." }
            val scope = com.virlin.app.domain.action.ProjectDeletion.scope(projectId,
                allStreams(), allTasks(), db.captures().all().map { it.toDomain() })
            return deleteTree(scope)
        }
        override suspend fun deleteWorkStreamTree(id: String): com.virlin.app.domain.action.ProjectDeletion {
            check(getStream(id) != null) { "Workstream not found." }
            return deleteTree(com.virlin.app.domain.action.ProjectDeletion.branch(id, "workstream", allStreams(), allTasks(), db.captures().all().map { it.toDomain() }))
        }
        override suspend fun deleteTaskTree(id: String): com.virlin.app.domain.action.ProjectDeletion {
            check(getTask(id) != null) { "Task not found." }
            return deleteTree(com.virlin.app.domain.action.ProjectDeletion.branch(id, "task", allStreams(), allTasks(), db.captures().all().map { it.toDomain() }))
        }
        private suspend fun deleteTree(scope: com.virlin.app.domain.action.ProjectDeletion): com.virlin.app.domain.action.ProjectDeletion {
            val projectId = scope.projectId
            val report = scope.copy(attachmentIds = scope.captureIds.mapNotNull {
                db.attachmentDocuments().byCaptureId(it)?.id
            }.toSet())
            // The receipt is committed with deletion so interrupted disk cleanup is recoverable.
            db.meta().put(MetaEntity(com.virlin.app.domain.action.ProjectDeletion.KEY_PREFIX + projectId, report.encode()))
            val sql = db.openHelper.writableDatabase
            fun delete(table: String, column: String, id: String) {
                sql.execSQL("DELETE FROM $table WHERE $column = ?", arrayOf(id))
            }
            report.captureIds.forEach { id ->
                listOf("note_documents", "prompt_documents", "attachment_documents", "voice_documents").forEach { delete(it, "captureItemId", id) }
                delete("task_page_blocks", "contentId", id)
                delete("tag_links", "targetId", id)
                delete("captures", "id", id)
            }
            val tags = if (report.kind == "project") db.projectTags().byProject(projectId) else emptyList()
            tags.forEach { db.tagLinks().deleteByTag(it.id); db.projectTags().delete(it.id) }
            report.ownerIds.forEach { id ->
                delete("external_run_intervals", "ownerId", id)
                delete("external_run_intervals", "taskId", id)
                delete("tag_links", "targetId", id)
                delete("priority_preferences", "streamId", id)
                delete("map_node_previews", "ownerKey", id)
            }
            report.noteOwners.forEach { delete("virlin_notes", "ownerKey", it) }
            val taskEventIds = sql.query("SELECT id, type, detail FROM events WHERE type IN ('TASK_CREATED','TASK_UPDATED','TASK_COMPLETED','ACTIVE_TASK_SET','ACTIVE_TASK_CLEARED')").use { rows ->
                buildList { while (rows.moveToNext()) if (report.ownsTaskEvent(rows.getString(1), rows.getString(2))) add(rows.getString(0)) }
            }
            taskEventIds.forEach { delete("events", "id", it) }
            (report.streamIds + report.taskIds).forEach { id ->
                listOf("cycles", "focus_sessions", "context_snapshots", "events", "external_stages").forEach { delete(it, "workStreamId", id) }
            }
            report.taskIds.forEach { id ->
                sql.execSQL("UPDATE captures SET convertedTaskId = NULL WHERE convertedTaskId = ?", arrayOf(id))
                delete("focus_sessions", "taskId", id); delete("context_snapshots", "taskId", id)
                delete("task_steps", "taskId", id); delete("task_page_blocks", "taskId", id)
                delete("tasks", "id", id)
            }
            report.streamIds.forEach { delete("workstreams", "id", it) }
            // Surviving containers must never point at a deleted active task.
            report.taskIds.forEach { id -> sql.execSQL("UPDATE workstreams SET activeTaskId = NULL WHERE activeTaskId = ?", arrayOf(id)) }
            if (report.kind == "project") delete("projects", "id", projectId)
            touchedStreams = true; touchedProjects = true; touchedTasks = true; touchedCaptures = true
            touchedStages = true; touchedTags = true; touchedLinks = true; touchedSteps = true
            touchedPageBlocks = true; touchedMapPreviews = true
            deletedProjects += report
            return report
        }
        var touchedStreams = false; var touchedProjects = false; var touchedTasks = false; var touchedCaptures = false; var touchedStages = false
        var touchedTags = false; var touchedLinks = false; var touchedSteps = false; var touchedPageBlocks = false
        var touchedMapPreviews = false

        override suspend fun allTags() = db.projectTags().all().map { it.toDomain() }
        override suspend fun getTag(id: String) = db.projectTags().byId(id)?.toDomain()
        override suspend fun saveTag(tag: com.virlin.app.domain.model.ProjectTag) {
            db.projectTags().upsert(tag.toEntity()); touchedTags = true
        }
        override suspend fun deleteTag(id: String) {
            // Deleting a tag removes its links; nothing it labelled is deleted with it.
            db.tagLinks().deleteByTag(id); db.projectTags().delete(id)
            touchedTags = true; touchedLinks = true
        }
        override suspend fun linksOf(
            targetType: com.virlin.app.domain.model.TagTargetType, targetId: String
        ) = db.tagLinks().byTarget(targetType.name, targetId).map { it.toDomain() }
        override suspend fun allLinks() = db.tagLinks().all().map { it.toDomain() }
        override suspend fun saveLink(link: com.virlin.app.domain.model.TagLink) {
            db.tagLinks().upsert(link.toEntity()); touchedLinks = true
        }
        override suspend fun deleteLink(
            tagId: String, targetType: com.virlin.app.domain.model.TagTargetType, targetId: String
        ) { db.tagLinks().delete(tagId, targetType.name, targetId); touchedLinks = true }
        override suspend fun reassignLinks(fromTagId: String, intoTagId: String) {
            db.tagLinks().reassign(fromTagId, intoTagId); touchedLinks = true
        }
        override suspend fun stepsOf(taskId: String) = db.taskSteps().byTask(taskId).map { it.toDomain() }
        override suspend fun saveStep(step: com.virlin.app.domain.model.TaskStep) {
            db.taskSteps().upsert(step.toEntity()); touchedSteps = true
        }
        override suspend fun deleteStep(id: String) { db.taskSteps().delete(id); touchedSteps = true }
        override suspend fun pageBlocksOf(taskId: String) = db.taskPageBlocks().byTask(taskId).map { it.toDomain() }
        override suspend fun getPageBlock(id: String) = db.taskPageBlocks().byId(id)?.toDomain()
        override suspend fun savePageBlock(block: com.virlin.app.domain.model.TaskPageBlock) {
            db.taskPageBlocks().upsert(block.toEntity()); touchedPageBlocks = true
        }
        override suspend fun deletePageBlock(id: String) { db.taskPageBlocks().delete(id); touchedPageBlocks = true }

        // Notes-page documents are read on open rather than observed, so no publish flag exists
        // for them; the editor re-reads through the repository when it reopens.
        override suspend fun noteDocOf(ownerKey: String) = db.virlinNotes().byOwner(ownerKey)?.toDomain()
        override suspend fun saveNoteDoc(doc: com.virlin.app.domain.notedoc.VirlinNoteDoc) {
            check(doc.ownerKey !in deletedNoteOwners && deletedProjects.none { doc.ownerKey in it.noteOwners }) { "Note owner was deleted." }
            db.virlinNotes().upsert(doc.toEntity())
        }

        override suspend fun mapNodePreviewOf(ownerId: String) =
            db.mapNodePreviews().byOwner(ownerId)?.let {
                com.virlin.app.domain.mappreview.MapPreviewCodec.decode(it.ownerKey, it.documentJson)
                    .copy(createdAt = it.createdAt, updatedAt = it.updatedAt)
            }

        override suspend fun saveMapNodePreview(preview: com.virlin.app.domain.mappreview.MapNodePreview) {
            check(preview.ownerId !in deletedPreviewOwners && deletedProjects.none { preview.ownerId in it.ownerIds }) { "Preview owner was deleted." }
            val existing = db.mapNodePreviews().byOwner(preview.ownerId)
            val now = preview.updatedAt ?: java.time.Instant.now()
            db.mapNodePreviews().upsert(
                MapNodePreviewEntity(
                    ownerKey = preview.ownerId,
                    documentJson = com.virlin.app.domain.mappreview.MapPreviewCodec.encode(preview),
                    revision = (existing?.revision ?: 0) + 1,
                    createdAt = existing?.createdAt ?: preview.createdAt ?: now,
                    updatedAt = now,
                )
            )
            touchedMapPreviews = true
        }

        override suspend fun deleteMapNodePreview(ownerId: String) {
            db.mapNodePreviews().deleteByOwner(ownerId); touchedMapPreviews = true
        }

        override suspend fun getCapture(id: String) = db.captures().byId(id)?.toDomain()
        override suspend fun saveCapture(capture: CaptureItem) { db.captures().upsert(capture.toEntity()); touchedCaptures = true }
        override suspend fun getNoteByCaptureId(captureItemId: String) =
            db.noteDocuments().byCaptureId(captureItemId)?.toDomain()
        override suspend fun saveNoteDocument(note: com.virlin.app.domain.model.NoteDocument) {
            db.noteDocuments().upsert(note.toEntity()); touchedCaptures = true
        }
        override suspend fun getPromptByCaptureId(captureItemId: String) =
            db.promptDocuments().byCaptureId(captureItemId)?.toDomain()
        override suspend fun savePromptDocument(prompt: com.virlin.app.domain.model.PromptDocument) {
            db.promptDocuments().upsert(prompt.toEntity()); touchedCaptures = true
        }
        override suspend fun getAttachmentByCaptureId(captureItemId: String) =
            db.attachmentDocuments().byCaptureId(captureItemId)?.toDomain()
        override suspend fun saveAttachmentDocument(attachment: com.virlin.app.domain.model.AttachmentDocument) {
            db.attachmentDocuments().upsert(attachment.toEntity()); touchedCaptures = true
        }
        override suspend fun getVoiceByCaptureId(captureItemId: String) =
            db.voiceDocuments().byCaptureId(captureItemId)?.toDomain()
        override suspend fun saveVoiceDocument(voice: com.virlin.app.domain.model.VoiceDocument) {
            db.voiceDocuments().upsert(voice.toEntity()); touchedCaptures = true
        }

        override suspend fun getStream(id: String) = db.workStreams().byId(id)?.toDomain()
        override suspend fun getActiveFocus() = db.workStreams().activeFocus()?.toDomain()
        override suspend fun getOpenFocusSession(streamId: String) = db.focusSessions().open(streamId)?.toDomain()
        override suspend fun getCycle(id: String) = db.cycles().byId(id)?.toDomain()
        override suspend fun getCurrentCycle(streamId: String) = db.cycles().current(streamId)?.toDomain()
        override suspend fun getLatestSnapshot(streamId: String) = db.snapshots().latest(streamId)?.toDomain()
        override suspend fun executionEvents(streamId: String) = db.events().byWorkStream(streamId).map { it.toDomain() }
        override suspend fun getProject(id: String) = db.projects().byId(id)?.toDomain()
        override suspend fun getTask(id: String) = db.tasks().byId(id)?.toDomain()
        override suspend fun allTasks() = db.tasks().all().map { it.toDomain() }
        override suspend fun allStreams() = db.workStreams().all().map { it.toDomain() }

        override suspend fun saveProject(project: Project) { db.projects().upsert(project.toEntity()); touchedProjects = true }
        private suspend fun recordRun(id: String, taskId: String?, old: com.virlin.app.domain.model.TaskExecution?, next: com.virlin.app.domain.model.TaskExecution?, end: java.time.Instant) {
            val start = old?.processingStartedAt?.takeUnless { old.externalTrackingPaused } ?: return
            val nextStart = next?.processingStartedAt?.takeUnless { next.externalTrackingPaused }
            val stoppedAt = minOf(end, old.checkAt ?: end)
            if (nextStart != start && stoppedAt > start) db.externalRuns().insert(
                ExternalRunEntity("$id:${start.toEpochMilli()}", id, taskId, start.toEpochMilli(), stoppedAt.toEpochMilli()))
        }
        override suspend fun saveTask(task: Task) {
            recordRun(task.id, task.id, getTask(task.id)?.execution, task.execution, task.updatedAt)
            db.tasks().upsert(task.toEntity()); touchedTasks = true
        }
        override suspend fun saveStream(stream: WorkStream) {
            val old = getStream(stream.id)
            recordRun(stream.id, old?.activeTaskId, old?.let { com.virlin.app.domain.model.TaskExecution.of(it) },
                com.virlin.app.domain.model.TaskExecution.of(stream), stream.updatedAt)
            db.workStreams().upsert(stream.toEntity()); touchedStreams = true
        }
        override suspend fun saveCycle(cycle: Cycle) {
            val seq = db.cycles().byId(cycle.id)?.seq ?: (db.cycles().maxSeq() + 1)
            db.cycles().upsert(cycle.toEntity(seq))
        }
        override suspend fun saveFocusSession(session: FocusSession) {
            val seq = db.focusSessions().byId(session.id)?.seq ?: (db.focusSessions().maxSeq() + 1)
            db.focusSessions().upsert(session.toEntity(seq))
        }
        override suspend fun stagesOf(workStreamId: String) = db.externalStages().byStream(workStreamId).map { it.toDomain() }
        override suspend fun saveStage(stage: ExternalStage) { db.externalStages().upsert(stage.toEntity()); touchedStages = true }
        override suspend fun saveSnapshot(snapshot: ContextSnapshot) { db.snapshots().insert(snapshot.toEntity(db.snapshots().maxSeq() + 1)) }
        override suspend fun appendEvent(event: WorkStreamEvent) { db.events().insert(event.toEntity(db.events().maxSeq() + 1)) }
    }

    companion object {
        /** Load initial StateFlow snapshots off the caller’s thread (use Dispatchers.IO). */
        suspend fun create(db: VirlinDatabase): RoomWorkStreamRepository {
            val streams = db.workStreams().all().map { it.toDomain() }
            val projects = db.projects().all().map { it.toDomain() }
            val tasks = db.tasks().all().map { it.toDomain() }
            val captures = db.captures().all().map { it.toDomain() }.newestFirst()
            val stages = db.externalStages().all().map { it.toDomain() }
            val tags = db.projectTags().all().map { it.toDomain() }
            val links = db.tagLinks().all().map { it.toDomain() }
            val steps = db.taskSteps().all().map { it.toDomain() }
            val pageBlocks = db.taskPageBlocks().all().map { it.toDomain() }
            return RoomWorkStreamRepository(
                db, streams, projects, tasks, captures, stages, tags, links, steps, pageBlocks
            )
        }
    }
}

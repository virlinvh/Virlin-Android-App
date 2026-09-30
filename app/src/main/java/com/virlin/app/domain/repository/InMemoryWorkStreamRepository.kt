package com.virlin.app.domain.repository

import com.virlin.app.domain.model.AttachmentDocument
import com.virlin.app.domain.model.VoiceDocument
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.ContextSnapshot
import com.virlin.app.domain.model.Cycle
import com.virlin.app.domain.model.ExternalStage
import com.virlin.app.domain.model.ExternalStages
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.NoteDocument
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.PromptDocument
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskHierarchy
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamEvent
import com.virlin.app.domain.model.WorkStreamState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deterministic in-memory repository. V1 persistence boundary; Room replaces it later.
 *
 * Transactions are serialized with a [Mutex] and staged in working copies; the observable
 * [streams] flow is published once, after the block succeeds, so observers never see a
 * half-applied hand-off. If the block throws, nothing is published.
 */
class InMemoryWorkStreamRepository(
    seed: List<WorkStream> = emptyList(),
    seedProjects: List<Project> = emptyList(),
    seedTasks: List<Task> = emptyList()
) : WorkStreamRepository {

    private val lock = Mutex()
    private val streamMap = LinkedHashMap<String, WorkStream>().apply { seed.forEach { put(it.id, it) } }
    private val projectMap = LinkedHashMap<String, Project>().apply { seedProjects.forEach { put(it.id, it) } }
    private val taskMap = LinkedHashMap<String, Task>().apply { seedTasks.forEach { put(it.id, it) } }
    private val cycles = mutableListOf<Cycle>()
    private val sessions = mutableListOf<FocusSession>()
    private val snapshots = mutableListOf<ContextSnapshot>()
    private val events = mutableListOf<WorkStreamEvent>()
    private val captureMap = LinkedHashMap<String, CaptureItem>()
    private val noteByCapture = LinkedHashMap<String, NoteDocument>()
    private val noteById = LinkedHashMap<String, NoteDocument>()
    private val promptByCapture = LinkedHashMap<String, PromptDocument>()
    private val promptById = LinkedHashMap<String, PromptDocument>()
    private val attachmentByCapture = LinkedHashMap<String, AttachmentDocument>()
    private val attachmentById = LinkedHashMap<String, AttachmentDocument>()
    private val voiceByCapture = LinkedHashMap<String, VoiceDocument>()
    private val voiceById = LinkedHashMap<String, VoiceDocument>()

    private val stageMap = LinkedHashMap<String, ExternalStage>()

    private val _stages = MutableStateFlow<List<ExternalStage>>(emptyList())
    override val stages: StateFlow<List<ExternalStage>> = _stages.asStateFlow()
    override suspend fun getStages(workStreamId: String) = lock.withLock { ExternalStages.of(stageMap.values, workStreamId) }

    private val _streams = MutableStateFlow(streamMap.values.toList())
    override val streams: StateFlow<List<WorkStream>> = _streams.asStateFlow()
    private val _projects = MutableStateFlow(projectMap.values.toList())
    override val projects: StateFlow<List<Project>> = _projects.asStateFlow()
    private val _tasks = MutableStateFlow(taskMap.values.toList())
    override val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()
    private val _captures = MutableStateFlow<List<CaptureItem>>(emptyList())
    override val captures: StateFlow<List<CaptureItem>> = _captures.asStateFlow()
    override suspend fun getCapture(id: String) = lock.withLock { captureMap[id] }
    override suspend fun getNoteByCaptureId(captureItemId: String) = lock.withLock { noteByCapture[captureItemId] }
    override suspend fun getNoteDocument(id: String) = lock.withLock { noteById[id] }
    override suspend fun getPromptByCaptureId(captureItemId: String) = lock.withLock { promptByCapture[captureItemId] }
    override suspend fun getPromptDocument(id: String) = lock.withLock { promptById[id] }
    override suspend fun getAttachmentByCaptureId(captureItemId: String) = lock.withLock { attachmentByCapture[captureItemId] }
    override suspend fun getAttachmentDocument(id: String) = lock.withLock { attachmentById[id] }
    override suspend fun getVoiceByCaptureId(captureItemId: String) = lock.withLock { voiceByCapture[captureItemId] }

    // ---- Tags and task steps (schema v13)
    private val tagsById = LinkedHashMap<String, com.virlin.app.domain.model.ProjectTag>()
    private val links = LinkedHashSet<com.virlin.app.domain.model.TagLink>()
    private val stepsById = LinkedHashMap<String, com.virlin.app.domain.model.TaskStep>()
    private val noteDocsByOwner = LinkedHashMap<String, com.virlin.app.domain.notedoc.VirlinNoteDoc>()
    private val pageBlocksById = LinkedHashMap<String, com.virlin.app.domain.model.TaskPageBlock>()
    private val _tags = MutableStateFlow<List<com.virlin.app.domain.model.ProjectTag>>(emptyList())
    override val tags: StateFlow<List<com.virlin.app.domain.model.ProjectTag>> = _tags.asStateFlow()
    private val _tagLinks = MutableStateFlow<List<com.virlin.app.domain.model.TagLink>>(emptyList())
    override val tagLinks: StateFlow<List<com.virlin.app.domain.model.TagLink>> = _tagLinks.asStateFlow()
    private val _taskSteps = MutableStateFlow<List<com.virlin.app.domain.model.TaskStep>>(emptyList())
    override val taskSteps: StateFlow<List<com.virlin.app.domain.model.TaskStep>> = _taskSteps.asStateFlow()
    private val _taskPageBlocks = MutableStateFlow<List<com.virlin.app.domain.model.TaskPageBlock>>(emptyList())
    override val taskPageBlocks: StateFlow<List<com.virlin.app.domain.model.TaskPageBlock>> = _taskPageBlocks.asStateFlow()
    override suspend fun getTag(id: String) = lock.withLock { tagsById[id] }
    override suspend fun getTaskSteps(taskId: String) =
        lock.withLock { stepsById.values.filter { it.taskId == taskId }.sortedBy { it.order } }
    override suspend fun getTaskPageBlocks(taskId: String) = lock.withLock {
        pageBlocksById.values.filter { it.taskId == taskId }.sortedBy { it.order }
    }
    override suspend fun getNoteDoc(ownerKey: String) = lock.withLock { noteDocsByOwner[ownerKey] }
    override suspend fun getNotesByCaptureIds(captureItemIds: List<String>) =
        lock.withLock { captureItemIds.mapNotNull { id -> noteByCapture[id]?.let { id to it } }.toMap() }
    override suspend fun getPromptsByCaptureIds(captureItemIds: List<String>) =
        lock.withLock { captureItemIds.mapNotNull { id -> promptByCapture[id]?.let { id to it } }.toMap() }
    override suspend fun getAttachmentsByCaptureIds(captureItemIds: List<String>) =
        lock.withLock { captureItemIds.mapNotNull { id -> attachmentByCapture[id]?.let { id to it } }.toMap() }
    override suspend fun getVoicesByCaptureIds(captureItemIds: List<String>) =
        lock.withLock { captureItemIds.mapNotNull { id -> voiceByCapture[id]?.let { id to it } }.toMap() }
    override suspend fun getVoiceDocument(id: String) = lock.withLock { voiceById[id] }

    override suspend fun getProject(id: String) = lock.withLock { projectMap[id] }
    override suspend fun getTask(id: String) = lock.withLock { taskMap[id] }
    override suspend fun getStreamsByProject(projectId: String) = lock.withLock { streamMap.values.filter { it.projectId == projectId } }
    override suspend fun getTasksByProject(projectId: String) = lock.withLock { taskMap.values.filter { it.projectId == projectId } }
    override suspend fun getTasksByWorkStream(workStreamId: String) = lock.withLock { taskMap.values.filter { it.workStreamId == workStreamId } }
    override suspend fun getChildren(taskId: String) = lock.withLock { TaskHierarchy.children(taskMap.values, taskId) }
    override suspend fun getAncestry(taskId: String) = lock.withLock { TaskHierarchy.ancestry(taskMap.values, taskId) }

    override suspend fun getStream(id: String) = lock.withLock { streamMap[id] }
    override suspend fun getActiveFocus() = lock.withLock { streamMap.values.firstOrNull { it.state == WorkStreamState.FOCUS } }
    override suspend fun getOpenFocusSession(streamId: String) = lock.withLock { sessions.lastOrNull { it.workStreamId == streamId && it.isOpen } }
    override suspend fun getCurrentCycle(streamId: String) = lock.withLock { cycles.lastOrNull { it.workStreamId == streamId && it.endedAt == null } }
    override suspend fun getLatestSnapshot(streamId: String) = lock.withLock { snapshots.lastOrNull { it.workStreamId == streamId } }
    override suspend fun getEvents(streamId: String) = lock.withLock { events.filter { it.workStreamId == streamId } }
    override suspend fun getEventsForStreams(streamIds: List<String>, limit: Int, offset: Int) =
        lock.withLock {
            val ids = streamIds.toSet()
            events.asSequence()
                .filter { it.workStreamId in ids }
                .sortedWith(compareByDescending<WorkStreamEvent> { it.at }.thenByDescending { it.id })
                .drop(offset).take(limit).toList()
        }
    override suspend fun getFocusSessions(streamId: String) = lock.withLock { sessions.filter { it.workStreamId == streamId } }
    override suspend fun allFocusSessions() = lock.withLock { sessions.toList() }
    override suspend fun allCycles() = lock.withLock { cycles.toList() }
    override suspend fun getCycles(streamId: String) = lock.withLock { cycles.filter { it.workStreamId == streamId } }

    override suspend fun <T> transaction(block: suspend WorkStreamWriter.() -> T): T = lock.withLock {
        val staged = Staged()
        val result = staged.block()
        // Commit: only reached if the block completed without throwing.
        staged.streams.forEach { (id, s) -> streamMap[id] = s }
        staged.projects.forEach { (id, p) -> projectMap[id] = p }
        staged.tasks.forEach { (id, t) -> taskMap[id] = t }
        cycles.addAll(staged.cycles.values); staged.cycleUpdates.forEach { c -> cycles.replaceAll { if (it.id == c.id) c else it } }
        sessions.addAll(staged.sessions.values); staged.sessionUpdates.forEach { s -> sessions.replaceAll { if (it.id == s.id) s else it } }
        snapshots.addAll(staged.snapshots)
        staged.stages.forEach { (id, st) -> stageMap[id] = st }
        if (staged.stages.isNotEmpty()) _stages.value = stageMap.values.toList()
        events.addAll(staged.events)
        staged.stagedTags.forEach { (id, t) -> tagsById[id] = t }
        links.addAll(staged.addedLinks)
        staged.removedLinks.forEach { (tagId, type, target) ->
            links.removeAll { it.tagId == tagId && it.targetType == type && it.targetId == target }
        }
        // A merge reassigns before the old tag goes; doing it the other way round would drop
        // exactly the links the merge was meant to keep.
        staged.reassigned.forEach { (from, into) ->
            val moved = links.filter { it.tagId == from }
            links.removeAll(moved.toSet())
            moved.forEach { links += it.copy(tagId = into) }
        }
        staged.removedTags.forEach { id ->
            tagsById.remove(id); links.removeAll { it.tagId == id }
        }
        staged.stagedSteps.forEach { (id, st) -> stepsById[id] = st }
        staged.removedSteps.forEach { stepsById.remove(it) }
        staged.stagedNoteDocs.forEach { (key, doc) -> noteDocsByOwner[key] = doc }
        staged.removedPageBlocks.forEach { pageBlocksById.remove(it) }
        staged.stagedPageBlocks.forEach { (id, block) -> pageBlocksById[id] = block }
        if (staged.removedPageBlocks.isNotEmpty() || staged.stagedPageBlocks.isNotEmpty()) {
            _taskPageBlocks.value = pageBlocksById.values.sortedWith(compareBy({ it.taskId }, { it.order }))
        }
        if (staged.stagedTags.isNotEmpty() || staged.removedTags.isNotEmpty()) _tags.value = tagsById.values.toList()
        if (staged.addedLinks.isNotEmpty() || staged.removedLinks.isNotEmpty() ||
            staged.reassigned.isNotEmpty() || staged.removedTags.isNotEmpty()
        ) _tagLinks.value = links.toList()
        if (staged.stagedSteps.isNotEmpty() || staged.removedSteps.isNotEmpty()) _taskSteps.value = stepsById.values.toList()
        _streams.value = streamMap.values.toList()
        if (staged.projects.isNotEmpty()) _projects.value = projectMap.values.toList()
        if (staged.tasks.isNotEmpty()) _tasks.value = taskMap.values.toList()
        staged.captures.forEach { (id, c) -> captureMap[id] = c }
        if (staged.captures.isNotEmpty()) _captures.value = captureMap.values.toList().newestFirst()
        staged.notes.values.forEach { n ->
            noteById[n.id] = n
            noteByCapture[n.captureItemId] = n
        }
        staged.prompts.values.forEach { p ->
            promptById[p.id] = p
            promptByCapture[p.captureItemId] = p
        }
        staged.attachments.values.forEach { a ->
            attachmentById[a.id] = a
            attachmentByCapture[a.captureItemId] = a
        }
        staged.voices.values.forEach { v ->
            voiceById[v.id] = v
            voiceByCapture[v.captureItemId] = v
        }
        result
    }

    /** Working copies for one transaction; reads see staged writes so a block is self-consistent. */
    private inner class Staged : WorkStreamWriter {
        val streams = LinkedHashMap<String, WorkStream>()
        val projects = LinkedHashMap<String, Project>()
        val tasks = LinkedHashMap<String, Task>()
        val cycles = LinkedHashMap<String, Cycle>()
        val cycleUpdates = mutableListOf<Cycle>()
        val sessions = LinkedHashMap<String, FocusSession>()
        val sessionUpdates = mutableListOf<FocusSession>()
        val snapshots = mutableListOf<ContextSnapshot>()
        val events = mutableListOf<WorkStreamEvent>()
        val stages = LinkedHashMap<String, ExternalStage>()
        val captures = LinkedHashMap<String, CaptureItem>()
        val notes = LinkedHashMap<String, NoteDocument>()
        val prompts = LinkedHashMap<String, PromptDocument>()
        val attachments = LinkedHashMap<String, AttachmentDocument>()
        val voices = LinkedHashMap<String, VoiceDocument>()
        val stagedTags = LinkedHashMap<String, com.virlin.app.domain.model.ProjectTag>()
        val removedTags = LinkedHashSet<String>()
        val addedLinks = LinkedHashSet<com.virlin.app.domain.model.TagLink>()
        val removedLinks = LinkedHashSet<Triple<String, com.virlin.app.domain.model.TagTargetType, String>>()
        val reassigned = mutableListOf<Pair<String, String>>()
        val stagedSteps = LinkedHashMap<String, com.virlin.app.domain.model.TaskStep>()
        val removedSteps = LinkedHashSet<String>()
        val stagedNoteDocs = LinkedHashMap<String, com.virlin.app.domain.notedoc.VirlinNoteDoc>()
        val stagedPageBlocks = LinkedHashMap<String, com.virlin.app.domain.model.TaskPageBlock>()
        val removedPageBlocks = LinkedHashSet<String>()

        override suspend fun allTags() = (tagsById + stagedTags).values.filter { it.id !in removedTags }
        override suspend fun getTag(id: String) =
            if (id in removedTags) null else stagedTags[id] ?: tagsById[id]
        override suspend fun saveTag(tag: com.virlin.app.domain.model.ProjectTag) { stagedTags[tag.id] = tag }
        override suspend fun deleteTag(id: String) { removedTags += id }
        override suspend fun linksOf(
            targetType: com.virlin.app.domain.model.TagTargetType, targetId: String
        ) = allLinks().filter { it.targetType == targetType && it.targetId == targetId }
        override suspend fun allLinks(): List<com.virlin.app.domain.model.TagLink> =
            (links + addedLinks).filterNot { l ->
                l.tagId in removedTags ||
                    Triple(l.tagId, l.targetType, l.targetId) in removedLinks
            }
        override suspend fun saveLink(link: com.virlin.app.domain.model.TagLink) { addedLinks += link }
        override suspend fun deleteLink(
            tagId: String, targetType: com.virlin.app.domain.model.TagTargetType, targetId: String
        ) { removedLinks += Triple(tagId, targetType, targetId) }
        override suspend fun reassignLinks(fromTagId: String, intoTagId: String) {
            reassigned += fromTagId to intoTagId
        }
        override suspend fun stepsOf(taskId: String) =
            (stepsById + stagedSteps).values
                .filter { it.taskId == taskId && it.id !in removedSteps }.sortedBy { it.order }
        override suspend fun saveStep(step: com.virlin.app.domain.model.TaskStep) { stagedSteps[step.id] = step }
        override suspend fun deleteStep(id: String) { removedSteps += id }

        override suspend fun noteDocOf(ownerKey: String) =
            stagedNoteDocs[ownerKey] ?: noteDocsByOwner[ownerKey]
        override suspend fun saveNoteDoc(doc: com.virlin.app.domain.notedoc.VirlinNoteDoc) { stagedNoteDocs[doc.ownerKey] = doc }
        override suspend fun pageBlocksOf(taskId: String) =
            (pageBlocksById + stagedPageBlocks).values.filter { it.taskId == taskId && it.id !in removedPageBlocks }.sortedBy { it.order }
        override suspend fun getPageBlock(id: String) =
            if (id in removedPageBlocks) null else stagedPageBlocks[id] ?: pageBlocksById[id]
        override suspend fun savePageBlock(block: com.virlin.app.domain.model.TaskPageBlock) {
            stagedPageBlocks[block.id] = block; removedPageBlocks.remove(block.id)
        }
        override suspend fun deletePageBlock(id: String) { removedPageBlocks += id; stagedPageBlocks.remove(id) }

        override suspend fun getCapture(id: String) = captures[id] ?: captureMap[id]
        override suspend fun saveCapture(capture: CaptureItem) { captures[capture.id] = capture }
        override suspend fun getNoteByCaptureId(captureItemId: String) =
            notes.values.firstOrNull { it.captureItemId == captureItemId }
                ?: noteByCapture[captureItemId]
        override suspend fun saveNoteDocument(note: NoteDocument) { notes[note.id] = note }
        override suspend fun getPromptByCaptureId(captureItemId: String) =
            prompts.values.firstOrNull { it.captureItemId == captureItemId }
                ?: promptByCapture[captureItemId]
        override suspend fun savePromptDocument(prompt: PromptDocument) { prompts[prompt.id] = prompt }
        override suspend fun getAttachmentByCaptureId(captureItemId: String) =
            attachments.values.firstOrNull { it.captureItemId == captureItemId }
                ?: attachmentByCapture[captureItemId]
        override suspend fun saveAttachmentDocument(attachment: AttachmentDocument) {
            attachments[attachment.id] = attachment
        }
        override suspend fun getVoiceByCaptureId(captureItemId: String) =
            voices.values.firstOrNull { it.captureItemId == captureItemId }
                ?: voiceByCapture[captureItemId]
        override suspend fun saveVoiceDocument(voice: VoiceDocument) {
            voices[voice.id] = voice
        }
        override suspend fun getStream(id: String) = streams[id] ?: streamMap[id]
        override suspend fun getActiveFocus(): WorkStream? {
            val merged = LinkedHashMap(streamMap).apply { putAll(streams) }
            return merged.values.firstOrNull { it.state == WorkStreamState.FOCUS }
        }
        override suspend fun getOpenFocusSession(streamId: String): FocusSession? {
            val staged = (sessions.values + sessionUpdates).lastOrNull { it.workStreamId == streamId }
            if (staged != null) return staged.takeIf { it.isOpen }
            return this@InMemoryWorkStreamRepository.sessions.lastOrNull { it.workStreamId == streamId && it.isOpen }
        }
        override suspend fun getCurrentCycle(streamId: String): Cycle? {
            val staged = (cycles.values + cycleUpdates).lastOrNull { it.workStreamId == streamId }
            if (staged != null) return staged.takeIf { it.endedAt == null }
            return this@InMemoryWorkStreamRepository.cycles.lastOrNull { it.workStreamId == streamId && it.endedAt == null }
        }
        override suspend fun getLatestSnapshot(streamId: String) =
            snapshots.lastOrNull { it.workStreamId == streamId }
                ?: this@InMemoryWorkStreamRepository.snapshots.lastOrNull { it.workStreamId == streamId }

        override suspend fun getProject(id: String) = projects[id] ?: projectMap[id]
        override suspend fun getTask(id: String) = tasks[id] ?: taskMap[id]
        override suspend fun allTasks(): List<Task> = LinkedHashMap(taskMap).apply { putAll(tasks) }.values.toList()
        override suspend fun allStreams(): List<WorkStream> = LinkedHashMap(streamMap).apply { putAll(streams) }.values.toList()
        override suspend fun saveProject(project: Project) { projects[project.id] = project }
        override suspend fun saveTask(task: Task) { tasks[task.id] = task }
        override suspend fun saveStream(stream: WorkStream) { streams[stream.id] = stream }
        override suspend fun saveCycle(cycle: Cycle) {
            if (cycles.containsKey(cycle.id) || this@InMemoryWorkStreamRepository.cycles.none { it.id == cycle.id }) cycles[cycle.id] = cycle
            else cycleUpdates += cycle
        }
        override suspend fun saveFocusSession(session: FocusSession) {
            if (sessions.containsKey(session.id) || this@InMemoryWorkStreamRepository.sessions.none { it.id == session.id }) sessions[session.id] = session
            else sessionUpdates += session
        }
        override suspend fun saveSnapshot(snapshot: ContextSnapshot) { snapshots += snapshot }
        override suspend fun stagesOf(workStreamId: String): List<ExternalStage> =
            ExternalStages.of(LinkedHashMap(stageMap).apply { putAll(stages) }.values, workStreamId)
        override suspend fun saveStage(stage: ExternalStage) { stages[stage.id] = stage }
        override suspend fun appendEvent(event: WorkStreamEvent) { events += event }
    }
}

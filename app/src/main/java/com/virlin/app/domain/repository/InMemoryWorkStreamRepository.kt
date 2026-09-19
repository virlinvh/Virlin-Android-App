package com.virlin.app.domain.repository

import com.virlin.app.domain.model.AttachmentDocument
import com.virlin.app.domain.model.VoiceDocument
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.ContextSnapshot
import com.virlin.app.domain.model.Cycle
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
    override suspend fun getFocusSessions(streamId: String) = lock.withLock { sessions.filter { it.workStreamId == streamId } }
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
        events.addAll(staged.events)
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
        val captures = LinkedHashMap<String, CaptureItem>()
        val notes = LinkedHashMap<String, NoteDocument>()
        val prompts = LinkedHashMap<String, PromptDocument>()
        val attachments = LinkedHashMap<String, AttachmentDocument>()
        val voices = LinkedHashMap<String, VoiceDocument>()

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
        override suspend fun appendEvent(event: WorkStreamEvent) { events += event }
    }
}

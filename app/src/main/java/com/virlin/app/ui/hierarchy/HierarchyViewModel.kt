package com.virlin.app.ui.hierarchy

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.activity.ProjectActivity
import com.virlin.app.domain.activity.ProjectActivityEntry
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.model.EffectiveExecutionMode
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.FocusInvestment
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.domain.repository.WorkStreamRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * State for the hierarchy screens (Streams' Projects section, Project Detail, WorkStream
 * Detail, Task Detail). Reads ONLY the domain repository; the only UI-owned state is which
 * parent rows are expanded. Every mutation goes through [VirlinActions].
 */
private const val ACTIVITY_PAGE = 100

class HierarchyViewModel(
    private val repository: WorkStreamRepository = VirlinGraph.repository,
    private val actions: VirlinActions = VirlinGraph.actions
) : ViewModel() {

    /** UI-only: expanded parent task ids. Not domain state. */
    private val expanded = MutableStateFlow<Set<String>>(emptySet())

    data class Snapshot(
        val projects: List<Project>,
        val streams: List<WorkStream>,
        val tasks: List<Task>,
        val expanded: Set<String>
    )

    val snapshot: StateFlow<Snapshot> = combine(
        repository.projects, repository.streams, repository.tasks, expanded
    ) { p, s, t, e -> Snapshot(p, s, t, e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            Snapshot(repository.projects.value, repository.streams.value, repository.tasks.value, emptySet()))

    // ------------------------------------------------------------------ derived views

    data class ProjectSummary(val project: Project, val streamCount: Int, val progress: ProgressLabel)

    fun projectSummaries(s: Snapshot): List<ProjectSummary> = s.projects.map { p ->
        ProjectSummary(p, s.streams.count { it.projectId == p.id },
            ProgressCalculator.ofProject(s.tasks, s.streams, p.id).toLabel(compact = true))
    }

    data class StreamSummary(val stream: WorkStream, val progress: ProgressLabel, val currentTask: Task?)

    fun streamSummary(s: Snapshot, stream: WorkStream) = StreamSummary(
        stream,
        ProgressCalculator.ofWorkStream(s.tasks, stream.id).toLabel(compact = true),
        stream.activeTaskId?.let { id -> s.tasks.firstOrNull { it.id == id } }
    )

    /**
     * PROJECT KNOWLEDGE — the captures already attached to a project (`CaptureItem.projectId`).
     * A read-only projection of the existing capture store: nothing new is created or copied here.
     */
    val captures: StateFlow<List<com.virlin.app.domain.model.CaptureItem>> = repository.captures
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.captures.value)

    // ------------------------------------------------------------------ Project Knowledge

    /**
     * PROJECT KNOWLEDGE — one row per saved capture filed to this project, with the document
     * that backs it already resolved. It is the SAME capture Activity shows: Knowledge is where
     * an item lives, Activity is when it happened, and neither copies the other's data.
     */
    data class KnowledgeEntry(
        val capture: com.virlin.app.domain.model.CaptureItem,
        val attachment: com.virlin.app.domain.model.AttachmentDocument? = null,
        val voice: com.virlin.app.domain.model.VoiceDocument? = null,
        val note: com.virlin.app.domain.model.NoteDocument? = null,
        val prompt: com.virlin.app.domain.model.PromptDocument? = null
    )

    private val knowledgeProject = MutableStateFlow<String?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val knowledge: StateFlow<List<KnowledgeEntry>> =
        combine(knowledgeProject, repository.captures) { projectId, captures -> projectId to captures }
            .flatMapLatest { (projectId, captures) ->
                kotlinx.coroutines.flow.flow {
                    if (projectId == null) { emit(emptyList()); return@flow }
                    // Archived items have left Knowledge; they stay in Activity, which is history.
                    val mine = captures.filter {
                        it.projectId == projectId && it.status != com.virlin.app.domain.model.CaptureStatus.ARCHIVED
                    }
                    val ids = mine.map { it.id }
                    // Four batched reads for the whole library — never one per row.
                    val attachments = repository.getAttachmentsByCaptureIds(ids)
                    val voices = repository.getVoicesByCaptureIds(ids)
                    val notes = repository.getNotesByCaptureIds(ids)
                    val prompts = repository.getPromptsByCaptureIds(ids)
                    emit(mine.map {
                        KnowledgeEntry(it, attachments[it.id], voices[it.id], notes[it.id], prompts[it.id])
                    })
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openKnowledge(projectId: String) = knowledgeProject.update {
        if (it == projectId) it else projectId
    }

    /**
     * Re-file a saved item. Only its context changes: the bytes on disk, its id and the
     * Activity entry that recorded when it was captured are all untouched.
     */
    fun moveCapture(captureId: String, projectId: String, workStreamId: String?, taskId: String?) =
        dispatch("moveCapture") {
            actions.attachCapture(
                captureId,
                com.virlin.app.domain.action.CaptureContext(projectId, workStreamId, taskId)
            )
        }

    /**
     * Re-file several saved items in ONE repository transaction: either the whole selection
     * moves or none of it does. A failure is surfaced to the screen rather than swallowed.
     */
    fun moveCaptures(captureIds: Set<String>, projectId: String, workStreamId: String?, taskId: String?) {
        viewModelScope.launch {
            val result = actions.attachCaptures(
                captureIds,
                com.virlin.app.domain.action.CaptureContext(projectId, workStreamId, taskId),
                projectId
            )
            _knowledgeMessage.value = when (result) {
                is ActionResult.Success ->
                    if (result.value.size == 1) "1 item moved" else "${result.value.size} items moved"
                is ActionResult.Rejected -> "Nothing moved: ${result.reason}"
                is ActionResult.NotFound -> "Nothing moved: an item no longer exists"
                is ActionResult.Failure -> "Nothing moved: the move could not be saved"
            }
        }
    }

    private val _knowledgeMessage = MutableStateFlow<String?>(null)
    /** One-shot feedback for the Knowledge screen; cleared once shown. */
    val knowledgeMessage: StateFlow<String?> = _knowledgeMessage.asStateFlow()
    fun clearKnowledgeMessage() { _knowledgeMessage.value = null }

    /**
     * Re-place a task: into one of this project's WorkStreams, or standalone. The SAME task
     * record moves — its id, status and contribution to progress are unchanged — so Now and the
     * project's totals keep reading the one canonical task.
     */
    fun placeTask(taskId: String, workStreamId: String?) {
        viewModelScope.launch {
            _knowledgeMessage.value = when (val r = actions.placeTask(taskId, workStreamId)) {
                is ActionResult.Success ->
                    if (workStreamId == null) "Task moved out of its workstream" else "Task moved"
                is ActionResult.Rejected -> "Task not moved: ${r.reason}"
                is ActionResult.NotFound -> "Task not moved: that workstream no longer exists"
                is ActionResult.Failure -> "Task not moved: the change could not be saved"
            }
        }
    }

    /**
     * A mixed selection: saved items and tasks re-placed together. Each kind goes through its
     * own atomic action; if the knowledge move fails, the task moves are not attempted, and the
     * screen is told what actually happened rather than assuming success.
     */
    fun organize(
        projectId: String,
        captureIds: Set<String>,
        taskIds: Set<String>,
        workStreamId: String?
    ) {
        viewModelScope.launch {
            if (captureIds.isNotEmpty()) {
                val moved = actions.attachCaptures(
                    captureIds,
                    com.virlin.app.domain.action.CaptureContext(projectId, workStreamId, null),
                    projectId
                )
                if (moved !is ActionResult.Success) {
                    _knowledgeMessage.value = "Nothing moved: the saved items could not be re-filed"
                    return@launch
                }
            }
            val failed = taskIds.count { actions.placeTask(it, workStreamId) !is ActionResult.Success }
            _knowledgeMessage.value = when {
                failed == 0 -> "${captureIds.size + taskIds.size} moved"
                failed == taskIds.size -> "Saved items moved; no task could be moved"
                else -> "Saved items moved; $failed of ${taskIds.size} tasks could not be"
            }
        }
    }

    /** The app's retention policy for a saved item: archived, recoverable, never destroyed. */
    fun archiveCapture(captureId: String) = dispatch("archiveCapture") { actions.archiveCapture(captureId) }

    /**
     * Duplicate: a NEW saved item with its own id, filed where the original is, built from the
     * same document through the same create actions. An attachment's bytes are copied so the two
     * items never share one file.
     */
    fun duplicateCapture(context: android.content.Context, captureId: String) =
        dispatch("duplicateCapture") {
            val entry = knowledge.value.firstOrNull { it.capture.id == captureId }
                ?: return@dispatch ActionResult.NotFound(captureId)
            val source = entry.capture
            val where = com.virlin.app.domain.action.CaptureContext(
                source.projectId, source.workStreamId, source.taskId
            )
            val title = (source.title ?: "Item") + " (copy)"
            when {
                entry.prompt != null -> actions.createPrompt(
                    title, entry.prompt.description, entry.prompt.tags, entry.prompt.blocks, where
                )
                entry.note != null -> actions.createTextNote(title, entry.note.blocks, where)
                entry.attachment != null -> {
                    val newId = "copy-" + java.util.UUID.randomUUID().toString().take(8)
                    val original = com.virlin.app.data.attachment.AttachmentFileStore
                        .resolve(context, entry.attachment.relativePath)
                    val imported = com.virlin.app.data.attachment.AttachmentFileStore.importFromUri(
                        context, android.net.Uri.fromFile(original), newId, entry.attachment.displayName
                    )
                    actions.createAttachment(
                        displayName = title, mimeType = entry.attachment.mimeType,
                        sizeBytes = imported.sizeBytes, relativePath = imported.relativePath,
                        kind = entry.attachment.kind, context = where
                    )
                }
                entry.voice != null -> {
                    val newCaptureId = "copy-" + java.util.UUID.randomUUID().toString().take(8)
                    val clips = entry.voice.sortedClips().map { clip ->
                        val target = com.virlin.app.data.voice.VoiceFileStore
                            .clipFile(context, newCaptureId, clip.id)
                        target.parentFile?.mkdirs()
                        com.virlin.app.data.voice.VoiceFileStore
                            .resolve(context, clip.relativePath).copyTo(target, overwrite = true)
                        clip.copy(relativePath = com.virlin.app.data.voice.VoiceFileStore
                            .relativePath(newCaptureId, clip.id))
                    }
                    actions.createVoice(title, clips, where, captureId = newCaptureId)
                }
                else -> actions.createCapture(
                    com.virlin.app.domain.action.CreateCapture(
                        type = source.type, content = source.content, title = title,
                        sourceUrl = source.sourceUrl, context = where
                    )
                )
            }
        }

    // ------------------------------------------------------------------ Stream task page

    /**
     * Add a task or subtask at a chosen position among its siblings. Creating with INHERIT
     * stores INHERIT: an explicit mode is never set on the child's behalf.
     */
    fun addStreamTask(
        streamId: String,
        parentId: String?,
        title: String,
        afterId: String?,
        preference: ExecutionPreference
    ) {
        viewModelScope.launch {
            val created = actions.createTask(
                CreateTask(
                    title = title, workStreamId = streamId, parentTaskId = parentId,
                    executionPreference = preference
                )
            )
            if (created !is ActionResult.Success) {
                _knowledgeMessage.value = "Task not added"
                return@launch
            }
            // Placement is a move, so one rule decides sibling order everywhere.
            val placed = actions.moveTasks(listOf(created.value.id), parentId, afterId, streamId)
            if (placed !is ActionResult.Success) _knowledgeMessage.value = "Added, but not placed where you asked"
        }
    }

    fun renameTask(taskId: String, title: String) =
        dispatch("renameTask") {
            actions.updateTask(
                taskId,
                com.virlin.app.domain.action.TaskUpdate(
                    title = com.virlin.app.domain.action.Field.Set(title)
                )
            )
        }

    fun moveTasks(taskIds: List<String>, destinationParentId: String?, afterId: String?, streamId: String) {
        viewModelScope.launch {
            _knowledgeMessage.value = when (val r = actions.moveTasks(taskIds, destinationParentId, afterId, streamId)) {
                is ActionResult.Success -> null
                is ActionResult.Rejected -> when (r.reason) {
                    com.virlin.app.domain.action.DomainError.CyclicParent ->
                        "A task cannot move inside itself"
                    else -> "Nothing moved: ${r.reason}"
                }
                is ActionResult.NotFound -> "Nothing moved: that task no longer exists"
                is ActionResult.Failure -> "Nothing moved: the change could not be saved"
            }
        }
    }

    fun duplicateTask(taskId: String) {
        viewModelScope.launch {
            _knowledgeMessage.value = when (actions.duplicateTask(taskId)) {
                is ActionResult.Success -> "Duplicated"
                else -> "Not duplicated"
            }
        }
    }

    // ------------------------------------------------------------------ Tags and steps

    /** This project's tags, observed. A tag from another project can never appear here. */
    val tags: StateFlow<List<com.virlin.app.domain.model.ProjectTag>> = repository.tags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.tags.value)

    val tagLinks: StateFlow<List<com.virlin.app.domain.model.TagLink>> = repository.tagLinks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.tagLinks.value)

    val taskSteps: StateFlow<List<com.virlin.app.domain.model.TaskStep>> = repository.taskSteps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.taskSteps.value)

    fun createTag(projectId: String, name: String) {
        viewModelScope.launch {
            _knowledgeMessage.value = when (val r = actions.createTag(projectId, name)) {
                is ActionResult.Success -> "#${r.value.name} ready"
                is ActionResult.Rejected -> "Tag not created: ${r.reason}"
                else -> "Tag not created"
            }
        }
    }

    fun renameTag(tagId: String, name: String) {
        viewModelScope.launch {
            _knowledgeMessage.value = when (val r = actions.renameTag(tagId, name)) {
                is ActionResult.Success -> "Renamed to #${r.value.name}"
                is ActionResult.Rejected -> when (r.reason) {
                    is com.virlin.app.domain.action.DomainError.DuplicateTagName ->
                        "That name is already used in this project"
                    else -> "Not renamed: ${r.reason}"
                }
                else -> "Not renamed"
            }
        }
    }

    fun mergeTags(fromTagId: String, intoTagId: String) {
        viewModelScope.launch {
            _knowledgeMessage.value = when (val r = actions.mergeTags(fromTagId, intoTagId)) {
                is ActionResult.Success -> "Merged into #${r.value.name}"
                else -> "Tags not merged"
            }
        }
    }

    fun deleteTag(tagId: String) {
        viewModelScope.launch {
            _knowledgeMessage.value = when (actions.deleteTag(tagId)) {
                is ActionResult.Success -> "Tag deleted. Nothing it labelled was removed."
                else -> "Tag not deleted"
            }
        }
    }

    /** One transaction across the whole selection; the screen keeps it until this succeeds. */
    fun setTags(projectId: String, tagIds: Set<String>, captureIds: Set<String>, taskIds: Set<String>) {
        viewModelScope.launch {
            _knowledgeMessage.value = when (val r = actions.setTags(projectId, tagIds, captureIds, taskIds)) {
                is ActionResult.Success -> "Tags saved"
                is ActionResult.Rejected -> "Tags not saved: ${r.reason}"
                is ActionResult.NotFound -> "Tags not saved: an item no longer exists"
                is ActionResult.Failure -> "Tags not saved"
            }
            _tagSaveSucceeded.value = true
        }
    }

    private val _tagSaveSucceeded = MutableStateFlow(false)
    val tagSaveSucceeded: StateFlow<Boolean> = _tagSaveSucceeded.asStateFlow()
    fun clearTagSaveFlag() { _tagSaveSucceeded.value = false }

    fun addStep(taskId: String, text: String) = dispatch("addStep") { actions.addStep(taskId, text) }
    fun setStepDone(stepId: String, done: Boolean) = dispatch("setStepDone") { actions.setStepDone(stepId, done) }
    fun editStep(stepId: String, text: String) = dispatch("editStep") { actions.editStep(stepId, text) }
    fun deleteStep(stepId: String) = dispatch("deleteStep") { actions.deleteStep(stepId) }
    fun moveStep(stepId: String, newIndex: Int) = dispatch("moveStep") { actions.moveStep(stepId, newIndex) }

    /**
     * A saved checklist becomes ONE canonical task carrying its steps, with the capture marked
     * ORGANIZED so it can never be converted twice. The checklist itself stays what it is: a
     * capture, not a task.
     */
    fun convertChecklistToTask(projectId: String, captureId: String, workStreamId: String?) {
        viewModelScope.launch {
            val capture = repository.getCapture(captureId)
            if (capture == null) { _knowledgeMessage.value = "That checklist no longer exists"; return@launch }
            if (capture.convertedTaskId != null) {
                _knowledgeMessage.value = "Already converted — opening the existing task"
                return@launch
            }
            val blocks = repository.getNoteByCaptureId(captureId)?.blocks
                ?: repository.getPromptByCaptureId(captureId)?.blocks
                ?: emptyList()
            val boxes = com.virlin.app.domain.activity.NoteBlockShape.checkboxes(blocks)
            val created = actions.convertCaptureToTask(
                captureId,
                com.virlin.app.domain.action.CaptureTaskTarget(
                    projectId = projectId, workStreamId = workStreamId ?: capture.workStreamId
                )
            )
            if (created !is ActionResult.Success) {
                _knowledgeMessage.value = "Not converted: the task could not be created"
                return@launch
            }
            // The ordered steps come across with their saved state.
            boxes.forEach { box ->
                val step = actions.addStep(created.value.id, box.plainText.ifBlank { "Step" })
                if (box.checked && step is ActionResult.Success) actions.setStepDone(step.value.id, true)
            }
            _knowledgeMessage.value = "Converted to a task with ${boxes.size} steps"
        }
    }

    // ------------------------------------------------------------------ Project Activity

    data class ActivityState(
        val entries: List<ProjectActivityEntry> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val hasMore: Boolean = false
    )

    private data class ActivityRequest(val projectId: String?, val limit: Int)

    /** One page of history at a time; [loadMoreActivity] grows it. */
    private val activityRequest = MutableStateFlow(ActivityRequest(null, ACTIVITY_PAGE))

    /**
     * PROJECT ACTIVITY — the chronological record for one project, rebuilt whenever the data it
     * reads changes. Events for every stream of the project come back in ONE batched query and
     * every capture document in four more; nothing is read per timeline row.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activity: StateFlow<ActivityState> =
        combine(activityRequest, repository.projects, repository.streams, repository.tasks, repository.captures) {
            request, projects, streams, tasks, captures -> Triple(request, projects, Triple(streams, tasks, captures))
        }.flatMapLatest { (request, projects, rest) ->
            val (streams, tasks, captures) = rest
            kotlinx.coroutines.flow.flow {
                val project = projects.firstOrNull { it.id == request.projectId }
                if (project == null) { emit(ActivityState()); return@flow }
                emit(ActivityState(loading = true))
                emit(
                    try {
                        val projectStreamIds = streams.filter { it.projectId == project.id }.map { it.id }
                        val events = repository.getEventsForStreams(projectStreamIds, request.limit)
                        val projectCaptureIds = captures.filter { it.projectId == project.id }.map { it.id }
                        val documents = ProjectActivity.Documents(
                            notes = repository.getNotesByCaptureIds(projectCaptureIds),
                            prompts = repository.getPromptsByCaptureIds(projectCaptureIds),
                            attachments = repository.getAttachmentsByCaptureIds(projectCaptureIds),
                            voices = repository.getVoicesByCaptureIds(projectCaptureIds)
                        )
                        ActivityState(
                            entries = ProjectActivity.build(project, streams, tasks, events, captures, documents),
                            hasMore = events.size >= request.limit
                        )
                    } catch (e: Exception) {
                        Log.w("Hierarchy", "activity failed", e)
                        ActivityState(error = "Activity could not be loaded.")
                    }
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityState())

    fun openActivity(projectId: String) = activityRequest.update {
        if (it.projectId == projectId) it else ActivityRequest(projectId, ACTIVITY_PAGE)
    }

    fun loadMoreActivity() = activityRequest.update { it.copy(limit = it.limit + ACTIVITY_PAGE) }

    /** Read one capture's stored text for the Activity detail's Copy action. */
    suspend fun captureText(captureId: String): String? {
        val capture = repository.getCapture(captureId) ?: return null
        return repository.getPromptByCaptureId(captureId)?.blocks?.joinToString("\n") { it.plainText }
            ?: repository.getNoteByCaptureId(captureId)?.blocks?.joinToString("\n") { it.plainText }
            ?: capture.content.takeIf { it.isNotBlank() }
            ?: capture.sourceUrl
    }

    fun streamRows(s: Snapshot, stream: WorkStream): List<TaskRow> =
        HierarchyPresentation.rows(s.tasks, stream.id, stream.activeTaskId, s.expanded)

    fun subtaskRows(s: Snapshot, task: Task): List<TaskRow> {
        val stream = task.workStreamId?.let { id -> s.streams.firstOrNull { it.id == id } }
        return HierarchyPresentation.rows(s.tasks, null, stream?.activeTaskId, s.expanded, rootTaskId = task.id)
    }

    fun breadcrumb(s: Snapshot, task: Task): HierarchyPresentation.Breadcrumb {
        val stream = task.workStreamId?.let { id -> s.streams.firstOrNull { it.id == id } }
        val project = (stream?.projectId ?: task.projectId)?.let { id -> s.projects.firstOrNull { it.id == id } }
        return HierarchyPresentation.breadcrumb(task, s.tasks, stream, project)
    }

    /** Human focus attributed to this task, derived from FocusSession timestamps. */
    suspend fun focusedOn(task: Task): Duration? {
        val wsId = task.workStreamId ?: return null
        val sessions = repository.getFocusSessions(wsId)
        val total = FocusInvestment.total(sessions, task.id, VirlinGraph.clock.now())
        return total.takeIf { !it.isZero && !it.isNegative }
    }

    suspend fun nextCandidate(streamId: String): Task? = actions.nextTaskCandidate(streamId).let {
        (it as? ActionResult.Success)?.value
    }

    // ------------------------------------------------------------------ UI-only state

    fun toggleExpanded(taskId: String) = expanded.update { if (taskId in it) it - taskId else it + taskId }
    fun expandPathTo(taskId: String?) = expanded.update { it + HierarchyPresentation.ancestorIds(snapshot.value.tasks, taskId) }

    // ------------------------------------------------------------------ intents → VirlinActions

    fun completeTask(id: String) = dispatch("completeTask") { actions.completeTask(id) }

    /**
     * PHASE 10 — delegate this work item to an external actor. The same WorkStream becomes the
     * Working For You item; no second task and no second identity is created.
     */
    fun delegate(
        workStreamId: String,
        workItemId: String?,
        actor: com.virlin.app.domain.model.ExternalActor,
        instruction: String,
        checkInMinutes: Long?,
        stages: List<com.virlin.app.domain.action.NewExternalStage>
    ) = dispatch("startExternalWork") {
        actions.startExternalWork(
            com.virlin.app.domain.action.StartExternalWork(
                workStreamId = workStreamId,
                actor = actor,
                instruction = instruction.takeIf { it.isNotBlank() },
                workItemId = workItemId,
                checkInMinutes = checkInMinutes,
                stages = stages
            )
        )
    }
    fun cancelTask(id: String) = dispatch("cancelTask") { actions.cancelTask(id) }

    /** The existing whole-project completion. Virlin has no separate archive state. */
    fun completeProject(id: String) = dispatch("completeProject") { actions.completeProject(id) }
    fun setActiveTask(streamId: String, taskId: String?) = dispatch("setActiveTask") { actions.setActiveTask(streamId, taskId) }

    /**
     * PHASE 09 — start human focus on this work item. Resolving is a pure read, so an active focus
     * elsewhere surfaces as a typed [pendingSwitch] the user confirms; nothing is written until then.
     */
    private val _pendingSwitch = MutableStateFlow<PendingSwitch?>(null)
    val pendingSwitch: StateFlow<PendingSwitch?> = _pendingSwitch.asStateFlow()

    /** A focus request that would displace work the user is already doing. */
    data class PendingSwitch(val target: com.virlin.app.domain.action.FocusTarget, val currentTitle: String)

    fun focusWorkItem(workItemId: String) {
        viewModelScope.launch {
            when (val r = actions.resolveFocusTarget(workItemId)) {
                is com.virlin.app.domain.action.ActionResult.Success -> {
                    val target = r.value
                    val currentTitle = target.displacedWorkItemId?.let { id -> repository.getTask(id)?.title }
                        ?: target.displacedStreamId?.let { id -> repository.getStream(id)?.title }
                    if (target.isSwitch && currentTitle != null) _pendingSwitch.value = PendingSwitch(target, currentTitle)
                    else start(workItemId)
                }
                else -> android.util.Log.d("HierarchyViewModel", "focus rejected: $r")
            }
        }
    }
    fun confirmSwitch() { _pendingSwitch.value?.let { p -> _pendingSwitch.value = null; start(p.target.workItem.id) } }
    fun cancelSwitch() { _pendingSwitch.value = null }
    private fun start(workItemId: String) = dispatch("startFocus") { actions.startFocus(workItemId) }

    /** Quick creation (Phase 08): a Project, and a WorkStream inside one. Same action layer as the Agent. */
    fun addProject(title: String) = dispatch("createProject") {
        actions.createProject(com.virlin.app.domain.action.CreateProject(title = title))
    }
    fun addWorkStream(projectId: String, title: String) = dispatch("createWorkStream") {
        actions.createWorkStream(com.virlin.app.domain.action.CreateWorkStream(title = title, projectId = projectId))
    }

    fun addTask(streamId: String, title: String, effort: Duration?) = dispatch("addTask") {
        actions.createTask(CreateTask(title = title, workStreamId = streamId, estimatedEffort = effort))
    }
    fun addStandaloneTask(projectId: String, title: String, effort: Duration?) = dispatch("addStandaloneTask") {
        actions.createTask(CreateTask(title = title, projectId = projectId, estimatedEffort = effort))
    }
    fun addSubtask(parentId: String, title: String, effort: Duration?) = dispatch("addSubtask") {
        actions.addSubtask(parentId, title, effort).also { r ->
            if (r is ActionResult.Success) expanded.update { it + parentId }
        }
    }

    // ---- Project identity icon (Phase 3). Priority custom → built-in → auto is resolved by ProjectIconSelection;
    // these only change the persisted choice. Choosing a built-in or Auto also drops the custom image file.
    fun selectBuiltInIcon(context: android.content.Context, projectId: String, iconId: String) = dispatch("selectBuiltInIcon") {
        com.virlin.app.data.projecticon.ProjectIconStore.delete(context.applicationContext, projectId)
        actions.updateProject(projectId, com.virlin.app.domain.action.ProjectUpdate(
            iconId = com.virlin.app.domain.action.Field.Set(iconId), iconPath = com.virlin.app.domain.action.Field.Clear))
    }
    fun setCustomIcon(projectId: String, relativePath: String) = dispatch("setCustomIcon") {
        actions.updateProject(projectId, com.virlin.app.domain.action.ProjectUpdate(iconPath = com.virlin.app.domain.action.Field.Set(relativePath)))
    }
    fun removeCustomIcon(context: android.content.Context, projectId: String) = dispatch("removeCustomIcon") {
        com.virlin.app.data.projecticon.ProjectIconStore.delete(context.applicationContext, projectId)
        actions.updateProject(projectId, com.virlin.app.domain.action.ProjectUpdate(iconPath = com.virlin.app.domain.action.Field.Clear))
    }
    fun useAutoIcon(context: android.content.Context, projectId: String) = dispatch("useAutoIcon") {
        com.virlin.app.data.projecticon.ProjectIconStore.delete(context.applicationContext, projectId)
        actions.updateProject(projectId, com.virlin.app.domain.action.ProjectUpdate(
            iconId = com.virlin.app.domain.action.Field.Clear, iconPath = com.virlin.app.domain.action.Field.Clear))
    }

    fun setProjectExecutionDefault(projectId: String, mode: EffectiveExecutionMode) =
        dispatch("setProjectExecutionDefault") { actions.setProjectExecutionDefault(projectId, mode) }

    fun setWorkStreamExecutionPreference(streamId: String, preference: ExecutionPreference) =
        dispatch("setWorkStreamExecutionPreference") { actions.setWorkStreamExecutionPreference(streamId, preference) }

    fun resetWorkStreamExecutionPreference(streamId: String) =
        dispatch("resetWorkStreamExecutionPreference") { actions.resetWorkStreamExecutionPreference(streamId) }

    fun setTaskExecutionPreference(taskId: String, preference: ExecutionPreference) =
        dispatch("setTaskExecutionPreference") { actions.setTaskExecutionPreference(taskId, preference) }

    fun resetTaskExecutionPreference(taskId: String) =
        dispatch("resetTaskExecutionPreference") { actions.resetTaskExecutionPreference(taskId) }

    private fun dispatch(name: String, block: suspend () -> ActionResult<*>) {
        viewModelScope.launch {
            when (val r = block()) {
                is ActionResult.Success -> Log.d(TAG, "$name: ok")
                is ActionResult.Rejected -> Log.d(TAG, "$name: rejected ${r.reason}")
                is ActionResult.NotFound -> Log.w(TAG, "$name: not found ${r.streamId}")
                is ActionResult.Failure -> Log.e(TAG, "$name: failure", r.cause)
            }
        }
    }

    private companion object { const val TAG = "HierarchyActions" }
}

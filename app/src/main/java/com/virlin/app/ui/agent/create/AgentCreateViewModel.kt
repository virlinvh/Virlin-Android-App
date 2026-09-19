package com.virlin.app.ui.agent.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateProject
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.CreateWorkStream
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.model.EffectiveExecutionMode
import com.virlin.app.domain.model.ExecutionModeResolver
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.ui.hierarchy.HierarchyPresentation
import com.virlin.app.ui.hierarchy.TaskRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration

/** What the user is creating. There are no Stage/Step types — a "subtask" is a Task with a parent. */
enum class CreateKind { PROJECT, WORKSTREAM, TASK }

/** Where a new Task lives: a WorkStream (project optional) or directly in a Project (standalone). */
enum class TaskOwnerKind { WORKSTREAM, PROJECT }

/** The item just persisted, offered contextual next actions. */
sealed interface Created {
    data class ProjectCreated(val project: Project) : Created
    data class WorkStreamCreated(val stream: WorkStream) : Created
    data class TaskCreated(val task: Task) : Created
}

/**
 * Ephemeral form state (never persisted; lost on process death by design). Everything the
 * pickers show comes from the repository; what the user typed lives here until CREATE.
 */
data class AgentCreateForm(
    val kind: CreateKind? = null,
    val title: String = "",
    /** Project picker: null = No Project (valid). */
    val projectId: String? = null,
    /**
     * WorkStream / Task execution preference. Projectless WorkStreams never use INHERIT
     * (UI hides that chip). Tasks default to INHERIT.
     */
    val executionPreference: ExecutionPreference = ExecutionPreference.HUMAN,
    /** Project create only — root default for descendants. */
    val projectDefaultExecution: EffectiveExecutionMode = EffectiveExecutionMode.HUMAN,
    val ownerKind: TaskOwnerKind = TaskOwnerKind.WORKSTREAM,
    val workStreamId: String? = null,
    val parentTaskId: String? = null,
    /** Optional estimate in minutes for tasks. */
    val estimateMinutes: String = "",
    val expanded: Set<String> = emptySet(),
    val created: Created? = null,
    val error: String? = null
)

/** Projection for rendering: the form plus the persisted choices it can pick from. */
data class AgentCreateState(
    val form: AgentCreateForm,
    val projects: List<Project>,
    val workStreams: List<WorkStream>,
    val tasks: List<Task> = emptyList(),
    /** Task rows of the selected owner (WorkStream or standalone Project tasks) for the parent picker. */
    val parentRows: List<TaskRow>,
    val selectedProjectTitle: String?,
    val selectedWorkStreamTitle: String?,
    val selectedParentTitle: String?
)

/**
 * Agent CREATE: deterministic structured creation of Project / WorkStream / Task through the
 * SAME `VirlinActions` as everything else. Holds only form state; each CREATE persists one
 * item atomically through the repository before any success is shown. No free text is
 * interpreted, nothing is auto-focused, no created task becomes active by itself.
 */
class AgentCreateViewModel(
    private val actions: VirlinActions = VirlinGraph.actions,
    private val repository: WorkStreamRepository = VirlinGraph.repository
) : ViewModel() {

    private val _form = MutableStateFlow(AgentCreateForm())
    val form: StateFlow<AgentCreateForm> = _form

    val state: StateFlow<AgentCreateState> = combine(repository.projects, repository.streams, repository.tasks, _form) { p, s, t, f ->
        project(p, s, t, f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
        project(repository.projects.value, repository.streams.value, repository.tasks.value, _form.value))

    private fun project(p: List<Project>, s: List<WorkStream>, t: List<Task>, f: AgentCreateForm): AgentCreateState {
        val streams = s.filter { !it.state.isTerminal }
        val rows = when (f.ownerKind) {
            TaskOwnerKind.WORKSTREAM -> f.workStreamId?.let { id -> HierarchyPresentation.rows(t, id, s.firstOrNull { it.id == id }?.activeTaskId, f.expanded) }
            TaskOwnerKind.PROJECT -> f.projectId?.let { pid -> HierarchyPresentation.rows(t.filter { it.projectId == pid && it.workStreamId == null }, null, null, f.expanded) }
        }.orEmpty()
        return AgentCreateState(
            form = f, projects = p, workStreams = streams, tasks = t, parentRows = rows,
            selectedProjectTitle = f.projectId?.let { id -> p.firstOrNull { it.id == id }?.title },
            selectedWorkStreamTitle = f.workStreamId?.let { id -> s.firstOrNull { it.id == id }?.title },
            selectedParentTitle = f.parentTaskId?.let { id -> t.firstOrNull { it.id == id }?.title }
        )
    }

    // ------------------------------------------------------------------ form edits (ephemeral)

    fun choose(kind: CreateKind) {
        _form.update {
            AgentCreateForm(
                kind = kind,
                executionPreference = if (kind == CreateKind.TASK) ExecutionPreference.INHERIT else ExecutionPreference.HUMAN
            )
        }
    }
    fun reset() { _form.value = AgentCreateForm() }
    fun setTitle(v: String) { _form.update { it.copy(title = v, error = null) } }
    fun setProject(id: String?) {
        _form.update { f ->
            val pref = if (id == null && f.executionPreference == ExecutionPreference.INHERIT)
                ExecutionPreference.HUMAN else f.executionPreference
            f.copy(projectId = id, parentTaskId = null, executionPreference = pref, error = null)
        }
    }
    fun setExecutionPreference(p: ExecutionPreference) { _form.update { it.copy(executionPreference = p) } }
    /** Compatibility for tests / older call sites that pass HUMAN/EXTERNAL only. */
    fun setMode(m: EffectiveExecutionMode) { setExecutionPreference(m.toPreference()) }
    fun setProjectDefaultExecution(m: EffectiveExecutionMode) { _form.update { it.copy(projectDefaultExecution = m) } }
    fun setOwnerKind(k: TaskOwnerKind) { _form.update { it.copy(ownerKind = k, parentTaskId = null, error = null) } }
    fun setWorkStream(id: String?) {
        val ws = id?.let { wid -> repository.streams.value.firstOrNull { it.id == wid } }
        _form.update { it.copy(workStreamId = id, projectId = ws?.projectId, parentTaskId = null, expanded = HierarchyPresentation.ancestorIds(repository.tasks.value, ws?.activeTaskId), error = null) }
    }
    fun setParent(id: String?) { _form.update { it.copy(parentTaskId = if (it.parentTaskId == id) null else id, error = null) } }
    fun toggleExpanded(id: String) { _form.update { it.copy(expanded = if (id in it.expanded) it.expanded - id else it.expanded + id) } }
    fun setEstimate(v: String) { _form.update { it.copy(estimateMinutes = v.filter(Char::isDigit)) } }

    // ------------------------------------------------------------------ contextual entry (chaining)

    /** From a created Project: add a WorkStream with the project preselected. */
    fun addWorkStreamTo(projectId: String) { _form.value = AgentCreateForm(kind = CreateKind.WORKSTREAM, projectId = projectId) }
    /** From a Project: standalone task. */
    fun addStandaloneTaskTo(projectId: String) { _form.value = AgentCreateForm(kind = CreateKind.TASK, ownerKind = TaskOwnerKind.PROJECT, projectId = projectId) }
    /** From a WorkStream: root task. */
    fun addTaskTo(workStreamId: String) { _form.value = AgentCreateForm(kind = CreateKind.TASK); setWorkStream(workStreamId) }
    /** From a Task: child task (ownership inherited by the domain). */
    fun addSubtaskTo(task: Task) {
        _form.value = AgentCreateForm(kind = CreateKind.TASK, projectId = task.projectId,
            ownerKind = if (task.workStreamId != null) TaskOwnerKind.WORKSTREAM else TaskOwnerKind.PROJECT,
            workStreamId = task.workStreamId, parentTaskId = task.id,
            expanded = HierarchyPresentation.ancestorIds(repository.tasks.value, task.id) + task.id)
    }

    // ------------------------------------------------------------------ CREATE (one atomic action each)

    fun create() {
        val f = _form.value
        val kind = f.kind ?: return
        val title = f.title.trim()
        if (title.isBlank()) { _form.update { it.copy(error = "Give it a name first") }; return }
        viewModelScope.launch {
            when (kind) {
                CreateKind.PROJECT -> report(actions.createProject(CreateProject(title = title, defaultExecutionMode = f.projectDefaultExecution))) { Created.ProjectCreated(it) }
                CreateKind.WORKSTREAM -> report(actions.createWorkStream(CreateWorkStream(title = title, projectId = f.projectId, executionPreference = f.executionPreference))) { Created.WorkStreamCreated(it) }
                CreateKind.TASK -> {
                    val effort = f.estimateMinutes.toLongOrNull()?.takeIf { it > 0 }?.let(Duration::ofMinutes)
                    val request = when {
                        f.parentTaskId != null -> CreateTask(title = title, parentTaskId = f.parentTaskId, estimatedEffort = effort, executionPreference = f.executionPreference)
                        f.ownerKind == TaskOwnerKind.WORKSTREAM && f.workStreamId != null -> CreateTask(title = title, workStreamId = f.workStreamId, estimatedEffort = effort, executionPreference = f.executionPreference)
                        f.ownerKind == TaskOwnerKind.PROJECT && f.projectId != null -> CreateTask(title = title, projectId = f.projectId, estimatedEffort = effort, executionPreference = f.executionPreference)
                        else -> null
                    }
                    if (request == null) { _form.update { it.copy(error = "Couldn't create that · Choose a WorkStream or Project") }; return@launch }
                    report(actions.createTask(request)) { Created.TaskCreated(it) }
                }
            }
        }
    }

    private fun <T> report(r: ActionResult<T>, wrap: (T) -> Created) {
        when (r) {
            is ActionResult.Success -> _form.update { it.copy(created = wrap(r.value), error = null, title = "") }
            is ActionResult.Rejected -> _form.update { it.copy(error = message(r.reason)) }
            is ActionResult.NotFound -> _form.update { it.copy(error = "Couldn't create that · that item no longer exists") }
            is ActionResult.Failure -> _form.update { it.copy(error = "Couldn't create that · something went wrong") }
        }
    }

    private fun message(e: DomainError): String = when (e) {
        DomainError.EmptyTitle -> "Give it a name first"
        is DomainError.ProjectNotFound -> "Couldn't create that · that Project no longer exists"
        is DomainError.TaskNotFound -> "Couldn't create that · the parent task no longer exists"
        DomainError.OwnershipMismatch -> "Couldn't create that · Choose a WorkStream or Project"
        DomainError.CyclicParent, DomainError.SelfParent -> "Couldn't create that · invalid parent"
        DomainError.TaskAlreadyClosed -> "Couldn't create that · the parent task is closed"
        DomainError.ProjectAlreadyDone -> "Couldn't create that · the Project is done"
        DomainError.InheritRequiresProject -> "Choose Human or External · Inherit needs a Project"
        DomainError.CannotChangeExecutionWhileProcessing -> "Finish or reconcile processing first"
        DomainError.NotExternalExecution -> "That work is human — hand off needs external execution"
        else -> "Couldn't create that"
    }

    /** Resolved label for INHERIT chips, e.g. "Inherit — External". */
    fun inheritLabel(form: AgentCreateForm, projects: List<Project>, streams: List<WorkStream>, tasks: List<Task>): String {
        val resolved = when (form.kind) {
            CreateKind.WORKSTREAM -> {
                val default = form.projectId?.let { id -> projects.firstOrNull { it.id == id }?.defaultExecutionMode }
                ExecutionModeResolver.resolveWorkStream(
                    WorkStream(
                        id = "_", title = "_", projectId = form.projectId,
                        executionPreference = ExecutionPreference.INHERIT,
                        state = com.virlin.app.domain.model.WorkStreamState.READY,
                        createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH
                    ),
                    default
                )
            }
            CreateKind.TASK -> {
                val parent = form.parentTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
                val ws = (parent?.workStreamId ?: form.workStreamId)?.let { id -> streams.firstOrNull { it.id == id } }
                val projectDefault = (ws?.projectId ?: form.projectId ?: parent?.projectId)
                    ?.let { id -> projects.firstOrNull { it.id == id }?.defaultExecutionMode }
                val probe = Task(
                    id = "_", title = "_", projectId = form.projectId, workStreamId = form.workStreamId,
                    parentTaskId = form.parentTaskId, executionPreference = ExecutionPreference.INHERIT,
                    createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH
                )
                ExecutionModeResolver.resolveTask(probe, tasks.associateBy { it.id }, ws, projectDefault)
            }
            else -> EffectiveExecutionMode.HUMAN
        }
        return "Inherit — ${resolved.name.lowercase().replaceFirstChar(Char::uppercase)}"
    }

    // ------------------------------------------------------------------ after-create actions (explicit only)

    /** Explicit: make the new task the WorkStream's current task. Only for WorkStream tasks. */
    fun setCurrent(task: Task) {
        val ws = task.workStreamId ?: return
        viewModelScope.launch {
            when (actions.setActiveTask(ws, task.id)) {
                is ActionResult.Success -> _form.update { it.copy(error = null) }
                else -> _form.update { it.copy(error = "Couldn't set that as current") }
            }
        }
    }
    /** Explicit: focus the new WorkStream via the accepted action (displacement applies). */
    fun focusNow(streamId: String) { viewModelScope.launch { actions.focusStream(streamId) } }
}

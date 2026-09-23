package com.virlin.app.ui.hierarchy

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.VirlinGraph
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * State for the hierarchy screens (Streams' Projects section, Project Detail, WorkStream
 * Detail, Task Detail). Reads ONLY the domain repository; the only UI-owned state is which
 * parent rows are expanded. Every mutation goes through [VirlinActions].
 */
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
    fun cancelTask(id: String) = dispatch("cancelTask") { actions.cancelTask(id) }
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

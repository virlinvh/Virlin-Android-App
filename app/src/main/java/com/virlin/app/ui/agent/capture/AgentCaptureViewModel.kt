package com.virlin.app.ui.agent.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CaptureTaskTarget
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which slice of captures the list shows. Default: the Inbox. */
enum class CaptureFilter { INBOX, ORGANIZED, ARCHIVED }

/** Explicit, visible context choice for the next save. None = global Inbox. */
data class CaptureContextChoice(val projectId: String? = null, val workStreamId: String? = null, val taskId: String? = null) {
    val isEmpty: Boolean get() = projectId == null && workStreamId == null && taskId == null
    fun toContext() = CaptureContext(projectId, workStreamId, taskId)
}

/** Ephemeral form + selection. Never persisted; a saved capture is durable the moment SAVE returns. */
data class AgentCaptureForm(
    val type: CaptureType = CaptureType.NOTE,
    /** LINK's optional note (the URL comes from the composer text). */
    val linkNote: String = "",
    val context: CaptureContextChoice = CaptureContextChoice(),
    val contextPickerOpen: Boolean = false,
    val filter: CaptureFilter = CaptureFilter.INBOX,
    val selectedId: String? = null,
    /** Organize panel inside the detail: attach / convert pickers. */
    val organizeOpen: Boolean = false,
    val feedback: String? = null,
    val error: String? = null
)

/** One Inbox row: preview only (the full content renders in the detail). */
data class CaptureRow(val item: CaptureItem, val preview: String, val age: String, val contextLabel: String?)

data class AgentCaptureState(
    val form: AgentCaptureForm,
    val rows: List<CaptureRow>,
    val selected: CaptureItem?,
    val selectedContextLabel: String?,
    /** One-tap optional shortcut: the current FOCUS WorkStream, if any. */
    val currentFocus: WorkStream?,
    val projects: List<Project>,
    val workStreams: List<WorkStream>,
    val contextLabel: String?,
    val inboxCount: Int
)

/**
 * Agent CAPTURE (Pass 10): low-friction external memory. Holds only ephemeral form/selection
 * state; every save/attach/archive/convert is one `VirlinActions` call. Never touches DAOs,
 * alarms, notifications, Focus or activeTaskId. Nothing in the text is parsed or guessed.
 */
class AgentCaptureViewModel(
    private val actions: VirlinActions = VirlinGraph.actions,
    private val repository: WorkStreamRepository = VirlinGraph.repository,
    private val clock: VirlinClock = VirlinGraph.clock
) : ViewModel() {

    private val _form = MutableStateFlow(AgentCaptureForm())
    val form: StateFlow<AgentCaptureForm> = _form

    val state: StateFlow<AgentCaptureState> =
        combine(repository.captures, repository.projects, repository.streams, repository.tasks, _form) { c, p, s, t, f -> project(c, p, s, t, f) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
                project(repository.captures.value, repository.projects.value, repository.streams.value, repository.tasks.value, _form.value))

    private fun project(captures: List<CaptureItem>, projects: List<Project>, streams: List<WorkStream>, tasks: List<Task>, f: AgentCaptureForm): AgentCaptureState {
        val now = clock.now()
        fun label(projectId: String?, workStreamId: String?, taskId: String?): String? {
            val parts = listOfNotNull(
                projectId?.let { id -> projects.firstOrNull { it.id == id }?.title },
                workStreamId?.let { id -> streams.firstOrNull { it.id == id }?.title },
                taskId?.let { id -> tasks.firstOrNull { it.id == id }?.title }
            )
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
        val status = when (f.filter) { CaptureFilter.INBOX -> CaptureStatus.INBOX; CaptureFilter.ORGANIZED -> CaptureStatus.ORGANIZED; CaptureFilter.ARCHIVED -> CaptureStatus.ARCHIVED }
        val rows = captures.filter { it.status == status }
            .sortedWith(compareByDescending<CaptureItem> { it.createdAt }.thenByDescending { it.id })
            .map { CaptureRow(it, CapturePresentation.preview(it), CapturePresentation.age(it.createdAt, now), label(it.projectId, it.workStreamId, it.taskId)) }
        val selected = f.selectedId?.let { id -> captures.firstOrNull { it.id == id } }
        return AgentCaptureState(
            form = f, rows = rows, selected = selected,
            selectedContextLabel = selected?.let { label(it.projectId, it.workStreamId, it.taskId) },
            currentFocus = streams.firstOrNull { it.state == WorkStreamState.FOCUS },
            projects = projects, workStreams = streams.filter { !it.state.isTerminal },
            contextLabel = label(f.context.projectId, f.context.workStreamId, f.context.taskId),
            inboxCount = captures.count { it.status == CaptureStatus.INBOX }
        )
    }

    // ------------------------------------------------------------------ form (ephemeral)

    fun setType(t: CaptureType) { _form.update { it.copy(type = t, error = null) } }
    fun setLinkNote(v: String) { _form.update { it.copy(linkNote = v) } }
    fun toggleContextPicker() { _form.update { it.copy(contextPickerOpen = !it.contextPickerOpen) } }
    fun setContext(c: CaptureContextChoice) { _form.update { it.copy(context = c, contextPickerOpen = false, error = null) } }
    fun clearContext() = setContext(CaptureContextChoice())
    /** Explicit one-tap shortcut; visible in the context line, never silent. */
    fun attachToCurrentFocus() {
        val ws = repository.streams.value.firstOrNull { it.state == WorkStreamState.FOCUS } ?: return
        setContext(CaptureContextChoice(projectId = ws.projectId, workStreamId = ws.id))
    }
    fun setFilter(f: CaptureFilter) { _form.update { it.copy(filter = f, selectedId = null, organizeOpen = false) } }
    fun select(id: String?) { _form.update { it.copy(selectedId = id, organizeOpen = false, error = null, feedback = null) } }
    fun toggleOrganize() { _form.update { it.copy(organizeOpen = !it.organizeOpen) } }
    fun dismissFeedback() { _form.update { it.copy(feedback = null, error = null) } }

    // ------------------------------------------------------------------ SAVE (one action, durable before success)

    /**
     * [text] is the composer text: NOTE/PROMPT content, or the LINK URL. [onSaved] runs only
     * after persistence succeeded (the caller clears the composer).
     */
    fun save(text: String, onSaved: () -> Unit = {}) {
        val f = _form.value
        if (f.type == CaptureType.FILE || f.type == CaptureType.VOICE) {
            _form.update { it.copy(error = if (f.type == CaptureType.VOICE) "Use Voice to record" else "Use File / Image to attach a file") }
            return
        }
        val request = when (f.type) {
            CaptureType.NOTE, CaptureType.PROMPT -> CreateCapture(type = f.type, content = text, context = f.context.toContext())
            CaptureType.LINK -> CreateCapture(type = CaptureType.LINK, sourceUrl = text, content = f.linkNote, context = f.context.toContext())
            CaptureType.FILE, CaptureType.VOICE -> return
        }
        viewModelScope.launch {
            when (val r = actions.createCapture(request)) {
                is ActionResult.Success -> {
                    val what = when (r.value.type) {
                        CaptureType.NOTE -> "Saved to Inbox"
                        CaptureType.PROMPT -> "Saved prompt"
                        CaptureType.LINK -> "Saved link"
                        CaptureType.FILE -> "Saved file"
                        CaptureType.VOICE -> "Saved voice"
                    }
                    // Context is a per-capture choice: the next capture starts global again.
                    _form.update { it.copy(linkNote = "", context = CaptureContextChoice(), contextPickerOpen = false, feedback = what, error = null, filter = CaptureFilter.INBOX) }
                    onSaved()
                }
                else -> _form.update { it.copy(error = message(r)) }
            }
        }
    }

    // ------------------------------------------------------------------ detail actions (explicit)

    fun archive(id: String) = run(actions::archiveCapture, id, "Archived") { it.copy(selectedId = null) }
    fun restore(id: String) = run(actions::restoreCapture, id, "Back in Inbox") { it.copy(selectedId = null) }

    fun attach(id: String, c: CaptureContextChoice) {
        viewModelScope.launch {
            when (val r = actions.attachCapture(id, c.toContext())) {
                is ActionResult.Success -> _form.update { it.copy(organizeOpen = false, feedback = if (c.isEmpty) "Context cleared" else "Attached", error = null) }
                else -> _form.update { it.copy(error = message(r)) }
            }
        }
    }

    fun convertToTask(id: String, target: CaptureTaskTarget) {
        viewModelScope.launch {
            when (val r = actions.convertCaptureToTask(id, target)) {
                is ActionResult.Success -> _form.update { it.copy(organizeOpen = false, selectedId = null, feedback = "Task created · ${r.value.title}", error = null) }
                else -> _form.update { it.copy(error = message(r)) }
            }
        }
    }

    private fun run(op: suspend (String) -> ActionResult<CaptureItem>, id: String, ok: String, then: (AgentCaptureForm) -> AgentCaptureForm) {
        viewModelScope.launch {
            when (val r = op(id)) {
                is ActionResult.Success -> _form.update { then(it).copy(feedback = ok, error = null) }
                else -> _form.update { it.copy(error = message(r)) }
            }
        }
    }

    private fun message(r: ActionResult<*>): String = when (r) {
        is ActionResult.Rejected -> when (r.reason) {
            DomainError.EmptyCapture -> "Nothing to save yet"
            DomainError.InvalidLink -> "Enter a full link starting with http:// or https://"
            is DomainError.CaptureNotFound -> "That capture no longer exists"
            DomainError.CaptureAlreadyOrganized -> "Already turned into a task"
            is DomainError.TaskNotFound -> "That task no longer exists — choose again"
            is DomainError.ProjectNotFound -> "That project no longer exists — choose again"
            DomainError.OwnershipMismatch -> "Choose a WorkStream or Project"
            DomainError.TaskAlreadyClosed -> "That task is closed — choose another"
            else -> "Couldn't do that"
        }
        is ActionResult.NotFound -> "That WorkStream no longer exists — choose again"
        else -> "Something went wrong"
    }
}

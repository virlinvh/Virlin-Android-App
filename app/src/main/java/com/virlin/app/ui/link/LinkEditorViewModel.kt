package com.virlin.app.ui.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CaptureUpdate
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.Field
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.capture.LinkUrl
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LinkSaveStatus { Idle, Saving, Saved, Error }

data class LinkUiState(
    val captureId: String?,
    val urlInput: String,
    /** Canonical http(s) URL when [urlInput] validates; drives Link Card. */
    val canonicalUrl: String?,
    val title: String,
    val note: String,
    val editingUrl: Boolean,
    val saveStatus: LinkSaveStatus = LinkSaveStatus.Idle,
    val toast: String? = null,
    val contextLabel: String? = null,
    val context: CaptureContext = CaptureContext.None,
    val contextPickerOpen: Boolean = false,
    val committedToInbox: Boolean = false,
    val loading: Boolean = true,
    val inboxBanner: String? = null,
    val openFailed: Boolean = false
) {
    val showCard: Boolean get() = canonicalUrl != null && !editingUrl
    val displayTitle: String
        get() = canonicalUrl?.let { LinkUrl.displayLabel(title.ifBlank { null }, it) }.orEmpty()
    val displayUrl: String
        get() = canonicalUrl?.let { LinkUrl.displayUrl(it) }.orEmpty()
    val hasMeaningfulContent: Boolean
        get() = canonicalUrl != null
}

/**
 * Full-screen Link capture editor.
 * Storage stays on CaptureItem(LINK): sourceUrl + optional title + content note.
 * No network on paste/save.
 */
class LinkEditorViewModel(
    private val initialCaptureId: String?,
    private val actions: VirlinActions = VirlinGraph.actions,
    private val repository: WorkStreamRepository = VirlinGraph.repository,
    private val ids: IdProvider = VirlinGraph.ids,
    private val clock: VirlinClock = VirlinGraph.clock
) : ViewModel() {

    private var saveJob: Job? = null
    private val draftCaptureId = initialCaptureId ?: ids.newId("cap")

    private val _state = MutableStateFlow(
        LinkUiState(
            captureId = initialCaptureId,
            urlInput = "",
            canonicalUrl = null,
            title = "",
            note = "",
            editingUrl = true,
            committedToInbox = initialCaptureId != null,
            loading = initialCaptureId != null
        )
    )
    val state: StateFlow<LinkUiState> = _state.asStateFlow()

    init {
        refreshContextLabel()
        if (initialCaptureId != null) {
            viewModelScope.launch {
                val cap = repository.getCapture(initialCaptureId)
                if (cap == null || cap.type != CaptureType.LINK) {
                    _state.update { it.copy(loading = false, toast = "Could not open link") }
                    return@launch
                }
                val canonical = LinkUrl.canonicalOrNull(cap.sourceUrl.orEmpty())
                _state.update {
                    it.copy(
                        captureId = cap.id,
                        urlInput = cap.sourceUrl.orEmpty(),
                        canonicalUrl = canonical,
                        title = cap.title.orEmpty(),
                        note = cap.content,
                        editingUrl = canonical == null,
                        committedToInbox = true,
                        loading = false,
                        saveStatus = LinkSaveStatus.Saved,
                        context = CaptureContext(cap.projectId, cap.workStreamId, cap.taskId)
                    )
                }
                refreshContextLabel()
            }
        } else {
            _state.update { it.copy(loading = false) }
        }
    }

    fun onUrlInputChange(raw: String) {
        val parsed = LinkUrl.canonicalOrNull(raw)
        _state.update { st ->
            when {
                parsed != null -> st.copy(
                    urlInput = raw,
                    canonicalUrl = parsed,
                    editingUrl = false,
                    openFailed = false
                )
                // Mid-edit typo: keep last good URL, stay on input
                st.editingUrl && st.canonicalUrl != null -> st.copy(
                    urlInput = raw,
                    editingUrl = true,
                    openFailed = false
                )
                else -> st.copy(
                    urlInput = raw,
                    canonicalUrl = null,
                    editingUrl = true,
                    openFailed = false
                )
            }
        }
        scheduleAutosave()
    }

    fun startEditUrl() {
        _state.update {
            it.copy(
                editingUrl = true,
                urlInput = it.canonicalUrl ?: it.urlInput
            )
        }
    }

    fun onTitleChange(t: String) {
        _state.update { it.copy(title = t) }
        scheduleAutosave()
    }

    fun onNoteChange(n: String) {
        _state.update { it.copy(note = n) }
        scheduleAutosave()
    }

    fun openContextPicker(open: Boolean) = _state.update { it.copy(contextPickerOpen = open) }

    fun setContext(ctx: CaptureContext) {
        _state.update { it.copy(context = ctx, contextPickerOpen = false) }
        refreshContextLabel()
        val capId = _state.value.captureId
        if (capId != null && _state.value.committedToInbox) {
            viewModelScope.launch {
                actions.attachCapture(capId, ctx)
                _state.update { it.copy(toast = "Context updated", saveStatus = LinkSaveStatus.Saved) }
            }
        }
    }

    fun clearToast() = _state.update { it.copy(toast = null) }
    fun clearInboxBanner() = _state.update { it.copy(inboxBanner = null) }
    fun clearOpenFailed() = _state.update { it.copy(openFailed = false) }

    fun markOpenFailed() = _state.update { it.copy(openFailed = true, toast = "No app can open this link") }

    fun copyUrlFeedback() = _state.update { it.copy(toast = "Copied ✓") }

    fun saveToInbox() {
        viewModelScope.launch {
            if (!_state.value.hasMeaningfulContent) {
                _state.update { it.copy(toast = "Paste a valid link first") }
                return@launch
            }
            commitToInbox(showBanner = "✓ In Inbox")
        }
    }

    suspend fun prepareExit(): PrepareExitResult {
        saveJob?.cancel()
        val st = _state.value
        if (!st.hasMeaningfulContent) {
            return PrepareExitResult(navigate = true, showInboxFeedback = false)
        }
        if (!st.committedToInbox) {
            val ok = commitToInbox(showBanner = "Saved to Inbox ✓")
            return PrepareExitResult(navigate = ok, showInboxFeedback = ok)
        }
        persistCommitted()
        return PrepareExitResult(navigate = true, showInboxFeedback = false)
    }

    data class PrepareExitResult(val navigate: Boolean, val showInboxFeedback: Boolean)

    fun archiveAndExit(onDone: () -> Unit) {
        viewModelScope.launch {
            val exit = prepareExit()
            if (!exit.navigate) return@launch
            val id = _state.value.captureId
            if (id != null && _state.value.committedToInbox) actions.archiveCapture(id)
            onDone()
        }
    }

    private suspend fun commitToInbox(showBanner: String): Boolean {
        val st = _state.value
        val url = st.canonicalUrl ?: return false
        val result = if (!st.committedToInbox) {
            actions.createCapture(
                CreateCapture(
                    type = CaptureType.LINK,
                    sourceUrl = url,
                    content = st.note,
                    title = st.title.ifBlank { null },
                    context = st.context,
                    id = st.captureId ?: draftCaptureId
                )
            )
        } else {
            actions.updateCapture(
                st.captureId!!,
                CaptureUpdate(
                    sourceUrl = Field.Set(url),
                    content = Field.Set(st.note),
                    title = Field.Set(st.title)
                )
            )
        }
        return when (result) {
            is ActionResult.Success -> {
                _state.update {
                    it.copy(
                        captureId = result.value.id,
                        urlInput = result.value.sourceUrl.orEmpty(),
                        canonicalUrl = result.value.sourceUrl,
                        committedToInbox = true,
                        saveStatus = LinkSaveStatus.Saved,
                        inboxBanner = showBanner,
                        toast = null,
                        editingUrl = false
                    )
                }
                true
            }
            else -> {
                _state.update { it.copy(saveStatus = LinkSaveStatus.Error, toast = "Could not save") }
                false
            }
        }
    }

    private suspend fun persistCommitted() {
        val st = _state.value
        val capId = st.captureId ?: return
        val url = st.canonicalUrl ?: return
        if (!st.committedToInbox) return
        when (
            actions.updateCapture(
                capId,
                CaptureUpdate(
                    sourceUrl = Field.Set(url),
                    content = Field.Set(st.note),
                    title = Field.Set(st.title)
                )
            )
        ) {
            is ActionResult.Success -> _state.update { it.copy(saveStatus = LinkSaveStatus.Saved) }
            else -> _state.update { it.copy(saveStatus = LinkSaveStatus.Error) }
        }
    }

    private fun scheduleAutosave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            _state.update { it.copy(saveStatus = LinkSaveStatus.Saving) }
            delay(450)
            val st = _state.value
            if (!st.committedToInbox) {
                // Session-local draft until Inbox commit
                _state.update {
                    it.copy(
                        saveStatus = if (st.hasMeaningfulContent) LinkSaveStatus.Saved else LinkSaveStatus.Idle
                    )
                }
                return@launch
            }
            if (st.canonicalUrl == null) {
                _state.update { it.copy(saveStatus = LinkSaveStatus.Error) }
                return@launch
            }
            persistCommitted()
        }
    }

    private fun refreshContextLabel() {
        viewModelScope.launch {
            val ctx = _state.value.context
            val label = when {
                ctx.taskId != null -> repository.getTask(ctx.taskId)?.title?.let { "Attached to · $it" }
                ctx.workStreamId != null -> repository.getStream(ctx.workStreamId)?.title?.let { "Attached to · $it" }
                ctx.projectId != null -> repository.getProject(ctx.projectId)?.title?.let { "Attached to · $it" }
                else -> null
            }
            _state.update { it.copy(contextLabel = label) }
        }
    }

    companion object {
        fun factory(captureId: String?): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LinkEditorViewModel(captureId) as T
        }
    }
}

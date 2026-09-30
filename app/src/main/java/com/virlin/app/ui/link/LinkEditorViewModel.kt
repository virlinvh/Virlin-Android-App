package com.virlin.app.ui.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.*
import com.virlin.app.domain.capture.*
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LinkSaveStatus { Idle, Saving, Saved, Error }

data class LinkUiState(
    val captureId: String? = null,
    val urlInput: String = "",
    val canonicalUrl: String? = null,
    val title: String = "",
    val note: String = "",
    val showPreview: Boolean = true,
    val playbackEnabled: Boolean = false,
    val startInput: String = "00:00",
    val endInput: String = "",
    val editing: Boolean = true,
    val saveStatus: LinkSaveStatus = LinkSaveStatus.Idle,
    val toast: String? = null,
    val contextLabel: String? = null,
    val context: CaptureContext = CaptureContext.None,
    val committedToInbox: Boolean = false,
    val loading: Boolean = true,
) {
    /** Compatibility projections for older UI/tests; the new screen uses explicit edit/detail mode. */
    val showCard: Boolean get() = canonicalUrl != null
    val editingUrl: Boolean get() = editing
    val preview: LinkPreview? get() = canonicalUrl?.let(LinkPresentation::preview)
    val supportsPlayback: Boolean get() = canonicalUrl?.let(LinkPresentation::supportsPlayback) == true
    val displayTitle: String get() = canonicalUrl?.let { LinkUrl.displayLabel(title.ifBlank { preview?.suggestedTitle }, it) }.orEmpty()
    val displayUrl: String get() = canonicalUrl?.let(LinkUrl::displayUrl).orEmpty()
    val isTimeValid: Boolean get() {
        if (!playbackEnabled) return true
        val start = LinkPresentation.parseTime(startInput) ?: return false
        val end = endInput.takeIf { it.isNotBlank() }?.let(LinkPresentation::parseTime)
        return endInput.isBlank() || (end != null && end > start)
    }
    val canSave: Boolean get() = canonicalUrl != null && isTimeValid
    val document: LinkDocument get() = LinkDocument(
        note = note,
        showPreview = showPreview,
        playbackEnabled = playbackEnabled && supportsPlayback,
        startSeconds = if (playbackEnabled) LinkPresentation.parseTime(startInput) else null,
        endSeconds = if (playbackEnabled) endInput.takeIf { it.isNotBlank() }?.let(LinkPresentation::parseTime) else null,
    )
}

/** Full-page Link workspace. CaptureItem remains the only persisted record; no map entity is created. */
class LinkEditorViewModel(
    private val initialCaptureId: String?,
    private val actions: VirlinActions = VirlinGraph.actions,
    private val repository: WorkStreamRepository = VirlinGraph.repository,
    private val ids: IdProvider = VirlinGraph.ids,
    @Suppress("unused") private val clock: VirlinClock = VirlinGraph.clock,
    private val initialContext: CaptureContext = CaptureContext.None,
) : ViewModel() {
    private val draftId = initialCaptureId ?: ids.newId("cap")
    private val _state = MutableStateFlow(LinkUiState(
        captureId = initialCaptureId,
        context = initialContext,
        committedToInbox = initialCaptureId != null,
        loading = initialCaptureId != null,
        editing = initialCaptureId == null,
    ))
    val state: StateFlow<LinkUiState> = _state.asStateFlow()

    init {
        if (initialCaptureId == null) {
            _state.update { it.copy(loading = false) }
            refreshContextLabel()
        } else viewModelScope.launch {
            val cap = repository.getCapture(initialCaptureId)
            if (cap == null || cap.type != CaptureType.LINK) {
                _state.update { it.copy(loading = false, toast = "Could not open link") }
                return@launch
            }
            val doc = LinkDocumentCodec.decode(cap.content)
            val canonical = LinkUrl.canonicalOrNull(cap.sourceUrl.orEmpty())
            _state.update { it.copy(
                captureId = cap.id,
                urlInput = cap.sourceUrl.orEmpty(),
                canonicalUrl = canonical,
                title = cap.title.orEmpty(),
                note = doc.note,
                showPreview = doc.showPreview,
                playbackEnabled = doc.playbackEnabled && canonical?.let(LinkPresentation::supportsPlayback) == true,
                startInput = LinkPresentation.formatTime(doc.startSeconds),
                endInput = doc.endSeconds?.let(LinkPresentation::formatTime).orEmpty(),
                editing = canonical == null,
                committedToInbox = true,
                loading = false,
                saveStatus = LinkSaveStatus.Saved,
                context = CaptureContext(cap.projectId, cap.workStreamId, cap.taskId),
            ) }
            refreshContextLabel()
        }
    }

    fun onUrlInputChange(raw: String) = _state.update {
        val canonical = LinkUrl.canonicalOrNull(raw)
        it.copy(
            urlInput = raw,
            canonicalUrl = canonical,
            playbackEnabled = it.playbackEnabled && canonical?.let(LinkPresentation::supportsPlayback) == true,
            saveStatus = LinkSaveStatus.Idle,
        )
    }
    fun onTitleChange(value: String) = _state.update { it.copy(title = value, saveStatus = LinkSaveStatus.Idle) }
    fun onNoteChange(value: String) = _state.update { it.copy(note = value, saveStatus = LinkSaveStatus.Idle) }
    fun setShowPreview(value: Boolean) = _state.update { it.copy(showPreview = value, saveStatus = LinkSaveStatus.Idle) }
    fun setPlaybackEnabled(value: Boolean) = _state.update { it.copy(playbackEnabled = value && it.supportsPlayback, saveStatus = LinkSaveStatus.Idle) }
    fun onStartChange(value: String) = _state.update { it.copy(startInput = value, saveStatus = LinkSaveStatus.Idle) }
    fun onEndChange(value: String) = _state.update { it.copy(endInput = value, saveStatus = LinkSaveStatus.Idle) }
    fun edit() = _state.update { it.copy(editing = true, toast = null) }
    fun cancelEdit() = _state.update { it.copy(editing = !it.committedToInbox, toast = null) }
    fun clearToast() = _state.update { it.copy(toast = null) }
    fun markOpenFailed() = _state.update { it.copy(toast = "No app can open this link") }
    fun copyUrlFeedback() = _state.update { it.copy(toast = "Link copied") }

    fun save() = viewModelScope.launch {
        val st = _state.value
        if (!st.canSave) {
            _state.update { it.copy(toast = if (st.canonicalUrl == null) "Paste a valid link first" else "Check the playback times") }
            return@launch
        }
        val url = st.canonicalUrl ?: return@launch
        _state.update { it.copy(saveStatus = LinkSaveStatus.Saving) }
        val content = LinkDocumentCodec.encode(st.document)
        val result = if (!st.committedToInbox) actions.createCapture(CreateCapture(
            type = CaptureType.LINK,
            sourceUrl = url,
            content = content,
            title = st.title.ifBlank { null },
            context = st.context,
            id = st.captureId ?: draftId,
        )) else actions.updateCapture(st.captureId!!, CaptureUpdate(
            sourceUrl = Field.Set(url),
            content = Field.Set(content),
            title = Field.Set(st.title),
        ))
        when (result) {
            is ActionResult.Success -> _state.update { it.copy(
                captureId = result.value.id,
                canonicalUrl = result.value.sourceUrl,
                urlInput = result.value.sourceUrl.orEmpty(),
                committedToInbox = true,
                editing = false,
                saveStatus = LinkSaveStatus.Saved,
                toast = "Link saved",
            ) }
            else -> _state.update { it.copy(saveStatus = LinkSaveStatus.Error, toast = "Could not save link") }
        }
    }

    fun archive(onDone: () -> Unit) = viewModelScope.launch {
        val id = _state.value.captureId ?: return@launch
        if (actions.archiveCapture(id) is ActionResult.Success) onDone()
        else _state.update { it.copy(toast = "Could not delete link") }
    }

    suspend fun prepareExit(): PrepareExitResult = PrepareExitResult(true, false)
    data class PrepareExitResult(val navigate: Boolean, val showInboxFeedback: Boolean)

    private fun refreshContextLabel() = viewModelScope.launch {
        val ctx = _state.value.context
        val label = when {
            ctx.taskId != null -> repository.getTask(ctx.taskId)?.title
            ctx.workStreamId != null -> repository.getStream(ctx.workStreamId)?.title
            ctx.projectId != null -> repository.getProject(ctx.projectId)?.title
            else -> null
        }
        _state.update { it.copy(contextLabel = label?.let { name -> "Attached to · $name" }) }
    }

    companion object {
        fun factory(captureId: String?, initialContext: CaptureContext = CaptureContext.None): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LinkEditorViewModel(captureId, initialContext = initialContext) as T
            }
    }
}

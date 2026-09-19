package com.virlin.app.ui.file

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virlin.app.data.attachment.AttachmentFileStore
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.attachment.AttachmentKindResolver
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.AttachmentDocument
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.repository.WorkStreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class FileSaveStatus { Idle, Importing, Saved, Error }

data class FileUiState(
    val captureId: String?,
    val attachmentId: String?,
    val displayName: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val kind: AttachmentKind = AttachmentKind.UNSUPPORTED,
    val absolutePath: String? = null,
    val saveStatus: FileSaveStatus = FileSaveStatus.Idle,
    val toast: String? = null,
    val contextLabel: String? = null,
    val context: CaptureContext = CaptureContext.None,
    val contextPickerOpen: Boolean = false,
    val committedToInbox: Boolean = false,
    val loading: Boolean = true,
    val inboxBanner: String? = null,
    val importError: String? = null
) {
    val hasMeaningfulContent: Boolean
        get() = absolutePath != null && sizeBytes > 0L && File(absolutePath).exists()

    val metaLine: String
        get() = listOf(
            AttachmentKindResolver.formatLabel(kind),
            AttachmentKindResolver.formatSize(sizeBytes)
        ).joinToString(" · ")
}

/**
 * Single-screen File / Image capture. Import streams into managed storage;
 * metadata persists via [VirlinActions]; viewer renders [absolutePath] below the selector.
 */
class FileViewerViewModel(
    private val initialCaptureId: String?,
    private val appContext: Context,
    private val actions: VirlinActions = VirlinGraph.actions,
    private val repository: WorkStreamRepository = VirlinGraph.repository,
    private val ids: IdProvider = VirlinGraph.ids
) : ViewModel() {

    private val draftCaptureId = initialCaptureId ?: ids.newId("cap")
    private val draftAttachmentId = ids.newId("att")

    private val _state = MutableStateFlow(
        FileUiState(
            captureId = initialCaptureId,
            attachmentId = if (initialCaptureId == null) draftAttachmentId else null,
            committedToInbox = initialCaptureId != null,
            loading = initialCaptureId != null
        )
    )
    val state: StateFlow<FileUiState> = _state.asStateFlow()

    init {
        refreshContextLabel()
        if (initialCaptureId != null) {
            viewModelScope.launch { loadExisting(initialCaptureId) }
        } else {
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun loadExisting(captureId: String) {
        val cap = repository.getCapture(captureId)
        if (cap == null || cap.type != CaptureType.FILE) {
            _state.update { it.copy(loading = false, toast = "Could not open file") }
            return
        }
        val att = repository.getAttachmentByCaptureId(captureId)
        if (att == null) {
            _state.update { it.copy(loading = false, toast = "Attachment missing", captureId = cap.id) }
            return
        }
        val file = AttachmentFileStore.resolve(appContext, att.relativePath)
        _state.update {
            it.copy(
                captureId = cap.id,
                attachmentId = att.id,
                displayName = att.displayName,
                mimeType = att.mimeType,
                sizeBytes = att.sizeBytes,
                kind = att.kind,
                absolutePath = file.takeIf { f -> f.exists() }?.absolutePath,
                committedToInbox = true,
                loading = false,
                saveStatus = FileSaveStatus.Saved,
                context = CaptureContext(cap.projectId, cap.workStreamId, cap.taskId),
                importError = if (!file.exists()) "Stored file is missing" else null
            )
        }
        refreshContextLabel()
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(saveStatus = FileSaveStatus.Importing, importError = null, toast = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val attId = _state.value.attachmentId ?: draftAttachmentId
                    // Replace: remove previous managed tree for this attachment id, then stream-copy.
                    AttachmentFileStore.deleteAttachmentTree(appContext, attId)
                    AttachmentFileStore.importFromUri(appContext, uri, attId)
                }
            }
            result.fold(
                onSuccess = { imported ->
                    _state.update {
                        it.copy(
                            attachmentId = imported.relativePath.substringBefore('/'),
                            displayName = imported.displayName,
                            mimeType = imported.mimeType,
                            sizeBytes = imported.sizeBytes,
                            kind = imported.kind,
                            absolutePath = imported.absoluteFile.absolutePath,
                            saveStatus = FileSaveStatus.Saved,
                            importError = null
                        )
                    }
                    // Autosave draft metadata only after commit path; exit flush handles Inbox.
                    if (_state.value.committedToInbox) {
                        persistCommitted()
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            saveStatus = FileSaveStatus.Error,
                            importError = e.message ?: "Could not import file",
                            toast = "Import failed"
                        )
                    }
                }
            )
        }
    }

    fun openContextPicker(open: Boolean) = _state.update { it.copy(contextPickerOpen = open) }

    fun setContext(ctx: CaptureContext) {
        _state.update { it.copy(context = ctx, contextPickerOpen = false) }
        refreshContextLabel()
        val capId = _state.value.captureId
        if (capId != null && _state.value.committedToInbox) {
            viewModelScope.launch {
                actions.attachCapture(capId, ctx)
                _state.update { it.copy(toast = "Context updated", saveStatus = FileSaveStatus.Saved) }
            }
        }
    }

    fun clearToast() = _state.update { it.copy(toast = null) }
    fun clearInboxBanner() = _state.update { it.copy(inboxBanner = null) }

    fun saveToInbox() {
        viewModelScope.launch {
            if (!_state.value.hasMeaningfulContent) {
                _state.update { it.copy(toast = "Select a file first") }
                return@launch
            }
            commitToInbox(showBanner = "✓ In Inbox")
        }
    }

    suspend fun prepareExit(): PrepareExitResult {
        val st = _state.value
        if (!st.hasMeaningfulContent) {
            // Empty draft: discard any partial managed folder for this attachment id.
            st.attachmentId?.let { id ->
                withContext(Dispatchers.IO) { AttachmentFileStore.deleteAttachmentTree(appContext, id) }
            }
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

    fun removeAttachment(onDone: () -> Unit) {
        viewModelScope.launch {
            val st = _state.value
            st.attachmentId?.let { id ->
                withContext(Dispatchers.IO) { AttachmentFileStore.deleteAttachmentTree(appContext, id) }
            }
            if (st.committedToInbox && st.captureId != null) {
                actions.archiveCapture(st.captureId)
            }
            onDone()
        }
    }

    private suspend fun commitToInbox(showBanner: String): Boolean {
        val st = _state.value
        val path = st.absolutePath ?: return false
        val relative = relativeFromAbsolute(path) ?: return false
        val attId = st.attachmentId ?: draftAttachmentId
        val capId = st.captureId ?: draftCaptureId
        val result = if (!st.committedToInbox) {
            actions.createAttachment(
                displayName = st.displayName,
                mimeType = st.mimeType,
                sizeBytes = st.sizeBytes,
                relativePath = relative,
                kind = st.kind,
                context = st.context,
                captureId = capId,
                attachmentId = attId
            )
        } else {
            actions.saveAttachment(
                captureItemId = capId,
                displayName = st.displayName,
                mimeType = st.mimeType,
                sizeBytes = st.sizeBytes,
                relativePath = relative,
                kind = st.kind
            )
        }
        return when (result) {
            is ActionResult.Success -> {
                applyDoc(result.value, showBanner)
                true
            }
            else -> {
                _state.update { it.copy(saveStatus = FileSaveStatus.Error, toast = "Could not save") }
                false
            }
        }
    }

    private suspend fun persistCommitted() {
        val st = _state.value
        val capId = st.captureId ?: return
        val path = st.absolutePath ?: return
        val relative = relativeFromAbsolute(path) ?: return
        if (!st.committedToInbox) return
        when (
            actions.saveAttachment(
                captureItemId = capId,
                displayName = st.displayName,
                mimeType = st.mimeType,
                sizeBytes = st.sizeBytes,
                relativePath = relative,
                kind = st.kind
            )
        ) {
            is ActionResult.Success -> _state.update { it.copy(saveStatus = FileSaveStatus.Saved) }
            else -> _state.update { it.copy(saveStatus = FileSaveStatus.Error, toast = "Could not save") }
        }
    }

    private fun applyDoc(doc: AttachmentDocument, banner: String?) {
        val file = AttachmentFileStore.resolve(appContext, doc.relativePath)
        _state.update {
            it.copy(
                captureId = doc.captureItemId,
                attachmentId = doc.id,
                displayName = doc.displayName,
                mimeType = doc.mimeType,
                sizeBytes = doc.sizeBytes,
                kind = doc.kind,
                absolutePath = file.absolutePath,
                committedToInbox = true,
                saveStatus = FileSaveStatus.Saved,
                inboxBanner = banner,
                toast = null
            )
        }
    }

    private fun relativeFromAbsolute(absolute: String): String? {
        val root = AttachmentFileStore.attachmentsRoot(appContext).absolutePath
        return if (absolute.startsWith(root)) {
            absolute.removePrefix(root).trimStart(File.separatorChar).replace('\\', '/')
        } else null
    }

    private fun refreshContextLabel() {
        viewModelScope.launch {
            val ctx = _state.value.context
            val label = when {
                ctx.taskId != null -> repository.getTask(ctx.taskId)?.title
                ctx.workStreamId != null -> repository.getStream(ctx.workStreamId)?.title
                ctx.projectId != null -> repository.getProject(ctx.projectId)?.title
                else -> null
            }
            _state.update { it.copy(contextLabel = label) }
        }
    }

    companion object {
        fun factory(captureId: String?, context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FileViewerViewModel(captureId, context.applicationContext) as T
            }
    }
}

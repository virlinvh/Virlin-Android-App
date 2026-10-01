package com.virlin.app.ui.attachment

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
import com.virlin.app.domain.model.AttachmentDocument
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.repository.WorkStreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One saved file in the task's attachment space. */
data class AttachmentRow(
    val captureId: String,
    val document: AttachmentDocument,
) {
    val kind: AttachmentKind get() = document.kind
    val preview: AttachmentKindResolver.Preview get() = AttachmentKindResolver.previewOf(kind)
}

/** A file that could not be imported. Reported per file so a partial batch stays honest. */
data class AttachmentImportFailure(val displayName: String, val reason: String)

data class AttachmentWorkspaceState(
    val taskId: String = "",
    val taskTitle: String = "",
    val loading: Boolean = true,
    val importing: Boolean = false,
    val importedCount: Int = 0,
    val importTotal: Int = 0,
    val rows: List<AttachmentRow> = emptyList(),
    val failures: List<AttachmentImportFailure> = emptyList(),
    val message: String? = null,
    val openDetail: AttachmentRow? = null,
    val pendingRemoval: AttachmentRow? = null,
    val renaming: AttachmentRow? = null,
)

/**
 * The task's universal file space.
 *
 * It owns no new persistence. Every imported file becomes one `CaptureItem(FILE)` plus one
 * [AttachmentDocument], exactly as the existing Capture File flow does, and `CaptureActions`
 * registers its `capture.file` Page block inside the same transaction. This screen is a view over
 * those records, not a container entity, which is why no Room change was required.
 */
class AttachmentWorkspaceViewModel(
    private val taskId: String,
    private val appContext: Context,
    private val actions: VirlinActions = VirlinGraph.actions,
    private val repository: WorkStreamRepository = VirlinGraph.repository,
    private val ids: com.virlin.app.domain.id.IdProvider = VirlinGraph.ids,
) : ViewModel() {

    private val _state = MutableStateFlow(AttachmentWorkspaceState(taskId = taskId))
    val state: StateFlow<AttachmentWorkspaceState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val task = repository.getTask(taskId)
            val captures = repository.captures.value
                .filter { it.taskId == taskId && it.type == CaptureType.FILE }
                .sortedBy { it.createdAt }
            val rows = captures.mapNotNull { capture ->
                actions.getAttachmentByCaptureId(capture.id)?.let { AttachmentRow(capture.id, it) }
            }
            _state.value = _state.value.copy(
                taskTitle = task?.title.orEmpty(),
                rows = rows,
                loading = false,
            )
        }
    }

    /**
     * Imports each selected file independently.
     *
     * A failure on one file never discards the others: successful imports stay visible and every
     * failure is reported by name. A file that fails after its managed directory was created has
     * that directory removed, so a partial copy is not stranded on disk.
     */
    fun importAll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                importing = true, importedCount = 0, importTotal = uris.size, failures = emptyList(),
            )
            val failures = ArrayList<AttachmentImportFailure>()
            var done = 0
            uris.forEach { uri ->
                val attachmentId = ids.newId("att")
                val outcome = runCatching {
                    withContext(Dispatchers.IO) {
                        AttachmentFileStore.importFromUri(appContext, uri, attachmentId)
                    }
                }
                outcome.onSuccess { imported ->
                    if (imported.sizeBytes <= 0L) {
                        withContext(Dispatchers.IO) {
                            AttachmentFileStore.deleteAttachmentTree(appContext, attachmentId)
                        }
                        failures += AttachmentImportFailure(imported.displayName, "The file is empty.")
                    } else {
                        val saved = actions.createAttachment(
                            displayName = sanitizeDisplayName(imported.displayName),
                            mimeType = imported.mimeType,
                            sizeBytes = imported.sizeBytes,
                            relativePath = imported.relativePath,
                            kind = imported.kind,
                            context = CaptureContext(taskId = taskId),
                            attachmentId = attachmentId,
                        )
                        if (saved !is ActionResult.Success) {
                            withContext(Dispatchers.IO) {
                                AttachmentFileStore.deleteAttachmentTree(appContext, attachmentId)
                            }
                            failures += AttachmentImportFailure(
                                imported.displayName, "Virlin could not save this file."
                            )
                        }
                    }
                }.onFailure { error ->
                    withContext(Dispatchers.IO) {
                        AttachmentFileStore.deleteAttachmentTree(appContext, attachmentId)
                    }
                    failures += AttachmentImportFailure(
                        "Selected file", error.message ?: "The file could not be read."
                    )
                }
                done++
                _state.value = _state.value.copy(importedCount = done)
            }
            _state.value = _state.value.copy(importing = false, failures = failures)
            refresh()
        }
    }

    fun rename(row: AttachmentRow, newName: String) {
        val clean = sanitizeDisplayName(newName).trim()
        if (clean.isEmpty()) {
            _state.value = _state.value.copy(message = "A file needs a name.", renaming = null)
            return
        }
        // Keep the original extension so the resolved kind and any external app still agree.
        val original = row.document.displayName
        val ext = original.substringAfterLast('.', "")
        val renamed = if (ext.isNotEmpty() && !clean.endsWith(".$ext", ignoreCase = true)) {
            "${clean.substringBeforeLast('.', clean)}.$ext"
        } else {
            clean
        }
        viewModelScope.launch {
            val result = actions.saveAttachment(
                captureItemId = row.captureId,
                displayName = renamed,
                mimeType = row.document.mimeType,
                sizeBytes = row.document.sizeBytes,
                relativePath = row.document.relativePath,
                kind = row.document.kind,
            )
            _state.value = _state.value.copy(
                renaming = null,
                message = if (result is ActionResult.Success) null else "Could not rename this file.",
            )
            refresh()
        }
    }

    fun askRemove(row: AttachmentRow) { _state.value = _state.value.copy(pendingRemoval = row) }
    fun cancelRemove() { _state.value = _state.value.copy(pendingRemoval = null) }

    /**
     * Removes exactly one attachment: its capture row is archived through the canonical action and
     * only that attachment's managed directory is deleted. No sibling file is touched.
     */
    fun confirmRemove() {
        val row = _state.value.pendingRemoval ?: return
        viewModelScope.launch {
            val archived = actions.archiveCapture(row.captureId)
            if (archived is ActionResult.Success) {
                withContext(Dispatchers.IO) {
                    AttachmentFileStore.deleteAttachmentTree(appContext, row.document.id)
                }
            }
            _state.value = _state.value.copy(
                pendingRemoval = null,
                openDetail = null,
                message = if (archived is ActionResult.Success) null else "Could not remove this file.",
            )
            refresh()
        }
    }

    fun open(row: AttachmentRow) { _state.value = _state.value.copy(openDetail = row) }
    fun closeDetail() { _state.value = _state.value.copy(openDetail = null) }
    fun startRename(row: AttachmentRow?) { _state.value = _state.value.copy(renaming = row) }
    fun clearMessage() { _state.value = _state.value.copy(message = null) }
    fun clearFailures() { _state.value = _state.value.copy(failures = emptyList()) }

    /** Managed copies must never carry separators or traversal segments in their display name. */
    private fun sanitizeDisplayName(raw: String): String =
        raw.replace('\\', '_')
            .replace('/', '_')
            .replace("..", "_")
            .filterNot { it.isISOControl() }
            .trim()
            .ifEmpty { "file" }
            .take(120)

    companion object {
        fun factory(taskId: String, context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AttachmentWorkspaceViewModel(taskId, context.applicationContext) as T
            }
    }
}

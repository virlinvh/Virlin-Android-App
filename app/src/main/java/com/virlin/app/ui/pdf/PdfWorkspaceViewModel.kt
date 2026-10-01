package com.virlin.app.ui.pdf

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
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.repository.WorkStreamRepository
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PdfWorkspaceState(
    val captureId: String? = null,
    val attachmentId: String? = null,
    val taskId: String? = null,
    val taskTitle: String? = null,
    val context: CaptureContext = CaptureContext.None,
    val displayName: String = "",
    val sizeBytes: Long = 0,
    val absolutePath: String? = null,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val selectedPages: Set<Int> = emptySet(),
    val crops: Map<Int, PdfCrop> = emptyMap(),
    val mode: PdfWorkspaceMode = PdfWorkspaceMode.HOME,
    val showPreviews: Boolean = true,
    val committed: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val createdCaptureIds: List<String> = emptyList()
) {
    val hasPdf: Boolean get() = absolutePath != null && pageCount > 0
    val selectedOrAll: List<Int>
        get() = (selectedPages.ifEmpty { (0 until pageCount).toSet() }).sorted()
}

class PdfWorkspaceViewModel(
    private val initialCaptureId: String?,
    private val initialTaskId: String?,
    private val appContext: Context,
    private val actions: VirlinActions = VirlinGraph.actions,
    private val repository: WorkStreamRepository = VirlinGraph.repository,
    private val ids: IdProvider = VirlinGraph.ids
) : ViewModel() {
    private val draftCaptureId = initialCaptureId ?: ids.newId("cap")
    private val draftAttachmentId = ids.newId("att")
    private val _state = MutableStateFlow(
        PdfWorkspaceState(
            captureId = initialCaptureId,
            attachmentId = if (initialCaptureId == null) draftAttachmentId else null,
            taskId = initialTaskId,
            context = CaptureContext(taskId = initialTaskId),
            committed = initialCaptureId != null,
            busy = initialCaptureId != null || initialTaskId != null
        )
    )
    val state: StateFlow<PdfWorkspaceState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val title = initialTaskId?.let { repository.getTask(it)?.title }
            _state.update { it.copy(taskTitle = title) }
            if (initialCaptureId != null) loadExisting(initialCaptureId)
            else _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun loadExisting(captureId: String) {
        val capture = repository.getCapture(captureId)
        val attachment = repository.getAttachmentByCaptureId(captureId)
        if (capture == null || capture.type != CaptureType.FILE || attachment == null || attachment.kind != AttachmentKind.PDF) {
            _state.update { it.copy(busy = false, message = "This PDF is unavailable") }
            return
        }
        val file = AttachmentFileStore.resolve(appContext, attachment.relativePath)
        val count = withContext(Dispatchers.IO) {
            if (!file.exists()) 0 else runCatching { PdfDocumentEngine.pageCount(file) }.getOrDefault(0)
        }
        val taskTitle = capture.taskId?.let { repository.getTask(it)?.title }
        _state.update {
            it.copy(
                attachmentId = attachment.id,
                taskId = capture.taskId,
                taskTitle = taskTitle,
                context = CaptureContext(capture.projectId, capture.workStreamId, capture.taskId),
                displayName = attachment.displayName,
                sizeBytes = attachment.sizeBytes,
                absolutePath = file.takeIf(File::exists)?.absolutePath,
                pageCount = count,
                committed = true,
                busy = false,
                message = if (count == 0) "Stored PDF is missing or invalid" else null
            )
        }
    }

    fun importPdf(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val attachmentId = _state.value.attachmentId ?: draftAttachmentId
            val stagedId = ids.newId("pdfstage")
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val staged = AttachmentFileStore.importFromUri(appContext, uri, stagedId, "document.pdf")
                    require(staged.kind == AttachmentKind.PDF) { "Choose a valid PDF file" }
                    require(PdfDocumentEngine.pageCount(staged.absoluteFile) > 0) { "The PDF has no readable pages" }
                    val finalDirectory = File(AttachmentFileStore.attachmentsRoot(appContext), attachmentId)
                    val backupDirectory = File(AttachmentFileStore.attachmentsRoot(appContext), "$attachmentId-backup")
                    backupDirectory.deleteRecursively()
                    val hadExisting = finalDirectory.exists()
                    if (hadExisting) require(finalDirectory.renameTo(backupDirectory)) { "Could not prepare PDF replacement" }
                    val installed = staged.absoluteFile.parentFile?.renameTo(finalDirectory) == true
                    if (!installed) {
                        if (hadExisting) backupDirectory.renameTo(finalDirectory)
                        error("Could not store the selected PDF")
                    }
                    backupDirectory.deleteRecursively()
                    staged.copy(relativePath = "$attachmentId/original", absoluteFile = File(finalDirectory, "original"))
                }
            }
            imported.fold(
                onSuccess = { file ->
                    val count = withContext(Dispatchers.IO) { PdfDocumentEngine.pageCount(file.absoluteFile) }
                    _state.update {
                        it.copy(
                            attachmentId = attachmentId,
                            displayName = file.displayName,
                            sizeBytes = file.sizeBytes,
                            absolutePath = file.absoluteFile.absolutePath,
                            pageCount = count,
                            currentPage = 0,
                            selectedPages = emptySet(),
                            crops = emptyMap(),
                            busy = false
                        )
                    }
                    if (_state.value.committed) persistImportedReplacement(file.relativePath)
                },
                onFailure = { error ->
                    withContext(Dispatchers.IO) { AttachmentFileStore.deleteAttachmentTree(appContext, stagedId) }
                    _state.update { it.copy(busy = false, message = error.message ?: "Could not import PDF") }
                }
            )
        }
    }

    private suspend fun persistImportedReplacement(relativePath: String) {
        val state = _state.value
        val captureId = state.captureId ?: return
        when (actions.saveAttachment(
            captureItemId = captureId,
            displayName = state.displayName,
            mimeType = "application/pdf",
            sizeBytes = state.sizeBytes,
            relativePath = relativePath,
            kind = AttachmentKind.PDF
        )) {
            is ActionResult.Success -> Unit
            else -> _state.update { it.copy(message = "PDF bytes were replaced, but metadata could not be saved") }
        }
    }

    fun setMode(mode: PdfWorkspaceMode) {
        if (!_state.value.hasPdf && mode != PdfWorkspaceMode.HOME) return
        _state.update { it.copy(mode = mode, message = null) }
    }

    fun setShowPreviews(show: Boolean) = _state.update { it.copy(showPreviews = show) }
    fun setCurrentPage(page: Int) = _state.update {
        it.copy(currentPage = page.coerceIn(0, (it.pageCount - 1).coerceAtLeast(0)))
    }

    fun togglePage(page: Int) = _state.update {
        val next = it.selectedPages.toMutableSet().apply { if (!add(page)) remove(page) }
        it.copy(selectedPages = next)
    }

    fun clearSelection() = _state.update { it.copy(selectedPages = emptySet()) }

    fun selectRange(text: String) {
        parsePdfPageRange(text, _state.value.pageCount).fold(
            onSuccess = { pages -> _state.update { it.copy(selectedPages = pages, message = null) } },
            onFailure = { error -> _state.update { it.copy(message = error.message) } }
        )
    }

    fun setCrop(page: Int, crop: PdfCrop, applyToSelection: Boolean) = _state.update { state ->
        val targets = if (applyToSelection) state.selectedOrAll else listOf(page)
        state.copy(crops = state.crops.toMutableMap().apply { targets.forEach { put(it, crop) } })
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun saveOriginalToPage() {
        viewModelScope.launch {
            val state = _state.value
            if (!state.hasPdf) return@launch
            if (state.committed) {
                _state.update { it.copy(message = "PDF is already saved to this Page") }
                return@launch
            }
            _state.update { it.copy(busy = true, message = null) }
            val relative = relativePath(state.absolutePath!!) ?: run {
                _state.update { it.copy(busy = false, message = "Managed PDF path is invalid") }
                return@launch
            }
            when (val result = actions.createAttachment(
                displayName = state.displayName,
                mimeType = "application/pdf",
                sizeBytes = state.sizeBytes,
                relativePath = relative,
                kind = AttachmentKind.PDF,
                context = state.context,
                captureId = draftCaptureId,
                attachmentId = state.attachmentId ?: draftAttachmentId
            )) {
                is ActionResult.Success -> _state.update {
                    it.copy(
                        captureId = result.value.captureItemId,
                        attachmentId = result.value.id,
                        committed = true,
                        busy = false,
                        createdCaptureIds = listOf(result.value.captureItemId),
                        message = "PDF saved to Page"
                    )
                }
                else -> _state.update { it.copy(busy = false, message = "Could not save PDF") }
            }
        }
    }

    fun exportSelectionToPage(format: PdfExportFormat) {
        viewModelScope.launch {
            val state = _state.value
            val source = state.absolutePath?.let(::File) ?: return@launch
            val indexes = state.selectedOrAll
            _state.update { it.copy(busy = true, message = null) }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    when (format) {
                        PdfExportFormat.PDF -> listOf(createDerivedPdf(source, indexes, state.crops, state.context))
                        PdfExportFormat.PNG -> createDerivedImages(source, indexes, state.crops, state.context)
                    }
                }
            }
            outcome.fold(
                onSuccess = { ids -> _state.update { it.copy(busy = false, createdCaptureIds = ids, message = "Saved ${ids.size} item${if (ids.size == 1) "" else "s"} to Page") } },
                onFailure = { error -> _state.update { it.copy(busy = false, message = error.message ?: "Export failed") } }
            )
        }
    }

    private suspend fun createDerivedPdf(
        source: File,
        indexes: List<Int>,
        crops: Map<Int, PdfCrop>,
        context: CaptureContext
    ): String {
        val attachmentId = ids.newId("att")
        val captureId = ids.newId("cap")
        val output = File(AttachmentFileStore.attachmentsRoot(appContext), "$attachmentId/original")
        PdfDocumentEngine.exportPdf(source, output, indexes, crops)
        val name = _state.value.displayName.substringBeforeLast('.', "document") + "-extract.pdf"
        val result = actions.createAttachment(name, "application/pdf", output.length(), "$attachmentId/original", AttachmentKind.PDF, context, captureId, attachmentId)
        if (result !is ActionResult.Success) {
            AttachmentFileStore.deleteAttachmentTree(appContext, attachmentId)
            error("Could not save extracted PDF")
        }
        return captureId
    }

    private suspend fun createDerivedImages(
        source: File,
        indexes: List<Int>,
        crops: Map<Int, PdfCrop>,
        context: CaptureContext
    ): List<String> {
        val created = mutableListOf<String>()
        indexes.forEach { page ->
            val attachmentId = ids.newId("att")
            val captureId = ids.newId("cap")
            val output = File(AttachmentFileStore.attachmentsRoot(appContext), "$attachmentId/original")
            output.parentFile?.mkdirs()
            val bitmap = PdfDocumentEngine.renderPage(source, page, PdfDocumentEngine.DEFAULT_EXPORT_WIDTH, crops[page] ?: PdfCrop())
            try {
                FileOutputStream(output).use { stream ->
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
                    stream.fd.sync()
                }
            } finally {
                bitmap.recycle()
            }
            val result = actions.createAttachment(
                "${_state.value.displayName.substringBeforeLast('.', "document")}-page-${page + 1}.png",
                "image/png",
                output.length(),
                "$attachmentId/original",
                AttachmentKind.IMAGE,
                context,
                captureId,
                attachmentId
            )
            if (result !is ActionResult.Success) {
                AttachmentFileStore.deleteAttachmentTree(appContext, attachmentId)
                error("Could not save page ${page + 1}")
            }
            created += captureId
        }
        return created
    }

    private fun relativePath(absolutePath: String): String? {
        val root = AttachmentFileStore.attachmentsRoot(appContext).absolutePath
        return absolutePath.takeIf { it.startsWith(root) }
            ?.removePrefix(root)?.trimStart(File.separatorChar)?.replace('\\', '/')
    }

    companion object {
        fun factory(captureId: String?, taskId: String?, context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PdfWorkspaceViewModel(captureId, taskId, context.applicationContext) as T
            }
    }
}

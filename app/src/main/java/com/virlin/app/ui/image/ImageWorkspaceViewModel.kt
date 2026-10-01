package com.virlin.app.ui.image

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
import com.virlin.app.domain.model.AttachmentDocument
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.repository.WorkStreamRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImageWorkspaceItem(
    val capture: CaptureItem,
    val document: AttachmentDocument,
    val absolutePath: String,
)

data class ImageEditSnapshot(
    val adjustments: ImageAdjustments = ImageAdjustments(),
    val crop: ImageCrop = ImageCrop(),
    val transform: ImageTransform = ImageTransform(),
    val strokes: List<ImageMarkupStroke> = emptyList(),
)

data class ImageWorkspaceState(
    val taskId: String? = null,
    val taskTitle: String = "",
    val loading: Boolean = true,
    val importing: Boolean = false,
    val items: List<ImageWorkspaceItem> = emptyList(),
    val selected: ImageWorkspaceItem? = null,
    val mode: ImageWorkspaceMode = ImageWorkspaceMode.LIBRARY,
    val edit: ImageEditSnapshot = ImageEditSnapshot(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
)

/**
 * Task-scoped image workspace built entirely on managed Attachments.
 *
 * The edit session is memory-only and non-destructive. Only Save copy writes a new PNG attachment,
 * which means abandoning the screen cannot alter the imported original or leave edit metadata.
 */
class ImageWorkspaceViewModel(
    private val taskId: String?,
    private val initialCaptureId: String?,
    private val appContext: Context,
    private val actions: VirlinActions = VirlinGraph.actions,
    private val repository: WorkStreamRepository = VirlinGraph.repository,
    private val ids: IdProvider = VirlinGraph.ids,
) : ViewModel() {
    private val _state = MutableStateFlow(ImageWorkspaceState(taskId = taskId))
    val state: StateFlow<ImageWorkspaceState> = _state.asStateFlow()

    private val history = ArrayList<ImageEditSnapshot>()
    private var historyIndex = -1

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val captures = repository.captures.value.filter {
                it.type == CaptureType.FILE && (taskId == null || it.taskId == taskId)
            }
            val images = captures.mapNotNull { capture ->
                val document = actions.getAttachmentByCaptureId(capture.id) ?: return@mapNotNull null
                if (document.kind != AttachmentKind.IMAGE) return@mapNotNull null
                ImageWorkspaceItem(
                    capture,
                    document,
                    AttachmentFileStore.resolve(appContext, document.relativePath).absolutePath,
                )
            }.sortedBy { it.capture.createdAt }
            val selected = when {
                initialCaptureId != null -> images.firstOrNull { it.capture.id == initialCaptureId }
                else -> _state.value.selected?.let { old -> images.firstOrNull { it.capture.id == old.capture.id } }
            }
            _state.value = _state.value.copy(
                taskTitle = taskId?.let { repository.getTask(it)?.title }.orEmpty(),
                loading = false,
                items = images,
                selected = selected,
                mode = if (initialCaptureId != null && selected != null && _state.value.mode == ImageWorkspaceMode.LIBRARY) ImageWorkspaceMode.EDIT else _state.value.mode,
            )
            if (selected != null && history.isEmpty()) resetHistory()
        }
    }

    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true, message = null)
            var savedCount = 0
            val failures = mutableListOf<String>()
            uris.forEach { uri ->
                val attachmentId = ids.newId("att")
                val imported = runCatching {
                    withContext(Dispatchers.IO) { AttachmentFileStore.importFromUri(appContext, uri, attachmentId) }
                }
                imported.onSuccess { file ->
                    if (file.kind != AttachmentKind.IMAGE || file.sizeBytes <= 0L) {
                        withContext(Dispatchers.IO) { AttachmentFileStore.deleteAttachmentTree(appContext, attachmentId) }
                        failures += "${file.displayName}: not a readable image"
                    } else {
                        val result = actions.createAttachment(
                            displayName = file.displayName,
                            mimeType = file.mimeType,
                            sizeBytes = file.sizeBytes,
                            relativePath = file.relativePath,
                            kind = AttachmentKind.IMAGE,
                            context = CaptureContext(taskId = taskId),
                            attachmentId = attachmentId,
                        )
                        if (result is ActionResult.Success) savedCount++ else {
                            withContext(Dispatchers.IO) { AttachmentFileStore.deleteAttachmentTree(appContext, attachmentId) }
                            failures += "${file.displayName}: could not be saved"
                        }
                    }
                }.onFailure {
                    withContext(Dispatchers.IO) { AttachmentFileStore.deleteAttachmentTree(appContext, attachmentId) }
                    failures += "Selected image: ${it.message ?: "could not be read"}"
                }
            }
            _state.value = _state.value.copy(
                importing = false,
                message = when {
                    failures.isNotEmpty() -> "Added $savedCount image${if (savedCount == 1) "" else "s"}. ${failures.joinToString("; ")}"
                    else -> null
                },
            )
            refresh()
        }
    }

    fun edit(item: ImageWorkspaceItem) {
        _state.value = _state.value.copy(selected = item, mode = ImageWorkspaceMode.EDIT)
        resetHistory()
    }

    fun setMode(mode: ImageWorkspaceMode) {
        if (mode != ImageWorkspaceMode.LIBRARY && _state.value.selected == null) return
        _state.value = _state.value.copy(mode = mode)
    }

    fun updateAdjustments(value: ImageAdjustments) { _state.value = _state.value.copy(edit = _state.value.edit.copy(adjustments = value.normalized())) }
    fun updateCrop(value: ImageCrop) { push(_state.value.edit.copy(crop = value)) }
    fun rotate() { val t = _state.value.edit.transform; push(_state.value.edit.copy(transform = t.copy(rotationQuarterTurns = t.rotationQuarterTurns + 1).normalized())) }
    fun flipHorizontal() { val t = _state.value.edit.transform; push(_state.value.edit.copy(transform = t.copy(flipHorizontal = !t.flipHorizontal))) }
    fun flipVertical() { val t = _state.value.edit.transform; push(_state.value.edit.copy(transform = t.copy(flipVertical = !t.flipVertical))) }
    fun straighten(degrees: Float) { _state.value = _state.value.copy(edit = _state.value.edit.copy(transform = _state.value.edit.transform.copy(straightenDegrees = degrees).normalized())) }
    fun addStroke(stroke: ImageMarkupStroke) { push(_state.value.edit.copy(strokes = _state.value.edit.strokes + stroke)) }
    fun clearMarkup() { push(_state.value.edit.copy(strokes = emptyList())) }
    fun commitCurrent() = push(_state.value.edit)

    fun undo() { if (historyIndex > 0) applyHistory(--historyIndex) }
    fun redo() { if (historyIndex + 1 < history.size) applyHistory(++historyIndex) }
    fun reset() { push(ImageEditSnapshot()) }

    fun saveCopy() {
        val item = _state.value.selected ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, message = null)
            val attachmentId = ids.newId("att")
            val captureId = ids.newId("cap")
            val output = File(AttachmentFileStore.attachmentsRoot(appContext), "$attachmentId/original")
            val edit = _state.value.edit
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = ImageRenderEngine.render(File(item.absolutePath), edit.crop, edit.transform, edit.adjustments, edit.strokes)
                    try { ImageRenderEngine.savePng(bitmap, output) } finally { bitmap.recycle() }
                    val base = item.document.displayName.substringBeforeLast('.', item.document.displayName)
                    val result = actions.createAttachment(
                        displayName = "$base-edited.png",
                        mimeType = "image/png",
                        sizeBytes = output.length(),
                        relativePath = "$attachmentId/original",
                        kind = AttachmentKind.IMAGE,
                        context = CaptureContext(
                            projectId = item.capture.projectId,
                            workStreamId = item.capture.workStreamId,
                            taskId = item.capture.taskId,
                        ),
                        captureId = captureId,
                        attachmentId = attachmentId,
                    )
                    check(result is ActionResult.Success) { "Could not register edited image" }
                }
            }
            outcome.onFailure { withContext(Dispatchers.IO) { AttachmentFileStore.deleteAttachmentTree(appContext, attachmentId) } }
            _state.value = _state.value.copy(
                saving = false,
                message = outcome.fold({ "Edited copy saved to Page" }, { it.message ?: "Could not save edited copy" }),
                mode = if (outcome.isSuccess) ImageWorkspaceMode.LIBRARY else _state.value.mode,
            )
            if (outcome.isSuccess) refresh()
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    private fun resetHistory() {
        history.clear(); history += ImageEditSnapshot(); historyIndex = 0; applyHistory(0)
    }

    private fun push(snapshot: ImageEditSnapshot) {
        if (historyIndex >= 0 && history[historyIndex] == snapshot) return
        while (history.lastIndex > historyIndex) history.removeAt(history.lastIndex)
        history += snapshot
        historyIndex = history.lastIndex
        applyHistory(historyIndex)
    }

    private fun applyHistory(index: Int) {
        _state.value = _state.value.copy(
            edit = history[index], canUndo = index > 0, canRedo = index < history.lastIndex,
        )
    }

    companion object {
        fun factory(taskId: String?, captureId: String?, context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ImageWorkspaceViewModel(taskId, captureId, context.applicationContext) as T
            }
    }
}

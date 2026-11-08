package com.virlin.app.domain.action

import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.TaskPageBlock
import com.virlin.app.domain.model.TaskPageTypeKeys
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.domain.time.VirlinClock

/** Transactional mutation boundary for ordered task Page references. */
class TaskPageActions(
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
    private val ids: IdProvider,
) {
    suspend fun loadAndReconcile(taskId: String): List<TaskPageBlock> {
        repository.transaction {
            getTask(taskId) ?: return@transaction
            // Archived items were removed from the Page on purpose; restoring one brings it back.
            val captures = repository.captures.value.filter {
                it.taskId == taskId && it.status != CaptureStatus.ARCHIVED
            }
            if (stepsOf(taskId).isNotEmpty()) ensurePageBlock(taskId, TaskPageTypeKeys.TODO, taskId)
            if (noteDocOf(TaskPageTypeKeys.noteOwner(taskId)) != null) {
                ensurePageBlock(taskId, TaskPageTypeKeys.NOTE, TaskPageTypeKeys.noteOwner(taskId))
            }
            captures.sortedBy { it.createdAt }.forEach {
                ensurePageBlock(taskId, TaskPageTypeKeys.capture(it.type), it.id)
            }
            // A new note that was opened and left without a word was never saved: drop its card.
            val abandoned = pageBlocksOf(taskId).filter {
                it.typeKey == TaskPageTypeKeys.NOTE && TaskPageTypeKeys.isExtraNoteOwner(it.contentId) &&
                    noteDocOf(it.contentId) == null
            }
            if (abandoned.isNotEmpty()) {
                abandoned.forEach { deletePageBlock(it.id) }
                denseOrder(taskId, clock.now())
            }
        }
        return repository.getTaskPageBlocks(taskId)
    }

    /**
     * Where Add → Note writes: the task's own note while it has never been written, otherwise a
     * brand-new note of the same task. Its Page card exists from the start. Null if the task is gone.
     */
    suspend fun newNote(taskId: String): NewTaskNote? = repository.transaction {
        val task = getTask(taskId) ?: return@transaction null
        val canonical = TaskPageTypeKeys.noteOwner(taskId)
        if (noteDocOf(canonical) == null) {
            ensurePageBlock(taskId, TaskPageTypeKeys.NOTE, canonical)
            return@transaction NewTaskNote(canonical, task.title)
        }
        val owner = TaskPageTypeKeys.extraNoteOwner(taskId, ids.newId("note"))
        ensurePageBlock(taskId, TaskPageTypeKeys.NOTE, owner)
        val number = pageBlocksOf(taskId).count { it.typeKey == TaskPageTypeKeys.NOTE }
        NewTaskNote(owner, "${task.title} $number")
    }

    suspend fun ensure(taskId: String, typeKey: String, contentId: String): TaskPageBlock? =
        repository.transaction {
            if (getTask(taskId) == null) return@transaction null
            ensurePageBlock(taskId, typeKey, contentId)
        }

    /**
     * Copies the stored task note [sourceOwner] - blocks, rich tree, title - into a new note of the
     * same task, listed right after the original on its Page. Null when the source is not a task
     * note, has never been saved, cannot be read, or its task is gone; nothing is written then.
     */
    suspend fun duplicateNote(sourceOwner: String): NewTaskNote? = repository.transaction {
        val taskId = TaskPageTypeKeys.noteTaskId(sourceOwner) ?: return@transaction null
        val task = getTask(taskId) ?: return@transaction null
        val source = noteDocOf(sourceOwner) ?: return@transaction null
        if (source.loadError != null) return@transaction null
        ensurePageBlock(taskId, TaskPageTypeKeys.NOTE, sourceOwner)
        val now = clock.now()
        val owner = TaskPageTypeKeys.extraNoteOwner(taskId, ids.newId("note"))
        val title = "${source.title?.takeIf { it.isNotBlank() } ?: task.title} (copy)".take(MAX_NOTE_TITLE)
        saveNoteDoc(source.copy(ownerKey = owner, title = title, createdAt = now, updatedAt = now, revision = 0))
        val copy = ensurePageBlock(taskId, TaskPageTypeKeys.NOTE, owner)
        val ordered = pageBlocksOf(taskId).sortedBy { it.order }.filter { it.id != copy.id }.toMutableList()
        val after = ordered.indexOfFirst { it.typeKey == TaskPageTypeKeys.NOTE && it.contentId == sourceOwner }
        ordered.add(if (after >= 0) after + 1 else ordered.size, copy)
        ordered.forEachIndexed { index, block ->
            if (block.order != index) savePageBlock(block.copy(order = index, updatedAt = now))
        }
        NewTaskNote(owner, title)
    }

    suspend fun reorder(taskId: String, orderedIds: List<String>): Boolean = repository.transaction {
        val current = pageBlocksOf(taskId)
        if (orderedIds.size != current.size || orderedIds.toSet() != current.map { it.id }.toSet()) {
            return@transaction false
        }
        val byId = current.associateBy { it.id }
        val now = clock.now()
        orderedIds.forEachIndexed { index, id -> savePageBlock(byId.getValue(id).copy(order = index, updatedAt = now)) }
        true
    }

    suspend fun move(blockId: String, destinationTaskId: String): Boolean = repository.transaction {
        val block = getPageBlock(blockId) ?: return@transaction false
        val destination = getTask(destinationTaskId) ?: return@transaction false
        val source = getTask(block.taskId) ?: return@transaction false
        if (source.projectId != destination.projectId) return@transaction false
        val existing = pageBlocksOf(destinationTaskId)
        if (existing.any { it.typeKey == block.typeKey && it.contentId == block.contentId }) return@transaction false
        val now = clock.now()
        if (block.typeKey.startsWith("capture.")) {
            val capture = getCapture(block.contentId) ?: return@transaction false
            saveCapture(capture.copy(projectId = destination.projectId, workStreamId = destination.workStreamId,
                taskId = destination.id, updatedAt = now))
        } else return@transaction false // aggregate task-owned content needs an explicit merge policy
        savePageBlock(block.copy(taskId = destinationTaskId, order = existing.size, updatedAt = now))
        denseOrder(block.taskId, now)
        true
    }

    /**
     * Moves a saved task note to another task of the same project: its whole document is stored
     * under a new note of the destination, listed last on that Page, and only then removed from
     * the source, all in one transaction. Returns the note's new owner key, or null when the note
     * is empty, unreadable, already there, or the destination is not allowed; nothing is written then.
     */
    suspend fun moveNote(blockId: String, destinationTaskId: String): String? = repository.transaction {
        val block = getPageBlock(blockId) ?: return@transaction null
        if (block.typeKey != TaskPageTypeKeys.NOTE || block.taskId == destinationTaskId) return@transaction null
        if (TaskPageTypeKeys.noteTaskId(block.contentId) != block.taskId) return@transaction null
        val source = getTask(block.taskId) ?: return@transaction null
        val destination = getTask(destinationTaskId) ?: return@transaction null
        if (source.projectId != destination.projectId) return@transaction null
        val doc = noteDocOf(block.contentId) ?: return@transaction null
        if (doc.loadError != null) return@transaction null
        val now = clock.now()
        val owner = TaskPageTypeKeys.extraNoteOwner(destination.id, ids.newId("note"))
        saveNoteDoc(doc.copy(ownerKey = owner, updatedAt = now, revision = 0))
        ensurePageBlock(destination.id, TaskPageTypeKeys.NOTE, owner)
        deleteNoteDoc(block.contentId)
        deletePageBlock(block.id)
        denseOrder(block.taskId, now)
        owner
    }

    suspend fun duplicate(blockId: String, destinationTaskId: String): Boolean = repository.transaction {
        val block = getPageBlock(blockId) ?: return@transaction false
        val destination = getTask(destinationTaskId) ?: return@transaction false
        val source = getTask(block.taskId) ?: return@transaction false
        if (source.projectId != destination.projectId || !block.typeKey.startsWith("capture.")) return@transaction false
        val original = getCapture(block.contentId) ?: return@transaction false
        val now = clock.now()
        val copyId = ids.newId("cap")
        val sameTask = destination.id == block.taskId
        val copyTitle = if (sameTask) original.title?.takeIf { it.isNotBlank() }?.let { "$it (copy)".take(MAX_NOTE_TITLE) }
            else original.title
        val copy = original.copy(id = copyId, title = copyTitle ?: original.title, projectId = destination.projectId,
            workStreamId = destination.workStreamId, taskId = destination.id,
            convertedTaskId = null, archivedAt = null, createdAt = now, updatedAt = now)
        saveCapture(copy)
        getNoteByCaptureId(original.id)?.let { saveNoteDocument(it.copy(id = ids.newId("note"), captureItemId = copyId, createdAt = now, updatedAt = now)) }
        noteDocOf("capture-${original.id}")?.let { saveNoteDoc(it.copy(ownerKey = "capture-$copyId", createdAt = now, updatedAt = now)) }
        getPromptByCaptureId(original.id)?.let { savePromptDocument(it.copy(id = ids.newId("prompt"), captureItemId = copyId, createdAt = now, updatedAt = now)) }
        getAttachmentByCaptureId(original.id)?.let { saveAttachmentDocument(it.copy(id = ids.newId("attachment"), captureItemId = copyId, createdAt = now, updatedAt = now)) }
        getVoiceByCaptureId(original.id)?.let { saveVoiceDocument(it.copy(id = ids.newId("voice"), captureItemId = copyId, createdAt = now, updatedAt = now)) }
        val page = ensureTaskPageBlock(ids, clock, destination.id, TaskPageTypeKeys.capture(copy.type), copy.id)
        if (sameTask) placeAfter(block.taskId, page, block.id, now)
        true
    }

    /**
     * Duplicate on the Page itself: the copy is listed right after the original. Notes copy their
     * whole document; captured items copy with their documents. The checklist is the task's own
     * steps, so it has no second copy on the same task.
     */
    suspend fun duplicateHere(blockId: String): Boolean {
        val block = repository.transaction { getPageBlock(blockId) } ?: return false
        return when {
            block.typeKey == TaskPageTypeKeys.NOTE -> duplicateNote(block.contentId) != null
            block.typeKey.startsWith("capture.") -> duplicate(block.id, block.taskId)
            else -> false
        }
    }

    /**
     * Removes one card from its Page once the user confirmed by typing its name. A note's document
     * is deleted; a captured item is archived, so it stays restorable from the Inbox. The checklist
     * and anything unreadable are refused, and nothing is written then.
     */
    suspend fun delete(blockId: String): Boolean = repository.transaction {
        val block = getPageBlock(blockId) ?: return@transaction false
        val now = clock.now()
        when {
            block.typeKey == TaskPageTypeKeys.NOTE -> {
                if (TaskPageTypeKeys.noteTaskId(block.contentId) != block.taskId) return@transaction false
                val doc = noteDocOf(block.contentId)
                if (doc?.loadError != null) return@transaction false
                if (doc != null) deleteNoteDoc(block.contentId)
            }
            block.typeKey.startsWith("capture.") -> {
                val capture = getCapture(block.contentId)
                if (capture != null && capture.status != CaptureStatus.ARCHIVED) {
                    saveCapture(capture.copy(status = CaptureStatus.ARCHIVED, archivedAt = now, updatedAt = now))
                }
            }
            else -> return@transaction false
        }
        deletePageBlock(block.id)
        denseOrder(block.taskId, now)
        true
    }

    private suspend fun WorkStreamWriter.placeAfter(taskId: String, moved: TaskPageBlock, afterId: String, now: java.time.Instant) {
        val ordered = pageBlocksOf(taskId).sortedBy { it.order }.filter { it.id != moved.id }.toMutableList()
        val after = ordered.indexOfFirst { it.id == afterId }
        ordered.add(if (after >= 0) after + 1 else ordered.size, moved)
        ordered.forEachIndexed { index, item ->
            if (item.order != index) savePageBlock(item.copy(order = index, updatedAt = now))
        }
    }

    private suspend fun WorkStreamWriter.denseOrder(taskId: String, now: java.time.Instant) {
        pageBlocksOf(taskId).sortedBy { it.order }.forEachIndexed { index, block ->
            if (block.order != index) savePageBlock(block.copy(order = index, updatedAt = now))
        }
    }

    private suspend fun WorkStreamWriter.ensurePageBlock(
        taskId: String,
        typeKey: String,
        contentId: String,
    ): TaskPageBlock {
        pageBlocksOf(taskId).firstOrNull { it.typeKey == typeKey && it.contentId == contentId }?.let { return it }
        val now = clock.now()
        return TaskPageBlock(ids.newId("page"), taskId, typeKey, contentId,
            pageBlocksOf(taskId).size, now, now).also { savePageBlock(it) }
    }
}

/** The note Add → Note opens: its document key and the title it starts with. */
data class NewTaskNote(val ownerKey: String, val title: String)

private const val MAX_NOTE_TITLE = 120

/** Used inside canonical creation transactions, including every task-scoped capture type. */
internal suspend fun WorkStreamWriter.ensureTaskPageBlock(
    ids: IdProvider,
    clock: VirlinClock,
    taskId: String,
    typeKey: String,
    contentId: String,
): TaskPageBlock {
    pageBlocksOf(taskId).firstOrNull { it.typeKey == typeKey && it.contentId == contentId }?.let { return it }
    val now = clock.now()
    return TaskPageBlock(ids.newId("page"), taskId, typeKey, contentId,
        pageBlocksOf(taskId).size, now, now).also { savePageBlock(it) }
}

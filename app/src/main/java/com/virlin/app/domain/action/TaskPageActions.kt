package com.virlin.app.domain.action

import com.virlin.app.domain.id.IdProvider
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
            val captures = repository.captures.value.filter { it.taskId == taskId }
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

    suspend fun duplicate(blockId: String, destinationTaskId: String): Boolean = repository.transaction {
        val block = getPageBlock(blockId) ?: return@transaction false
        val destination = getTask(destinationTaskId) ?: return@transaction false
        val source = getTask(block.taskId) ?: return@transaction false
        if (source.projectId != destination.projectId || !block.typeKey.startsWith("capture.")) return@transaction false
        val original = getCapture(block.contentId) ?: return@transaction false
        val now = clock.now()
        val copyId = ids.newId("cap")
        val copy = original.copy(id = copyId, projectId = destination.projectId,
            workStreamId = destination.workStreamId, taskId = destination.id,
            convertedTaskId = null, archivedAt = null, createdAt = now, updatedAt = now)
        saveCapture(copy)
        getNoteByCaptureId(original.id)?.let { saveNoteDocument(it.copy(id = ids.newId("note"), captureItemId = copyId, createdAt = now, updatedAt = now)) }
        noteDocOf("capture-${original.id}")?.let { saveNoteDoc(it.copy(ownerKey = "capture-$copyId", createdAt = now, updatedAt = now)) }
        getPromptByCaptureId(original.id)?.let { savePromptDocument(it.copy(id = ids.newId("prompt"), captureItemId = copyId, createdAt = now, updatedAt = now)) }
        getAttachmentByCaptureId(original.id)?.let { saveAttachmentDocument(it.copy(id = ids.newId("attachment"), captureItemId = copyId, createdAt = now, updatedAt = now)) }
        getVoiceByCaptureId(original.id)?.let { saveVoiceDocument(it.copy(id = ids.newId("voice"), captureItemId = copyId, createdAt = now, updatedAt = now)) }
        ensureTaskPageBlock(ids, clock, destination.id, TaskPageTypeKeys.capture(copy.type), copy.id)
        true
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

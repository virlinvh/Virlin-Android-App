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
        }
        return repository.getTaskPageBlocks(taskId)
    }

    suspend fun ensure(taskId: String, typeKey: String, contentId: String): TaskPageBlock? =
        repository.transaction {
            if (getTask(taskId) == null) return@transaction null
            ensurePageBlock(taskId, typeKey, contentId)
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

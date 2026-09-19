package com.virlin.app.domain.action

import com.virlin.app.domain.capture.LinkUrl
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.domain.time.VirlinClock

/**
 * Capture operations (Pass 10). Composed INTO [DefaultVirlinActions] like [StructureActions].
 * Capture is preservation, not execution: nothing here touches WorkStream state, Focus,
 * activeTaskId, alarms or notifications. Content is stored verbatim; no parsing, no guessing
 * of context. Convert-to-Task creates the Task through [StructureActions] in the SAME
 * transaction so a failed creation never leaves a capture marked ORGANIZED.
 */
internal class CaptureActions(
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
    private val ids: IdProvider,
    private val structure: StructureActions
) {

    suspend fun createCapture(r: CreateCapture): ActionResult<CaptureItem> = tx { createCaptureIn(this, r) }

    /** Same rules as [createCapture], for composing into another writer transaction (e.g. Text Note). */
    suspend fun createCaptureIn(w: WorkStreamWriter, r: CreateCapture): ActionResult<CaptureItem> = with(w) {
        val content = r.content.trim()
        val url = when (r.type) {
            CaptureType.LINK -> LinkUrl.canonicalOrNull(r.sourceUrl.orEmpty())
            else -> r.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
        }
        when (r.type) {
            CaptureType.NOTE, CaptureType.PROMPT -> if (content.isEmpty()) return ActionResult.Rejected(DomainError.EmptyCapture)
            CaptureType.LINK -> if (url == null) return ActionResult.Rejected(DomainError.InvalidLink)
            CaptureType.FILE, CaptureType.VOICE -> if (content.isEmpty()) return ActionResult.Rejected(DomainError.EmptyCapture)
        }
        val ctx = resolveContext(r.context) ?: return contextError(r.context)
        val now = clock.now()
        val item = CaptureItem(
            id = r.id ?: ids.newId("cap"), type = r.type, content = content,
            title = r.title?.trim()?.takeIf { it.isNotEmpty() }, sourceUrl = if (r.type == CaptureType.LINK) url else null,
            projectId = ctx.projectId, workStreamId = ctx.workStreamId, taskId = ctx.taskId,
            status = CaptureStatus.INBOX, createdAt = now, updatedAt = now
        )
        saveCapture(item)
        ActionResult.Success(item)
    }

    suspend fun updateCapture(id: String, u: CaptureUpdate): ActionResult<CaptureItem> = tx {
        val c = getCapture(id) ?: return@tx ActionResult.Rejected(DomainError.CaptureNotFound(id))
        val content = u.content.applyTo(c.content)?.trim() ?: ""
        val urlRaw = u.sourceUrl.applyTo(c.sourceUrl)
        val url = when (c.type) {
            CaptureType.LINK -> LinkUrl.canonicalOrNull(urlRaw.orEmpty())
            else -> urlRaw?.trim()?.takeIf { it.isNotEmpty() }
        }
        when (c.type) {
            CaptureType.NOTE, CaptureType.PROMPT -> if (content.isEmpty()) return@tx ActionResult.Rejected(DomainError.EmptyCapture)
            CaptureType.LINK -> if (url == null) return@tx ActionResult.Rejected(DomainError.InvalidLink)
            CaptureType.FILE, CaptureType.VOICE -> if (content.isEmpty()) return@tx ActionResult.Rejected(DomainError.EmptyCapture)
        }
        val updated = c.copy(content = content, title = u.title.applyTo(c.title)?.trim()?.takeIf { it.isNotEmpty() },
            sourceUrl = if (c.type == CaptureType.LINK) url else null, updatedAt = clock.now())
        saveCapture(updated)
        ActionResult.Success(updated)
    }

    /** ATTACH: the capture stays a capture, gains explicit context. Null context detaches. */
    suspend fun attachCapture(id: String, context: CaptureContext): ActionResult<CaptureItem> = tx {
        val c = getCapture(id) ?: return@tx ActionResult.Rejected(DomainError.CaptureNotFound(id))
        val ctx = resolveContext(context) ?: return@tx contextError(context)
        val updated = c.copy(projectId = ctx.projectId, workStreamId = ctx.workStreamId, taskId = ctx.taskId, updatedAt = clock.now())
        saveCapture(updated)
        ActionResult.Success(updated)
    }

    suspend fun archiveCapture(id: String): ActionResult<CaptureItem> = tx {
        val c = getCapture(id) ?: return@tx ActionResult.Rejected(DomainError.CaptureNotFound(id))
        if (c.status == CaptureStatus.ARCHIVED) return@tx ActionResult.Success(c)
        val now = clock.now()
        val updated = c.copy(status = CaptureStatus.ARCHIVED, archivedAt = now, updatedAt = now)
        saveCapture(updated)
        ActionResult.Success(updated)
    }

    /** Back to the Inbox from ARCHIVED (ORGANIZED stays organized — the Task exists). */
    suspend fun restoreCapture(id: String): ActionResult<CaptureItem> = tx {
        val c = getCapture(id) ?: return@tx ActionResult.Rejected(DomainError.CaptureNotFound(id))
        if (c.status != CaptureStatus.ARCHIVED) return@tx ActionResult.Rejected(DomainError.CaptureNotArchived)
        val updated = c.copy(status = CaptureStatus.INBOX, archivedAt = null, updatedAt = clock.now())
        saveCapture(updated)
        ActionResult.Success(updated)
    }

    /**
     * CONVERT: create a real Task (same rules as CREATE mode) and mark the capture ORGANIZED,
     * atomically. A capture converts at most once.
     */
    suspend fun convertCaptureToTask(id: String, target: CaptureTaskTarget): ActionResult<Task> = tx {
        val c = getCapture(id) ?: return@tx ActionResult.Rejected(DomainError.CaptureNotFound(id))
        if (c.status == CaptureStatus.ORGANIZED) return@tx ActionResult.Rejected(DomainError.CaptureAlreadyOrganized)
        val title = (c.title ?: c.content.lineSequence().firstOrNull() ?: "").trim().ifEmpty { c.sourceUrl ?: "" }
        val notes = buildString {
            if (c.content.isNotBlank() && c.content.trim() != title) append(c.content)
            c.sourceUrl?.let { if (isNotEmpty()) append('\n'); append(it) }
        }.takeIf { it.isNotBlank() }
        val request = CreateTask(
            title = title, description = notes,
            workStreamId = target.workStreamId, projectId = target.projectId, parentTaskId = target.parentTaskId
        )
        val created = structure.createTaskIn(this, request)
        val task = when (created) {
            is ActionResult.Success -> created.value
            is ActionResult.Rejected -> return@tx ActionResult.Rejected(created.reason)
            is ActionResult.NotFound -> return@tx created
            is ActionResult.Failure -> return@tx created
        }
        val now = clock.now()
        saveCapture(c.copy(status = CaptureStatus.ORGANIZED, convertedTaskId = task.id,
            projectId = task.projectId, workStreamId = task.workStreamId, taskId = task.id, updatedAt = now))
        ActionResult.Success(task)
    }

    // ------------------------------------------------------------------ helpers

    private data class Resolved(val projectId: String?, val workStreamId: String?, val taskId: String?)

    /** Validate the explicit context against a consistent view; derive ancestry, never trust the form. */
    private suspend fun WorkStreamWriter.resolveContext(ctx: CaptureContext?): Resolved? {
        if (ctx == null || ctx.isEmpty) return Resolved(null, null, null)
        ctx.taskId?.let { tid ->
            val task = getTask(tid) ?: return null
            if (ctx.workStreamId != null && ctx.workStreamId != task.workStreamId) return null
            if (ctx.projectId != null && ctx.projectId != task.projectId) return null
            return Resolved(task.projectId, task.workStreamId, task.id)
        }
        ctx.workStreamId?.let { wid ->
            val ws = getStream(wid) ?: return null
            if (ctx.projectId != null && ctx.projectId != ws.projectId) return null
            return Resolved(ws.projectId, ws.id, null)
        }
        ctx.projectId?.let { pid -> getProject(pid) ?: return null; return Resolved(pid, null, null) }
        return Resolved(null, null, null)
    }

    private suspend fun WorkStreamWriter.contextError(ctx: CaptureContext?): ActionResult<CaptureItem> {
        ctx ?: return ActionResult.Rejected(DomainError.OwnershipMismatch)
        ctx.taskId?.let { if (getTask(it) == null) return ActionResult.Rejected(DomainError.TaskNotFound(it)) }
        ctx.workStreamId?.let { if (getStream(it) == null) return ActionResult.NotFound(it) }
        ctx.projectId?.let { if (getProject(it) == null) return ActionResult.Rejected(DomainError.ProjectNotFound(it)) }
        return ActionResult.Rejected(DomainError.OwnershipMismatch)
    }

    private suspend fun <T> tx(block: suspend WorkStreamWriter.() -> ActionResult<T>): ActionResult<T> =
        try { repository.transaction(block) } catch (e: Exception) { ActionResult.Failure(e) }
}

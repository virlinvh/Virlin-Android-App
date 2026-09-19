package com.virlin.app.domain.action

import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.PromptDocument
import com.virlin.app.domain.prompt.PromptDocumentCodec
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.domain.time.VirlinClock

/**
 * Prompt document ops. Capture lifecycle stays on CaptureItem; this writes [PromptDocument]
 * and keeps Capture title/content preview in sync for Inbox.
 */
internal class PromptActions(
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
    private val ids: IdProvider,
    private val capture: CaptureActions
) {

    suspend fun createPrompt(
        title: String?,
        description: String?,
        tags: List<String>,
        blocks: List<NoteBlock>,
        context: CaptureContext = CaptureContext.None,
        captureId: String? = null,
        promptId: String? = null
    ): ActionResult<PromptDocument> = tx {
        val now = clock.now()
        val doc = PromptDocument(
            id = promptId ?: ids.newId("prm"),
            captureItemId = captureId ?: ids.newId("cap"),
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            blocks = blocks.ifEmpty { listOf(NoteBlock(ids.newId("blk"), NoteBlockType.TEXT)) },
            createdAt = now,
            updatedAt = now
        )
        if (!doc.hasMeaningfulContent()) return@tx ActionResult.Rejected(DomainError.EmptyCapture)
        val preview = PromptDocumentCodec.preview(doc).ifBlank { "Prompt" }
        val create = CreateCapture(
            type = CaptureType.PROMPT,
            content = preview,
            title = doc.title,
            context = context,
            id = doc.captureItemId
        )
        when (val r = capture.createCaptureIn(this, create)) {
            is ActionResult.Success -> Unit
            is ActionResult.Rejected -> return@tx ActionResult.Rejected(r.reason)
            is ActionResult.NotFound -> return@tx r
            is ActionResult.Failure -> return@tx r
        }
        savePromptDocument(doc)
        ActionResult.Success(doc)
    }

    suspend fun savePrompt(
        captureItemId: String,
        title: String?,
        description: String?,
        tags: List<String>,
        blocks: List<NoteBlock>
    ): ActionResult<PromptDocument> = tx {
        val cap = getCapture(captureItemId)
            ?: return@tx ActionResult.Rejected(DomainError.CaptureNotFound(captureItemId))
        if (cap.type != CaptureType.PROMPT) return@tx ActionResult.Rejected(DomainError.NotAPrompt)
        val now = clock.now()
        val existing = getPromptByCaptureId(captureItemId)
        val doc = (existing ?: PromptDocument(
            id = ids.newId("prm"),
            captureItemId = captureItemId,
            title = null,
            blocks = emptyList(),
            createdAt = cap.createdAt,
            updatedAt = now
        )).copy(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            blocks = blocks,
            updatedAt = now
        )
        if (!doc.hasMeaningfulContent()) return@tx ActionResult.Rejected(DomainError.EmptyCapture)
        val preview = PromptDocumentCodec.preview(doc).ifBlank { "Prompt" }
        saveCapture(
            cap.copy(
                title = doc.title,
                content = preview,
                updatedAt = now
            )
        )
        savePromptDocument(doc)
        ActionResult.Success(doc)
    }

    suspend fun getOrHydratePrompt(captureItemId: String): ActionResult<PromptDocument> {
        getPromptDocumentByCaptureId(captureItemId)?.let { return ActionResult.Success(it) }
        val cap = repository.getCapture(captureItemId)
            ?: return ActionResult.Rejected(DomainError.CaptureNotFound(captureItemId))
        if (cap.type != CaptureType.PROMPT) return ActionResult.Rejected(DomainError.NotAPrompt)
        val now = clock.now()
        val blocks = if (cap.content.isNotBlank()) {
            listOf(NoteBlock(id = ids.newId("blk"), type = NoteBlockType.TEXT, plainText = cap.content))
        } else {
            listOf(NoteBlock(id = ids.newId("blk"), type = NoteBlockType.TEXT))
        }
        return ActionResult.Success(
            PromptDocument(
                id = ids.newId("prm"),
                captureItemId = captureItemId,
                title = cap.title,
                blocks = blocks,
                createdAt = cap.createdAt,
                updatedAt = now
            )
        )
    }

    suspend fun getPromptDocumentByCaptureId(captureItemId: String): PromptDocument? =
        repository.getPromptByCaptureId(captureItemId)

    private suspend fun <T> tx(block: suspend WorkStreamWriter.() -> ActionResult<T>): ActionResult<T> =
        try {
            repository.transaction(block)
        } catch (e: Exception) {
            ActionResult.Failure(e)
        }
}

package com.virlin.app.domain.action

import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.NoteDocument
import com.virlin.app.domain.note.NotePlainTextSerializer
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.domain.time.VirlinClock

/**
 * Text Note document ops. Capture lifecycle stays on [CaptureItem]; this writes [NoteDocument]
 * and keeps Capture title/content preview in sync for Inbox.
 */
internal class NoteActions(
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
    private val ids: IdProvider,
    private val capture: CaptureActions
) {

    /**
     * Persist a new Text Note once it has meaningful content. Creates CaptureItem(NOTE) + NoteDocument
     * in one transaction. Empty drafts must not call this.
     */
    suspend fun createTextNote(
        title: String?,
        blocks: List<NoteBlock>,
        context: CaptureContext = CaptureContext.None,
        captureId: String? = null,
        noteId: String? = null
    ): ActionResult<NoteDocument> = tx {
        val now = clock.now()
        val doc = NoteDocument(
            id = noteId ?: ids.newId("note"),
            captureItemId = captureId ?: ids.newId("cap"),
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            blocks = blocks.ifEmpty { listOf(NoteBlock(ids.newId("blk"), NoteBlockType.TEXT)) },
            createdAt = now,
            updatedAt = now
        )
        if (!doc.hasMeaningfulContent()) return@tx ActionResult.Rejected(DomainError.EmptyCapture)
        val preview = NotePlainTextSerializer.preview(doc).ifBlank { "Text note" }
        val create = CreateCapture(
            type = CaptureType.NOTE,
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
        saveNoteDocument(doc)
        ActionResult.Success(doc)
    }

    /** Update existing note + Capture preview/title. Creates the NoteDocument row if hydrating a legacy NOTE. */
    suspend fun saveTextNote(
        captureItemId: String,
        title: String?,
        blocks: List<NoteBlock>
    ): ActionResult<NoteDocument> = tx {
        val cap = getCapture(captureItemId)
            ?: return@tx ActionResult.Rejected(DomainError.CaptureNotFound(captureItemId))
        if (cap.type != CaptureType.NOTE) return@tx ActionResult.Rejected(DomainError.NotATextNote)
        val now = clock.now()
        val existing = getNoteByCaptureId(captureItemId)
        val doc = (existing ?: NoteDocument(
            id = ids.newId("note"),
            captureItemId = captureItemId,
            title = null,
            blocks = emptyList(),
            createdAt = cap.createdAt,
            updatedAt = now
        )).copy(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            blocks = blocks,
            updatedAt = now
        )
        if (!doc.hasMeaningfulContent()) return@tx ActionResult.Rejected(DomainError.EmptyCapture)
        val preview = NotePlainTextSerializer.preview(doc).ifBlank { "Text note" }
        saveCapture(
            cap.copy(
                title = doc.title,
                content = preview,
                updatedAt = now
            )
        )
        saveNoteDocument(doc)
        ActionResult.Success(doc)
    }

    /**
     * Open path: load note, or hydrate a legacy plain NOTE into a document (not yet persisted
     * until [saveTextNote]).
     */
    suspend fun getOrHydrateTextNote(captureItemId: String): ActionResult<NoteDocument> {
        getNoteDocumentByCaptureId(captureItemId)?.let { return ActionResult.Success(it) }
        val cap = repository.getCapture(captureItemId)
            ?: return ActionResult.Rejected(DomainError.CaptureNotFound(captureItemId))
        if (cap.type != CaptureType.NOTE) return ActionResult.Rejected(DomainError.NotATextNote)
        val now = clock.now()
        val blocks = if (cap.content.isNotBlank()) {
            listOf(NoteBlock(id = ids.newId("blk"), type = NoteBlockType.TEXT, plainText = cap.content))
        } else {
            listOf(NoteBlock(id = ids.newId("blk"), type = NoteBlockType.TEXT))
        }
        val hydrated = NoteDocument(
            id = ids.newId("note"),
            captureItemId = captureItemId,
            title = cap.title,
            blocks = blocks,
            createdAt = cap.createdAt,
            updatedAt = now
        )
        return ActionResult.Success(hydrated)
    }

    suspend fun getNoteDocumentByCaptureId(captureItemId: String): NoteDocument? =
        repository.getNoteByCaptureId(captureItemId)

    private suspend fun <T> tx(block: suspend WorkStreamWriter.() -> ActionResult<T>): ActionResult<T> =
        try {
            repository.transaction(block)
        } catch (e: Exception) {
            ActionResult.Failure(e)
        }
}

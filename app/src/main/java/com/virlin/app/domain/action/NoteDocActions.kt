package com.virlin.app.domain.action

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.VirlinNoteDoc
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import com.virlin.app.domain.model.*
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.domain.time.VirlinClock

/**
 * THE NOTES PAGE'S DOCUMENTS.
 *
 * Ordinary task/project workspace keys remain independent documents. Capture/draft keys use
 * a validated transactional adapter: rich document, Capture lifecycle and Knowledge preview
 * commit together. Legacy content is imported lazily, not rewritten simply by opening it.
 */
internal class NoteDocActions(
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
    private val ids: IdProvider,
    private val captureActions: CaptureActions,
) {

    /** The stored document, or null when this owner has never been written. */
    suspend fun loadNoteDoc(ownerKey: String): VirlinNoteDoc? {
        val captureId = CaptureNoteBridge.captureId(ownerKey) ?: return repository.getNoteDoc(ownerKey)
        repository.getNoteDoc(CaptureNoteBridge.owner(captureId))?.let { return it }
        val capture = repository.getCapture(captureId)?.takeIf { it.type == CaptureType.NOTE } ?: return null
        val legacy = repository.getNotesByCaptureIds(listOf(captureId))[captureId]
        return VirlinNoteDoc(CaptureNoteBridge.owner(captureId), legacy?.title ?: capture.title,
            CaptureNoteBridge.fromLegacy(legacy?.blocks ?: listOf(NoteBlock("$captureId-text", NoteBlockType.TEXT, plainText = capture.content.orEmpty()))),
            capture.createdAt, capture.updatedAt)
    }

    /**
     * Writes the whole document for [ownerKey], creating it on first save.
     *
     * Idempotent and transactional: saving identical content returns the stored document without
     * a write, so autosave cannot inflate the revision or the timestamp on every recomposition.
     * `createdAt` is preserved across saves; `revision` increments only on a real change.
     */
    suspend fun saveNoteDoc(
        ownerKey: String,
        title: String?,
        blocks: List<NoteDocBlock>,
    ): ActionResult<VirlinNoteDoc> = tx {
        if (ownerKey.isBlank()) return@tx ActionResult.Rejected(DomainError.EmptyTitle)
        val captureId = CaptureNoteBridge.captureId(ownerKey)
        val canonicalOwner = captureId?.let(CaptureNoteBridge::owner) ?: ownerKey
        if (captureId != null) {
            val previous = getCapture(captureId)
            if (previous != null && previous.type != CaptureType.NOTE) return@tx ActionResult.Rejected(DomainError.NotATextNote)
            val unchanged = noteDocOf(canonicalOwner)
            if (previous != null && unchanged != null && unchanged.title == title && unchanged.blocks == blocks) return@tx ActionResult.Success(unchanged)
            val legacyBlocks = CaptureNoteBridge.toLegacy(blocks)
            // Untouched empty Knowledge drafts are not saved merely by closing the page.
            if (previous == null && legacyBlocks.none { it.hasMeaningfulContent() }) {
                if (CaptureNoteBridge.draftProject(ownerKey) == null) return@tx ActionResult.Rejected(DomainError.CaptureNotFound(captureId))
                return@tx ActionResult.Success(VirlinNoteDoc(canonicalOwner, title, blocks))
            }
            val now = clock.now()
            val oldNote = getNoteByCaptureId(captureId)
            if (oldNote != null && unchanged == null && noteDocOf("capture-legacy-$captureId") == null) {
                // Preserve the exact original payload (including collapsed nested toggles) for recovery.
                saveNoteDoc(VirlinNoteDoc("capture-legacy-$captureId", oldNote.title,
                    listOf(NoteDocBlock("source", com.virlin.app.domain.notedoc.NoteDocBlockType.CODE,
                        com.virlin.app.domain.note.NoteDocumentCodec.encodePayload(oldNote.blocks))),
                    oldNote.createdAt, now))
            }
            val note = NoteDocument(oldNote?.id ?: ids.newId("note"), captureId, title, legacyBlocks, oldNote?.createdAt ?: now, now)
            val preview = com.virlin.app.domain.note.NotePlainTextSerializer.preview(note).ifBlank { "Note" }
            if (previous == null) {
                val projectId = CaptureNoteBridge.draftProject(ownerKey) ?: return@tx ActionResult.Rejected(DomainError.CaptureNotFound(captureId))
                when (val created = captureActions.createCaptureIn(this, CreateCapture(CaptureType.NOTE,
                    content = preview, title = title, context = CaptureContext(projectId = projectId), id = captureId))) {
                    is ActionResult.Success -> Unit
                    is ActionResult.Rejected -> return@tx ActionResult.Rejected(created.reason)
                    is ActionResult.NotFound -> return@tx created
                    is ActionResult.Failure -> return@tx created
                }
            } else saveCapture(previous.copy(title = title, content = preview, updatedAt = now))
            saveNoteDocument(note)
        }
        val existing = noteDocOf(canonicalOwner)
        if (existing != null && existing.title == title && existing.blocks == blocks) {
            return@tx ActionResult.Success(existing)
        }
        val now = clock.now()
        val doc = VirlinNoteDoc(
            ownerKey = canonicalOwner,
            title = title,
            blocks = blocks,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            revision = (existing?.revision ?: 0) + 1,
        )
        saveNoteDoc(doc)
        ActionResult.Success(doc)
    }

    suspend fun duplicateCaptureNote(captureId: String, title: String): ActionResult<CaptureItem> = tx {
        val original = getCapture(captureId) ?: return@tx ActionResult.Rejected(DomainError.CaptureNotFound(captureId))
        if (original.type != CaptureType.NOTE) return@tx ActionResult.Rejected(DomainError.NotATextNote)
        val id = ids.newId("cap")
        val now = clock.now()
        val result = captureActions.createCaptureIn(this, CreateCapture(CaptureType.NOTE, content = original.content,
            title = title, context = CaptureContext(original.projectId, original.workStreamId, original.taskId), id = id))
        if (result !is ActionResult.Success) return@tx result
        getNoteByCaptureId(captureId)?.let { saveNoteDocument(it.copy(id = ids.newId("note"), captureItemId = id, title = title, createdAt = now, updatedAt = now)) }
        noteDocOf(CaptureNoteBridge.owner(captureId))?.let { saveNoteDoc(it.copy(ownerKey = CaptureNoteBridge.owner(id), title = title, createdAt = now, updatedAt = now)) }
        ActionResult.Success(result.value)
    }

    private suspend fun <T> tx(block: suspend WorkStreamWriter.() -> ActionResult<T>): ActionResult<T> =
        try { repository.transaction(block) } catch (e: Exception) { ActionResult.Failure(e) }
}

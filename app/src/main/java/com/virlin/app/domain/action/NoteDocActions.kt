package com.virlin.app.domain.action

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.VirlinNoteDoc
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import com.virlin.app.domain.notedoc.rich.RichJson
import com.virlin.app.domain.notedoc.rich.RichNoteAdapter
import com.virlin.app.domain.model.*
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.domain.time.VirlinClock

/** User-facing rejection messages for note saves. Shown verbatim by the editors. */
object NoteDocMessages {
    const val STALE = "Note changed on the phone. Reload before saving."
    const val DAMAGED = "This note could not be read. Recover it before editing."
    const val RICH_STRUCTURE = "This change affects a nested part of this note (a table, a nested list, an " +
        "equation or a block inside another block) that can't be changed this way. Nothing was saved."
    const val INVALID_DOCUMENT = "The note editor produced an invalid document; nothing was saved."
}

/**
 * THE NOTES PAGE'S DOCUMENTS.
 *
 * Ordinary task/project workspace keys remain independent documents. Capture/draft keys use
 * a validated transactional adapter: rich document, Capture lifecycle and Knowledge preview
 * commit together. Legacy content is imported lazily, not rewritten simply by opening it.
 *
 * Rich (v2) documents are written by [saveRichNoteDoc]. Block-level writers ([saveNoteDoc]: the
 * desktop and older editors) are merged into the rich tree by [RichNoteAdapter.applyBlockEdits]
 * and refused when the merge cannot be expressed. A damaged stored document is never
 * overwritten except by the explicit recovery path.
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
     * Writes the whole document for [ownerKey] from flat blocks, creating it on first save.
     *
     * Idempotent and transactional: saving identical content returns the stored document without
     * a write, so autosave cannot inflate the revision or the timestamp on every recomposition.
     * `createdAt` is preserved across saves; `revision` increments only on a real change.
     */
    suspend fun saveNoteDoc(
        ownerKey: String,
        title: String?,
        blocks: List<NoteDocBlock>,
        expectedRevision: Int? = null,
    ): ActionResult<VirlinNoteDoc> = save(ownerKey, title, expectedRevision, replaceDamaged = false) { stored ->
        val tree = stored?.richDocument ?: return@save Content(blocks, null)
        val merged = RichNoteAdapter.applyBlockEdits(tree, blocks)
            ?: return@save null
        Content(RichNoteAdapter.project(merged), merged)
    }

    /** Writes a rich (format v2) document. Its block projection is derived, never supplied. */
    suspend fun saveRichNoteDoc(
        ownerKey: String,
        title: String?,
        richDocument: String,
        expectedRevision: Int?,
        replaceDamaged: Boolean = false,
    ): ActionResult<VirlinNoteDoc> {
        val canonical = try {
            RichNoteAdapter.canonical(richDocument)
        } catch (e: RichJson.MalformedJson) {
            return ActionResult.Rejected(DomainError.PlacementRejected(NoteDocMessages.INVALID_DOCUMENT))
        }
        val blocks = RichNoteAdapter.project(canonical)
        return save(ownerKey, title, expectedRevision, replaceDamaged) { Content(blocks, canonical) }
    }

    /**
     * Stores [payload] under a new recovery owner key and returns that key. A valid rich tree is
     * stored as a readable note; anything else is kept verbatim in a code block.
     */
    suspend fun saveNoteRecoveryCopy(ownerKey: String, reason: String, payload: String): ActionResult<String> = tx {
        val now = clock.now()
        val key = "note-recovery-${ownerKey.take(80)}-${now.toEpochMilli()}-${ids.newId("r")}"
        val tree = runCatching { RichNoteAdapter.canonical(payload) }.getOrNull()
            ?: RichNoteAdapter.recoveryDocument("Recovered from $ownerKey: $reason", payload)
        saveNoteDoc(VirlinNoteDoc(key, "Recovery: $reason", RichNoteAdapter.project(tree), now, now, 1, richDocument = tree))
        ActionResult.Success(key)
    }

    /**
     * Recovers a damaged note: the raw stored payload is first copied to a recovery owner, then
     * the note is replaced by the salvaged text. Never reinterprets literal Markdown.
     */
    suspend fun recoverDamagedNote(ownerKey: String): ActionResult<VirlinNoteDoc> {
        val stored = loadNoteDoc(ownerKey) ?: return ActionResult.Rejected(DomainError.PlacementRejected(NoteDocMessages.DAMAGED))
        val raw = stored.damagedRaw ?: return ActionResult.Success(stored)
        when (val backup = saveNoteRecoveryCopy(stored.ownerKey, "damaged note", raw)) {
            is ActionResult.Success -> Unit
            is ActionResult.Rejected -> return ActionResult.Rejected(backup.reason)
            is ActionResult.NotFound -> return backup
            is ActionResult.Failure -> return backup
        }
        return saveRichNoteDoc(stored.ownerKey, stored.title, RichNoteAdapter.salvage(raw), stored.revision, replaceDamaged = true)
    }

    private class Content(val blocks: List<NoteDocBlock>, val richDocument: String?)

    private suspend fun save(
        ownerKey: String,
        title: String?,
        expectedRevision: Int?,
        replaceDamaged: Boolean,
        content: (VirlinNoteDoc?) -> Content?,
    ): ActionResult<VirlinNoteDoc> = tx {
        if (ownerKey.isBlank()) return@tx ActionResult.Rejected(DomainError.EmptyTitle)
        val captureId = CaptureNoteBridge.captureId(ownerKey)
        val canonicalOwner = captureId?.let(CaptureNoteBridge::owner) ?: ownerKey
        val stored = noteDocOf(canonicalOwner)
        if (expectedRevision != null && (stored?.revision ?: 0) != expectedRevision)
            return@tx ActionResult.Rejected(DomainError.PlacementRejected(NoteDocMessages.STALE))
        if (stored?.loadError != null && !replaceDamaged)
            return@tx ActionResult.Rejected(DomainError.PlacementRejected(NoteDocMessages.DAMAGED))
        val next = content(stored?.takeIf { it.loadError == null })
            ?: return@tx ActionResult.Rejected(DomainError.PlacementRejected(NoteDocMessages.RICH_STRUCTURE))
        val blocks = next.blocks
        val rich = next.richDocument
        fun sameAs(doc: VirlinNoteDoc?) = doc != null && doc.loadError == null && doc.title == title &&
            doc.blocks == blocks && doc.richDocument == rich
        if (captureId != null) {
            val previous = getCapture(captureId)
            if (previous != null && previous.type != CaptureType.NOTE) return@tx ActionResult.Rejected(DomainError.NotATextNote)
            if (previous != null && sameAs(stored)) return@tx ActionResult.Success(stored!!)
            val legacyBlocks = CaptureNoteBridge.toLegacy(blocks)
            // Untouched empty Knowledge drafts are not saved merely by closing the page.
            if (previous == null && legacyBlocks.none { it.hasMeaningfulContent() }) {
                if (CaptureNoteBridge.draftProject(ownerKey) == null) return@tx ActionResult.Rejected(DomainError.CaptureNotFound(captureId))
                return@tx ActionResult.Success(VirlinNoteDoc(canonicalOwner, title, blocks, richDocument = rich))
            }
            val now = clock.now()
            val oldNote = getNoteByCaptureId(captureId)
            if (oldNote != null && stored == null && noteDocOf("capture-legacy-$captureId") == null) {
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
                    content = preview, title = title, context = CaptureContext(projectId = projectId,
                        workStreamId = CaptureNoteBridge.draftWorkstream(ownerKey)), id = captureId))) {
                    is ActionResult.Success -> Unit
                    is ActionResult.Rejected -> return@tx ActionResult.Rejected(created.reason)
                    is ActionResult.NotFound -> return@tx created
                    is ActionResult.Failure -> return@tx created
                }
            } else saveCapture(previous.copy(title = title, content = preview, updatedAt = now))
            saveNoteDocument(note)
        }
        if (sameAs(stored)) return@tx ActionResult.Success(stored!!)
        val now = clock.now()
        val doc = VirlinNoteDoc(
            ownerKey = canonicalOwner,
            title = title,
            blocks = blocks,
            createdAt = stored?.createdAt ?: now,
            updatedAt = now,
            revision = (stored?.revision ?: 0) + 1,
            richDocument = rich,
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

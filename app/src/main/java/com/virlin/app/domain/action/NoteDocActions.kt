package com.virlin.app.domain.action

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.VirlinNoteDoc
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.domain.time.VirlinClock

/**
 * THE NOTES PAGE'S DOCUMENTS.
 *
 * A self-contained feature. These actions read and write only the `virlin_notes` table: they
 * never touch a capture note, a [com.virlin.app.domain.model.NoteDocument], or the legacy plain
 * `tasks.notes` string, and nothing is imported from them. How this document eventually relates
 * to those surfaces is a later decision, so no coupling is created here.
 *
 * [ownerKey] is opaque. These actions do not resolve it, validate it against a task or project,
 * or care what produced it — which is exactly what lets the owner be decided later without
 * rewriting storage.
 */
class NoteDocActions(
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
) {

    /** The stored document, or null when this owner has never been written. */
    suspend fun loadNoteDoc(ownerKey: String): VirlinNoteDoc? = repository.getNoteDoc(ownerKey)

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
        val existing = noteDocOf(ownerKey)
        if (existing != null && existing.title == title && existing.blocks == blocks) {
            return@tx ActionResult.Success(existing)
        }
        val now = clock.now()
        val doc = VirlinNoteDoc(
            ownerKey = ownerKey,
            title = title,
            blocks = blocks,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            revision = (existing?.revision ?: 0) + 1,
        )
        saveNoteDoc(doc)
        ActionResult.Success(doc)
    }

    private suspend fun <T> tx(block: suspend WorkStreamWriter.() -> ActionResult<T>): ActionResult<T> =
        try { repository.transaction(block) } catch (e: Exception) { ActionResult.Failure(e) }
}

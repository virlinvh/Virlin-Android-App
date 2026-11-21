package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteRuns

/**
 * A card edit carried into the card's note, ready for the caller to save (the note is never
 * written here). Only edits that land on an exact, unique piece of the note are applied; anything
 * that cannot be placed is counted in [skipped] and left out rather than guessed.
 */
internal data class FlashcardNoteSyncPlan(
    val blocks: List<NoteDocBlock>,
    /** Note blocks whose text, formatting or font changed. */
    val changed: Int,
    /** New card parts (pictures, lines) added to the note next to the passage they sit by. */
    val inserted: Int,
    /** Edited or added card parts that have no place in the note. */
    val skipped: Int,
    /** Parts removed from the card; the note keeps them, it never loses text this way. */
    val keptInNote: Int,
) {
    val hasChanges: Boolean get() = changed + inserted > 0
}

/** Turns the card's own edit switch into the dialog's "update the note too" option. */
internal class FlashcardNoteUpdate(
    val on: Boolean,
    val onToggle: (Boolean) -> Unit,
    /** The faces exactly as edited (before card ids are renumbered), handed over just before save. */
    val onEditedFaces: (front: List<NoteDocBlock>, back: List<NoteDocBlock>) -> Unit,
)

private class NoteSpot(val noteId: String, val offset: Int)

/**
 * [oldFaces] are the faces the editor opened with and [newFaces] the same faces as edited, in the
 * same order; card blocks keep their ids while edited, so an id present in both is the same part.
 * Each old text part is found in the note as an exact, unique substring of one block (starting
 * from [sourceBlockId]); a changed part replaces that substring with its new text and runs,
 * keeping the rest of the note block as it was. A new part goes right after the card part before
 * it (or before the one after it); a new picture on a face with no part from the note goes after
 * the card's source block. Nothing is ever deleted from the note.
 */
internal fun planFlashcardNoteSync(
    note: List<NoteDocBlock>,
    sourceBlockId: String?,
    oldFaces: List<List<NoteDocBlock>>,
    newFaces: List<List<NoteDocBlock>>,
    newId: () -> String,
): FlashcardNoteSyncPlan {
    val blocks = note.toMutableList()
    var changed = 0
    var inserted = 0
    var skipped = 0
    var kept = 0
    val anchorId = sourceBlockId?.takeIf { id -> note.any { it.id == id } }
    oldFaces.zip(newFaces).forEach { (old, new) ->
        val spots = HashMap<String, NoteSpot>()
        val used = HashSet<String>()
        var from = anchorId?.let { id -> blocks.indexOfFirst { it.id == id } }?.coerceAtLeast(0) ?: 0
        old.forEach { part ->
            if (!part.type.isTextual || part.text.isBlank()) return@forEach
            val spot = findSpot(blocks, part.text, from, used) ?: findSpot(blocks, part.text, 0, used)
                ?: return@forEach
            spots[part.id] = spot
            used += spot.noteId
            from = blocks.indexOfFirst { it.id == spot.noteId }
        }
        val oldById = old.associateBy { it.id }
        val newIds = new.mapTo(HashSet()) { it.id }
        old.forEach { if (it.id !in newIds && it.hasFlashcardContent()) kept++ }

        new.forEach { part ->
            val before = oldById[part.id] ?: return@forEach
            if (part.text == before.text && NoteRuns.normalize(part.runs, part.text.length) ==
                NoteRuns.normalize(before.runs, before.text.length)) return@forEach
            val spot = spots[part.id]
            val at = spot?.let { s -> blocks.indexOfFirst { it.id == s.noteId } } ?: -1
            val target = blocks.getOrNull(at)
            if (spot == null || target == null ||
                !target.text.regionMatches(spot.offset, before.text, 0, before.text.length)) {
                skipped++
                return@forEach
            }
            blocks[at] = replaceSpan(target, spot.offset, before.text.length, part)
            changed++
        }

        val placed = HashMap<String, String>().apply { spots.forEach { (cardId, s) -> put(cardId, s.noteId) } }
        new.forEachIndexed { index, part ->
            if (part.id in oldById || !part.hasFlashcardContent()) return@forEachIndexed
            val after = (index - 1 downTo 0).firstNotNullOfOrNull { placed[new[it].id] }
                ?: anchorId.takeIf { !part.type.isTextual && placed.isEmpty() }
            val beforeId = if (after == null) (index + 1 until new.size).firstNotNullOfOrNull { placed[new[it].id] } else null
            val neighbourAt = blocks.indexOfFirst { it.id == (after ?: beforeId) }
            if (neighbourAt < 0) { skipped++; return@forEachIndexed }
            val created = part.copy(id = newId(), indent = blocks[neighbourAt].indent, isCollapsed = false)
            blocks.add(if (after != null) neighbourAt + 1 else neighbourAt, created)
            placed[part.id] = created.id
            inserted++
        }
    }
    return FlashcardNoteSyncPlan(blocks, changed, inserted, skipped, kept)
}

/** The only place in one note block holding [text], or null when it is missing or repeated. */
private fun findSpot(blocks: List<NoteDocBlock>, text: String, from: Int, used: Set<String>): NoteSpot? {
    for (i in from.coerceAtLeast(0) until blocks.size) {
        val block = blocks[i]
        if (!block.type.isTextual || block.id in used) continue
        val at = block.text.indexOf(text)
        if (at >= 0 && block.text.indexOf(text, at + 1) < 0) return NoteSpot(block.id, at)
    }
    return null
}

/** [block] with `[offset, offset + length)` replaced by [part]'s text and runs; the rest keeps its runs. */
private fun replaceSpan(block: NoteDocBlock, offset: Int, length: Int, part: NoteDocBlock): NoteDocBlock {
    val end = offset + length
    val delta = part.text.length - length
    val text = block.text.substring(0, offset) + part.text + block.text.substring(end)
    val runs = ArrayList<NoteDocRun>()
    block.runs.forEach { r ->
        if (r.start < offset) runs += r.copy(end = minOf(r.end, offset))
        if (r.end > end) runs += r.copy(start = maxOf(r.start, end) + delta, end = r.end + delta)
    }
    part.runs.forEach { r -> runs += r.copy(start = r.start + offset, end = r.end + offset) }
    return block.copy(text = text, runs = NoteRuns.normalize(runs, text.length))
}

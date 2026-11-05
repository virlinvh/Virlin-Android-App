package com.virlin.app.domain.notedoc

import androidx.compose.ui.text.TextRange

/** Selects from the original passage without consuming it, so either side can be reassigned. */
fun flashcardSideFromSelection(source: NoteDocBlock, selection: TextRange): NoteDocBlock? =
    flashcardSelection(source, selection.start, selection.end)?.copy(
        id = "flashcard-side", type = NoteDocBlockType.PARAGRAPH)

/** Keeps copied marks aligned while the user edits a card side. */
fun editFlashcardSide(side: NoteDocBlock, newText: String): NoteDocBlock {
    val (at, removed, inserted) = NoteRuns.diff(side.text, newText)
    return side.copy(text = newText,
        runs = NoteRuns.shiftForEdit(side.runs, at, removed, inserted, newText.length))
}

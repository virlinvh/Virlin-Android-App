package com.virlin.app.domain.notedoc

/** A frozen copy of the selected note text and only the formatting that intersects it. */
fun flashcardSelection(block: NoteDocBlock, start: Int, end: Int): NoteDocBlock? {
    if (!block.type.isTextual) return null
    val from = minOf(start, end).coerceIn(0, block.text.length)
    val to = maxOf(start, end).coerceIn(0, block.text.length)
    if (from == to || block.text.substring(from, to).isBlank()) return null
    val selectedRuns = block.runs.mapNotNull { run ->
        val left = maxOf(run.start, from)
        val right = minOf(run.end, to)
        if (left >= right) null else run.copy(start = left - from, end = right - from)
    }
    return block.copy(id = "flashcard-content", text = block.text.substring(from, to),
        runs = NoteRuns.normalize(selectedRuns, to - from), indent = 0, isCollapsed = false)
}

package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocBlock

/**
 * The block [id] together with every block folded away under it: the blocks that follow it
 * in the note and are not shown until the next shown block. Selecting a folded heading or
 * toggle therefore selects what it hides.
 */
internal fun blockWithFolded(blocks: List<NoteDocBlock>, shownIds: Set<String>, id: String): List<String> {
    val at = blocks.indexOfFirst { it.id == id }
    if (at < 0) return emptyList()
    val result = arrayListOf(id)
    var i = at + 1
    while (i < blocks.size && blocks[i].id !in shownIds) result += blocks[i++].id
    return result
}

/** The shown blocks from [fromId] to [toId] in either order, each with what it folds away. */
internal fun blockRange(blocks: List<NoteDocBlock>, shown: List<NoteDocBlock>, fromId: String, toId: String): Set<String> {
    val a = shown.indexOfFirst { it.id == fromId }
    val b = shown.indexOfFirst { it.id == toId }
    if (a < 0 || b < 0) return emptySet()
    val shownIds = shown.mapTo(HashSet()) { it.id }
    return (minOf(a, b)..maxOf(a, b)).flatMapTo(LinkedHashSet()) { blockWithFolded(blocks, shownIds, shown[it].id) }
}

/** The index range of [selected] in [blocks] when the selected blocks sit next to each other. */
internal fun contiguousBlockRange(blocks: List<NoteDocBlock>, selected: Set<String>): IntRange? {
    val indices = blocks.indices.filter { blocks[it].id in selected }
    if (indices.isEmpty()) return null
    return if (indices.last() - indices.first() + 1 == indices.size) indices.first()..indices.last() else null
}

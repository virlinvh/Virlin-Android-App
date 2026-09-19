package com.virlin.app.domain.note

import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.NoteDocument

/**
 * Deterministic plain-text projection for Copy All and CaptureItem.content preview.
 * No internal ids/marks metadata.
 */
object NotePlainTextSerializer {

    fun serialize(doc: NoteDocument, includeTitle: Boolean = true): String = buildString {
        if (includeTitle) {
            val t = doc.title?.trim().orEmpty()
            if (t.isNotEmpty()) {
                append(t)
                append('\n')
                append('\n')
            }
        }
        appendBlocks(doc.blocks, numberedCounters = mutableMapOf())
    }.trimEnd()

    /** Short Inbox preview (first meaningful lines). */
    fun preview(doc: NoteDocument, maxChars: Int = 160): String {
        val full = serialize(doc, includeTitle = true)
        if (full.isBlank()) return ""
        return if (full.length <= maxChars) full else full.take(maxChars - 1).trimEnd() + "…"
    }

    private fun StringBuilder.appendBlocks(
        blocks: List<NoteBlock>,
        indent: String = "",
        numberedCounters: MutableMap<Int, Int>
    ) {
        var depth = indent.length / 2
        for (b in blocks) {
            when (b.type) {
                NoteBlockType.DIVIDER -> {
                    append(indent).append("────────").append('\n')
                    numberedCounters[depth] = 0
                }
                NoteBlockType.BULLETED_LIST -> {
                    append(indent).append("• ").append(b.plainText).append('\n')
                    numberedCounters[depth] = 0
                }
                NoteBlockType.NUMBERED_LIST -> {
                    val n = (numberedCounters[depth] ?: 0) + 1
                    numberedCounters[depth] = n
                    append(indent).append(n).append(". ").append(b.plainText).append('\n')
                }
                NoteBlockType.CHECKBOX -> {
                    append(indent).append(if (b.checked) "✓ " else "☐ ").append(b.plainText).append('\n')
                    numberedCounters[depth] = 0
                }
                NoteBlockType.QUOTE, NoteBlockType.CALLOUT -> {
                    append(indent).append("“").append(b.plainText).append("”").append('\n')
                    numberedCounters[depth] = 0
                }
                NoteBlockType.CODE -> {
                    append(indent).append("```").append('\n')
                    b.plainText.lineSequence().forEach { append(indent).append(it).append('\n') }
                    append(indent).append("```").append('\n')
                    numberedCounters[depth] = 0
                }
                NoteBlockType.TOGGLE -> {
                    append(indent).append("▸ ").append(b.plainText).append('\n')
                    numberedCounters[depth] = 0
                    if (b.children.isNotEmpty()) {
                        appendBlocks(b.children, indent + "  ", numberedCounters)
                    }
                }
                NoteBlockType.HEADING_1, NoteBlockType.HEADING_2, NoteBlockType.HEADING_3, NoteBlockType.TEXT -> {
                    append(indent).append(b.plainText).append('\n')
                    numberedCounters[depth] = 0
                }
            }
            if (b.type != NoteBlockType.NUMBERED_LIST) {
                // keep numbered sequence only across consecutive numbered items
            }
        }
    }
}

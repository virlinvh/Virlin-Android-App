package com.virlin.app.domain.note

import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.NoteDocument

/**
 * Intermediate model for PDF layout (no Android types). [NotePdfExporter] on Android renders this.
 */
data class NotePdfDocumentModel(
    val title: String?,
    val includeDate: Boolean,
    val dateLabel: String?,
    val lines: List<NotePdfLine>
)

sealed class NotePdfLine {
    data class Heading(val level: Int, val text: String) : NotePdfLine()
    data class Body(val text: String) : NotePdfLine()
    data class Bullet(val text: String) : NotePdfLine()
    data class Numbered(val index: Int, val text: String) : NotePdfLine()
    data class Checkbox(val checked: Boolean, val text: String) : NotePdfLine()
    data class Quote(val text: String) : NotePdfLine()
    data class Callout(val text: String) : NotePdfLine()
    data class Code(val text: String) : NotePdfLine()
    data object Divider : NotePdfLine()
    data class ToggleTitle(val text: String) : NotePdfLine()
}

object NotePdfModelBuilder {
    fun build(
        doc: NoteDocument,
        includeTitle: Boolean = true,
        includeDate: Boolean = true,
        dateLabel: String? = null
    ): NotePdfDocumentModel {
        val lines = mutableListOf<NotePdfLine>()
        appendBlocks(doc.blocks, lines, numbered = mutableMapOf())
        return NotePdfDocumentModel(
            title = if (includeTitle) doc.title?.trim()?.takeIf { it.isNotEmpty() } else null,
            includeDate = includeDate,
            dateLabel = dateLabel,
            lines = lines
        )
    }

    private fun appendBlocks(
        blocks: List<NoteBlock>,
        out: MutableList<NotePdfLine>,
        numbered: MutableMap<Int, Int>,
        depth: Int = 0
    ) {
        for (b in blocks) {
            when (b.type) {
                NoteBlockType.HEADING_1 -> out += NotePdfLine.Heading(1, b.plainText)
                NoteBlockType.HEADING_2 -> out += NotePdfLine.Heading(2, b.plainText)
                NoteBlockType.HEADING_3 -> out += NotePdfLine.Heading(3, b.plainText)
                NoteBlockType.TEXT -> if (b.plainText.isNotEmpty()) out += NotePdfLine.Body(b.plainText)
                NoteBlockType.BULLETED_LIST -> {
                    out += NotePdfLine.Bullet(b.plainText); numbered[depth] = 0
                }
                NoteBlockType.NUMBERED_LIST -> {
                    val n = (numbered[depth] ?: 0) + 1
                    numbered[depth] = n
                    out += NotePdfLine.Numbered(n, b.plainText)
                }
                NoteBlockType.CHECKBOX -> out += NotePdfLine.Checkbox(b.checked, b.plainText)
                NoteBlockType.QUOTE -> out += NotePdfLine.Quote(b.plainText)
                NoteBlockType.CALLOUT -> out += NotePdfLine.Callout(b.plainText)
                NoteBlockType.CODE -> out += NotePdfLine.Code(b.plainText)
                NoteBlockType.DIVIDER -> out += NotePdfLine.Divider
                NoteBlockType.TOGGLE -> {
                    out += NotePdfLine.ToggleTitle(b.plainText)
                    if (b.children.isNotEmpty()) appendBlocks(b.children, out, numbered, depth + 1)
                }
            }
            if (b.type != NoteBlockType.NUMBERED_LIST) numbered[depth] = 0
        }
    }
}

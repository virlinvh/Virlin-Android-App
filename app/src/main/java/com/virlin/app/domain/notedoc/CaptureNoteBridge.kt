package com.virlin.app.domain.notedoc

import com.virlin.app.domain.model.*

/** Stable capture ownership, independent of where the user later organizes the capture. */
object CaptureNoteBridge {
    fun owner(captureId: String) = "capture-$captureId"
    fun draftOwner(projectId: String, captureId: String) = "knowledge/${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(projectId.toByteArray(Charsets.UTF_8))}/$captureId"
    fun draftWorkstreamOwner(projectId: String, workStreamId: String, captureId: String) =
        draftOwner(projectId, captureId) + "/" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(workStreamId.toByteArray(Charsets.UTF_8))
    fun captureId(owner: String): String? = when {
        owner.startsWith("capture-") -> owner.removePrefix("capture-").takeIf { it.isNotBlank() }
        owner.startsWith("knowledge/") -> owner.split('/').takeIf { it.size == 3 || it.size == 4 }?.get(2)?.takeIf { it.isNotBlank() }
        else -> null
    }
    fun draftProject(owner: String): String? = owner.split('/').takeIf { (it.size == 3 || it.size == 4) && it.first() == "knowledge" }?.get(1)
        ?.let { runCatching { String(java.util.Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
    fun draftWorkstream(owner: String): String? = owner.split('/').takeIf { it.size == 4 && it.first() == "knowledge" }?.get(3)
        ?.let { runCatching { String(java.util.Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }

    /** Expand legacy toggles; keep child order, indentation, text, marks and checked states. */
    fun fromLegacy(blocks: List<NoteBlock>, depth: Int = 0): List<NoteDocBlock> = blocks.flatMap { block ->
        val type = when (block.type) {
            NoteBlockType.HEADING_1 -> NoteDocBlockType.HEADING_1
            NoteBlockType.HEADING_2 -> NoteDocBlockType.HEADING_2
            NoteBlockType.HEADING_3 -> NoteDocBlockType.HEADING_3
            NoteBlockType.BULLETED_LIST -> NoteDocBlockType.BULLET
            NoteBlockType.NUMBERED_LIST -> NoteDocBlockType.NUMBERED
            NoteBlockType.CHECKBOX -> NoteDocBlockType.CHECKLIST
            NoteBlockType.DIVIDER -> NoteDocBlockType.DIVIDER
            NoteBlockType.QUOTE -> NoteDocBlockType.QUOTE
            NoteBlockType.CALLOUT -> NoteDocBlockType.PROMPT
            NoteBlockType.CODE -> NoteDocBlockType.CODE
            else -> NoteDocBlockType.PARAGRAPH
        }
        val runs = block.marks.map { mark -> NoteDocRun(mark.start, mark.end,
            marks = when (mark.style) {
                InlineStyle.BOLD -> setOf(NoteDocMark.BOLD)
                InlineStyle.ITALIC -> setOf(NoteDocMark.ITALIC)
                InlineStyle.UNDERLINE -> setOf(NoteDocMark.UNDERLINE)
                InlineStyle.STRIKETHROUGH -> setOf(NoteDocMark.STRIKE)
                InlineStyle.CODE -> setOf(NoteDocMark.CODE)
                else -> emptySet()
            }, highlight = if (mark.style == InlineStyle.HIGHLIGHT) 0xFFFFF59DL else null,
            linkUrl = mark.linkUrl) }
        listOf(NoteDocBlock(block.id, type, block.plainText, runs, indent = depth, checked = block.checked)) + fromLegacy(block.children, depth + 1)
    }

    /** Compatibility projection for Knowledge previews/checklist organization; rich data stays intact. */
    fun toLegacy(blocks: List<NoteDocBlock>): List<NoteBlock> = blocks.map { block ->
        val type = when (block.type) {
            NoteDocBlockType.HEADING_1 -> NoteBlockType.HEADING_1
            NoteDocBlockType.HEADING_2 -> NoteBlockType.HEADING_2
            NoteDocBlockType.HEADING_3 -> NoteBlockType.HEADING_3
            NoteDocBlockType.BULLET -> NoteBlockType.BULLETED_LIST
            NoteDocBlockType.NUMBERED -> NoteBlockType.NUMBERED_LIST
            NoteDocBlockType.CHECKLIST -> NoteBlockType.CHECKBOX
            NoteDocBlockType.DIVIDER -> NoteBlockType.DIVIDER
            NoteDocBlockType.QUOTE -> NoteBlockType.QUOTE
            NoteDocBlockType.CODE -> NoteBlockType.CODE
            NoteDocBlockType.PROMPT -> NoteBlockType.CALLOUT
            else -> NoteBlockType.TEXT
        }
        NoteBlock(block.id, type,
            plainText = block.table?.let { table -> (0 until table.rows).joinToString("\n") { row ->
                (0 until table.cols).joinToString("\t") { column -> table.cell(row, column) }
            } } ?: if (block.type == NoteDocBlockType.IMAGE) "[Image]" else block.text,
            checked = block.checked,
            marks = block.runs.flatMap { run -> run.marks.map { mark -> TextMark(run.start, run.end, when (mark) {
                NoteDocMark.BOLD -> InlineStyle.BOLD
                NoteDocMark.ITALIC -> InlineStyle.ITALIC
                NoteDocMark.UNDERLINE -> InlineStyle.UNDERLINE
                NoteDocMark.STRIKE -> InlineStyle.STRIKETHROUGH
                NoteDocMark.CODE -> InlineStyle.CODE
            }) } + listOfNotNull(run.linkUrl?.let { TextMark(run.start, run.end, InlineStyle.LINK, it) }) })
    }
}

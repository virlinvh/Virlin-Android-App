package com.virlin.app.domain.notedoc

/**
 * Recognises chat assistants' copyable prompt cards in an already-parsed paste.
 *
 * Copying such a response yields the card as ordinary paragraphs followed by the card's own
 * button label ("Copy prompt"), usually led by a short header line. Only when that label is
 * present are the text blocks directly above it — never code, equations, headings, tables or
 * images — turned into one PROMPT block, the header becoming its title. Without the label the
 * blocks are returned unchanged.
 */
internal object ChatPromptCards {
    private const val MARKER = "copy prompt"
    private val BODY_TYPES = setOf(NoteDocBlockType.PARAGRAPH, NoteDocBlockType.BULLET,
        NoteDocBlockType.NUMBERED, NoteDocBlockType.CHECKLIST, NoteDocBlockType.QUOTE)

    fun group(blocks: List<NoteDocBlock>): List<NoteDocBlock> {
        if (blocks.none { isMarker(it) || trailingMarker(it) }) return blocks
        val out = ArrayList<NoteDocBlock>(blocks.size)
        for (block in expandTrailingMarkers(blocks)) {
            if (!isMarker(block)) { out += block; continue }
            var start = out.size
            while (start > 0 && out[start - 1].type in BODY_TYPES && !isMarker(out[start - 1])) start--
            val collected = out.subList(start, out.size).toList()
            val labelAt = collected.indices.firstOrNull { isLabel(collected[it]) && it < collected.lastIndex }
            val body = if (labelAt != null) collected.drop(labelAt + 1) else collected
            if (body.isEmpty()) { out += block; continue }
            val keep = if (labelAt != null) collected.take(labelAt) else emptyList()
            repeat(out.size - start) { out.removeAt(out.lastIndex) }
            out += keep
            out += prompt(labelAt?.let { collected[it].text.trim() }, body)
        }
        return out
    }

    private fun isMarker(block: NoteDocBlock) =
        block.type == NoteDocBlockType.PARAGRAPH && block.text.trim().equals(MARKER, ignoreCase = true)

    private fun trailingMarker(block: NoteDocBlock): Boolean {
        if (block.type != NoteDocBlockType.PARAGRAPH || '\n' !in block.text) return false
        val lines = block.text.trimEnd().lines()
        return lines.size > 1 && lines.last().trim().equals(MARKER, ignoreCase = true)
    }

    /** "…last line\nCopy prompt" in one paragraph becomes the paragraph plus the label. */
    private fun expandTrailingMarkers(blocks: List<NoteDocBlock>): List<NoteDocBlock> = blocks.flatMap { block ->
        if (!trailingMarker(block)) return@flatMap listOf(block)
        val text = block.text.trimEnd()
        val head = text.substring(0, text.lastIndexOf('\n')).trimEnd()
        listOf(block.copy(text = head, runs = NoteRuns.normalize(block.runs, head.length)),
            NoteDocBlock(id = "", type = NoteDocBlockType.PARAGRAPH, text = "Copy prompt"))
    }

    /** A card header: one short line that does not read as a sentence. */
    private fun isLabel(block: NoteDocBlock): Boolean {
        if (block.type != NoteDocBlockType.PARAGRAPH) return false
        val text = block.text.trim()
        return text.isNotEmpty() && text.length <= 60 && '\n' !in text && text.any(Char::isLetter) &&
            text.last() !in ".!?:;,"
    }

    private fun prompt(title: String?, body: List<NoteDocBlock>): NoteDocBlock {
        val text = StringBuilder()
        val runs = ArrayList<NoteDocRun>()
        var number = 0
        body.forEachIndexed { index, block ->
            val listItem = block.type != NoteDocBlockType.PARAGRAPH && block.type != NoteDocBlockType.QUOTE
            if (index > 0) text.append(if (listItem && body[index - 1].type == block.type) "\n" else "\n\n")
            number = if (block.type == NoteDocBlockType.NUMBERED) {
                if (index > 0 && body[index - 1].type == NoteDocBlockType.NUMBERED) number + 1 else 1
            } else 0
            val prefix = "  ".repeat(block.indent.coerceAtLeast(0)) + when (block.type) {
                NoteDocBlockType.BULLET -> "- "
                NoteDocBlockType.NUMBERED -> "$number. "
                NoteDocBlockType.CHECKLIST -> if (block.checked) "- [x] " else "- [ ] "
                else -> ""
            }
            text.append(prefix)
            val offset = text.length
            text.append(block.text)
            block.runs.forEach { runs += it.copy(start = it.start + offset, end = it.end + offset) }
        }
        val value = text.toString()
        return NoteDocBlock(id = "", type = NoteDocBlockType.PROMPT, text = value,
            runs = NoteRuns.normalize(runs, value.length), promptTitle = title)
    }
}

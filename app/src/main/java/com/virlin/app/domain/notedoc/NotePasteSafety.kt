package com.virlin.app.domain.notedoc

/**
 * Limits that keep very large pastes responsive without dropping or altering characters.
 *
 * There is no size cap: a paste of any length is kept. Long text is only divided at line breaks
 * it already contains, so every character survives and no formatting is invented.
 */
object NotePasteSafety {
    /** Insertions longer than this are formatted off the main thread. */
    const val BACKGROUND_CHARS = 20_000

    /** Clipboard HTML longer than this is only parsed off the main thread. */
    const val BACKGROUND_HTML_CHARS = 200_000

    /** Paragraph blocks longer than this are divided at their own line breaks. */
    const val MAX_PARAGRAPH_CHARS = 8_000

    /**
     * The fallback when formatting cannot be imported: one plain paragraph per blank-line
     * separated section. Lines that hold only whitespace become the gaps between paragraphs.
     */
    fun plainParagraphs(text: String): List<NoteDocBlock> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val sections = ArrayList<String>()
        val current = StringBuilder()
        for (line in normalized.split('\n')) {
            if (line.isBlank()) {
                if (current.isNotEmpty()) { sections += current.toString(); current.setLength(0) }
            } else {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
        }
        if (current.isNotEmpty()) sections += current.toString()
        if (sections.isEmpty()) return emptyList()
        return splitLongParagraphs(sections.mapIndexed { index, section ->
            NoteDocBlock(id = "paste-$index", type = NoteDocBlockType.PARAGRAPH, text = section)
        })
    }

    /**
     * Divides oversized PARAGRAPH blocks at their existing line breaks, carrying their runs.
     * Other block types, and single lines longer than the limit, are left whole.
     */
    fun splitLongParagraphs(blocks: List<NoteDocBlock>): List<NoteDocBlock> {
        if (blocks.none { it.type == NoteDocBlockType.PARAGRAPH && it.text.length > MAX_PARAGRAPH_CHARS })
            return blocks
        val out = ArrayList<NoteDocBlock>(blocks.size)
        blocks.forEach { block ->
            if (block.type != NoteDocBlockType.PARAGRAPH || block.text.length <= MAX_PARAGRAPH_CHARS) {
                out += block
                return@forEach
            }
            lineChunks(block.text).forEachIndexed { index, (start, end) ->
                val runs = block.runs.mapNotNull { run ->
                    val a = maxOf(run.start, start)
                    val b = minOf(run.end, end)
                    if (b > a) run.copy(start = a - start, end = b - start) else null
                }
                out += block.copy(id = "${block.id}-$index", text = block.text.substring(start, end), runs = runs)
            }
        }
        return out
    }

    /** Ranges of at most [MAX_PARAGRAPH_CHARS], cut at line breaks (the break itself is dropped). */
    internal fun lineChunks(text: String): List<Pair<Int, Int>> {
        val ranges = ArrayList<Pair<Int, Int>>()
        var start = 0
        while (start < text.length) {
            if (text.length - start <= MAX_PARAGRAPH_CHARS) {
                ranges += start to text.length
                break
            }
            var cut = text.lastIndexOf('\n', start + MAX_PARAGRAPH_CHARS)
            if (cut < start) cut = text.indexOf('\n', start + MAX_PARAGRAPH_CHARS).let { if (it < 0) text.length else it }
            ranges += start to cut
            start = cut + 1
        }
        return ranges
    }
}

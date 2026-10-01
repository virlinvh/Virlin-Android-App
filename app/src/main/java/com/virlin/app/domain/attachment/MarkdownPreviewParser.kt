package com.virlin.app.domain.attachment

/**
 * Minimal, deliberately conservative Markdown reader for the Attachment preview.
 *
 * SAFETY: this parser has no HTML path at all. Raw HTML in the source is emitted as literal text,
 * so there is no renderer that could execute script, load a remote resource, or reach a
 * JavaScript-capable WebView. It also never rewrites the file: it reads an already length-capped
 * string from [TextFileReader] and produces display blocks, leaving the stored bytes untouched.
 *
 * It covers what the approved document view shows - headings, paragraphs, bullet and ordered
 * items, task checkboxes, fenced code and quotes - and nothing more. Anything it does not
 * recognise degrades to a paragraph rather than being dropped.
 */
object MarkdownPreviewParser {

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class Bullet(val text: String, val indent: Int) : Block
        data class Ordered(val number: Int, val text: String, val indent: Int) : Block
        data class Task(val text: String, val checked: Boolean, val indent: Int) : Block
        data class Code(val language: String?, val lines: List<String>) : Block
        data class Quote(val text: String) : Block
        data object Divider : Block
    }

    /** Inline emphasis the renderer may apply. Offsets index [InlineText.text]. */
    data class Span(val start: Int, val end: Int, val style: Style)

    enum class Style { BOLD, ITALIC, CODE }

    data class InlineText(val text: String, val spans: List<Span>)

    fun parse(source: String, maxBlocks: Int = 2_000): List<Block> {
        val blocks = ArrayList<Block>()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        val paragraph = StringBuilder()

        fun flushParagraph() {
            val text = paragraph.toString().trim()
            paragraph.setLength(0)
            if (text.isNotEmpty() && blocks.size < maxBlocks) blocks += Block.Paragraph(text)
        }

        while (i < lines.size && blocks.size < maxBlocks) {
            val raw = lines[i]
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            val indent = (line.length - trimmed.length).coerceAtMost(12) / 2

            when {
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    val language = trimmed.removePrefix("```").trim().ifBlank { null }
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code += lines[i]
                        i++
                    }
                    blocks += Block.Code(language, code)
                }

                trimmed.isEmpty() -> flushParagraph()

                isDivider(trimmed) -> {
                    flushParagraph()
                    blocks += Block.Divider
                }

                trimmed.startsWith("#") -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length
                    val text = trimmed.drop(level).trim()
                    if (level in 1..6 && text.isNotEmpty()) {
                        blocks += Block.Heading(level.coerceAtMost(3), text)
                    } else {
                        blocks += Block.Paragraph(trimmed)
                    }
                }

                trimmed.startsWith("> ") || trimmed == ">" -> {
                    flushParagraph()
                    blocks += Block.Quote(trimmed.removePrefix(">").trim())
                }

                taskPrefix(trimmed) != null -> {
                    flushParagraph()
                    val (checked, text) = taskPrefix(trimmed)!!
                    blocks += Block.Task(text, checked, indent)
                }

                isBullet(trimmed) -> {
                    flushParagraph()
                    blocks += Block.Bullet(trimmed.drop(2).trim(), indent)
                }

                orderedPrefix(trimmed) != null -> {
                    flushParagraph()
                    val (number, text) = orderedPrefix(trimmed)!!
                    blocks += Block.Ordered(number, text, indent)
                }

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(trimmed)
                }
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    /**
     * Extracts `**bold**`, `*italic*`/`_italic_` and `` `code` `` into spans over clean text.
     * Unmatched markers stay literal, so malformed emphasis cannot swallow the rest of a document.
     */
    fun inline(source: String): InlineText {
        val out = StringBuilder()
        val spans = ArrayList<Span>()
        var i = 0
        while (i < source.length) {
            val rest = source.length - i
            when {
                rest >= 4 && source.startsWith("**", i) -> {
                    val close = source.indexOf("**", i + 2)
                    if (close < 0) { out.append(source[i]); i++ }
                    else {
                        val start = out.length
                        out.append(source, i + 2, close)
                        spans += Span(start, out.length, Style.BOLD)
                        i = close + 2
                    }
                }
                source[i] == '`' -> {
                    val close = source.indexOf('`', i + 1)
                    if (close < 0) { out.append(source[i]); i++ }
                    else {
                        val start = out.length
                        out.append(source, i + 1, close)
                        spans += Span(start, out.length, Style.CODE)
                        i = close + 1
                    }
                }
                source[i] == '*' || source[i] == '_' -> {
                    val marker = source[i]
                    val close = source.indexOf(marker, i + 1)
                    if (close < 0 || close == i + 1) { out.append(source[i]); i++ }
                    else {
                        val start = out.length
                        out.append(source, i + 1, close)
                        spans += Span(start, out.length, Style.ITALIC)
                        i = close + 1
                    }
                }
                else -> { out.append(source[i]); i++ }
            }
        }
        return InlineText(out.toString(), spans)
    }

    private fun isBullet(t: String) =
        (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) && taskPrefix(t) == null

    private fun isDivider(t: String) =
        t.length >= 3 && (t.all { it == '-' } || t.all { it == '*' } || t.all { it == '_' })

    private fun taskPrefix(t: String): Pair<Boolean, String>? {
        val m = Regex("^[-*+]\\s+\\[( |x|X)]\\s+(.*)$").find(t) ?: return null
        return (m.groupValues[1].lowercase() == "x") to m.groupValues[2].trim()
    }

    private fun orderedPrefix(t: String): Pair<Int, String>? {
        val m = Regex("^(\\d{1,3})[.)]\\s+(.*)$").find(t) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].trim()
    }
}

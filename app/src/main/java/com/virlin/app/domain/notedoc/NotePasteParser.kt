package com.virlin.app.domain.notedoc

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Imports clipboard content into the existing note block/run model. HTML is parsed as data;
 * markup is never rendered or stored. Plain text from AI tools is interpreted as Markdown.
 */
object NotePasteParser {
    fun parse(plainText: String, htmlText: String? = null,
        imageResolver: ((String) -> String?)? = null): List<NoteDocBlock> {
        val htmlBlocks = htmlText?.takeIf { it.isNotBlank() }?.let {
            runCatching { fromHtml(it, imageResolver) }.getOrNull()
        }.orEmpty()
        val markdownLooksStructured = plainText.lines().any { line ->
            val trimmed = line.trimStart()
            trimmed.matches(Regex("#{1,6}\\s+.*")) ||
                trimmed.startsWith("```") || trimmed.startsWith("~~~") ||
                trimmed.startsWith("$$") || trimmed.startsWith("\\[") ||
                trimmed.contains(Regex("\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__")) ||
                trimmed.matches(Regex("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?"))
        }
        val htmlOnlyWrapsPlainText = htmlBlocks.isNotEmpty() &&
            htmlBlocks.all { it.type == NoteDocBlockType.PARAGRAPH && it.runs.isEmpty() }
        // Some Android clipboard providers wrap Markdown source in styled HTML spans.
        // The spans make it look "rich" even though the text still contains literal
        // fences/headings/table delimiters. In that case structure must come from the
        // Markdown source, not the decorative HTML wrapper.
        val htmlContainsRawMarkdown = htmlBlocks.any { block ->
            block.type in setOf(NoteDocBlockType.PARAGRAPH, NoteDocBlockType.HEADING_1,
                NoteDocBlockType.HEADING_2, NoteDocBlockType.HEADING_3) &&
            block.text.lines().any { line ->
                val trimmed = line.trimStart()
                trimmed.matches(Regex("#{1,6}\\s+.*")) ||
                    trimmed.startsWith("```") || trimmed.startsWith("~~~") ||
                    trimmed.contains(Regex("\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__")) ||
                    trimmed.matches(Regex("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?"))
            }
        }
        // Image bytes/URIs can exist only in HTML; never throw them away for a
        // Markdown fallback, even if another paragraph has literal Markdown.
        val htmlHasImportedImage = htmlBlocks.any { it.type == NoteDocBlockType.IMAGE }
        val blocks = if (htmlBlocks.isNotEmpty() &&
            !(markdownLooksStructured && !htmlHasImportedImage &&
                (htmlOnlyWrapsPlainText || htmlContainsRawMarkdown))) htmlBlocks
        else fromMarkdown(plainText)
        val grouped = groupMetricCards(blocks)
        return grouped
    }

    private fun block(type: NoteDocBlockType, text: String, runs: List<NoteDocRun> = emptyList(),
        indent: Int = 0, table: NoteDocTable? = null, mathMode: Boolean? = null): NoteDocBlock =
        NoteDocBlock(id = "", type = type, text = text,
            runs = NoteRuns.normalize(runs, text.length), indent = indent, table = table,
            mathMode = mathMode)

    private fun fromMarkdown(source: String): List<NoteDocBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val result = mutableListOf<NoteDocBlock>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                val (text, runs) = inlineMarkdown(paragraph.joinToString("\n"))
                result += block(NoteDocBlockType.PARAGRAPH, text, runs)
                paragraph.clear()
            }
        }
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val trimmed = line.trim()
            val fence = Regex("^\\s*(```+|~~~+)(.*)$").matchEntire(line)
            val tableHeader = lineIndex + 1 < lines.size && line.contains('|') &&
                lines[lineIndex + 1].trim().matches(Regex("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?"))
            if (tableHeader) {
                flush()
                fun cells(value: String) = value.trim().trim('|').split('|').map { it.trim() }
                val header = cells(line)
                val rows = mutableListOf(header)
                lineIndex += 2
                while (lineIndex < lines.size && lines[lineIndex].contains('|') &&
                    lines[lineIndex].isNotBlank()) {
                    rows += cells(lines[lineIndex])
                    lineIndex++
                }
                val columns = header.size.coerceAtLeast(1)
                result += block(NoteDocBlockType.TABLE, "", table = NoteDocTable(
                    rows.size, columns, rows.flatMap { row ->
                        List(columns) { column -> row.getOrElse(column) { "" } }
                    }))
                lineIndex--
            } else if (fence != null) {
                flush()
                val marker = fence.groupValues[1].first()
                val code = mutableListOf<String>()
                lineIndex++
                while (lineIndex < lines.size &&
                    !lines[lineIndex].trimStart().startsWith(marker.toString().repeat(3))) {
                    code += lines[lineIndex++]
                }
                val language = fence.groupValues[2].trim().lowercase()
                val prompt = language == "prompt" || language == "message"
                result += block(if (prompt) NoteDocBlockType.PROMPT else NoteDocBlockType.CODE,
                    code.joinToString("\n"), mathMode = when {
                        prompt || language.isBlank() -> null
                        language in setOf("latex", "tex", "math") -> true
                        else -> false
                    })
            } else if (trimmed == "$$" || trimmed == "\\[") {
                flush()
                val close = if (trimmed == "$$") "$$" else "\\]"
                val equation = mutableListOf<String>()
                lineIndex++
                while (lineIndex < lines.size && lines[lineIndex].trim() != close) {
                    equation += lines[lineIndex++]
                }
                result += block(NoteDocBlockType.CODE,
                    trimmed + "\n" + equation.joinToString("\n") + "\n" + close,
                    mathMode = true)
            } else if (trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 4) {
                flush()
                result += block(NoteDocBlockType.CODE, trimmed, mathMode = true)
            } else if (trimmed.isEmpty()) {
                flush()
            } else {
                val heading = Regex("^\\s*(#{1,6})\\s+(.+?)\\s*#*\\s*$").matchEntire(line)
                val checklist = Regex("^\\s*[-*+]\\s+\\[([ xX])\\]\\s+(.+)$").matchEntire(line)
                val bullet = Regex("^(\\s*)[-*+•]\\s+(.+)$").matchEntire(line)
                val numbered = Regex("^(\\s*)\\d{1,4}(?:\\\\)?[.)]\\s+(.+)$").matchEntire(line)
                val quote = Regex("^\\s*>\\s?(.+)$").matchEntire(line)
                when {
                    heading != null -> {
                        flush()
                        val type = when (heading.groupValues[1].length) {
                            1 -> NoteDocBlockType.HEADING_1
                            2 -> NoteDocBlockType.HEADING_2
                            else -> NoteDocBlockType.HEADING_3
                        }
                        val (text, runs) = inlineMarkdown(heading.groupValues[2])
                        result += block(type, text, runs)
                    }
                    trimmed.matches(Regex("(-{3,}|\\*{3,}|_{3,})")) -> {
                        flush(); result += block(NoteDocBlockType.DIVIDER, "")
                    }
                    checklist != null -> {
                        flush()
                        val (text, runs) = inlineMarkdown(checklist.groupValues[2])
                        result += block(NoteDocBlockType.CHECKLIST, text, runs)
                            .copy(checked = !checklist.groupValues[1].equals(" "))
                    }
                    bullet != null || numbered != null -> {
                        flush()
                        val match = bullet ?: numbered!!
                        val (text, runs) = inlineMarkdown(match.groupValues[2])
                        result += block(if (bullet != null) NoteDocBlockType.BULLET
                            else NoteDocBlockType.NUMBERED, text, runs,
                            indent = (match.groupValues[1].length / 2).coerceAtMost(5))
                    }
                    quote != null -> {
                        flush()
                        val (text, runs) = inlineMarkdown(quote.groupValues[1])
                        result += block(NoteDocBlockType.QUOTE, text, runs)
                    }
                    NoteMath.isEquation(trimmed) -> {
                        flush()
                        result += block(NoteDocBlockType.CODE, trimmed, mathMode = true)
                    }
                    else -> paragraph += line
                }
            }
            lineIndex++
        }
        flush()
        return result
    }

    private fun inlineMarkdown(source: String): Pair<String, List<NoteDocRun>> {
        val out = StringBuilder()
        val runs = mutableListOf<NoteDocRun>()
        fun append(text: String, marks: Set<NoteDocMark> = emptySet(), link: String? = null) {
            val start = out.length
            out.append(text)
            if (out.length > start && (marks.isNotEmpty() || link != null))
                runs += NoteDocRun(start, out.length, marks, linkUrl = link)
        }
        var at = 0
        while (at < source.length) {
            val link = Regex("^\\[([^]]+)]\\((https?://[^\\s)]+)\\)").find(source, at)
                ?.takeIf { it.range.first == at }
            if (link != null) {
                append(link.groupValues[1], link = link.groupValues[2])
                at = link.range.last + 1
                continue
            }
            val token = listOf("**" to NoteDocMark.BOLD, "__" to NoteDocMark.BOLD,
                "~~" to NoteDocMark.STRIKE, "`" to NoteDocMark.CODE,
                "*" to NoteDocMark.ITALIC, "_" to NoteDocMark.ITALIC)
                .firstOrNull { (delimiter, _) -> source.startsWith(delimiter, at) &&
                    !(delimiter.startsWith("_") && at > 0 && source[at - 1].isLetterOrDigit()) &&
                    source.indexOf(delimiter, at + delimiter.length) > at + delimiter.length }
            if (token != null) {
                val end = source.indexOf(token.first, at + token.first.length)
                append(source.substring(at + token.first.length, end), setOf(token.second))
                at = end + token.first.length
            } else {
                if (source[at] == '\\' && at + 1 < source.length &&
                    source[at + 1] in ".!#*_-+()[]") {
                    append(source[at + 1].toString())
                    at += 2
                    continue
                }
                append(source[at].toString())
                at++
            }
        }
        return out.toString() to runs
    }

    private data class InlineStyle(
        val marks: Set<NoteDocMark> = emptySet(),
        val link: String? = null,
        val color: Long? = null,
        val highlight: Long? = null,
        val fontSizeSp: Int? = null,
        val fontFamily: String? = null,
    ) {
        val hasFormatting: Boolean get() = marks.isNotEmpty() || link != null ||
            color != null || highlight != null || fontSizeSp != null || fontFamily != null
    }

    private fun cssValue(style: String, name: String): String? =
        Regex("(?:^|;)\\s*${Regex.escape(name)}\\s*:\\s*([^;]+)", RegexOption.IGNORE_CASE)
            .find(style)?.groupValues?.get(1)?.trim()

    private fun cssColor(value: String?): Long? {
        val raw = value?.trim()?.lowercase() ?: return null
        val hex = raw.removePrefix("#")
        val rgb = when {
            raw.startsWith("#") && hex.length == 3 && hex.all { it in "0123456789abcdef" } ->
                hex.map { "$it$it" }.joinToString("").toInt(16)
            raw.startsWith("#") && hex.length == 6 && hex.all { it in "0123456789abcdef" } ->
                hex.toInt(16)
            else -> {
                val match = Regex("rgb\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\)")
                    .matchEntire(raw) ?: return null
                val channels = match.groupValues.drop(1).map { it.toInt() }
                if (channels.any { it > 255 }) return null
                (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
            }
        }
        return 0xFF000000L or rgb.toLong()
    }

    private fun isMetricLabel(value: String): Boolean = value.trim().let { text ->
        text.length in 2..32 && text.none { it == '\n' || it == '|' } &&
            text.any(Char::isLetter) && !text.endsWith('.')
    }

    private fun isMetricValue(value: String): Boolean =
        value.trim().matches(Regex("[+-]?[0-9][0-9,]*(?:\\.[0-9]+)?%?"))

    /** Short repeated label/number cards become one readable grid, not eight isolated lines. */
    private fun groupMetricCards(blocks: List<NoteDocBlock>): List<NoteDocBlock> {
        val lines = blocks.flatMap { original ->
            val parts = original.text.lines().map(String::trim).filter(String::isNotEmpty)
            if (original.type == NoteDocBlockType.PARAGRAPH && original.runs.isEmpty() &&
                parts.size >= 2 && parts.size % 2 == 0 &&
                parts.chunked(2).all { isMetricLabel(it[0]) && isMetricValue(it[1]) })
                parts.map { block(NoteDocBlockType.PARAGRAPH, it) }
            else listOf(original)
        }
        val grouped = mutableListOf<NoteDocBlock>()
        var index = 0
        while (index < lines.size) {
            val labels = mutableListOf<String>()
            val values = mutableListOf<String>()
            while (index + labels.size * 2 + 1 < lines.size && labels.size < 20) {
                val label = lines[index + labels.size * 2]
                val value = lines[index + labels.size * 2 + 1]
                if (label.type != NoteDocBlockType.PARAGRAPH || label.runs.isNotEmpty() ||
                    value.type != NoteDocBlockType.PARAGRAPH || value.runs.isNotEmpty() ||
                    !isMetricLabel(label.text) || !isMetricValue(value.text)) break
                labels += label.text.trim()
                values += value.text.trim()
            }
            if (labels.size >= 3) {
                grouped += block(NoteDocBlockType.TABLE, "", table = NoteDocTable(
                    rows = 2, cols = labels.size, cells = labels + values))
                index += labels.size * 2
            } else {
                grouped += lines[index]
                index++
            }
        }
        return grouped
    }

    private fun fromHtml(html: String, imageResolver: ((String) -> String?)?): List<NoteDocBlock> {
        val document = Jsoup.parseBodyFragment(html)
        val result = mutableListOf<NoteDocBlock>()
        val blockTags = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
            "blockquote", "pre", "li", "ul", "ol", "table", "hr", "img")
        fun collect(element: Element, type: NoteDocBlockType, indent: Int) {
            // Rendered MathML retains its original TeX in this annotation.
            val annotatedTeX = element.select("annotation[encoding=application/x-tex]")
                .map { it.wholeText().trim() }.filter(String::isNotBlank)
            if (annotatedTeX.isNotEmpty()) {
                result += block(NoteDocBlockType.CODE, annotatedTeX.joinToString("\n"),
                    mathMode = true)
                return
            }
            if (type == NoteDocBlockType.CODE || type == NoteDocBlockType.PROMPT) {
                // Copy buttons alongside <code> are UI chrome, not part of the pasted prompt.
                result += block(type, (element.selectFirst("code") ?: element).wholeText().trimEnd(),
                    mathMode = if (type == NoteDocBlockType.CODE) false else null)
                return
            }
            val text = StringBuilder()
            val runs = mutableListOf<NoteDocRun>()
            fun walk(node: Node, inherited: InlineStyle) {
                when (node) {
                    is TextNode -> {
                        val value = node.wholeText
                        val start = text.length
                        text.append(value)
                        if (text.length > start && inherited.hasFormatting)
                            runs += NoteDocRun(start, text.length, inherited.marks,
                                color = inherited.color, highlight = inherited.highlight,
                                fontFamily = inherited.fontFamily,
                                fontSizeSp = inherited.fontSizeSp, linkUrl = inherited.link)
                    }
                    is Element -> {
                        if (node.normalName() in setOf("script", "style", "iframe", "img", "ul", "ol")) return
                        if (node.normalName() == "br") { text.append('\n'); return }
                        val marks = inherited.marks.toMutableSet()
                        val css = node.attr("style").lowercase()
                        when (node.normalName()) {
                            "b", "strong" -> marks += NoteDocMark.BOLD
                            "i", "em" -> marks += NoteDocMark.ITALIC
                            "u" -> marks += NoteDocMark.UNDERLINE
                            "s", "del", "strike" -> marks += NoteDocMark.STRIKE
                            "code" -> marks += NoteDocMark.CODE
                        }
                        if (Regex("font-weight\\s*:\\s*(bold|[6-9]00)").containsMatchIn(css))
                            marks += NoteDocMark.BOLD
                        if (Regex("font-style\\s*:\\s*italic").containsMatchIn(css))
                            marks += NoteDocMark.ITALIC
                        val href = node.takeIf { it.normalName() == "a" }?.attr("href")
                            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                        val family = cssValue(css, "font-family")?.lowercase()?.let {
                            when {
                                "mono" in it -> "Mono"
                                "serif" in it && "sans" !in it -> "Serif"
                                "sans" in it || "inter" in it -> "Inter"
                                else -> null
                            }
                        }
                        val fontSize = cssValue(css, "font-size")
                            ?.let { Regex("([\\d.]+)(px|pt)", RegexOption.IGNORE_CASE).matchEntire(it) }
                            ?.let {
                                val size = it.groupValues[1].toFloatOrNull() ?: 0f
                                (size * if (it.groupValues[2].equals("pt", true)) 4f / 3f else 1f)
                                    .toInt().takeIf { points -> points in 8..96 }
                            }
                        val next = InlineStyle(marks, href ?: inherited.link,
                            color = cssColor(cssValue(css, "color")) ?: inherited.color,
                            highlight = cssColor(cssValue(css, "background-color"))
                                ?: if (node.normalName() == "mark") 0xFFFFEB8AL else inherited.highlight,
                            fontSizeSp = fontSize ?: inherited.fontSizeSp,
                            fontFamily = family ?: inherited.fontFamily)
                        node.childNodes().forEach { walk(it, next) }
                    }
                }
            }
            walk(element, InlineStyle())
            val raw = text.toString()
            val leading = raw.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return
            val end = raw.indexOfLast { !it.isWhitespace() } + 1
            val trimmedRuns = runs.mapNotNull {
                val start = (it.start - leading).coerceAtLeast(0)
                val finish = (it.end - leading).coerceAtMost(end - leading)
                if (finish > start) it.copy(start = start, end = finish) else null
            }
            val value = raw.substring(leading, end)
            val escapedNumber = type in setOf(NoteDocBlockType.HEADING_1,
                NoteDocBlockType.HEADING_2, NoteDocBlockType.HEADING_3) &&
                value.matches(Regex("^\\d{1,3}\\\\\\..*", RegexOption.DOT_MATCHES_ALL))
            val cleanValue = if (escapedNumber) value.removeRange(value.indexOf('\\'), value.indexOf('\\') + 1)
                else value
            val cleanRuns = if (escapedNumber) NoteRuns.shiftForEdit(trimmedRuns,
                value.indexOf('\\'), 1, 0, cleanValue.length) else trimmedRuns
            result += block(type, cleanValue, cleanRuns, indent,
                mathMode = if (element.normalName() == "math") true else null)
        }
        fun visit(parent: Element, listType: NoteDocBlockType? = null, indent: Int = 0) {
            parent.childNodes().forEach { node ->
                if (node is TextNode && node.wholeText.isNotBlank()) {
                    collect(Element("p").text(node.wholeText), listType ?: NoteDocBlockType.PARAGRAPH, indent)
                } else if (node is Element) {
                    val tag = node.normalName()
                    when (tag) {
                        "script", "style", "iframe" -> Unit
                        "img" -> {
                            val source = node.attr("src")
                            val imported = source.takeIf { it.isNotBlank() }?.let { imageResolver?.invoke(it) }
                            if (imported != null) result += block(NoteDocBlockType.IMAGE, "")
                                .copy(imagePath = imported)
                            else {
                                val alt = node.attr("alt").ifBlank { "Image unavailable from clipboard" }
                                result += block(NoteDocBlockType.PARAGRAPH, alt)
                            }
                        }
                        "ul", "ol" -> visit(node, if (tag == "ul") NoteDocBlockType.BULLET
                            else NoteDocBlockType.NUMBERED, indent)
                        "hr" -> result += block(NoteDocBlockType.DIVIDER, "")
                        "table" -> {
                            val rows = node.select("tr").map { row ->
                                row.select("th,td").map { it.text() }
                            }.filter { it.isNotEmpty() }
                            if (rows.isNotEmpty()) {
                                val cols = rows.maxOf { it.size }
                                result += block(NoteDocBlockType.TABLE, "", table = NoteDocTable(
                                    rows.size, cols,
                                    rows.flatMap { row ->
                                        List(cols) { column -> row.getOrElse(column) { "" } }
                                    }))
                            }
                        }
                        "div" -> {
                            val children = node.children()
                            if (children.size == 2 && node.ownText().isBlank() &&
                                isMetricLabel(children[0].text()) && isMetricValue(children[1].text())) {
                                result += block(NoteDocBlockType.PARAGRAPH,
                                    children[0].text().trim() + "\n" + children[1].text().trim())
                            } else if (children.any { it.normalName() in blockTags }) visit(node, listType, indent)
                            else collect(node, listType ?: NoteDocBlockType.PARAGRAPH, indent)
                        }
                        "li" -> {
                            collect(node, listType ?: NoteDocBlockType.BULLET, indent)
                            node.children().filter { it.normalName() in setOf("ul", "ol") }
                                .forEach { visit(it, if (it.normalName() == "ul")
                                    NoteDocBlockType.BULLET else NoteDocBlockType.NUMBERED,
                                    (indent + 1).coerceAtMost(5)) }
                        }
                        else -> {
                            val type = when (tag) {
                                "h1" -> NoteDocBlockType.HEADING_1
                                "h2" -> NoteDocBlockType.HEADING_2
                                "h3", "h4", "h5", "h6" -> NoteDocBlockType.HEADING_3
                                "blockquote" -> NoteDocBlockType.QUOTE
                                "pre" -> if (node.classNames().any {
                                        "prompt" in it.lowercase() || "message" in it.lowercase()
                                    } || node.attr("data-language").lowercase() in setOf("prompt", "message") ||
                                    node.selectFirst("code")?.classNames()?.any {
                                        it.lowercase() in setOf("language-prompt", "language-message")
                                    } == true)
                                    NoteDocBlockType.PROMPT else NoteDocBlockType.CODE
                                "math" -> NoteDocBlockType.CODE
                                else -> listType ?: NoteDocBlockType.PARAGRAPH
                            }
                            if (node.children().any { it.normalName() in blockTags } &&
                                tag !in setOf("pre", "blockquote")) visit(node, listType, indent)
                            else collect(node, type, indent)
                        }
                    }
                }
            }
        }
        visit(document.body())
        return result
    }
}

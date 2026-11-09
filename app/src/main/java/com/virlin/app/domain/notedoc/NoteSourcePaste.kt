package com.virlin.app.domain.notedoc

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** How a copy from a source is read. Each source keeps the formatting that source actually puts on the clipboard. */
enum class NotePasteReading {
    /** AI chats and Google Search: their formatted copy, else their Markdown. */
    AI_CHAT,
    /** Google Docs: its own formatted copy, else one paragraph per line. */
    GOOGLE_DOCS,
    /** Microsoft Word: its formatted copy with Word's list paragraphs, else one paragraph per line. */
    WORD,
    /** Web pages: their formatted copy, else one paragraph per line. */
    WEB_PAGE,
    /** PDF viewers: hard-wrapped lines are joined back into paragraphs; lists and capital headings kept. */
    PDF,
    /** Markdown source, whatever else is on the clipboard. */
    MARKDOWN,
    /** Text only: one paragraph per line, nothing interpreted. */
    PLAIN,
}

enum class NotePasteSource(val label: String, val reading: NotePasteReading, val group: Group) {
    CHATGPT("ChatGPT", NotePasteReading.AI_CHAT, Group.AI),
    GEMINI("Gemini", NotePasteReading.AI_CHAT, Group.AI),
    CLAUDE("Claude", NotePasteReading.AI_CHAT, Group.AI),
    GROK("Grok", NotePasteReading.AI_CHAT, Group.AI),
    DEEPSEEK("DeepSeek", NotePasteReading.AI_CHAT, Group.AI),
    NOTEBOOKLM("NotebookLM", NotePasteReading.AI_CHAT, Group.AI),
    PERPLEXITY("Perplexity", NotePasteReading.AI_CHAT, Group.AI),
    COPILOT("Copilot", NotePasteReading.AI_CHAT, Group.AI),
    META_AI("Meta AI", NotePasteReading.AI_CHAT, Group.AI),
    OTHER_AI("Other AI chat", NotePasteReading.AI_CHAT, Group.AI),
    GOOGLE_SEARCH("Google Search", NotePasteReading.AI_CHAT, Group.WEB),
    WEB_PAGE("Website", NotePasteReading.WEB_PAGE, Group.WEB),
    GOOGLE_DOCS("Google Docs", NotePasteReading.GOOGLE_DOCS, Group.DOCUMENTS),
    WORD("Word", NotePasteReading.WORD, Group.DOCUMENTS),
    PDF("PDF", NotePasteReading.PDF, Group.DOCUMENTS),
    MARKDOWN("Markdown", NotePasteReading.MARKDOWN, Group.TEXT),
    PLAIN("Plain text", NotePasteReading.PLAIN, Group.TEXT);

    enum class Group(val label: String) { AI("AI chats"), WEB("Web"), DOCUMENTS("Documents"), TEXT("Text") }
}

/**
 * Turns what the clipboard holds into note blocks the way the chosen source formats its copies.
 * Pure: the caller reads the clipboard and supplies pictures through [imageResolver].
 */
object NoteSourcePaste {
    private const val MAX_BLOCKS = 8000

    fun parse(source: NotePasteSource, plain: String, html: String?,
        imageResolver: ((String) -> String?)? = null): List<NoteDocBlock> {
        val text = plain.replace("\r\n", "\n").replace('\r', '\n').replace('\u000C', '\n')
        val rich = html?.takeIf { it.isNotBlank() }
        val blocks = when (source.reading) {
            NotePasteReading.AI_CHAT -> {
                val cleaned = if (source == NotePasteSource.GOOGLE_SEARCH) searchPlain(text) else text
                NotePasteParser.parse(dropEmptyBullets(cleaned), rich, imageResolver)
            }
            NotePasteReading.GOOGLE_DOCS -> rich?.let {
                if (GoogleDocsPaste.isGoogleDocsHtml(it)) runCatching { GoogleDocsPaste.parse(it, imageResolver) }
                    .getOrNull()?.takeIf(List<NoteDocBlock>::isNotEmpty)
                else null
            } ?: richOrLines(rich, text, imageResolver)
            NotePasteReading.WORD -> richOrLines(rich?.let(::wordHtml), text, imageResolver)
            NotePasteReading.WEB_PAGE -> richOrLines(rich, text, imageResolver)
            NotePasteReading.PDF -> rich?.let { richBlocks(it, imageResolver) } ?: pdfText(text)
            NotePasteReading.MARKDOWN -> NotePasteParser.parse(text, null, imageResolver)
            NotePasteReading.PLAIN -> plainLines(text)
        }
        return blocks.take(MAX_BLOCKS)
    }

    /** Whether the source reads anything from the formatted (HTML) copy. */
    fun usesFormattedCopy(source: NotePasteSource): Boolean =
        source.reading != NotePasteReading.MARKDOWN && source.reading != NotePasteReading.PLAIN

    private fun richBlocks(html: String, imageResolver: ((String) -> String?)?): List<NoteDocBlock>? =
        runCatching { NotePasteParser.parse(Jsoup.parse(html).text(), normalizeBodySize(html), imageResolver) }
            .getOrNull()?.takeIf { blocks -> blocks.any { it.text.isNotBlank() || it.type != NoteDocBlockType.PARAGRAPH } }

    private fun richOrLines(html: String?, text: String, imageResolver: ((String) -> String?)?): List<NoteDocBlock> =
        html?.let { richBlocks(it, imageResolver) } ?: documentLines(text)

    // ------------------------------------------------------------------ Google Search plain copy

    private val SEARCH_LINK = Regex("\\s*\\((?:/goto\\?url=|/search\\?|/url\\?)[^)\\s]*\\)")

    /** Google's mobile copy appends each citation's internal redirect path; it is not part of the answer. */
    private fun searchPlain(text: String): String = text.replace(SEARCH_LINK, "")

    private val EMPTY_BULLET = Regex("^\\s*[*\\-•+]\\s*$")

    private fun dropEmptyBullets(text: String): String =
        text.split('\n').filterNot { EMPTY_BULLET.matches(it) }.joinToString("\n")

    // ------------------------------------------------------------------ formatted copies

    private val FONT_SIZE = Regex("font-size\\s*:\\s*([\\d.]+)\\s*(px|pt)", RegexOption.IGNORE_CASE)

    /**
     * Documents and pages state their body size on every run (11pt Word text, 16px web text). That
     * size is the note's ordinary text; only sizes that differ from it are kept.
     */
    private fun fragment(html: String) = Jsoup.parseBodyFragment(html).apply { outputSettings().prettyPrint(false) }

    private fun normalizeBodySize(html: String): String {
        val document = fragment(html)
        val weights = HashMap<String, Int>()
        document.select("[style]").forEach { element ->
            val size = FONT_SIZE.find(element.attr("style"))?.let { it.groupValues[1].toFloat().toString() + it.groupValues[2].lowercase() }
                ?: return@forEach
            weights[size] = (weights[size] ?: 0) + element.ownText().length
        }
        val body = weights.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: return html
        var changed = false
        document.select("[style]").forEach { element ->
            val style = element.attr("style")
            val match = FONT_SIZE.find(style) ?: return@forEach
            if (match.groupValues[1].toFloat().toString() + match.groupValues[2].lowercase() == body &&
                element.normalName() !in setOf("h1", "h2", "h3", "h4", "h5", "h6")) {
                element.attr("style", style.removeRange(match.range))
                changed = true
            }
        }
        return if (changed) document.body().html() else html
    }

    private val WORD_LEVEL = Regex("mso-list\\s*:[^;]*level(\\d+)", RegexOption.IGNORE_CASE)
    private val WORD_LIST = Regex("mso-list\\s*:\\s*(l\\d+)", RegexOption.IGNORE_CASE)
    private val NUMBER_MARKER = Regex("^\\(?[0-9a-zA-Z]{1,4}[.)]$")

    /**
     * Word writes a list as paragraphs (`MsoListParagraph`, `mso-list: l0 level2`) whose first span
     * holds the typed bullet or number. Those become real nested lists; Title/Subtitle become headings.
     */
    internal fun wordHtml(html: String): String {
        if (!html.contains("mso-", ignoreCase = true) && !html.contains("Mso", ignoreCase = false)) return html
        val document = fragment(html)
        document.select("p.MsoTitle").forEach { it.tagName("h1") }
        document.select("p.MsoSubtitle").forEach { it.tagName("h2") }
        data class Item(val paragraph: Element, val list: String?, val level: Int, val numbered: Boolean)
        fun markerOf(p: Element) = p.select("span[style~=(?i)mso-list\\s*:\\s*ignore]")
        fun itemOf(p: Element): Item? {
            val style = p.attr("style")
            val listClass = p.classNames().any { it.startsWith("MsoListParagraph") }
            val level = WORD_LEVEL.find(style)?.groupValues?.get(1)?.toIntOrNull()
            if (!listClass && level == null) return null
            val glyph = markerOf(p).text().replace('\u00A0', ' ').trim()
            return Item(p, WORD_LIST.find(style)?.groupValues?.get(1)?.lowercase(),
                ((level ?: 1) - 1).coerceIn(0, 5), NUMBER_MARKER.matches(glyph))
        }
        val paragraphs = document.select("p")
        var index = 0
        while (index < paragraphs.size) {
            val first = itemOf(paragraphs[index])
            if (first == null) { index++; continue }
            val run = mutableListOf(first)
            var next = index + 1
            while (next < paragraphs.size && paragraphs[next].previousElementSibling() === run.last().paragraph) {
                val item = itemOf(paragraphs[next]) ?: break
                val otherList = item.list != null && first.list != null && item.list != first.list
                val otherKind = item.level <= first.level && item.numbered != first.numbered
                if (otherList || otherKind) break
                run += item
                next++
            }
            run.forEach { markerOf(it.paragraph).remove() }
            val root = Element(if (first.numbered) "ol" else "ul")
            val stack = ArrayDeque<Pair<Int, Element>>().apply { addLast(first.level to root) }
            run.forEach { item ->
                while (stack.size > 1 && stack.last().first > item.level) stack.removeLast()
                if (item.level > stack.last().first) {
                    val nested = Element(if (item.numbered) "ol" else "ul")
                    (stack.last().second.children().lastOrNull() ?: stack.last().second).appendChild(nested)
                    stack.addLast(item.level to nested)
                }
                val li = Element("li")
                item.paragraph.childNodes().toList().forEach { li.appendChild(it) }
                stack.last().second.appendChild(li)
            }
            first.paragraph.before(root)
            run.forEach { it.paragraph.remove() }
            index = next
        }
        return document.body().html()
    }

    // ------------------------------------------------------------------ text copies

    private fun block(type: NoteDocBlockType, text: String, indent: Int = 0) =
        NoteDocBlock(id = "", type = type, text = text, indent = indent)

    private val LINE_BULLET = Regex("^([\\t ]*)[•●○◦▪■□➢►✓\\-–*+]\\s+(.+)$")
    /** Word's text copy marks second-level bullets with "o" and a tab. */
    private val WORD_O_BULLET = Regex("^([\\t ]*)o\\t+(.+)$")
    private val LINE_NUMBER = Regex("^([\\t ]*)(?:\\d{1,3}|[a-zA-Z]|i{2,3}|iv|vi{1,3}|ix)[.)]\\s+(.+)$")

    private fun indentOf(lead: String): Int =
        (lead.count { it == '\t' } + lead.count { it == ' ' } / 4).coerceAtMost(5)

    /** Document and page text: every line is its own paragraph; typed bullets and numbers are lists. */
    private fun documentLines(text: String): List<NoteDocBlock> = text.split('\n').mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        LINE_BULLET.matchEntire(line)?.let { return@mapNotNull block(NoteDocBlockType.BULLET, it.groupValues[2].trim(), indentOf(it.groupValues[1])) }
        WORD_O_BULLET.matchEntire(line)?.let { return@mapNotNull block(NoteDocBlockType.BULLET, it.groupValues[2].trim(), maxOf(1, indentOf(it.groupValues[1]))) }
        LINE_NUMBER.matchEntire(line)?.let { return@mapNotNull block(NoteDocBlockType.NUMBERED, it.groupValues[2].trim(), indentOf(it.groupValues[1])) }
        block(NoteDocBlockType.PARAGRAPH, line.trim())
    }

    private fun plainLines(text: String): List<NoteDocBlock> =
        text.split('\n').filter(String::isNotBlank).map { block(NoteDocBlockType.PARAGRAPH, it.trimEnd()) }

    private val SENTENCE_END = Regex("[.!?:;\"”’)\\]]$")

    private fun isCapitalHeading(line: String): Boolean {
        val letters = line.filter(Char::isLetter)
        return line.length <= 80 && letters.length >= 3 && letters.all { it.isUpperCase() } &&
            !SENTENCE_END.containsMatchIn(line.trimEnd().removeSuffix(":"))
    }

    /**
     * A PDF copy breaks lines where the page did. Lines are joined back into paragraphs; a short
     * line ending a sentence closes its paragraph, and hyphenated words are rejoined.
     */
    internal fun pdfText(text: String): List<NoteDocBlock> {
        val lines = text.split('\n').map { it.trimEnd() }
        val widths = lines.map { it.trim().length }.filter { it >= 20 }.sorted()
        val typical = widths.getOrNull(widths.size / 2) ?: 60
        val result = mutableListOf<NoteDocBlock>()
        var current: StringBuilder? = null
        var currentType = NoteDocBlockType.PARAGRAPH
        var currentIndent = 0
        var lastLine = ""
        fun flush() {
            current?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let { result += block(currentType, it, currentIndent) }
            current = null
        }
        fun append(piece: String) {
            val builder = current ?: StringBuilder().also { current = it }
            val hyphenated = builder.length > 1 && builder.endsWith("-") &&
                builder[builder.length - 2].isLetter() && piece.firstOrNull()?.isLowerCase() == true
            when {
                builder.isEmpty() -> builder.append(piece)
                hyphenated -> { builder.setLength(builder.length - 1); builder.append(piece) }
                else -> builder.append(' ').append(piece)
            }
        }
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                isCapitalHeading(line) && (current == null || SENTENCE_END.containsMatchIn(lastLine)) -> {
                    flush(); result += block(NoteDocBlockType.HEADING_2, line)
                }
                LINE_BULLET.matchEntire(raw) != null -> {
                    flush(); val m = LINE_BULLET.matchEntire(raw)!!
                    currentType = NoteDocBlockType.BULLET; currentIndent = indentOf(m.groupValues[1]); append(m.groupValues[2].trim())
                }
                LINE_NUMBER.matchEntire(raw) != null -> {
                    flush(); val m = LINE_NUMBER.matchEntire(raw)!!
                    currentType = NoteDocBlockType.NUMBERED; currentIndent = indentOf(m.groupValues[1]); append(m.groupValues[2].trim())
                }
                else -> {
                    val closes = current != null && SENTENCE_END.containsMatchIn(lastLine) &&
                        lastLine.length < typical * 0.8 && line.first().let { it.isUpperCase() || it.isDigit() }
                    if (closes || current == null) {
                        flush(); currentType = NoteDocBlockType.PARAGRAPH; currentIndent = 0
                    }
                    append(line)
                }
            }
            lastLine = line
        }
        flush()
        return result
    }
}

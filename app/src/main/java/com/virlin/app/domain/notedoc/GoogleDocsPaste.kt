package com.virlin.app.domain.notedoc

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI
import java.net.URLDecoder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads the HTML Google Docs puts on the clipboard. Docs marks almost nothing with tags: every
 * span carries inline CSS (weight, style, size, colour, font), the whole slice is wrapped in a
 * `<b id="docs-internal-guid-…" style="font-weight:normal">`, list depth is `aria-level`, and
 * pictures are web addresses on Google's image servers. Only clipboard HTML that carries that
 * marker comes here; every other source keeps the general [NotePasteParser] reading.
 */
object GoogleDocsPaste {
    private const val MAX_BLOCKS = 8_000
    private const val NOTE_TEXT_SP = 16f

    private val MARKER = Regex("docs-internal-guid|content=\"Google Docs\"", RegexOption.IGNORE_CASE)

    fun isGoogleDocsHtml(html: String?): Boolean = html != null && MARKER.containsMatchIn(html)

    /** Google's servers for pictures inside a copied document; the only remote pictures fetched. */
    fun isDocsImageUrl(source: String): Boolean {
        val uri = runCatching { URI(source.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            (host.endsWith(".googleusercontent.com") || host == "docs.google.com")
    }

    /** Whether the copied document has pictures that live on Google's servers. */
    fun hasRemoteImages(html: String): Boolean = runCatching {
        Jsoup.parseBodyFragment(html).select("img[src]").any { isDocsImageUrl(it.attr("src")) }
    }.getOrDefault(false)

    /**
     * Whether [inserted] is the visible text of [html]. Docs and the keyboard can disagree about
     * spacing, line breaks and chips, so only letters and digits are compared.
     */
    fun textMatches(html: String, inserted: String): Boolean = runCatching {
        val fromHtml = letters(Jsoup.parseBodyFragment(html).text())
        val typed = letters(inserted)
        if (fromHtml.isEmpty() || typed.isEmpty()) return@runCatching false
        if (fromHtml == typed) return@runCatching true
        val (short, long) = if (fromHtml.length <= typed.length) fromHtml to typed else typed to fromHtml
        short.length >= long.length * 0.9 && long.contains(short)
    }.getOrDefault(false)

    private fun letters(value: String) = value.filter(Char::isLetterOrDigit).lowercase()

    fun parse(html: String, imageResolver: ((String) -> String?)? = null): List<NoteDocBlock> =
        Reader(Jsoup.parseBodyFragment(html), imageResolver).read()

    private class Style(
        val marks: Set<NoteDocMark> = emptySet(),
        val color: Long? = null,
        val highlight: Long? = null,
        val font: String? = null,
        /** Points as Docs wrote them; turned into note sizes relative to the document's body size. */
        val points: Float? = null,
        val link: String? = null,
    )

    private class Reader(val document: org.jsoup.nodes.Document, val imageResolver: ((String) -> String?)?) {
        val blocks = mutableListOf<NoteDocBlock>()
        val bodyPoints = bodySize()

        // The paragraph being gathered.
        private var type = NoteDocBlockType.PARAGRAPH
        private var indent = 0
        private var align = NoteDocAlign.LEFT
        private var checked = false
        private val text = StringBuilder()
        private val runs = mutableListOf<NoteDocRun>()
        private val sizes = mutableListOf<Pair<Int, Float?>>()

        fun read(): List<NoteDocBlock> {
            visit(document.body(), null, 0)
            return blocks
        }

        /** The size most of the document's text uses; Docs' own default is 11 pt. */
        private fun bodySize(): Float {
            val weight = HashMap<Float, Int>()
            document.select("span[style]").forEach { span ->
                val size = points(span.attr("style")) ?: return@forEach
                weight[size] = (weight[size] ?: 0) + span.ownText().length
            }
            return weight.maxByOrNull { it.value }?.key?.takeIf { it > 0f } ?: 11f
        }

        private fun visit(parent: Element, list: NoteDocBlockType?, depth: Int) {
            for (node in parent.childNodes()) {
                if (blocks.size >= MAX_BLOCKS) return
                when (node) {
                    is TextNode -> if (node.wholeText.isNotBlank()) paragraph(parent, NoteDocBlockType.PARAGRAPH, 0) {
                        inline(node, Style())
                    }
                    is Element -> element(node, list, depth)
                }
            }
        }

        private fun element(node: Element, list: NoteDocBlockType?, depth: Int) {
            when (val tag = node.normalName()) {
                "script", "style", "meta", "title", "head", "iframe", "canvas", "svg" -> Unit
                "br" -> Unit
                "hr" -> blocks += NoteDocBlock(id = "", type = NoteDocBlockType.DIVIDER)
                "img" -> image(node)
                "ul", "ol" -> visit(node, if (tag == "ol") NoteDocBlockType.NUMBERED else NoteDocBlockType.BULLET, depth + 1)
                "li" -> listItem(node, list ?: NoteDocBlockType.BULLET, depth)
                "table" -> table(node)
                "h1", "h2", "h3", "h4", "h5", "h6" -> paragraph(node, HEADINGS[(tag[1] - '1').coerceAtMost(4)], 0) {
                    node.childNodes().forEach { inline(it, Style()) }
                }
                "blockquote" -> if (hasBlocks(node)) visit(node, list, depth)
                    else paragraph(node, NoteDocBlockType.QUOTE, 0) { node.childNodes().forEach { inline(it, Style()) } }
                "pre" -> {
                    val code = node.wholeText().trimEnd()
                    if (code.isNotBlank()) blocks += NoteDocBlock(id = "", type = NoteDocBlockType.CODE, text = code, mathMode = false)
                }
                else -> if (hasBlocks(node)) visit(node, list, depth)
                    else paragraph(node, NoteDocBlockType.PARAGRAPH, 0) { node.childNodes().forEach { inline(it, Style()) } }
            }
        }

        private fun listItem(node: Element, list: NoteDocBlockType, depth: Int) {
            val css = node.attr("style").lowercase()
            val bullet = css(css, "list-style-type")
            val kind = when {
                node.attr("role") == "checkbox" || node.hasAttr("aria-checked") -> NoteDocBlockType.CHECKLIST
                bullet == null -> list
                bullet in setOf("disc", "circle", "square", "none") -> NoteDocBlockType.BULLET
                else -> NoteDocBlockType.NUMBERED
            }
            val level = node.attr("aria-level").toIntOrNull()?.minus(1) ?: (depth - 1)
            paragraph(node, kind, level.coerceIn(0, 5)) {
                checked = node.attr("aria-checked").equals("true", ignoreCase = true)
                node.childNodes().forEach { child ->
                    if (child is Element && child.normalName() in setOf("ul", "ol")) Unit else inline(child, Style())
                }
            }
            node.children().filter { it.normalName() in setOf("ul", "ol") }.forEach {
                visit(it, if (it.normalName() == "ol") NoteDocBlockType.NUMBERED else NoteDocBlockType.BULLET, depth + 1)
            }
        }

        /** Gathers one block's inline content; pictures inside it split it where they stand. */
        private fun paragraph(node: Element, kind: NoteDocBlockType, level: Int, content: () -> Unit) {
            flush()
            type = kind; indent = level; checked = false
            align = alignment(node) ?: node.children().firstOrNull { it.normalName() == "p" }?.let(::alignment)
                ?: NoteDocAlign.LEFT
            content()
            flush()
        }

        private fun inline(node: Node, inherited: Style) {
            when (node) {
                is TextNode -> append(node.wholeText, inherited)
                is Element -> {
                    val tag = node.normalName()
                    when (tag) {
                        "script", "style", "svg", "canvas", "iframe" -> return
                        "br" -> { append("\n", inherited); return }
                        "img" -> { image(node); return }
                    }
                    val style = styleOf(node, inherited)
                    node.childNodes().forEach { inline(it, style) }
                }
            }
        }

        private fun styleOf(node: Element, inherited: Style): Style {
            val css = node.attr("style").lowercase()
            val marks = inherited.marks.toMutableSet()
            when (node.normalName()) {
                "b", "strong" -> marks += NoteDocMark.BOLD
                "i", "em" -> marks += NoteDocMark.ITALIC
                "u", "ins" -> marks += NoteDocMark.UNDERLINE
                "s", "del", "strike" -> marks += NoteDocMark.STRIKE
                "code" -> marks += NoteDocMark.CODE
            }
            css(css, "font-weight")?.let { weight ->
                val bold = weight == "bold" || weight == "bolder" || (weight.toIntOrNull() ?: 400) >= 600
                if (bold) marks += NoteDocMark.BOLD else marks -= NoteDocMark.BOLD
            }
            css(css, "font-style")?.let { if (it == "italic" || it == "oblique") marks += NoteDocMark.ITALIC else marks -= NoteDocMark.ITALIC }
            css(css, "text-decoration")?.let { decoration ->
                if ("underline" in decoration) marks += NoteDocMark.UNDERLINE
                if ("line-through" in decoration) marks += NoteDocMark.STRIKE
            }
            val link = node.takeIf { it.normalName() == "a" }?.attr("href")?.let(::safeLink)
            return Style(
                marks = marks,
                color = css(css, "color")?.let(::color)?.takeUnless { it == 0xFF000000L } ?: inherited.color,
                highlight = css(css, "background-color")?.let(::color)?.takeUnless { it == 0xFFFFFFFFL } ?: inherited.highlight,
                font = css(css, "font-family")?.let(::font) ?: inherited.font,
                points = points(css) ?: inherited.points,
                link = link ?: inherited.link,
            )
        }

        private fun append(value: String, style: Style) {
            if (value.isEmpty()) return
            val start = text.length
            text.append(value.replace('\u00A0', ' ').replace('\t', ' ').replace('\u000B', '\n'))
            val size = style.points?.let(::noteSize)
            sizes += text.length to style.points
            val run = NoteDocRun(start, text.length, style.marks, style.color, style.highlight, style.font, size, style.link)
            if (!run.isEmptyStyle) runs += run
        }

        /** Note size for [points], or null when it is the document's normal size. */
        private fun noteSize(points: Float): Int? {
            if (abs(points - bodyPoints) < 0.75f) return null
            return (NOTE_TEXT_SP * points / bodyPoints).roundToInt().coerceIn(8, 96)
        }

        private fun flush() {
            val raw = text.toString()
            val lead = raw.length - raw.trimStart().length
            val value = raw.trim()
            if (value.isNotEmpty() && blocks.size < MAX_BLOCKS) {
                val kind = if (type == NoteDocBlockType.PARAGRAPH) titleLevel(raw, value.length) ?: type else type
                val heading = kind.headingLevel > 0
                val shifted = runs.mapNotNull { r ->
                    val a = (r.start - lead).coerceIn(0, value.length)
                    val b = (r.end - lead).coerceIn(0, value.length)
                    if (b <= a) null else r.copy(start = a, end = b, fontSizeSp = if (heading) null else r.fontSizeSp)
                }.filterNot { it.isEmptyStyle }
                blocks += NoteDocBlock(
                    id = "", type = kind, text = value,
                    runs = NoteRuns.normalize(shifted, value.length),
                    align = align, indent = if (heading) 0 else indent,
                    checked = kind == NoteDocBlockType.CHECKLIST && checked,
                )
            }
            text.setLength(0); runs.clear(); sizes.clear()
        }

        /**
         * Docs' Title and Subtitle styles copy as plain paragraphs in much larger type; a short
         * paragraph set entirely that large becomes a heading instead of oversized body text.
         */
        private fun titleLevel(raw: String, length: Int): NoteDocBlockType? {
            if (length > 160 || '\n' in raw.trim()) return null
            var start = 0
            var smallest = Float.MAX_VALUE
            for ((end, points) in sizes) {
                val visible = raw.substring(start.coerceAtMost(raw.length), end.coerceAtMost(raw.length)).isNotBlank()
                if (visible) smallest = minOf(smallest, points ?: return null)
                start = end
            }
            if (smallest == Float.MAX_VALUE) return null
            return when {
                smallest >= bodyPoints * 1.75f -> NoteDocBlockType.HEADING_1
                smallest >= bodyPoints * 1.3f -> NoteDocBlockType.HEADING_2
                else -> null
            }
        }

        private fun image(node: Element) {
            val source = node.attr("src").trim()
            if (source.isEmpty()) return
            val stored = runCatching { imageResolver?.invoke(source) }.getOrNull()
            flushKeepingParagraph {
                if (stored != null) blocks += NoteDocBlock(id = "", type = NoteDocBlockType.IMAGE, imagePath = stored)
                else {
                    val label = node.attr("alt").trim().ifBlank { "Picture from Google Docs (couldn't be downloaded)" }
                    val web = source.takeIf { it.startsWith("https://") }
                    blocks += NoteDocBlock(id = "", text = label,
                        runs = if (web != null) listOf(NoteDocRun(0, label.length, emptySet(), linkUrl = web)) else emptyList())
                }
            }
        }

        /** Ends the text gathered so far, adds a block, and carries on in the same paragraph style. */
        private fun flushKeepingParagraph(add: () -> Unit) {
            val keepType = type; val keepIndent = indent; val keepAlign = align
            flush()
            if (blocks.size < MAX_BLOCKS) add()
            type = keepType; indent = keepIndent; align = keepAlign
        }

        private fun table(node: Element) {
            flush()
            val rows = node.select("tr").filter { it.closest("table") == node }.map { row ->
                row.children().filter { it.normalName() in setOf("td", "th") }.flatMap { cell ->
                    val value = cell.select("p").map { it.text().trim() }.filter(String::isNotEmpty)
                        .ifEmpty { listOf(cell.text().trim()) }.joinToString(" ")
                    List(cell.attr("colspan").toIntOrNull()?.coerceIn(1, 12) ?: 1) { i -> if (i == 0) value else "" }
                }
            }.filter { row -> row.any { it.isNotBlank() } }
            if (rows.isEmpty()) return
            val cols = rows.maxOf { it.size }
            if (rows.size <= NoteDocTable.MAX_ROWS && cols <= NoteDocTable.MAX_COLS) {
                blocks += NoteDocBlock(id = "", type = NoteDocBlockType.TABLE, table = NoteDocTable(
                    rows.size, cols, rows.flatMap { row -> row + List(cols - row.size) { "" } }))
            } else rows.forEach { row ->
                blocks += NoteDocBlock(id = "", text = row.filter { it.isNotBlank() }.joinToString("  |  "))
            }
            node.select("img").forEach(::image)
        }

        private fun hasBlocks(node: Element) = node.children().any { it.normalName() in BLOCK_TAGS }

        private fun alignment(node: Element): NoteDocAlign? = when (css(node.attr("style").lowercase(), "text-align")) {
            "center" -> NoteDocAlign.CENTER
            "right", "end" -> NoteDocAlign.RIGHT
            "left", "start", "justify" -> NoteDocAlign.LEFT
            else -> null
        }
    }

    private val HEADINGS = listOf(NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2,
        NoteDocBlockType.HEADING_3, NoteDocBlockType.HEADING_4, NoteDocBlockType.HEADING_5)

    private val BLOCK_TAGS = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
        "table", "hr", "blockquote", "pre")

    private fun css(style: String, name: String): String? =
        Regex("(?:^|;)\\s*${Regex.escape(name)}\\s*:\\s*([^;]+)").find(style)?.groupValues?.get(1)?.trim()

    private fun points(style: String): Float? {
        val match = Regex("([\\d.]+)\\s*(pt|px)").matchEntire(css(style.lowercase(), "font-size") ?: return null)
            ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        return (if (match.groupValues[2] == "px") value * 0.75f else value).takeIf { it in 4f..200f }
    }

    private fun color(value: String): Long? {
        val raw = value.trim().lowercase()
        val hex = raw.removePrefix("#")
        val rgb = when {
            raw.startsWith("#") && hex.length == 6 -> hex.toIntOrNull(16)
            raw.startsWith("#") && hex.length == 3 -> hex.map { "$it$it" }.joinToString("").toIntOrNull(16)
            raw.startsWith("rgb") -> Regex("rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*(?:,\\s*([\\d.]+)\\s*)?\\)")
                .matchEntire(raw)?.groupValues?.let { g ->
                    if (g[4].isNotEmpty() && (g[4].toFloatOrNull() ?: 1f) == 0f) return null
                    val c = g.subList(1, 4).map { it.toInt() }
                    if (c.any { it > 255 }) null else (c[0] shl 16) or (c[1] shl 8) or c[2]
                }
            else -> null
        } ?: return null
        return 0xFF000000L or rgb.toLong()
    }

    private fun font(family: String): String? {
        val name = family.lowercase()
        return when {
            listOf("mono", "courier", "consolas", "source code").any { it in name } -> "Mono"
            "sans" in name -> null
            listOf("serif", "times", "georgia", "merriweather", "lora", "garamond", "playfair").any { it in name } -> "Serif"
            else -> null
        }
    }

    /** Docs routes outside links through google.com/url?q=…; the real address is kept. */
    private fun safeLink(href: String): String? {
        var url = href.trim()
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.host?.lowercase()?.endsWith("google.com") == true && uri.path == "/url") {
            uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("q=") }?.let {
                url = runCatching { URLDecoder.decode(it.removePrefix("q="), "UTF-8") }.getOrDefault(url)
            }
        }
        val scheme = url.substringBefore(':', "").lowercase()
        return url.takeIf { scheme in setOf("http", "https", "mailto") && it.length <= 2_000 }
    }
}

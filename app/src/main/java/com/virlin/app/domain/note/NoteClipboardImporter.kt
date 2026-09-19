package com.virlin.app.domain.note

import com.virlin.app.domain.model.InlineStyle
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.TextMark

/**
 * Layered clipboard → Virlin blocks import.
 * Priority: HTML (sanitized) → Markdown-like plain → plain paragraphs.
 * No app-specific coupling; best-effort fidelity only.
 */
object NoteClipboardImporter {

    fun import(
        html: String?,
        plain: String,
        newId: () -> String
    ): List<NoteBlock> {
        val htmlMd = html?.takeIf { it.isNotBlank() }?.let { htmlToRoughMarkdown(it) }
        val source = when {
            htmlMd != null && looksStructured(htmlMd) -> htmlMd
            looksLikeMarkdown(plain) -> plain
            htmlMd != null && htmlMd.isNotBlank() -> htmlMd
            else -> plain
        }
        val trimmed = source.replace("\r\n", "\n").trim()
        if (trimmed.isEmpty()) return emptyList()
        return if (looksLikeMarkdown(trimmed) || looksStructured(trimmed)) {
            parseMarkdown(trimmed, newId)
        } else {
            parsePlain(trimmed, newId)
        }
    }

    fun looksLikeMarkdown(text: String): Boolean {
        val lines = text.lineSequence().take(40).toList()
        return lines.any { line ->
            HEADING.matches(line) ||
                BULLET.matches(line) ||
                NUMBERED.matches(line) ||
                CHECKBOX.matches(line) ||
                line.startsWith("> ") ||
                line.trim() == "---" ||
                line.trim() == "***" ||
                line.startsWith("```")
        }
    }

    private fun looksStructured(text: String): Boolean =
        text.contains('\n') || looksLikeMarkdown(text)

    /** Sanitize + map common HTML tags to rough Markdown (no scripts/styles). */
    fun htmlToRoughMarkdown(html: String): String {
        var s = html
        s = SCRIPT_STYLE.replace(s, "")
        s = s.replace(Regex("<h1[^>]*>", RegexOption.IGNORE_CASE), "\n# ")
            .replace(Regex("</h1>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<h2[^>]*>", RegexOption.IGNORE_CASE), "\n## ")
            .replace(Regex("</h2>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<h3[^>]*>", RegexOption.IGNORE_CASE), "\n### ")
            .replace(Regex("</h3>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<blockquote[^>]*>", RegexOption.IGNORE_CASE), "\n> ")
            .replace(Regex("</blockquote>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<pre[^>]*>\\s*<code[^>]*>", RegexOption.IGNORE_CASE), "\n```\n")
            .replace(Regex("</code>\\s*</pre>", RegexOption.IGNORE_CASE), "\n```\n")
        s = s.replace(Regex("<pre[^>]*>", RegexOption.IGNORE_CASE), "\n```\n")
            .replace(Regex("</pre>", RegexOption.IGNORE_CASE), "\n```\n")
        s = s.replace(Regex("<code[^>]*>", RegexOption.IGNORE_CASE), "`")
            .replace(Regex("</code>", RegexOption.IGNORE_CASE), "`")
        s = s.replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n- ")
            .replace(Regex("</li>", RegexOption.IGNORE_CASE), "")
        // Prefer numbered markers when inside <ol> (best-effort; nested lists stay flat).
        s = s.replace(Regex("<ol[^>]*>([\\s\\S]*?)</ol>", RegexOption.IGNORE_CASE)) { m ->
            val inner = m.groupValues[1]
                .replace(Regex("\\n- "), "\n1. ")
            "\n$inner\n"
        }
        s = s.replace(Regex("</?(ul|ol)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<hr\\s*/?>", RegexOption.IGNORE_CASE), "\n---\n")
        s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("<strong[^>]*>|<b[^>]*>", RegexOption.IGNORE_CASE), "**")
            .replace(Regex("</strong>|</b>", RegexOption.IGNORE_CASE), "**")
        s = s.replace(Regex("<em[^>]*>|<i[^>]*>", RegexOption.IGNORE_CASE), "_")
            .replace(Regex("</em>|</i>", RegexOption.IGNORE_CASE), "_")
        s = s.replace(Regex("<u[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</u>", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("<s[^>]*>|<del[^>]*>|<strike[^>]*>", RegexOption.IGNORE_CASE), "~~")
            .replace(Regex("</s>|</del>|</strike>", RegexOption.IGNORE_CASE), "~~")
        s = s.replace(Regex("<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)) { m ->
            val url = m.groupValues[1]
            val label = TAGS.replace(m.groupValues[2], "").trim().ifBlank { url }
            "[$label]($url)"
        }
        s = TAGS.replace(s, "")
        return decodeEntities(s)
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun parseMarkdown(text: String, newId: () -> String): List<NoteBlock> {
        val out = mutableListOf<NoteBlock>()
        val lines = text.replace("\r\n", "\n").lines()
        var i = 0
        var para = StringBuilder()
        fun flushPara() {
            val t = para.toString().trimEnd()
            para = StringBuilder()
            if (t.isNotBlank()) out += textBlock(newId(), t)
        }
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    flushPara()
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        if (body.isNotEmpty()) body.append('\n')
                        body.append(lines[i])
                        i++
                    }
                    out += NoteBlock(newId(), NoteBlockType.CODE, body.toString())
                    if (i < lines.size) i++ // closing fence
                }
                line.trim() == "---" || line.trim() == "***" || line.trim() == "___" -> {
                    flushPara()
                    out += NoteBlock(newId(), NoteBlockType.DIVIDER)
                    i++
                }
                HEADING.matches(line) -> {
                    flushPara()
                    val m = HEADING.matchEntire(line)!!
                    val level = m.groupValues[1].length
                    val content = m.groupValues[2].trim()
                    val type = when (level) {
                        1 -> NoteBlockType.HEADING_1
                        2 -> NoteBlockType.HEADING_2
                        else -> NoteBlockType.HEADING_3
                    }
                    out += NoteBlock(newId(), type, content)
                    i++
                }
                CHECKBOX.matches(line) -> {
                    flushPara()
                    val m = CHECKBOX.matchEntire(line)!!
                    val checked = m.groupValues[1].equals("x", true)
                    out += NoteBlock(newId(), NoteBlockType.CHECKBOX, m.groupValues[2].trim(), checked = checked)
                    i++
                }
                BULLET.matches(line) -> {
                    flushPara()
                    out += NoteBlock(newId(), NoteBlockType.BULLETED_LIST, BULLET.matchEntire(line)!!.groupValues[1].trim())
                    i++
                }
                NUMBERED.matches(line) -> {
                    flushPara()
                    out += NoteBlock(newId(), NoteBlockType.NUMBERED_LIST, NUMBERED.matchEntire(line)!!.groupValues[1].trim())
                    i++
                }
                line.startsWith("> ") || line == ">" -> {
                    flushPara()
                    val q = StringBuilder(line.removePrefix("> ").removePrefix(">"))
                    i++
                    while (i < lines.size && (lines[i].startsWith("> ") || lines[i] == ">")) {
                        q.append('\n').append(lines[i].removePrefix("> ").removePrefix(">"))
                        i++
                    }
                    out += NoteBlock(newId(), NoteBlockType.QUOTE, q.toString().trim())
                }
                line.isBlank() -> {
                    flushPara()
                    i++
                }
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line)
                    i++
                }
            }
        }
        flushPara()
        return out.ifEmpty { listOf(NoteBlock(newId(), NoteBlockType.TEXT, text)) }
    }

    fun parsePlain(text: String, newId: () -> String): List<NoteBlock> {
        val parts = text.split(Regex("\n{2,}"))
        return if (parts.size == 1) {
            listOf(NoteBlock(newId(), NoteBlockType.TEXT, text))
        } else {
            parts.map { NoteBlock(newId(), NoteBlockType.TEXT, it.trim()) }.filter { it.plainText.isNotEmpty() }
                .ifEmpty { listOf(NoteBlock(newId(), NoteBlockType.TEXT, text)) }
        }
    }

    private fun textBlock(id: String, raw: String): NoteBlock {
        val (plain, marks) = extractInline(raw)
        return NoteBlock(id, NoteBlockType.TEXT, plain, marks)
    }

    /** Minimal inline: **bold**, _italic_, ~~strike~~, `code`. */
    fun extractInline(raw: String): Pair<String, List<TextMark>> {
        val marks = mutableListOf<TextMark>()
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            when {
                raw.startsWith("**", i) -> {
                    val end = raw.indexOf("**", i + 2)
                    if (end > i) {
                        val start = out.length
                        out.append(raw.substring(i + 2, end))
                        marks += TextMark(start, out.length, InlineStyle.BOLD)
                        i = end + 2
                    } else {
                        out.append(raw[i]); i++
                    }
                }
                raw.startsWith("~~", i) -> {
                    val end = raw.indexOf("~~", i + 2)
                    if (end > i) {
                        val start = out.length
                        out.append(raw.substring(i + 2, end))
                        marks += TextMark(start, out.length, InlineStyle.STRIKETHROUGH)
                        i = end + 2
                    } else {
                        out.append(raw[i]); i++
                    }
                }
                raw[i] == '`' -> {
                    val end = raw.indexOf('`', i + 1)
                    if (end > i) {
                        val start = out.length
                        out.append(raw.substring(i + 1, end))
                        marks += TextMark(start, out.length, InlineStyle.CODE)
                        i = end + 1
                    } else {
                        out.append(raw[i]); i++
                    }
                }
                raw.startsWith("[", i) && raw.indexOf("](", i) > i -> {
                    val closeLabel = raw.indexOf("](", i)
                    val closeUrl = raw.indexOf(')', closeLabel + 2)
                    if (closeLabel > i && closeUrl > closeLabel) {
                        val label = raw.substring(i + 1, closeLabel)
                        val url = raw.substring(closeLabel + 2, closeUrl)
                        val start = out.length
                        out.append(label)
                        marks += TextMark(start, out.length, InlineStyle.LINK, linkUrl = url)
                        i = closeUrl + 1
                    } else {
                        out.append(raw[i]); i++
                    }
                }
                raw[i] == '_' -> {
                    val end = raw.indexOf('_', i + 1)
                    if (end > i) {
                        val start = out.length
                        out.append(raw.substring(i + 1, end))
                        marks += TextMark(start, out.length, InlineStyle.ITALIC)
                        i = end + 1
                    } else {
                        out.append(raw[i]); i++
                    }
                }
                else -> {
                    out.append(raw[i]); i++
                }
            }
        }
        return out.toString() to marks
    }

    private fun decodeEntities(s: String): String =
        s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")

    private val SCRIPT_STYLE = Regex("<(script|style)[^>]*>[\\s\\S]*?</\\1>", setOf(RegexOption.IGNORE_CASE))
    private val TAGS = Regex("<[^>]+>")
    private val HEADING = Regex("^(#{1,3})\\s+(.*)$")
    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    private val CHECKBOX = Regex("^\\s*[-*]\\s+\\[([ xX])]\\s+(.*)$")
}

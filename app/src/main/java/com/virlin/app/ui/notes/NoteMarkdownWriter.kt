package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocAudio
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteMath

/**
 * A note as GitHub-flavoured Markdown. Bold, italic, strikethrough, inline code, links, lists,
 * checklists, quotes, code with its language, equations and tables keep their meaning; underline
 * uses `<u>`. Colours, fonts and sizes have no Markdown form and are left out.
 */
internal object NoteMarkdownWriter {

    /** [image] gives the relative path a picture was saved under, or null to leave it out. */
    fun write(title: String?, blocks: List<NoteDocBlock>, image: (String) -> String?): String {
        val out = StringBuilder()
        val heading = title?.trim()?.takeIf { it.isNotEmpty() }
        heading?.let { out.append("# ").append(escape(it)) }
        val shift = if (heading != null) 1 else 0
        var previous: NoteDocBlock? = null
        var number = 0
        blocks.forEach { b ->
            val listItem = b.type == NoteDocBlockType.BULLET || b.type == NoteDocBlockType.NUMBERED ||
                b.type == NoteDocBlockType.CHECKLIST
            number = if (b.type == NoteDocBlockType.NUMBERED) {
                if (previous?.type == NoteDocBlockType.NUMBERED && previous?.indent == b.indent) number + 1 else 1
            } else 0
            val prev = previous
            val chunk = block(b, number, shift, image)
            if (chunk == null) { previous = b; return@forEach }
            val tight = listItem && prev != null && (prev.type == NoteDocBlockType.BULLET ||
                prev.type == NoteDocBlockType.NUMBERED || prev.type == NoteDocBlockType.CHECKLIST)
            if (out.isNotEmpty()) out.append(if (tight) "\n" else "\n\n")
            out.append(chunk)
            previous = b
        }
        return out.append('\n').toString()
    }

    private fun block(b: NoteDocBlock, number: Int, shift: Int, image: (String) -> String?): String? {
        val indent = "  ".repeat(b.indent.coerceIn(0, 8))
        return when (b.type) {
            NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2, NoteDocBlockType.HEADING_3,
            NoteDocBlockType.HEADING_4, NoteDocBlockType.HEADING_5 ->
                "#".repeat((b.type.headingLevel + shift).coerceAtMost(6)) + " " + inline(b.text, b.runs, "<br>")
            NoteDocBlockType.BULLET -> "$indent- " + inline(b.text, b.runs, "\\\n$indent  ")
            NoteDocBlockType.NUMBERED -> "   ".repeat(b.indent.coerceIn(0, 8)) + "$number. " +
                inline(b.text, b.runs, "\\\n" + "   ".repeat(b.indent.coerceIn(0, 8) + 1))
            NoteDocBlockType.CHECKLIST -> "$indent- [${if (b.checked) "x" else " "}] " +
                inline(b.text, b.runs, "\\\n$indent      ")
            NoteDocBlockType.QUOTE -> "> " + inline(b.text, b.runs, "\\\n> ")
            NoteDocBlockType.PROMPT -> buildString {
                b.promptTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { append("> **").append(escape(it)).append("**\n>\n") }
                append("> ").append(inline(b.text, b.runs, "\\\n> "))
            }
            NoteDocBlockType.CODE -> if (NoteMath.isEquation(b)) "$$\n${b.text}\n$$" else {
                val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(b.text).maxOfOrNull { it.value.length } ?: 0) + 1))
                "$fence${language(b.text)}\n${b.text}\n$fence"
            }
            NoteDocBlockType.DIVIDER -> "---"
            NoteDocBlockType.TABLE -> b.table?.let { t ->
                fun row(r: Int) = (0 until t.cols).joinToString(" | ", "| ", " |") { c ->
                    inline(t.cell(r, c), t.runsAt(r, c), "<br>").replace("|", "\\|").ifEmpty { " " }
                }
                buildString {
                    append(row(0)).append('\n')
                    append((0 until t.cols).joinToString(" | ", "| ", " |") { "---" })
                    for (r in 1 until t.rows) append('\n').append(row(r))
                }
            }
            NoteDocBlockType.IMAGE -> b.imagePath?.let(image)?.let { "![Image]($it)" }
            NoteDocBlockType.VIDEO -> b.video?.let { v ->
                val label = escape(v.title?.takeIf { it.isNotBlank() } ?: "YouTube video")
                "[▶ $label](${v.watchUrl})" + (v.channel?.takeIf { it.isNotBlank() }?.let { " · ${escape(it)}" } ?: "")
            }
            NoteDocBlockType.AUDIO -> "*${escape(NoteDocAudio.plainText(b.audio).removeSurrounding("[", "]"))}*"
            NoteDocBlockType.TOGGLE -> "▸ " + inline(b.text, b.runs, "\\\n  ")
            NoteDocBlockType.PARAGRAPH -> if (b.text.isEmpty()) null else inline(b.text, b.runs, "\\\n")
        }
    }

    private fun language(code: String): String = when (CodeHighlighter.highlight(code).language) {
        CodeLanguage.PYTHON -> "python"
        CodeLanguage.JAVASCRIPT -> "javascript"
        CodeLanguage.TYPESCRIPT -> "typescript"
        CodeLanguage.KOTLIN -> "kotlin"
        CodeLanguage.JAVA -> "java"
        CodeLanguage.SQL -> "sql"
        CodeLanguage.BASH -> "bash"
        CodeLanguage.JSON -> "json"
        CodeLanguage.HTML -> "html"
        CodeLanguage.CSS -> "css"
        CodeLanguage.C_LIKE, CodeLanguage.PLAIN -> ""
    }

    /** One block's text with its marks; [lineBreak] replaces line breaks inside the block. */
    fun inline(text: String, runs: List<NoteDocRun>, lineBreak: String): String {
        val lines = ArrayList<String>()
        var from = 0
        while (from <= text.length) {
            val nl = text.indexOf('\n', from).let { if (it < 0) text.length else it }
            lines += line(text, runs, from, nl)
            from = nl + 1
        }
        return lines.joinToString(lineBreak)
    }

    private fun line(text: String, runs: List<NoteDocRun>, from: Int, to: Int): String {
        val out = StringBuilder()
        var at = from
        fun plain(end: Int) { if (end > at) out.append(escape(text.substring(at, end), atLineStart = at == from)) }
        runs.sortedBy { it.start }.forEach { run ->
            val s = maxOf(run.start, from)
            val e = minOf(run.end, to)
            if (e <= s || s < at) return@forEach
            plain(s)
            out.append(styled(text.substring(s, e), run, s == from))
            at = e
        }
        plain(to)
        return out.toString()
    }

    private fun styled(segment: String, run: NoteDocRun, atLineStart: Boolean): String {
        val core = segment.trim()
        if (core.isEmpty()) return segment
        val lead = segment.substring(0, segment.indexOf(core))
        val trail = segment.substring(segment.indexOf(core) + core.length)
        var s = if (NoteDocMark.CODE in run.marks) {
            val ticks = "`".repeat((Regex("`+").findAll(core).maxOfOrNull { it.value.length } ?: 0) + 1)
            if (ticks.length > 1) "$ticks $core $ticks" else "`$core`"
        } else escape(core, atLineStart && lead.isEmpty())
        if (NoteDocMark.STRIKE in run.marks) s = "~~$s~~"
        if (NoteDocMark.ITALIC in run.marks) s = "*$s*"
        if (NoteDocMark.BOLD in run.marks) s = "**$s**"
        if (NoteDocMark.UNDERLINE in run.marks) s = "<u>$s</u>"
        run.linkUrl?.takeIf { it.isNotBlank() }?.let { url -> s = "[$s](${url.replace(" ", "%20").replace(")", "%29")})" }
        return lead + s + trail
    }

    private val SPECIAL = Regex("[\\\\`*_\\[\\]<]")
    private val LINE_START = Regex("^(\\s*)([#>+-]|\\d+[.)])(?=\\s|$)")

    fun escape(text: String, atLineStart: Boolean = true): String {
        val s = SPECIAL.replace(text) { "\\" + it.value }
        return if (atLineStart) LINE_START.replace(s) { m ->
            m.groupValues[1] + m.groupValues[2].let { t -> if (t.last() == '.' || t.last() == ')') t.dropLast(1) + "\\" + t.last() else "\\" + t }
        } else s
    }
}

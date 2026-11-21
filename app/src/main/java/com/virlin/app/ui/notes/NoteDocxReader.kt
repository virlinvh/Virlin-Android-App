package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.NoteRuns
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/** What a Word document became: note blocks plus counts for an honest summary. */
internal class NoteDocxImport(
    val blocks: List<NoteDocBlock>,
    val images: Int,
    val skippedImages: Int,
    val tables: Int,
    val truncated: Boolean,
)

/**
 * Reads a Word (.docx) file — including Google Docs downloaded or converted as Word — into note
 * blocks: headings, paragraphs, bulleted and numbered lists with their levels, quotes, alignment,
 * bold / italic / underline / strike, colours, highlights, fonts, relative sizes, web links, tables
 * and pictures. Nothing is executed or fetched: external XML entities are never loaded, only web
 * and mail links are kept, and every part read is bounded in size.
 */
internal object NoteDocxReader {
    const val MAX_FILE_BYTES = 50L * 1024 * 1024
    private const val MAX_XML_BYTES = 40L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    private const val MAX_IMAGES = 300
    private const val MAX_BLOCKS = 8_000
    private const val MAX_ENTRIES = 10_000
    private const val MAX_INDENT = 5

    /** A file the reader declines, with a message for the person importing it. */
    class Refused(message: String) : Exception(message)

    /** Old binary Word (.doc) and password-protected Word files both start with this OLE signature. */
    fun isOleFile(head: ByteArray): Boolean = head.size >= 4 &&
        head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()

    /** [storeImage] keeps a picture's bytes and returns its stored name, or null if it isn't a usable picture. */
    fun read(file: File, storeImage: (ByteArray) -> String?): NoteDocxImport {
        val zip = runCatching { ZipFile(file) }.getOrElse { throw Refused("This file isn't a Word document (.docx).") }
        zip.use {
            if (zip.size() > MAX_ENTRIES) throw Refused("This document has too many parts to import safely.")
            val main = mainDocumentPath(zip)
            val docEntry = zip.getEntry(main) ?: throw Refused("This file isn't a Word document (.docx).")
            val dir = main.substringBeforeLast('/', "")
            val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + main.substringAfterLast('/') + ".rels"
            val rels = zip.getEntry(relsPath)?.let { parseRels(zip.bytes(it, MAX_XML_BYTES), dir) }.orEmpty()
            val styles = zip.getEntry("$dir/styles.xml".trimStart('/'))
                ?.let { parseStyles(zip.bytes(it, MAX_XML_BYTES)) } ?: Styles()
            val numbering = zip.getEntry("$dir/numbering.xml".trimStart('/'))
                ?.let { parseNumbering(zip.bytes(it, MAX_XML_BYTES)) }.orEmpty()
            val body = BodyReader(rels, styles, numbering) { target ->
                val entry = zip.getEntry(target) ?: return@BodyReader null
                if (entry.size > MAX_IMAGE_BYTES) return@BodyReader null
                runCatching { zip.bytes(entry, MAX_IMAGE_BYTES) }.getOrNull()?.let(storeImage)
            }
            body.read(zip.bytes(docEntry, MAX_XML_BYTES))
            if (body.blocks.isEmpty()) throw Refused("This document has no text or pictures to import.")
            return NoteDocxImport(body.blocks, body.images, body.skippedImages, body.tables, body.truncated)
        }
    }

    // ------------------------------------------------------------------ package parts

    private fun mainDocumentPath(zip: ZipFile): String {
        val rels = zip.getEntry("_rels/.rels") ?: return "word/document.xml"
        var found: String? = null
        parse(zip.bytes(rels, MAX_XML_BYTES)) { p ->
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship" &&
                attr(p, "Type")?.endsWith("/officeDocument") == true) {
                found = attr(p, "Target")?.let { resolvePath("", it) }
            }
        }
        return found ?: "word/document.xml"
    }

    private class Rel(val target: String, val external: Boolean)

    private fun parseRels(xml: ByteArray, dir: String): Map<String, Rel> {
        val out = HashMap<String, Rel>()
        parse(xml) { p ->
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = attr(p, "Id") ?: return@parse
                val target = attr(p, "Target") ?: return@parse
                val external = attr(p, "TargetMode").equals("External", ignoreCase = true)
                out[id] = Rel(if (external) target else resolvePath(dir, target), external)
            }
        }
        return out
    }

    /** [target] relative to [dir] inside the package, with `..` resolved and no escape above the root. */
    private fun resolvePath(dir: String, target: String): String {
        val parts = ArrayDeque<String>()
        val start = if (target.startsWith("/")) "" else dir
        (start.split('/') + target.split('/')).forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    private class Style(
        val name: String?, val basedOn: String?, val outline: Int?,
        val numId: String?, val ilvl: Int?, val bold: Boolean?, val italic: Boolean?,
    )

    private class Styles(
        val byId: Map<String, Style> = emptyMap(),
        val defaultFont: String? = null,
        /** Half-points; Word's own default is 11 pt. */
        val defaultSize: Int = 22,
        val defaultParagraph: String? = null,
    ) {
        private fun chain(id: String?): Sequence<Style> = generateSequence(id?.let { byId[it] }) { s ->
            s.basedOn?.let { byId[it] }
        }.take(12)

        fun headingLevel(id: String?): Int {
            for (s in chain(id ?: defaultParagraph)) {
                val name = s.name?.lowercase()?.trim()
                Regex("heading\\s*(\\d)").matchEntire(name.orEmpty())?.let { return it.groupValues[1].toInt().coerceIn(1, 5) }
                if (name == "title") return 1
                if (name == "subtitle") return 2
                s.outline?.let { if (it in 0..8) return (it + 1).coerceAtMost(5) }
            }
            return 0
        }

        fun isQuote(id: String?) = chain(id).any { it.name?.lowercase()?.contains("quote") == true }

        fun isCode(id: String?) = chain(id).any { s ->
            val n = s.name?.lowercase().orEmpty()
            "code" in n || "preformatted" in n
        }

        fun listFor(id: String?): Pair<String, Int>? = chain(id).firstOrNull { it.numId != null }
            ?.let { it.numId!! to (it.ilvl ?: 0) }

        fun bold(id: String?) = chain(id).firstNotNullOfOrNull { it.bold }
        fun italic(id: String?) = chain(id).firstNotNullOfOrNull { it.italic }
    }

    private fun parseStyles(xml: ByteArray): Styles {
        val byId = HashMap<String, Style>()
        var defaultFont: String? = null
        var defaultSize = 22
        var defaultParagraph: String? = null
        var normalFont: String? = null
        var inDefaults = false
        var id: String? = null
        var isDefault = false
        var name: String? = null; var basedOn: String? = null; var outline: Int? = null
        var numId: String? = null; var ilvl: Int? = null; var bold: Boolean? = null; var italic: Boolean? = null
        var font: String? = null
        parse(xml) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "docDefaults" -> inDefaults = true
                    "style" -> {
                        id = attr(p, "styleId"); isDefault = attr(p, "default").let { it == "1" || it == "true" } &&
                            attr(p, "type") == "paragraph"
                        name = null; basedOn = null; outline = null; numId = null; ilvl = null
                        bold = null; italic = null; font = null
                    }
                    "name" -> if (id != null) name = attr(p, "val")
                    "basedOn" -> if (id != null) basedOn = attr(p, "val")
                    "outlineLvl" -> if (id != null) outline = attr(p, "val")?.toIntOrNull()
                    "numId" -> if (id != null) numId = attr(p, "val")
                    "ilvl" -> if (id != null) ilvl = attr(p, "val")?.toIntOrNull()
                    "b" -> if (id != null) bold = on(p)
                    "i" -> if (id != null) italic = on(p)
                    "rFonts" -> (attr(p, "ascii") ?: attr(p, "hAnsi"))?.let { f ->
                        if (inDefaults) defaultFont = f else if (id != null) font = f
                    }
                    "sz" -> if (inDefaults) attr(p, "val")?.toIntOrNull()?.let { defaultSize = it }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "docDefaults" -> inDefaults = false
                    "style" -> {
                        val key = id
                        if (key != null) {
                            byId[key] = Style(name, basedOn, outline, numId?.takeIf { it != "0" }, ilvl, bold, italic)
                            if (isDefault) { defaultParagraph = key; normalFont = font }
                        }
                        id = null
                    }
                }
            }
        }
        return Styles(byId, normalFont ?: defaultFont, defaultSize.coerceIn(8, 400), defaultParagraph)
    }

    /** (numId, level) → true for a bulleted level, false for a numbered one. */
    private fun parseNumbering(xml: ByteArray): Map<Pair<String, Int>, Boolean> {
        val abstractLevels = HashMap<String, HashMap<Int, Boolean>>()
        val nums = HashMap<String, String>()
        var abstractId: String? = null
        var level: Int? = null
        var numId: String? = null
        parse(xml) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "abstractNum" -> abstractId = attr(p, "abstractNumId")
                    "lvl" -> level = attr(p, "ilvl")?.toIntOrNull()
                    "numFmt" -> {
                        val a = abstractId; val l = level
                        if (a != null && l != null && numId == null)
                            abstractLevels.getOrPut(a) { HashMap() }[l] = attr(p, "val") == "bullet"
                    }
                    "num" -> numId = attr(p, "numId")
                    "abstractNumId" -> numId?.let { n -> attr(p, "val")?.let { nums[n] = it } }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "abstractNum" -> abstractId = null
                    "lvl" -> level = null
                    "num" -> numId = null
                }
            }
        }
        val out = HashMap<Pair<String, Int>, Boolean>()
        nums.forEach { (n, a) -> abstractLevels[a]?.forEach { (l, bullet) -> out[n to l] = bullet } }
        return out
    }

    // ------------------------------------------------------------------ document body

    private class RunProps(
        val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false,
        val strike: Boolean = false, val color: Long? = null, val highlight: Long? = null,
        val font: String? = null, val halfPoints: Int? = null, val hidden: Boolean = false,
    )

    private class BodyReader(
        val rels: Map<String, Rel>,
        val styles: Styles,
        val numbering: Map<Pair<String, Int>, Boolean>,
        val image: (String) -> String?,
    ) {
        val blocks = mutableListOf<NoteDocBlock>()
        var images = 0
        var skippedImages = 0
        var tables = 0
        var truncated = false
        private var nextId = 0

        // Paragraph
        private var pStyle: String? = null
        private var numId: String? = null
        private var ilvl = 0
        private var align = NoteDocAlign.LEFT
        private val text = StringBuilder()
        private val runs = mutableListOf<NoteDocRun>()
        private var run = RunProps()
        private val links = ArrayDeque<String?>()
        private var fieldInstr: StringBuilder? = null
        private var fieldLink: String? = null

        // Table
        private var tableDepth = 0
        private var rows = mutableListOf<MutableList<String>>()
        private var cell: StringBuilder? = null
        private var span = 1
        private var headerRow = false
        private val afterTable = mutableListOf<NoteDocBlock>()

        fun read(xml: ByteArray) = parse(xml) { p ->
            if (truncated) return@parse
            when (p.eventType) {
                XmlPullParser.START_TAG -> start(p)
                XmlPullParser.END_TAG -> end(p)
            }
        }

        private fun start(p: XmlPullParser) {
            val ns = p.namespace.orEmpty()
            val w = "wordprocessingml" in ns
            when {
                // Deleted tracked changes, text boxes (already drawn by their picture) and
                // Office's duplicate fallback renderings would only repeat or resurrect text.
                w && p.name in SKIPPED -> skip(p)
                p.name == "Fallback" && "markup-compatibility" in ns -> skip(p)
                w && p.name == "tbl" -> { if (tableDepth == 0) startTable(); tableDepth++ }
                w && p.name == "tr" && tableDepth == 1 -> rows.add(mutableListOf())
                w && p.name == "tblHeader" && tableDepth == 1 && rows.size == 1 -> headerRow = on(p)
                w && p.name == "tc" && tableDepth == 1 -> { cell = StringBuilder(); span = 1 }
                w && p.name == "gridSpan" && tableDepth == 1 -> span = attr(p, "val")?.toIntOrNull()?.coerceIn(1, 64) ?: 1
                w && p.name == "p" -> startParagraph()
                w && p.name == "pPr" -> readParagraphProps(p)
                w && p.name == "hyperlink" -> links.addLast(attr(p, "id")?.let { rels[it] }
                    ?.takeIf { it.external }?.target?.let(::safeLink))
                w && p.name == "r" -> run = RunProps()
                w && p.name == "rPr" -> run = readRunProps(p)
                w && p.name == "t" -> append(p.nextText())
                "officeDocument/2006/math" in ns && p.name == "t" -> append(p.nextText())
                w && (p.name == "tab" || p.name == "ptab") -> append(" ")
                w && p.name == "br" -> if (attr(p, "type") != "page" && attr(p, "type") != "column") lineBreak()
                w && p.name == "cr" -> lineBreak()
                w && p.name == "noBreakHyphen" -> append("-")
                w && p.name == "fldChar" -> when (attr(p, "fldCharType")) {
                    "begin" -> { fieldInstr = StringBuilder(); fieldLink = null }
                    "separate" -> { fieldLink = fieldInstr?.let(::fieldHyperlink); fieldInstr = null }
                    "end" -> { fieldInstr = null; fieldLink = null }
                }
                w && p.name == "instrText" -> fieldInstr?.append(p.nextText()) ?: skip(p)
                p.name == "blip" -> (attr(p, "embed"))?.let(::picture)
                p.name == "imagedata" -> attr(p, "id")?.let(::picture)
                w && p.name == "sectPr" -> skip(p)
            }
        }

        private fun end(p: XmlPullParser) {
            if ("wordprocessingml" !in p.namespace.orEmpty()) return
            when (p.name) {
                "p" -> endParagraph()
                "hyperlink" -> links.removeLastOrNull()
                "r" -> run = RunProps()
                "tc" -> if (tableDepth == 1) {
                    rows.lastOrNull()?.let { row ->
                        row.add(cell?.toString()?.trim()?.replace(Regex("\\s+"), " ").orEmpty())
                        repeat(span - 1) { row.add("") }
                    }
                    cell = null
                }
                "tbl" -> { tableDepth--; if (tableDepth == 0) endTable() }
            }
        }

        private fun startParagraph() {
            if (text.isNotEmpty()) flush()
            pStyle = null; numId = null; ilvl = 0; align = NoteDocAlign.LEFT
            text.setLength(0); runs.clear(); run = RunProps()
        }

        private fun readParagraphProps(p: XmlPullParser) {
            val depth = p.depth
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.END_TAG && p.depth == depth) return
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "pStyle" -> pStyle = attr(p, "val")
                    "numId" -> numId = attr(p, "val")?.takeIf { it != "0" }
                    "ilvl" -> ilvl = attr(p, "val")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                    "jc" -> align = when (attr(p, "val")) {
                        "center" -> NoteDocAlign.CENTER
                        "right", "end" -> NoteDocAlign.RIGHT
                        else -> NoteDocAlign.LEFT
                    }
                    "rPr", "sectPr", "pPrChange" -> skip(p)
                }
            }
        }

        private fun readRunProps(p: XmlPullParser): RunProps {
            var bold = false; var italic = false; var underline = false; var strike = false
            var color: Long? = null; var highlight: Long? = null; var font: String? = null
            var size: Int? = null; var hidden = false
            var styleBold: Boolean? = null; var styleItalic: Boolean? = null
            val depth = p.depth
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.END_TAG && p.depth == depth) break
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "rStyle" -> attr(p, "val").let { s -> styleBold = styles.bold(s); styleItalic = styles.italic(s) }
                    "b" -> bold = on(p)
                    "i" -> italic = on(p)
                    "u" -> underline = attr(p, "val") != "none"
                    "strike", "dstrike" -> strike = strike || on(p)
                    "color" -> color = hexColor(attr(p, "val"))?.takeIf { it != 0xFF000000L }
                    "highlight" -> highlight = HIGHLIGHTS[attr(p, "val")]
                    "shd" -> if (highlight == null) highlight = hexColor(attr(p, "fill"))?.takeIf { it != 0xFFFFFFFFL }
                    "rFonts" -> font = attr(p, "ascii") ?: attr(p, "hAnsi")
                    "sz" -> size = attr(p, "val")?.toIntOrNull()
                    "vanish" -> hidden = on(p)
                    "rPrChange" -> skip(p)
                }
            }
            return RunProps(bold || styleBold == true, italic || styleItalic == true, underline, strike,
                color, highlight, font, size, hidden)
        }

        private fun append(value: String) {
            if (value.isEmpty() || run.hidden) return
            cell?.let { if (tableDepth > 0) { it.append(value); return } }
            if (tableDepth > 0) return
            val start = text.length
            text.append(value.replace('\t', ' ').replace('\u00A0', ' '))
            val marks = buildSet {
                if (run.bold) add(NoteDocMark.BOLD)
                if (run.italic) add(NoteDocMark.ITALIC)
                if (run.underline) add(NoteDocMark.UNDERLINE)
                if (run.strike) add(NoteDocMark.STRIKE)
            }
            val link = links.lastOrNull { it != null } ?: fieldLink
            val styled = NoteDocRun(start, text.length, marks, run.color, run.highlight,
                fontFor(run.font), sizeFor(run.halfPoints), link)
            if (!styled.isEmptyStyle) runs += styled
        }

        private fun fontFor(name: String?): String? {
            if (name == null || name.equals(styles.defaultFont, ignoreCase = true)) return null
            return when (name.lowercase()) {
                "calibri", "arial", "helvetica", "segoe ui", "aptos", "calibri light", "arial nova" -> null
                "times new roman", "georgia", "cambria", "garamond", "book antiqua", "palatino linotype" -> "Serif"
                "courier new", "consolas", "courier", "lucida console" -> "Mono"
                else -> name.take(60)
            }
        }

        /** Sizes far from the document's normal size keep their proportion against the note's 16 sp text. */
        private fun sizeFor(halfPoints: Int?): Int? {
            if (halfPoints == null || kotlin.math.abs(halfPoints - styles.defaultSize) < 4) return null
            return (16f * halfPoints / styles.defaultSize).roundToInt().coerceIn(NOTE_MIN_TEXT_SIZE, NOTE_MAX_TEXT_SIZE)
        }

        private fun lineBreak() {
            if (tableDepth > 0) { cell?.append(' '); return }
            flush()
        }

        private fun picture(rid: String) {
            val rel = rels[rid]?.takeUnless { it.external } ?: return
            if (images + skippedImages >= MAX_IMAGES) { skippedImages++; return }
            val name = image(rel.target)
            if (name == null) { skippedImages++; return }
            images++
            val block = NoteDocBlock(id = id(), type = NoteDocBlockType.IMAGE, imagePath = name)
            if (tableDepth > 0) afterTable += block else { flush(); add(block) }
        }

        private fun endParagraph() {
            if (tableDepth > 0) { cell?.let { if (it.isNotEmpty() && !it.endsWith(" ")) it.append(' ') }; return }
            flush()
        }

        /** Turns the gathered text into a block (skipping blank lines) and starts a continuation. */
        private fun flush() {
            if (tableDepth > 0) return
            val raw = text.toString()
            val lead = raw.length - raw.trimStart().length
            val value = raw.trim()
            if (value.isNotEmpty()) {
                val heading = styles.headingLevel(pStyle)
                val list = numId?.let { it to ilvl } ?: styles.listFor(pStyle)
                val type = when {
                    heading > 0 -> HEADINGS[heading - 1]
                    styles.isCode(pStyle) -> NoteDocBlockType.CODE
                    styles.isQuote(pStyle) -> NoteDocBlockType.QUOTE
                    list != null -> if (numbering[list] == false) NoteDocBlockType.NUMBERED else NoteDocBlockType.BULLET
                    else -> NoteDocBlockType.PARAGRAPH
                }
                val shifted = runs.mapNotNull { r ->
                    val a = (r.start - lead).coerceIn(0, value.length)
                    val b = (r.end - lead).coerceIn(0, value.length)
                    if (b <= a) null
                    else if (type.headingLevel > 0) r.copy(start = a, end = b, fontSizeSp = null)
                    else if (type == NoteDocBlockType.CODE) null
                    else r.copy(start = a, end = b)
                }.filterNot { it.isEmptyStyle }
                add(NoteDocBlock(
                    id = id(), type = type, text = value,
                    runs = NoteRuns.normalize(shifted, value.length),
                    align = align,
                    indent = if (list != null && type.headingLevel == 0) list.second.coerceIn(0, MAX_INDENT) else 0,
                    mathMode = if (type == NoteDocBlockType.CODE) false else null,
                ))
            }
            text.setLength(0)
            runs.clear()
        }

        private fun startTable() {
            if (text.isNotEmpty()) flush()
            rows = mutableListOf(); headerRow = false; afterTable.clear(); cell = null
        }

        private fun endTable() {
            val kept = rows.filter { row -> row.any { it.isNotBlank() } }
            val cols = kept.maxOfOrNull { it.size } ?: 0
            if (kept.isNotEmpty() && cols > 0) {
                if (kept.size <= NoteDocTable.MAX_ROWS && cols <= NoteDocTable.MAX_COLS) {
                    val cells = kept.flatMap { row -> row + List(cols - row.size) { "" } }
                    tables++
                    add(NoteDocBlock(id = id(), type = NoteDocBlockType.TABLE,
                        table = NoteDocTable(kept.size, cols, cells,
                            headerRow = headerRow && rows.firstOrNull()?.any { it.isNotBlank() } == true)))
                } else kept.forEach { row ->
                    // Larger than a note table holds: each row stays readable as one line.
                    add(NoteDocBlock(id = id(), text = row.filter { it.isNotBlank() }.joinToString("  |  ")))
                }
            }
            afterTable.forEach(::add)
            afterTable.clear()
            rows = mutableListOf()
            cell = null
        }

        private fun add(block: NoteDocBlock) {
            if (blocks.size >= MAX_BLOCKS) { truncated = true; return }
            blocks += block
        }

        private fun id() = "import-${nextId++}"
    }

    private val SKIPPED = setOf("del", "moveFrom", "delText", "txbxContent", "footnoteReference",
        "endnoteReference", "commentReference", "annotationRef")

    private val HEADINGS = listOf(NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2,
        NoteDocBlockType.HEADING_3, NoteDocBlockType.HEADING_4, NoteDocBlockType.HEADING_5)

    /** Word's named highlight colours. */
    private val HIGHLIGHTS = mapOf(
        "yellow" to 0xFFFFF59DL, "green" to 0xFFC8E6C9L, "cyan" to 0xFFB2EBF2L, "magenta" to 0xFFF8BBD0L,
        "blue" to 0xFFBBDEFBL, "red" to 0xFFFFCDD2L, "darkBlue" to 0xFF9FA8DAL, "darkCyan" to 0xFF80CBC4L,
        "darkGreen" to 0xFFA5D6A7L, "darkMagenta" to 0xFFCE93D8L, "darkRed" to 0xFFEF9A9AL,
        "darkYellow" to 0xFFFFE082L, "lightGray" to 0xFFEEEEEEL, "darkGray" to 0xFFBDBDBDL,
    )

    private fun hexColor(value: String?): Long? {
        if (value == null || value.length != 6) return null
        return value.toLongOrNull(16)?.let { it or 0xFF000000L }
    }

    private fun safeLink(url: String): String? {
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore(':', "").lowercase()
        return trimmed.takeIf { scheme in setOf("http", "https", "mailto") && it.length <= 2_000 }
    }

    private fun fieldHyperlink(instr: CharSequence): String? =
        Regex("^\\s*HYPERLINK\\s+\"([^\"]+)\"").find(instr)?.groupValues?.get(1)?.let(::safeLink)

    // ------------------------------------------------------------------ XML helpers

    private fun on(p: XmlPullParser): Boolean = attr(p, "val")?.lowercase() !in setOf("0", "false", "off", "none")

    /** An attribute by its local name, whatever namespace prefix the file used. */
    private fun attr(p: XmlPullParser, local: String): String? {
        for (i in 0 until p.attributeCount) if (p.getAttributeName(i) == local) return p.getAttributeValue(i)
        return null
    }

    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) when (p.next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }

    private fun parse(xml: ByteArray, onEvent: (XmlPullParser) -> Unit) {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        parser.setInput(ByteArrayInputStream(xml), null)
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) onEvent(parser)
        } catch (e: Refused) {
            throw e
        } catch (e: Exception) {
            throw Refused("Part of this document couldn't be read, so nothing was imported.")
        }
    }

    private fun ZipFile.bytes(entry: java.util.zip.ZipEntry, limit: Long): ByteArray =
        getInputStream(entry).use { it.readBounded(limit) }

    private fun InputStream.readBounded(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw Refused("Part of this document is too large to import safely.")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

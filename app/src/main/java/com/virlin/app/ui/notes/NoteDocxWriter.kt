package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocAudio
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.NoteListStyles
import com.virlin.app.domain.notedoc.NoteMath
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A note as an editable Word document (.docx, Office Open XML) that Word and Google Docs open
 * natively: real headings, numbered and bulleted lists, tables, links, fonts, colours and
 * highlights, and pictures embedded at document resolution. Nothing is drawn as an image of text.
 */
internal object NoteDocxWriter {
    private const val BODY_FONT = "Roboto"
    private const val MONO_FONT = "Roboto Mono"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL_IMAGE = "$R/image"
    private const val REL_LINK = "$R/hyperlink"

    private class Rel(val id: String, val type: String, val target: String, val external: Boolean)

    private class Doc(val contentWidthPt: Float, val contentHeightPt: Float, val colors: NotePrintColors,
                      val image: (String, Float) -> NoteExportImages.Encoded?) {
        val rels = ArrayList<Rel>()
        val media = LinkedHashMap<String, ByteArray>()
        val abstractNums = ArrayList<String>()
        var drawings = 0

        fun rel(type: String, target: String, external: Boolean): String =
            "rId${rels.size + 10}".also { rels += Rel(it, type, target, external) }
    }

    fun write(out: OutputStream, title: String?, blocks: List<NoteDocBlock>, page: NotePrintSettings,
              colors: NotePrintColors, image: (String, Float) -> NoteExportImages.Encoded?) {
        val widthPt = page.pageWidthPt
        val heightPt = page.pageHeightPt
        val marginPt = page.margins.points
        val doc = Doc(widthPt - 2 * marginPt, heightPt - 2 * marginPt, colors, image)
        val body = StringBuilder()
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { body.append(p("Title", content = runs(it, emptyList()))) }

        val numIds = listNumbers(blocks, doc)
        blocks.forEachIndexed { i, b -> body.append(block(b, numIds[i], doc)) }

        val orient = if (page.landscape) " w:orient=\"landscape\"" else ""
        val m = (marginPt * 20).toInt()
        val numbers = page.pageNumbers != NotePageNumbers.OFF
        val header = page.pageNumbers == NotePageNumbers.TOP
        val partName = if (header) "header1.xml" else "footer1.xml"
        body.append("<w:sectPr>")
        if (numbers) body.append(if (header) "<w:headerReference w:type=\"default\" r:id=\"rId3\"/>"
            else "<w:footerReference w:type=\"default\" r:id=\"rId3\"/>")
        body.append("<w:pgSz w:w=\"${(widthPt * 20).toInt()}\" w:h=\"${(heightPt * 20).toInt()}\"$orient/>")
            .append("<w:pgMar w:top=\"$m\" w:right=\"$m\" w:bottom=\"$m\" w:left=\"$m\" w:header=\"${m / 2}\" w:footer=\"${m / 2}\" w:gutter=\"0\"/></w:sectPr>")

        ZipOutputStream(out).use { zip ->
            fun put(name: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
            fun put(name: String, xml: String) = put(name, (XML_HEAD + xml).toByteArray(Charsets.UTF_8))
            put("[Content_Types].xml", contentTypes(if (numbers) partName else null))
            if (numbers) put("word/$partName", pageNumberPart(header, page.numberAlign, colors))
            put("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$R/officeDocument\" Target=\"word/document.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
                "</Relationships>")
            put("docProps/core.xml", "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>${esc(title?.trim().orEmpty())}</dc:title>" +
                "<dc:creator>Virlin</dc:creator></cp:coreProperties>")
            put("word/document.xml", "<w:document xmlns:w=\"$W\" xmlns:r=\"$R\" " +
                "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
                "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
                "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>$body</w:body></w:document>")
            put("word/styles.xml", styles(colors))
            put("word/numbering.xml", "<w:numbering xmlns:w=\"$W\">" + doc.abstractNums.joinToString("") +
                doc.abstractNums.indices.joinToString("") { "<w:num w:numId=\"${it + 1}\"><w:abstractNumId w:val=\"$it\"/></w:num>" } +
                "</w:numbering>")
            put("word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$R/styles\" Target=\"styles.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"$R/numbering\" Target=\"numbering.xml\"/>" +
                (if (numbers) "<Relationship Id=\"rId3\" Type=\"$R/${if (header) "header" else "footer"}\" Target=\"$partName\"/>" else "") +
                doc.rels.joinToString("") { r ->
                    "<Relationship Id=\"${r.id}\" Type=\"${r.type}\" Target=\"${esc(r.target)}\"" +
                        (if (r.external) " TargetMode=\"External\"" else "") + "/>"
                } + "</Relationships>")
            doc.media.forEach { (name, bytes) -> put("word/media/$name", bytes) }
        }
    }

    // ---- Blocks ------------------------------------------------------------------------------

    private fun block(b: NoteDocBlock, numId: Int?, doc: Doc): String {
        val jc = when (b.align) { NoteDocAlign.CENTER -> "center"; NoteDocAlign.RIGHT -> "right"; else -> null }
        val line = if (b.lineSpacing != 1f && b.lineSpacing > 0f) (288 * b.lineSpacing).toInt() else null
        val indent = b.indent.coerceIn(0, 8)
        val ind = if (indent > 0) indent * 360 else null
        return when (b.type) {
            NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2, NoteDocBlockType.HEADING_3,
            NoteDocBlockType.HEADING_4, NoteDocBlockType.HEADING_5 ->
                p("Heading${b.type.headingLevel}", line = line, ind = ind, jc = jc, content = runs(b.text, b.runs, doc = doc))
            NoteDocBlockType.BULLET, NoteDocBlockType.NUMBERED ->
                p("ListParagraph", numId = numId, ilvl = indent, line = line, jc = jc, content = runs(b.text, b.runs, doc = doc))
            NoteDocBlockType.CHECKLIST ->
                p(null, line = line, ind = ind, jc = jc, content = runs(if (b.checked) "☑ " else "☐ ", emptyList()) +
                    runs(b.text, b.runs, doc = doc, base = Base(strike = b.checked, color = if (b.checked) doc.colors.muted else null)))
            NoteDocBlockType.QUOTE -> p("Quote", line = line, jc = jc, content = runs(b.text, b.runs, doc = doc))
            NoteDocBlockType.PROMPT -> buildString {
                b.promptTitle?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    append(p("PromptTitle", keepNext = true, content = runs(it, emptyList())))
                }
                append(p("Prompt", line = line, jc = jc, content = runs(b.text, b.runs, doc = doc)))
            }
            NoteDocBlockType.CODE -> if (NoteMath.isEquation(b)) {
                p("Equation", jc = "center", content = runs(b.text, emptyList()))
            } else {
                val highlight = CodeHighlighter.highlight(b.text)
                val tokens = highlight.tokens.map { t ->
                    NoteDocRun(t.start, t.end, if (t.kind == CodeTokenKind.COMMENT) setOf(NoteDocMark.ITALIC) else emptySet(),
                        color = NotePrintRenderer.SYNTAX_COLORS[t.kind.ordinal].toLong() and 0xFFFFFFFFL)
                }
                val label = highlight.language.label.takeIf { highlight.language != CodeLanguage.PLAIN && highlight.language != CodeLanguage.C_LIKE }
                (label?.let { p("CodeLabel", keepNext = true, content = runs(it, emptyList())) } ?: "") +
                    p("Code", content = runs(b.text, tokens))
            }
            NoteDocBlockType.DIVIDER -> "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" " +
                "w:color=\"${hex(doc.colors.border.toLong() or 0xFF000000L)}\"/></w:pBdr></w:pPr></w:p>"
            NoteDocBlockType.TABLE -> b.table?.let { table(it, doc) + "<w:p/>" } ?: ""
            NoteDocBlockType.IMAGE -> picture(b, jc, doc)
            NoteDocBlockType.VIDEO -> b.video?.let { v ->
                val title = v.title?.takeIf { it.isNotBlank() } ?: "YouTube video"
                p(null, jc = jc, content = runs("▶ $title", listOf(NoteDocRun(0, title.length + 2, setOf(NoteDocMark.BOLD), linkUrl = v.watchUrl)), doc = doc) +
                    (v.channel?.takeIf { it.isNotBlank() }?.let { runs("  ·  $it", emptyList(), Base(color = doc.colors.muted)) } ?: ""))
            } ?: ""
            NoteDocBlockType.AUDIO -> p(null, jc = jc, content = runs(NoteDocAudio.plainText(b.audio).removeSurrounding("[", "]"),
                listOf(NoteDocRun(0, Int.MAX_VALUE, setOf(NoteDocMark.ITALIC))), Base(color = doc.colors.muted)))
            NoteDocBlockType.TOGGLE -> p(null, line = line, ind = ind, jc = jc,
                content = runs("▸ ", emptyList(), Base(color = doc.colors.muted)) + runs(b.text, b.runs, doc = doc))
            NoteDocBlockType.PARAGRAPH -> p(null, line = line, ind = ind, jc = jc, content = runs(b.text, b.runs, doc = doc))
        }
    }

    private fun p(style: String?, numId: Int? = null, ilvl: Int = 0, keepNext: Boolean = false, line: Int? = null,
                  ind: Int? = null, jc: String? = null, content: String): String {
        val ppr = StringBuilder()
        style?.let { ppr.append("<w:pStyle w:val=\"$it\"/>") }
        if (keepNext) ppr.append("<w:keepNext/>")
        numId?.let { ppr.append("<w:numPr><w:ilvl w:val=\"$ilvl\"/><w:numId w:val=\"$it\"/></w:numPr>") }
        line?.let { ppr.append("<w:spacing w:line=\"$it\" w:lineRule=\"auto\"/>") }
        ind?.let { ppr.append("<w:ind w:left=\"$it\"/>") }
        jc?.let { ppr.append("<w:jc w:val=\"$it\"/>") }
        return "<w:p>" + (if (ppr.isNotEmpty()) "<w:pPr>$ppr</w:pPr>" else "") + content + "</w:p>"
    }

    /** One numbering definition per run of list items of the same kind and style, so each list counts from 1. */
    private fun listNumbers(blocks: List<NoteDocBlock>, doc: Doc): List<Int?> {
        var lastKey: String? = null
        var current = 0
        return blocks.map { b ->
            if (b.type != NoteDocBlockType.BULLET && b.type != NoteDocBlockType.NUMBERED) { lastKey = null; return@map null }
            val style = NoteListStyles.effectiveKey(b.type, b.listStyle)
            val key = "${b.type}:$style"
            if (key != lastKey) {
                doc.abstractNums += abstractNum(doc.abstractNums.size, b.type, style)
                current = doc.abstractNums.size
                lastKey = key
            }
            current
        }
    }

    private fun abstractNum(id: Int, type: NoteDocBlockType, style: String?): String {
        val bullet = type == NoteDocBlockType.BULLET
        val format = when (style) {
            "decimal-zero" -> "decimalZero"
            "lower-alpha", "lower-alpha-paren" -> "lowerLetter"
            "upper-alpha" -> "upperLetter"
            "lower-roman" -> "lowerRoman"
            "upper-roman" -> "upperRoman"
            else -> "decimal"
        }
        val suffix = if (style == "decimal-paren" || style == "lower-alpha-paren") ")" else "."
        val levels = (0..8).joinToString("") { lvl ->
            val text = if (bullet) NoteListStyles.marker(type, style) else "%${lvl + 1}$suffix"
            "<w:lvl w:ilvl=\"$lvl\"><w:start w:val=\"1\"/><w:numFmt w:val=\"${if (bullet) "bullet" else format}\"/>" +
                "<w:lvlText w:val=\"${esc(text)}\"/><w:lvlJc w:val=\"left\"/>" +
                "<w:pPr><w:ind w:left=\"${360 * (lvl + 2)}\" w:hanging=\"360\"/></w:pPr></w:lvl>"
        }
        return "<w:abstractNum w:abstractNumId=\"$id\"><w:multiLevelType w:val=\"hybridMultilevel\"/>$levels</w:abstractNum>"
    }

    private fun table(t: NoteDocTable, doc: Doc): String {
        val content = doc.contentWidthPt
        val explicit = (0 until t.cols).map { c -> t.widthAt(c)?.let { it * NotePrintRenderer.SP } }
        val autoCount = explicit.count { it == null }
        val auto = if (autoCount == 0) 0f else maxOf((content - explicit.sumOf { (it ?: 0f).toDouble() }.toFloat()) / autoCount, 48f)
        var widths = explicit.map { it ?: auto }
        val total = widths.sum()
        if (total > content) widths = widths.map { it * content / total }
        val twips = widths.map { (it * 20).toInt().coerceAtLeast(200) }
        val border = hex(doc.colors.border.toLong() or 0xFF000000L)
        val line = if (t.gridLines) "w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"$border\"" else "w:val=\"nil\""
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"${twips.sum()}\" w:type=\"dxa\"/><w:tblBorders>")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { sb.append("<w:$it $line/>") }
        sb.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:top w:w=\"60\" w:type=\"dxa\"/>")
            .append("<w:left w:w=\"100\" w:type=\"dxa\"/><w:bottom w:w=\"60\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/>")
            .append("</w:tblCellMar></w:tblPr><w:tblGrid>")
        twips.forEach { sb.append("<w:gridCol w:w=\"$it\"/>") }
        sb.append("</w:tblGrid>")
        for (r in 0 until t.rows) {
            sb.append("<w:tr>")
            if (r == 0 && t.headerRow) sb.append("<w:trPr><w:cantSplit/><w:tblHeader/></w:trPr>") else sb.append("<w:trPr><w:cantSplit/></w:trPr>")
            for (c in 0 until t.cols) {
                val header = t.isHeader(r, c)
                val fill = t.fillAt(r, c) ?: if (header) doc.colors.wash.toLong() else null
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"${twips[c]}\" w:type=\"dxa\"/>")
                fill?.let { sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
                sb.append("</w:tcPr>")
                sb.append(p("TableText", content = runs(t.cell(r, c), t.runsAt(r, c), Base(bold = header), doc)))
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        return sb.append("</w:tbl>").toString()
    }

    private fun picture(b: NoteDocBlock, jc: String?, doc: Doc): String {
        val name = b.imagePath ?: return ""
        val image = doc.image(name, doc.contentWidthPt)
            ?: return p(null, jc = jc, content = runs("Image unavailable", emptyList(), Base(color = doc.colors.muted)))
        var w = doc.contentWidthPt
        var h = w * image.heightPx / image.widthPx
        val max = doc.contentHeightPt * .85f
        if (h > max) { h = max; w = h * image.widthPx / image.heightPx }
        val id = ++doc.drawings
        val file = "image$id.${image.extension}"
        doc.media[file] = image.bytes
        val rel = doc.rel(REL_IMAGE, "media/$file", external = false)
        val cx = (w * 12700).toLong()
        val cy = (h * 12700).toLong()
        val drawing = "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"Picture $id\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>" +
            "<pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"$file\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rel\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>" +
            "</wp:inline></w:drawing></w:r>"
        return p(null, jc = jc, content = drawing)
    }

    // ---- Runs --------------------------------------------------------------------------------

    private class Base(val bold: Boolean = false, val strike: Boolean = false, val color: Int? = null)

    private fun runs(text: String, runs: List<NoteDocRun>, base: Base = Base(), doc: Doc? = null): String {
        if (text.isEmpty()) return ""
        val out = StringBuilder()
        var at = 0
        runs.filter { it.end > it.start }.sortedBy { it.start }.forEach { r ->
            val s = r.start.coerceIn(0, text.length)
            val e = r.end.coerceIn(s, text.length)
            if (e <= s || s < at) return@forEach
            if (s > at) out.append(run(text.substring(at, s), null, base))
            val piece = run(text.substring(s, e), r, base)
            val url = r.linkUrl?.takeIf { it.isNotBlank() && doc != null }
            if (url != null) out.append("<w:hyperlink r:id=\"${doc!!.rel(REL_LINK, url, external = true)}\">$piece</w:hyperlink>")
            else out.append(piece)
            at = e
        }
        if (at < text.length) out.append(run(text.substring(at), null, base))
        return out.toString()
    }

    private fun run(text: String, r: NoteDocRun?, base: Base): String {
        val marks = r?.marks.orEmpty()
        val rpr = StringBuilder()
        if (r?.linkUrl?.isNotBlank() == true) rpr.append("<w:rStyle w:val=\"Hyperlink\"/>")
        val font = if (NoteDocMark.CODE in marks) MONO_FONT else fontName(r?.fontFamily)
        font?.let { val f = esc(it); rpr.append("<w:rFonts w:ascii=\"$f\" w:hAnsi=\"$f\" w:cs=\"$f\"/>") }
        if (base.bold || NoteDocMark.BOLD in marks) rpr.append("<w:b/>")
        if (NoteDocMark.ITALIC in marks) rpr.append("<w:i/>")
        if (base.strike || NoteDocMark.STRIKE in marks) rpr.append("<w:strike/>")
        (r?.color ?: base.color?.toLong())?.let { rpr.append("<w:color w:val=\"${hex(it)}\"/>") }
        r?.fontSizeSp?.let { val hp = Math.round(it * NotePrintRenderer.SP * 2); rpr.append("<w:sz w:val=\"$hp\"/><w:szCs w:val=\"$hp\"/>") }
        if (NoteDocMark.UNDERLINE in marks) rpr.append("<w:u w:val=\"single\"/>")
        val shade = r?.highlight ?: if (NoteDocMark.CODE in marks) 0xFFEEF1EFL else null
        shade?.let { rpr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
        val body = StringBuilder()
        val pending = StringBuilder()
        fun flush() {
            if (pending.isNotEmpty()) body.append("<w:t xml:space=\"preserve\">").append(esc(pending.toString())).append("</w:t>")
            pending.clear()
        }
        text.forEach { ch ->
            when (ch) {
                '\n' -> { flush(); body.append("<w:br/>") }
                '\t' -> { flush(); body.append("<w:tab/>") }
                '\r' -> Unit
                else -> pending.append(ch)
            }
        }
        flush()
        return "<w:r>" + (if (rpr.isNotEmpty()) "<w:rPr>$rpr</w:rPr>" else "") + body + "</w:r>"
    }

    private fun fontName(family: String?): String? = when (family) {
        null, "", "Inter" -> null
        "Serif" -> "Georgia"
        "Mono" -> MONO_FONT
        else -> family
    }

    // ---- Package parts -----------------------------------------------------------------------

    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    private fun contentTypes(pageNumberPart: String?) = "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
        "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
        "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
        (pageNumberPart?.let { part ->
            val kind = if (part.startsWith("header")) "header" else "footer"
            "<Override PartName=\"/word/$part\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.$kind+xml\"/>"
        } ?: "") +
        "</Types>"

    /** "Page 2 of 5" with live page fields, so the numbers stay right as the document is edited. */
    private fun pageNumberPart(header: Boolean, align: NoteNumberAlign, c: NotePrintColors): String {
        val tag = if (header) "w:hdr" else "w:ftr"
        val jc = when (align) { NoteNumberAlign.LEFT -> "left"; NoteNumberAlign.CENTER -> "center"; NoteNumberAlign.RIGHT -> "right" }
        val rpr = "<w:rPr><w:color w:val=\"${hex(c.muted.toLong())}\"/><w:sz w:val=\"17\"/><w:szCs w:val=\"17\"/></w:rPr>"
        fun text(s: String) = "<w:r>$rpr<w:t xml:space=\"preserve\">$s</w:t></w:r>"
        fun field(code: String) = "<w:fldSimple w:instr=\" $code \"><w:r>$rpr<w:t>1</w:t></w:r></w:fldSimple>"
        return "<$tag xmlns:w=\"$W\" xmlns:r=\"$R\"><w:p><w:pPr><w:spacing w:after=\"0\"/><w:jc w:val=\"$jc\"/></w:pPr>" +
            text("Page ") + field("PAGE") + text(" of ") + field("NUMPAGES") + "</w:p></$tag>"
    }

    private fun styles(c: NotePrintColors): String {
        val ink = hex(c.ink.toLong())
        val accent = hex(c.accent.toLong())
        val wash = hex(c.wash.toLong())
        val muted = hex(c.muted.toLong())
        fun half(sp: Int) = Math.round(sp * NotePrintRenderer.SP * 2)
        fun heading(level: Int, before: Int, after: Int) =
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\"><w:name w:val=\"heading $level\"/>" +
                "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:uiPriority w:val=\"9\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before=\"$before\" w:after=\"$after\"/><w:outlineLvl w:val=\"${level - 1}\"/></w:pPr>" +
                "<w:rPr><w:b/><w:sz w:val=\"${half(NotesViewModel.defaultSizeFor(headingType(level)))}\"/>" +
                "<w:szCs w:val=\"${half(NotesViewModel.defaultSizeFor(headingType(level)))}\"/></w:rPr></w:style>"
        val body = half(NotesViewModel.defaultSizeFor(NoteDocBlockType.PARAGRAPH))
        val small = half(TABLE_CELL_TEXT_SP)
        return "<w:styles xmlns:w=\"$W\"><w:docDefaults><w:rPrDefault><w:rPr>" +
            "<w:rFonts w:ascii=\"$BODY_FONT\" w:hAnsi=\"$BODY_FONT\" w:eastAsia=\"$BODY_FONT\" w:cs=\"$BODY_FONT\"/>" +
            "<w:color w:val=\"$ink\"/><w:sz w:val=\"$body\"/><w:szCs w:val=\"$body\"/><w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"140\" w:line=\"288\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:spacing w:after=\"320\"/></w:pPr>" +
            "<w:rPr><w:b/><w:sz w:val=\"${half(27)}\"/><w:szCs w:val=\"${half(27)}\"/></w:rPr></w:style>" +
            heading(1, 360, 120) + heading(2, 300, 100) + heading(3, 260, 80) + heading(4, 220, 60) + heading(5, 200, 60) +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:qFormat/><w:pPr><w:spacing w:after=\"60\"/><w:contextualSpacing/></w:pPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"10\" w:color=\"$accent\"/></w:pBdr>" +
            "<w:ind w:left=\"260\"/></w:pPr><w:rPr><w:color w:val=\"$muted\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"PromptTitle\"><w:name w:val=\"Prompt Title\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$wash\"/><w:spacing w:after=\"0\"/><w:ind w:left=\"160\" w:right=\"160\"/></w:pPr>" +
            "<w:rPr><w:b/><w:color w:val=\"$accent\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Prompt\"><w:name w:val=\"Prompt\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$wash\"/><w:ind w:left=\"160\" w:right=\"160\"/></w:pPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"CodeLabel\"><w:name w:val=\"Code Label\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F4F6F5\"/><w:spacing w:after=\"0\"/><w:ind w:left=\"160\" w:right=\"160\"/></w:pPr>" +
            "<w:rPr><w:b/><w:color w:val=\"$muted\"/><w:sz w:val=\"${half(11)}\"/><w:szCs w:val=\"${half(11)}\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"Code\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F4F6F5\"/><w:spacing w:line=\"264\" w:lineRule=\"auto\"/>" +
            "<w:ind w:left=\"160\" w:right=\"160\"/></w:pPr>" +
            "<w:rPr><w:rFonts w:ascii=\"$MONO_FONT\" w:hAnsi=\"$MONO_FONT\" w:cs=\"$MONO_FONT\"/>" +
            "<w:sz w:val=\"${half(13)}\"/><w:szCs w:val=\"${half(13)}\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Equation\"><w:name w:val=\"Equation\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:rPr><w:rFonts w:ascii=\"Cambria Math\" w:hAnsi=\"Cambria Math\"/><w:i/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"TableText\"><w:name w:val=\"Table Text\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:pPr><w:spacing w:after=\"0\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr>" +
            "<w:rPr><w:sz w:val=\"$small\"/><w:szCs w:val=\"$small\"/></w:rPr></w:style>" +
            "<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/>" +
            "<w:rPr><w:color w:val=\"1155CC\"/><w:u w:val=\"single\"/></w:rPr></w:style>" +
            "</w:styles>"
    }

    private fun headingType(level: Int) = when (level) {
        1 -> NoteDocBlockType.HEADING_1
        2 -> NoteDocBlockType.HEADING_2
        3 -> NoteDocBlockType.HEADING_3
        4 -> NoteDocBlockType.HEADING_4
        else -> NoteDocBlockType.HEADING_5
    }

    private fun hex(argb: Long) = "%06X".format(java.util.Locale.US, argb and 0xFFFFFF)

    private val INVALID_XML = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]")

    fun esc(s: String): String = INVALID_XML.replace(s, "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

package com.virlin.app.domain.attachment

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Best-effort read-only OOXML extractors (DOCX / XLSX / PPTX) using Zip + XmlPullParser.
 * No Apache POI / no macros / no script execution. Layout is not Word-perfect.
 */
object OoxmlReaders {

    data class DocView(val paragraphs: List<String>, val truncated: Boolean)
    data class SheetView(val name: String, val rows: List<List<String>>)
    data class WorkbookView(val sheets: List<SheetView>, val truncated: Boolean)
    data class SlideView(val index: Int, val lines: List<String>)
    data class DeckView(val slides: List<SlideView>, val truncated: Boolean)

    fun readDocx(input: InputStream, maxParagraphs: Int = 800): DocView {
        val paragraphs = mutableListOf<String>()
        var truncated = false
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val texts = extractTextNodes(zip, setOf("t"))
                    var buf = StringBuilder()
                    // document.xml text nodes are w:t; approximate paragraphs by breaks in sequence
                    texts.forEach { t ->
                        if (paragraphs.size >= maxParagraphs) {
                            truncated = true
                            return@forEach
                        }
                        if (t.isBlank()) {
                            if (buf.isNotEmpty()) {
                                paragraphs += buf.toString(); buf = StringBuilder()
                            }
                        } else {
                            if (buf.isNotEmpty()) buf.append(' ')
                            buf.append(t)
                        }
                    }
                    if (buf.isNotEmpty() && paragraphs.size < maxParagraphs) paragraphs += buf.toString()
                    else if (buf.isNotEmpty()) truncated = true
                    break
                }
                entry = zip.nextEntry
            }
        }
        return DocView(paragraphs.ifEmpty { listOf("(No readable text found in DOCX)") }, truncated)
    }

    fun readXlsx(input: InputStream, maxRowsPerSheet: Int = 200, maxSheets: Int = 8): WorkbookView {
        val bytes = input.readBytes() // workbook needs random access to multiple entries; capped below
        if (bytes.size > 12 * 1024 * 1024) {
            return WorkbookView(listOf(SheetView("Too large", listOf(listOf("Spreadsheet exceeds 12 MB in-memory parse limit")))), true)
        }
        val shared = mutableListOf<String>()
        val sheetEntries = mutableListOf<Pair<String, String>>() // name to zip path
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                when {
                    e.name == "xl/sharedStrings.xml" -> shared += extractTextNodes(zip, setOf("t"))
                    e.name.startsWith("xl/worksheets/sheet") && e.name.endsWith(".xml") -> {
                        val path = e.name
                        sheetEntries += path to path
                    }
                }
                e = zip.nextEntry
            }
        }
        // Re-read sheet bodies
        val sheets = mutableListOf<SheetView>()
        var truncated = false
        sheetEntries.take(maxSheets).forEachIndexed { idx, (path, _) ->
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name == path) {
                        val rows = parseSheetRows(zip, shared, maxRowsPerSheet)
                        if (rows.second) truncated = true
                        sheets += SheetView("Sheet ${idx + 1}", rows.first)
                        break
                    }
                    e = zip.nextEntry
                }
            }
        }
        if (sheetEntries.size > maxSheets) truncated = true
        return WorkbookView(sheets.ifEmpty { listOf(SheetView("Sheet", listOf(listOf("(Empty)")))) }, truncated)
    }

    fun readPptx(input: InputStream, maxSlides: Int = 40): DeckView {
        val slides = mutableListOf<SlideView>()
        var truncated = false
        val slideXml = mutableListOf<ByteArray>()
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name.startsWith("ppt/slides/slide") && e.name.endsWith(".xml")) {
                    if (slideXml.size >= maxSlides) {
                        truncated = true
                    } else {
                        slideXml += zip.readBytes()
                    }
                }
                e = zip.nextEntry
            }
        }
        slideXml.forEachIndexed { i, bytes ->
            val lines = extractTextNodes(bytes.inputStream(), setOf("t"))
                .map { it.trim() }.filter { it.isNotEmpty() }
            slides += SlideView(i + 1, lines.ifEmpty { listOf("(No text on slide)") })
        }
        return DeckView(slides.ifEmpty { listOf(SlideView(1, listOf("(No slides found)"))) }, truncated)
    }

    private fun parseSheetRows(input: InputStream, shared: List<String>, maxRows: Int): Pair<List<List<String>>, Boolean> {
        val rows = mutableListOf<List<String>>()
        var truncated = false
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")
        var event = parser.eventType
        var currentRow = mutableListOf<String>()
        var inV = false
        var cellType: String? = null
        var vText = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = mutableListOf()
                    "c" -> cellType = parser.getAttributeValue(null, "t")
                    "v" -> { inV = true; vText = StringBuilder() }
                }
                XmlPullParser.TEXT -> if (inV) vText.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> {
                        inV = false
                        val raw = vText.toString()
                        val value = if (cellType == "s") shared.getOrNull(raw.toIntOrNull() ?: -1) ?: raw else raw
                        currentRow += value
                        cellType = null
                    }
                    "row" -> {
                        if (rows.size >= maxRows) truncated = true
                        else rows += currentRow.toList()
                    }
                }
            }
            event = parser.next()
        }
        return rows to truncated
    }

    private fun extractTextNodes(input: InputStream, tags: Set<String>): List<String> {
        val out = mutableListOf<String>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")
        var event = parser.eventType
        var capture = false
        val buf = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name in tags) {
                    capture = true; buf.clear()
                }
                XmlPullParser.TEXT -> if (capture) buf.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name in tags && capture) {
                    out += buf.toString(); capture = false
                }
            }
            event = parser.next()
        }
        return out
    }
}

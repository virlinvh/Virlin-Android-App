package com.virlin.app.domain.attachment

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

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

    fun readDocx(input: InputStream, maxParagraphs: Int = 800): DocView = try {
        parseDocx(input, maxParagraphs.coerceIn(1, 800))
    } catch (e: ImportLimitException) { DocView(listOf(e.message.orEmpty()), true) }
    catch (e: java.io.InterruptedIOException) { throw e }
    catch (_: java.io.IOException) { DocView(listOf("This document could not be read."), true) }
    catch (_: org.xmlpull.v1.XmlPullParserException) { DocView(listOf("This document is not valid."), true) }

    private fun parseDocx(input: InputStream, maxParagraphs: Int): DocView {
        val paragraphs = mutableListOf<String>()
        var truncated = false
        BudgetZipStream(input).use { zip ->
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

    fun readXlsx(input: InputStream, maxRowsPerSheet: Int = 200, maxSheets: Int = 8): WorkbookView = try {
        parseXlsx(input, maxRowsPerSheet.coerceIn(1, 200), maxSheets.coerceIn(1, 8))
    } catch (e: ImportLimitException) { WorkbookView(listOf(SheetView("Preview limit", listOf(listOf(e.message.orEmpty())))), true) }
    catch (e: java.io.InterruptedIOException) { throw e }
    catch (_: java.io.IOException) { WorkbookView(listOf(SheetView("Unreadable", listOf(listOf("This spreadsheet could not be read.")))), true) }
    catch (_: org.xmlpull.v1.XmlPullParserException) { WorkbookView(listOf(SheetView("Unreadable", listOf(listOf("This spreadsheet is not valid.")))), true) }

    private fun parseXlsx(input: InputStream, maxRowsPerSheet: Int, maxSheets: Int): WorkbookView {
        val bytes = readBoundedImport(input)
        val shared = mutableListOf<String>()
        val sheetEntries = mutableListOf<Pair<String, String>>() // name to zip path
        BudgetZipStream(bytes.inputStream()).use { zip ->
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
            BudgetZipStream(bytes.inputStream()).use { zip ->
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

    fun readPptx(input: InputStream, maxSlides: Int = 40): DeckView = try {
        parsePptx(input, maxSlides.coerceIn(1, 40))
    } catch (e: ImportLimitException) { DeckView(listOf(SlideView(1, listOf(e.message.orEmpty()))), true) }
    catch (e: java.io.InterruptedIOException) { throw e }
    catch (_: java.io.IOException) { DeckView(listOf(SlideView(1, listOf("This presentation could not be read."))), true) }
    catch (_: org.xmlpull.v1.XmlPullParserException) { DeckView(listOf(SlideView(1, listOf("This presentation is not valid."))), true) }

    private fun parsePptx(input: InputStream, maxSlides: Int): DeckView {
        val slides = mutableListOf<SlideView>()
        var truncated = false
        val slideXml = mutableListOf<ByteArray>()
        BudgetZipStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name.startsWith("ppt/slides/slide") && e.name.endsWith(".xml")) {
                    if (slideXml.size >= maxSlides) {
                        truncated = true
                    } else {
                        slideXml += readBoundedImport(zip, 4L * 1024 * 1024)
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
                XmlPullParser.TEXT -> if (inV) {
                    if (vText.length + parser.text.length > 32_768) throw ImportLimitException()
                    vText.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> {
                        inV = false
                        val raw = vText.toString()
                        val value = if (cellType == "s") shared.getOrNull(raw.toIntOrNull() ?: -1) ?: raw else raw
                        if (currentRow.size >= 1024) throw ImportLimitException()
                        currentRow += value
                        cellType = null
                    }
                    "row" -> {
                        if (rows.size >= maxRows) return rows to true
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
        var depth = 0
        val buf = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) depth++
            if (event == XmlPullParser.END_TAG) depth--
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name in tags) {
                    capture = true; buf.clear()
                }
                XmlPullParser.TEXT -> if (capture) {
                    if (buf.length + parser.text.length > 32_768) throw ImportLimitException()
                    buf.append(parser.text)
                }
                XmlPullParser.END_TAG -> if (parser.name in tags && capture) {
                    if (out.size >= 50_000) throw ImportLimitException()
                    out += buf.toString(); capture = false
                }
            }
            event = parser.next()
        }
        if (capture || depth != 0) throw org.xmlpull.v1.XmlPullParserException("Incomplete document")
        return out
    }
}

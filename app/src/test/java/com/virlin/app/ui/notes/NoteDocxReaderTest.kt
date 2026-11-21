package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class NoteDocxReaderTest {
    @get:Rule val folder = TemporaryFolder()

    private val stored = mutableListOf<ByteArray>()
    private fun store(bytes: ByteArray): String? =
        if (String(bytes) == "broken") null else "img-${stored.size}".also { stored += bytes }

    private fun docx(body: String, prolog: String = "", parts: Map<String, ByteArray> = emptyMap()): File {
        val file = folder.newFile("doc-${System.nanoTime()}.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("_rels/.rels", ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
                "Target=\"word/document.xml\"/></Relationships>").toByteArray())
            put("word/document.xml", ("$prolog<w:document xmlns:w=\"$W\" xmlns:r=\"$R\" xmlns:a=\"$A\"><w:body>$body</w:body></w:document>").toByteArray())
            put("word/_rels/document.xml.rels", RELS.toByteArray())
            put("word/styles.xml", STYLES.toByteArray())
            put("word/numbering.xml", NUMBERING.toByteArray())
            parts.forEach { (name, bytes) -> put(name, bytes) }
        }
        return file
    }

    private fun p(text: String, style: String? = null, extra: String = "") =
        "<w:p><w:pPr>${style?.let { "<w:pStyle w:val=\"$it\"/>" } ?: ""}$extra</w:pPr><w:r><w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"

    private fun list(text: String, numId: Int, level: Int) =
        p(text, extra = "<w:numPr><w:ilvl w:val=\"$level\"/><w:numId w:val=\"$numId\"/></w:numPr>")

    @Test fun headingsListsQuotesAndAlignmentBecomeNoteSections() {
        val result = NoteDocxReader.read(docx(
            p("Field guide", "Title") + p("Plants", "Heading1") + p("Leaves", "Heading2") +
                p("Veins", "Outlined") + p("Plain body text") + p("   ") +
                list("First bullet", 1, 0) + list("Nested bullet", 1, 1) + list("Step one", 2, 0) +
                p("Wise words", "Quote") + p("Centred", extra = "<w:jc w:val=\"center\"/>"),
        ), ::store)

        val shape = result.blocks.map { it.type to it.text }
        assertEquals(listOf(
            NoteDocBlockType.HEADING_1 to "Field guide",
            NoteDocBlockType.HEADING_1 to "Plants",
            NoteDocBlockType.HEADING_2 to "Leaves",
            NoteDocBlockType.HEADING_3 to "Veins",
            NoteDocBlockType.PARAGRAPH to "Plain body text",
            NoteDocBlockType.BULLET to "First bullet",
            NoteDocBlockType.BULLET to "Nested bullet",
            NoteDocBlockType.NUMBERED to "Step one",
            NoteDocBlockType.QUOTE to "Wise words",
            NoteDocBlockType.PARAGRAPH to "Centred",
        ), shape)
        assertEquals(1, result.blocks[6].indent)
        assertEquals(NoteDocAlign.CENTER, result.blocks.last().align)
        assertEquals(0, result.images)
        assertFalse(result.truncated)
    }

    @Test fun runStylesAndOnlyWebLinksAreKept() {
        val body = "<w:p>" +
            "<w:r><w:rPr><w:b/></w:rPr><w:t>Bold</w:t></w:r>" +
            "<w:r><w:t xml:space=\"preserve\"> and </w:t></w:r>" +
            "<w:r><w:rPr><w:i/><w:color w:val=\"C62828\"/></w:rPr><w:t>red</w:t></w:r>" +
            "<w:r><w:t xml:space=\"preserve\"> </w:t></w:r>" +
            "<w:hyperlink r:id=\"rLink\"><w:r><w:t>site</w:t></w:r></w:hyperlink>" +
            "<w:r><w:t xml:space=\"preserve\"> </w:t></w:r>" +
            "<w:hyperlink r:id=\"rBad\"><w:r><w:t>trap</w:t></w:r></w:hyperlink>" +
            "</w:p>"
        val block = NoteDocxReader.read(docx(body), ::store).blocks.single()

        assertEquals("Bold and red site trap", block.text)
        fun runAt(i: Int) = block.runs.first { i >= it.start && i < it.end }
        assertTrue(NoteDocMark.BOLD in runAt(0).marks)
        assertTrue(NoteDocMark.ITALIC in runAt(block.text.indexOf("red")).marks)
        assertEquals(0xFFC62828L, runAt(block.text.indexOf("red")).color)
        assertEquals("https://example.com/guide", runAt(block.text.indexOf("site")).linkUrl)
        assertTrue(block.runs.none { block.text.indexOf("trap") in it.start until it.end && it.linkUrl != null })
    }

    @Test fun tablesBecomeTableBlocksAndPicturesAreStoredInPlace() {
        val cell = { t: String -> "<w:tc><w:p><w:r><w:t>$t</w:t></w:r></w:p></w:tc>" }
        val picture = { id: String -> "<w:p><w:r><w:drawing><a:graphic><a:graphicData><a:blip r:embed=\"$id\"/></a:graphicData></a:graphic></w:drawing></w:r></w:p>" }
        val body = p("Before") + picture("rImg") + p("After") + picture("rBroken") +
            "<w:tbl><w:tr><w:trPr><w:tblHeader/></w:trPr>${cell("Name")}${cell("Role")}</w:tr>" +
            "<w:tr>${cell("Ada")}${cell("Engineer")}</w:tr></w:tbl>"
        val result = NoteDocxReader.read(docx(body, parts = mapOf(
            "word/media/image1.png" to "png-bytes".toByteArray(),
            "word/media/broken.png" to "broken".toByteArray(),
        )), ::store)

        assertEquals(listOf(NoteDocBlockType.PARAGRAPH, NoteDocBlockType.IMAGE, NoteDocBlockType.PARAGRAPH,
            NoteDocBlockType.TABLE), result.blocks.map { it.type })
        assertEquals("img-0", result.blocks[1].imagePath)
        assertEquals("png-bytes", String(stored.single()))
        assertEquals(1, result.images)
        assertEquals(1, result.skippedImages)
        val table = result.blocks.last().table!!
        assertEquals(2, table.rows)
        assertEquals(2, table.cols)
        assertEquals(listOf("Name", "Role", "Ada", "Engineer"), table.cells)
        assertTrue(table.headerRow)
        assertEquals(1, result.tables)
    }

    @Test fun deletedTrackedChangesAndHiddenTextAreLeftOut() {
        val body = "<w:p><w:r><w:t xml:space=\"preserve\">Kept </w:t></w:r>" +
            "<w:del><w:r><w:delText>gone</w:delText></w:r></w:del>" +
            "<w:r><w:rPr><w:vanish/></w:rPr><w:t>hidden</w:t></w:r>" +
            "<w:r><w:t>text</w:t></w:r></w:p>"
        assertEquals("Kept text", NoteDocxReader.read(docx(body), ::store).blocks.single().text)
    }

    @Test fun legacyWordFilesAreRecognised() {
        assertTrue(NoteDocxReader.isOleFile(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0)))
        assertFalse(NoteDocxReader.isOleFile("PK\u0003\u0004abcd".toByteArray()))
    }

    @Test fun filesThatAreNotWordDocumentsAreRefused() {
        val text = folder.newFile("notes.docx").apply { writeText("just some text") }
        assertRefused { NoteDocxReader.read(text, ::store) }
        val emptyZip = folder.newFile("empty.docx")
        ZipOutputStream(emptyZip.outputStream()).use { it.putNextEntry(ZipEntry("other.txt")); it.write(1); it.closeEntry() }
        assertRefused { NoteDocxReader.read(emptyZip, ::store) }
        assertRefused { NoteDocxReader.read(docx(p("   ")), ::store) }
    }

    @Test fun externalEntitiesAreNeverLoaded() {
        val secret = folder.newFile("secret.txt").apply { writeText("TOP-SECRET") }
        val prolog = "<?xml version=\"1.0\"?><!DOCTYPE w:document [<!ENTITY xxe SYSTEM \"${secret.toURI()}\">]>"
        val result = runCatching { NoteDocxReader.read(docx(p("&xxe;"), prolog = prolog), ::store) }
        result.getOrNull()?.let { r -> assertTrue(r.blocks.none { "TOP-SECRET" in it.text }) }
            ?: assertTrue(result.exceptionOrNull() is NoteDocxReader.Refused)
    }

    @Test fun summaryCountsWhatArrived() {
        val result = NoteDocxReader.read(docx(p("Plants", "Heading1") + p("Body")), ::store)
        assertEquals("Imported \u201Cguide.docx\u201D: 2 sections, 1 heading. Undo removes the import.",
            NoteDocumentImport.summary("guide.docx", result))
        assertNull(NoteDocumentImport.storedImages(result).firstOrNull())
    }

    private fun assertRefused(block: () -> Unit) {
        try { block(); fail("expected the file to be refused") } catch (_: NoteDocxReader.Refused) {}
    }

    private companion object {
        const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        const val RELS = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rLink\" Type=\"$R/hyperlink\" Target=\"https://example.com/guide\" TargetMode=\"External\"/>" +
            "<Relationship Id=\"rBad\" Type=\"$R/hyperlink\" Target=\"javascript:alert(1)\" TargetMode=\"External\"/>" +
            "<Relationship Id=\"rImg\" Type=\"$R/image\" Target=\"media/image1.png\"/>" +
            "<Relationship Id=\"rBroken\" Type=\"$R/image\" Target=\"media/broken.png\"/>" +
            "</Relationships>"
        const val STYLES = "<w:styles xmlns:w=\"$W\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Outlined\"><w:name w:val=\"Section label\"/><w:pPr><w:outlineLvl w:val=\"2\"/></w:pPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/></w:style>" +
            "</w:styles>"
        const val NUMBERING = "<w:numbering xmlns:w=\"$W\">" +
            "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"bullet\"/></w:lvl>" +
            "<w:lvl w:ilvl=\"1\"><w:numFmt w:val=\"bullet\"/></w:lvl></w:abstractNum>" +
            "<w:abstractNum w:abstractNumId=\"1\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"decimal\"/></w:lvl></w:abstractNum>" +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>" +
            "<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>" +
            "</w:numbering>"
    }
}

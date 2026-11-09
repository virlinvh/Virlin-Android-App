package com.virlin.app.ui.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.NoteDocVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteExportTest {
    private val colors = NotePrintColors.of(null)

    private fun sample(imageName: String? = null) = listOfNotNull(
        NoteDocBlock("h", NoteDocBlockType.HEADING_1, "Plan"),
        NoteDocBlock("p", NoteDocBlockType.PARAGRAPH, "Bold and link here",
            runs = listOf(NoteDocRun(0, 4, setOf(NoteDocMark.BOLD)), NoteDocRun(9, 13, linkUrl = "https://example.com"))),
        NoteDocBlock("b1", NoteDocBlockType.BULLET, "first"),
        NoteDocBlock("b2", NoteDocBlockType.BULLET, "nested", indent = 1),
        NoteDocBlock("n1", NoteDocBlockType.NUMBERED, "one"),
        NoteDocBlock("n2", NoteDocBlockType.NUMBERED, "two"),
        NoteDocBlock("c", NoteDocBlockType.CHECKLIST, "done", checked = true),
        NoteDocBlock("q", NoteDocBlockType.QUOTE, "Quoted"),
        NoteDocBlock("code", NoteDocBlockType.CODE, "def f():\n    return 1", mathMode = false),
        NoteDocBlock("t", NoteDocBlockType.TABLE, table = NoteDocTable(2, 2, listOf("Fruit", "Colour", "Apple", "Red|ish"),
            headerRow = true)),
        imageName?.let { NoteDocBlock("i", NoteDocBlockType.IMAGE, imagePath = it) },
        NoteDocBlock("v", NoteDocBlockType.VIDEO, video = NoteDocVideo("dQw4w9WgXcQ", title = "Clip")),
        NoteDocBlock("e", NoteDocBlockType.PARAGRAPH, "# not a heading & <tag>"),
    )

    @Test fun markdownKeepsStructureAndMarks() {
        val md = NoteMarkdownWriter.write("My note", sample()) { null }
        assertTrue(md, md.startsWith("# My note\n\n## Plan\n\n"))
        assertTrue("**Bold** and [link](https://example.com) here" in md)
        assertTrue("- first\n  - nested\n1. one\n2. two\n- [x] done" in md)
        assertTrue("> Quoted" in md)
        assertTrue("```python\ndef f():\n    return 1\n```" in md)
        assertTrue("| Fruit | Colour |\n| --- | --- |\n| Apple | Red\\|ish |" in md)
        assertTrue("[▶ Clip](https://www.youtube.com/watch?v=dQw4w9WgXcQ)" in md)
        assertTrue("\\# not a heading & \\<tag>" in md)
    }

    @Test fun docxIsAWellFormedWordPackage() {
        val jpeg = ByteArrayOutputStream().also {
            Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                .compress(Bitmap.CompressFormat.JPEG, 80, it)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        NoteDocxWriter.write(out, "My note", sample("pic.png"), NotePrintSettings(numberAlign = NoteNumberAlign.RIGHT), colors) { _, _ ->
            NoteExportImages.Encoded(jpeg, "jpeg", 40, 20)
        }
        val parts = unzip(out.toByteArray())
        listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/numbering.xml",
            "word/_rels/document.xml.rels", "docProps/core.xml", "word/media/image1.jpeg", "word/footer1.xml").forEach {
            assertTrue("missing $it", it in parts)
        }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, bytes) ->
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).also { assertTrue(name, it.documentElement != null) }
        }
        val document = String(parts.getValue("word/document.xml"))
        assertTrue("<w:pStyle w:val=\"Heading1\"/>" in document)
        assertTrue("<w:tbl>" in document && "<w:hyperlink" in document && "<w:drawing>" in document)
        assertTrue(document.indexOf("</w:tbl>") < document.indexOf("<w:sectPr>"))
        assertEquals(2, Regex("<w:abstractNum ").findAll(String(parts.getValue("word/numbering.xml"))).count())
        assertTrue("TargetMode=\"External\"" in String(parts.getValue("word/_rels/document.xml.rels")))
        // Page numbers are live fields in the footer, where the page formatting put them.
        val footer = String(parts.getValue("word/footer1.xml"))
        assertTrue(" PAGE " in footer && " NUMPAGES " in footer && "w:val=\"right\"" in footer)
        assertTrue("<w:footerReference w:type=\"default\" r:id=\"rId3\"/>" in document)
        assertTrue("w:left=\"1080\"" in document)
    }

    @Test fun slimmerRewritesLosslessPicturesAsJpeg() {
        val w = 160
        val h = 120
        val rgb = ByteArray(w * h * 3) { i ->
            val p = i / 3
            val x = p % w
            val y = p / w
            (when (i % 3) { 0 -> x * 255 / w; 1 -> y * 255 / h; else -> (x * y + p * 7919) % 256 }).toByte()
        }
        val pdf = syntheticPdf(w, h, rgb)
        val slim = NotePdfSlimmer.slim(pdf, 80)
        val text = String(slim, Charsets.ISO_8859_1)
        assertTrue(slim.size < pdf.size)
        assertTrue("/DCTDecode" in text)
        assertFalse("/FlateDecode" in text.substringAfter("4 0 obj").substringBefore("endobj"))
        val xrefAt = text.substringAfterLast("startxref\n").substringBefore("\n").trim().toInt()
        assertTrue(text.startsWith("xref", xrefAt))
        val imageAt = Regex("(\\d{10}) 00000 n ").findAll(text.substring(xrefAt)).toList()[3].groupValues[1].toInt()
        assertTrue(text.startsWith("4 0 obj", imageAt))
        val jpegStart = text.indexOf("stream\n", imageAt) + "stream\n".length
        val decoded = BitmapFactory.decodeByteArray(slim, jpegStart, slim.size - jpegStart)
        assertEquals(w, decoded.width)
    }

    @Test fun slimmerHandlesSrgbProfiledPictures() {
        val text = String(NotePdfSlimmer.slim(syntheticPdf(160, 120, photo(160, 120), icc = true), 80), Charsets.ISO_8859_1)
        assertTrue("/DCTDecode" in text && "/ColorSpace [/ICCBased 6 0 R]" in text)
    }

    @Test fun slimmerLeavesFilesItDoesNotUnderstandAlone() {
        val notPdf = "hello".toByteArray()
        assertTrue(NotePdfSlimmer.slim(notPdf, 80).contentEquals(notPdf))
    }

    @Test fun everyFormatProducesAFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "virlin-export-test").apply { mkdirs() }
        val photo = File(dir, "photo.png")
        Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 0 until 600 step 3) for (x in 0 until 900 step 3) setPixel(x, y, Color.rgb(x % 256, y % 256, (x * y) % 256))
        }.compress(Bitmap.CompressFormat.PNG, 100, photo.outputStream())
        val source = NotePrintSource("Export note", sample("photo.png"), colors, imageFile = { File(dir, it) })
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        fun bytes(settings: NoteExportSettings) = NoteExporter.bytes(context, settings, NotePrintSettings(), source)

        val word = bytes(NoteExportSettings(format = NoteExportFormat.WORD))
        assertTrue("word/document.xml" in unzip(word))
        val md = bytes(NoteExportSettings(format = NoteExportFormat.MARKDOWN, markdownImages = true))
        val mdParts = unzip(md)
        assertTrue("Export note.md" in mdParts && "images/image-1.jpg" in mdParts)
        val long = bytes(NoteExportSettings(format = NoteExportFormat.IMAGE))
        assertEquals(1240, BitmapFactory.decodeByteArray(long, 0, long.size).width)
        val pages = unzip(bytes(NoteExportSettings(format = NoteExportFormat.IMAGE, imageOutput = NoteImageOutput.PAGES,
            imageType = NoteImageType.PNG)))
        assertTrue(pages.isNotEmpty() && pages.keys.all { it.endsWith(".png") })
        dir.deleteRecursively()
    }

    @Test fun fileNamesAndTypesFollowTheFormat() {
        assertEquals("a b.docx", NoteExporter.fileName("a/b", NoteExportSettings(format = NoteExportFormat.WORD)))
        assertEquals("application/zip", NoteExportSettings(format = NoteExportFormat.MARKDOWN, markdownImages = true).mimeType)
        assertEquals("image/png", NoteExportSettings(format = NoteExportFormat.IMAGE, imageType = NoteImageType.PNG).mimeType)
        assertEquals("184 KB", NoteExporter.sizeLabel(184L * 1024))
    }

    private fun photo(w: Int, h: Int) = ByteArray(w * h * 3) { i ->
        val p = i / 3
        val x = p % w
        val y = p / w
        (when (i % 3) { 0 -> x * 255 / w; 1 -> y * 255 / h; else -> (x * y + p * 7919) % 256 }).toByte()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes()
            }
        }
        return parts
    }

    private fun syntheticPdf(w: Int, h: Int, rgb: ByteArray, icc: Boolean = false): ByteArray {
        val deflater = Deflater().apply { setInput(rgb); finish() }
        val buf = ByteArray(rgb.size + 1024)
        val packed = buf.copyOf(deflater.deflate(buf)).also { deflater.end() }
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        ascii("%PDF-1.4\n")
        offsets += out.size(); ascii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets += out.size(); ascii("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets += out.size(); ascii("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
            "/Resources << /XObject << /Im1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n")
        offsets += out.size()
        ascii("4 0 obj\n<< /Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace ${if (icc) "[/ICCBased 6 0 R]" else "/DeviceRGB"} " +
            "/BitsPerComponent 8 /Filter /FlateDecode /Length ${packed.size} >>\nstream\n")
        out.write(packed); ascii("\nendstream\nendobj\n")
        val content = "q 160 0 0 120 20 40 cm /Im1 Do Q"
        offsets += out.size(); ascii("5 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
        offsets += out.size(); ascii("6 0 obj\n<</N 3\n/Length 0>> stream\n\nendstream\nendobj\n")
        val xref = out.size()
        ascii("xref\n0 7\n0000000000 65535 f \n")
        offsets.forEach { ascii("%010d 00000 n \n".format(it)) }
        ascii("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }
}

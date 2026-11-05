package com.virlin.app.domain

import com.virlin.app.domain.attachment.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class ImportBudgetTest {
    private fun archive(name: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry(name)); it.write(body); it.closeEntry() }
        return out.toByteArray()
    }
    @Test fun limit_isCheckedWhileReading_notAfterConsumingInput() {
        var read = 0
        val endless = object : InputStream() {
            override fun read() = 0.also { read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(0, off, off + len); read += len; return len
            }
        }
        try { readBoundedImport(endless, 1024); fail("Expected limit") }
        catch (_: ImportLimitException) { assertTrue(read <= 8192) }
    }
    @Test fun boundedImport_preservesSmallContent() {
        val content = "small map".toByteArray()
        assertArrayEquals(content, readBoundedImport(content.inputStream(), 1024))
    }
    @Test fun compressedSlide_isRejectedWithoutAllocatingWholeEntry() {
        val bytes = archive("ppt/slides/slide1.xml", ByteArray(5 * 1024 * 1024) { 32 })
        assertTrue(OoxmlReaders.readPptx(bytes.inputStream()).truncated)
    }
    @Test fun skippedZipEntry_alsoConsumesBudget() {
        val bytes = archive("ignored.bin", ByteArray(5 * 1024 * 1024) { 32 })
        assertTrue(OoxmlReaders.readDocx(bytes.inputStream()).truncated)
    }
    @Test fun docx_preservesOrdinaryText() {
        val xml = "<document><t>Hello</t><t>world</t></document>".toByteArray()
        assertEquals(listOf("Hello world"), OoxmlReaders.readDocx(archive("word/document.xml", xml).inputStream()).paragraphs)
    }
    @Test fun oversizedTextNode_isRejected() {
        val xml = ("<document><t>" + "a".repeat(40_000) + "</t></document>").toByteArray()
        assertTrue(OoxmlReaders.readDocx(archive("word/document.xml", xml).inputStream()).truncated)
    }
    @Test fun malformedXml_returnsFriendlyError_notUncaughtException() {
        assertTrue(OoxmlReaders.readDocx(archive("word/document.xml", "<document><t>broken".toByteArray()).inputStream()).truncated)
    }
    @Test fun ioFailure_returnsFriendlyError() {
        val broken = object : InputStream() { override fun read(): Int = throw java.io.IOException("Provider failure") }
        assertTrue(OoxmlReaders.readXlsx(broken).truncated)
    }
}

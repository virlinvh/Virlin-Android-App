package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.attachment.AttachmentKindResolver
import com.virlin.app.domain.attachment.CsvTableReader
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AttachmentCaptureTest {

    private val clock = object : VirlinClock {
        override fun now(): Instant = Instant.parse("2026-09-14T12:00:00Z")
    }
    private val repo = InMemoryWorkStreamRepository()
    private val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())

    @Test
    fun kindResolver_mapsCommonTypes() {
        assertEquals(AttachmentKind.PDF, AttachmentKindResolver.resolve("application/pdf", "a.pdf"))
        assertEquals(AttachmentKind.IMAGE, AttachmentKindResolver.resolve("image/jpeg", "a.jpg"))
        assertEquals(AttachmentKind.CSV, AttachmentKindResolver.resolve("text/csv", "data.csv"))
        assertEquals(AttachmentKind.DOCX, AttachmentKindResolver.resolve(null, "memo.docx"))
        assertEquals(AttachmentKind.XLSX, AttachmentKindResolver.resolve(null, "sheet.xlsx"))
        assertEquals(AttachmentKind.PPTX, AttachmentKindResolver.resolve(null, "deck.pptx"))
        assertEquals(AttachmentKind.TEXT, AttachmentKindResolver.resolve("application/json", "x.json"))
        assertEquals(AttachmentKind.UNSUPPORTED, AttachmentKindResolver.resolve("application/zip", "a.zip"))
    }

    @Test
    fun csvReader_parsesQuotedCommas() {
        val line = CsvTableReader.parseLine("""a,"b,c",d""")
        assertEquals(listOf("a", "b,c", "d"), line)
    }

    @Test
    fun createAttachment_commitsOnce_andHydrates() = runBlocking {
        val first = actions.createAttachment(
            displayName = "spec.pdf",
            mimeType = "application/pdf",
            sizeBytes = 4096,
            relativePath = "att1/original",
            kind = AttachmentKind.PDF,
            captureId = "cap_file_1",
            attachmentId = "att1"
        )
        assertTrue(first is ActionResult.Success)
        val doc = (first as ActionResult.Success).value
        assertEquals(CaptureType.FILE, repo.getCapture(doc.captureItemId)!!.type)
        assertEquals("spec.pdf", repo.getAttachmentByCaptureId(doc.captureItemId)!!.displayName)

        val again = actions.saveAttachment(
            captureItemId = doc.captureItemId,
            displayName = "spec.pdf",
            mimeType = "application/pdf",
            sizeBytes = 8192,
            relativePath = "att1/original",
            kind = AttachmentKind.PDF
        )
        assertTrue(again is ActionResult.Success)
        assertEquals(1, repo.captures.value.count { it.id == doc.captureItemId })
        assertEquals(8192L, repo.getAttachmentByCaptureId(doc.captureItemId)!!.sizeBytes)
    }

    @Test
    fun saveAttachment_rejectsNonFileCapture() = runBlocking {
        actions.createCapture(
            com.virlin.app.domain.action.CreateCapture(type = CaptureType.NOTE, content = "hi", id = "cap_note")
        )
        val r = actions.saveAttachment(
            captureItemId = "cap_note",
            displayName = "x.pdf",
            mimeType = "application/pdf",
            sizeBytes = 10,
            relativePath = "x/original",
            kind = AttachmentKind.PDF
        )
        assertTrue(r is ActionResult.Rejected)
        assertEquals(DomainError.NotAnAttachment, (r as ActionResult.Rejected).reason)
    }

    @Test
    fun emptyAttachment_rejected() = runBlocking {
        val r = actions.createAttachment(
            displayName = "empty.pdf",
            mimeType = "application/pdf",
            sizeBytes = 0,
            relativePath = "att/original",
            kind = AttachmentKind.PDF
        )
        assertTrue(r is ActionResult.Rejected)
        assertFalse(AttachmentKindResolver.formatSize(1200).isBlank())
    }
}

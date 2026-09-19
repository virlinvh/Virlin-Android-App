package com.virlin.app.domain.action

import com.virlin.app.domain.attachment.AttachmentKindResolver
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.AttachmentDocument
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.domain.time.VirlinClock

/**
 * File/Image attachment ops. Capture lifecycle stays on CaptureItem(FILE);
 * original bytes live in managed storage — this layer only persists metadata.
 */
internal class AttachmentActions(
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
    private val ids: IdProvider,
    private val capture: CaptureActions
) {

    suspend fun createAttachment(
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        relativePath: String,
        kind: AttachmentKind,
        context: CaptureContext = CaptureContext.None,
        captureId: String? = null,
        attachmentId: String? = null
    ): ActionResult<AttachmentDocument> = tx {
        if (sizeBytes <= 0L || relativePath.isBlank()) {
            return@tx ActionResult.Rejected(DomainError.EmptyCapture)
        }
        val now = clock.now()
        val doc = AttachmentDocument(
            id = attachmentId ?: ids.newId("att"),
            captureItemId = captureId ?: ids.newId("cap"),
            displayName = displayName.trim().ifEmpty { "file" },
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            sizeBytes = sizeBytes,
            relativePath = relativePath,
            kind = kind,
            createdAt = now,
            updatedAt = now
        )
        val create = CreateCapture(
            type = CaptureType.FILE,
            content = "${doc.displayName} · ${AttachmentKindResolver.formatLabel(doc.kind)} · ${AttachmentKindResolver.formatSize(doc.sizeBytes)}",
            title = doc.displayName,
            context = context,
            id = doc.captureItemId
        )
        when (val r = capture.createCaptureIn(this, create)) {
            is ActionResult.Success -> Unit
            is ActionResult.Rejected -> return@tx ActionResult.Rejected(r.reason)
            is ActionResult.NotFound -> return@tx r
            is ActionResult.Failure -> return@tx r
        }
        saveAttachmentDocument(doc)
        ActionResult.Success(doc)
    }

    suspend fun saveAttachment(
        captureItemId: String,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        relativePath: String,
        kind: AttachmentKind
    ): ActionResult<AttachmentDocument> = tx {
        val cap = getCapture(captureItemId)
            ?: return@tx ActionResult.Rejected(DomainError.CaptureNotFound(captureItemId))
        if (cap.type != CaptureType.FILE) return@tx ActionResult.Rejected(DomainError.NotAnAttachment)
        if (sizeBytes <= 0L || relativePath.isBlank()) {
            return@tx ActionResult.Rejected(DomainError.EmptyCapture)
        }
        val now = clock.now()
        val existing = getAttachmentByCaptureId(captureItemId)
        val doc = (existing ?: AttachmentDocument(
            id = ids.newId("att"),
            captureItemId = captureItemId,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            relativePath = relativePath,
            kind = kind,
            createdAt = cap.createdAt,
            updatedAt = now
        )).copy(
            displayName = displayName.trim().ifEmpty { "file" },
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            sizeBytes = sizeBytes,
            relativePath = relativePath,
            kind = kind,
            updatedAt = now
        )
        saveCapture(
            cap.copy(
                title = doc.displayName,
                content = "${doc.displayName} · ${AttachmentKindResolver.formatLabel(doc.kind)} · ${AttachmentKindResolver.formatSize(doc.sizeBytes)}",
                updatedAt = now
            )
        )
        saveAttachmentDocument(doc)
        ActionResult.Success(doc)
    }

    suspend fun getAttachmentByCaptureId(captureItemId: String): AttachmentDocument? =
        repository.getAttachmentByCaptureId(captureItemId)

    private suspend fun <T> tx(block: suspend WorkStreamWriter.() -> ActionResult<T>): ActionResult<T> =
        try {
            repository.transaction(block)
        } catch (e: Exception) {
            ActionResult.Failure(e)
        }
}

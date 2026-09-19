package com.virlin.app.domain.model

import java.time.Instant

/**
 * File/Image attachment companion to [CaptureItem] type FILE.
 * Original bytes live under managed storage ([relativePath]); this row is metadata only.
 */
enum class AttachmentKind {
    PDF, IMAGE, VIDEO, AUDIO, TEXT, CSV, DOCX, XLSX, PPTX, UNSUPPORTED
}

data class AttachmentDocument(
    val id: String,
    val captureItemId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val relativePath: String,
    val kind: AttachmentKind,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun hasMeaningfulContent(): Boolean = sizeBytes > 0 && relativePath.isNotBlank()
}

package com.virlin.app.domain.attachment

import com.virlin.app.domain.model.AttachmentKind

/** Resolve viewer kind from MIME and/or filename — local only, no content sniffing beyond extension. */
object AttachmentKindResolver {

    fun resolve(mimeType: String?, displayName: String): AttachmentKind {
        val mime = mimeType?.lowercase()?.trim().orEmpty()
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return when {
            mime == "application/pdf" || ext == "pdf" -> AttachmentKind.PDF
            mime.startsWith("image/") || ext in imageExt -> AttachmentKind.IMAGE
            mime.startsWith("video/") || ext in videoExt -> AttachmentKind.VIDEO
            mime.startsWith("audio/") || ext in audioExt -> AttachmentKind.AUDIO
            mime == "text/csv" || mime == "application/csv" || ext == "csv" -> AttachmentKind.CSV
            mime.contains("wordprocessingml") || ext == "docx" -> AttachmentKind.DOCX
            mime.contains("spreadsheetml") || ext == "xlsx" -> AttachmentKind.XLSX
            mime.contains("presentationml") || ext == "pptx" -> AttachmentKind.PPTX
            mime.startsWith("text/") || ext in textExt -> AttachmentKind.TEXT
            mime == "application/json" || mime == "application/xml" -> AttachmentKind.TEXT
            else -> AttachmentKind.UNSUPPORTED
        }
    }

    fun formatLabel(kind: AttachmentKind): String = when (kind) {
        AttachmentKind.PDF -> "PDF"
        AttachmentKind.IMAGE -> "Image"
        AttachmentKind.VIDEO -> "Video"
        AttachmentKind.AUDIO -> "Audio"
        AttachmentKind.TEXT -> "Text"
        AttachmentKind.CSV -> "CSV"
        AttachmentKind.DOCX -> "Word"
        AttachmentKind.XLSX -> "Spreadsheet"
        AttachmentKind.PPTX -> "Presentation"
        AttachmentKind.UNSUPPORTED -> "File"
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
    private val videoExt = setOf("mp4", "webm", "3gp", "mkv", "mov")
    private val audioExt = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac")
    private val textExt = setOf("txt", "log", "md", "markdown", "json", "xml", "html", "htm", "css", "js", "kt", "java", "py")
}

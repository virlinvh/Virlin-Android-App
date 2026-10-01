package com.virlin.app.domain.attachment

import com.virlin.app.domain.model.AttachmentKind

/** Resolve viewer kind from MIME and/or filename — local only, no content sniffing beyond extension. */
object AttachmentKindResolver {

    fun resolve(mimeType: String?, displayName: String): AttachmentKind {
        val mime = mimeType?.lowercase()?.trim().orEmpty()
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return when {
            mime == "application/pdf" || ext == "pdf" -> AttachmentKind.PDF
            ext in markdownExt -> AttachmentKind.MARKDOWN
            ext in archiveExt || mime in archiveMime -> AttachmentKind.ARCHIVE
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
        AttachmentKind.MARKDOWN -> "Markdown"
        AttachmentKind.ARCHIVE -> "Archive"
        AttachmentKind.UNSUPPORTED -> "File"
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /**
     * How a kind may be shown. Kept here so routing stays centralized: there is deliberately no
     * second capability enum elsewhere in the app.
     */
    enum class Preview {
        /** Rendered inside the Attachment workspace. */
        IN_APP,
        /** Handed to the dedicated PDF workspace. */
        PDF_WORKSPACE,
        /** Stored safely, described only - share or open with another app. */
        DETAILS_ONLY,
    }

    fun previewOf(kind: AttachmentKind): Preview = when (kind) {
        AttachmentKind.PDF -> Preview.PDF_WORKSPACE
        AttachmentKind.ARCHIVE, AttachmentKind.UNSUPPORTED -> Preview.DETAILS_ONLY
        else -> Preview.IN_APP
    }

    private val markdownExt = setOf("md", "markdown")
    private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
    private val archiveMime = setOf(
        "application/zip", "application/x-zip-compressed", "application/vnd.rar",
        "application/x-rar-compressed", "application/x-7z-compressed", "application/x-tar",
        "application/gzip", "application/x-gzip",
    )

    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
    private val videoExt = setOf("mp4", "webm", "3gp", "mkv", "mov")
    private val audioExt = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac")
    private val textExt = setOf("txt", "log", "json", "xml", "html", "htm", "css", "js", "kt", "java", "py")
}

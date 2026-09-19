package com.virlin.app.data.attachment

import android.content.Context
import android.net.Uri
import com.virlin.app.domain.attachment.AttachmentKindResolver
import com.virlin.app.domain.model.AttachmentKind
import java.io.File
import java.io.FileOutputStream

/**
 * Streams a SAF/content Uri into Virlin-managed files. Never holds the whole file in a ByteArray.
 */
object AttachmentFileStore {

    data class Imported(
        val relativePath: String,
        val absoluteFile: File,
        val sizeBytes: Long,
        val displayName: String,
        val mimeType: String,
        val kind: AttachmentKind
    )

    fun attachmentsRoot(context: Context): File =
        File(context.filesDir, "attachments").also { it.mkdirs() }

    fun resolve(context: Context, relativePath: String): File =
        File(attachmentsRoot(context), relativePath)

    fun importFromUri(
        context: Context,
        uri: Uri,
        attachmentId: String,
        fallbackName: String = "file"
    ): Imported {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri) ?: fallbackName
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val kind = AttachmentKindResolver.resolve(mime, displayName)
        val dir = File(attachmentsRoot(context), attachmentId).also { it.mkdirs() }
        val dest = File(dir, "original")
        var size = 0L
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    size += n
                }
                output.fd.sync()
            }
        } ?: error("Unable to open selected file")
        val relative = "$attachmentId/original"
        return Imported(relative, dest, size, displayName, mime, kind)
    }

    fun deleteAttachmentTree(context: Context, attachmentId: String) {
        File(attachmentsRoot(context), attachmentId).deleteRecursively()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}

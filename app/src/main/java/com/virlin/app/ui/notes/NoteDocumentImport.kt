package com.virlin.app.ui.notes

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.virlin.app.data.noteimage.NoteImageStore
import java.io.File
import java.io.InputStream

/**
 * Turns a picked Word or Google Docs file into note blocks. Blocking: call off the main thread.
 * Google Docs live as virtual files, so they are requested from their provider as `.docx`.
 * Nothing is written to the note here; pictures are stored privately and removed again when
 * the import is refused.
 */
internal object NoteDocumentImport {
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val GOOGLE_DOC = "application/vnd.google-apps.document"
    private const val OLD_WORD = "application/msword"

    /** What the system picker offers; anything else is still checked when read. */
    val PICKER_TYPES = arrayOf(DOCX, GOOGLE_DOC, OLD_WORD, "application/octet-stream", "application/zip")

    private const val OLD_FORMAT =
        "This is an older Word (.doc) or protected file. Save it as .docx in Word or Google Docs, then import it."

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    fun import(context: Context, uri: Uri): NoteDocxImport {
        val resolver = context.contentResolver
        val type = runCatching { resolver.getType(uri) }.getOrNull()
        if (type == OLD_WORD) throw NoteDocxReader.Refused(OLD_FORMAT)
        val temp = File.createTempFile("note-import", ".docx", context.cacheDir)
        try {
            open(resolver, uri, type).use { input -> copyBounded(input, temp) }
            val head = ByteArray(8)
            val read = temp.inputStream().use { it.read(head) }
            if (read == 8 && NoteDocxReader.isOleFile(head)) throw NoteDocxReader.Refused(OLD_FORMAT)
            val stored = mutableListOf<String>()
            try {
                return NoteDocxReader.read(temp) { bytes ->
                    runCatching { NoteImageStore.importBytes(context, bytes) }.getOrNull()?.also(stored::add)
                }
            } catch (error: Exception) {
                discardImages(context, stored)
                throw error
            }
        } finally {
            temp.delete()
        }
    }

    /** What the person is told after [result] from [name] was added to the note. */
    fun summary(name: String, result: NoteDocxImport): String {
        fun count(n: Int, one: String) = "$n $one${if (n == 1) "" else "s"}"
        val headings = result.blocks.count { it.type.headingLevel > 0 }
        val parts = buildList {
            add(count(result.blocks.size, "section"))
            if (headings > 0) add(count(headings, "heading"))
            if (result.images > 0) add(count(result.images, "picture"))
            if (result.tables > 0) add(count(result.tables, "table"))
        }
        return buildString {
            append("Imported \u201C").append(name).append("\u201D: ").append(parts.joinToString(", ")).append('.')
            if (result.skippedImages > 0) append(" ")
                .append(count(result.skippedImages, "picture")).append(" couldn't be read and ")
                .append(if (result.skippedImages == 1) "was" else "were").append(" left out.")
            if (result.truncated) append(" The document was very long, so only its first part was imported.")
            append(" Undo removes the import.")
        }
    }

    /** Pictures [result] stored, for removal when it never reached the note. */
    fun storedImages(result: NoteDocxImport): List<String> = result.blocks.mapNotNull { it.imagePath }

    /** Removes pictures stored by an import that did not reach the note. */
    fun discardImages(context: Context, names: List<String>) {
        names.forEach { runCatching { NoteImageStore.resolve(context, it)?.delete() } }
    }

    private fun open(resolver: ContentResolver, uri: Uri, type: String?): InputStream {
        val convertible = type == GOOGLE_DOC || (type != DOCX &&
            runCatching { resolver.getStreamTypes(uri, DOCX) }.getOrNull()?.isNotEmpty() == true)
        if (convertible) {
            runCatching { resolver.openTypedAssetFileDescriptor(uri, DOCX, null)?.createInputStream() }
                .getOrNull()?.let { return it }
            if (type == GOOGLE_DOC) throw NoteDocxReader.Refused(
                "This Google Doc couldn't be downloaded as Word. Check your connection, or download it as .docx first.")
        }
        return runCatching { resolver.openInputStream(uri) }.getOrNull()
            ?: throw NoteDocxReader.Refused("This file couldn't be opened.")
    }

    private fun copyBounded(input: InputStream, target: File) {
        target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > NoteDocxReader.MAX_FILE_BYTES) throw NoteDocxReader.Refused(
                    "This document is larger than 50 MB, so it can't be imported.")
                out.write(buffer, 0, n)
            }
            if (total == 0L) throw NoteDocxReader.Refused("This file is empty.")
        }
    }
}

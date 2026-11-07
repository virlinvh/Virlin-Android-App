package com.virlin.app.data.noteimage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID
import android.util.Base64

/** Durable, private copies of images embedded in notes. Picker URI grants are not retained. */
object NoteImageStore {
    private const val MAX_BYTES = 25L * 1024 * 1024
    private val validName = Regex("[0-9a-f-]{36}")

    fun resolve(context: Context, name: String): File? =
        name.takeIf { validName.matches(it) }?.let { File(context.filesDir, "note_images/$it") }

    fun import(context: Context, uri: Uri): String {
        val root = File(context.filesDir, "note_images").also { it.mkdirs() }
        val name = UUID.randomUUID().toString()
        val file = File(root, name)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open image")
            var copied = 0L
            input.use { source ->
                file.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_BYTES) { "Image is too large (25 MB maximum)" }
                        target.write(buffer, 0, count)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
            return name
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    /**
     * Stores an edited picture as a new file and returns its name. The file it was made from is
     * left untouched, so undo can always return to it.
     */
    fun saveEdited(context: Context, bitmap: Bitmap, png: Boolean): String {
        val root = File(context.filesDir, "note_images").also { it.mkdirs() }
        val name = UUID.randomUUID().toString()
        val file = File(root, name)
        try {
            file.outputStream().use { out ->
                require(bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    if (png) 100 else 92, out)) { "Could not save the edited image" }
            }
            require(file.length() in 1..MAX_BYTES) { "The edited image is too large to save" }
            return name
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    /** Whether the stored picture keeps transparency (PNG, WebP or GIF), so edits should too. */
    fun keepsTransparency(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outMimeType in setOf("image/png", "image/webp", "image/gif")
    }

    /** Saves a clipboard-embedded bitmap to the same private store as picker images. */
    fun importDataUri(context: Context, source: String): String {
        val comma = source.indexOf(',')
        require(comma in 1..100 && source.substring(0, comma).matches(
            Regex("data:image/(png|jpeg|webp|gif);base64", RegexOption.IGNORE_CASE))) {
            "Unsupported embedded image"
        }
        require(source.length - comma - 1 <= (MAX_BYTES * 4 / 3 + 8)) { "Image is too large" }
        val bytes = Base64.decode(source.substring(comma + 1), Base64.DEFAULT)
        require(bytes.size <= MAX_BYTES) { "Image is too large" }
        val root = File(context.filesDir, "note_images").also { it.mkdirs() }
        val name = UUID.randomUUID().toString()
        val file = File(root, name)
        try {
            file.outputStream().use { it.write(bytes) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
            return name
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }
}

package com.virlin.app.data.projecticon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Managed storage for custom project icons under `filesDir/project-icons/` — the same pattern as
 * [com.virlin.app.data.attachment.AttachmentFileStore] and `VoiceFileStore`. Room stores only the
 * relative path (`Project.iconPath`); the bytes live here, owned by Virlin, so a picker's temporary
 * content Uri is never relied on after import.
 *
 * Import rules: PNG / JPEG / WebP only; the stream must decode as an image; the ORIGINAL bytes are
 * copied unchanged (no re-encode, so quality is never degraded); presentation is normalised at
 * display time by [decodeForDisplay] (downsampled, centre-crop is done by the composable).
 */
object ProjectIconStore {

    /** Accepted MIME types for a custom icon. */
    val supportedMimeTypes: Set<String> = setOf("image/png", "image/jpeg", "image/webp")

    class UnsupportedImage(message: String) : IllegalArgumentException(message)

    fun root(context: Context): File = File(context.filesDir, "project-icons").also { it.mkdirs() }

    fun resolve(context: Context, relativePath: String): File = File(root(context), relativePath)

    /**
     * Copies the picked image into the store for [projectId] and returns its relative path.
     * Any previous icon file of that project is deleted after the new one is safely written.
     * Throws [UnsupportedImage] for a non-image or unsupported type — nothing is written then.
     */
    fun importFromUri(context: Context, projectId: String, uri: Uri): String {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: sniffMime(context, uri)
        if (mime !in supportedMimeTypes) throw UnsupportedImage("Unsupported image type: $mime")
        // Validate that it really decodes as an image (bounds only — no full bitmap in memory).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = resolver.openInputStream(uri) ?: throw UnsupportedImage("Cannot open image")
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }   // bounds-only decode always returns null by design
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UnsupportedImage("Not a decodable image")

        val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val relative = "$projectId/icon-${System.currentTimeMillis()}.$ext"
        val dest = resolve(context, relative).also { it.parentFile?.mkdirs() }
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out, 64 * 1024); out.fd.sync() }
        } ?: throw UnsupportedImage("Cannot open image")
        // Remove any previous icon of this project (keep the one just written).
        dest.parentFile?.listFiles()?.filter { it != dest }?.forEach { it.delete() }
        return relative
    }

    /** Deletes the project's icon files. Safe to call when nothing exists. */
    fun delete(context: Context, projectId: String) {
        File(root(context), projectId).deleteRecursively()
    }

    /** True when the reference points at an existing, readable file. */
    fun exists(context: Context, relativePath: String?): Boolean =
        !relativePath.isNullOrBlank() && resolve(context, relativePath).let { it.isFile && it.length() > 0 }

    /**
     * Decodes the icon downsampled to roughly [targetPx] on its shorter side (power-of-two
     * `inSampleSize`), or null when the file is missing / corrupt. Never throws.
     */
    fun decodeForDisplay(context: Context, relativePath: String, targetPx: Int): Bitmap? {
        val file = resolve(context, relativePath)
        if (!file.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val shorter = minOf(bounds.outWidth, bounds.outHeight)
            while (shorter / (sample * 2) >= targetPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeFile(file.path, opts)
        } catch (e: Throwable) {
            null
        }
    }

    private fun sniffMime(context: Context, uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        return bounds.outMimeType ?: "application/octet-stream"
    }
}

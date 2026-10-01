package com.virlin.app.data.mappreview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Managed private storage for mind-map preview images.
 *
 * Preview images are **not** Attachments. Routing them through the Attachment store would give each
 * one a Capture, an Inbox entry and a `capture.file` Task Page block, which would turn private map
 * decoration into the user's attached work. They therefore live in their own root and are only ever
 * referenced by a path **relative** to it, so the app directory may move.
 */
object MapPreviewImageStore {

    private const val ROOT = "map_previews"

    /** Decoded previews are bounded: a map never needs full-resolution pixels. */
    const val MAX_EDGE_PX = 1280

    fun root(context: Context): File = File(context.filesDir, ROOT).apply { mkdirs() }

    fun resolve(context: Context, relativePath: String): File = File(root(context), relativePath)

    /** Everything belonging to one preview lives in one directory, so clearing it is one delete. */
    fun ownerDir(context: Context, ownerId: String): File = File(root(context), safe(ownerId))

    data class Imported(
        val relativePath: String,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
    )

    /**
     * Copies [uri] into managed storage, downscaled to [MAX_EDGE_PX].
     *
     * Returns `null` when the source is unreadable, zero-byte, or not a decodable image, and leaves
     * nothing behind: a file created before the failure was detected is deleted, so a rejected
     * import can never strand bytes. Imported content is only ever decoded as a bitmap, never
     * executed or parsed as anything else.
     */
    fun importFromUri(context: Context, uri: Uri, ownerId: String, imageId: String): Imported? {
        val dir = ownerDir(context, ownerId).apply { mkdirs() }
        val target = File(dir, "$imageId.png")
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            val decoded = context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@runCatching null

            // This bitmap is ours alone and is never handed to Compose, so recycling it here is
            // safe. A bitmap passed to asImageBitmap() must NEVER be recycled - that exact mistake
            // crashed the Image workspace with "Canvas: trying to use a recycled bitmap".
            val result = try {
                target.outputStream().use { decoded.compress(Bitmap.CompressFormat.PNG, 92, it) }
                Imported(
                    relativePath = "${safe(ownerId)}/${target.name}",
                    width = decoded.width,
                    height = decoded.height,
                    sizeBytes = target.length(),
                )
            } finally {
                decoded.recycle()
            }
            if (result.sizeBytes <= 0L) null else result
        }.getOrNull().also { if (it == null) target.delete() }
    }

    /** Removes one image's bytes. Siblings are never touched. */
    fun delete(context: Context, relativePath: String): Boolean =
        runCatching { resolve(context, relativePath).delete() }.getOrDefault(false)

    /** Removes every managed file for one node, used when a preview is cleared. */
    fun deleteOwner(context: Context, ownerId: String) {
        runCatching { ownerDir(context, ownerId).deleteRecursively() }
    }

    /** True when the bytes a preview references are actually present and non-empty. */
    fun exists(context: Context, relativePath: String): Boolean =
        runCatching { resolve(context, relativePath).let { it.isFile && it.length() > 0 } }
            .getOrDefault(false)

    internal fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= MAX_EDGE_PX || h / 2 >= MAX_EDGE_PX) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /** Ids are generated, but a managed path must never be able to escape its root. */
    internal fun safe(id: String): String =
        id.replace('\\', '_').replace('/', '_').replace("..", "_")
            .filterNot { it.isISOControl() }.take(80).ifBlank { "node" }
}

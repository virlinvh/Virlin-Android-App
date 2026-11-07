package com.virlin.app.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.virlin.app.domain.notedoc.NotePasteReport

/** Images on the system clipboard (a copied picture or diagram), which a text paste cannot carry. */
internal object NoteClipboardImages {
    /** Reads only the clip's description, so asking does not count as reading the clipboard. */
    fun available(context: Context): Boolean {
        val description = context.getSystemService(ClipboardManager::class.java)
            ?.primaryClipDescription ?: return false
        return (0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("image/") }
    }

    fun uris(context: Context): List<Uri> {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return emptyList()
        return imageUris(context, clip)
    }

    fun imageUris(context: Context, clip: ClipData): List<Uri> {
        val declared = (0 until clip.description.mimeTypeCount)
            .any { clip.description.getMimeType(it).startsWith("image/") }
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            .filter { uri -> uri.scheme == "content" && (declared ||
                runCatching { context.contentResolver.getType(uri) }.getOrNull()?.startsWith("image/") == true) }
    }
}

internal fun clipSummary(context: Context, clip: ClipData): NotePasteReport.ClipMeta =
    NotePasteReport.ClipMeta(
        items = clip.itemCount,
        mimeTypes = (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) },
        imageItems = runCatching { NoteClipboardImages.imageUris(context, clip).size }.getOrDefault(0),
    )

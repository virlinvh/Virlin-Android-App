package com.virlin.app.ui.notes

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.text.Html
import android.text.Spanned
import com.virlin.app.data.noteimage.NoteImageStore
import com.virlin.app.domain.notedoc.GoogleDocsPaste

/** What the clipboard holds for a "Paste from" source: its text and, when the app sent one, its formatted copy. */
internal data class NoteClipboardCopy(val plain: String, val html: String?)

internal object NoteSourceClipboard {
    /** Reads the first clip item; null when it carries no text at all. */
    fun read(context: Context): NoteClipboardCopy? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 } ?: return null
        val item = clip.getItemAt(0)
        val plain = item.text?.toString() ?: runCatching { item.coerceToText(context)?.toString() }.getOrNull()
        val html = item.htmlText ?: googleDocsProviderHtml(context, clip, item)
            ?: (item.text as? Spanned)?.let { Html.toHtml(it, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE) }
        if (plain.isNullOrBlank() && html.isNullOrBlank()) return null
        return NoteClipboardCopy(plain.orEmpty(), html)
    }

    /** Stores pictures a formatted copy carries; Google Docs pictures are downloaded off the main thread. */
    fun imageResolver(context: Context): (String) -> String? = { source ->
        runCatching {
            when {
                source.startsWith("data:image/", ignoreCase = true) -> NoteImageStore.importDataUri(context, source)
                Uri.parse(source).scheme == "content" -> NoteImageStore.import(context, Uri.parse(source))
                GoogleDocsPaste.isDocsImageUrl(source) && Looper.myLooper() != Looper.getMainLooper() ->
                    NoteImageStore.importRemote(context, source)
                else -> null
            }
        }.getOrNull()
    }
}

package com.virlin.app.ui.note

import android.content.ClipboardManager
import android.content.Context

/** Reads HTML + plain from the system clipboard (best-effort). */
object ClipboardNoteReader {
    data class Clip(val html: String?, val plain: String)

    fun read(context: Context): Clip {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return Clip(null, "")
        val clip = cm.primaryClip ?: return Clip(null, "")
        if (clip.itemCount == 0) return Clip(null, "")
        val item = clip.getItemAt(0)
        val html = item.htmlText?.takeIf { it.isNotBlank() }
        val plain = item.coerceToText(context)?.toString().orEmpty()
        return Clip(html = html, plain = plain)
    }
}

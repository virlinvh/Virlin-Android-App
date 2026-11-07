package com.virlin.app.ui.notes

import android.content.Context

/**
 * Where each note was last read: the block at the top of the editor and how far it was
 * scrolled into it, so reopening a note continues at the same place. View state only — it is
 * never part of the document, and a missing or stale entry just opens the note at the top.
 */
internal class NotePositionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("note_positions", Context.MODE_PRIVATE)

    fun load(noteKey: String): Pair<String, Int>? {
        val raw = prefs.getString(noteKey, null) ?: return null
        val split = raw.lastIndexOf('|')
        if (split <= 0) return null
        return raw.substring(0, split) to (raw.substring(split + 1).toIntOrNull() ?: 0).coerceAtLeast(0)
    }

    fun save(noteKey: String, blockId: String, offsetPx: Int) {
        val value = "$blockId|${offsetPx.coerceAtLeast(0)}"
        if (prefs.getString(noteKey, null) != value) prefs.edit().putString(noteKey, value).apply()
    }
}

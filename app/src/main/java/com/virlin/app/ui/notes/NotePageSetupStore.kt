package com.virlin.app.ui.notes

import android.content.Context

/**
 * Each note's page formatting (auto-fit, paper, layout, margins, page numbers, title), kept beside the note
 * like [NotePositionStore]. It is never part of the document: a missing or unreadable entry means
 * the note has not been formatted yet, and the document itself is never touched.
 */
internal class NotePageSetupStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("note_page_setup", Context.MODE_PRIVATE)

    fun load(noteKey: String): NotePrintSettings? = prefs.getString(noteKey, null)?.let(::decode)

    fun save(noteKey: String, settings: NotePrintSettings) {
        val value = encode(settings)
        if (prefs.getString(noteKey, null) != value) prefs.edit().putString(noteKey, value).apply()
    }

    /** Whether the note is shown and exported as formatted pages. Its chosen setup is kept either way. */
    fun isOn(noteKey: String): Boolean = prefs.getBoolean("$noteKey$ON", false)

    fun setOn(noteKey: String, on: Boolean) {
        if (isOn(noteKey) != on) prefs.edit().putBoolean("$noteKey$ON", on).apply()
    }

    companion object {
        private const val ON = "#on"

        internal fun encode(s: NotePrintSettings): String =
            listOf(s.paper.name, if (s.landscape) "L" else "P", s.margins.name, s.pageNumbers.name,
                s.numberAlign.name, if (s.includeTitle) "T" else "-", if (s.autoFit) "F" else "-").joinToString("|")

        internal fun decode(raw: String): NotePrintSettings? = runCatching {
            val p = raw.split('|')
            if (p.size < 6) return null
            NotePrintSettings(
                paper = NotePaper.valueOf(p[0]),
                landscape = p[1] == "L",
                margins = NoteMargins.valueOf(p[2]),
                pageNumbers = NotePageNumbers.valueOf(p[3]),
                numberAlign = NoteNumberAlign.valueOf(p[4]),
                includeTitle = p[5] == "T",
                // Entries saved before auto-fit have six fields.
                autoFit = p.getOrNull(6) == "F",
            )
        }.getOrNull()
    }
}

package com.virlin.app.domain.prompt

import com.virlin.app.domain.note.NoteClipboardImporter

/** Preserve raw plain text exactly; rich clipboard content gets portable Markdown semantics. */
fun importedPromptText(html: String?, plain: String): String {
    if (html.isNullOrBlank()) return plain
    // Many apps put a plain-text wrapper on the HTML clipboard flavor. In that case the
    // clipboard's plain flavor is more faithful to the user's spacing and line endings.
    if (!Regex("(?i)<(?:h[1-6]|strong|b|em|i|ul|ol|li|blockquote|pre|code|br)\\b").containsMatchIn(html)) return plain
    val formatted = NoteClipboardImporter.htmlToRoughMarkdown(html)
    return formatted.ifBlank { plain }
}

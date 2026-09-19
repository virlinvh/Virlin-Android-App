package com.virlin.app.domain.model

import java.time.Instant

/**
 * Capture Text Note (rich document). Lifecycle (Inbox / context / archive) stays on
 * [CaptureItem]; this owns title + ordered blocks. MIXED / Actor / cloud sync are out of scope.
 *
 * Storage V1: one Room row per note with a JSON block payload ([NoteDocumentCodec]) so nesting
 * (Toggle children), reorder, and autosave stay practical without per-keystroke N+1 DAO work.
 */
enum class NoteBlockType {
    TEXT,
    HEADING_1,
    HEADING_2,
    HEADING_3,
    BULLETED_LIST,
    NUMBERED_LIST,
    CHECKBOX,
    TOGGLE,
    DIVIDER,
    QUOTE,
    CALLOUT,
    CODE;

    val isTextCapable: Boolean get() = this != DIVIDER

    val acceptsChildren: Boolean get() = this == TOGGLE
}

enum class InlineStyle {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
    HIGHLIGHT,
    CODE,
    LINK
}

/** Half-open [start, end) mark over [NoteBlock.plainText]. */
data class TextMark(
    val start: Int,
    val end: Int,
    val style: InlineStyle,
    val linkUrl: String? = null
) {
    init {
        require(start >= 0 && end >= start) { "invalid mark range $start..$end" }
    }
}

data class NoteBlock(
    val id: String,
    val type: NoteBlockType,
    val plainText: String = "",
    val marks: List<TextMark> = emptyList(),
    val checked: Boolean = false,
    val collapsed: Boolean = false,
    /** Toggle children only. Other types keep this empty. */
    val children: List<NoteBlock> = emptyList()
) {
    fun hasMeaningfulContent(): Boolean = when (type) {
        NoteBlockType.DIVIDER -> true
        NoteBlockType.CHECKBOX -> plainText.isNotBlank() || checked
        NoteBlockType.TOGGLE -> plainText.isNotBlank() || children.any { it.hasMeaningfulContent() }
        else -> plainText.isNotBlank() || children.any { it.hasMeaningfulContent() }
    }
}

data class NoteDocument(
    val id: String,
    val captureItemId: String,
    val title: String?,
    val blocks: List<NoteBlock>,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun hasMeaningfulContent(): Boolean =
        !title.isNullOrBlank() || blocks.any { it.hasMeaningfulContent() }

    companion object {
        fun emptyDraft(id: String, captureItemId: String, now: Instant, firstBlockId: String) = NoteDocument(
            id = id,
            captureItemId = captureItemId,
            title = null,
            blocks = listOf(NoteBlock(id = firstBlockId, type = NoteBlockType.TEXT)),
            createdAt = now,
            updatedAt = now
        )
    }
}

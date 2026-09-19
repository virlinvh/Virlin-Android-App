package com.virlin.app.domain.model

import java.time.Instant

/**
 * Capture Prompt document. Lifecycle (Inbox / context / archive) stays on [CaptureItem];
 * this owns title, optional description/tags, and the structured prompt body ([NoteBlock] tree).
 * Body formatting reuses the Text Note block model so clipboard HTML/Markdown import stays shared.
 */
data class PromptDocument(
    val id: String,
    val captureItemId: String,
    val title: String?,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val blocks: List<NoteBlock>,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun hasMeaningfulContent(): Boolean =
        !title.isNullOrBlank() ||
            !description.isNullOrBlank() ||
            tags.any { it.isNotBlank() } ||
            blocks.any { it.hasMeaningfulContent() }
}

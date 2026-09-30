package com.virlin.app.domain.model

import java.time.Instant

enum class PromptContentMode { PROMPT, CODE }

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
    /** Original editor contents. Never normalized; Copy returns this value byte-for-byte. */
    val sourceText: String = "",
    val mode: PromptContentMode = PromptContentMode.PROMPT,
    /** User override, or the last accepted automatic suggestion. */
    val language: String? = null,
    /** The answer pasted by the user. Virlin never presents this as generated content. */
    val responseText: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun hasMeaningfulContent(): Boolean =
        !title.isNullOrBlank() ||
            !description.isNullOrBlank() ||
            tags.any { it.isNotBlank() } ||
            sourceText.isNotBlank() ||
            !responseText.isNullOrBlank() ||
            blocks.any { it.hasMeaningfulContent() }
}

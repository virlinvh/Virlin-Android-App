package com.virlin.app.domain.mappreview

import java.time.Instant

/**
 * A mind-map node's optional compact preview.
 *
 * This is **presentation data owned by one node**, not Task Page content and not a Capture. It
 * deliberately does not use the Task Page block model: a preview is a single document keyed by the
 * node's own stable id, it never appears in the Inbox, and it never creates a Page block. Putting it
 * in the Page model would make internal map decoration indistinguishable from the user's real
 * attached work.
 *
 * [ownerId] is the stable id of the Project, WorkStream or Task the node projects — the same id
 * `MapTopic.id` carries. Nothing here is keyed by title, because titles repeat.
 */
data class MapNodePreview(
    val ownerId: String,
    val description: FormattedText = FormattedText.EMPTY,
    val images: List<MapPreviewImage> = emptyList(),
    val expanded: Boolean = false,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    /**
     * A preview with no description and no images carries no meaning, so it is never stored. The
     * editor saves this as a delete rather than leaving an empty row behind.
     */
    val isEmpty: Boolean get() = description.isBlank && images.isEmpty()

    companion object {
        /** The description cap. Enforced in the domain, not only by the text field. */
        const val MAX_DESCRIPTION = 180

        /** The image cap. Enforced in the action layer, not only by hiding the add tile. */
        const val MAX_IMAGES = 7
    }
}

/** One managed image belonging to a preview, in its stable display order. */
data class MapPreviewImage(
    val id: String,
    /**
     * Path **relative** to the preview image root. Absolute paths are never persisted, so the
     * application directory may move without orphaning every preview.
     */
    val relativePath: String,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
)

/**
 * A short run-encoded string supporting exactly bold and italic.
 *
 * Runs are stored as non-overlapping, ordered, half-open ranges over [text]. Only two marks exist;
 * there is deliberately no underline, colour, heading, list, link or markdown, because this is a
 * one-line map decoration rather than a document editor. Notes remains the rich editor.
 */
data class FormattedText(
    val text: String = "",
    val runs: List<FormatRun> = emptyList(),
) {
    val isBlank: Boolean get() = text.isBlank()

    /** Normalised form: clamped to [text], ordered, with empty and redundant runs removed. */
    fun normalized(): FormattedText {
        if (runs.isEmpty()) return copy(runs = emptyList())
        val clamped = runs.mapNotNull { run ->
            val start = run.start.coerceIn(0, text.length)
            val end = run.end.coerceIn(0, text.length)
            if (end <= start || run.marks.isEmpty()) null else run.copy(start = start, end = end)
        }.sortedWith(compareBy({ it.start }, { it.end }))
        // Merge runs that touch or overlap and carry identical marks, so repeated edits cannot
        // grow an unbounded list of adjacent fragments that all mean the same thing.
        val merged = ArrayList<FormatRun>(clamped.size)
        clamped.forEach { run ->
            val last = merged.lastOrNull()
            if (last != null && last.marks == run.marks && run.start <= last.end) {
                merged[merged.lastIndex] = last.copy(end = maxOf(last.end, run.end))
            } else {
                merged += run
            }
        }
        return copy(runs = merged)
    }

    /** The marks covering [index], used to render and to seed the toolbar's active state. */
    fun marksAt(index: Int): Set<FormatMark> =
        runs.firstOrNull { index >= it.start && index < it.end }?.marks ?: emptySet()

    companion object {
        val EMPTY = FormattedText()

        /** Builds a plain, unformatted value. */
        fun plain(text: String) = FormattedText(text)
    }
}

enum class FormatMark { BOLD, ITALIC }

/** A half-open `[start, end)` range of [FormattedText.text] carrying zero or more marks. */
data class FormatRun(val start: Int, val end: Int, val marks: Set<FormatMark>)

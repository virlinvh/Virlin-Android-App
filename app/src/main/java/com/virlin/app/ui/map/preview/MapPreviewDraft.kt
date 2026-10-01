package com.virlin.app.ui.map.preview

import com.virlin.app.domain.mappreview.FormatMark
import com.virlin.app.domain.mappreview.FormattedText
import com.virlin.app.domain.mappreview.FormattedTextEditor
import com.virlin.app.domain.mappreview.MapNodePreview
import com.virlin.app.domain.mappreview.MapPreviewImage

/**
 * One text edit expressed as a replacement, recovered from before/after strings.
 *
 * A Compose text field reports the whole new string, not what changed. Formatting runs cannot
 * survive that without knowing the edit, so the change is recovered by matching the common prefix
 * and suffix. This is the standard technique and it is exact for every single-region edit an IME,
 * a paste or a backspace produces.
 */
data class TextEdit(val start: Int, val end: Int, val inserted: String)

internal fun diffEdit(old: String, new: String): TextEdit {
    if (old == new) return TextEdit(old.length, old.length, "")
    var prefix = 0
    val maxPrefix = minOf(old.length, new.length)
    while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (
        suffix < (maxPrefix - prefix) &&
        old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
    ) suffix++
    return TextEdit(
        start = prefix,
        end = old.length - suffix,
        inserted = new.substring(prefix, new.length - suffix),
    )
}

/**
 * The popup editor's working copy.
 *
 * It is deliberately a plain value: the draft lives in the popup, never in the map's state, so
 * typing a description cannot recompose the graph. Nothing here touches the database — [saved]
 * remains whatever was last committed until the editor explicitly saves.
 *
 * [addedImages] tracks images imported during this editing session. If the user cancels, exactly
 * those files are deleted and the saved preview is untouched; if the user saves, they become part
 * of the record and [removedPaths] are the files that may then be deleted.
 */
data class MapPreviewDraft(
    val ownerId: String,
    val description: FormattedText = FormattedText.EMPTY,
    val images: List<MapPreviewImage> = emptyList(),
    val activeImage: Int = 0,
    /** The marks applied to text typed next, when the caret is not inheriting from its left. */
    val pendingMarks: Set<FormatMark> = emptySet(),
    val addedImages: List<MapPreviewImage> = emptyList(),
    val removedPaths: List<String> = emptyList(),
    val saving: Boolean = false,
) {
    val canAddImage: Boolean get() = images.size < MapNodePreview.MAX_IMAGES
    val isEmpty: Boolean get() = description.isBlank && images.isEmpty()
    val hasContent: Boolean get() = !isEmpty
    val counter: String get() = "${images.size} / ${MapNodePreview.MAX_IMAGES}"
    val characterCounter: String
        get() = "${description.text.length} / ${MapNodePreview.MAX_DESCRIPTION}"

    val active: MapPreviewImage? get() = images.getOrNull(activeImage)

    /** Applies a text-field change, preserving the bold/italic runs around it. */
    fun withText(newText: String): MapPreviewDraft {
        val edit = diffEdit(description.text, newText)
        val marks = if (edit.inserted.isEmpty()) null else pendingMarks.ifEmpty { null }
        return copy(
            description = FormattedTextEditor.replace(
                description, edit.start, edit.end, edit.inserted, insertMarks = marks,
            ),
        )
    }

    /**
     * Toggles a mark.
     *
     * With a selection it formats that range. With no selection it arms the mark for the next
     * typed characters, which is what a user expects from pressing B before typing.
     */
    fun toggleMark(mark: FormatMark, selectionStart: Int, selectionEnd: Int): MapPreviewDraft =
        if (selectionEnd > selectionStart) {
            copy(description = FormattedTextEditor.toggleMark(description, selectionStart, selectionEnd, mark))
        } else {
            copy(pendingMarks = if (mark in pendingMarks) pendingMarks - mark else pendingMarks + mark)
        }

    /** Whether the toolbar button should read as checked for the current selection or caret. */
    fun isActive(mark: FormatMark, selectionStart: Int, selectionEnd: Int): Boolean =
        if (selectionEnd > selectionStart) {
            (selectionStart until selectionEnd).all { mark in description.marksAt(it) }
        } else {
            mark in pendingMarks ||
                (selectionStart > 0 && mark in description.marksAt(selectionStart - 1))
        }

    fun addImage(image: MapPreviewImage): MapPreviewDraft =
        if (!canAddImage) this
        else copy(
            images = images + image,
            addedImages = addedImages + image,
            activeImage = images.size,
        )

    /**
     * Replaces the active image in place.
     *
     * The old file's path is only queued for deletion; nothing is removed from disk until the save
     * commits, so a failed save can never leave the record pointing at bytes that are already gone.
     */
    fun replaceActive(image: MapPreviewImage): MapPreviewDraft {
        val index = activeImage
        val old = images.getOrNull(index) ?: return this
        return copy(
            images = images.toMutableList().apply { this[index] = image },
            addedImages = addedImages + image,
            removedPaths = removedPaths + old.relativePath,
        )
    }

    /** Deletes the active image and selects the nearest remaining one. */
    fun deleteActive(): MapPreviewDraft {
        val old = images.getOrNull(activeImage) ?: return this
        val remaining = images.toMutableList().apply { removeAt(activeImage) }
        return copy(
            images = remaining,
            activeImage = activeImage.coerceAtMost(remaining.lastIndex.coerceAtLeast(0)),
            removedPaths = removedPaths + old.relativePath,
            addedImages = addedImages.filterNot { it.id == old.id },
        )
    }

    /** Clears everything the preview holds. The underlying task is never touched. */
    fun cleared(): MapPreviewDraft = copy(
        description = FormattedText.EMPTY,
        images = emptyList(),
        activeImage = 0,
        pendingMarks = emptySet(),
        removedPaths = removedPaths + images.map { it.relativePath },
        addedImages = emptyList(),
    )

    fun select(index: Int): MapPreviewDraft =
        copy(activeImage = index.coerceIn(0, images.lastIndex.coerceAtLeast(0)))

    companion object {
        fun of(ownerId: String, preview: MapNodePreview?) = MapPreviewDraft(
            ownerId = ownerId,
            description = preview?.description ?: FormattedText.EMPTY,
            images = preview?.images.orEmpty(),
        )
    }
}

package com.virlin.app.domain.note

import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType

/**
 * Pure Enter / soft-break rules for the Text Note block editor.
 * Wrapping is visual only — block boundaries come only from these explicit actions.
 */
object NoteEnterSemantics {

    data class EnterResult(
        val updatedCurrent: NoteBlock,
        /** New root-level sibling after [updatedCurrent], or null if handled inside toggle children. */
        val newSibling: NoteBlock?,
        /** When Enter was on a toggle child: replacement children list for the parent toggle. */
        val updatedToggleChildren: List<NoteBlock>? = null
    )

    /**
     * Apply Enter at a split in [before]/[after] (newline already stripped from both).
     * [newId] is the id for any newly created block.
     */
    fun applyEnter(
        block: NoteBlock,
        before: String,
        after: String,
        newId: String,
        /** When non-null, [block] is treated as a child of a Toggle; sibling is another child. */
        asToggleChild: Boolean = false
    ): EnterResult {
        if (block.type == NoteBlockType.CODE) {
            // Multiline code: soft newline stays in the same block.
            return EnterResult(
                updatedCurrent = block.copy(plainText = before + "\n" + after),
                newSibling = null
            )
        }
        if (block.type == NoteBlockType.DIVIDER) {
            return EnterResult(updatedCurrent = block, newSibling = null)
        }

        val empty = before.isBlank() && after.isBlank()
        // List / checkbox: empty Enter exits to TEXT.
        if (empty && block.type in listExitTypes) {
            val textBlock = block.copy(type = NoteBlockType.TEXT, plainText = "", checked = false)
            return EnterResult(updatedCurrent = textBlock, newSibling = null)
        }

        val nextType = when (block.type) {
            NoteBlockType.BULLETED_LIST -> NoteBlockType.BULLETED_LIST
            NoteBlockType.NUMBERED_LIST -> NoteBlockType.NUMBERED_LIST
            NoteBlockType.CHECKBOX -> NoteBlockType.CHECKBOX
            NoteBlockType.HEADING_1, NoteBlockType.HEADING_2, NoteBlockType.HEADING_3,
            NoteBlockType.QUOTE, NoteBlockType.CALLOUT, NoteBlockType.TEXT, NoteBlockType.TOGGLE ->
                NoteBlockType.TEXT
            NoteBlockType.CODE, NoteBlockType.DIVIDER -> NoteBlockType.TEXT
        }

        val updated = block.copy(plainText = before)
        val sibling = NoteBlock(
            id = newId,
            type = nextType,
            plainText = after,
            checked = false
        )
        return if (asToggleChild) {
            EnterResult(updatedCurrent = updated, newSibling = sibling, updatedToggleChildren = null)
        } else {
            EnterResult(updatedCurrent = updated, newSibling = sibling)
        }
    }

    fun insertSoftBreak(plainText: String, cursor: Int): String {
        val i = cursor.coerceIn(0, plainText.length)
        return plainText.substring(0, i) + "\n" + plainText.substring(i)
    }

    /** True when [newText] is [oldText] plus exactly one newline insertion (Enter or soft break). */
    fun newlineInsertion(oldText: String, newText: String): Int? {
        if (newText.length != oldText.length + 1) return null
        for (i in newText.indices) {
            if (i >= oldText.length || newText[i] != oldText[i]) {
                return if (newText[i] == '\n') i else null
            }
        }
        return null
    }

    private val listExitTypes = setOf(
        NoteBlockType.BULLETED_LIST,
        NoteBlockType.NUMBERED_LIST,
        NoteBlockType.CHECKBOX
    )
}

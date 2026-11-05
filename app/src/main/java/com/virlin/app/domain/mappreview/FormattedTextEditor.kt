package com.virlin.app.domain.mappreview

/**
 * The deterministic editing rules for a preview description.
 *
 * Every operation is a pure function over [FormattedText], so the behaviour that is hard to get
 * right — what happens to a bold range when text is inserted inside it, deleted across it, or
 * replaced under it — is testable without a device, a Compose field or an IME.
 *
 * The model is intentionally small: two marks, non-overlapping ordered runs, half-open ranges.
 */
object FormattedTextEditor {

    /**
     * Replaces `[start, end)` with [insert].
     *
     * Text before the edit keeps its marks. Text after it shifts by the length delta. A run that
     * spans the edit survives as one run rather than splitting, because the user sees one
     * continuous emphasis and splitting it would make later merges depend on edit history.
     *
     * [insertMarks] are the marks applied to newly typed text. Passing `null` means "inherit",
     * which takes the marks at the left edge of the replaced range — typing inside a bold word
     * stays bold, which is what every editor does.
     */
    fun replace(
        source: FormattedText,
        start: Int,
        end: Int,
        insert: String,
        insertMarks: Set<FormatMark>? = null,
        maxLength: Int = MapNodePreview.MAX_DESCRIPTION,
    ): FormattedText {
        val from = start.coerceIn(0, source.text.length)
        val to = end.coerceIn(from, source.text.length)
        // The cap is enforced here, in the domain, so it holds for paste and IME commits too and
        // not only for the field's own maxLength.
        val room = (maxLength - (source.text.length - (to - from))).coerceAtLeast(0)
        val added = if (insert.length > room) insert.take(room) else insert
        val text = source.text.substring(0, from) + added + source.text.substring(to)

        // Inherit from the character to the LEFT of the caret, which is the one the user just
        // typed after. At position 0 there is nothing to inherit from, so the text starts plain.
        val marks = insertMarks ?: if (from > 0) source.marksAt(from - 1) else emptySet()

        // Rebuilding a per-character table is O(180) and makes the hard cases - inserting inside a
        // run, deleting across several runs, replacing a selection that starts bold and ends plain
        // - fall out of the same three lines instead of needing bespoke index arithmetic.
        val before = (0 until from).map { source.marksAt(it) }
        val inserted = List(added.length) { marks }
        val after = (to until source.text.length).map { source.marksAt(it) }
        return fromPerCharacter(text, before + inserted + after)
    }

    /** Applies [mark] across `[start, end)`. */
    fun applyMark(source: FormattedText, start: Int, end: Int, mark: FormatMark): FormattedText =
        setMark(source, start, end, mark, enable = true)

    /** Removes [mark] across `[start, end)`. */
    fun clearMark(source: FormattedText, start: Int, end: Int, mark: FormatMark): FormattedText =
        setMark(source, start, end, mark, enable = false)

    /**
     * Toggles [mark] across `[start, end)`.
     *
     * The whole selection turns the mark off only when every character already carries it;
     * otherwise it turns on. That makes a mixed selection resolve to "all on" in one tap, which is
     * what a user means by pressing B on a partly bold phrase.
     */
    fun toggleMark(source: FormattedText, start: Int, end: Int, mark: FormatMark): FormattedText {
        val from = start.coerceIn(0, source.text.length)
        val to = end.coerceIn(from, source.text.length)
        if (to == from) return source
        val allMarked = (from until to).all { mark in source.marksAt(it) }
        return setMark(source, from, to, mark, enable = !allMarked)
    }

    private fun setMark(
        source: FormattedText,
        start: Int,
        end: Int,
        mark: FormatMark,
        enable: Boolean,
    ): FormattedText {
        val from = start.coerceIn(0, source.text.length)
        val to = end.coerceIn(from, source.text.length)
        if (to == from) return source
        // Rebuild per character: with at most 180 characters and two marks this is trivially cheap,
        // and it makes bold/italic overlap deterministic instead of depending on run order.
        val perChar = ArrayList<Set<FormatMark>>(source.text.length)
        for (i in source.text.indices) {
            val existing = source.marksAt(i)
            perChar += if (i in from until to) {
                if (enable) existing + mark else existing - mark
            } else {
                existing
            }
        }
        return fromPerCharacter(source.text, perChar)
    }

    /** Rebuilds minimal runs from a per-character mark table. */
    fun fromPerCharacter(text: String, perChar: List<Set<FormatMark>>): FormattedText {
        val runs = ArrayList<FormatRun>()
        var index = 0
        while (index < text.length) {
            val marks = perChar.getOrElse(index) { emptySet() }
            var end = index + 1
            while (end < text.length && perChar.getOrElse(end) { emptySet() } == marks) end++
            if (marks.isNotEmpty()) runs += FormatRun(index, end, marks)
            index = end
        }
        return FormattedText(text, runs).normalized()
    }
}

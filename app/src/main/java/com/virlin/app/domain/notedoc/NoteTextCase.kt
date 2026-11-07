package com.virlin.app.domain.notedoc

/** The three cases the toolbar's change-case tool cycles through. */
enum class NoteTextCase { UPPER, TITLE, LOWER }

/**
 * Case changes for note text that never change its length, so every formatting run, link and
 * caret offset stays exactly where it was. Inline maths (`\(…\)`) keeps its case — LaTeX commands
 * are case-sensitive. Conversions are locale-independent.
 */
object NoteTextCases {

    /** A text and the [start, end) part of it to convert. */
    data class Segment(val text: String, val start: Int, val end: Int)

    private val INLINE_MATH = Regex("\\\\\\((.+?)\\\\\\)")
    private val APOSTROPHES = setOf('\'', '\u2019')

    /** Characters of [text] whose case must not change. */
    fun protectedMask(text: String): BooleanArray {
        val mask = BooleanArray(text.length)
        for (m in INLINE_MATH.findAll(text)) for (i in m.range) mask[i] = true
        return mask
    }

    /**
     * The case one tap moves to: ALL CAPS → Title Case → lowercase → ALL CAPS, skipping a step
     * that would change nothing (e.g. "A" is already both). Null when no letter can change.
     */
    fun nextCase(segments: List<Segment>): NoteTextCase? {
        val letters = StringBuilder()
        for (seg in segments) {
            val mask = protectedMask(seg.text)
            for (i in seg.start.coerceAtLeast(0) until seg.end.coerceAtMost(seg.text.length)) {
                if (!mask[i] && Character.isLetter(seg.text[i])) letters.append(seg.text[i])
            }
        }
        if (letters.isEmpty()) return null
        val hasLower = letters.any { Character.isLowerCase(it) }
        val isTitle = segments.all { convert(it.text, it.start, it.end, NoteTextCase.TITLE) == it.text }
        val order = when {
            !hasLower -> listOf(NoteTextCase.TITLE, NoteTextCase.LOWER, NoteTextCase.UPPER)
            isTitle -> listOf(NoteTextCase.LOWER, NoteTextCase.UPPER, NoteTextCase.TITLE)
            else -> listOf(NoteTextCase.UPPER, NoteTextCase.TITLE, NoteTextCase.LOWER)
        }
        return order.firstOrNull { case -> segments.any { convert(it.text, it.start, it.end, case) != it.text } }
    }

    /** [text] with [start, end) converted to [case]; same length, maths untouched. */
    fun convert(text: String, start: Int, end: Int, case: NoteTextCase): String {
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        if (from == to) return text
        val mask = protectedMask(text)
        val out = StringBuilder(text)
        var i = from
        while (i < to) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            if (!mask[i] && Character.isLetter(cp)) {
                val target = when (case) {
                    NoteTextCase.UPPER -> Character.toUpperCase(cp)
                    NoteTextCase.LOWER -> Character.toLowerCase(cp)
                    NoteTextCase.TITLE -> if (isWordStart(text, i)) Character.toTitleCase(cp) else Character.toLowerCase(cp)
                }
                if (target != cp && Character.charCount(target) == width) {
                    out.replace(i, i + width, String(Character.toChars(target)))
                }
            }
            i += width
        }
        return out.toString()
    }

    /** The word around [caret] (letters, digits, inner apostrophes), or null when there is none. */
    fun wordAt(text: String, caret: Int): IntRange? {
        fun part(i: Int) = i in text.indices && (Character.isLetterOrDigit(text[i]) ||
            (text[i] in APOSTROPHES && i > 0 && i + 1 < text.length &&
                Character.isLetter(text[i - 1]) && Character.isLetter(text[i + 1])))
        val at = caret.coerceIn(0, text.length)
        var a = at
        while (a > 0 && part(a - 1)) a--
        var b = at
        while (b < text.length && part(b)) b++
        return if (a < b) a until b else null
    }

    private fun isWordStart(text: String, i: Int): Boolean {
        if (i == 0) return true
        val prev = text[i - 1]
        if (Character.isLetterOrDigit(prev) || Character.isLowSurrogate(prev)) return false
        // "don't" stays one word: an apostrophe right after a letter does not start a new one.
        if (prev in APOSTROPHES && i >= 2 && Character.isLetter(text[i - 2])) return false
        return true
    }
}

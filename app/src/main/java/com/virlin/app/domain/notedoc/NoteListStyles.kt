package com.virlin.app.domain.notedoc

/**
 * Bullet and numbering styles for [NoteDocBlockType.BULLET] / [NoteDocBlockType.NUMBERED] blocks.
 *
 * A block stores the style [Style.key] in [NoteDocBlock.listStyle]; null is the type's default
 * (disc / decimal), so notes written before styles existed are unchanged. Unknown keys from a
 * newer writer are kept as stored and drawn with the default marker.
 */
object NoteListStyles {

    data class Style(val key: String, val label: String, val sample: String)

    const val DEFAULT_BULLET = "disc"
    const val DEFAULT_NUMBER = "decimal"

    val bullets: List<Style> = listOf(
        Style(DEFAULT_BULLET, "Dot", "•"),
        Style("circle", "Circle", "◦"),
        Style("square", "Square", "▪"),
        Style("hollow-square", "Open square", "□"),
        Style("diamond", "Diamond", "◆"),
        Style("dash", "Dash", "–"),
        Style("arrow", "Arrow", "➤"),
        Style("chevron", "Chevron", "›"),
        Style("triangle", "Triangle", "‣"),
        Style("star", "Star", "★"),
        Style("check", "Tick", "✓"),
    )

    val numbers: List<Style> = listOf(
        Style(DEFAULT_NUMBER, "1. 2. 3.", "1."),
        Style("decimal-paren", "1) 2) 3)", "1)"),
        Style("decimal-zero", "01. 02. 03.", "01."),
        Style("lower-alpha", "a. b. c.", "a."),
        Style("lower-alpha-paren", "a) b) c)", "a)"),
        Style("upper-alpha", "A. B. C.", "A."),
        Style("lower-roman", "i. ii. iii.", "i."),
        Style("upper-roman", "I. II. III.", "I."),
    )

    fun stylesFor(type: NoteDocBlockType): List<Style> = when (type) {
        NoteDocBlockType.BULLET -> bullets
        NoteDocBlockType.NUMBERED -> numbers
        else -> emptyList()
    }

    /** The key a block of [type] actually draws with: its own valid key, else the default. */
    fun effectiveKey(type: NoteDocBlockType, key: String?): String? = when (type) {
        NoteDocBlockType.BULLET -> key?.takeIf { k -> bullets.any { it.key == k } } ?: DEFAULT_BULLET
        NoteDocBlockType.NUMBERED -> key?.takeIf { k -> numbers.any { it.key == k } } ?: DEFAULT_NUMBER
        else -> null
    }

    /** What to store for [key] on a block of [type]: null for the default, or for non-list blocks. */
    fun storedKey(type: NoteDocBlockType, key: String?): String? {
        val effective = effectiveKey(type, key) ?: return null
        return effective.takeIf { it != DEFAULT_BULLET && it != DEFAULT_NUMBER }
    }

    /** The marker drawn before a list item, e.g. "➤", "b)" or "iv.". [number] is 1-based. */
    fun marker(type: NoteDocBlockType, key: String?, number: Int = 1): String {
        val effective = effectiveKey(type, key) ?: return ""
        if (type == NoteDocBlockType.BULLET) return bullets.first { it.key == effective }.sample
        val n = number.coerceAtLeast(1)
        return when (effective) {
            "decimal-paren" -> "$n)"
            "decimal-zero" -> "${n.toString().padStart(2, '0')}."
            "lower-alpha" -> "${alpha(n)}."
            "lower-alpha-paren" -> "${alpha(n)})"
            "upper-alpha" -> "${alpha(n).uppercase()}."
            "lower-roman" -> "${roman(n).lowercase()}."
            "upper-roman" -> "${roman(n)}."
            else -> "$n."
        }
    }

    /** 1 → a, 26 → z, 27 → aa (spreadsheet-style letters). */
    fun alpha(n: Int): String {
        var v = n.coerceAtLeast(1)
        val sb = StringBuilder()
        while (v > 0) {
            v--
            sb.append('a' + v % 26)
            v /= 26
        }
        return sb.reverse().toString()
    }

    /** Roman numerals for 1–3999; larger numbers fall back to digits. */
    fun roman(n: Int): String {
        if (n !in 1..3999) return n.toString()
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var v = n
        val sb = StringBuilder()
        for (i in values.indices) while (v >= values[i]) { sb.append(symbols[i]); v -= values[i] }
        return sb.toString()
    }

    /**
     * Indices of the list [index] belongs to: the run of same-type items at its indent, skipping
     * deeper nested blocks and ending at a shallower block or any other block at the same indent.
     * This is exactly one stored list, so a style is applied to the whole list at once.
     */
    fun listGroup(blocks: List<NoteDocBlock>, index: Int): List<Int> {
        val block = blocks.getOrNull(index) ?: return emptyList()
        if (block.type != NoteDocBlockType.BULLET && block.type != NoteDocBlockType.NUMBERED) return emptyList()
        val level = block.indent
        fun member(b: NoteDocBlock) = b.type == block.type && b.indent == level
        val out = ArrayList<Int>()
        var i = index
        while (i - 1 >= 0) {
            val prev = blocks[i - 1]
            if (prev.indent > level) { i--; continue }
            if (member(prev)) i-- else break
        }
        while (i < blocks.size) {
            val b = blocks[i]
            if (b.indent > level) { i++; continue }
            if (!member(b)) break
            out += i
            i++
        }
        return out
    }
}

package com.virlin.app.domain.notedoc

import java.time.Instant

/**
 * The Notes page's OWN document model.
 *
 * Deliberately independent of [com.virlin.app.domain.model.NoteDocument], of the capture Text
 * Note editor and of the legacy plain `tasks.notes` string. The Notes page is being built as a
 * self-contained feature; the relationship to those other surfaces is a later decision, so this
 * model shares no types with them and nothing here reads or writes their storage.
 */
enum class NoteDocBlockType {
    PARAGRAPH, HEADING_1, HEADING_2, HEADING_3,
    BULLET, NUMBERED, CHECKLIST,
    QUOTE, CODE, PROMPT, DIVIDER, TABLE;

    /** Divider and table hold no editable inline text of their own. */
    val isTextual: Boolean get() = this != DIVIDER && this != TABLE
}

enum class NoteDocAlign { LEFT, CENTER, RIGHT }

/** Character-level marks that can overlap freely on the same range. */
enum class NoteDocMark { BOLD, ITALIC, UNDERLINE, STRIKE, CODE }

/**
 * A half-open `[start, end)` span of one block's text carrying inline formatting.
 *
 * Runs never overlap after [NoteRuns.normalize]; overlapping input is split so every character
 * maps to exactly one run. `null` attributes mean "inherit the block default".
 */
data class NoteDocRun(
    val start: Int,
    val end: Int,
    val marks: Set<NoteDocMark> = emptySet(),
    val color: Long? = null,
    val highlight: Long? = null,
    val fontFamily: String? = null,
    val fontSizeSp: Int? = null,
    val linkUrl: String? = null,
) {
    val isEmptyStyle: Boolean
        get() = marks.isEmpty() && color == null && highlight == null &&
            fontFamily == null && fontSizeSp == null && linkUrl == null

    val length: Int get() = end - start
}

data class NoteDocTable(
    val rows: Int,
    val cols: Int,
    /** Row-major, exactly `rows * cols` entries. */
    val cells: List<String>,
) {
    fun cell(r: Int, c: Int): String = cells.getOrElse(r * cols + c) { "" }

    fun withCell(r: Int, c: Int, value: String): NoteDocTable {
        val i = r * cols + c
        if (i !in cells.indices) return this
        return copy(cells = cells.toMutableList().also { it[i] = value })
    }

    companion object {
        fun blank(rows: Int, cols: Int) = NoteDocTable(rows, cols, List(rows * cols) { "" })
    }
}

data class NoteDocBlock(
    val id: String,
    val type: NoteDocBlockType = NoteDocBlockType.PARAGRAPH,
    val text: String = "",
    val runs: List<NoteDocRun> = emptyList(),
    val align: NoteDocAlign = NoteDocAlign.LEFT,
    val indent: Int = 0,
    val checked: Boolean = false,
    /** Multiplier on the block's line height. 1f is the type's own default. */
    val lineSpacing: Float = 1f,
    /** PROMPT only: the callout's user-authored heading. Never AI generated. */
    val promptTitle: String? = null,
    val table: NoteDocTable? = null,
)

/**
 * One note. [ownerKey] is an opaque key the feature is filed under; the Notes page does not
 * interpret it and nothing else in the app currently reads it.
 */
data class VirlinNoteDoc(
    val ownerKey: String,
    val title: String? = null,
    val blocks: List<NoteDocBlock> = emptyList(),
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
    val revision: Int = 0,
)

/**
 * Run arithmetic. Pure, so selection formatting and edit survival are unit-testable without a
 * ViewModel, a database or Compose.
 */
object NoteRuns {

    /** Splits overlaps, drops empties, merges identical neighbours, sorts by start. */
    fun normalize(runs: List<NoteDocRun>, textLength: Int): List<NoteDocRun> {
        if (runs.isEmpty() || textLength <= 0) return emptyList()
        val edges = sortedSetOf(0, textLength)
        runs.forEach {
            if (it.start in 0..textLength) edges.add(it.start)
            if (it.end in 0..textLength) edges.add(it.end)
        }
        val bounds = edges.toList()
        val pieces = ArrayList<NoteDocRun>(bounds.size)
        for (i in 0 until bounds.size - 1) {
            val s = bounds[i]
            val e = bounds[i + 1]
            if (e <= s) continue
            // Later runs win on scalar attributes; marks accumulate.
            var marks = emptySet<NoteDocMark>()
            var color: Long? = null
            var highlight: Long? = null
            var family: String? = null
            var size: Int? = null
            var link: String? = null
            runs.forEach { r ->
                if (r.start <= s && r.end >= e) {
                    marks = marks + r.marks
                    r.color?.let { color = it }
                    r.highlight?.let { highlight = it }
                    r.fontFamily?.let { family = it }
                    r.fontSizeSp?.let { size = it }
                    r.linkUrl?.let { link = it }
                }
            }
            val piece = NoteDocRun(s, e, marks, color, highlight, family, size, link)
            if (piece.isEmptyStyle) continue
            val last = pieces.lastOrNull()
            if (last != null && last.end == s && sameStyle(last, piece)) {
                pieces[pieces.size - 1] = last.copy(end = e)
            } else {
                pieces.add(piece)
            }
        }
        return pieces
    }

    fun sameStyle(a: NoteDocRun, b: NoteDocRun): Boolean =
        a.marks == b.marks && a.color == b.color && a.highlight == b.highlight &&
            a.fontFamily == b.fontFamily && a.fontSizeSp == b.fontSizeSp && a.linkUrl == b.linkUrl

    /** The style in effect at [index], used to seed the caret style. */
    fun styleAt(runs: List<NoteDocRun>, index: Int): NoteDocRun? =
        runs.firstOrNull { index >= it.start && index < it.end }
            ?: runs.firstOrNull { it.end == index }

    /** True when EVERY character of `[start, end)` already carries [mark]. */
    fun rangeHasMark(runs: List<NoteDocRun>, start: Int, end: Int, mark: NoteDocMark): Boolean {
        if (end <= start) return false
        var at = start
        val sorted = runs.filter { it.end > start && it.start < end }.sortedBy { it.start }
        for (r in sorted) {
            if (r.start > at) return false
            if (mark !in r.marks) return false
            at = maxOf(at, r.end)
            if (at >= end) return true
        }
        return at >= end
    }

    /** Adds or removes [mark] across `[start, end)`; [add] false removes it. */
    fun applyMark(
        runs: List<NoteDocRun>, textLength: Int,
        start: Int, end: Int, mark: NoteDocMark, add: Boolean,
    ): List<NoteDocRun> {
        if (end <= start) return runs
        val out = ArrayList<NoteDocRun>()
        // Keep the untouched parts of every existing run, restyle the overlap.
        runs.forEach { r ->
            if (r.end <= start || r.start >= end) { out.add(r); return@forEach }
            if (r.start < start) out.add(r.copy(end = start))
            if (r.end > end) out.add(r.copy(start = end))
            val s = maxOf(r.start, start)
            val e = minOf(r.end, end)
            if (e > s) {
                val marks = if (add) r.marks + mark else r.marks - mark
                out.add(r.copy(start = s, end = e, marks = marks))
            }
        }
        if (add) {
            // Cover stretches of the range that had no run at all.
            val covered = runs.filter { it.end > start && it.start < end }.sortedBy { it.start }
            var at = start
            covered.forEach { r ->
                if (r.start > at) out.add(NoteDocRun(at, minOf(r.start, end), setOf(mark)))
                at = maxOf(at, r.end)
            }
            if (at < end) out.add(NoteDocRun(at, end, setOf(mark)))
        }
        return normalize(out, textLength)
    }

    /** Applies a scalar attribute (colour, font, size, link) across `[start, end)`. */
    fun applyAttribute(
        runs: List<NoteDocRun>, textLength: Int, start: Int, end: Int,
        transform: (NoteDocRun) -> NoteDocRun,
    ): List<NoteDocRun> {
        if (end <= start) return runs
        val out = ArrayList<NoteDocRun>()
        runs.forEach { r ->
            if (r.end <= start || r.start >= end) { out.add(r); return@forEach }
            if (r.start < start) out.add(r.copy(end = start))
            if (r.end > end) out.add(r.copy(start = end))
            val s = maxOf(r.start, start)
            val e = minOf(r.end, end)
            if (e > s) out.add(transform(r.copy(start = s, end = e)))
        }
        val covered = runs.filter { it.end > start && it.start < end }.sortedBy { it.start }
        var at = start
        covered.forEach { r ->
            if (r.start > at) out.add(transform(NoteDocRun(at, minOf(r.start, end))))
            at = maxOf(at, r.end)
        }
        if (at < end) out.add(transform(NoteDocRun(at, end)))
        return normalize(out, textLength)
    }

    /**
     * Moves runs across a text edit so formatting survives typing, deletion and paste.
     *
     * The edit is described as the replacement of `[from, from + removed)` with [inserted]
     * characters. Text inside the removed range loses its runs; text after it shifts. Inserted
     * text joins the run that was in effect at the edit point, which is what makes typing inside
     * a bold word stay bold.
     */
    fun shiftForEdit(
        runs: List<NoteDocRun>, from: Int, removed: Int, inserted: Int, newLength: Int,
    ): List<NoteDocRun> {
        if (runs.isEmpty()) return runs
        val removeEnd = from + removed
        val delta = inserted - removed
        val out = ArrayList<NoteDocRun>(runs.size)
        runs.forEach { r ->
            var s = r.start
            var e = r.end
            // Collapse the deleted span out of this run.
            s = if (s <= from) s else if (s >= removeEnd) s + delta else from + inserted
            e = if (e <= from) e else if (e >= removeEnd) e + delta else from
            // Insertions at a boundary extend the run that owned the character before them.
            if (inserted > 0 && r.end == from && removed == 0) e = from + inserted
            if (e > s) out.add(r.copy(start = s.coerceIn(0, newLength), end = e.coerceIn(0, newLength)))
        }
        return normalize(out, newLength)
    }

    /** Describes an edit as a single replaced span by matching the common prefix and suffix. */
    fun diff(old: String, new: String): Triple<Int, Int, Int> {
        if (old == new) return Triple(0, 0, 0)
        var p = 0
        val max = minOf(old.length, new.length)
        while (p < max && old[p] == new[p]) p++
        var s = 0
        while (s < max - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        return Triple(p, old.length - p - s, new.length - p - s)
    }
}

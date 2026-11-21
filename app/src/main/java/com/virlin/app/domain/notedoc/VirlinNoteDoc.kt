package com.virlin.app.domain.notedoc

import java.time.Instant

/**
 * The Notes page's OWN document model.
 *
 * The rich editor model, independent of the legacy plain `tasks.notes` string.
 * CaptureNoteBridge adapts older capture documents and creates compatibility projections;
 * this rich document remains the authoritative formatting source.
 */
enum class NoteDocBlockType {
    PARAGRAPH, HEADING_1, HEADING_2, HEADING_3,
    BULLET, NUMBERED, CHECKLIST,
    QUOTE, CODE, PROMPT, DIVIDER, TABLE, IMAGE, TOGGLE,
    // Appended so stored names and any ordinal use of the earlier entries stay unchanged.
    HEADING_4, HEADING_5,
    /** An embedded YouTube clip; see [NoteDocVideo]. */
    VIDEO,
    /** A voice recording made in the note; see [NoteDocAudio]. */
    AUDIO;

    /** Non-text blocks hold no editable inline text of their own. */
    val isTextual: Boolean get() = this != DIVIDER && this != TABLE && this != IMAGE && this != VIDEO && this != AUDIO

    /** 1–5 for headings, 0 for everything else. */
    val headingLevel: Int get() = when (this) {
        HEADING_1 -> 1
        HEADING_2 -> 2
        HEADING_3 -> 3
        HEADING_4 -> 4
        HEADING_5 -> 5
        else -> 0
    }
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

/**
 * A grid of cells. The optional lists are either empty (nothing set anywhere) or hold exactly one
 * entry per cell (row-major) or per column; every helper keeps them in that canonical form, so two
 * tables that look the same are equal.
 */
data class NoteDocTable(
    val rows: Int,
    val cols: Int,
    /** Row-major, exactly `rows * cols` entries. */
    val cells: List<String>,
    /** Row-major inline formatting per cell; empty when no cell is formatted. */
    val cellRuns: List<List<NoteDocRun>> = emptyList(),
    /** Row-major cell shading (ARGB); empty when no cell is shaded. */
    val cellFills: List<Long?> = emptyList(),
    /** Column widths in dp, null for automatic; empty when every column is automatic. */
    val colWidths: List<Int?> = emptyList(),
    val headerRow: Boolean = false,
    val headerColumn: Boolean = false,
    val gridLines: Boolean = true,
) {
    fun cell(r: Int, c: Int): String = cells.getOrElse(r * cols + c) { "" }

    fun runsAt(r: Int, c: Int): List<NoteDocRun> = cellRuns.getOrNull(r * cols + c).orEmpty()

    fun fillAt(r: Int, c: Int): Long? = cellFills.getOrNull(r * cols + c)

    fun widthAt(c: Int): Int? = colWidths.getOrNull(c)

    fun isHeader(r: Int, c: Int): Boolean = (headerRow && r == 0) || (headerColumn && c == 0)

    /** New text for one cell; its formatting follows the edit like a text block's runs do. */
    fun withCell(r: Int, c: Int, value: String): NoteDocTable {
        val i = r * cols + c
        if (i !in cells.indices) return this
        val old = cells[i]
        if (old == value) return this
        val (from, removed, inserted) = NoteRuns.diff(old, value)
        return withCellContent(r, c, value, NoteRuns.shiftForEdit(runsAt(r, c), from, removed, inserted, value.length))
    }

    fun withCellContent(r: Int, c: Int, text: String, runs: List<NoteDocRun>): NoteDocTable {
        val i = r * cols + c
        if (i !in cells.indices) return this
        val allRuns = List(rows * cols) { runsAtIndex(it) }.toMutableList()
        allRuns[i] = NoteRuns.normalize(runs, text.length)
        return copy(cells = cells.toMutableList().also { it[i] = text }, cellRuns = allRuns).compact()
    }

    fun withCellFill(r: Int, c: Int, argb: Long?): NoteDocTable {
        val i = r * cols + c
        if (i !in cells.indices) return this
        val fills = List(rows * cols) { cellFills.getOrNull(it) }.toMutableList()
        fills[i] = argb
        return copy(cellFills = fills).compact()
    }

    fun withColumnWidth(c: Int, dp: Int?): NoteDocTable {
        if (c !in 0 until cols) return this
        val widths = List(cols) { colWidths.getOrNull(it) }.toMutableList()
        widths[c] = dp?.coerceIn(MIN_COL_WIDTH, MAX_COL_WIDTH)
        return copy(colWidths = widths).compact()
    }

    /** A blank row inserted before row [index] (clamped; [rows] appends). */
    fun withRowAt(index: Int): NoteDocTable {
        val at = index.coerceIn(0, rows)
        fun <T> insert(list: List<T>, blank: T): List<T> =
            if (list.isEmpty()) list else list.toMutableList().also { it.addAll(at * cols, List(cols) { blank }) }
        val grid = List(rows) { r -> List(cols) { c -> cell(r, c) } }.toMutableList()
        grid.add(at, List(cols) { "" })
        return copy(rows = rows + 1, cells = grid.flatten(),
            cellRuns = insert(cellRuns, emptyList()), cellFills = insert(cellFills, null))
    }

    /** A blank column inserted before column [index] (clamped; [cols] appends). */
    fun withColumnAt(index: Int): NoteDocTable {
        val at = index.coerceIn(0, cols)
        fun <T> insert(list: List<T>, blank: T): List<T> = if (list.isEmpty()) list else List(rows) { r ->
            list.subList(r * cols, r * cols + cols).toMutableList().also { it.add(at, blank) }
        }.flatten()
        return copy(cols = cols + 1, cells = insert(cells, ""),
            cellRuns = insert(cellRuns, emptyList()), cellFills = insert(cellFills, null),
            colWidths = if (colWidths.isEmpty()) colWidths else colWidths.toMutableList().also { it.add(at, null) })
    }

    /** The table without row [index]; the last row is never removed. */
    fun withoutRow(index: Int): NoteDocTable {
        if (rows <= 1 || index !in 0 until rows) return this
        fun <T> drop(list: List<T>): List<T> =
            if (list.isEmpty()) list else list.filterIndexed { i, _ -> i / cols != index }
        return copy(rows = rows - 1, cells = drop(cells), cellRuns = drop(cellRuns), cellFills = drop(cellFills)).compact()
    }

    /** The table without column [index]; the last column is never removed. */
    fun withoutColumn(index: Int): NoteDocTable {
        if (cols <= 1 || index !in 0 until cols) return this
        fun <T> drop(list: List<T>): List<T> =
            if (list.isEmpty()) list else list.filterIndexed { i, _ -> i % cols != index }
        return copy(cols = cols - 1, cells = drop(cells), cellRuns = drop(cellRuns), cellFills = drop(cellFills),
            colWidths = if (colWidths.isEmpty()) colWidths else colWidths.filterIndexed { i, _ -> i != index }).compact()
    }

    private fun runsAtIndex(i: Int): List<NoteDocRun> = cellRuns.getOrNull(i).orEmpty()

    /** Canonical form: an optional list with nothing set in it is stored as empty. */
    fun compact(): NoteDocTable = copy(
        cellRuns = if (cellRuns.all { it.isEmpty() }) emptyList() else cellRuns,
        cellFills = if (cellFills.all { it == null }) emptyList() else cellFills,
        colWidths = if (colWidths.all { it == null }) emptyList() else colWidths,
    )

    companion object {
        const val MAX_ROWS = 50
        const val MAX_COLS = 12
        const val MIN_COL_WIDTH = 64
        const val MAX_COL_WIDTH = 320

        fun blank(rows: Int, cols: Int) = NoteDocTable(rows, cols, List(rows * cols) { "" })
    }
}

/**
 * A YouTube clip embedded in a note: the video and the part of it to play. [endSeconds] null plays
 * to the end of the video. [title] and [channel] are what YouTube reported when the clip was
 * embedded, kept so the card reads well offline; they are never generated.
 */
data class NoteDocVideo(
    val videoId: String,
    val startSeconds: Int = 0,
    val endSeconds: Int? = null,
    val title: String? = null,
    val channel: String? = null,
) {
    val isValid: Boolean
        get() = VIDEO_ID.matches(videoId) && startSeconds >= 0 && (endSeconds == null || endSeconds > startSeconds)

    /** The video's page on YouTube, starting at the clip. */
    val watchUrl: String
        get() = "https://www.youtube.com/watch?v=$videoId" + if (startSeconds > 0) "&t=${startSeconds}s" else ""

    companion object {
        /** YouTube video ids; anything else is never stored or put into a player URL. */
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{6,20}")
        const val MAX_TEXT = 300
    }
}

/**
 * A voice recording embedded in a note. [file] is the app-managed recording (never a picker URI);
 * [peaks] is a short loudness outline, one hex digit per bar, so the card can draw the recording's
 * shape without decoding it.
 */
data class NoteDocAudio(
    val file: String,
    val durationMs: Long,
    val peaks: String = "",
    /** Audio made from text: the start of that text. Null for a microphone recording. */
    val title: String? = null,
) {
    val isValid: Boolean
        get() = FILE.matches(file) && durationMs in 1..MAX_DURATION_MS && PEAKS.matches(peaks) &&
            (title == null || title.length <= MAX_TITLE)

    companion object {
        val FILE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.m4a")
        val PEAKS = Regex("[0-9a-f]{0,64}")
        const val PEAK_COUNT = 48
        const val MAX_DURATION_MS = 6L * 60 * 60 * 1000
        const val MAX_TITLE = 120

        /** A one-line title for audio made from [text]. */
        fun titleFor(text: String): String? {
            val line = text.trim().replace(Regex("\\s+"), " ")
            if (line.isEmpty()) return null
            return if (line.length <= 80) line else line.take(79).trimEnd() + "…"
        }

        /** m:ss, or h:mm:ss from an hour. */
        fun formatDuration(ms: Long): String {
            val total = (ms.coerceAtLeast(0) / 1000).toInt()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            fun two(v: Int) = v.toString().padStart(2, '0')
            return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
        }

        /** The plain-text stand-in where a note is shown as text. */
        fun plainText(audio: NoteDocAudio?): String = when {
            audio == null -> "[Audio]"
            audio.title != null -> "[Audio: ${audio.title} · ${formatDuration(audio.durationMs)}]"
            else -> "[Audio · ${formatDuration(audio.durationMs)}]"
        }

        /** [levels] (0..1, any count) squeezed to [PEAK_COUNT] hex digits. */
        fun encodePeaks(levels: List<Float>): String {
            if (levels.isEmpty()) return ""
            return buildString {
                for (i in 0 until PEAK_COUNT) {
                    val from = i * levels.size / PEAK_COUNT
                    val to = maxOf(from + 1, (i + 1) * levels.size / PEAK_COUNT).coerceAtMost(levels.size)
                    val level = (from until to).maxOf { levels[it] }.coerceIn(0f, 1f)
                    append("0123456789abcdef"[(level * 15f + .5f).toInt()])
                }
            }
        }

        fun decodePeaks(peaks: String): List<Float> = peaks.map { c -> Character.digit(c, 16).coerceAtLeast(0) / 15f }
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
    /** IMAGE only: app-managed file name, never a temporary picker URI. */
    val imagePath: String? = null,
    /** Collapsed toggle or heading section; descendants remain stored intact. */
    val isCollapsed: Boolean = false,
    /** CODE only: true for imported/inserted math, false for explicit code, null for legacy notes. */
    val mathMode: Boolean? = null,
    /**
     * Headings only: a toggle heading always shows its arrow, and [isCollapsed] hides its section
     * (until the next heading of the same or a larger size) even outside heading-fold mode.
     */
    val toggleHeading: Boolean = false,
    /** BULLET / NUMBERED only: a [NoteListStyles] key; null is the default dot / 1. 2. 3. */
    val listStyle: String? = null,
    /** VIDEO only: the embedded clip. */
    val video: NoteDocVideo? = null,
    /** AUDIO only: the recording. */
    val audio: NoteDocAudio? = null,
)

/** Visible document order, shared by the editor and preview. Descendants remain stored intact. */
fun visibleNoteBlocks(blocks: List<NoteDocBlock>, foldHeadings: Boolean = true): List<NoteDocBlock> {
    val visible = ArrayList<NoteDocBlock>(blocks.size)
    val hiddenAt = ArrayDeque<Int>()
    val headings = ArrayDeque<Pair<Int, Boolean>>()
    blocks.forEach { block ->
        val level = block.type.headingLevel
        if (level != 0) while (headings.isNotEmpty() && headings.last().first >= level) headings.removeLast()
        while (hiddenAt.isNotEmpty() && block.indent <= hiddenAt.last()) hiddenAt.removeLast()
        if (hiddenAt.isEmpty() && headings.none { it.second }) visible += block
        if (block.type == NoteDocBlockType.TOGGLE && block.isCollapsed) hiddenAt.addLast(block.indent)
        if (level != 0) headings.addLast(level to ((foldHeadings || block.toggleHeading) && block.isCollapsed))
    }
    return visible
}

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
    /**
     * Format v2 rich tree (ProseMirror JSON) when the note was written by the rich editor. When
     * set it is authoritative and [blocks] is its derived projection.
     */
    val richDocument: String? = null,
    /** Set when the stored document could not be decoded; [blocks] is then empty, not the content. */
    val loadError: String? = null,
    /** The undecodable stored payload, kept verbatim so it is never replaced by an empty note. */
    val damagedRaw: String? = null,
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

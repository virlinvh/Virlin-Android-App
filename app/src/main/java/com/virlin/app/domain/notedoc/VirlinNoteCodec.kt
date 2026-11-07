package com.virlin.app.domain.notedoc

/**
 * Versioned JSON codec for [VirlinNoteDoc] blocks.
 *
 * Hand-rolled, with no third-party or Android JSON dependency, so the exact same code path runs
 * in plain JVM unit tests and in Room storage. It is self-contained on purpose: it does not share
 * a format, a parser or a schema version with the capture note codec.
 *
 * Unknown keys and unknown enum names decode to defaults rather than throwing, so a document
 * written by a later build still opens.
 */
object VirlinNoteCodec {

    const val VERSION = 1

    fun encode(blocks: List<NoteDocBlock>): String = buildString {
        append("{\"v\":").append(VERSION).append(",\"blocks\":[")
        blocks.forEachIndexed { i, b ->
            if (i > 0) append(',')
            encodeBlock(this, b)
        }
        append("]}")
    }

    fun decode(json: String): List<NoteDocBlock> {
        val root = (Json.parse(json) as? Map<*, *>) ?: return emptyList()
        val blocks = root["blocks"] as? List<*> ?: return emptyList()
        return blocks.mapIndexedNotNull { i, raw ->
            val o = raw as? Map<*, *> ?: return@mapIndexedNotNull null
            decodeBlock(o, i)
        }
    }

    private fun encodeBlock(sb: StringBuilder, b: NoteDocBlock) {
        sb.append('{')
        sb.append("\"id\":").append(quote(b.id))
        sb.append(",\"type\":").append(quote(b.type.name))
        if (b.text.isNotEmpty()) sb.append(",\"text\":").append(quote(b.text))
        if (b.align != NoteDocAlign.LEFT) sb.append(",\"align\":").append(quote(b.align.name))
        if (b.indent != 0) sb.append(",\"indent\":").append(b.indent)
        if (b.checked) sb.append(",\"checked\":true")
        if (b.isCollapsed) sb.append(",\"collapsed\":true")
        if (b.toggleHeading) sb.append(",\"toggleHeading\":true")
        b.listStyle?.let { sb.append(",\"listStyle\":").append(quote(it)) }
        b.mathMode?.let { sb.append(",\"mathMode\":").append(it) }
        if (b.lineSpacing != 1f) sb.append(",\"spacing\":").append(b.lineSpacing)
        b.promptTitle?.let { sb.append(",\"promptTitle\":").append(quote(it)) }
        b.imagePath?.let { sb.append(",\"imagePath\":").append(quote(it)) }
        b.video?.let { v ->
            sb.append(",\"video\":{\"id\":").append(quote(v.videoId)).append(",\"start\":").append(v.startSeconds)
            v.endSeconds?.let { sb.append(",\"end\":").append(it) }
            v.title?.let { sb.append(",\"title\":").append(quote(it)) }
            v.channel?.let { sb.append(",\"channel\":").append(quote(it)) }
            sb.append('}')
        }
        b.audio?.let { a ->
            sb.append(",\"audio\":{\"file\":").append(quote(a.file)).append(",\"ms\":").append(a.durationMs)
            if (a.peaks.isNotEmpty()) sb.append(",\"peaks\":").append(quote(a.peaks))
            a.title?.let { sb.append(",\"title\":").append(quote(it)) }
            sb.append('}')
        }
        b.table?.let { t ->
            sb.append(",\"table\":{\"rows\":").append(t.rows)
                .append(",\"cols\":").append(t.cols).append(",\"cells\":[")
            t.cells.forEachIndexed { i, c -> if (i > 0) sb.append(','); sb.append(quote(c)) }
            sb.append(']')
            if (t.cellRuns.isNotEmpty()) {
                sb.append(",\"cellRuns\":[")
                t.cellRuns.forEachIndexed { i, runs -> if (i > 0) sb.append(','); encodeRuns(sb, runs) }
                sb.append(']')
            }
            if (t.cellFills.isNotEmpty()) sb.append(",\"fills\":[")
                .append(t.cellFills.joinToString(",") { it?.toString() ?: "null" }).append(']')
            if (t.colWidths.isNotEmpty()) sb.append(",\"widths\":[")
                .append(t.colWidths.joinToString(",") { it?.toString() ?: "null" }).append(']')
            if (t.headerRow) sb.append(",\"headerRow\":true")
            if (t.headerColumn) sb.append(",\"headerColumn\":true")
            if (!t.gridLines) sb.append(",\"gridLines\":false")
            sb.append('}')
        }
        if (b.runs.isNotEmpty()) {
            sb.append(",\"runs\":")
            encodeRuns(sb, b.runs)
        }
        sb.append('}')
    }

    private fun encodeRuns(sb: StringBuilder, runs: List<NoteDocRun>) {
        sb.append('[')
        runs.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{\"s\":").append(r.start).append(",\"e\":").append(r.end)
            if (r.marks.isNotEmpty()) {
                sb.append(",\"m\":[")
                r.marks.sortedBy { it.name }.forEachIndexed { j, m ->
                    if (j > 0) sb.append(',')
                    sb.append(quote(m.name))
                }
                sb.append(']')
            }
            r.color?.let { sb.append(",\"fg\":").append(it) }
            r.highlight?.let { sb.append(",\"bg\":").append(it) }
            r.fontFamily?.let { sb.append(",\"ff\":").append(quote(it)) }
            r.fontSizeSp?.let { sb.append(",\"fs\":").append(it) }
            r.linkUrl?.let { sb.append(",\"href\":").append(quote(it)) }
            sb.append('}')
        }
        sb.append(']')
    }

    private fun decodeRuns(raw: Any?): List<NoteDocRun> = (raw as? List<*>).orEmpty().mapNotNull { item ->
        val r = item as? Map<*, *> ?: return@mapNotNull null
        val s = num(r["s"])?.toInt() ?: return@mapNotNull null
        val e = num(r["e"])?.toInt() ?: return@mapNotNull null
        NoteDocRun(
            start = s,
            end = e,
            marks = (r["m"] as? List<*>).orEmpty()
                .mapNotNull { m -> enumOrNull<NoteDocMark>(m as? String) }.toSet(),
            color = num(r["fg"])?.toLong(),
            highlight = num(r["bg"])?.toLong(),
            fontFamily = r["ff"] as? String,
            fontSizeSp = num(r["fs"])?.toInt(),
            linkUrl = r["href"] as? String,
        )
    }

    private fun decodeBlock(o: Map<*, *>, index: Int): NoteDocBlock {
        val text = o["text"] as? String ?: ""
        val runs = decodeRuns(o["runs"])
        val table = (o["table"] as? Map<*, *>)?.let { t ->
            val rows = num(t["rows"])?.toInt() ?: 0
            val cols = num(t["cols"])?.toInt() ?: 0
            val cells = (t["cells"] as? List<*>).orEmpty().map { it as? String ?: "" }
            if (rows <= 0 || cols <= 0) return@let null
            val texts = List(rows * cols) { i -> cells.getOrElse(i) { "" } }
            val cellRuns = (t["cellRuns"] as? List<*>).orEmpty()
            val fills = (t["fills"] as? List<*>).orEmpty()
            val widths = (t["widths"] as? List<*>).orEmpty()
            NoteDocTable(
                rows, cols, texts,
                cellRuns = if (cellRuns.isEmpty()) emptyList()
                    else List(rows * cols) { i -> NoteRuns.normalize(decodeRuns(cellRuns.getOrNull(i)), texts[i].length) },
                cellFills = if (fills.isEmpty()) emptyList() else List(rows * cols) { i -> num(fills.getOrNull(i))?.toLong() },
                colWidths = if (widths.isEmpty()) emptyList() else List(cols) { i -> num(widths.getOrNull(i))?.toInt() },
                headerRow = t["headerRow"] == true,
                headerColumn = t["headerColumn"] == true,
                gridLines = t["gridLines"] != false,
            ).compact()
        }
        return NoteDocBlock(
            id = o["id"] as? String ?: "b$index",
            type = enumOrNull<NoteDocBlockType>(o["type"] as? String) ?: NoteDocBlockType.PARAGRAPH,
            text = text,
            runs = NoteRuns.normalize(runs, text.length),
            align = enumOrNull<NoteDocAlign>(o["align"] as? String) ?: NoteDocAlign.LEFT,
            indent = num(o["indent"])?.toInt() ?: 0,
            checked = o["checked"] == true,
            isCollapsed = o["collapsed"] == true,
            toggleHeading = o["toggleHeading"] == true,
            listStyle = o["listStyle"] as? String,
            mathMode = o["mathMode"] as? Boolean,
            lineSpacing = num(o["spacing"])?.toFloat() ?: 1f,
            promptTitle = o["promptTitle"] as? String,
            table = table,
            imagePath = o["imagePath"] as? String,
            video = (o["video"] as? Map<*, *>)?.let { v ->
                NoteDocVideo(
                    videoId = v["id"] as? String ?: "",
                    startSeconds = num(v["start"])?.toInt() ?: 0,
                    endSeconds = num(v["end"])?.toInt(),
                    title = (v["title"] as? String)?.take(NoteDocVideo.MAX_TEXT),
                    channel = (v["channel"] as? String)?.take(NoteDocVideo.MAX_TEXT),
                ).takeIf { it.isValid }
            },
            audio = (o["audio"] as? Map<*, *>)?.let { a ->
                NoteDocAudio(
                    file = a["file"] as? String ?: "",
                    durationMs = num(a["ms"])?.toLong() ?: 0L,
                    peaks = a["peaks"] as? String ?: "",
                    title = (a["title"] as? String)?.take(NoteDocAudio.MAX_TITLE),
                ).takeIf { it.isValid }
            },
        )
    }

    private fun num(v: Any?): Double? = v as? Double

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } }

    private fun quote(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    /** Minimal recursive-descent JSON reader producing Map / List / String / Double / Boolean / null. */
    private object Json {
        fun parse(src: String): Any? = Reader(src).let { r ->
            r.ws()
            val v = runCatching { r.value() }.getOrNull()
            v
        }

        private class Reader(val s: String) {
            var i = 0

            fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

            fun value(): Any? {
                ws()
                if (i >= s.length) return null
                return when (s[i]) {
                    '{' -> obj()
                    '[' -> arr()
                    '"' -> str()
                    't' -> { expect("true"); true }
                    'f' -> { expect("false"); false }
                    'n' -> { expect("null"); null }
                    else -> number()
                }
            }

            fun obj(): Map<String, Any?> {
                i++ // {
                val m = LinkedHashMap<String, Any?>()
                ws()
                if (i < s.length && s[i] == '}') { i++; return m }
                while (i < s.length) {
                    ws()
                    val k = str()
                    ws()
                    if (i < s.length && s[i] == ':') i++
                    m[k] = value()
                    ws()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == '}') { i++; break }
                    break
                }
                return m
            }

            fun arr(): List<Any?> {
                i++ // [
                val l = ArrayList<Any?>()
                ws()
                if (i < s.length && s[i] == ']') { i++; return l }
                while (i < s.length) {
                    l.add(value())
                    ws()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == ']') { i++; break }
                    break
                }
                return l
            }

            fun str(): String {
                if (i >= s.length || s[i] != '"') return ""
                i++
                val sb = StringBuilder()
                while (i < s.length && s[i] != '"') {
                    val c = s[i]
                    if (c == '\\' && i + 1 < s.length) {
                        i++
                        when (val e = s[i]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = s.substring(i + 1, minOf(i + 5, s.length))
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: ' ')
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    } else {
                        sb.append(c)
                    }
                    i++
                }
                i++ // closing quote
                return sb.toString()
            }

            fun number(): Double? {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
                return s.substring(start, i).toDoubleOrNull()
            }

            fun expect(word: String) { if (s.startsWith(word, i)) i += word.length else i++ }
        }
    }
}

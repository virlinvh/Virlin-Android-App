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
        if (b.lineSpacing != 1f) sb.append(",\"spacing\":").append(b.lineSpacing)
        b.promptTitle?.let { sb.append(",\"promptTitle\":").append(quote(it)) }
        b.table?.let { t ->
            sb.append(",\"table\":{\"rows\":").append(t.rows)
                .append(",\"cols\":").append(t.cols).append(",\"cells\":[")
            t.cells.forEachIndexed { i, c -> if (i > 0) sb.append(','); sb.append(quote(c)) }
            sb.append("]}")
        }
        if (b.runs.isNotEmpty()) {
            sb.append(",\"runs\":[")
            b.runs.forEachIndexed { i, r ->
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
        sb.append('}')
    }

    private fun decodeBlock(o: Map<*, *>, index: Int): NoteDocBlock {
        val text = o["text"] as? String ?: ""
        val runs = (o["runs"] as? List<*>).orEmpty().mapNotNull { raw ->
            val r = raw as? Map<*, *> ?: return@mapNotNull null
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
        val table = (o["table"] as? Map<*, *>)?.let { t ->
            val rows = num(t["rows"])?.toInt() ?: 0
            val cols = num(t["cols"])?.toInt() ?: 0
            val cells = (t["cells"] as? List<*>).orEmpty().map { it as? String ?: "" }
            if (rows <= 0 || cols <= 0) null
            else NoteDocTable(rows, cols, List(rows * cols) { i -> cells.getOrElse(i) { "" } })
        }
        return NoteDocBlock(
            id = o["id"] as? String ?: "b$index",
            type = enumOrNull<NoteDocBlockType>(o["type"] as? String) ?: NoteDocBlockType.PARAGRAPH,
            text = text,
            runs = NoteRuns.normalize(runs, text.length),
            align = enumOrNull<NoteDocAlign>(o["align"] as? String) ?: NoteDocAlign.LEFT,
            indent = num(o["indent"])?.toInt() ?: 0,
            checked = o["checked"] == true,
            lineSpacing = num(o["spacing"])?.toFloat() ?: 1f,
            promptTitle = o["promptTitle"] as? String,
            table = table,
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

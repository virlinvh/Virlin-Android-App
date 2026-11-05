package com.virlin.app.domain.mappreview

/**
 * The persisted form of a preview document.
 *
 * The payload is versioned so a later format can be read without a Room migration: adding a mark or
 * an image field changes [VERSION] and the reader, not the schema.
 *
 * JSON is written and read by hand rather than through `org.json`, for the same reason
 * `VirlinNoteCodec` does it: the android.jar stub on the JVM unit-test classpath returns null from
 * every `org.json` call, so a codec built on it cannot be tested without Robolectric. The format
 * here is flat and fully known, so a small reader is enough.
 *
 * Decoding is total — a malformed or truncated payload degrades to an empty preview rather than
 * throwing, because one bad row must never stop a whole map from drawing.
 */
object MapPreviewCodec {

    const val VERSION = 1

    fun encode(preview: MapNodePreview): String {
        val description = preview.description.normalized()
        val runs = description.runs.joinToString(",") { run ->
            val marks = run.marks.joinToString(",") { quote(it.name) }
            """{"start":${run.start},"end":${run.end},"marks":[$marks]}"""
        }
        val images = preview.images.joinToString(",") { image ->
            """{"id":${quote(image.id)},"path":${quote(image.relativePath)},""" +
                """"w":${image.width},"h":${image.height},"bytes":${image.sizeBytes}}"""
        }
        return """{"version":$VERSION,"text":${quote(description.text)},""" +
            """"runs":[$runs],"images":[$images],"expanded":${preview.expanded}}"""
    }

    fun decode(ownerId: String, json: String): MapNodePreview = runCatching {
        val root = Json.parse(json) as? Map<*, *> ?: return@runCatching MapNodePreview(ownerId)

        val runs = ArrayList<FormatRun>()
        (root["runs"] as? List<*>).orEmpty().forEach { entry ->
            val item = entry as? Map<*, *> ?: return@forEach
            val marks = (item["marks"] as? List<*>).orEmpty().mapNotNull { name ->
                // An unknown mark written by a newer build is dropped, not fatal.
                runCatching { FormatMark.valueOf(name as String) }.getOrNull()
            }.toSet()
            if (marks.isEmpty()) return@forEach
            runs += FormatRun(item.int("start"), item.int("end"), marks)
        }

        val images = ArrayList<MapPreviewImage>()
        (root["images"] as? List<*>).orEmpty().forEach { entry ->
            val item = entry as? Map<*, *> ?: return@forEach
            val id = item["id"] as? String ?: return@forEach
            val path = item["path"] as? String ?: return@forEach
            if (id.isBlank() || path.isBlank()) return@forEach
            images += MapPreviewImage(
                id = id,
                relativePath = path,
                width = item.int("w"),
                height = item.int("h"),
                sizeBytes = item.int("bytes").toLong(),
            )
        }

        MapNodePreview(
            ownerId = ownerId,
            description = FormattedText(root["text"] as? String ?: "", runs).normalized(),
            // The cap is re-applied on read: a row written by a future build with a larger limit
            // must not silently exceed this build's contract.
            images = images.take(MapNodePreview.MAX_IMAGES),
            expanded = root["expanded"] as? Boolean ?: false,
        )
    }.getOrElse { MapNodePreview(ownerId = ownerId) }

    private fun Map<*, *>.int(key: String): Int = when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** Minimal recursive-descent reader producing Map / List / String / Double / Boolean / null. */
    private object Json {
        fun parse(src: String): Any? = Reader(src).let { r ->
            r.ws()
            runCatching { r.value() }.getOrNull()
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
                i++
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
                i++
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
                if (i < s.length) i++
                return sb.toString()
            }

            fun number(): Any? {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
                return s.substring(start, i).toDoubleOrNull()
            }

            fun expect(word: String) {
                if (s.startsWith(word, i)) i += word.length else i = s.length
            }
        }
    }
}

package com.virlin.app.domain.notedoc.rich

/**
 * Strict JSON for rich note trees. Unlike the lenient v1 reader, malformed input throws, so a
 * damaged stored note is reported instead of being mistaken for an empty one.
 *
 * Values: [Map] (insertion-ordered), [List], [String], [Long] (integral numbers), [Double],
 * [Boolean], or null.
 */
object RichJson {

    class MalformedJson(message: String) : IllegalArgumentException(message)

    fun parse(source: String): Any? {
        val reader = Reader(source)
        reader.ws()
        val value = reader.value()
        reader.ws()
        if (reader.i != source.length) reader.fail("Unexpected trailing content")
        return value
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value.toString())
            is Float, is Double -> {
                val d = (value as Number).toDouble()
                require(d.isFinite()) { "Non-finite number" }
                if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) out.append(d.toLong()) else out.append(d)
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                value.forEach { (k, v) ->
                    if (!first) out.append(',')
                    first = false
                    quote(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v -> if (i > 0) out.append(','); write(out, v) }
                out.append(']')
            }
            else -> quote(out, value.toString())
        }
    }

    fun quote(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' || c == '\u2028' || c == '\u2029' ->
                    out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private class Reader(val s: String) {
        var i = 0

        fun fail(message: String): Nothing = throw MalformedJson("$message at offset $i")

        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }

        fun value(): Any? {
            ws()
            if (i >= s.length) fail("Unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Invalid literal")
            i += word.length
            return result
        }

        fun obj(): Map<String, Any?> {
            i++
            val map = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("Expected key")
                val key = str()
                ws()
                if (i >= s.length || s[i] != ':') fail("Expected ':'")
                i++
                map[key] = value()
                ws()
                if (i >= s.length) fail("Unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        fun arr(): List<Any?> {
            i++
            val list = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list.add(value())
                ws()
                if (i >= s.length) fail("Unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        if (i + 1 >= s.length) fail("Bad escape")
                        i++
                        when (val e = s[i]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) fail("Bad unicode escape")
                                val code = s.substring(i + 1, i + 5).toIntOrNull(16) ?: fail("Bad unicode escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("Bad escape '$e'")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        fun number(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val text = s.substring(start, i)
            if (text.isEmpty()) fail("Unexpected character")
            if (text.none { it == '.' || it == 'e' || it == 'E' }) text.toLongOrNull()?.let { return it }
            return text.toDoubleOrNull() ?: fail("Bad number")
        }
    }
}

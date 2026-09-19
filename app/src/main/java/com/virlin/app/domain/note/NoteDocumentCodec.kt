package com.virlin.app.domain.note

import com.virlin.app.domain.model.InlineStyle
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.NoteDocument
import com.virlin.app.domain.model.TextMark
import java.time.Instant

/**
 * Deterministic JSON codec for [NoteDocument] block payloads. No third-party JSON dependency —
 * keeps JVM unit tests and Room storage aligned. Schema version [VERSION] is written as `v`.
 */
object NoteDocumentCodec {
    const val VERSION = 1

    fun encodePayload(blocks: List<NoteBlock>): String = buildString {
        append("{\"v\":").append(VERSION).append(",\"blocks\":")
        appendBlocks(blocks)
        append('}')
    }

    fun decodePayload(json: String): List<NoteBlock> {
        val root = JsonObj.parse(json)
        val arr = root.arr("blocks") ?: return emptyList()
        return arr.map { decodeBlock(it.asObj()) }
    }

    fun encodeDocument(doc: NoteDocument): String = encodePayload(doc.blocks)

    fun decodeInto(docMeta: NoteDocumentMeta, payload: String): NoteDocument = NoteDocument(
        id = docMeta.id,
        captureItemId = docMeta.captureItemId,
        title = docMeta.title,
        blocks = decodePayload(payload),
        createdAt = docMeta.createdAt,
        updatedAt = docMeta.updatedAt
    )

    data class NoteDocumentMeta(
        val id: String,
        val captureItemId: String,
        val title: String?,
        val createdAt: Instant,
        val updatedAt: Instant
    )

    private fun StringBuilder.appendBlocks(blocks: List<NoteBlock>) {
        append('[')
        blocks.forEachIndexed { i, b ->
            if (i > 0) append(',')
            appendBlock(b)
        }
        append(']')
    }

    private fun StringBuilder.appendBlock(b: NoteBlock) {
        append('{')
        appendKv("id", b.id); append(',')
        appendKv("type", b.type.name); append(',')
        appendKv("text", b.plainText); append(',')
        append("\"checked\":").append(b.checked).append(',')
        append("\"collapsed\":").append(b.collapsed).append(',')
        append("\"marks\":[")
        b.marks.forEachIndexed { i, m ->
            if (i > 0) append(',')
            append('{')
            append("\"s\":").append(m.start).append(',')
            append("\"e\":").append(m.end).append(',')
            appendKv("k", m.style.name)
            m.linkUrl?.let { append(','); appendKv("u", it) }
            append('}')
        }
        append("],\"children\":")
        appendBlocks(b.children)
        append('}')
    }

    private fun StringBuilder.appendKv(key: String, value: String) {
        append('"').append(key).append("\":"); appendJsonString(value)
    }

    private fun StringBuilder.appendJsonString(s: String) {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private fun decodeBlock(o: JsonObj): NoteBlock {
        val marks = o.arr("marks")?.map { m ->
            val mo = m.asObj()
            TextMark(
                start = mo.int("s"),
                end = mo.int("e"),
                style = InlineStyle.valueOf(mo.str("k")),
                linkUrl = mo.strOrNull("u")
            )
        }.orEmpty()
        val children = o.arr("children")?.map { decodeBlock(it.asObj()) }.orEmpty()
        return NoteBlock(
            id = o.str("id"),
            type = NoteBlockType.valueOf(o.str("type")),
            plainText = o.strOrNull("text").orEmpty(),
            marks = marks,
            checked = o.bool("checked"),
            collapsed = o.bool("collapsed"),
            children = children
        )
    }
}

/** Tiny JSON subset parser sufficient for our codec output. */
internal sealed class JsonVal {
    abstract fun asObj(): JsonObj
    data class Obj(val o: JsonObj) : JsonVal() { override fun asObj() = o }
    data class Arr(val a: List<JsonVal>) : JsonVal() { override fun asObj() = error("not object") }
    data class Str(val s: String) : JsonVal() { override fun asObj() = error("not object") }
    data class Num(val n: Long) : JsonVal() { override fun asObj() = error("not object") }
    data class Bool(val b: Boolean) : JsonVal() { override fun asObj() = error("not object") }
    data object Null : JsonVal() { override fun asObj() = error("not object") }
}

internal class JsonObj(private val map: Map<String, JsonVal>) {
    fun str(key: String): String = (map[key] as? JsonVal.Str)?.s ?: error("missing string $key")
    fun strOrNull(key: String): String? = when (val v = map[key]) {
        is JsonVal.Str -> v.s
        JsonVal.Null, null -> null
        else -> error("not string $key")
    }
    fun int(key: String): Int = (map[key] as? JsonVal.Num)?.n?.toInt() ?: 0
    fun long(key: String): Long = (map[key] as? JsonVal.Num)?.n ?: 0L
    fun bool(key: String): Boolean = (map[key] as? JsonVal.Bool)?.b ?: false
    fun arr(key: String): List<JsonVal>? = (map[key] as? JsonVal.Arr)?.a

    companion object {
        fun parse(json: String): JsonObj = (JsonParser(json).parseValue() as JsonVal.Obj).o
    }
}

private class JsonParser(private val s: String) {
    private var i = 0

    fun parseValue(): JsonVal {
        skip()
        return when (val c = peek()) {
            '{' -> parseObj()
            '[' -> parseArr()
            '"' -> JsonVal.Str(parseString())
            't', 'f' -> parseBool()
            'n' -> { expect("null"); JsonVal.Null }
            '-', in '0'..'9' -> parseNum()
            else -> error("unexpected '$c' at $i")
        }
    }

    private fun parseObj(): JsonVal.Obj {
        expect('{'); skip()
        val map = linkedMapOf<String, JsonVal>()
        if (peek() == '}') { i++; return JsonVal.Obj(JsonObj(map)) }
        while (true) {
            skip(); val key = parseString(); skip(); expect(':'); val v = parseValue()
            map[key] = v; skip()
            when (peek()) {
                ',' -> { i++; continue }
                '}' -> { i++; break }
                else -> error("expected , or } at $i")
            }
        }
        return JsonVal.Obj(JsonObj(map))
    }

    private fun parseArr(): JsonVal.Arr {
        expect('['); skip()
        if (peek() == ']') { i++; return JsonVal.Arr(emptyList()) }
        val out = mutableListOf<JsonVal>()
        while (true) {
            out += parseValue(); skip()
            when (peek()) {
                ',' -> { i++; continue }
                ']' -> { i++; break }
                else -> error("expected , or ] at $i")
            }
        }
        return JsonVal.Arr(out)
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            when (val c = s[i++]) {
                '"' -> return sb.toString()
                '\\' -> when (val e = s[i++]) {
                    '"', '\\', '/' -> sb.append(e)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = s.substring(i, i + 4); i += 4
                        sb.append(hex.toInt(16).toChar())
                    }
                    else -> error("bad escape")
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseBool(): JsonVal.Bool {
        if (s.startsWith("true", i)) { i += 4; return JsonVal.Bool(true) }
        if (s.startsWith("false", i)) { i += 5; return JsonVal.Bool(false) }
        error("bad bool at $i")
    }

    private fun parseNum(): JsonVal.Num {
        val start = i
        if (peek() == '-') i++
        while (peek() in '0'..'9') i++
        return JsonVal.Num(s.substring(start, i).toLong())
    }

    private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'
    private fun expect(c: Char) { skip(); if (peek() != c) error("expected $c at $i"); i++ }
    private fun expect(lit: String) {
        skip()
        if (!s.startsWith(lit, i)) error("expected $lit at $i")
        i += lit.length
    }
}

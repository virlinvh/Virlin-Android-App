package com.virlin.app.domain.prompt

import com.virlin.app.domain.model.PromptDocument
import com.virlin.app.domain.model.PromptContentMode
import com.virlin.app.domain.note.NoteDocumentCodec
import com.virlin.app.domain.note.NotePlainTextSerializer
import com.virlin.app.domain.note.JsonObj
import java.time.Instant

/**
 * Codec helpers for [PromptDocument]: block payload via [NoteDocumentCodec]; tags as a JSON string array.
 */
object PromptDocumentCodec {

    fun encodeTags(tags: List<String>): String = buildString {
        append('[')
        tags.map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, t ->
            if (i > 0) append(',')
            append('"')
            append(t.replace("\\", "\\\\").replace("\"", "\\\""))
            append('"')
        }
        append(']')
    }

    fun decodeTags(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        val out = mutableListOf<String>()
        var i = 0
        while (i < json.length) {
            if (json[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < json.length) {
                    when (val c = json[i]) {
                        '\\' -> {
                            if (i + 1 < json.length) {
                                sb.append(json[i + 1]); i += 2
                            } else i++
                        }
                        '"' -> { i++; break }
                        else -> { sb.append(c); i++ }
                    }
                }
                val t = sb.toString().trim()
                if (t.isNotEmpty()) out += t
            } else i++
        }
        return out
    }

    /**
     * Version 2 extends the existing block payload in-place. Old readers still see `blocks`;
     * new readers recover exact source/response text. No Room schema migration is required.
     */
    fun encodeDocument(doc: PromptDocument): String {
        val base = NoteDocumentCodec.encodePayload(doc.blocks).dropLast(1)
        val exactSource = doc.sourceText.ifEmpty { NotePlainTextSerializer.serializeBlocks(doc.blocks) }
        return buildString {
            append(base)
            append(",\"promptV\":2")
            append(",\"source\":"); appendJsonString(exactSource)
            append(",\"mode\":"); appendJsonString(doc.mode.name)
            append(",\"language\":")
            if (doc.language == null) append("null") else appendJsonString(doc.language)
            append(",\"response\":")
            if (doc.responseText == null) append("null") else appendJsonString(doc.responseText)
            append('}')
        }
    }

    fun encodeBlocks(blocks: List<com.virlin.app.domain.model.NoteBlock>): String =
        NoteDocumentCodec.encodePayload(blocks)

    fun decodeBlocks(json: String) = NoteDocumentCodec.decodePayload(json)

    data class Meta(
        val id: String,
        val captureItemId: String,
        val title: String?,
        val description: String?,
        val tagsJson: String?,
        val createdAt: Instant,
        val updatedAt: Instant
    )

    fun decodeInto(meta: Meta, documentJson: String): PromptDocument {
        val root = runCatching { JsonObj.parse(documentJson) }.getOrNull()
        val blocks = decodeBlocks(documentJson)
        val storedSource = root?.strOrNull("source")
        val legacySource = NotePlainTextSerializer.serializeBlocks(blocks)
        return PromptDocument(
            id = meta.id,
            captureItemId = meta.captureItemId,
            title = meta.title,
            description = meta.description,
            tags = decodeTags(meta.tagsJson),
            blocks = blocks,
            sourceText = storedSource ?: legacySource,
            mode = root?.strOrNull("mode")?.let { runCatching { PromptContentMode.valueOf(it) }.getOrNull() }
                ?: PromptContentMode.PROMPT,
            language = root?.strOrNull("language"),
            responseText = root?.strOrNull("response"),
            createdAt = meta.createdAt,
            updatedAt = meta.updatedAt
        )
    }

    /** Plain-text projection for Copy / Inbox preview (title + body). */
    fun plainText(doc: PromptDocument): String {
        if (doc.sourceText.isNotEmpty()) return doc.sourceText
        val body = NotePlainTextSerializer.serialize(
            com.virlin.app.domain.model.NoteDocument(
                id = doc.id,
                captureItemId = doc.captureItemId,
                title = doc.title,
                blocks = doc.blocks,
                createdAt = doc.createdAt,
                updatedAt = doc.updatedAt
            ),
            includeTitle = true
        )
        val desc = doc.description?.trim().orEmpty()
        return when {
            desc.isEmpty() -> body
            body.isEmpty() -> desc
            else -> "$desc\n\n$body"
        }
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    fun preview(doc: PromptDocument, maxChars: Int = 160): String {
        val full = plainText(doc)
        if (full.isBlank()) return ""
        return if (full.length <= maxChars) full else full.take(maxChars - 1).trimEnd() + "…"
    }
}

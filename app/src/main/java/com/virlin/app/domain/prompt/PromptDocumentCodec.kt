package com.virlin.app.domain.prompt

import com.virlin.app.domain.model.PromptDocument
import com.virlin.app.domain.note.NoteDocumentCodec
import com.virlin.app.domain.note.NotePlainTextSerializer
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

    fun decodeInto(meta: Meta, documentJson: String): PromptDocument = PromptDocument(
        id = meta.id,
        captureItemId = meta.captureItemId,
        title = meta.title,
        description = meta.description,
        tags = decodeTags(meta.tagsJson),
        blocks = decodeBlocks(documentJson),
        createdAt = meta.createdAt,
        updatedAt = meta.updatedAt
    )

    /** Plain-text projection for Copy / Inbox preview (title + body). */
    fun plainText(doc: PromptDocument): String {
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

    fun preview(doc: PromptDocument, maxChars: Int = 160): String {
        val full = plainText(doc)
        if (full.isBlank()) return ""
        return if (full.length <= maxChars) full else full.take(maxChars - 1).trimEnd() + "…"
    }
}

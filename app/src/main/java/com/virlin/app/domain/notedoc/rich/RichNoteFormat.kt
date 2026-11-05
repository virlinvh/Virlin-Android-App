package com.virlin.app.domain.notedoc.rich

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.VirlinNoteCodec

/**
 * Stored note document formats.
 *
 * - v1 `{"v":1,"blocks":[...]}`: the flat block list, still written for notes never opened in
 *   the rich editor.
 * - v2 `{"v":2,"doc":<rich tree>,"blocks":[...]}`: the rich tree is authoritative; `blocks` is a
 *   derived projection kept so v1 readers (older builds, exports) still see the content.
 *
 * Decoding is strict: anything that does not parse is [Stored.Damaged], never an empty note,
 * so a save can never silently replace content that failed to load.
 */
object RichNoteFormat {

    const val VERSION = 2
    private const val V1_PREFIX = "{\"v\":1,\"blocks\":"

    sealed interface Stored {
        val blocks: List<NoteDocBlock>

        data class Legacy(override val blocks: List<NoteDocBlock>) : Stored
        data class Rich(val document: String, override val blocks: List<NoteDocBlock>) : Stored
        data class Damaged(val raw: String, val reason: String) : Stored {
            override val blocks: List<NoteDocBlock> get() = emptyList()
        }
    }

    fun decode(json: String): Stored {
        if (json.isBlank()) return Stored.Damaged(json, "The stored note document is empty.")
        val root = try {
            RichJson.parse(json)
        } catch (e: RichJson.MalformedJson) {
            return Stored.Damaged(json, "The stored note document is not valid JSON (${e.message}).")
        } as? Map<*, *> ?: return Stored.Damaged(json, "The stored note document is not an object.")
        val version = (root["v"] as? Number)?.toInt()
        return when {
            version == VERSION -> {
                val doc = root["doc"] as? Map<*, *>
                    ?: return Stored.Damaged(json, "The rich note has no document tree.")
                try {
                    val view = RichNoteAdapter.parseDocument(RichJson.stringify(doc))
                    Stored.Rich(RichJson.stringify(view.node), RichNoteAdapter.project(view))
                } catch (e: RichJson.MalformedJson) {
                    Stored.Damaged(json, "The rich note tree is invalid (${e.message}).")
                }
            }
            version == null || version == VirlinNoteCodec.VERSION -> {
                if (root["blocks"] !is List<*>) Stored.Damaged(json, "The note has no block list.")
                else Stored.Legacy(VirlinNoteCodec.decode(json))
            }
            else -> Stored.Damaged(json, "The note was written by a newer Virlin version (format v$version).")
        }
    }

    /** Splits stored JSON into chunks of at most [size] chars without splitting a surrogate pair. */
    fun chunks(json: String, size: Int): List<String> {
        require(size >= 2)
        val out = ArrayList<String>(json.length / size + 1)
        var start = 0
        while (start < json.length) {
            var end = minOf(start + size, json.length)
            if (end < json.length && Character.isHighSurrogate(json[end - 1])) end--
            out += json.substring(start, end)
            start = end
        }
        return out
    }

    /**
     * Encodes a note. With a [richDocument] the stored blocks are always re-projected from the
     * tree, so the projection can never drift from the authoritative document.
     */
    fun encode(blocks: List<NoteDocBlock>, richDocument: String?): String {
        if (richDocument == null) return VirlinNoteCodec.encode(blocks)
        val view = RichNoteAdapter.parseDocument(richDocument)
        val projection = VirlinNoteCodec.encode(RichNoteAdapter.project(view))
        check(projection.startsWith(V1_PREFIX) && projection.endsWith("}"))
        return buildString(richDocument.length + projection.length + 32) {
            append("{\"v\":").append(VERSION).append(",\"doc\":")
            RichJson.write(this, view.node)
            append(",\"blocks\":")
            append(projection, V1_PREFIX.length, projection.length - 1)
            append('}')
        }
    }
}

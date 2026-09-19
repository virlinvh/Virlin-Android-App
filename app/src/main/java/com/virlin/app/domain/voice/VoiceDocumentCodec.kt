package com.virlin.app.domain.voice

import com.virlin.app.domain.model.VoiceClip
import com.virlin.app.domain.model.VoiceDocument
import com.virlin.app.domain.note.JsonObj
import java.time.Instant

/**
 * Codec for VoiceDocument clip lists stored in Room as JSON.
 * Hand-rolled like [com.virlin.app.domain.note.NoteDocumentCodec] so JVM unit tests
 * do not depend on Android org.json stubs.
 */
object VoiceDocumentCodec {

    data class Meta(
        val id: String,
        val captureItemId: String,
        val title: String?,
        val createdAt: Instant,
        val updatedAt: Instant
    )

    fun encodeClips(clips: List<VoiceClip>): String = buildString {
        append('[')
        clips.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append('{')
            appendKv("id", c.id); append(',')
            appendKv("displayName", c.displayName); append(',')
            appendKv("relativePath", c.relativePath); append(',')
            append("\"durationMs\":").append(c.durationMs).append(',')
            append("\"sizeBytes\":").append(c.sizeBytes).append(',')
            appendKv("mimeType", c.mimeType); append(',')
            append("\"sortOrder\":").append(c.sortOrder).append(',')
            append("\"createdAt\":").append(c.createdAt.toEpochMilli())
            append('}')
        }
        append(']')
    }

    fun decodeClips(json: String): List<VoiceClip> {
        if (json.isBlank() || json == "[]") return emptyList()
        val wrapped = JsonObj.parse("{\"clips\":$json}")
        val arr = wrapped.arr("clips") ?: return emptyList()
        return arr.mapIndexed { i, v ->
            val o = v.asObj()
            VoiceClip(
                id = o.str("id"),
                displayName = o.strOrNull("displayName") ?: "Voice",
                relativePath = o.str("relativePath"),
                durationMs = o.long("durationMs"),
                sizeBytes = o.long("sizeBytes"),
                mimeType = o.strOrNull("mimeType") ?: "audio/mp4",
                sortOrder = if (o.strOrNull("id") != null) o.int("sortOrder") else i,
                createdAt = Instant.ofEpochMilli(o.long("createdAt"))
            )
        }
    }

    fun decodeInto(meta: Meta, clipsJson: String): VoiceDocument = VoiceDocument(
        id = meta.id,
        captureItemId = meta.captureItemId,
        title = meta.title,
        clips = decodeClips(clipsJson),
        createdAt = meta.createdAt,
        updatedAt = meta.updatedAt
    )

    fun preview(doc: VoiceDocument): String {
        val title = doc.title?.takeIf { it.isNotBlank() }
        val n = doc.clips.size
        val dur = doc.clips.sumOf { it.durationMs }
        val time = formatDuration(dur)
        return when {
            title != null && n > 0 -> "$title · $n recording${if (n == 1) "" else "s"} · $time"
            n > 0 -> "$n recording${if (n == 1) "" else "s"} · $time"
            title != null -> title
            else -> "Voice note"
        }
    }

    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0).toInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    private fun StringBuilder.appendKv(key: String, value: String) {
        append('"').append(key).append("\":")
        appendJsonString(value)
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
}

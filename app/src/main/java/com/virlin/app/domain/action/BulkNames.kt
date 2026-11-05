package com.virlin.app.domain.action

import android.util.JsonReader
import android.util.JsonToken

/** Bounded, name-only v1. Ownership comes from the screen, never from pasted IDs. */
object BulkNames {
    fun parse(source: String): List<String> {
        require(source.length <= 50_000) { "Input is too large (maximum 50,000 characters)." }
        val names = mutableListOf<String>()
        JsonReader(source.reader()).use { reader ->
            reader.isLenient = false
            val fields = mutableSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName()
                require(fields.add(field)) { "Repeated field: $field." }
                when (field) {
                    "version" -> {
                        require(reader.peek() == JsonToken.NUMBER && reader.nextString() == "1") { "Use version 1." }
                    }
                    "items" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            require(names.size < 100) { "Maximum 100 items." }
                            reader.beginObject()
                            require(reader.hasNext() && reader.nextName() == "name") { "Each item must contain only name." }
                            require(reader.peek() == JsonToken.STRING) { "Names must be strings." }
                            val name = reader.nextString().trim()
                            require(name.isNotBlank() && name.length <= 200 && name.none { it == '\n' || it == '\r' }) { "Provide a name of 1–200 characters on one line." }
                            require(!reader.hasNext()) { "Only name is allowed. Nested children are not supported yet." }
                            reader.endObject()
                            names += name
                        }
                        reader.endArray()
                    }
                    else -> error("Only version and items are allowed.")
                }
            }
            reader.endObject()
            require(fields == setOf("version", "items") && names.isNotEmpty()) { "Provide version and 1–100 items." }
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Remove text after the JSON object." }
        }
        return names.also {
            require(names.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == names.size) { "Remove duplicate names from this batch." }
        }
    }

    fun prompt(type: String) = """
        Convert the information I provide into names for new $type.
        Return only valid JSON, without Markdown fences or commentary, using this exact format:
        {"version":1,"items":[{"name":"First item"},{"name":"Second item"}]}
        Rules: 1–100 items; each name 1–200 characters on one line; no duplicate names.
        Do not include IDs, children, metadata, or any other fields. Preserve my meaning.
        The destination is selected in Virlin, not in the JSON. Do not invent extra items.
        Information to convert:
        [Paste my information here]
    """.trimIndent()
}

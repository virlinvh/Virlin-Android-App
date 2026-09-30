package com.virlin.app.domain.prompt

sealed interface ResponseSegment {
    val text: String
    data class Prose(override val text: String) : ResponseSegment
    data class Code(override val text: String, val language: String?) : ResponseSegment
}

/** Deterministic display parser. It never rewrites the stored response string. */
object PromptContentParser {
    private val fenced = Regex("```([A-Za-z0-9_+.#-]*)[ \\t]*\\r?\\n([\\s\\S]*?)```", RegexOption.MULTILINE)

    fun responseSegments(source: String): List<ResponseSegment> {
        if (source.isEmpty()) return emptyList()
        val out = mutableListOf<ResponseSegment>()
        var cursor = 0
        fenced.findAll(source).forEach { match ->
            if (match.range.first > cursor) {
                source.substring(cursor, match.range.first).trim('\n', '\r')
                    .takeIf { it.isNotBlank() }?.let { out += ResponseSegment.Prose(it) }
            }
            out += ResponseSegment.Code(
                text = match.groupValues[2].trimEnd('\n', '\r'),
                language = match.groupValues[1].takeIf { it.isNotBlank() }?.let(::canonicalLanguage)
                    ?: detectLanguage(match.groupValues[2]).takeUnless { it == "Plain text" }
            )
            cursor = match.range.last + 1
        }
        if (cursor < source.length) {
            source.substring(cursor).trim('\n', '\r')
                .takeIf { it.isNotBlank() }?.let { out += ResponseSegment.Prose(it) }
        }
        return out.ifEmpty { listOf(ResponseSegment.Prose(source)) }
    }

    fun detectLanguage(source: String): String {
        val s = source.trim()
        if (s.isEmpty()) return "Plain text"
        return when {
            Regex("(?m)^\\s*(package|import)\\s+[\\w.]+|\\b(fun|val|var)\\s+\\w+").containsMatchIn(s) -> "Kotlin"
            Regex("(?m)^\\s*(interface|type)\\s+\\w+|\\b(const|let)\\s+\\w+|=>").containsMatchIn(s) -> "TypeScript"
            Regex("(?m)^\\s*(def|from|import)\\s+|:\\s*$|\\bprint\\(").containsMatchIn(s) -> "Python"
            Regex("(?m)^\\s*(public|private|protected)?\\s*(class|interface)\\s+|System\\.out\\.").containsMatchIn(s) -> "Java"
            Regex("(?m)^\\s*(SELECT|INSERT|UPDATE|DELETE|CREATE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "SQL"
            s.startsWith("{") || s.startsWith("[") && Regex("\"[^\"]+\"\\s*:").containsMatchIn(s) -> "JSON"
            Regex("</?[A-Za-z][^>]*>").containsMatchIn(s) -> "HTML"
            else -> "Plain text"
        }
    }

    private fun canonicalLanguage(value: String): String = when (value.lowercase()) {
        "kt", "kotlin" -> "Kotlin"
        "java" -> "Java"
        "ts", "typescript" -> "TypeScript"
        "js", "javascript" -> "JavaScript"
        "py", "python" -> "Python"
        "sql" -> "SQL"
        "json" -> "JSON"
        "html" -> "HTML"
        "css" -> "CSS"
        "sh", "bash", "shell" -> "Shell"
        else -> value
    }
}

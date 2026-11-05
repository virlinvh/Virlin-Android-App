package com.virlin.app.domain.notedoc

/** Detects pasted TeX without changing the stored, editable source. */
object NoteMath {
    private val texCommand = Regex("\\\\(?:frac|dfrac|tfrac|sqrt|sum|int|lim|begin|alpha|beta|theta|pi|times|cdot|left|right)\\b")
    private val mathOperators = Regex("[=+−×÷<>]")
    // Android's ICU regex engine rejects the old character class containing a
    // literal closing brace, although the desktop/JVM engine accepted it.
    private val superscript = Regex("\\^\\s*(?:\\{|[A-Za-z0-9])")
    private val subscript = Regex("[A-Za-z]_[0-9]+")
    private val assignment = Regex("^\\s*[A-Za-z]{1,2}\\s*=")

    fun isEquation(block: NoteDocBlock): Boolean =
        block.type == NoteDocBlockType.CODE && block.text.isNotBlank() &&
            (block.mathMode ?: isEquation(block.text))

    fun isEquation(source: String): Boolean {
        val text = source.trim()
        if (text.isEmpty()) return false
        // Configuration, URLs, and source-code assignments are not math. In particular,
        // underscores in ENV_VAR names must never be interpreted as TeX subscripts.
        if (Regex("(?m)^\\s*[A-Z][A-Z0-9_]{2,}\\s*=").containsMatchIn(text) ||
            "://" in text) return false
        if ((text.startsWith("$$") && text.endsWith("$$")) ||
            (text.startsWith("\\[") && text.endsWith("\\]")) ||
            (text.startsWith("\\(") && text.endsWith("\\)"))) return true
        if (texCommand.containsMatchIn(text)) return true
        return text.lines().filter(String::isNotBlank).all { line ->
            mathOperators.containsMatchIn(line) &&
                (superscript.containsMatchIn(line) || subscript.containsMatchIn(line) ||
                    "_{" in line ||
                    assignment.containsMatchIn(line))
        }
    }

    fun renderSource(source: String): String {
        val trimmed = source.trim()
        val content = when {
            trimmed.startsWith("$$") && trimmed.endsWith("$$") -> trimmed.substring(2, trimmed.length - 2)
            trimmed.startsWith("\\[") && trimmed.endsWith("\\]") -> trimmed.substring(2, trimmed.length - 2)
            trimmed.startsWith("\\(") && trimmed.endsWith("\\)") -> trimmed.substring(2, trimmed.length - 2)
            else -> trimmed
        }.trim()
        // Clipboard MathML often supplies one annotation per adjacent expression. The
        // importer stores those annotations on separate lines, but they were one visual
        // row in the source. Preserve explicit TeX environments and row breaks only.
        val lines = content.lines().map(String::trim).filter(String::isNotEmpty)
        return if ("\\begin" in content || "\\\\" in content) content
        else lines.joinToString(" \\; ")
    }
}

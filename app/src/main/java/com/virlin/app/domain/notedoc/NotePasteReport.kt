package com.virlin.app.domain.notedoc

/**
 * A plain-language account of what the last paste carried and what the note made of it, so a
 * missing picture or diagram can be traced to its source. Only counts and MIME types are kept:
 * never the pasted content itself.
 */
object NotePasteReport {
    /** What the clipboard offered besides the text the keyboard inserted. */
    data class ClipMeta(val items: Int, val mimeTypes: List<String>, val imageItems: Int)

    private val IMG = Regex("<img\\b", RegexOption.IGNORE_CASE)
    private val SVG = Regex("<svg\\b", RegexOption.IGNORE_CASE)
    private val CANVAS = Regex("<canvas\\b", RegexOption.IGNORE_CASE)
    private val EMBEDDED = Regex("src\\s*=\\s*[\"']?data:image/", RegexOption.IGNORE_CASE)
    private val MARKDOWN_IMAGE = Regex("!\\[[^\\]\\n]*]\\([^)\\s]+\\)")
    private val FENCE = Regex("^\\s*(```|~~~)\\s*([A-Za-z0-9_+-]*)", RegexOption.MULTILINE)
    private val DIAGRAM_LANGUAGES = setOf("mermaid", "plantuml", "dot", "graphviz", "flowchart")

    fun describe(meta: ClipMeta?, plain: String, html: String?, htmlUsed: Boolean,
        blocks: List<NoteDocBlock>): String {
        val img = html?.let { IMG.findAll(it).count() } ?: 0
        val svg = html?.let { SVG.findAll(it).count() } ?: 0
        val canvas = html?.let { CANVAS.findAll(it).count() } ?: 0
        val embedded = html?.let { EMBEDDED.findAll(it).count() } ?: 0
        val markdownImages = MARKDOWN_IMAGE.findAll(plain).count()
        val fences = FENCE.findAll(plain).map { it.groupValues[2].lowercase() }.toList()
        val codeBlocks = fences.size / 2
        val diagramCode = fences.count { it in DIAGRAM_LANGUAGES }
        val imagesAdded = blocks.count { it.type == NoteDocBlockType.IMAGE }
        val pictures = img + svg + canvas + markdownImages + (meta?.imageItems ?: 0)
        return buildString {
            if (meta != null) appendLine("Clipboard: ${meta.items} item(s) · " +
                meta.mimeTypes.ifEmpty { listOf("unknown") }.joinToString(", "))
            appendLine("Text: ${plain.length} characters · formatted copy (HTML): " +
                (html?.let { "${it.length} characters" + if (htmlUsed) ", used" else ", not used" } ?: "none"))
            appendLine("Pictures in the formatted copy: $img image(s) ($embedded embedded), " +
                "$svg drawing(s), $canvas canvas(es)")
            appendLine("In the text: $codeBlocks code block(s), $diagramCode diagram source(s), " +
                "$markdownImages image link(s)")
            if (meta != null && meta.imageItems > 0) appendLine("Separate image items: ${meta.imageItems}")
            appendLine("Added to the note: ${blocks.size} block(s), $imagesAdded image(s), " +
                "${blocks.count { it.type == NoteDocBlockType.CODE }} code block(s), " +
                "${blocks.count { it.type == NoteDocBlockType.TABLE }} table(s)")
            appendLine()
            append(when {
                pictures == 0 && diagramCode == 0 ->
                    "No picture or diagram was on the clipboard. The app you copied from sent only " +
                        "text, so anything it showed as a picture never reached this note. Copy the " +
                        "picture on its own (or take a screenshot) and add it with the Image tool."
                html != null && !htmlUsed && img + svg + embedded > 0 ->
                    "The formatted copy had pictures, but its text did not match the pasted text, " +
                        "so the plain text was used and the pictures could not be placed."
                else -> "The clipboard carried $pictures picture or drawing part(s) and $diagramCode " +
                    "diagram source(s); $imagesAdded became image blocks. A drawing's words are kept " +
                    "as text, a diagram's source as a code block, and a picture that could not be " +
                    "read as its description."
            })
        }
    }
}

package com.virlin.app.domain.notedoc.rich

import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.NoteDocVideo
import com.virlin.app.domain.notedoc.NoteDocAudio
import com.virlin.app.domain.notedoc.NoteMath
import com.virlin.app.domain.notedoc.NoteRuns

private typealias JNode = MutableMap<String, Any?>

/**
 * Explicit adapter between the rich note tree (format v2, authored by the WebView editor) and
 * the flat v1 block list that other features read: desktop sync, capture dual-write, Master
 * Document, flashcards, previews.
 *
 * - [fromLegacy] converts a stored v1 document for editing without reinterpreting its text.
 * - [project] derives the v1 block list from a tree. The tree remains the source of truth.
 * - [applyBlockEdits] maps block-level edits (desktop) back onto the tree. Unchanged blocks
 *   keep their rich nodes untouched; a structural change the adapter cannot express is refused.
 */
object RichNoteAdapter {

    // ------------------------------------------------------------------ parsing

    @Suppress("UNCHECKED_CAST")
    fun parseDocument(json: String): JNodeView {
        val root = RichJson.parse(json) as? MutableMap<String, Any?>
            ?: throw RichJson.MalformedJson("Document root is not an object")
        if (root["type"] != "doc") throw RichJson.MalformedJson("Document root type is not doc")
        validate(root, 0)
        return JNodeView(root)
    }

    /** Canonical string for a validated tree. */
    fun canonical(json: String): String = RichJson.stringify(parseDocument(json).node)

    private fun validate(node: Map<*, *>, depth: Int) {
        if (depth > 400) throw RichJson.MalformedJson("Document nesting is too deep")
        val type = node["type"] as? String ?: throw RichJson.MalformedJson("Node without type")
        if (type == "text" && node["text"] !is String) throw RichJson.MalformedJson("Text node without text")
        node["attrs"]?.let { if (it !is Map<*, *>) throw RichJson.MalformedJson("attrs is not an object") }
        node["marks"]?.let { marks ->
            if (marks !is List<*>) throw RichJson.MalformedJson("marks is not a list")
            marks.forEach { if (it !is Map<*, *> || it["type"] !is String) throw RichJson.MalformedJson("Bad mark") }
        }
        node["content"]?.let { content ->
            if (content !is List<*>) throw RichJson.MalformedJson("content is not a list")
            content.forEach { child ->
                if (child !is Map<*, *>) throw RichJson.MalformedJson("Child is not an object")
                validate(child, depth + 1)
            }
        }
    }

    /** Opaque wrapper so callers cannot mutate a parsed tree accidentally. */
    class JNodeView internal constructor(internal val node: JNode)

    // ------------------------------------------------------------------ legacy -> tree

    fun fromLegacy(blocks: List<NoteDocBlock>): String = RichJson.stringify(legacyDoc(blocks))

    /** A duplicated note's tree: fresh block ids and every checklist item unchecked. Text is untouched. */
    fun resetForDuplicate(json: String, newId: () -> String): String {
        val root = parseDocument(json).node
        fun walk(node: JNode) {
            @Suppress("UNCHECKED_CAST")
            (node["attrs"] as? MutableMap<String, Any?>)?.let { a ->
                if (a["id"] != null) a["id"] = newId()
                if (node["type"] == "taskItem") a["checked"] = false
            }
            @Suppress("UNCHECKED_CAST")
            (node["content"] as? List<JNode>)?.forEach(::walk)
        }
        walk(root)
        return RichJson.stringify(root)
    }

    internal fun legacyDoc(blocks: List<NoteDocBlock>): JNode {
        val content = convertRange(blocks, 0, blocks.size, 0)
        if (content.isEmpty()) content.add(node("paragraph", attrs("id" to "p-empty")))
        return node("doc", null, content)
    }

    private fun convertRange(blocks: List<NoteDocBlock>, from: Int, to: Int, offset: Int): MutableList<Any?> {
        val out = mutableListOf<Any?>()
        var i = from
        while (i < to) {
            val b = blocks[i]
            when (b.type) {
                NoteDocBlockType.BULLET, NoteDocBlockType.NUMBERED, NoteDocBlockType.CHECKLIST -> {
                    val (lists, next) = listLevel(blocks, i, to, b.indent, offset, 0)
                    out.addAll(lists)
                    i = next
                }
                NoteDocBlockType.TOGGLE -> {
                    var end = i + 1
                    while (end < to && blocks[end].indent > b.indent) end++
                    val summary = node("paragraph", blockAttrs(b, "${b.id}:s", indent = 0), inlineFrom(b.text, b.runs))
                    val children = convertRange(blocks, i + 1, end, b.indent + 1)
                    out += node("toggleBlock", attrs("id" to b.id, "collapsed" to b.isCollapsed.takeIf { it },
                        "indent" to (b.indent - offset).takeIf { it != 0 }), (mutableListOf<Any?>(summary) + children).toMutableList())
                    i = end
                }
                else -> { out += legacyBlock(b, offset); i++ }
            }
        }
        return out
    }

    /** Builds nested lists from indent depth so that projection returns the same indents. */
    private fun listLevel(blocks: List<NoteDocBlock>, from: Int, to: Int, level: Int,
                          offset: Int, depth: Int): Pair<List<JNode>, Int> {
        val lists = mutableListOf<JNode>()
        var current: JNode? = null
        var currentType: NoteDocBlockType? = null
        var i = from
        while (i < to) {
            val b = blocks[i]
            if (!b.type.isListType() || b.indent < level) break
            if (b.indent > level && current != null) {
                val items = current.content()
                val parent = items.last()
                val (nested, next) = listLevel(blocks, i, to, b.indent, offset, depth + 1)
                parent.contentList().addAll(nested)
                i = next
                continue
            }
            if (current == null || currentType != b.type || current.attr<String>("listStyle") != listStyleAttr(b)) {
                currentType = b.type
                current = node(listTypeName(b.type), attrs("id" to "list-${b.id}", "listStyle" to listStyleAttr(b)), mutableListOf())
                lists += current
            }
            val itemIndent = b.indent - offset - depth
            val paragraph = node("paragraph", blockAttrs(b, "${b.id}:p", indent = 0), inlineFrom(b.text, b.runs))
            val item = if (b.type == NoteDocBlockType.CHECKLIST)
                node("taskItem", attrs("id" to b.id, "checked" to b.checked, "indent" to itemIndent.takeIf { it != 0 }), mutableListOf(paragraph))
            else node("listItem", attrs("id" to b.id, "indent" to itemIndent.takeIf { it != 0 }), mutableListOf(paragraph))
            current.contentList() += item
            i++
        }
        return lists to i
    }

    private fun legacyBlock(b: NoteDocBlock, offset: Int): JNode {
        val indent = (b.indent - offset).takeIf { it != 0 }
        return when (b.type) {
            NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2, NoteDocBlockType.HEADING_3,
            NoteDocBlockType.HEADING_4, NoteDocBlockType.HEADING_5 ->
                node("heading", blockAttrs(b, b.id, indent).apply {
                    put("level", headingLevel(b.type).toLong())
                    if (b.isCollapsed) put("collapsed", true)
                    if (b.toggleHeading) put("toggle", true)
                }, inlineFrom(b.text, b.runs))
            NoteDocBlockType.QUOTE -> node("blockquote", attrs("id" to b.id, "indent" to indent),
                mutableListOf(node("paragraph", blockAttrs(b, "${b.id}:p", null), inlineFrom(b.text, b.runs))))
            NoteDocBlockType.CODE -> {
                val auto = b.mathMode == null
                val math = b.mathMode ?: NoteMath.isEquation(b.text)
                if (math) node("mathBlock", attrs("id" to b.id, "latex" to b.text, "legacyAuto" to auto.takeIf { it },
                    "indent" to indent))
                else node("codeBlock", attrs("id" to b.id, "language" to "", "legacyAuto" to auto.takeIf { it },
                    "indent" to indent), textContent(b.text))
            }
            NoteDocBlockType.PROMPT -> node("promptBlock", attrs("id" to b.id, "role" to "prompt",
                "title" to b.promptTitle, "indent" to indent), textContent(b.text))
            NoteDocBlockType.DIVIDER -> node("horizontalRule", attrs("id" to b.id, "indent" to indent))
            NoteDocBlockType.IMAGE -> {
                val path = b.imagePath.orEmpty()
                node("image", attrs("id" to b.id,
                    "name" to path.takeIf { MANAGED_IMAGE.matches(it) },
                    "url" to path.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) },
                    "legacyPath" to path.takeIf { it.isNotEmpty() && !MANAGED_IMAGE.matches(it) &&
                        !it.startsWith("https://", true) && !it.startsWith("http://", true) },
                    "indent" to indent))
            }
            NoteDocBlockType.VIDEO -> node("youtube", videoAttrs(b.id, b.video, indent))
            NoteDocBlockType.AUDIO -> node("audio", attrs("id" to b.id, "file" to b.audio?.file,
                "durationMs" to b.audio?.durationMs, "peaks" to b.audio?.peaks?.takeIf { it.isNotEmpty() },
                "title" to b.audio?.title, "indent" to indent))
            NoteDocBlockType.TABLE -> {
                val table = b.table ?: NoteDocTable.blank(1, 1)
                node("table", attrs("id" to b.id, "indent" to indent, "gridLines" to false.takeIf { !table.gridLines }),
                    MutableList(table.rows) { r ->
                        node("tableRow", null, MutableList(table.cols) { c ->
                            node(if (table.isHeader(r, c)) "tableHeader" else "tableCell", tableCellAttrs(table, r, c),
                                mutableListOf(node("paragraph", attrs("id" to "${b.id}:r${r}c$c"),
                                    inlineFrom(table.cell(r, c), table.runsAt(r, c)))))
                        })
                    })
            }
            else -> node("paragraph", blockAttrs(b, b.id, indent), inlineFrom(b.text, b.runs))
        }
    }

    private fun videoAttrs(id: String, video: NoteDocVideo?, indent: Int?): JNode = attrs(
        "id" to id,
        "videoId" to video?.videoId,
        "start" to video?.startSeconds?.takeIf { it > 0 }?.toLong(),
        "end" to video?.endSeconds?.toLong(),
        "title" to video?.title,
        "channel" to video?.channel,
        "indent" to indent,
    )

    private fun projectVideo(n: JNode): NoteDocVideo? = NoteDocVideo(
        videoId = n.attr<String>("videoId").orEmpty(),
        startSeconds = n.attrInt("start") ?: 0,
        endSeconds = n.attrInt("end"),
        title = n.attr<String>("title")?.take(NoteDocVideo.MAX_TEXT),
        channel = n.attr<String>("channel")?.take(NoteDocVideo.MAX_TEXT),
    ).takeIf { it.isValid }

    private fun blockAttrs(b: NoteDocBlock, id: String, indent: Int?): JNode = attrs(
        "id" to id,
        "align" to b.align.takeIf { it != NoteDocAlign.LEFT }?.name?.lowercase(),
        "indent" to indent,
        "spacing" to b.lineSpacing.takeIf { it != 1f }?.toDouble(),
    )

    // ------------------------------------------------------------------ tree -> blocks

    fun project(json: String): List<NoteDocBlock> = project(parseDocument(json))

    fun project(view: JNodeView): List<NoteDocBlock> = projectWithTargets(view.node).map { it.block }

    /** Projects a selection slice (a list of top-level nodes) for flashcards and copy. */
    @Suppress("UNCHECKED_CAST")
    fun projectSlice(contentJson: String): List<NoteDocBlock> {
        val content = RichJson.parse(contentJson) as? MutableList<Any?> ?: return emptyList()
        val doc = node("doc", null, content)
        validate(doc, 0)
        return projectWithTargets(doc).map { it.block }
    }

    internal class Projected(val block: NoteDocBlock, val target: Target)

    internal sealed class Target(val node: JNode, val chain: List<Pair<MutableList<Any?>, JNode>>) {
        /** Inline content lives in [container]; [owner] carries type-level attributes. */
        class Inline(owner: JNode, val container: JNode, chain: List<Pair<MutableList<Any?>, JNode>>,
                     val topLevelTextblock: Boolean) : Target(owner, chain)
        class Text(node: JNode, chain: List<Pair<MutableList<Any?>, JNode>>) : Target(node, chain)
        class Math(node: JNode, chain: List<Pair<MutableList<Any?>, JNode>>) : Target(node, chain)
        class Table(node: JNode, chain: List<Pair<MutableList<Any?>, JNode>>) : Target(node, chain)
        class Fixed(node: JNode, chain: List<Pair<MutableList<Any?>, JNode>>) : Target(node, chain)
    }

    internal fun projectWithTargets(doc: JNode): List<Projected> {
        val out = ArrayList<Projected>()
        val counter = intArrayOf(0)
        walk(doc.contentList(), 0, 0, emptyList(), out, counter, topLevel = true)
        return out
    }

    private fun walk(nodes: MutableList<Any?>, offset: Int, listDepth: Int,
                     chain: List<Pair<MutableList<Any?>, JNode>>, out: MutableList<Projected>,
                     counter: IntArray, topLevel: Boolean = false) {
        for (raw in nodes.toList()) {
            @Suppress("UNCHECKED_CAST") val n = raw as? JNode ?: continue
            val here = chain + (nodes to n)
            val id = n.attr<String>("id") ?: "anon-${counter[0]++}"
            val indent = offset + (n.attrInt("indent") ?: 0)
            when (n["type"]) {
                "paragraph" -> out += Projected(inlineBlock(n, id, NoteDocBlockType.PARAGRAPH, indent),
                    Target.Inline(n, n, here, topLevel))
                "heading" -> {
                    val level = (n.attrInt("level") ?: 1).coerceIn(1, 6)
                    // Level 6 shows as Heading 5; its stored level is kept unless the user restyles it.
                    val type = when (level) { 1 -> NoteDocBlockType.HEADING_1; 2 -> NoteDocBlockType.HEADING_2
                        3 -> NoteDocBlockType.HEADING_3; 4 -> NoteDocBlockType.HEADING_4
                        else -> NoteDocBlockType.HEADING_5 }
                    out += Projected(inlineBlock(n, id, type, indent).copy(isCollapsed = n.attr<Boolean>("collapsed") == true,
                        toggleHeading = n.attr<Boolean>("toggle") == true),
                        Target.Inline(n, n, here, topLevel))
                }
                "blockquote" -> {
                    var first = true
                    for (childRaw in n.contentList().toList()) {
                        @Suppress("UNCHECKED_CAST") val child = childRaw as? JNode ?: continue
                        val childChain = here + (n.contentList() to child)
                        if (child["type"] == "paragraph") {
                            val childId = if (first) id else child.attr<String>("id") ?: "anon-${counter[0]++}"
                            out += Projected(inlineBlock(child, childId, NoteDocBlockType.QUOTE, indent),
                                Target.Inline(if (first) n else child, child, childChain, false))
                        } else walk(mutableListOf(child), indent, 0, here, out, counter)
                        first = false
                    }
                }
                "codeBlock" -> out += Projected(NoteDocBlock(id, NoteDocBlockType.CODE, plainText(n),
                    indent = indent, mathMode = if (n.attr<Boolean>("legacyAuto") == true) null else false),
                    Target.Text(n, here))
                "promptBlock" -> out += Projected(NoteDocBlock(id, NoteDocBlockType.PROMPT, plainText(n),
                    indent = indent, promptTitle = n.attr<String>("title")), Target.Text(n, here))
                "mathBlock" -> out += Projected(NoteDocBlock(id, NoteDocBlockType.CODE,
                    n.attr<String>("latex")?.takeIf { it.isNotEmpty() } ?: n.attr<String>("fallbackText").orEmpty(),
                    indent = indent, mathMode = if (n.attr<Boolean>("legacyAuto") == true) null else true),
                    Target.Math(n, here))
                "bulletList", "orderedList", "taskList" -> {
                    val type = when (n["type"]) { "orderedList" -> NoteDocBlockType.NUMBERED
                        "taskList" -> NoteDocBlockType.CHECKLIST; else -> NoteDocBlockType.BULLET }
                    val listStyle = if (type == NoteDocBlockType.CHECKLIST) null else n.attr<String>("listStyle")
                    for (itemRaw in n.contentList().toList()) {
                        @Suppress("UNCHECKED_CAST") val item = itemRaw as? JNode ?: continue
                        val itemChain = here + (n.contentList() to item)
                        val itemId = item.attr<String>("id") ?: "anon-${counter[0]++}"
                        val itemIndent = offset + listDepth + (item.attrInt("indent") ?: 0)
                        val children = item.contentList()
                        @Suppress("UNCHECKED_CAST") val firstChild = children.firstOrNull() as? JNode
                        val rest: List<Any?>
                        if (firstChild?.get("type") == "paragraph") {
                            out += Projected(inlineBlock(firstChild, itemId, type, itemIndent)
                                .copy(checked = type == NoteDocBlockType.CHECKLIST && item.attr<Boolean>("checked") == true,
                                    listStyle = listStyle),
                                Target.Inline(item, firstChild, itemChain, false))
                            rest = children.drop(1)
                        } else {
                            out += Projected(NoteDocBlock(itemId, type, "", indent = itemIndent,
                                checked = type == NoteDocBlockType.CHECKLIST && item.attr<Boolean>("checked") == true,
                                listStyle = listStyle),
                                Target.Fixed(item, itemChain))
                            rest = children
                        }
                        for (childRaw in rest) {
                            @Suppress("UNCHECKED_CAST") val child = childRaw as? JNode ?: continue
                            if (child["type"] in LIST_TYPES) walk(mutableListOf(child), offset, listDepth + 1, itemChain, out, counter)
                            else walk(mutableListOf(child), itemIndent + 1, 0, itemChain, out, counter)
                        }
                    }
                }
                "table" -> {
                    val rows = n.contentList().mapNotNull { it as? Map<*, *> }
                    val cols = rows.maxOfOrNull { (it["content"] as? List<*>)?.size ?: 0 } ?: 0
                    out += Projected(NoteDocBlock(id, NoteDocBlockType.TABLE, indent = indent,
                        table = if (rows.isEmpty() || cols == 0) NoteDocTable.blank(1, 1)
                        else projectTable(n, rows, cols)), Target.Table(n, here))
                }
                "image" -> out += Projected(NoteDocBlock(id, NoteDocBlockType.IMAGE, indent = indent,
                    imagePath = n.attr<String>("name") ?: n.attr<String>("url") ?: n.attr<String>("legacyPath") ?: ""),
                    Target.Fixed(n, here))
                "horizontalRule" -> out += Projected(NoteDocBlock(id, NoteDocBlockType.DIVIDER, indent = indent), Target.Fixed(n, here))
                "youtube" -> out += Projected(NoteDocBlock(id, NoteDocBlockType.VIDEO, indent = indent,
                    video = projectVideo(n)), Target.Fixed(n, here))
                "audio" -> out += Projected(NoteDocBlock(id, NoteDocBlockType.AUDIO, indent = indent,
                    audio = NoteDocAudio(n.attr<String>("file").orEmpty(), n.attrNumber("durationMs")?.toLong() ?: 0L,
                        n.attr<String>("peaks").orEmpty(), n.attr<String>("title")?.take(NoteDocAudio.MAX_TITLE))
                        .takeIf { it.isValid }), Target.Fixed(n, here))
                "toggleBlock" -> {
                    val children = n.contentList()
                    @Suppress("UNCHECKED_CAST") val summary = children.firstOrNull() as? JNode
                    if (summary?.get("type") == "paragraph") {
                        out += Projected(inlineBlock(summary, id, NoteDocBlockType.TOGGLE, indent)
                            .copy(isCollapsed = n.attr<Boolean>("collapsed") == true),
                            Target.Inline(n, summary, here, false))
                        walk(children.drop(1).toMutableList(), indent + 1, 0, here, out, counter)
                    } else {
                        out += Projected(NoteDocBlock(id, NoteDocBlockType.TOGGLE, "", indent = indent,
                            isCollapsed = n.attr<Boolean>("collapsed") == true), Target.Fixed(n, here))
                        walk(children, indent + 1, 0, here, out, counter)
                    }
                }
                else -> {
                    val content = n.contentList()
                    val inline = content.any { (it as? Map<*, *>)?.get("type") in INLINE_TYPES }
                    if (inline) out += Projected(inlineBlock(n, id, NoteDocBlockType.PARAGRAPH, indent), Target.Fixed(n, here))
                    else walk(content, indent, 0, here, out, counter)
                }
            }
        }
    }

    private fun inlineBlock(container: JNode, id: String, type: NoteDocBlockType, indent: Int): NoteDocBlock {
        val (text, runs) = inlineText(container)
        return NoteDocBlock(id = id, type = type, text = text, runs = NoteRuns.normalize(runs, text.length),
            align = when (container.attr<String>("align")?.lowercase()) {
                "center" -> NoteDocAlign.CENTER; "right" -> NoteDocAlign.RIGHT; else -> NoteDocAlign.LEFT },
            indent = indent,
            lineSpacing = (container.attrNumber("spacing")?.toFloat() ?: 1f),
        )
    }

    private fun inlineText(container: Map<*, *>): Pair<String, List<NoteDocRun>> {
        val text = StringBuilder()
        val runs = ArrayList<NoteDocRun>()
        for (raw in (container["content"] as? List<*>).orEmpty()) {
            val child = raw as? Map<*, *> ?: continue
            when (child["type"]) {
                "text" -> {
                    val start = text.length
                    text.append(child["text"] as String)
                    runFromMarks(child["marks"] as? List<*>, start, text.length)?.let(runs::add)
                }
                "hardBreak" -> text.append('\n')
                "mathInline" -> {
                    val attrs = child["attrs"] as? Map<*, *>
                    val latex = (attrs?.get("latex") as? String)?.takeIf { it.isNotEmpty() }
                        ?: (attrs?.get("fallbackText") as? String).orEmpty()
                    text.append("\\(").append(latex).append("\\)")
                }
                else -> text.append(plainText(child))
            }
        }
        return text.toString() to runs
    }

    private fun runFromMarks(marks: List<*>?, start: Int, end: Int): NoteDocRun? {
        if (marks.isNullOrEmpty() || end <= start) return null
        val set = mutableSetOf<NoteDocMark>()
        var href: String? = null
        var highlight: Long? = null
        var color: Long? = null
        var family: String? = null
        var size: Int? = null
        for (raw in marks) {
            val mark = raw as? Map<*, *> ?: continue
            val attrs = mark["attrs"] as? Map<*, *>
            when (mark["type"]) {
                "bold" -> set += NoteDocMark.BOLD
                "italic" -> set += NoteDocMark.ITALIC
                "underline" -> set += NoteDocMark.UNDERLINE
                "strike" -> set += NoteDocMark.STRIKE
                "code" -> set += NoteDocMark.CODE
                "link" -> href = attrs?.get("href") as? String
                "highlight" -> highlight = cssToArgb(attrs?.get("color") as? String) ?: DEFAULT_HIGHLIGHT
                "textStyle" -> {
                    color = cssToArgb(attrs?.get("color") as? String)
                    family = (attrs?.get("fontFamily") as? String)?.takeIf { it.isNotBlank() }
                    size = (attrs?.get("fontSize") as? Number)?.toInt()
                }
            }
        }
        val run = NoteDocRun(start, end, set, color, highlight, family, size, href)
        return run.takeUnless { it.isEmptyStyle }
    }

    private fun projectTable(table: Map<*, *>, rows: List<Map<*, *>>, cols: Int): NoteDocTable {
        val grid = rows.map { row ->
            val rowCells = (row["content"] as? List<*>).orEmpty()
            List(cols) { c -> rowCells.getOrNull(c) as? Map<*, *> }
        }
        val texts = grid.flatMap { row -> row.map { cell -> cell?.let(::cellText) ?: "" } }
        fun header(cell: Map<*, *>?) = cell?.get("type") == "tableHeader"
        val headerRow = grid.first().all(::header)
        val body = grid.drop(if (headerRow) 1 else 0)
        return NoteDocTable(
            rows.size, cols, texts,
            cellRuns = grid.flatMap { row -> row.map { cell -> cell?.let(::cellRuns).orEmpty() } },
            cellFills = grid.flatMap { row -> row.map { cell -> cssToArgb(cell?.attr<String>("background")) } },
            colWidths = List(cols) { c ->
                (grid.first()[c]?.attr<List<*>>("colwidth")?.firstOrNull() as? Number)?.toInt()
            },
            headerRow = headerRow,
            headerColumn = body.isNotEmpty() && body.all { header(it[0]) },
            gridLines = table.attr<Boolean>("gridLines") != false,
        ).compact()
    }

    /** Formatting of a cell holding one paragraph; anything richer projects as plain text. */
    private fun cellRuns(cell: Map<*, *>): List<NoteDocRun> {
        val paragraph = (cell["content"] as? List<*>)?.singleOrNull() as? Map<*, *> ?: return emptyList()
        if (paragraph["type"] != "paragraph" && paragraph["type"] != "heading") return emptyList()
        val (text, runs) = inlineText(paragraph)
        return NoteRuns.normalize(runs, text.length)
    }

    private fun tableCellAttrs(table: NoteDocTable, r: Int, c: Int): JNode? = attrs(
        "background" to table.fillAt(r, c)?.let(::argbToCss),
        "colwidth" to table.widthAt(c)?.let { mutableListOf<Any?>(it.toLong()) },
    ).takeIf { it.isNotEmpty() }

    private fun cellText(cell: Map<*, *>): String =
        (cell["content"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.joinToString("\n") {
            if (it["type"] == "paragraph" || it["type"] == "heading") inlineText(it).first else plainText(it)
        }

    /** Plain text of a node: text, hard breaks as newlines, inline math as \( \). */
    fun plainText(node: Map<*, *>): String = buildString { appendPlain(this, node) }

    private fun appendPlain(out: StringBuilder, node: Map<*, *>) {
        when (node["type"]) {
            "text" -> out.append(node["text"] as? String ?: "")
            "hardBreak" -> out.append('\n')
            "mathInline" -> out.append("\\(").append((node["attrs"] as? Map<*, *>)?.get("latex") as? String ?: "").append("\\)")
            else -> {
                val children = (node["content"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
                val blockChildren = children.any { it["type"] !in INLINE_TYPES }
                children.forEachIndexed { i, child ->
                    if (blockChildren && i > 0) out.append('\n')
                    appendPlain(out, child)
                }
            }
        }
    }

    // ------------------------------------------------------------------ block edits -> tree

    /**
     * Applies desktop/legacy block edits to [treeJson]. Returns the new tree, or null when an edit
     * changes structure in a way the rich document cannot represent safely.
     */
    fun applyBlockEdits(treeJson: String, incoming: List<NoteDocBlock>): String? {
        val doc = parseDocument(treeJson).node
        val projected = projectWithTargets(doc)
        var byId = projected.associateBy { it.block.id }
        val incomingIds = incoming.mapTo(HashSet()) { it.id }
        // Removals first, using node identity so later lookups stay valid.
        for (p in projected) if (p.block.id !in incomingIds) {
            val removed = p.target.chain.lastOrNull()?.second ?: return null
            val keepsDescendant = projected.any { other ->
                other !== p && other.block.id in incomingIds && other.target.chain.any { it.second === removed }
            }
            if (keepsDescendant || !remove(p)) return null
        }
        reorderTopLevel(doc, projected.filter { it.block.id in incomingIds }, incoming)
        var previousTop: JNode? = null
        // Set when the previous block is an item of a top-level list, so a new line typed after it
        // lands right after that item instead of after the whole list.
        var previousItem: Pair<JNode, JNode>? = null
        for (block in incoming) {
            val p = byId[block.id]
            if (p == null) {
                val created = legacyDoc(listOf(block)).contentList()
                val docContent = doc.contentList()
                val after = previousItem
                if (after != null) {
                    val (list, item) = after
                    val items = list.contentList()
                    val index = items.indexOfFirst { it === item }
                    if (index < 0) return null
                    @Suppress("UNCHECKED_CAST") val single = created.singleOrNull() as? JNode
                    @Suppress("UNCHECKED_CAST") val newItem = single?.contentList()?.singleOrNull() as? JNode
                    if (newItem != null && single["type"] == list["type"] &&
                        single.attr<String>("listStyle") == list.attr<String>("listStyle")) {
                        items.add(index + 1, newItem)
                        previousItem = list to newItem
                        continue
                    }
                    if (!splitListAfter(docContent, list, index)) return null
                }
                val at = previousTop?.let { top -> docContent.indexOfFirst { it === top } + 1 } ?: 0
                docContent.addAll(at.coerceIn(0, docContent.size), created)
                @Suppress("UNCHECKED_CAST")
                previousTop = created.lastOrNull() as? JNode ?: previousTop
                previousItem = null
                continue
            }
            previousTop = p.target.chain.firstOrNull()?.second
            previousItem = topLevelListItem(p)
            val normalized = block.copy(runs = NoteRuns.normalize(block.runs, block.text.length))
            if (normalized == p.block) continue
            if (!patch(p, normalized)) return null
            if (normalized.type != p.block.type) {
                // A type change can move nodes (into or out of a list, quote or toggle).
                byId = projectWithTargets(doc).associateBy { it.block.id }
                val moved = byId[block.id] ?: return null
                previousTop = moved.target.chain.firstOrNull()?.second ?: return null
                previousItem = topLevelListItem(moved)
            }
        }
        return RichJson.stringify(doc)
    }

    /**
     * Puts the document's top-level nodes in the order [incoming] lists their blocks, when that is
     * a pure reorder of whole top-level nodes (a list, quote or toggle moves as one piece) and
     * every kept block has its own id. Anything else leaves the tree exactly as it was, so the
     * caller's read-back sees the order was not applied. Node identities never change, so
     * [kept]'s targets stay valid.
     */
    private fun reorderTopLevel(doc: JNode, kept: List<Projected>, incoming: List<NoteDocBlock>) {
        val byId = kept.associateBy { it.block.id }
        val wanted = incoming.mapNotNull { block -> block.id.takeIf { it in byId } }
        if (wanted == kept.map { it.block.id }) return
        if (kept.any { it.block.id.startsWith("anon-") }) return
        val order = ArrayList<JNode>()
        for (id in wanted) {
            val node = byId.getValue(id).target.chain.firstOrNull()?.second ?: return
            if (order.lastOrNull() === node) continue
            if (order.any { it === node }) return
            order += node
        }
        val top = doc.contentList()
        val original = ArrayList(top)
        // Nodes with no block of their own stay right after the node they followed.
        val start = Any()
        val trailing = java.util.IdentityHashMap<Any, MutableList<Any?>>()
        var anchor: Any = start
        for (raw in original) {
            if (order.any { it === raw }) anchor = raw!! else trailing.getOrPut(anchor) { ArrayList() } += raw
        }
        if (original.count { raw -> order.any { it === raw } } != order.size) return
        top.clear()
        trailing[start]?.let { top.addAll(it) }
        for (node in order) {
            top += node
            trailing[node]?.let { top.addAll(it) }
        }
        val after = projectWithTargets(doc).map { it.block.id }.filter { it in byId }
        if (after != wanted) {
            top.clear()
            top.addAll(original)
        }
    }

    private fun remove(p: Projected): Boolean {
        if (p.target.chain.isEmpty()) return false
        val chain = p.target.chain
        val target = chain.last()
        target.first.removeAll { it === target.second }
        // Collapse containers that became empty (lists, quotes, toggles), never the document.
        for (index in chain.indices.reversed()) {
            val (parentList, node) = chain[index]
            if (node.contentList().isEmpty() && node["type"] in EMPTYABLE_CONTAINERS) parentList.removeAll { it === node }
        }
        return true
    }

    private fun patch(p: Projected, block: NoteDocBlock): Boolean {
        val old = p.block
        return when (val target = p.target) {
            is Target.Inline -> {
                if (block.type != old.type && (block.type !in TEXTBLOCK_TYPES || old.type !in TEXTBLOCK_TYPES)) {
                    return patch(convertStep(p, block) ?: return false, block)
                }
                if (block.type != old.type) {
                    if (!target.topLevelTextblock) return false
                    val node = target.container
                    if (block.type == NoteDocBlockType.PARAGRAPH) {
                        node["type"] = "paragraph"; node.attrsMap().remove("level"); node.attrsMap().remove("collapsed")
                        node.attrsMap().remove("toggle")
                    } else {
                        node["type"] = "heading"; node.attrsMap()["level"] = headingLevel(block.type).toLong()
                    }
                }
                if (block.toggleHeading != old.toggleHeading) {
                    if (block.toggleHeading && target.container["type"] != "heading") return false
                    target.container.attrsMap().setOrRemove("toggle", block.toggleHeading.takeIf { it })
                }
                if (block.indent != old.indent) {
                    if (!target.topLevelTextblock) return false
                    val base = old.indent - (target.container.attrInt("indent") ?: 0)
                    target.container.attrsMap().setOrRemove("indent", (block.indent - base).takeIf { it != 0 }?.toLong())
                }
                if (block.text != old.text || block.runs != old.runs) {
                    val allowedMath = collectInlineMath(target.container)
                    target.container["content"] = inlineFromWithMath(block.text, block.runs, allowedMath)
                }
                if (block.align != old.align) target.container.attrsMap().setOrRemove("align",
                    block.align.takeIf { it != NoteDocAlign.LEFT }?.name?.lowercase())
                if (block.lineSpacing != old.lineSpacing) target.container.attrsMap().setOrRemove("spacing",
                    block.lineSpacing.takeIf { it != 1f }?.toDouble())
                if (block.checked != old.checked) {
                    if (target.node["type"] != "taskItem") return false
                    target.node.attrsMap()["checked"] = block.checked
                }
                if (block.isCollapsed != old.isCollapsed) {
                    val owner = if (target.node["type"] == "toggleBlock") target.node else target.container
                    owner.attrsMap().setOrRemove("collapsed", block.isCollapsed.takeIf { it })
                }
                if (listStyleAttr(block) != listStyleAttr(old)) {
                    // The style belongs to the whole list node; callers restyle every item of it.
                    val list = target.chain.getOrNull(target.chain.size - 2)?.second
                    if (target.node["type"] != "listItem" || list?.get("type") !in setOf("bulletList", "orderedList")) return false
                    list!!.attrsMap().setOrRemove("listStyle", listStyleAttr(block))
                }
                true
            }
            is Target.Text -> {
                if (block.type != old.type) return patch(convertStep(p, block) ?: return false, block)
                if (block.indent != old.indent) return false
                if (block.text != old.text) target.node["content"] = textContent(block.text)
                if (block.promptTitle != old.promptTitle) target.node.attrsMap().setOrRemove("title", block.promptTitle)
                true
            }
            is Target.Math -> {
                if (block.type != old.type || block.mathMode != old.mathMode || block.indent != old.indent) return false
                if (block.text != old.text) {
                    target.node.attrsMap()["latex"] = block.text
                    target.node.attrsMap().remove("mathml")
                }
                true
            }
            is Target.Table -> {
                val oldTable = old.table ?: return false
                val newTable = block.table ?: return false
                if (block.type != old.type) return false
                if (isSimpleTable(target.node, oldTable)) return rebuildTable(target.node, old.id, oldTable, newTable)
                // Merged cells or cells holding several blocks: only text and formatting edits, plus
                // growth at the edges, can be written without losing anything.
                val sameSize = oldTable.rows == newTable.rows && oldTable.cols == newTable.cols
                if (if (sameSize) newTable.cellFills != oldTable.cellFills
                    else newTable.cellFills.isNotEmpty() || oldTable.cellFills.isNotEmpty()) return false
                if (newTable.colWidths != oldTable.colWidths || newTable.headerRow != oldTable.headerRow ||
                    newTable.headerColumn != oldTable.headerColumn || newTable.gridLines != oldTable.gridLines) return false
                val (dr, dc) = if (sameSize) 0 to 0
                else growTable(target.node, old.id, oldTable, newTable) ?: return false
                val rows = target.node.contentList()
                for (r in 0 until newTable.rows) for (c in 0 until newTable.cols) {
                    val sr = r - dr
                    val sc = c - dc
                    val inOld = sr in 0 until oldTable.rows && sc in 0 until oldTable.cols
                    val before = if (inOld) oldTable.cell(sr, sc) else ""
                    val beforeRuns = if (inOld) oldTable.runsAt(sr, sc) else emptyList()
                    if (before == newTable.cell(r, c) && beforeRuns == newTable.runsAt(r, c)) continue
                    @Suppress("UNCHECKED_CAST")
                    val row = rows.getOrNull(r) as? JNode ?: return false
                    @Suppress("UNCHECKED_CAST")
                    val cell = row.contentList().getOrNull(c) as? JNode ?: return false
                    @Suppress("UNCHECKED_CAST")
                    val oldId = ((cell["content"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.let { (it["attrs"] as? Map<*, *>)?.get("id") }
                    cell["content"] = mutableListOf<Any?>(node("paragraph", attrs("id" to (oldId ?: "${old.id}:r${r}c$c")),
                        inlineFrom(newTable.cell(r, c), newTable.runsAt(r, c))))
                }
                true
            }
            is Target.Fixed -> {
                // A recording is never edited in place; it is only inserted or removed.
                if (target.node["type"] == "audio") return block == old
                if (target.node["type"] == "youtube") {
                    // New clip times or details for an embedded video; nothing else about it changes.
                    val video = block.video
                    if (block.type != old.type || block.indent != old.indent || block.copy(video = old.video) != old ||
                        video == null || !video.isValid) return false
                    val a = target.node.attrsMap()
                    a["videoId"] = video.videoId
                    a.setOrRemove("start", video.startSeconds.takeIf { it > 0 }?.toLong())
                    a.setOrRemove("end", video.endSeconds?.toLong())
                    a.setOrRemove("title", video.title)
                    a.setOrRemove("channel", video.channel)
                    return true
                }
                // An edited or replaced picture: only the stored file name changes; every other
                // attribute of the image node (size, caption, alignment) stays as it was.
                val path = block.imagePath.orEmpty()
                if (target.node["type"] != "image" || block.type != old.type || block.indent != old.indent ||
                    block.copy(imagePath = old.imagePath) != old || !MANAGED_IMAGE.matches(path)) return false
                val a = target.node.attrsMap()
                a["name"] = path
                a.remove("url")
                a.remove("legacyPath")
                true
            }
        }
    }

    /**
     * Moves a block one step toward [block]'s type when that needs a different node shape: a list
     * item, quote, toggle, code or prompt block first becomes a top-level paragraph; a top-level
     * paragraph or heading then becomes a list item, quote, toggle, code or prompt block. Only
     * simple shapes convert (top level, a single paragraph, nothing nested under it, its own id;
     * code or prompt only from unformatted text). Anything else returns null with the tree
     * unchanged, so the save is refused instead of losing content.
     */
    private fun convertStep(p: Projected, block: NoteDocBlock): Projected? {
        val old = p.block
        // Nodes without an id are numbered by position; moving one would renumber the others.
        if (p.target.node.attr<String>("id") != old.id) return null
        val root = p.target.chain.firstOrNull()?.first ?: return null
        if (old.type == NoteDocBlockType.TOGGLE) return convertToggle(p, block.copy(type = NoteDocBlockType.PARAGRAPH))
        val changed = when {
            old.type.isListType() -> unwrapListItem(p)
            old.type == NoteDocBlockType.QUOTE -> unwrapQuote(p)
            old.type == NoteDocBlockType.CODE || old.type == NoteDocBlockType.PROMPT -> textToParagraph(p)
            old.type !in TEXTBLOCK_TYPES -> false
            block.type == NoteDocBlockType.TOGGLE -> return convertToggle(p, block)
            block.type.isListType() -> wrapInList(p, block)
            block.type == NoteDocBlockType.QUOTE -> wrapInQuote(p)
            block.type == NoteDocBlockType.CODE || block.type == NoteDocBlockType.PROMPT -> paragraphToText(p, block)
            else -> false
        }
        if (!changed) return null
        val out = ArrayList<Projected>()
        walk(root, 0, 0, emptyList(), out, intArrayOf(0), topLevel = true)
        return out.firstOrNull { it.block.id == old.id }
    }

    /** The single top-level paragraph or heading behind [p], or null for any other shape. */
    private fun topLevelTextblock(p: Projected): Pair<MutableList<Any?>, JNode>? {
        val target = p.target as? Target.Inline ?: return null
        if (!target.topLevelTextblock || target.chain.size != 1 || target.container !== target.node) return null
        return target.chain.single()
    }

    private fun asParagraph(node: JNode, id: String) {
        node["type"] = "paragraph"
        node.attrsMap().apply {
            remove("level"); remove("collapsed"); remove("toggle"); remove("indent")
            put("id", id)
        }
    }

    /** Joins a neighbouring list of the same kind and style, so consecutive items stay one list. */
    @Suppress("UNCHECKED_CAST")
    private fun wrapInList(p: Projected, block: NoteDocBlock): Boolean {
        val (parent, textNode) = topLevelTextblock(p) ?: return false
        val at = parent.indexOfFirst { it === textNode }
        if (at < 0) return false
        val indent = textNode.attrInt("indent")?.takeIf { it != 0 }?.toLong()
        asParagraph(textNode, "${p.block.id}:p")
        val item = if (block.type == NoteDocBlockType.CHECKLIST)
            node("taskItem", attrs("id" to p.block.id, "checked" to false, "indent" to indent), mutableListOf(textNode))
        else node("listItem", attrs("id" to p.block.id, "indent" to indent), mutableListOf(textNode))
        val type = listTypeName(block.type)
        val style = listStyleAttr(block)
        fun joinable(raw: Any?): JNode? = (raw as? JNode)?.takeIf {
            it["type"] == type && it.attr<String>("listStyle") == style && it.attr<String>("id") != null
        }
        val before = joinable(parent.getOrNull(at - 1))
        val after = joinable(parent.getOrNull(at + 1))
        when {
            before != null -> {
                parent.removeAt(at)
                before.contentList() += item
                if (after != null) {
                    before.contentList().addAll(after.contentList())
                    parent.removeAll { it === after }
                }
            }
            after != null -> {
                parent.removeAt(at)
                after.contentList().add(0, item)
            }
            else -> parent[at] = node(type, attrs("id" to "list-${p.block.id}", "listStyle" to style), mutableListOf(item))
        }
        return true
    }

    /** Lifts a one-paragraph item of a top-level list out of it; later items keep a list of their own. */
    private fun unwrapListItem(p: Projected): Boolean {
        val target = p.target as? Target.Inline ?: return false
        if (target.chain.size != 2) return false
        val (parent, list) = target.chain[0]
        val (items, item) = target.chain[1]
        val paragraph = target.container
        if (list["type"] !in LIST_TYPES || paragraph["type"] != "paragraph") return false
        if (item.contentList().size != 1 || item.contentList().first() !== paragraph) return false
        val at = parent.indexOfFirst { it === list }
        val index = items.indexOfFirst { it === item }
        if (at < 0 || index < 0) return false
        if (index == 0 && list.attr<String>("id") == null) return false
        if (!splitListAfter(parent, list, index)) return false
        paragraph.attrsMap().apply {
            put("id", p.block.id)
            setOrRemove("indent", item.attrInt("indent")?.takeIf { it != 0 }?.toLong())
        }
        items.removeAt(index)
        parent.add(at + 1, paragraph)
        if (items.isEmpty()) parent.removeAt(at)
        return true
    }

    /** Moves the items after [index] into a new list of the same kind placed right after [list]. */
    private fun splitListAfter(parent: MutableList<Any?>, list: JNode, index: Int): Boolean {
        val items = list.contentList()
        if (index >= items.lastIndex) return true
        val at = parent.indexOfFirst { it === list }
        if (at < 0) return false
        val tail = items.subList(index + 1, items.size).toMutableList()
        while (items.size > index + 1) items.removeAt(items.size - 1)
        val tailAttrs = linkedMapOf<String, Any?>()
        (list["attrs"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String && k != "id" && k != "start") tailAttrs[k] = v }
        tailAttrs["id"] = "list-${(tail.first() as? Map<*, *>)?.attr<String>("id") ?: "split-$at"}"
        parent.add(at + 1, node(list["type"] as String, tailAttrs, tail))
        return true
    }

    /** The top-level list and item [p] sits in, or null when it is not a top-level list item. */
    private fun topLevelListItem(p: Projected?): Pair<JNode, JNode>? {
        val chain = p?.target?.chain ?: return null
        if (chain.size != 2 || chain[0].second["type"] !in LIST_TYPES) return null
        return chain[0].second to chain[1].second
    }

    private fun wrapInQuote(p: Projected): Boolean {
        val (parent, textNode) = topLevelTextblock(p) ?: return false
        val at = parent.indexOfFirst { it === textNode }
        if (at < 0) return false
        val indent = textNode.attrInt("indent")?.takeIf { it != 0 }?.toLong()
        asParagraph(textNode, "${p.block.id}:p")
        parent[at] = node("blockquote", attrs("id" to p.block.id, "indent" to indent), mutableListOf(textNode))
        return true
    }

    private fun unwrapQuote(p: Projected): Boolean {
        val target = p.target as? Target.Inline ?: return false
        if (target.chain.size != 2) return false
        val (parent, quote) = target.chain[0]
        val paragraph = target.container
        if (quote["type"] != "blockquote" || paragraph["type"] != "paragraph") return false
        if (quote.contentList().size != 1 || quote.contentList().first() !== paragraph) return false
        val at = parent.indexOfFirst { it === quote }
        if (at < 0) return false
        paragraph.attrsMap().apply {
            put("id", p.block.id)
            setOrRemove("indent", quote.attrInt("indent")?.takeIf { it != 0 }?.toLong())
        }
        parent[at] = paragraph
        return true
    }

    private fun textToParagraph(p: Projected): Boolean {
        val target = p.target as? Target.Text ?: return false
        if (target.chain.size != 1) return false
        val (parent, textNode) = target.chain.single()
        if (textNode["type"] == "codeBlock" && !textNode.attr<String>("language").isNullOrBlank()) return false
        val at = parent.indexOfFirst { it === textNode }
        if (at < 0) return false
        parent[at] = node("paragraph", attrs("id" to p.block.id,
            "indent" to textNode.attrInt("indent")?.takeIf { it != 0 }?.toLong()), inlineFrom(p.block.text, emptyList()))
        return true
    }

    private fun paragraphToText(p: Projected, block: NoteDocBlock): Boolean {
        val (parent, textNode) = topLevelTextblock(p) ?: return false
        val plain = textNode.contentList().all { raw ->
            val child = raw as? Map<*, *>
            child != null && (child["type"] == "hardBreak" ||
                (child["type"] == "text" && (child["marks"] as? List<*>).isNullOrEmpty()))
        }
        if (!plain) return false
        val at = parent.indexOfFirst { it === textNode }
        if (at < 0) return false
        parent[at] = legacyBlock(p.block.copy(type = block.type, runs = emptyList(), mathMode = block.mathMode,
            promptTitle = block.promptTitle, toggleHeading = false, isCollapsed = false), 0)
        return true
    }

    /**
     * Wraps a top-level paragraph or heading into a toggle block, or unwraps a toggle that has no
     * children back into a top-level text block. The summary node (and so its inline content and
     * marks) is reused as is. Returns the re-targeted block, or null — leaving the tree unchanged —
     * when the shape cannot be converted without moving other content (a toggle with children).
     */
    private fun convertToggle(p: Projected, block: NoteDocBlock): Projected? {
        val old = p.block
        val target = p.target as? Target.Inline ?: return null
        val (parentList, owner) = target.chain.lastOrNull() ?: return null
        val summary = target.container
        if (block.type == NoteDocBlockType.TOGGLE) {
            if (!target.topLevelTextblock || old.type !in TEXTBLOCK_TYPES || summary !== owner) return null
            val at = parentList.indexOfFirst { it === summary }
            if (at < 0) return null
            val indent = summary.attrInt("indent")
            summary["type"] = "paragraph"
            summary.attrsMap().apply {
                remove("level"); remove("collapsed"); remove("toggle"); remove("indent")
                put("id", "${old.id}:s")
            }
            val toggle = node("toggleBlock", attrs("id" to old.id, "indent" to indent?.toLong()), mutableListOf(summary))
            parentList[at] = toggle
            return Projected(old.copy(type = NoteDocBlockType.TOGGLE, isCollapsed = false, toggleHeading = false),
                Target.Inline(toggle, summary, target.chain.dropLast(1) + (parentList to toggle), false))
        }
        if (owner["type"] != "toggleBlock" || block.type !in TEXTBLOCK_TYPES) return null
        val content = owner.contentList()
        if (content.size != 1 || content.first() !== summary || summary["type"] != "paragraph") return null
        val at = parentList.indexOfFirst { it === owner }
        if (at < 0) return null
        summary.attrsMap().apply {
            put("id", old.id)
            setOrRemove("indent", owner.attrInt("indent")?.takeIf { it != 0 }?.toLong())
        }
        parentList[at] = summary
        return Projected(old.copy(type = NoteDocBlockType.PARAGRAPH, isCollapsed = false),
            Target.Inline(summary, summary, target.chain.dropLast(1) + (parentList to summary), target.chain.size == 1))
    }

    /**
     * True when every cell is a plain one-by-one cell holding at most one paragraph of text,
     * line breaks and inline math: exactly what [NoteDocTable] describes, so the table can be
     * rewritten from it without losing anything.
     */
    private fun isSimpleTable(table: JNode, t: NoteDocTable): Boolean {
        val rows = table.contentList()
        if (rows.size != t.rows) return false
        for ((r, rowRaw) in rows.withIndex()) {
            val row = rowRaw as? Map<*, *> ?: return false
            if (row["type"] != "tableRow") return false
            val cells = (row["content"] as? List<*>).orEmpty()
            if (cells.size != t.cols) return false
            for ((c, cellRaw) in cells.withIndex()) {
                val cell = cellRaw as? Map<*, *> ?: return false
                if (cell["type"] != if (t.isHeader(r, c)) "tableHeader" else "tableCell") return false
                if ((cell.attrInt("colspan") ?: 1) != 1 || (cell.attrInt("rowspan") ?: 1) != 1) return false
                val background = cell.attr<Any>("background")
                if (background != null && cssToArgb(background as? String) != t.fillAt(r, c)) return false
                val width = cell.attr<List<*>>("colwidth")?.firstOrNull()
                if (width != null && (width as? Number)?.toInt() != t.widthAt(c)) return false
                val content = (cell["content"] as? List<*>).orEmpty()
                if (content.size > 1) return false
                val paragraph = content.firstOrNull() as? Map<*, *> ?: if (content.isEmpty()) continue else return false
                if (paragraph["type"] != "paragraph") return false
                if ((paragraph["content"] as? List<*>).orEmpty().any { (it as? Map<*, *>)?.get("type") !in INLINE_TYPES }) return false
            }
        }
        return true
    }

    /**
     * Rewrites a simple table to [new]: rows and columns may be inserted or deleted anywhere, and
     * header cells, shading, widths and grid lines follow the model. Cells whose text and
     * formatting did not change keep their stored nodes; the old grid is matched to the new one by
     * the insertion/deletion position that keeps the most cells unchanged.
     */
    private fun rebuildTable(table: JNode, blockId: String, old: NoteDocTable, new: NoteDocTable): Boolean {
        @Suppress("UNCHECKED_CAST")
        val oldRows = table.contentList().map { it as JNode }
        @Suppress("UNCHECKED_CAST")
        val oldCells = oldRows.map { row -> row.contentList().map { it as JNode } }
        val usedIds = HashSet<String>()
        oldCells.forEach { row -> row.forEach { cell ->
            (cell["content"] as? List<*>).orEmpty().forEach { p -> (p as? Map<*, *>)?.attr<String>("id")?.let(usedIds::add) }
        } }
        fun freshId(r: Int, c: Int): String {
            val base = "$blockId:r${r}c$c"
            var id = base
            var n = 2
            while (!usedIds.add(id)) id = "$base-${n++}"
            return id
        }
        fun unchanged(sr: Int, sc: Int, r: Int, c: Int) =
            old.cell(sr, sc) == new.cell(r, c) && old.runsAt(sr, sc) == new.runsAt(r, c)
        val rowMap: IntArray
        val colMap: IntArray
        run {
            var best: Pair<IntArray, IntArray>? = null
            var bestScore = -1
            for (rm in axisMaps(old.rows, new.rows)) for (cm in axisMaps(old.cols, new.cols)) {
                var score = 0
                for (r in 0 until new.rows) { val sr = rm[r]; if (sr < 0) continue
                    for (c in 0 until new.cols) { val sc = cm[c]; if (sc >= 0 && unchanged(sr, sc, r, c)) score++ } }
                if (score > bestScore) { bestScore = score; best = rm to cm }
            }
            rowMap = best!!.first
            colMap = best.second
        }
        table["content"] = MutableList<Any?>(new.rows) { r ->
            val sr = rowMap[r]
            val row = if (sr >= 0) oldRows[sr] else node("tableRow", null, mutableListOf())
            row["content"] = MutableList<Any?>(new.cols) { c ->
                val sc = colMap[c]
                val base = if (sr >= 0 && sc >= 0) oldCells[sr][sc] else null
                val cell = base ?: node("tableCell", null)
                cell["type"] = if (new.isHeader(r, c)) "tableHeader" else "tableCell"
                val a = cell.attrsMap()
                a.setOrRemove("background", new.fillAt(r, c)?.let(::argbToCss))
                a.setOrRemove("colwidth", new.widthAt(c)?.let { mutableListOf<Any?>(it.toLong()) })
                if (a.isEmpty()) cell.remove("attrs")
                if (base == null || !unchanged(sr, sc, r, c)) {
                    @Suppress("UNCHECKED_CAST")
                    val paragraph = (base?.get("content") as? List<*>)?.firstOrNull() as? JNode
                        ?: node("paragraph", attrs("id" to freshId(r, c)))
                    paragraph["content"] = inlineFromWithMath(new.cell(r, c), new.runsAt(r, c), collectInlineMath(paragraph))
                    cell["content"] = mutableListOf<Any?>(paragraph)
                }
                cell
            }
            row
        }
        table.attrsMap().setOrRemove("gridLines", false.takeIf { !new.gridLines })
        return true
    }

    /**
     * Candidate old-index maps for one axis growing or shrinking from [old] to [new]: one block of
     * inserted (-1) or deleted entries at each possible position.
     */
    private fun axisMaps(old: Int, new: Int): List<IntArray> {
        val d = new - old
        if (d == 0) return listOf(IntArray(new) { it })
        return (0..minOf(old, new)).map { at ->
            IntArray(new) { i ->
                when {
                    i < at -> i
                    d > 0 && i < at + d -> -1
                    else -> i - d
                }
            }
        }
    }

    /**
     * Grows a stored table in place to [new]'s size by adding whole rows (top or bottom) and/or
     * whole columns (left or right). Existing row and cell nodes are kept untouched; new cells copy
     * their neighbour's cell type. Returns the (row, column) offset of the old grid inside the new
     * one, or null — leaving the node unchanged — when the stored shape cannot be grown safely
     * (shrinking, ragged rows, merged cells).
     */
    private fun growTable(table: JNode, blockId: String, old: NoteDocTable, new: NoteDocTable): Pair<Int, Int>? {
        if (new.rows < old.rows || new.cols < old.cols || old.rows == 0 || old.cols == 0) return null
        @Suppress("UNCHECKED_CAST")
        val rowNodes = table.contentList().map { it as? JNode ?: return null }
        if (rowNodes.size != old.rows) return null
        val usedIds = HashSet<String>()
        for (row in rowNodes) {
            if (row["type"] != "tableRow") return null
            val cells = row.contentList()
            if (cells.size != old.cols) return null
            for (cellRaw in cells) {
                val cell = cellRaw as? Map<*, *> ?: return null
                if ((cell.attrInt("colspan") ?: 1) != 1 || (cell.attrInt("rowspan") ?: 1) != 1) return null
                (cell["content"] as? List<*>).orEmpty().forEach { p -> (p as? Map<*, *>)?.attr<String>("id")?.let(usedIds::add) }
            }
        }
        val addRows = new.rows - old.rows
        val addCols = new.cols - old.cols
        val (dr, dc) = listOf(0 to 0, addRows to 0, 0 to addCols, addRows to addCols).distinct().maxBy { (r0, c0) ->
            var same = 0
            for (r in 0 until old.rows) for (c in 0 until old.cols) if (old.cell(r, c) == new.cell(r + r0, c + c0)) same++
            same
        }
        fun freshId(r: Int, c: Int): String {
            val base = "$blockId:r${r}c$c"
            var id = base
            var n = 2
            while (!usedIds.add(id)) id = "$base-${n++}"
            return id
        }
        val grown = MutableList<Any?>(new.rows) { r ->
            val sr = r - dr
            val row = rowNodes.getOrNull(sr) ?: node("tableRow", null, mutableListOf())
            @Suppress("UNCHECKED_CAST")
            val reference = rowNodes[sr.coerceIn(0, old.rows - 1)].contentList().map { it as JNode }
            val existing = row.contentList().toList()
            row["content"] = MutableList<Any?>(new.cols) { c ->
                val sc = c - dc
                if (sr in 0 until old.rows && sc in 0 until old.cols) existing[sc]
                else node(reference[sc.coerceIn(0, old.cols - 1)]["type"] as? String ?: "tableCell", null,
                    mutableListOf(node("paragraph", attrs("id" to freshId(r, c)), mutableListOf())))
            }
            row
        }
        table["content"] = grown
        return dr to dc
    }

    private fun collectInlineMath(container: Map<*, *>): Set<String> =
        (container["content"] as? List<*>).orEmpty().mapNotNull { child ->
            (child as? Map<*, *>)?.takeIf { it["type"] == "mathInline" }
                ?.let { (it["attrs"] as? Map<*, *>)?.get("latex") as? String }
        }.toSet()

    // ------------------------------------------------------------------ recovery

    /** Best-effort text salvage from a damaged stored document. Never reinterprets Markdown. */
    fun salvage(raw: String): String {
        val texts = Regex("\"(?:text|latex|title)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(raw)
            .map { unescape(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
        val content = if (texts.isEmpty()) mutableListOf<Any?>(node("codeBlock", attrs("id" to "salvage-raw", "language" to "json"), textContent(raw)))
        else texts.mapIndexed { i, text -> node("paragraph", attrs("id" to "salvage-$i"), inlineFrom(text, emptyList())) as Any? }.toMutableList()
        return RichJson.stringify(node("doc", null, content))
    }

    /** A rich document that stores [raw] verbatim in a code block, used for recovery copies. */
    fun recoveryDocument(heading: String, raw: String): String = RichJson.stringify(node("doc", null, mutableListOf(
        node("heading", attrs("id" to "recovery-heading", "level" to 2L), inlineFrom(heading, emptyList())),
        node("codeBlock", attrs("id" to "recovery-raw", "language" to "json"), textContent(raw)),
    )))

    private fun unescape(s: String): String = runCatching { RichJson.parse("\"$s\"") as String }.getOrDefault(s)

    // ------------------------------------------------------------------ inline construction

    internal fun inlineFrom(text: String, runs: List<NoteDocRun>): MutableList<Any?> {
        if (text.isEmpty()) return mutableListOf()
        val normalized = NoteRuns.normalize(runs, text.length)
        val edges = sortedSetOf(0, text.length)
        normalized.forEach { edges += it.start; edges += it.end }
        text.forEachIndexed { i, c -> if (c == '\n') { edges += i; edges += i + 1 } }
        val bounds = edges.toList()
        val out = mutableListOf<Any?>()
        for (k in 0 until bounds.size - 1) {
            val s = bounds[k]
            val e = bounds[k + 1]
            if (e <= s) continue
            val segment = text.substring(s, e)
            if (segment == "\n") { out += node("hardBreak", null); continue }
            val run = normalized.firstOrNull { it.start <= s && it.end >= e }
            val marks = run?.let(::marksFor).orEmpty()
            out += linkedMapOf<String, Any?>("type" to "text", "text" to segment).apply {
                if (marks.isNotEmpty()) put("marks", marks)
            }
        }
        return out
    }

    private fun inlineFromWithMath(text: String, runs: List<NoteDocRun>, allowed: Set<String>): MutableList<Any?> {
        if (allowed.isEmpty()) return inlineFrom(text, runs)
        val out = mutableListOf<Any?>()
        var cursor = 0
        for (match in Regex("\\\\\\((.+?)\\\\\\)").findAll(text)) {
            val latex = match.groupValues[1]
            if (latex !in allowed) continue
            if (match.range.first > cursor) out.addAll(inlineFrom(text.substring(cursor, match.range.first),
                sliceRuns(runs, cursor, match.range.first)))
            out.add(node("mathInline", attrs("latex" to latex)))
            cursor = match.range.last + 1
        }
        if (cursor < text.length) out.addAll(inlineFrom(text.substring(cursor), sliceRuns(runs, cursor, text.length)))
        return out
    }

    private fun sliceRuns(runs: List<NoteDocRun>, start: Int, end: Int): List<NoteDocRun> = runs.mapNotNull { r ->
        val a = maxOf(r.start, start)
        val b = minOf(r.end, end)
        if (b > a) r.copy(start = a - start, end = b - start) else null
    }

    private fun marksFor(run: NoteDocRun): List<Any?> {
        val marks = mutableListOf<Any?>()
        if (NoteDocMark.BOLD in run.marks) marks += mark("bold")
        if (NoteDocMark.ITALIC in run.marks) marks += mark("italic")
        if (NoteDocMark.UNDERLINE in run.marks) marks += mark("underline")
        if (NoteDocMark.STRIKE in run.marks) marks += mark("strike")
        if (NoteDocMark.CODE in run.marks) marks += mark("code")
        run.linkUrl?.let { marks += mark("link", attrs("href" to it)) }
        run.highlight?.let { marks += mark("highlight", attrs("color" to argbToCss(it))) }
        if (run.color != null || run.fontFamily != null || run.fontSizeSp != null) marks += mark("textStyle", attrs(
            "color" to run.color?.let(::argbToCss), "fontFamily" to run.fontFamily, "fontSize" to run.fontSizeSp?.toLong()))
        return marks
    }

    private fun textContent(text: String): MutableList<Any?> =
        if (text.isEmpty()) mutableListOf() else mutableListOf(linkedMapOf<String, Any?>("type" to "text", "text" to text))

    // ------------------------------------------------------------------ colours

    fun argbToCss(value: Long): String {
        val alpha = (value ushr 24) and 0xFF
        val rgb = value and 0xFFFFFF
        return if (alpha == 0xFFL) "#" + rgb.toString(16).padStart(6, '0')
        else "#" + rgb.toString(16).padStart(6, '0') + alpha.toString(16).padStart(2, '0')
    }

    fun cssToArgb(value: String?): Long? {
        val v = value?.trim()?.lowercase() ?: return null
        Regex("#([0-9a-f]{3})").matchEntire(v)?.let { m ->
            val h = m.groupValues[1].map { "$it$it" }.joinToString("")
            return 0xFF000000L or h.toLong(16)
        }
        Regex("#([0-9a-f]{6})").matchEntire(v)?.let { return 0xFF000000L or it.groupValues[1].toLong(16) }
        Regex("#([0-9a-f]{6})([0-9a-f]{2})").matchEntire(v)?.let {
            return (it.groupValues[2].toLong(16) shl 24) or it.groupValues[1].toLong(16)
        }
        Regex("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(?:,\\s*([0-9.]+)\\s*)?\\)").matchEntire(v)?.let { m ->
            val (r, g, b) = m.destructured
            val a = m.groupValues[4].toDoubleOrNull()?.let { (it.coerceIn(0.0, 1.0) * 255).toLong() } ?: 0xFFL
            return (a shl 24) or (r.toLong().coerceIn(0, 255) shl 16) or (g.toLong().coerceIn(0, 255) shl 8) or b.toLong().coerceIn(0, 255)
        }
        return null
    }

    // ------------------------------------------------------------------ helpers

    private val MANAGED_IMAGE = Regex("[0-9a-f-]{36}", RegexOption.IGNORE_CASE)
    private const val DEFAULT_HIGHLIGHT = 0xFFFFF3BFL
    private val LIST_TYPES = setOf("bulletList", "orderedList", "taskList")
    private val INLINE_TYPES = setOf("text", "hardBreak", "mathInline")
    private val EMPTYABLE_CONTAINERS = setOf("bulletList", "orderedList", "taskList", "blockquote", "toggleBlock",
        "listItem", "taskItem")
    private val TEXTBLOCK_TYPES = setOf(NoteDocBlockType.PARAGRAPH, NoteDocBlockType.HEADING_1,
        NoteDocBlockType.HEADING_2, NoteDocBlockType.HEADING_3, NoteDocBlockType.HEADING_4, NoteDocBlockType.HEADING_5)

    private fun NoteDocBlockType.isListType() = this == NoteDocBlockType.BULLET ||
        this == NoteDocBlockType.NUMBERED || this == NoteDocBlockType.CHECKLIST

    private fun listTypeName(type: NoteDocBlockType) = when (type) {
        NoteDocBlockType.NUMBERED -> "orderedList"; NoteDocBlockType.CHECKLIST -> "taskList"; else -> "bulletList"
    }

    private fun listStyleAttr(b: NoteDocBlock): String? =
        b.listStyle.takeIf { b.type == NoteDocBlockType.BULLET || b.type == NoteDocBlockType.NUMBERED }

    private fun headingLevel(type: NoteDocBlockType) = type.headingLevel.takeIf { it > 0 } ?: 3

    private fun node(type: String, attrs: JNode?, content: MutableList<Any?>? = null): JNode =
        linkedMapOf<String, Any?>("type" to type).apply {
            if (!attrs.isNullOrEmpty()) put("attrs", attrs)
            if (content != null) put("content", content)
        }

    private fun mark(type: String, attrs: JNode? = null): JNode =
        linkedMapOf<String, Any?>("type" to type).apply { if (!attrs.isNullOrEmpty()) put("attrs", attrs) }

    /** Attribute map without null values. */
    private fun attrs(vararg pairs: Pair<String, Any?>): JNode =
        linkedMapOf<String, Any?>().apply { pairs.forEach { (k, v) -> if (v != null) put(k, v) } }

    @Suppress("UNCHECKED_CAST")
    private fun JNode.contentList(): MutableList<Any?> =
        (this["content"] as? MutableList<Any?>) ?: mutableListOf<Any?>().also { this["content"] = it }

    @Suppress("UNCHECKED_CAST")
    private fun JNode.content(): List<JNode> = contentList().mapNotNull { it as? JNode }

    @Suppress("UNCHECKED_CAST")
    private fun JNode.attrsMap(): JNode =
        (this["attrs"] as? JNode) ?: linkedMapOf<String, Any?>().also { this["attrs"] = it }

    private fun JNode.setOrRemove(key: String, value: Any?) { if (value == null) remove(key) else put(key, value) }

    private inline fun <reified T> Map<*, *>.attr(key: String): T? = (this["attrs"] as? Map<*, *>)?.get(key) as? T

    private fun Map<*, *>.attrInt(key: String): Int? = ((this["attrs"] as? Map<*, *>)?.get(key) as? Number)?.toInt()

    private fun Map<*, *>.attrNumber(key: String): Double? = ((this["attrs"] as? Map<*, *>)?.get(key) as? Number)?.toDouble()
}

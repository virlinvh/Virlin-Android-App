package com.virlin.app.domain.notedoc.rich

import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
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
            if (current == null || currentType != b.type) {
                currentType = b.type
                current = node(listTypeName(b.type), attrs("id" to "list-${b.id}"), mutableListOf())
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
            NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2, NoteDocBlockType.HEADING_3 ->
                node("heading", blockAttrs(b, b.id, indent).apply {
                    put("level", headingLevel(b.type).toLong())
                    if (b.isCollapsed) put("collapsed", true)
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
            NoteDocBlockType.TABLE -> {
                val table = b.table ?: NoteDocTable.blank(1, 1)
                node("table", attrs("id" to b.id, "indent" to indent), MutableList(table.rows) { r ->
                    node("tableRow", null, MutableList(table.cols) { c ->
                        node(if (r == 0) "tableHeader" else "tableCell", null,
                            mutableListOf(node("paragraph", attrs("id" to "${b.id}:r${r}c$c"), inlineFrom(table.cell(r, c), emptyList()))))
                    })
                })
            }
            else -> node("paragraph", blockAttrs(b, b.id, indent), inlineFrom(b.text, b.runs))
        }
    }

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
                    val type = when (level) { 1 -> NoteDocBlockType.HEADING_1; 2 -> NoteDocBlockType.HEADING_2
                        else -> NoteDocBlockType.HEADING_3 }
                    out += Projected(inlineBlock(n, id, type, indent).copy(isCollapsed = n.attr<Boolean>("collapsed") == true),
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
                                .copy(checked = type == NoteDocBlockType.CHECKLIST && item.attr<Boolean>("checked") == true),
                                Target.Inline(item, firstChild, itemChain, false))
                            rest = children.drop(1)
                        } else {
                            out += Projected(NoteDocBlock(itemId, type, "", indent = itemIndent,
                                checked = type == NoteDocBlockType.CHECKLIST && item.attr<Boolean>("checked") == true),
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
                    val cells = rows.flatMap { row ->
                        val rowCells = (row["content"] as? List<*>).orEmpty()
                        List(cols) { c -> (rowCells.getOrNull(c) as? Map<*, *>)?.let { cellText(it) } ?: "" }
                    }
                    out += Projected(NoteDocBlock(id, NoteDocBlockType.TABLE, indent = indent,
                        table = if (rows.isEmpty() || cols == 0) NoteDocTable.blank(1, 1)
                        else NoteDocTable(rows.size, cols, cells)), Target.Table(n, here))
                }
                "image" -> out += Projected(NoteDocBlock(id, NoteDocBlockType.IMAGE, indent = indent,
                    imagePath = n.attr<String>("name") ?: n.attr<String>("url") ?: n.attr<String>("legacyPath") ?: ""),
                    Target.Fixed(n, here))
                "horizontalRule" -> out += Projected(NoteDocBlock(id, NoteDocBlockType.DIVIDER, indent = indent), Target.Fixed(n, here))
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
        val byId = projected.associateBy { it.block.id }
        val incomingIds = incoming.mapTo(HashSet()) { it.id }
        // Removals first, using node identity so later lookups stay valid.
        for (p in projected) if (p.block.id !in incomingIds) {
            val removed = p.target.chain.lastOrNull()?.second ?: return null
            val keepsDescendant = projected.any { other ->
                other !== p && other.block.id in incomingIds && other.target.chain.any { it.second === removed }
            }
            if (keepsDescendant || !remove(p)) return null
        }
        var previousTop: JNode? = null
        for (block in incoming) {
            val p = byId[block.id]
            if (p == null) {
                val created = legacyDoc(listOf(block)).contentList()
                val docContent = doc.contentList()
                val at = previousTop?.let { top -> docContent.indexOfFirst { it === top } + 1 } ?: 0
                docContent.addAll(at.coerceIn(0, docContent.size), created)
                @Suppress("UNCHECKED_CAST")
                previousTop = created.lastOrNull() as? JNode ?: previousTop
                continue
            }
            previousTop = p.target.chain.firstOrNull()?.second
            val normalized = block.copy(runs = NoteRuns.normalize(block.runs, block.text.length))
            if (normalized == p.block) continue
            if (!patch(p, normalized)) return null
        }
        return RichJson.stringify(doc)
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
                if (block.type != old.type) {
                    if (!target.topLevelTextblock || block.type !in TEXTBLOCK_TYPES) return false
                    val node = target.container
                    if (block.type == NoteDocBlockType.PARAGRAPH) {
                        node["type"] = "paragraph"; node.attrsMap().remove("level"); node.attrsMap().remove("collapsed")
                    } else {
                        node["type"] = "heading"; node.attrsMap()["level"] = headingLevel(block.type).toLong()
                    }
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
                true
            }
            is Target.Text -> {
                if (block.type != old.type || block.indent != old.indent) return false
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
                if (block.type != old.type || oldTable.rows != newTable.rows || oldTable.cols != newTable.cols) return false
                val rows = target.node.contentList()
                for (r in 0 until newTable.rows) for (c in 0 until newTable.cols) {
                    if (oldTable.cell(r, c) == newTable.cell(r, c)) continue
                    @Suppress("UNCHECKED_CAST")
                    val row = rows.getOrNull(r) as? JNode ?: return false
                    @Suppress("UNCHECKED_CAST")
                    val cell = row.contentList().getOrNull(c) as? JNode ?: return false
                    @Suppress("UNCHECKED_CAST")
                    val oldId = ((cell["content"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.let { (it["attrs"] as? Map<*, *>)?.get("id") }
                    cell["content"] = mutableListOf<Any?>(node("paragraph", attrs("id" to (oldId ?: "${old.id}:r${r}c$c")),
                        inlineFrom(newTable.cell(r, c), emptyList())))
                }
                true
            }
            is Target.Fixed -> false
        }
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
        NoteDocBlockType.HEADING_2, NoteDocBlockType.HEADING_3)

    private fun NoteDocBlockType.isListType() = this == NoteDocBlockType.BULLET ||
        this == NoteDocBlockType.NUMBERED || this == NoteDocBlockType.CHECKLIST

    private fun listTypeName(type: NoteDocBlockType) = when (type) {
        NoteDocBlockType.NUMBERED -> "orderedList"; NoteDocBlockType.CHECKLIST -> "taskList"; else -> "bulletList"
    }

    private fun headingLevel(type: NoteDocBlockType) = when (type) {
        NoteDocBlockType.HEADING_1 -> 1; NoteDocBlockType.HEADING_2 -> 2; else -> 3
    }

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

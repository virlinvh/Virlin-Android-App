package com.virlin.app.ui.map

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a real `.xmind` workbook on the device.
 *
 * A `.xmind` file is a ZIP holding `content.json`, `metadata.json` and `manifest.json`; this
 * writer produces exactly that, so the result is a workbook rather than a renamed JSON file.
 * The supplied `VirlinXmindExport.ts` uses XMind's Node generator, and this repository has no
 * Node runtime and is offline-only, so the same validated contract is implemented here in
 * Kotlin: one project root, ordered children, iterative traversal for arbitrary depth, and the
 * same rejections (duplicate ids, missing parents, cycles, disconnected nodes).
 *
 * No XMind artwork, logo or theme is copied — only the open file layout.
 *
 * INTEROPERABILITY IS NOT VERIFIED: no XMind installation was available here to open the
 * output. See the notes in `docs/DEVELOPMENT_STATUS.md`.
 */
object XmindWriter {

    class ExportException(message: String) : IllegalArgumentException(message)

    fun write(topics: List<MapTopic>, out: OutputStream) {
        val content = buildContent(topics)
        ZipOutputStream(out).use { zip ->
            zip.entry("content.json", content.toString())
            zip.entry(
                "metadata.json",
                JSONObject().put(
                    "creator",
                    JSONObject().put("name", "Virlin").put("version", "1")
                ).toString()
            )
            zip.entry(
                "manifest.json",
                JSONObject().put(
                    "file-entries",
                    JSONObject()
                        .put("content.json", JSONObject())
                        .put("metadata.json", JSONObject())
                ).toString()
            )
        }
    }

    /** Exposed for tests: the workbook's `content.json`, built from the same validated tree. */
    fun buildContent(topics: List<MapTopic>): JSONArray {
        if (topics.isEmpty()) throw ExportException("The project is empty")
        val byId = topics.associateBy { it.id }
        if (byId.size != topics.size) throw ExportException("Duplicate entity IDs")
        val roots = topics.filter { it.kind == MapKind.PROJECT && it.parentId == null }
        if (roots.size != 1) throw ExportException("Exactly one project root is required")
        val root = roots.single()

        val children = HashMap<String, MutableList<MapTopic>>()
        for (item in topics) {
            if (item.id == root.id) continue
            val parent = item.parentId
            if (parent == null || parent !in byId) {
                throw ExportException("Missing parent for ${item.id}")
            }
            children.getOrPut(parent) { mutableListOf() }.add(item)
        }
        // Stable order: the entity's own order, then its id — never its title, which repeats.
        children.values.forEach { list ->
            list.sortWith(compareBy({ it.order }, { it.id }))
        }

        // Iterative postorder, so a deep subtask chain cannot overflow the stack.
        val built = HashMap<String, JSONObject>()
        val active = HashSet<String>()
        val stack = ArrayDeque<Pair<MapTopic, Boolean>>()
        stack.addLast(root to false)
        var count = 0
        while (stack.isNotEmpty()) {
            val (item, exit) = stack.removeLast()
            if (!exit) {
                if (!active.add(item.id)) throw ExportException("Cycle at ${item.id}")
                stack.addLast(item to true)
                children[item.id].orEmpty().asReversed().forEach { stack.addLast(it to false) }
            } else {
                active.remove(item.id)
                count++
                built[item.id] = topicJson(item, children[item.id].orEmpty().map { built.getValue(it.id) })
            }
        }
        if (count != topics.size) throw ExportException("Disconnected map nodes")

        val sheet = JSONObject()
            .put("id", sheetId(root.id))
            .put("class", "sheet")
            .put("title", root.title)
            .put("rootTopic", built.getValue(root.id))
        return JSONArray().put(sheet)
    }

    private fun topicJson(item: MapTopic, children: List<JSONObject>): JSONObject {
        val topic = JSONObject()
            // The source entity's own id keeps two identically titled nodes distinct.
            .put("id", topicId(item.id))
            .put("class", "topic")
            .put("title", item.title)
        val status = when (item.status) {
            MapStatus.NONE -> ""
            MapStatus.TODO -> "To do"
            MapStatus.CURRENT -> "Current"
            MapStatus.DONE -> "Done"
            MapStatus.BLOCKED -> "Blocked"
        }
        val note = listOf(item.detail, status).filter { it.isNotBlank() }.joinToString(" · ")
        if (note.isNotBlank()) {
            topic.put(
                "notes",
                JSONObject().put("plain", JSONObject().put("content", note))
            )
        }
        if (children.isNotEmpty()) {
            topic.put(
                "children",
                JSONObject().put("attached", JSONArray().apply { children.forEach { put(it) } })
            )
        }
        return topic
    }

    private fun topicId(sourceId: String) = "virlin-" + sourceId.filter { it.isLetterOrDigit() || it == '-' }
        .ifBlank { sourceId.hashCode().toUInt().toString(16) }

    private fun sheetId(sourceId: String) = "sheet-" + topicId(sourceId)

    private fun ZipOutputStream.entry(name: String, body: String) {
        putNextEntry(ZipEntry(name))
        write(body.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}

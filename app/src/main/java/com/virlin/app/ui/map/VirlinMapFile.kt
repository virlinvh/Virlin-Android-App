package com.virlin.app.ui.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * The portable `.virlinmap` snapshot, taken from the supplied reference codec.
 *
 * It is a SNAPSHOT for sharing and preview only. The live map always reads the current
 * project, workstreams and tasks; decoding one of these files never writes to them.
 */
data class VirlinMapSnapshot(
    val projectId: String,
    val projectTitle: String,
    val topics: List<MapTopic>,
    val appearance: MapAppearance,
)

/** Versioned UTF-8 JSON. The caller writes the bytes through the document picker. */
fun encodeVirlinMap(snapshot: VirlinMapSnapshot): ByteArray {
    val appearance = JSONObject()
        .put("layout", snapshot.appearance.layout.name)
        .put("paletteIndex", snapshot.appearance.paletteIndex)
    val overrides = JSONObject()
    snapshot.appearance.nodeColors.forEach { (id, argb) -> overrides.put(id, argb.toString(16)) }
    appearance.put("nodeColors", overrides)
    val branches = JSONObject()
    snapshot.appearance.branchColors.forEach { (id, argb) -> branches.put(id, argb.toString(16)) }
    appearance.put("branchColors", branches)
    val nodes = JSONArray()
    snapshot.topics.forEach { t ->
        nodes.put(
            JSONObject()
                .put("id", t.id).put("parentId", t.parentId ?: JSONObject.NULL)
                .put("title", t.title).put("detail", t.detail)
                .put("kind", t.kind.name).put("status", t.status.name)
                .put("order", t.order)
        )
    }
    return JSONObject().put("format", "virlin-map")
        .put("schemaVersion", 1)
        .put("projectId", snapshot.projectId)
        .put("projectTitle", snapshot.projectTitle)
        .put("appearance", appearance)
        .put("topics", nodes)
        .toString().toByteArray(Charsets.UTF_8)
}

/**
 * Decodes into an isolated preview. Size, schema version, ids, parents and cycles are all
 * checked before anything is shown, and nothing here touches the repository.
 */
fun decodeVirlinMap(bytes: ByteArray): VirlinMapSnapshot {
    require(bytes.size <= 16 * 1024 * 1024) { "Map file is too large" }
    val json = JSONObject(bytes.toString(Charsets.UTF_8))
    require(json.optString("format") == "virlin-map") { "Not a Virlin map" }
    require(json.optInt("schemaVersion", -1) == 1) { "Unsupported Virlin map version" }
    val nodes = json.getJSONArray("topics")
    require(nodes.length() in 1..100_000) { "Unusable map node count" }
    val topics = buildList(nodes.length()) {
        for (i in 0 until nodes.length()) {
            val n = nodes.getJSONObject(i)
            add(
                MapTopic(
                    id = n.getString("id"),
                    parentId = if (n.isNull("parentId")) null else n.getString("parentId"),
                    title = n.getString("title"), detail = n.optString("detail"),
                    kind = enumOrNull<MapKind>(n.getString("kind")) ?: error("Unknown node kind"),
                    status = enumOrNull<MapStatus>(n.optString("status", "NONE")) ?: MapStatus.NONE,
                    order = n.optInt("order", 0),
                )
            )
        }
    }
    val ids = topics.mapTo(HashSet()) { it.id }
    require(ids.size == topics.size) { "Duplicate map IDs" }
    require(topics.count { it.kind == MapKind.PROJECT && it.parentId == null } == 1) {
        "A map needs exactly one project root"
    }
    topics.forEach { t ->
        require(t.parentId == null || t.parentId in ids) { "Map node ${t.id} has no parent" }
        require(t.parentId != t.id) { "Map node ${t.id} is its own parent" }
    }
    requireConnectedAcyclic(topics)

    val a = json.getJSONObject("appearance")
    return VirlinMapSnapshot(
        projectId = json.getString("projectId"),
        projectTitle = json.getString("projectTitle"),
        topics = topics,
        appearance = MapAppearance(
            layout = enumOrNull<MapLayout>(a.optString("layout")) ?: MapLayout.RIGHT_TREE,
            paletteIndex = a.optInt("paletteIndex", 0),
            nodeColors = a.colorMap("nodeColors", ids),
            branchColors = a.colorMap("branchColors", ids),
        ),
    )
}

/** Every node must be reachable from the root exactly once: no cycles, no orphan islands. */
private fun requireConnectedAcyclic(topics: List<MapTopic>) {
    val children = topics.groupBy { it.parentId }
    val root = topics.first { it.parentId == null }
    val seen = HashSet<String>()
    val stack = ArrayDeque<MapTopic>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        require(seen.add(node.id)) { "Cycle in the map at ${node.id}" }
        children[node.id].orEmpty().forEach(stack::addLast)
    }
    require(seen.size == topics.size) { "Map contains disconnected nodes" }
}

private fun JSONObject.colorMap(field: String, ids: Set<String>): Map<String, Long> {
    val source = optJSONObject(field) ?: return emptyMap()
    val out = mutableMapOf<String, Long>()
    val keys = source.keys()
    while (keys.hasNext()) {
        val id = keys.next()
        // An override for an id this file does not contain is discarded rather than kept.
        if (id in ids) source.getString(id).toULongOrNull(16)?.let { out[id] = it.toLong() }
    }
    return out
}

private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }

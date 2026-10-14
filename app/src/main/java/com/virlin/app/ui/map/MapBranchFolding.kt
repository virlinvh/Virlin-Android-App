package com.virlin.app.ui.map

/** View-only folding; canonical topics and ownership are never rewritten. */
internal fun mapDescendants(topics: List<MapTopic>, id: String): Set<String> {
    val children = topics.groupBy { it.parentId }
    val seen = mutableSetOf(id)
    val pending = ArrayDeque<String>()
    pending.add(id)
    while (pending.isNotEmpty()) {
        children[pending.removeLast()].orEmpty().forEach { child ->
            if (seen.add(child.id)) pending.add(child.id)
        }
    }
    return seen - id
}

/** A temporary reading view rooted at one node; source topics and parent links stay untouched. */
internal fun focusedMapTopics(topics: List<MapTopic>, rootId: String?): List<MapTopic> {
    if (rootId == null || topics.none { it.id == rootId }) return topics
    val ids = mapDescendants(topics, rootId) + rootId
    return topics.filter { it.id in ids }.map { topic ->
        if (topic.id == rootId) topic.copy(parentId = null) else topic
    }
}

internal fun foldedMapTopics(topics: List<MapTopic>, collapsed: Set<String>): List<MapTopic> {
    val hidden = collapsed.flatMapTo(mutableSetOf()) { mapDescendants(topics, it) }
    return topics.filterNot { it.id in hidden }
}

private fun mapDepths(topics: List<MapTopic>): Map<String, Int> {
    val ids = topics.mapTo(mutableSetOf()) { it.id }
    val children = topics.groupBy { it.parentId }
    val depths = mutableMapOf<String, Int>()
    val pending = ArrayDeque<String>()
    topics.filter { it.parentId !in ids }.forEach { depths[it.id] = 0; pending.add(it.id) }
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        children[id].orEmpty().forEach {
            if (it.id !in depths) { depths[it.id] = depths.getValue(id) + 1; pending.add(it.id) }
        }
    }
    return depths
}

/** Fold only the deepest currently visible level; repeated clicks reach the roots. */
internal fun collapseMapLevel(topics: List<MapTopic>, collapsed: Set<String>): Set<String> {
    val depths = mapDepths(topics)
    val visible = foldedMapTopics(topics, collapsed)
    val deepest = visible.maxOfOrNull { depths[it.id] ?: 0 } ?: return collapsed
    if (deepest == 0) return collapsed
    return collapsed + visible.filter { depths[it.id] == deepest }.mapNotNull { it.parentId }
}

/** Reveal one frontier only, including when the parent was folded individually. */
internal fun expandMapLevel(topics: List<MapTopic>, collapsed: Set<String>): Set<String> {
    val depths = mapDepths(topics)
    val children = topics.groupBy { it.parentId }
    val frontier = foldedMapTopics(topics, collapsed).filter { it.id in collapsed && children[it.id].orEmpty().isNotEmpty() }
    val shallowest = frontier.minOfOrNull { depths[it.id] ?: 0 } ?: return collapsed
    val opening = frontier.filter { (depths[it.id] ?: 0) == shallowest }.mapTo(mutableSetOf()) { it.id }
    val next = opening.flatMap { children[it].orEmpty() }.filter { children[it.id].orEmpty().isNotEmpty() }.map { it.id }
    return (collapsed - opening) + next
}

/** Apply one level of folding only below the selected box, preserving every other branch. */
internal fun collapseMapBranchLevel(topics: List<MapTopic>, collapsed: Set<String>, selectedId: String): Set<String> =
    mapBranchLevel(topics, collapsed, selectedId, ::collapseMapLevel)

/** Reveal one frontier below the selected box, without opening unrelated branches. */
internal fun expandMapBranchLevel(topics: List<MapTopic>, collapsed: Set<String>, selectedId: String): Set<String> =
    mapBranchLevel(topics, collapsed, selectedId, ::expandMapLevel)

private fun mapBranchLevel(
    topics: List<MapTopic>, collapsed: Set<String>, selectedId: String,
    operation: (List<MapTopic>, Set<String>) -> Set<String>,
): Set<String> {
    if (topics.none { it.id == selectedId }) return collapsed
    val ids = mapDescendants(topics, selectedId) + selectedId
    val branch = topics.filter { it.id in ids }
    return (collapsed - ids) + operation(branch, collapsed intersect ids)
}

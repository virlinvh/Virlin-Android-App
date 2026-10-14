package com.virlin.app.ui.map

import kotlin.math.max
import kotlin.math.min

internal const val MAP_MIN_ZOOM = .005f
internal const val MAP_MAX_ZOOM = 3f

/** Keep executable nodes and their ancestry; empty workstreams are not executable branches. */
internal fun taskOnlyMapTopics(topics: List<MapTopic>): List<MapTopic> {
    val byId = topics.associateBy { it.id }
    val keep = topics.filter { it.parentId == null }.mapTo(mutableSetOf()) { it.id }
    topics.filter { it.kind == MapKind.TASK }.forEach { task ->
        var node: MapTopic? = task
        val seen = mutableSetOf<String>()
        while (node != null && seen.add(node.id)) {
            keep += node.id
            node = node.parentId?.let(byId::get)
        }
    }
    return topics.filter { it.id in keep }.map { it.copy(detail = "") }
}

internal fun fitMapZoom(viewWidth: Float, viewHeight: Float, mapWidth: Float, mapHeight: Float): Float =
    min(1f, min(max(1f, viewWidth) / max(1f, mapWidth),
        max(1f, viewHeight) / max(1f, mapHeight))).coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM)

/** Preserve the world point under the viewport centre when pressing either zoom button. */
internal fun zoomMapOffset(offset: Float, centre: Float, oldZoom: Float, newZoom: Float): Float =
    centre - (centre - offset) * (newZoom / oldZoom)

package com.virlin.app.ui.map

/** A node override is independent of the project palette and never recolors its descendants. */
internal fun toggleBoxColor(appearance: MapAppearance, nodeId: String, color: Long): MapAppearance =
    appearance.copy(nodeColors = if (appearance.nodeColors[nodeId] == color)
        appearance.nodeColors - nodeId else appearance.nodeColors + (nodeId to color))

internal fun setBoxColor(appearance: MapAppearance, nodeId: String, color: Long): MapAppearance =
    appearance.copy(nodeColors = appearance.nodeColors + (nodeId to color))

internal fun clearBoxColor(appearance: MapAppearance, nodeId: String): MapAppearance =
    appearance.copy(nodeColors = appearance.nodeColors - nodeId)

internal val boxHighlightColors = listOf(
    0xFF52A887, 0xFF95BC7C, 0xFF93C9C0, 0xFF438FAD,
    0xFF82A9DE, 0xFF7582D1, 0xFFAC85D3, 0xFFD28CC0,
    0xFFE58DA6, 0xFFD88474, 0xFFEDAD82, 0xFFE6C26C,
    0xFFE8C451, 0xFFF0D36A, 0xFFF4DD83, 0xFFF6E7A5, 0xFFF7EDC2,
)

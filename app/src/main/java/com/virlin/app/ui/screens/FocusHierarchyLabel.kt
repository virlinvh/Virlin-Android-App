package com.virlin.app.ui.screens

/** Presentation only: never rename persisted nodes or use short labels as navigation IDs. */
internal fun compactFocusHierarchyLabel(fullName: String): String {
    val word = fullName.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
    val points = word.codePoints().toArray()
    return if (points.size <= 10) word else String(points, 0, 9) + "…"
}

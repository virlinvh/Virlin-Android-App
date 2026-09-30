package com.virlin.app.domain.model

/**
 * Project identity (2026-09-21): how a project is recognised everywhere its work appears.
 *
 * `Project.iconPath` is the single persisted reference (custom image in the managed store).
 * Everything else here is a pure, deterministic derivation from the project itself so a project
 * without a custom icon is still distinguishable — and looks the same on every launch:
 *
 *  - [initials]  "Claude · Virlin" → "CV", "Antigravity" → "A", "Codex · MBA Research" → "CM"
 *  - [hue]       a stable 0–359° hue picked from the project id (never random, never time-based)
 *
 * WorkStreams and Tasks never carry their own identity; they resolve it through `projectId`.
 */
object ProjectIdentity {

    /**
     * One or two initials from a display name. Words are split on whitespace and separators
     * ("·", "-", "/", "&", ":"); the first letter of the first two words is used, upper-cased.
     * Falls back to "?" when the name has no letters or digits.
     */
    fun initials(name: String): String {
        val words = name.split(Regex("[\\s·\\-/&:|,.]+")).map { w -> w.trim() }.filter { w -> w.any { it.isLetterOrDigit() } }
        val letters = words.take(2).map { w -> w.first { it.isLetterOrDigit() }.uppercaseChar() }
        return if (letters.isEmpty()) "?" else letters.joinToString("")
    }

    /** Stable hue for a project id: same id → same hue, forever; different ids spread around the wheel. */
    fun hue(projectId: String): Int {
        var h = 0
        for (c in projectId) h = (h * 31 + c.code) and 0x7fffffff
        // Golden-angle scatter keeps neighbouring ids visually apart.
        return ((h % 360) * 137.508 % 360.0).toInt()
    }

    /**
     * Task/WorkStream → Project resolution used by every surface: the owning project by id, or
     * null when the item is projectless or the project no longer exists (callers then fall back
     * to the item's own display name for initials).
     */
    fun resolve(projectId: String?, projects: List<Project>): Project? =
        projectId?.let { id -> projects.firstOrNull { it.id == id } }

    /** Whether a persisted reference should be treated as "no custom icon". */
    fun hasCustomIcon(iconPath: String?): Boolean = !iconPath.isNullOrBlank()
}

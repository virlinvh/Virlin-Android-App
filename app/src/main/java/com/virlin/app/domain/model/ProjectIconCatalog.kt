package com.virlin.app.domain.model

/**
 * Built-in project icon library (Phase 3) — semantic, STABLE string ids persisted in
 * `Project.iconId`. Presentation maps an id to a vector + colours (`ui/components`); nothing
 * resource-dependent is ever stored. Unknown ids resolve to null → the caller falls through.
 */
object ProjectIconCatalog {

    /** Curated ids, in display order. */
    val ids: List<String> = listOf(
        "code", "terminal", "laptop", "mobile", "web", "ai", "brain", "research",
        "book", "education", "writing", "design", "palette", "analytics", "database", "cloud",
        "automation", "rocket", "business", "target", "lab", "folder", "tools", "idea"
    )

    /** Human label per id (also the accessibility name: "<label> icon"). */
    fun label(id: String): String? = labels[id]

    fun isKnown(id: String?): Boolean = id != null && id in labels

    private val labels = mapOf(
        "code" to "Code", "terminal" to "Terminal", "laptop" to "Laptop", "mobile" to "Mobile app",
        "web" to "Web", "ai" to "AI", "brain" to "Brain", "research" to "Research",
        "book" to "Book", "education" to "Education", "writing" to "Writing", "design" to "Design",
        "palette" to "Palette", "analytics" to "Analytics", "database" to "Database", "cloud" to "Cloud",
        "automation" to "Automation", "rocket" to "Rocket", "business" to "Business", "target" to "Target",
        "lab" to "Lab", "folder" to "Folder", "tools" to "Tools", "idea" to "Idea"
    )

    /**
     * Deterministic AUTOMATIC icon for a project without an explicit choice: simple keyword rules on
     * the title (first matching rule wins, checked in this order), otherwise a generic icon picked
     * by the project's stable identity hash — never random, identical on every launch.
     */
    fun autoIconId(title: String, projectId: String): String {
        val t = title.lowercase()
        // Keywords match at a word START only ("develop" → "development", but "ai" never matches "maintenance").
        for ((keywords, id) in rules) if (keywords.any { kw -> Regex("(^|[^a-z0-9])" + Regex.escape(kw)).containsMatchIn(t) }) return id
        return generic[ProjectIdentity.hue(projectId) % generic.size]
    }

    private val rules: List<Pair<List<String>, String>> = listOf(
        listOf("psycholog", "brain", "mind", "cognit") to "brain",
        listOf("research", "study", "thesis", "paper") to "research",
        listOf("education", "college", "course", "learning", "skills", "school", "class", "lab") to "education",
        listOf("business", "mba", "market", "finance", "sales", "company") to "business",
        listOf("design", "ui", "ux", "brand") to "design",
        listOf("cloud", "sync", "server", "backend") to "cloud",
        listOf("automation", "workflow", "pipeline", "automate") to "automation",
        listOf("data", "analytics", "report", "metrics") to "analytics",
        listOf("fix", "repair", "maintenance", "tools") to "tools",
        listOf("mobile", "android", "ios") to "mobile",
        listOf("web", "site", "website") to "web",
        listOf("ai", "agent", "model", "llm") to "ai",
        listOf("writing", "blog", "book", "notes", "essay") to "writing",
        listOf("develop", "code", "software", "engineering", "build", "app", "dev") to "code",
        listOf("idea", "concept") to "idea"
    )

    /** Generic pool for projects that match no rule. */
    private val generic = listOf("folder", "target", "rocket", "idea", "book")
}

/** Resolved icon choice for a project — the single priority rule every surface uses. */
sealed interface ProjectIconSelection {
    /** A user-chosen image in the managed store. */
    data class Custom(val path: String) : ProjectIconSelection
    /** A user-chosen built-in icon. */
    data class BuiltIn(val id: String) : ProjectIconSelection
    /** No explicit choice: the deterministic automatic built-in icon. */
    data class Auto(val id: String) : ProjectIconSelection

    companion object {
        /** custom image → chosen built-in → automatic built-in. Initials remain the last visual safety net. */
        fun of(project: Project): ProjectIconSelection = when {
            ProjectIdentity.hasCustomIcon(project.iconPath) -> Custom(project.iconPath!!)
            ProjectIconCatalog.isKnown(project.iconId) -> BuiltIn(project.iconId!!)
            else -> Auto(ProjectIconCatalog.autoIconId(project.title, project.id))
        }
    }
}

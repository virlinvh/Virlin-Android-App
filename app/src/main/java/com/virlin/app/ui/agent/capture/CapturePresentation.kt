package com.virlin.app.ui.agent.capture

import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.CaptureType
import java.time.Duration
import java.time.Instant

/** Pure, testable rendering helpers for capture rows. No parsing of meaning — display only. */
object CapturePresentation {
    const val PreviewChars = 120

    /** First line(s), truncated — a list row never renders a whole prompt. */
    fun preview(item: CaptureItem): String {
        val raw = when (item.type) {
            CaptureType.LINK -> item.title?.takeIf { it.isNotBlank() } ?: displayUrl(item.sourceUrl ?: "")
            else -> item.title?.takeIf { it.isNotBlank() } ?: item.content
        }
        val oneLine = raw.trim().replace(Regex("\\s*\\n+\\s*"), " · ")
        return if (oneLine.length <= PreviewChars) oneLine else oneLine.take(PreviewChars - 1).trimEnd() + "…"
    }

    /** Scheme dropped for display only; the stored URL is untouched. */
    fun displayUrl(url: String): String = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    fun age(createdAt: Instant, now: Instant): String {
        val d = Duration.between(createdAt, now).coerceAtLeast(Duration.ZERO)
        return when {
            d < Duration.ofMinutes(1) -> "now"
            d < Duration.ofHours(1) -> "${d.toMinutes()}m ago"
            d < Duration.ofDays(1) -> "${d.toHours()}h ago"
            else -> "${d.toDays()}d ago"
        }
    }

    fun typeLabel(t: CaptureType): String = when (t) { CaptureType.NOTE -> "Note"; CaptureType.PROMPT -> "Prompt"; CaptureType.LINK -> "Link" }
}

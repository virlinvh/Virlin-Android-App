package com.virlin.app.domain.command.text

import java.time.Duration

/**
 * Deterministic duration phrases only (Pass 12): `5 min`, `10 minutes`, `15m`, `1 hour`,
 * `2 hours`, `90 minutes`, `1h 30m`. No calendar language ("tomorrow", "this evening") — that
 * is a future time-interpreter pass. Zero, negative, malformed and absurdly long values are
 * rejected as null; callers decide how to ask.
 */
object DurationParser {
    val Max: Duration = Duration.ofHours(24)

    private val unit = Regex("""^(\d{1,4})\s*(m|min|mins|minute|minutes|h|hr|hrs|hour|hours)$""", RegexOption.IGNORE_CASE)
    private val compound = Regex("""^(\d{1,4})\s*(h|hr|hrs|hour|hours)\s+(\d{1,4})\s*(m|min|mins|minute|minutes)$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Duration? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        val d = compound.matchEntire(t)?.let { m -> Duration.ofHours(m.groupValues[1].toLong()).plusMinutes(m.groupValues[3].toLong()) }
            ?: unit.matchEntire(t)?.let { m ->
                val n = m.groupValues[1].toLong()
                if (m.groupValues[2].lowercase().startsWith("h")) Duration.ofHours(n) else Duration.ofMinutes(n)
            } ?: return null
        return d.takeIf { !it.isZero && !it.isNegative && it <= Max }
    }

    /** True when [text] looks like a duration phrase at all (used to give a precise error). */
    fun looksLikeDuration(text: String): Boolean = Regex("""^\d""").containsMatchIn(text.trim())
}

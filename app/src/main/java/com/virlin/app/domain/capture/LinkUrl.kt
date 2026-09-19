package com.virlin.app.domain.capture

import java.net.URI

/**
 * Local-only HTTP(S) URL validation for Capture LINK.
 * Never fetches. Never resolves redirects. Rejects non-web schemes.
 */
object LinkUrl {

    sealed class Result {
        data class Valid(val canonical: String) : Result()
        data object Invalid : Result()
    }

    private val blockedSchemes = setOf(
        "javascript", "file", "content", "intent", "data", "about", "blob", "mailto", "tel"
    )

    /**
     * Validate [raw]. Optionally normalize unambiguous domain-shaped input
     * (e.g. `example.com` → `https://example.com`). Arbitrary prose is never coerced.
     */
    fun parse(raw: String): Result {
        val t = raw.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return Result.Invalid

        val lower = t.lowercase()
        val schemePrefix = blockedSchemes.firstOrNull { lower.startsWith("$it:") }
        if (schemePrefix != null) return Result.Invalid

        val candidate = when {
            lower.startsWith("https://") || lower.startsWith("http://") -> t
            isDomainShaped(t) -> "https://$t"
            else -> return Result.Invalid
        }

        return try {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return Result.Invalid
            val host = uri.host
            if (host.isNullOrBlank()) return Result.Invalid
            if (host.contains(' ')) return Result.Invalid
            Result.Valid(candidate)
        } catch (_: Exception) {
            Result.Invalid
        }
    }

    fun isValid(raw: String): Boolean = parse(raw) is Result.Valid

    fun canonicalOrNull(raw: String): String? = (parse(raw) as? Result.Valid)?.canonical

    /** Host/path for display only — never mutates stored URL. */
    fun displayUrl(canonical: String): String =
        canonical
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')

    /** Card title when user left title blank. */
    fun displayLabel(title: String?, canonical: String): String {
        val custom = title?.trim()?.takeIf { it.isNotEmpty() }
        if (custom != null) return custom
        return displayUrl(canonical).substringBefore('/').ifBlank { displayUrl(canonical) }
    }

    /**
     * Unambiguous host-like input: has a dot in the host segment, no scheme, URL-safe characters.
     * Does not accept spaces or free prose.
     */
    fun isDomainShaped(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return false
        if (t.contains("://")) return false
        if (t.startsWith(".") || t.startsWith("/")) return false
        val hostPart = t.substringBefore('/').substringBefore('?').substringBefore('#')
        if (!hostPart.contains('.')) return false
        if (hostPart.startsWith(".") || hostPart.endsWith(".")) return false
        // Letters/digits and common host + path punctuation only
        return t.matches(Regex("^[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+$"))
    }
}

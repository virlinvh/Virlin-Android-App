package com.virlin.app.platform

import android.content.Intent
import android.net.Uri
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Shareable deep links that open a specific owner-scoped note inside Virlin.
 *
 * Format: `virlin://note/<base64url(ownerKey)>`
 *
 * The path segment is Base64url of the opaque [ownerKey] so slash-bearing knowledge draft
 * keys stay a single path segment. The link never carries note body text — only the local
 * document identity. Opening the link is navigation-only; it does not mutate Room.
 */
object NoteDeepLinks {
    const val SCHEME = "virlin"
    const val HOST = "note"
    const val EXTRA_OWNER_KEY = "virlin.noteOwnerKey"

    private const val MAX_OWNER_KEY_LENGTH = 512
    private val OWNER_KEY = Regex(
        "^(global" +
            "|task-[A-Za-z0-9._-]+" +
            "|project-[A-Za-z0-9._-]+" +
            "|capture(-legacy)?-[A-Za-z0-9._-]+" +
            "|knowledge/[A-Za-z0-9_-]+/[A-Za-z0-9._-]+(/[A-Za-z0-9_-]+)?)$",
    )

    fun isValidOwnerKey(ownerKey: String): Boolean =
        ownerKey.isNotBlank() &&
            ownerKey.length <= MAX_OWNER_KEY_LENGTH &&
            !ownerKey.any { it.isISOControl() } &&
            OWNER_KEY.matches(ownerKey)

    /** Canonical share URI, or null when [ownerKey] is rejected. */
    fun uriFor(ownerKey: String): Uri? {
        if (!isValidOwnerKey(ownerKey)) return null
        val segment = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ownerKey.toByteArray(StandardCharsets.UTF_8))
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendPath(segment)
            .build()
    }

    fun linkText(ownerKey: String): String? = uriFor(ownerKey)?.toString()

    /**
     * Parse a note deep link from an incoming VIEW intent's data URI, or from the explicit
     * [EXTRA_OWNER_KEY] used by the share helper when launching Virlin directly.
     */
    fun ownerKeyFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        intent.getStringExtra(EXTRA_OWNER_KEY)?.let { extra ->
            if (isValidOwnerKey(extra)) return extra
        }
        return ownerKeyFromUri(intent.data)
    }

    fun ownerKeyFromUri(uri: Uri?): String? {
        if (uri == null) return null
        if (uri.scheme != SCHEME) return null
        if (uri.host != HOST) return null
        val segment = uri.pathSegments.singleOrNull()?.takeIf { it.isNotBlank() } ?: return null
        if (segment.length > 1024) return null
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        return decoded.takeIf(::isValidOwnerKey)
    }

    /**
     * Sharesheet text payload = the deep link only (plus an optional short title line).
     * Callers must invoke only after an explicit user action.
     */
    fun shareIntent(ownerKey: String, title: String? = null): Intent? {
        val link = linkText(ownerKey) ?: return null
        val body = title?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it\n$link" } ?: link
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title.trim())
            putExtra(EXTRA_OWNER_KEY, ownerKey)
        }
    }
}

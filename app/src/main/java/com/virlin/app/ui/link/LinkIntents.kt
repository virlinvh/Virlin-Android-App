package com.virlin.app.ui.link

import android.content.Intent
import android.net.Uri
import com.virlin.app.domain.capture.LinkUrl

/**
 * Safe Android intents for Capture Link. No browser package hard-coding.
 * Callers must only invoke after explicit user action.
 */
object LinkIntents {

    /** ACTION_VIEW for http(s) only. Null if URL fails validation. */
    fun viewIntent(canonicalUrl: String): Intent? {
        val valid = LinkUrl.canonicalOrNull(canonicalUrl) ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(valid))
    }

    /** Sharesheet text payload = exact canonical URL. */
    fun shareIntent(canonicalUrl: String): Intent? {
        val valid = LinkUrl.canonicalOrNull(canonicalUrl) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, valid)
        }
    }
}

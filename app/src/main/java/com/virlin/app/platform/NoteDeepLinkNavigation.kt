package com.virlin.app.platform

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pending note deep-link destination. Set from the launch/new intent, consumed once by the
 * app navigation root after Room is ready. Navigation only — never a domain mutation.
 */
object NoteDeepLinkNavigation {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    fun offer(intent: Intent?) {
        NoteDeepLinks.ownerKeyFromIntent(intent)?.let { _pending.value = it }
    }

    fun consume(): String? = _pending.value.also { _pending.value = null }
}

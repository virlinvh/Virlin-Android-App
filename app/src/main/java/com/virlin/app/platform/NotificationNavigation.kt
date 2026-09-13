package com.virlin.app.platform

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where a notification body/CHECK tap wants the app to go. Set from the launch/new intent,
 * consumed once by the Now screen. Navigation only — never a domain mutation.
 */
object NotificationNavigation {
    data class Target(val streamId: String, val target: NotificationTarget)

    private val _pending = MutableStateFlow<Target?>(null)
    val pending: StateFlow<Target?> = _pending

    fun fromIntent(intent: Intent?): Target? {
        val id = intent?.getStringExtra(AttentionNotifications.EXTRA_STREAM) ?: return null
        val t = intent.getStringExtra(AttentionNotifications.EXTRA_TARGET)?.let { runCatching { NotificationTarget.valueOf(it) }.getOrNull() } ?: return null
        return Target(id, t)
    }

    fun offer(intent: Intent?) { fromIntent(intent)?.let { _pending.value = it } }
    fun consume(): Target? = _pending.value.also { _pending.value = null }
}

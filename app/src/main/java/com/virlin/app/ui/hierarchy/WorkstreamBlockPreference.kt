package com.virlin.app.ui.hierarchy

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Display-only, project-scoped opt-in. Ordering remains in the hierarchy database. */
@Composable internal fun rememberWorkstreamBlocks(projectId: String): Pair<Boolean, (Boolean) -> Unit> {
    val prefs = LocalContext.current.getSharedPreferences("project_block_order", 0)
    var enabled by remember(projectId) { mutableStateOf(prefs.getBoolean(projectId, false)) }
    DisposableEffect(projectId, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
            if (key == projectId) enabled = store.getBoolean(projectId, false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled to { value -> prefs.edit().putBoolean(projectId, value).apply() }
}

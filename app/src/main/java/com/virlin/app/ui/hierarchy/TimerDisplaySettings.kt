package com.virlin.app.ui.hierarchy

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

@Composable
internal fun timerRotationSeconds(scopeId: String, projectId: String? = null): Int {
    val preferences = LocalContext.current.getSharedPreferences("hierarchy_timer_display", 0)
    val projectKey = projectId?.let { "project:$it" }
    fun readSeconds(): Int = preferences.getInt(
        projectKey?.takeIf { preferences.contains(it) } ?: scopeId, 6).coerceIn(5, 7)
    var seconds by remember(scopeId, projectId) { mutableIntStateOf(readSeconds()) }
    DisposableEffect(preferences, scopeId, projectId) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
            if (key == scopeId || key == projectKey) seconds = readSeconds()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return seconds
}

@Composable
internal fun TimerDisplaySettings(scopeId: String) {
    val preferences = LocalContext.current.getSharedPreferences("hierarchy_timer_display", 0)
    val seconds = timerRotationSeconds(scopeId)
    Text("Timer display", fontSize = 14.sp)
    Text("Focused / External · change every $seconds seconds", fontSize = 12.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        (5..7).forEach { value ->
            TextButton(onClick = { preferences.edit().putInt(scopeId, value).apply() }) {
                Text(if (seconds == value) "$value s · selected" else "$value s")
            }
        }
    }
}

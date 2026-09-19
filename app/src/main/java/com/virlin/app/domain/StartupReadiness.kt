package com.virlin.app.domain

/**
 * Centralized repository / cold-start readiness. UI observes this once at the composition
 * root; individual screens do not poll initialization flags.
 */
sealed class StartupReadiness {
    data object Initializing : StartupReadiness()
    data object Ready : StartupReadiness()
    data class Error(val message: String) : StartupReadiness()
}

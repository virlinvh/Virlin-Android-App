package com.virlin.app.domain.time

import java.time.Instant

/**
 * Injectable time source. Domain actions never call `System.currentTimeMillis()` directly,
 * so every timestamp — and every duration derived from timestamps — is deterministic in tests.
 * `java.time` is available from API 26 (Virlin's minSdk).
 */
interface VirlinClock {
    fun now(): Instant
}

object SystemVirlinClock : VirlinClock {
    override fun now(): Instant = Instant.now()
}

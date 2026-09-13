package com.virlin.app.domain.id

import java.util.UUID

/**
 * Injectable identifier source for cycles, focus sessions, snapshots and events.
 * Production uses UUIDs; tests use a sequential provider so IDs are deterministic.
 */
interface IdProvider {
    /** [prefix] is a short type tag, e.g. "cyc", "fs", "snap", "ev" — useful when debugging. */
    fun newId(prefix: String): String
}

object UuidIdProvider : IdProvider {
    override fun newId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}

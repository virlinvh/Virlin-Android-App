package com.virlin.app.domain.attention

import java.time.Instant

/**
 * A user's PRIORITY PREFERENCE for one Needs You item (Phase 04).
 *
 * Three concepts stay strictly separate:
 * - **effective rank** — where the item sits in the queue right now (`NeedsYouOrder.queue`);
 * - **preferred position** — where a policy would LIKE it placed when it applies;
 * - **scope** — how long that preference lives.
 *
 * A preference never owns a rank: the queue always recalculates effective order, so two items may
 * both prefer position 1 while their effective ranks stay unique.
 *
 * The model is persistence-ready (a stable stream id, a plain int and a typed scope), but Phase 04
 * keeps it in memory only — see [InMemoryPriorityPreferences].
 */
data class PriorityPreference(
    /** Stable identity of the item this preference belongs to — never a list index. */
    val streamId: String,
    /** 1-based position the user asked for. Clamped against the live queue when applied. */
    val preferredPosition: Int,
    val scope: PriorityScope,
    /** When the user set it (ordering tie-break and, later, sync conflict resolution). */
    val createdAt: Instant
) {
    /** Whether this preference still applies at [now]. [PriorityScope.CurrentTerm] never expires yet. */
    fun isActiveAt(now: Instant): Boolean = when (val s = scope) {
        PriorityScope.OneTime -> false                 // consumed by the move that created it
        PriorityScope.Always -> true
        PriorityScope.CurrentTerm -> true              // no term boundary exists yet — see the docs
        is PriorityScope.Until -> now.isBefore(s.expiresAt)
    }
}

/** How long a [PriorityPreference] applies. Typed state — never a UI label. */
sealed interface PriorityScope {
    /**
     * "This time": the current occurrence only. It is NEVER stored — a OneTime choice can neither
     * create a durable policy nor (Phase 07) delete an existing one; it is purely a move now.
     */
    data object OneTime : PriorityScope

    /** "Always prioritize here": re-apply the preferred position whenever the item returns. */
    data object Always : PriorityScope

    /**
     * "This term": applies while the current work term is active. Virlin has no term/semester
     * boundary in the domain yet, so the scope is stored and behaves like [Always] until a real
     * term concept exists; expiration is deliberately unresolved (documented, not faked).
     */
    data object CurrentTerm : PriorityScope

    /** "Custom": applies until [expiresAt], then the item falls back to default insertion. */
    data class Until(val expiresAt: Instant) : PriorityScope
}

/**
 * The store of priority preferences — the abstraction boundary the rest of the app talks to.
 * Production is Room-backed (`data/db/RoomPriorityPreferences`); [InMemoryPriorityPreferences] is
 * for tests, previews and fakes. Callers never see a Room entity, and nothing above this interface
 * knows where the data lives (memory today, the same interface after a future sync layer).
 *
 * Every member is `suspend`: a durable implementation must never touch the database on the main
 * thread.
 */
interface PriorityPreferences {
    suspend fun get(streamId: String): PriorityPreference?
    suspend fun all(): List<PriorityPreference>
    /** Insert or REPLACE — one current preference per item (stable id), never two competing rows. */
    suspend fun save(preference: PriorityPreference)
    suspend fun remove(streamId: String)

    /** The preference for [streamId] only if it still applies at [now]; expired ones are deleted. */
    suspend fun activeFor(streamId: String, now: Instant): PriorityPreference? {
        val pref = get(streamId) ?: return null
        if (pref.isActiveAt(now)) return pref
        remove(streamId)
        return null
    }

    /** Drop every expired preference. Safe to call at start-up; never needs the UI to be open. */
    suspend fun cleanupExpired(now: Instant): Int {
        val expired = all().filterNot { it.isActiveAt(now) }
        expired.forEach { remove(it.streamId) }
        return expired.size
    }
}

/** Process-local preferences for tests, previews and fakes. Does NOT survive process death. */
class InMemoryPriorityPreferences(seed: List<PriorityPreference> = emptyList()) : PriorityPreferences {
    private val byStream = LinkedHashMap<String, PriorityPreference>()
    init { seed.forEach { byStream[it.streamId] = it } }
    override suspend fun get(streamId: String) = byStream[streamId]
    override suspend fun all() = byStream.values.toList()
    override suspend fun save(preference: PriorityPreference) { byStream[preference.streamId] = preference }
    override suspend fun remove(streamId: String) { byStream.remove(streamId) }
}

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
    /** "This time": applies to the current occurrence only; nothing is remembered. */
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
 * The store of priority preferences. Phase 04 ships [InMemoryPriorityPreferences]; a Room-backed
 * implementation can replace it without touching the queue, the actions or the UI.
 */
interface PriorityPreferences {
    fun get(streamId: String): PriorityPreference?
    fun all(): List<PriorityPreference>
    fun put(preference: PriorityPreference)
    fun remove(streamId: String)
}

/** Process-local preferences. Does NOT survive process death — Phase 04 has no persistence. */
class InMemoryPriorityPreferences : PriorityPreferences {
    private val byStream = LinkedHashMap<String, PriorityPreference>()
    override fun get(streamId: String) = byStream[streamId]
    override fun all() = byStream.values.toList()
    override fun put(preference: PriorityPreference) { byStream[preference.streamId] = preference }
    override fun remove(streamId: String) { byStream.remove(streamId) }
}

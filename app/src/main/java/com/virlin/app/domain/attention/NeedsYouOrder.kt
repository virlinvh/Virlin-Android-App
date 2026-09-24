package com.virlin.app.domain.attention

import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import java.time.Instant

/**
 * The ONE Needs You ordering rule (pure; no clock, no I/O).
 *
 * Priority/rank ("what do I want to handle first?") and waiting time ("how long has this been
 * waiting?") are different concepts. The order is:
 *
 * 1. streams with an explicit [WorkStream.attentionRank], ascending (ties → longest waiting first, then id);
 * 2. every unranked stream, longest waiting first (earliest [waitingSince]; ties → id).
 *
 * The comparator depends only on persisted fields — never on the current time — so a timer tick
 * can never re-sort the list, and an explicitly established manual order is never disturbed by
 * waiting time. Unranked / newly arriving streams always sit after the ranked block.
 */
object NeedsYouOrder {

    /**
     * When a CHECK stream started needing the human — the same timestamp the negative timer counts from.
     * CHECK_DUE (from PROCESSING): the scheduled `checkAt`; RETURN_DUE / RESULT_READY (from SNOOZED):
     * the transition stamp `updatedAt`. Fallback: `updatedAt` (always present).
     */
    fun waitingSince(stream: WorkStream): Instant = when (stream.snoozeReason) {
        null -> stream.checkAt ?: stream.updatedAt
        else -> stream.updatedAt
    }

    private val comparator: Comparator<WorkStream> = compareBy<WorkStream> { it.attentionRank == null }   // ranked block first
        .thenBy { it.attentionRank ?: 0 }
        .thenBy { waitingSince(it) }
        .thenBy { it.id }

    /** The Needs You list (only CHECK streams) in display order. */
    fun order(streams: List<WorkStream>): List<WorkStream> =
        streams.filter { it.state == WorkStreamState.CHECK }.sortedWith(comparator)

    /** One queue entry: the item and its EFFECTIVE rank — its 1-based position in the queue. */
    data class Entry(val stream: WorkStream, val rank: Int)

    /**
     * THE canonical Needs You queue: [order] with each item's effective rank attached.
     *
     * Effective rank is always a dense `1..N` over the currently displayed items, whatever the
     * stored [WorkStream.attentionRank] values look like (an item that never moved has none, and a
     * removal can leave a gap in the stored keys). Stored rank is only the persisted sort key;
     * the position IS the rank, so `1, 2, 4, 7` can never reach the UI.
     *
     * Identity is [WorkStream.id] and never depends on position: moving an item is a new rank on
     * the same item, never a new item. Duplicate ids are collapsed (first wins) so a bad upstream
     * list cannot produce two cards claiming the same position.
     */
    fun queue(streams: List<WorkStream>): List<Entry> =
        order(streams).distinctBy { it.id }.mapIndexed { i, s -> Entry(s, i + 1) }

    /** Effective rank of one item, or null when it is not currently in Needs You. */
    fun effectiveRank(streams: List<WorkStream>, streamId: String): Int? =
        queue(streams).firstOrNull { it.stream.id == streamId }?.rank

    /**
     * Re-densify the stored ranks of the ranked block to `1..k` in the queue's own order, so a
     * removal (or any exit from CHECK) closes the gap it left behind. Returns only the streams
     * whose stored rank actually changes; ordering, timers and `updatedAt` are never touched.
     */
    fun normalize(streams: List<WorkStream>): List<WorkStream> {
        val ranked = order(streams).filter { it.attentionRank != null }
        return ranked.mapIndexedNotNull { i, s -> if (s.attentionRank == i + 1) null else s.copy(attentionRank = i + 1) }
    }

    /** Result of [planMove]: the new full order and only the streams whose stored rank must change. */
    data class Move(val order: List<WorkStream>, val changed: List<WorkStream>) {
        val isNoOp: Boolean get() = changed.isEmpty()
    }

    /**
     * ONE atomic reorder: put [streamId] at 1-based [position] in the current Needs You order and
     * shift the others automatically — the user never renumbers anything else.
     *
     * - `[A,B,C,D]`, D → 2 = `[A,D,B,C]`;  A → 4 = `[B,C,D,A]`;  C → 1 = `[C,A,B,D]`.
     * - Out-of-range positions clamp to the first / last position (never an exception, never a gap).
     * - Moving to the current position is a no-op (nothing to persist).
     * - After a move every Needs You stream carries a dense rank 1..n, so the visible order is fully
     *   explicit and can never show duplicate positions.
     *
     * Returns null when [streamId] is not currently in Needs You.
     */
    fun planMove(streams: List<WorkStream>, streamId: String, position: Int): Move? {
        val current = order(streams)
        val from = current.indexOfFirst { it.id == streamId }
        if (from < 0) return null
        val to = (position - 1).coerceIn(0, current.lastIndex)
        val reordered = if (from == to) current else current.toMutableList().also { it.add(to, it.removeAt(from)) }
        val ranked = reordered.mapIndexed { i, s -> if (s.attentionRank == i + 1) s else s.copy(attentionRank = i + 1) }
        val changed = ranked.filterIndexed { i, s -> s.attentionRank != reordered[i].attentionRank }
        return if (from == to) Move(current, emptyList()) else Move(ranked, changed)
    }
}

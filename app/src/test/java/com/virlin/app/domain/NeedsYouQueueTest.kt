package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState.CHECK
import com.virlin.app.domain.model.WorkStreamState.PROCESSING
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.screens.NeedsYouPriority
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Needs You as an ORDERED ATTENTION QUEUE (Phase 03).
 *
 * Effective rank = position in `NeedsYouOrder.queue`, always dense `1..N`; identity is the stream
 * id and never depends on position; moving shifts the displaced items; removal closes the gap; a
 * new arrival appends. Queue position and timer state are independent.
 */
class NeedsYouQueueTest {

    private val t0: Instant = Instant.parse("2026-09-22T09:00:00Z")

    /** CHECK stream waiting since `t0 + offset` — earlier offset = longer waiting = earlier by default. */
    private fun item(id: String, offset: Long, rank: Int? = null) = WorkStream(
        id = id, title = id, state = CHECK, checkAt = t0.plusSeconds(offset), attentionRank = rank,
        createdAt = t0.minusSeconds(7200), updatedAt = t0.minusSeconds(7200)
    )
    private fun processing(id: String, offset: Long) = WorkStream(
        id = id, title = id, state = PROCESSING, checkAt = t0.plusSeconds(offset), createdAt = t0, updatedAt = t0
    )
    private fun queueOf(repo: InMemoryWorkStreamRepository) = NeedsYouOrder.queue(repo.streams.value)
    private fun ids(repo: InMemoryWorkStreamRepository) = queueOf(repo).map { it.stream.id }
    private fun ranks(repo: InMemoryWorkStreamRepository) = queueOf(repo).map { it.rank }

    private fun harness(streams: List<WorkStream>): Pair<InMemoryWorkStreamRepository, DefaultVirlinActions> {
        val repo = InMemoryWorkStreamRepository(seed = streams)
        return repo to DefaultVirlinActions(repo, FakeClock(t0.plusSeconds(60)), SequentialIdProvider())
    }
    private fun abcde() = listOf(item("A", 0), item("B", 10), item("C", 20), item("D", 30), item("E", 40))

    // ------------------------------------------------------------------ 1: the queue itself

    @Test fun test1_initialQueue_hasDenseRanksInOrder() {
        val (repo, _) = harness(abcde().take(4))
        assertEquals(listOf("A", "B", "C", "D"), ids(repo))
        assertEquals(listOf(1, 2, 3, 4), ranks(repo))
        assertEquals(1, NeedsYouOrder.effectiveRank(repo.streams.value, "A"))
        assertEquals(4, NeedsYouOrder.effectiveRank(repo.streams.value, "D"))
        assertNull(NeedsYouOrder.effectiveRank(repo.streams.value, "nope"))
    }

    // ------------------------------------------------------------------ 2 / 3: move shifts the displaced items

    @Test fun test2_moveLastToTwo() = runBlocking {
        val (repo, actions) = harness(abcde().take(4))
        assertTrue(actions.reorderNeedsYou("D", 2) is ActionResult.Success)
        assertEquals(listOf("A", "D", "B", "C"), ids(repo))
        assertEquals(listOf(1, 2, 3, 4), ranks(repo))
    }

    @Test fun test3_moveFirstToFour() = runBlocking {
        val (repo, actions) = harness(abcde().take(4))
        actions.reorderNeedsYou("A", 4)
        assertEquals(listOf("B", "C", "D", "A"), ids(repo))
        assertEquals(listOf(1, 2, 3, 4), ranks(repo))
    }

    // ------------------------------------------------------------------ 4: removal closes the gap

    @Test fun test4_removingRankTwo_normalizesTheRest() = runBlocking {
        val (repo, actions) = harness(abcde().take(4))
        actions.reorderNeedsYou("D", 2)                                  // A D B C, all ranked
        assertTrue(actions.markReady("D") is ActionResult.Success)       // remove the item at rank 2
        assertEquals(listOf("A", "B", "C"), ids(repo))
        assertEquals(listOf(1, 2, 3), ranks(repo))
        assertNull(repo.getStream("D")!!.attentionRank)                  // the item keeps its identity, not its rank
        // Stored keys are dense too, so the persisted queue cannot drift from what is shown.
        assertEquals(listOf(1, 2, 3), NeedsYouOrder.order(repo.streams.value).map { it.attentionRank })
    }

    // ------------------------------------------------------------------ 5: a new item appends

    @Test fun test5_newItemAppendsToTheEnd() = runBlocking {
        val (repo, actions) = harness(abcde().take(3) + processing("NEW", 600))
        actions.reorderNeedsYou("C", 1)                                  // C A B
        actions.checkDue("NEW")                                          // arrives in Needs You
        assertEquals(listOf("C", "A", "B", "NEW"), ids(repo))
        assertEquals(listOf(1, 2, 3, 4), ranks(repo))
        assertNull(repo.getStream("NEW")!!.attentionRank)                // unranked: it simply sits last
    }

    // ------------------------------------------------------------------ 6 / 12: long queues

    @Test fun test6_moveTwelveToOne_inATwelveItemQueue() = runBlocking {
        val items = (1..12).map { item("i$it", it * 10L) }
        val (repo, actions) = harness(items)
        assertEquals((1..12).map { "i$it" }, ids(repo))
        actions.reorderNeedsYou("i12", 1)
        assertEquals(listOf("i12") + (1..11).map { "i$it" }, ids(repo))
        assertEquals((1..12).toList(), ranks(repo))
    }

    @Test fun test12_twentyFiveItems_move23to3() = runBlocking {
        val items = (1..25).map { item("i$it", it * 10L) }
        val (repo, actions) = harness(items)
        assertEquals(25, ids(repo).size)
        actions.reorderNeedsYou("i23", 3)
        val expected = listOf("i1", "i2", "i23") + (3..22).map { "i$it" } + listOf("i24", "i25")
        assertEquals(expected, ids(repo))
        assertEquals((1..25).toList(), ranks(repo))
        // 1–10 coloured, 11–25 neutral — the visuals follow the queue with no manual update.
        queueOf(repo).forEach { e -> assertEquals("rank ${e.rank}", e.rank > 10, NeedsYouPriority.visualsFor(e.rank).neutral) }
    }

    // ------------------------------------------------------------------ 7 / 8 / 9 / 10 / 11: boundaries and safety

    @Test fun test7_and_8_outOfRangeTargetsClamp() = runBlocking {
        val (repo, actions) = harness(abcde())
        assertTrue(actions.reorderNeedsYou("C", 0) is ActionResult.Success)
        assertEquals(listOf("C", "A", "B", "D", "E"), ids(repo))          // 0 → first
        assertTrue(actions.reorderNeedsYou("C", -10) is ActionResult.Success)
        assertEquals(listOf("C", "A", "B", "D", "E"), ids(repo))          // already first: unchanged
        assertTrue(actions.reorderNeedsYou("A", 999) is ActionResult.Success)
        assertEquals(listOf("C", "B", "D", "E", "A"), ids(repo))          // beyond the end → last
        assertEquals(listOf(1, 2, 3, 4, 5), ranks(repo))
    }

    @Test fun test9_movingToItsCurrentRank_changesNothing() = runBlocking {
        val (repo, actions) = harness(abcde())
        val before = repo.streams.value
        assertTrue(actions.reorderNeedsYou("B", 2) is ActionResult.Success)
        assertEquals(before, repo.streams.value)                          // no writes at all
        actions.reorderNeedsYou("E", 1)
        val ranked = repo.streams.value
        actions.reorderNeedsYou("E", 1)
        assertEquals(ranked, repo.streams.value)
    }

    @Test fun test10_unknownOrNonQueueItem_isASafeControlledFailure() = runBlocking {
        val (repo, actions) = harness(abcde() + processing("P", 600))
        assertTrue(actions.reorderNeedsYou("ghost", 1) is ActionResult.NotFound)
        val p = actions.reorderNeedsYou("P", 1)
        assertTrue(p is ActionResult.Rejected && p.reason == DomainError.NotInNeedsYou)
        assertEquals(listOf("A", "B", "C", "D", "E"), ids(repo))          // nothing moved, nothing crashed
        assertTrue(repo.streams.value.all { it.attentionRank == null })
    }

    @Test fun test11_duplicateIds_cannotProduceTwoPositions() {
        val dup = listOf(item("A", 0), item("A", 0), item("B", 10))
        val q = NeedsYouOrder.queue(dup)
        assertEquals(listOf("A", "B"), q.map { it.stream.id })
        assertEquals(listOf(1, 2), q.map { it.rank })
    }

    // ------------------------------------------------------------------ 13: the timer is independent of position

    @Test fun test13_timerStateSurvivesReorder() = runBlocking {
        val (repo, actions) = harness(abcde())
        val before = repo.streams.value.associate { it.id to Triple(NeedsYouOrder.waitingSince(it), it.updatedAt, it.checkAt) }
        actions.reorderNeedsYou("E", 1); actions.reorderNeedsYou("A", 5); actions.reorderNeedsYou("C", 2)
        repo.streams.value.forEach { s ->
            assertEquals("waitingSince ${s.id}", before[s.id]!!.first, NeedsYouOrder.waitingSince(s))
            assertEquals("updatedAt ${s.id}", before[s.id]!!.second, s.updatedAt)
            assertEquals("checkAt ${s.id}", before[s.id]!!.third, s.checkAt)
        }
        // Priority never re-sorts by time either: the manual order stands.
        assertEquals(listOf("E", "C", "B", "D", "A"), ids(repo))
    }

    // ------------------------------------------------------------------ 14 / 15: the 10 ↔ 11 colour threshold

    @Test fun test14_and_15_crossingTheTenthPosition_flipsTheVisualIdentity() = runBlocking {
        val items = (1..12).map { item("i$it", it * 10L) }
        val (repo, actions) = harness(items)
        fun visualOf(id: String) = NeedsYouPriority.visualsFor(NeedsYouOrder.effectiveRank(repo.streams.value, id))
        assertEquals(NeedsYouPriority.accents[9], visualOf("i10").accent)          // rank 10 = pale yellow
        assertTrue(visualOf("i11").neutral)                                        // rank 11 = neutral
        actions.reorderNeedsYou("i12", 1)                                          // everyone shifts down one
        assertEquals(11, NeedsYouOrder.effectiveRank(repo.streams.value, "i10"))
        assertTrue("rank 10 → 11 becomes neutral", visualOf("i10").neutral)
        assertEquals(10, NeedsYouOrder.effectiveRank(repo.streams.value, "i9"))
        assertEquals(NeedsYouPriority.accents[9], visualOf("i9").accent)           // rank 9 → 10 becomes pale yellow
        assertEquals(NeedsYouPriority.accents[0], visualOf("i12").accent)          // the moved item is now red
        assertNotEquals(visualOf("i12").accent, visualOf("i1").accent)
    }

    // ------------------------------------------------------------------ the queue is a projection, not a second list

    @Test fun queue_containsOnlyCheckItems_andIsStableForTheSameInput() {
        val mixed = abcde() + processing("P", 600)
        assertEquals(listOf("A", "B", "C", "D", "E"), NeedsYouOrder.queue(mixed).map { it.stream.id })
        assertEquals(NeedsYouOrder.queue(mixed), NeedsYouOrder.queue(mixed.shuffled()))
    }
}

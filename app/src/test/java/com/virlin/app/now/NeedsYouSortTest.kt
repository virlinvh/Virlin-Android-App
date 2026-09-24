package com.virlin.app.now

import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.attention.AttentionTiming
import com.virlin.app.domain.attention.InMemoryPriorityPreferences
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.attention.PriorityScope
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState.CHECK
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.screens.NeedsYouPriority
import com.virlin.app.ui.screens.NeedsYouSort
import com.virlin.app.ui.screens.NeedsYouSortMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Phase 06 — DISPLAY sort vs CANONICAL priority. The projection reorders what is shown and nothing
 * else: ranks, colours, `dueAt` and preferences are never touched by a view choice.
 */
class NeedsYouSortTest {

    /** "now" = 12:00. A due 11:40 (20m), C due 11:45 (15m), B due 11:58 (2m). */
    private val now: Instant = Instant.parse("2026-09-22T12:00:00Z")
    private val clock = FakeClock(now)

    private fun item(id: String, dueAt: Instant, rank: Int? = null) = WorkStream(
        id = id, title = id, state = CHECK, checkAt = dueAt, attentionRank = rank,
        createdAt = now.minusSeconds(86400), updatedAt = now.minusSeconds(86400)
    )
    private fun at(hm: String) = Instant.parse("2026-09-22T$hm:00Z")

    private fun harness(streams: List<WorkStream>): Pair<InMemoryWorkStreamRepository, DefaultVirlinActions> {
        val repo = InMemoryWorkStreamRepository(seed = streams)
        return repo to DefaultVirlinActions(repo, clock, SequentialIdProvider(), InMemoryPriorityPreferences())
    }
    private fun queue(repo: InMemoryWorkStreamRepository) = NeedsYouOrder.queue(repo.streams.value)
    private fun shown(repo: InMemoryWorkStreamRepository, mode: NeedsYouSortMode) =
        NeedsYouSort.display(queue(repo), mode).map { it.stream.id }

    /** Canonical order A, B, C (ranked) with the due times from the brief. */
    private fun abc() = listOf(item("A", at("11:40"), 1), item("B", at("11:58"), 2), item("C", at("11:45"), 3))

    // ------------------------------------------------------------------ 1–3: the three modes

    @Test fun test1_priorityShowsCanonicalOrder() {
        val (repo, _) = harness(abc())
        assertEquals(listOf("A", "B", "C"), shown(repo, NeedsYouSortMode.PRIORITY))
        assertTrue(NeedsYouSortMode.PRIORITY.isDefault)
        assertEquals(listOf("Priority", "Longest waiting", "Most recent"), NeedsYouSortMode.entries.map { it.label })
    }

    @Test fun test2_longestWaitingIsEarliestDueFirst() {
        val (repo, _) = harness(abc())
        assertEquals(listOf("A", "C", "B"), shown(repo, NeedsYouSortMode.LONGEST_WAITING))
    }

    @Test fun test3_mostRecentIsLatestDueFirst() {
        val (repo, _) = harness(abc())
        assertEquals(listOf("B", "C", "A"), shown(repo, NeedsYouSortMode.MOST_RECENT))
    }

    // ------------------------------------------------------------------ 4 / 15: ties

    @Test fun test4_equalDueAt_isBrokenByCanonicalRank() {
        val same = at("11:50")
        val (repo, _) = harness(listOf(item("B", same, 5), item("A", same, 2), item("C", at("11:40"), 9)))
        assertEquals(listOf("C", "A", "B"), shown(repo, NeedsYouSortMode.LONGEST_WAITING))   // C oldest, then rank 2, rank 5
        assertEquals(listOf("A", "B", "C"), shown(repo, NeedsYouSortMode.MOST_RECENT))
    }

    @Test fun test15_identicalDueAtAndRank_fallsBackToStableId() {
        val same = at("11:50")
        val entries = listOf(
            NeedsYouOrder.Entry(item("z", same), 1), NeedsYouOrder.Entry(item("a", same), 1), NeedsYouOrder.Entry(item("m", same), 1)
        )
        NeedsYouSortMode.entries.forEach { mode ->
            assertEquals(mode.name, listOf("a", "m", "z"), NeedsYouSort.display(entries, mode).map { it.stream.id })
            assertEquals("stable", NeedsYouSort.display(entries, mode), NeedsYouSort.display(entries.reversed(), mode))
        }
    }

    // ------------------------------------------------------------------ 5–7, 16: sorting changes nothing real

    @Test fun test5_6_7_switchingModeMutatesNothing() {
        val (repo, _) = harness(abc())
        val before = repo.streams.value
        NeedsYouSortMode.entries.forEach { shown(repo, it) }
        assertEquals(before, repo.streams.value)                                      // 5/6: no rank, no dueAt write
        repo.streams.value.forEach { s ->
            assertEquals(before.first { it.id == s.id }.checkAt, s.checkAt)
            assertEquals(before.first { it.id == s.id }.attentionRank, s.attentionRank)
            assertEquals(before.first { it.id == s.id }.updatedAt, s.updatedAt)        // 7: timers derive from these
        }
        // The timer text is a function of dueAt − now only; the display mode is irrelevant.
        assertEquals("+00:20:00", AttentionTiming.format(repo.streams.value.first { it.id == "A" }.checkAt, now))
    }

    @Test fun test16_visualsUseCanonicalRank_notDisplayIndex() {
        val (repo, _) = harness(abc())
        val display = NeedsYouSort.display(queue(repo), NeedsYouSortMode.MOST_RECENT)
        assertEquals(listOf("B", "C", "A"), display.map { it.stream.id })
        assertEquals(listOf(2, 3, 1), display.map { it.rank })                          // ranks travel with the item
        // First on screen is rank 2 → rank-2 identity, never rank-1 red.
        assertEquals(NeedsYouPriority.visualsFor(2).accent, NeedsYouPriority.visualsFor(display.first().rank).accent)
        assertFalse(NeedsYouPriority.visualsFor(display.first().rank).accent == NeedsYouPriority.accents[0])
    }

    // ------------------------------------------------------------------ 8–10: interplay with Phases 03–05

    @Test fun test8_priorityEditUnderAlternateSort_changesRankOnly() = runBlocking {
        val (repo, actions) = harness(abc())
        var mode = NeedsYouSortMode.LONGEST_WAITING
        assertEquals(listOf("A", "C", "B"), shown(repo, mode))
        actions.setNeedsYouPriority("C", 1, PriorityScope.OneTime)                      // canonical change
        assertEquals(listOf("C", "A", "B"), queue(repo).map { it.stream.id })
        assertEquals(listOf("A", "C", "B"), shown(repo, mode))                          // display still by due time
        assertEquals(NeedsYouSortMode.LONGEST_WAITING, mode)                            // mode is untouched by editing
    }

    @Test fun test9_checkAgainRemovesTheItem_andTheProjectionResorts() = runBlocking {
        val (repo, actions) = harness(abc())
        actions.continueProcessing("A", AttentionTiming.checkAgainAt(now, 5))           // Phase 05: leaves Needs You
        assertEquals(listOf("C", "B"), shown(repo, NeedsYouSortMode.LONGEST_WAITING))
        assertEquals(listOf("B", "C"), shown(repo, NeedsYouSortMode.MOST_RECENT))
        assertEquals(listOf("B", "C"), shown(repo, NeedsYouSortMode.PRIORITY))          // canonical ranks re-densified
        assertEquals(listOf(1, 2), queue(repo).map { it.rank })
    }

    @Test fun test10_returningItem_takesItsPolicyPositionThenTheProjectionApplies() = runBlocking {
        val (repo, actions) = harness(abc())
        actions.setNeedsYouPriority("C", 1, PriorityScope.Always)
        actions.continueProcessing("C", AttentionTiming.checkAgainAt(now, 5))
        clock.current = now.plusSeconds(300)
        actions.checkDue("C")
        assertEquals(1, NeedsYouOrder.effectiveRank(repo.streams.value, "C"))           // policy first …
        assertEquals(listOf("C", "A", "B"), shown(repo, NeedsYouSortMode.PRIORITY))
        assertEquals(listOf("A", "B", "C"), shown(repo, NeedsYouSortMode.LONGEST_WAITING))   // … then the view
        assertEquals(listOf("C", "B", "A"), shown(repo, NeedsYouSortMode.MOST_RECENT))
    }

    // ------------------------------------------------------------------ 11–14: sizes and rapid switching

    @Test fun test11_emptyQueue() {
        val (repo, _) = harness(emptyList())
        NeedsYouSortMode.entries.forEach { assertTrue(shown(repo, it).isEmpty()) }
    }

    @Test fun test12_singleItem() {
        val (repo, _) = harness(listOf(item("A", at("11:40"), 1)))
        NeedsYouSortMode.entries.forEach { assertEquals(listOf("A"), shown(repo, it)) }
    }

    @Test fun test13_twentyFiveItems_areDeterministic() {
        val items = (1..25).map { item("i$it", now.minusSeconds(it * 60L), it) }        // i1 newest … i25 oldest
        val (repo, _) = harness(items)
        assertEquals((1..25).map { "i$it" }, shown(repo, NeedsYouSortMode.PRIORITY))
        assertEquals((25 downTo 1).map { "i$it" }, shown(repo, NeedsYouSortMode.LONGEST_WAITING))
        assertEquals((1..25).map { "i$it" }, shown(repo, NeedsYouSortMode.MOST_RECENT))
        repeat(3) { assertEquals(shown(repo, NeedsYouSortMode.LONGEST_WAITING), shown(repo, NeedsYouSortMode.LONGEST_WAITING)) }
        assertEquals(25, shown(repo, NeedsYouSortMode.PRIORITY).distinct().size)        // no duplicates
    }

    @Test fun test14_rapidSwitching_neverMutatesTheQueue() {
        val (repo, _) = harness((1..25).map { item("i$it", now.minusSeconds(it * 30L), it) })
        val before = repo.streams.value
        repeat(20) {
            shown(repo, NeedsYouSortMode.PRIORITY); shown(repo, NeedsYouSortMode.LONGEST_WAITING)
            shown(repo, NeedsYouSortMode.MOST_RECENT); shown(repo, NeedsYouSortMode.PRIORITY)
        }
        assertEquals(before, repo.streams.value)
        assertEquals((1..25).toList(), queue(repo).map { it.rank })
    }

    // ------------------------------------------------------------------ the projection covers only Needs You

    @Test fun projection_onlyCoversCheckItems() {
        val processing = WorkStream(id = "P", title = "P", state = com.virlin.app.domain.model.WorkStreamState.PROCESSING,
            checkAt = now.plusSeconds(600), createdAt = now, updatedAt = now)
        val (repo, _) = harness(abc() + processing)
        NeedsYouSortMode.entries.forEach { assertFalse("P" in shown(repo, it)) }        // Working For You is untouched
    }
}

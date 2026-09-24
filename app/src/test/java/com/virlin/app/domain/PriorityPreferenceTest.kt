package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.attention.InMemoryPriorityPreferences
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.attention.PriorityScope
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState.CHECK
import com.virlin.app.domain.model.WorkStreamState.PROCESSING
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.screens.NeedsYouPriority
import com.virlin.app.ui.screens.waitingCountLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Phase 04 — the priority EDITOR's engine: save applies through the Phase 03 move, scopes decide
 * what happens when an item returns, and effective rank stays distinct from preferred position.
 */
class PriorityPreferenceTest {

    private val t0: Instant = Instant.parse("2026-09-22T09:00:00Z")
    private val clock = FakeClock(t0)

    private fun item(id: String, offset: Long) = WorkStream(
        id = id, title = id, state = CHECK, checkAt = t0.plusSeconds(offset),
        createdAt = t0.minusSeconds(7200), updatedAt = t0.minusSeconds(7200)
    )
    private fun harness(streams: List<WorkStream>): Triple<InMemoryWorkStreamRepository, DefaultVirlinActions, InMemoryPriorityPreferences> {
        val repo = InMemoryWorkStreamRepository(seed = streams)
        val prefs = InMemoryPriorityPreferences()
        return Triple(repo, DefaultVirlinActions(repo, clock, SequentialIdProvider(), prefs), prefs)
    }
    private fun ids(repo: InMemoryWorkStreamRepository) = NeedsYouOrder.queue(repo.streams.value).map { it.stream.id }
    private fun abcde() = listOf(item("A", 0), item("B", 10), item("C", 20), item("D", 30), item("E", 40))

    /** The editor's own preview state, exactly as the sheet holds it (position + scope, no writes). */
    private class EditorPreview(val currentPosition: Int, queueSize: Int) {
        var selected = currentPosition
        var scope: PriorityScope = PriorityScope.OneTime
        var size = queueSize
        val safeSelected get() = selected.coerceIn(1, size.coerceAtLeast(1))
    }

    // ------------------------------------------------------------------ 1–3: preview state and cancel

    @Test fun test1_editorOpensOnTheItemsCurrentRank() {
        val (repo, _, _) = harness(abcde())
        val rank = NeedsYouOrder.effectiveRank(repo.streams.value, "D")!!
        assertEquals(4, rank)
        assertEquals(4, EditorPreview(rank, 5).selected)                      // never defaults to 1
    }

    @Test fun test2_changingThePreviewDoesNotTouchTheQueue() {
        val (repo, _, _) = harness(abcde())
        val before = repo.streams.value
        val preview = EditorPreview(4, 5).also { it.selected = 1; it.scope = PriorityScope.Always }
        assertEquals(1, preview.safeSelected)
        assertEquals(before, repo.streams.value)                              // nothing written before Save
        assertEquals(listOf("A", "B", "C", "D", "E"), ids(repo))
    }

    @Test fun test3_cancelLeavesTheQueueUnchanged() = runBlocking {
        val (repo, _, prefs) = harness(abcde())
        val before = repo.streams.value
        EditorPreview(4, 5).also { it.selected = 1 }                          // …then the user cancels: no action call
        assertEquals(before, repo.streams.value)
        assertTrue(prefs.all().isEmpty())
    }

    // ------------------------------------------------------------------ 4, 5, 18: save

    @Test fun test4_saveMovesThroughThePhase03Engine() = runBlocking {
        val (repo, actions, _) = harness(abcde())
        val r = actions.setNeedsYouPriority("D", 1, PriorityScope.OneTime)
        assertTrue(r is ActionResult.Success)
        assertEquals(listOf("D", "A", "B", "C", "E"), ids(repo))
        assertEquals((1..5).toList(), NeedsYouOrder.queue(repo.streams.value).map { it.rank })
    }

    @Test fun test5_saveNeverTouchesTheTimer() = runBlocking {
        val (repo, actions, _) = harness(abcde())
        val before = repo.streams.value.associate { it.id to Triple(NeedsYouOrder.waitingSince(it), it.updatedAt, it.checkAt) }
        actions.setNeedsYouPriority("E", 2, PriorityScope.Always)
        repo.streams.value.forEach { s ->
            assertEquals(before[s.id]!!.first, NeedsYouOrder.waitingSince(s))
            assertEquals(before[s.id]!!.second, s.updatedAt)
            assertEquals(before[s.id]!!.third, s.checkAt)
        }
    }

    @Test fun test18_rankChangeDrivesThePhase02Resolver() = runBlocking {
        val (repo, actions, _) = harness((1..12).map { item("i$it", it * 10L) })
        actions.setNeedsYouPriority("i12", 1, PriorityScope.OneTime)
        fun visual(id: String) = NeedsYouPriority.visualsFor(NeedsYouOrder.effectiveRank(repo.streams.value, id))
        assertEquals(NeedsYouPriority.accents[0], visual("i12").accent)
        assertTrue(visual("i10").neutral)                                     // pushed from 10 to 11
        assertEquals(NeedsYouPriority.accents[9], visual("i9").accent)        // 9 → 10
    }

    // ------------------------------------------------------------------ 6–11: the scopes

    @Test fun test6_thisTime_doesNotReapplyAfterReturn() = runBlocking {
        val (repo, actions, prefs) = harness(abcde())
        actions.setNeedsYouPriority("E", 1, PriorityScope.OneTime)
        assertEquals(listOf("E", "A", "B", "C", "D"), ids(repo))
        assertNull(prefs.get("E"))                                            // nothing remembered
        actions.continueProcessing("E", t0.plusSeconds(3600)); actions.checkDue("E")
        assertEquals(listOf("A", "B", "C", "D", "E"), ids(repo))              // returns at the end
    }

    @Test fun test7_and_8_always_reappliesAtThePreferredPosition() = runBlocking {
        val (repo, actions, prefs) = harness(abcde())
        actions.setNeedsYouPriority("E", 3, PriorityScope.Always)
        assertEquals(listOf("A", "B", "E", "C", "D"), ids(repo))
        assertEquals(3, prefs.get("E")!!.preferredPosition)
        assertEquals(PriorityScope.Always, prefs.get("E")!!.scope)
        actions.continueProcessing("E", t0.plusSeconds(3600)); actions.checkDue("E")
        assertEquals(listOf("A", "B", "E", "C", "D"), ids(repo))              // re-enters at 3, not at the end
        assertEquals(3, NeedsYouOrder.effectiveRank(repo.streams.value, "E"))
    }

    @Test fun test9_custom_unexpired_applies() = runBlocking {
        val (repo, actions, _) = harness(abcde())
        actions.setNeedsYouPriority("E", 2, PriorityScope.Until(t0.plus(Duration.ofHours(24))))
        actions.continueProcessing("E", t0.plusSeconds(3600))
        clock.current = t0.plus(Duration.ofHours(2))                          // still inside the window
        actions.checkDue("E")
        assertEquals(2, NeedsYouOrder.effectiveRank(repo.streams.value, "E"))
    }

    @Test fun test10_custom_expired_fallsBackToDefaultInsertion() = runBlocking {
        val (repo, actions, prefs) = harness(abcde())
        actions.setNeedsYouPriority("E", 2, PriorityScope.Until(t0.plus(Duration.ofHours(1))))
        actions.continueProcessing("E", t0.plusSeconds(3600))
        clock.current = t0.plus(Duration.ofHours(5))                          // expired
        actions.checkDue("E")
        assertEquals(listOf("A", "B", "C", "D", "E"), ids(repo))              // appended
        assertNull("expired preference is dropped", prefs.get("E"))
    }

    @Test fun test11_currentTerm_appliesUntilATermConceptExists() = runBlocking {
        val (repo, actions, prefs) = harness(abcde())
        actions.setNeedsYouPriority("E", 2, PriorityScope.CurrentTerm)
        actions.continueProcessing("E", t0.plusSeconds(3600))
        clock.current = t0.plus(Duration.ofDays(30))
        actions.checkDue("E")
        // Documented contract: no term boundary exists in the domain yet, so the scope is stored
        // and behaves like Always. It must never silently expire on a made-up boundary.
        assertEquals(2, NeedsYouOrder.effectiveRank(repo.streams.value, "E"))
        assertEquals(PriorityScope.CurrentTerm, prefs.get("E")!!.scope)
    }

    // ------------------------------------------------------------------ 12: conflicting preferences

    @Test fun test12_twoAlwaysPreferencesForPositionOne_stayDeterministic() = runBlocking {
        val (repo, actions, prefs) = harness(abcde())
        actions.setNeedsYouPriority("D", 1, PriorityScope.Always)
        actions.setNeedsYouPriority("E", 1, PriorityScope.Always)
        assertEquals(listOf("E", "D", "A", "B", "C"), ids(repo))
        // Both keep "preferred position 1"; the effective ranks stay unique — the item being
        // (re)introduced takes the position and the rest shift.
        assertEquals(1, prefs.get("D")!!.preferredPosition)
        assertEquals(1, prefs.get("E")!!.preferredPosition)
        val ranks = NeedsYouOrder.queue(repo.streams.value).map { it.rank }
        assertEquals(ranks.distinct(), ranks)
        actions.continueProcessing("D", t0.plusSeconds(3600)); actions.checkDue("D")
        assertEquals(listOf("D", "E", "A", "B", "C"), ids(repo))
        assertEquals((1..5).toList(), NeedsYouOrder.queue(repo.streams.value).map { it.rank })
    }

    // ------------------------------------------------------------------ 13–17: bounds and defensive cases

    @Test fun test13_smallQueue_offersOnlyValidPositions() {
        val (repo, _, _) = harness(abcde().take(4))
        val size = NeedsYouOrder.queue(repo.streams.value).size
        assertEquals(4, size)
        assertEquals(listOf(1, 2, 3, 4), (1..minOf(size, NeedsYouPriority.coloredRanks)).toList())
        assertEquals("4 activities waiting", waitingCountLabel(size))
        assertEquals("1 activity waiting", waitingCountLabel(1))
    }

    @Test fun test14_largeQueue_move23to3() = runBlocking {
        val (repo, actions, _) = harness((1..25).map { item("i$it", it * 10L) })
        assertEquals("25 activities waiting", waitingCountLabel(25))
        actions.setNeedsYouPriority("i23", 3, PriorityScope.OneTime)
        assertEquals(3, NeedsYouOrder.effectiveRank(repo.streams.value, "i23"))
        assertEquals((1..25).toList(), NeedsYouOrder.queue(repo.streams.value).map { it.rank })
    }

    @Test fun test15_invalidSelectedPosition_isClampedNotCrashing() = runBlocking {
        val (repo, actions, _) = harness(abcde())
        assertTrue(actions.setNeedsYouPriority("C", 0, PriorityScope.OneTime) is ActionResult.Success)
        assertEquals(1, NeedsYouOrder.effectiveRank(repo.streams.value, "C"))
        assertTrue(actions.setNeedsYouPriority("C", 99, PriorityScope.Always) is ActionResult.Success)
        assertEquals(5, NeedsYouOrder.effectiveRank(repo.streams.value, "C"))
        assertEquals(5, actions.priorityPreference("C")!!.preferredPosition)   // stored clamped, not 99
    }

    @Test fun test16_itemGoneBeforeSave_isASafeControlledFailure() = runBlocking {
        val (repo, actions, prefs) = harness(abcde())
        actions.markReady("D")                                                 // it leaves while the sheet is open
        val r = actions.setNeedsYouPriority("D", 1, PriorityScope.Always)
        assertTrue(r is ActionResult.Rejected)                                 // not in Needs You any more
        assertEquals(listOf("A", "B", "C", "E"), ids(repo))                    // nothing reordered
        assertNull(prefs.get("D"))
        assertTrue(actions.setNeedsYouPriority("ghost", 1, PriorityScope.Always) is ActionResult.NotFound)
    }

    @Test fun test17_queueShrinksWhileTheEditorIsOpen_previewRevalidates() = runBlocking {
        val (repo, actions, _) = harness(abcde())
        val preview = EditorPreview(5, 5).also { it.selected = 5 }
        actions.markReady("A"); actions.markReady("B")                         // queue shrinks to 3
        preview.size = NeedsYouOrder.queue(repo.streams.value).size
        assertEquals(3, preview.size)
        assertEquals(3, preview.safeSelected)                                  // clamped, never invalid
        assertTrue(actions.setNeedsYouPriority("E", preview.safeSelected, PriorityScope.OneTime) is ActionResult.Success)
        assertEquals(3, NeedsYouOrder.effectiveRank(repo.streams.value, "E"))
    }

    // ------------------------------------------------------------------ effective rank ≠ preferred position

    @Test fun preferredPosition_doesNotOwnTheRank() = runBlocking {
        val (repo, actions, prefs) = harness(abcde())
        actions.setNeedsYouPriority("E", 2, PriorityScope.Always)
        assertEquals(2, NeedsYouOrder.effectiveRank(repo.streams.value, "E"))
        actions.reorderNeedsYou("A", 1)                                        // someone else moves above it
        actions.reorderNeedsYou("D", 2)
        assertEquals(3, NeedsYouOrder.effectiveRank(repo.streams.value, "E"))  // the queue decides the rank …
        assertEquals(2, prefs.get("E")!!.preferredPosition)                    // … the preference is unchanged
    }
}

package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.attention.AttentionTiming
import com.virlin.app.domain.attention.InMemoryPriorityPreferences
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.attention.PriorityPreference
import com.virlin.app.domain.attention.PriorityPreferences
import com.virlin.app.domain.attention.PriorityScope
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState.CHECK
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.screens.NeedsYouSort
import com.virlin.app.ui.screens.NeedsYouSortMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Phase 07 — durable priority policies. These tests pin the CONTRACT of `PriorityPreferences`
 * (any implementation): what is stored, what is not, replacement, expiry and recovery. The Room
 * implementation and the migration are proven on-device in `PriorityPersistenceRoomTest`.
 */
class PriorityPersistenceTest {

    private val t0: Instant = Instant.parse("2026-09-23T10:00:00Z")
    private val clock = FakeClock(t0)

    private fun item(id: String, offset: Long) = WorkStream(
        id = id, title = id, state = CHECK, checkAt = t0.plusSeconds(offset),
        createdAt = t0.minusSeconds(7200), updatedAt = t0.minusSeconds(7200)
    )
    private fun abc() = listOf(item("A", 0), item("B", 10), item("C", 20), item("D", 30))

    /** The store survives "process death" in these tests by handing the SAME rows to a new instance. */
    private class Harness(seed: List<WorkStream>, clock: FakeClock, var store: PriorityPreferences = InMemoryPriorityPreferences()) {
        var repo = InMemoryWorkStreamRepository(seed = seed)
        var actions = DefaultVirlinActions(repo, clock, SequentialIdProvider(), store)
        val clockRef = clock
        /** Rebuild every in-memory object from the persisted rows — the unit-test stand-in for a restart. */
        fun restart() = runBlocking {
            val rows = store.all()
            val streams = repo.streams.value.map { it.copy() }
            store = InMemoryPriorityPreferences(rows)
            repo = InMemoryWorkStreamRepository(seed = streams)
            actions = DefaultVirlinActions(repo, clockRef, SequentialIdProvider(), store)
        }
        fun ids() = NeedsYouOrder.queue(repo.streams.value).map { it.stream.id }
    }
    private fun harness(seed: List<WorkStream> = abc()) = Harness(seed, clock)

    // ------------------------------------------------------------------ 1–5: what is stored

    @Test fun test1_2_alwaysSavesAndLoads() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 2, PriorityScope.Always)
        val saved = h.store.get("D")!!
        assertEquals("D", saved.streamId); assertEquals(2, saved.preferredPosition)
        assertEquals(PriorityScope.Always, saved.scope); assertEquals(t0, saved.createdAt)
    }

    @Test fun test3_currentTermKeepsItsOwnType() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("C", 1, PriorityScope.CurrentTerm)
        assertEquals(PriorityScope.CurrentTerm, h.store.get("C")!!.scope)   // never silently stored as Always
        h.restart()
        assertEquals(PriorityScope.CurrentTerm, h.store.get("C")!!.scope)
    }

    @Test fun test4_untilSavesItsExpiration() = runBlocking {
        val h = harness()
        val expiry = t0.plus(Duration.ofHours(8))
        h.actions.setNeedsYouPriority("B", 1, PriorityScope.Until(expiry))
        assertEquals(PriorityScope.Until(expiry), h.store.get("B")!!.scope)
    }

    @Test fun test5_oneTimeIsNeverPersisted() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 2, PriorityScope.OneTime)
        assertEquals(listOf("A", "D", "B", "C"), h.ids())                    // the move still happens
        assertNull(h.store.get("D"))
        assertTrue(h.store.all().isEmpty())
        h.restart()
        assertTrue("a restart must not resurrect a one-time move", h.store.all().isEmpty())
    }

    // ------------------------------------------------------------------ 6–8: replacement and removal

    @Test fun test6_savingAgainUpdatesTheSameRow() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 2, PriorityScope.Always)
        h.actions.setNeedsYouPriority("D", 4, PriorityScope.Always)
        assertEquals(1, h.store.all().size)                                  // one policy per item
        assertEquals(4, h.store.get("D")!!.preferredPosition)
    }

    @Test fun test7_scopeReplacementLeavesNoStalePolicy() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 2, PriorityScope.Always)
        val expiry = t0.plus(Duration.ofDays(1))
        h.actions.setNeedsYouPriority("D", 4, PriorityScope.Until(expiry))
        assertEquals(1, h.store.all().size)
        assertEquals(PriorityScope.Until(expiry), h.store.get("D")!!.scope)
        assertEquals(4, h.store.get("D")!!.preferredPosition)
    }

    @Test fun test8_removePreference() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 2, PriorityScope.Always)
        h.actions.clearPriorityPreference("D")
        assertNull(h.store.get("D"))
        assertEquals(listOf("A", "D", "B", "C"), h.ids())                    // removal never re-orders
    }

    /** Phase 07 refinement of the Phase 04 contract: a one-time move keeps an existing policy. */
    @Test fun oneTimeOverride_keepsAnExistingDurablePolicy() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 2, PriorityScope.Always)
        h.actions.setNeedsYouPriority("D", 4, PriorityScope.OneTime)         // just this occurrence
        assertEquals(4, NeedsYouOrder.effectiveRank(h.repo.streams.value, "D"))
        assertEquals(2, h.store.get("D")!!.preferredPosition)                // the Always policy stands
        assertEquals(PriorityScope.Always, h.store.get("D")!!.scope)
    }

    // ------------------------------------------------------------------ 9–10, 16–17: expiry

    @Test fun test9_expiredUntilIsIgnoredOnEntry() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 1, PriorityScope.Until(t0.plus(Duration.ofHours(1))))
        h.actions.continueProcessing("D", AttentionTiming.checkAgainAt(t0, 5))
        clock.current = t0.plus(Duration.ofHours(3))                         // expired
        h.actions.checkDue("D")
        assertEquals(listOf("A", "B", "C", "D"), h.ids())                    // default insertion
        assertNull("expired policy is cleaned up", h.store.get("D"))
    }

    @Test fun test10_cleanupExpiredRemovesOnlyExpiredRows() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("A", 1, PriorityScope.Always)
        h.actions.setNeedsYouPriority("B", 2, PriorityScope.Until(t0.plus(Duration.ofHours(1))))
        h.actions.setNeedsYouPriority("C", 3, PriorityScope.CurrentTerm)
        clock.current = t0.plus(Duration.ofHours(2))
        assertEquals(1, h.actions.cleanupExpiredPriorityPreferences())
        assertEquals(setOf("A", "C"), h.store.all().map { it.streamId }.toSet())
    }

    @Test fun test16_untilSurvivesRestartBeforeExpiry() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 2, PriorityScope.Until(t0.plus(Duration.ofHours(6))))
        h.actions.continueProcessing("D", AttentionTiming.checkAgainAt(t0, 5))
        h.restart()
        clock.current = t0.plus(Duration.ofHours(2))
        h.actions.checkDue("D")
        assertEquals(2, NeedsYouOrder.effectiveRank(h.repo.streams.value, "D"))
    }

    @Test fun test17_untilIsIgnoredAfterExpiryAcrossRestart() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 2, PriorityScope.Until(t0.plus(Duration.ofHours(1))))
        h.actions.continueProcessing("D", AttentionTiming.checkAgainAt(t0, 5))
        h.restart()
        clock.current = t0.plus(Duration.ofHours(4))
        h.actions.checkDue("D")
        assertEquals(listOf("A", "B", "C", "D"), h.ids())
        assertNull(h.store.get("D"))
    }

    // ------------------------------------------------------------------ 11–15: clamping, identity, safety, recovery

    @Test fun test11_preferredPositionBeyondTheQueueClampsWithoutRewritingTheStoredValue() = runBlocking {
        val h = harness(listOf(item("A", 0), item("B", 10), item("C", 20)))
        h.actions.setNeedsYouPriority("C", 3, PriorityScope.Always)
        h.store.save(PriorityPreference("C", 8, PriorityScope.Always, t0))   // a policy from a bigger queue
        h.actions.continueProcessing("C", AttentionTiming.checkAgainAt(t0, 5))
        clock.current = t0.plus(Duration.ofMinutes(5))
        h.actions.checkDue("C")
        assertEquals(3, NeedsYouOrder.effectiveRank(h.repo.streams.value, "C"))   // clamped to last
        assertEquals(8, h.store.get("C")!!.preferredPosition)                     // stored intent preserved
    }

    @Test fun test12_stableIdentitySurvivesReordering() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 2, PriorityScope.Always)
        h.actions.reorderNeedsYou("A", 4); h.actions.reorderNeedsYou("D", 3)
        assertEquals(1, h.store.all().size)
        assertEquals("D", h.store.all().single().streamId)                   // still the same item's policy
        assertEquals(2, h.store.get("D")!!.preferredPosition)
    }

    @Test fun test13_14_missingOrOrphanedItemIsSafe() = runBlocking {
        val h = harness()
        h.store.save(PriorityPreference("ghost", 1, PriorityScope.Always, t0))    // orphan row
        assertTrue(h.actions.setNeedsYouPriority("ghost", 1, PriorityScope.Always) is ActionResult.NotFound)
        assertEquals(listOf("A", "B", "C", "D"), h.ids())                          // attention is unaffected
        h.actions.checkDue("A")                                                    // a normal entry still works
        assertNotNull(h.store.get("ghost"))                                        // the orphan simply sits there
        assertEquals(0, h.actions.cleanupExpiredPriorityPreferences())             // and is not "expired"
    }

    @Test fun test15_alwaysSurvivesRepositoryRecreation_andAppliesOnReEntry() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 2, PriorityScope.Always)
        h.actions.continueProcessing("D", AttentionTiming.checkAgainAt(t0, 5))     // leaves Needs You
        h.restart()                                                                // ← process death
        assertEquals(2, h.store.get("D")!!.preferredPosition)
        clock.current = t0.plus(Duration.ofMinutes(5))
        h.actions.checkDue("D")                                                    // returns
        assertEquals(listOf("A", "D", "B", "C"), h.ids())
        assertEquals(2, NeedsYouOrder.effectiveRank(h.repo.streams.value, "D"))
    }

    // ------------------------------------------------------------------ 18–21: the other subsystems stay separate

    @Test fun test18_19_checkAgainKeepsThePolicyAndTheTimingFields() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 2, PriorityScope.Always)
        val before = h.repo.streams.value.associate { it.id to Triple(it.checkAt, it.updatedAt, it.attentionRank) }
        h.actions.continueProcessing("D", AttentionTiming.checkAgainAt(t0, 5))
        assertEquals(PriorityScope.Always, h.store.get("D")!!.scope)                // 18: policy intact
        h.repo.streams.value.filter { it.id != "D" }.forEach {                      // 19: nothing else touched
            assertEquals(before[it.id]!!.first, it.checkAt); assertEquals(before[it.id]!!.second, it.updatedAt)
        }
        assertEquals(t0.plus(Duration.ofMinutes(5)), h.repo.getStream("D")!!.checkAt)
    }

    @Test fun test20_sortProjectionNeverTouchesPersistence() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 2, PriorityScope.Always)
        val before = h.store.all()
        NeedsYouSortMode.entries.forEach { NeedsYouSort.display(NeedsYouOrder.queue(h.repo.streams.value), it) }
        assertEquals(before, h.store.all())
        // Canonical priority resolves FIRST, the display projection only afterwards.
        assertEquals(listOf("A", "D", "B", "C"), h.ids())
        assertEquals(listOf("A", "B", "C", "D"), NeedsYouSort.display(NeedsYouOrder.queue(h.repo.streams.value), NeedsYouSortMode.LONGEST_WAITING).map { it.stream.id })
    }

    @Test fun test21_twoWritesNeverProduceCompetingActivePolicies() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 1, PriorityScope.Always)
        h.actions.setNeedsYouPriority("D", 3, PriorityScope.CurrentTerm)
        assertEquals(1, h.store.all().size)
        assertEquals(PriorityScope.CurrentTerm, h.store.get("D")!!.scope)
        assertEquals(3, h.store.get("D")!!.preferredPosition)
    }

    // ------------------------------------------------------------------ the four concepts stay separate

    @Test fun policy_queue_timing_andViewRemainFourDistinctThings() = runBlocking {
        val h = harness()
        h.actions.setNeedsYouPriority("D", 2, PriorityScope.Always)
        val d = h.repo.getStream("D")!!
        assertEquals(2, h.store.get("D")!!.preferredPosition)                      // policy
        assertEquals(2, NeedsYouOrder.effectiveRank(h.repo.streams.value, "D"))    // queue
        assertEquals(t0.plusSeconds(30), d.checkAt)                                // timing
        assertEquals(NeedsYouSortMode.PRIORITY, NeedsYouSortMode.entries.first())  // view (session only)
    }
}

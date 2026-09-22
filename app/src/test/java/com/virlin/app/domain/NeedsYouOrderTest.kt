package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.CHECK
import com.virlin.app.domain.model.WorkStreamState.PROCESSING
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Needs You priority ranking — Phase 1 (domain / ordering foundation).
 * Rank ("what first?") and waiting time ("how long?") stay separate: the rule is `NeedsYouOrder`,
 * the atomic reorder is `VirlinActions.reorderNeedsYou`, the rank lives on the WorkStream row.
 */
class NeedsYouOrderTest {

    private val t0: Instant = Instant.parse("2026-09-22T09:00:00Z")

    /** A CHECK stream waiting since `t0 + sinceOffset` (checkAt = waitingSince, no snooze). */
    private fun check(id: String, sinceOffsetSeconds: Long, rank: Int? = null) = WorkStream(
        id = id, title = id, state = CHECK, checkAt = t0.plusSeconds(sinceOffsetSeconds), attentionRank = rank,
        createdAt = t0.minusSeconds(3600), updatedAt = t0.minusSeconds(3600)
    )
    private fun other(id: String, state: WorkStreamState) =
        WorkStream(id = id, title = id, state = state, createdAt = t0, updatedAt = t0, checkAt = if (state == PROCESSING) t0.plusSeconds(600) else null)

    /** A = waiting longest … D = waiting shortest → default order [A,B,C,D]. */
    private fun abcd() = listOf(check("A", 0), check("B", 10), check("C", 20), check("D", 30))
    private fun ids(list: List<WorkStream>) = list.map { it.id }

    private fun harness(streams: List<WorkStream>): Pair<InMemoryWorkStreamRepository, DefaultVirlinActions> {
        val repo = InMemoryWorkStreamRepository(seed = streams)
        return repo to DefaultVirlinActions(repo, FakeClock(t0.plusSeconds(100)), SequentialIdProvider())
    }
    private fun order(repo: InMemoryWorkStreamRepository) = ids(NeedsYouOrder.order(repo.streams.value))

    // ------------------------------------------------------------------ A: default = waiting time

    @Test fun A_defaultOrder_isLongestWaitingFirst_andIgnoresOtherStates() {
        val shuffled = listOf(check("C", 20), other("X", PROCESSING), check("A", 0), other("Y", WorkStreamState.READY), check("D", 30), check("B", 10))
        assertEquals(listOf("A", "B", "C", "D"), ids(NeedsYouOrder.order(shuffled)))
    }

    @Test fun A2_snoozedOrigin_waitsSinceTransition_notOldCheckAt() {
        val s = check("S", 500).copy(snoozeReason = SnoozeReason.HUMAN_RETURN, updatedAt = t0.plusSeconds(5))
        assertEquals(t0.plusSeconds(5), NeedsYouOrder.waitingSince(s))
        assertEquals(listOf("A", "S", "B"), ids(NeedsYouOrder.order(listOf(check("B", 10), s, check("A", 0)))))
    }

    @Test fun A3_equalTimestamps_tieBreakIsDeterministic() {
        val equal = listOf(check("Q", 0), check("P", 0), check("R", 0))
        assertEquals(listOf("P", "Q", "R"), ids(NeedsYouOrder.order(equal)))
        assertEquals(ids(NeedsYouOrder.order(equal)), ids(NeedsYouOrder.order(equal.reversed())))
    }

    // ------------------------------------------------------------------ B / C / D: the three documented moves

    @Test fun B_move4to2() = runBlocking {
        val (repo, actions) = harness(abcd())
        val r = actions.reorderNeedsYou("D", 2)
        assertTrue(r is ActionResult.Success)
        assertEquals(listOf("A", "D", "B", "C"), ids((r as ActionResult.Success).value))
        assertEquals(listOf("A", "D", "B", "C"), order(repo))
    }

    @Test fun C_move1to4() = runBlocking {
        val (repo, actions) = harness(abcd())
        actions.reorderNeedsYou("A", 4)
        assertEquals(listOf("B", "C", "D", "A"), order(repo))
    }

    @Test fun D_move3to1() = runBlocking {
        val (repo, actions) = harness(abcd())
        actions.reorderNeedsYou("C", 1)
        assertEquals(listOf("C", "A", "B", "D"), order(repo))
    }

    // ------------------------------------------------------------------ E: never duplicate positions; dense ranks

    @Test fun E_afterAnyMove_ranksAreDenseAndUnique() = runBlocking {
        val (repo, actions) = harness(abcd())
        actions.reorderNeedsYou("D", 2); actions.reorderNeedsYou("A", 4); actions.reorderNeedsYou("B", 1)
        val ranks = NeedsYouOrder.order(repo.streams.value).map { it.attentionRank }
        assertEquals(listOf(1, 2, 3, 4), ranks)
        assertEquals(listOf("B", "D", "C", "A"), order(repo))
    }

    @Test fun E2_outOfRange_clampsSafely() = runBlocking {
        val (repo, actions) = harness(abcd())
        assertTrue(actions.reorderNeedsYou("C", 99) is ActionResult.Success)
        assertEquals(listOf("A", "B", "D", "C"), order(repo))                 // beyond last → last
        assertTrue(actions.reorderNeedsYou("D", 0) is ActionResult.Success)
        assertEquals(listOf("D", "A", "B", "C"), order(repo))                 // below first → first
        assertTrue(actions.reorderNeedsYou("B", -7) is ActionResult.Success)
        assertEquals(listOf("B", "D", "A", "C"), order(repo))
        assertEquals(listOf(1, 2, 3, 4), NeedsYouOrder.order(repo.streams.value).map { it.attentionRank })
    }

    @Test fun E3_notInNeedsYou_isRejected_andNothingChanges() = runBlocking {
        val (repo, actions) = harness(abcd() + other("X", PROCESSING))
        val r = actions.reorderNeedsYou("X", 1)
        assertTrue(r is ActionResult.Rejected && r.reason == DomainError.NotInNeedsYou)
        assertTrue(actions.reorderNeedsYou("nope", 1) is ActionResult.NotFound)
        assertEquals(listOf("A", "B", "C", "D"), order(repo))
        assertTrue(repo.streams.value.all { it.attentionRank == null })
    }

    // ------------------------------------------------------------------ F: order is derived from persisted rows only (Room round trip: NeedsYouRankPersistenceTest)

    @Test fun F_orderIsDerivedFromPersistedRanks_notFromMemory() = runBlocking {
        val (repo, actions) = harness(abcd())
        actions.reorderNeedsYou("D", 2)
        val reloaded = InMemoryWorkStreamRepository(seed = repo.streams.value.map { it.copy() })   // rebuilt from rows only
        assertEquals(listOf("A", "D", "B", "C"), ids(NeedsYouOrder.order(reloaded.streams.value)))
    }

    // ------------------------------------------------------------------ G: entering / leaving / returning

    @Test fun G_newArrival_isUnranked_afterTheRankedBlock() = runBlocking {
        val (repo, actions) = harness(abcd() + other("P", PROCESSING))
        actions.reorderNeedsYou("D", 1)                                       // [D,A,B,C] all ranked
        actions.checkDue("P")                                                 // P enters Needs You now (checkAt = t0+600, later than every other)
        assertEquals(listOf("D", "A", "B", "C", "P"), order(repo))
        assertNull(repo.getStream("P")!!.attentionRank)
    }

    @Test fun G2_leavingNeedsYou_clearsRank_othersKeepOrder() = runBlocking {
        val (repo, actions) = harness(abcd())
        actions.reorderNeedsYou("D", 1)                                       // [D,A,B,C]
        actions.markReady("A")                                                // A leaves Needs You
        assertNull(repo.getStream("A")!!.attentionRank)
        assertEquals(listOf("D", "B", "C"), order(repo))
        actions.focusStream("D")                                              // through Focus as well
        assertNull(repo.getStream("D")!!.attentionRank)
        assertEquals(listOf("B", "C"), order(repo))
    }

    @Test fun G3_returningLater_entersUnranked_byWaitingTime() = runBlocking {
        val (repo, actions) = harness(abcd())
        actions.reorderNeedsYou("D", 1)                                       // [D,A,B,C]
        assertTrue(actions.continueProcessing("D", t0.plusSeconds(900)) is ActionResult.Success)   // still running → leaves
        assertEquals(listOf("A", "B", "C"), order(repo))
        actions.checkDue("D")                                                 // returns: unranked, after the ranked block
        assertNull(repo.getStream("D")!!.attentionRank)
        assertEquals(listOf("A", "B", "C", "D"), order(repo))
        actions.reorderNeedsYou("D", 2)                                       // can be ranked again
        assertEquals(listOf("A", "D", "B", "C"), order(repo))
    }

    @Test fun G4_rankedBlock_thenUnranked_byWaitingTime() {
        val streams = listOf(check("A", 0), check("B", 10, rank = 2), check("C", 20, rank = 1), check("D", 30))
        assertEquals(listOf("C", "B", "A", "D"), ids(NeedsYouOrder.order(streams)))
    }

    // ------------------------------------------------------------------ I: waiting time never disturbs an established manual order

    @Test fun I_waitingTimeDoesNotReorderRankedItems() = runBlocking {
        val (repo, actions) = harness(abcd())
        val before = repo.streams.value.associate { it.id to it.updatedAt }
        actions.reorderNeedsYou("D", 1)                                       // [D,A,B,C] although D waited least
        repo.streams.value.forEach { assertEquals(before[it.id], it.updatedAt) }   // reorder never touched updatedAt
        // The order is a function of persisted fields only — input order and the clock are irrelevant.
        assertEquals(listOf("D", "A", "B", "C"), order(repo))
        assertEquals(listOf("D", "A", "B", "C"), ids(NeedsYouOrder.order(repo.streams.value.shuffled())))
        // Waiting time still reads exactly as before (rank and urgency are separate concepts).
        assertEquals(t0.plusSeconds(30), NeedsYouOrder.waitingSince(repo.getStream("D")!!))
    }

    // ------------------------------------------------------------------ J: no-op

    @Test fun J_moveToCurrentPosition_isNoOp() = runBlocking {
        val (repo, actions) = harness(abcd())
        val snapshot = repo.streams.value
        val r = actions.reorderNeedsYou("B", 2)
        assertTrue(r is ActionResult.Success)
        assertEquals(snapshot, repo.streams.value)                            // nothing persisted, no ranks assigned
        actions.reorderNeedsYou("D", 2)
        val ranked = repo.streams.value
        actions.reorderNeedsYou("D", 2)                                       // already there
        assertEquals(ranked, repo.streams.value)
    }

    @Test fun J2_planMove_unknownStream_isNull() {
        assertNull(NeedsYouOrder.planMove(abcd(), "zzz", 1))
        assertTrue(NeedsYouOrder.planMove(abcd(), "B", 2)!!.isNoOp)
    }
}

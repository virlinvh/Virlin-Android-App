package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.ContextUpdate
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.action.Field
import com.virlin.app.domain.action.getOrNull
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.*
import com.virlin.app.domain.model.WorkStreamTransitions
import com.virlin.app.domain.model.effectiveAttentionState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

class FakeClock(var current: Instant) : VirlinClock {
    override fun now(): Instant = current
    fun advance(d: Duration) { current = current.plus(d) }
}

class SequentialIdProvider : IdProvider {
    private val counters = HashMap<String, Int>()
    override fun newId(prefix: String): String {
        val n = (counters[prefix] ?: 0) + 1; counters[prefix] = n; return "$prefix-$n"
    }
}

/** Domain transition, time, history and invariant tests. Deterministic: fake clock, sequential ids. */
class VirlinActionsTest {

    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")
    private lateinit var clock: FakeClock
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions

    private fun stream(id: String, state: WorkStreamState, title: String = id) = WorkStream(
        id = id, title = title, state = state, createdAt = t0, updatedAt = t0,
        nextHumanAction = "next-$id", waitingFor = "wait-$id", lastHumanAction = "last-$id"
    )

    @Before
    fun setUp() {
        clock = FakeClock(t0)
        repo = InMemoryWorkStreamRepository(
            seed = listOf(
                stream("psych", FOCUS, "Psychology"),
                stream("claude", PROCESSING, "Claude · Virlin"),
                stream("codex", PROCESSING, "Codex · MBA"),
                stream("anti", CHECK, "Antigravity"),
                stream("notion", READY, "Notion Transfer"),
                stream("cloud", BLOCKED, "Cloud Sync"),
                stream("design", PAUSED, "Design Review"),
                stream("old", DONE, "Old")
            )
        )
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
    }

    private suspend fun state(id: String) = repo.getStream(id)!!.state
    private suspend fun events(id: String) = repo.getEvents(id).map { it.type }
    private fun <T> ActionResult<T>.rejectedWith(): DomainError = (this as ActionResult.Rejected).reason

    // ---------------------------------------------------------------- transition table

    @Test
    fun transitionTable_matchesSpec() {
        assertTrue(WorkStreamTransitions.canTransition(READY, FOCUS))
        assertTrue(WorkStreamTransitions.canTransition(CHECK, FOCUS))
        assertTrue(WorkStreamTransitions.canTransition(FOCUS, PROCESSING))
        assertTrue(WorkStreamTransitions.canTransition(CHECK, SNOOZED))
        assertFalse(WorkStreamTransitions.canTransition(PROCESSING, SNOOZED)) // processing defers via checkAt
        assertFalse(WorkStreamTransitions.canTransition(PROCESSING, FOCUS))   // must be checked first
        assertFalse(WorkStreamTransitions.canTransition(DONE, FOCUS))
        assertTrue(WorkStreamTransitions.targetsFrom(DONE).isEmpty())
    }

    // ---------------------------------------------------------------- focus

    @Test
    fun focus_readyToFocus_displacesCurrentFocusToReady() = runTest {
        val r = actions.focusStream("notion")
        val out = r.getOrNull()!!
        assertEquals(FOCUS, state("notion"))
        assertEquals(READY, state("psych"))
        assertEquals("psych", out.displaced!!.id)
        assertEquals(listOf(EventType.FOCUS_LEFT), events("psych"))
        assertEquals(listOf(EventType.FOCUS_STARTED), events("notion"))
        // Displaced stream's session closed and context snapshotted for Context Return.
        assertNull(repo.getOpenFocusSession("psych"))
        val snap = repo.getLatestSnapshot("psych")!!
        assertEquals("next-psych", snap.nextHumanAction); assertEquals(READY, snap.reason)
    }

    @Test
    fun focus_checkToFocus_opensSessionAndCycle() = runTest {
        actions.focusStream("anti")
        assertEquals(FOCUS, state("anti"))
        assertNotNull(repo.getOpenFocusSession("anti"))
        assertNotNull(repo.getCurrentCycle("anti"))
        assertEquals(1, repo.getStream("anti")!!.cycleCount)
    }

    @Test
    fun focus_neverLeavesTwoFocusStreams_evenAfterManySwitches() = runTest {
        listOf("notion", "anti", "design", "cloud", "notion", "anti").forEach { actions.focusStream(it) }
        assertEquals(1, repo.streams.value.count { it.state == FOCUS })
        assertEquals(FOCUS, state("anti"))
    }

    @Test
    fun focus_alreadyFocused_rejected() = runTest {
        assertEquals(DomainError.AlreadyFocused, actions.focusStream("psych").rejectedWith())
    }

    @Test
    fun focus_done_rejected() = runTest {
        assertEquals(DomainError.StreamAlreadyDone, actions.focusStream("old").rejectedWith())
        assertEquals(DONE, state("old"))
    }

    @Test
    fun focus_processing_rejected_untilChecked() = runTest {
        val r = actions.focusStream("claude").rejectedWith() as DomainError.InvalidTransition
        assertEquals(PROCESSING, r.from); assertEquals(FOCUS, r.to)
    }

    @Test
    fun focus_fromPaused_recordsResumed() = runTest {
        actions.focusStream("design")
        assertEquals(listOf(EventType.RESUMED, EventType.FOCUS_STARTED), events("design"))
    }

    @Test
    fun multipleProcessing_withOneFocus_isSupported() = runTest {
        assertEquals(2, repo.streams.value.count { it.state == PROCESSING })
        actions.focusStream("notion")
        assertEquals(2, repo.streams.value.count { it.state == PROCESSING })
        assertEquals(1, repo.streams.value.count { it.state == FOCUS })
    }

    // ---------------------------------------------------------------- hand-off

    @Test
    fun handOff_closesSession_startsProcessing_snapshots_cycles_events() = runTest {
        actions.focusStream("notion")            // 10:00 session opens
        clock.advance(Duration.ofMinutes(8))
        val checkAt = clock.now().plus(Duration.ofMinutes(5))
        val r = actions.handOffStream("notion", waitingFor = "Claude", nextHumanAction = "Review output", checkAt = checkAt)
        val s = r.getOrNull()!!
        assertEquals(PROCESSING, s.state)
        assertEquals(clock.now(), s.processingStartedAt)
        assertEquals(checkAt, s.checkAt)
        assertEquals("Claude", s.waitingFor); assertEquals("Review output", s.nextHumanAction)
        // Session closed with an 8-minute duration derived from timestamps.
        val session = repo.getFocusSessions("notion").single()
        assertFalse(session.isOpen)
        assertEquals(Duration.ofMinutes(8), session.duration(clock.now()))
        // Cycle recorded the hand-off; snapshot captured for Context Return.
        val cycle = repo.getCycles("notion").single()
        assertEquals(clock.now(), cycle.handedOffAt)
        val snap = repo.getLatestSnapshot("notion")!!
        assertEquals(PROCESSING, snap.reason); assertEquals("Review output", snap.nextHumanAction); assertEquals(checkAt, snap.checkAt)
        // Semantic history in order.
        assertEquals(listOf(EventType.FOCUS_STARTED, EventType.HANDOFF, EventType.PROCESSING_STARTED), events("notion"))
        // Human focus is free.
        assertNull(repo.getActiveFocus())
    }

    @Test
    fun handOff_requiresFocus() = runTest {
        assertEquals(DomainError.NotInFocus, actions.handOffStream("notion").rejectedWith())
    }

    @Test
    fun handOff_secondCycle_afterReturningToFocus() = runTest {
        actions.focusStream("notion"); actions.handOffStream("notion")
        actions.checkDue("notion"); actions.focusStream("notion"); actions.handOffStream("notion")
        assertEquals(2, repo.getCycles("notion").size)
        assertEquals(2, repo.getStream("notion")!!.cycleCount)
    }

    // ---------------------------------------------------------------- check

    @Test
    fun checkStream_isReadOriented_recordsEvent_noStateChange() = runTest {
        val out = actions.checkStream("claude").getOrNull()!!
        assertEquals(PROCESSING, out.stream.state)
        assertEquals(PROCESSING, state("claude"))
        assertEquals(listOf(EventType.CHECKED), events("claude"))
    }

    @Test
    fun checkDue_processingToCheck() = runTest {
        actions.checkDue("claude")
        assertEquals(CHECK, state("claude"))
        assertEquals(listOf(EventType.CHECK_DUE), events("claude"))
    }

    @Test
    fun continueProcessing_fromCheck_setsNewCheckTime() = runTest {
        val later = clock.now().plus(Duration.ofMinutes(5))
        val s = actions.continueProcessing("anti", later).getOrNull()!!
        assertEquals(PROCESSING, s.state); assertEquals(later, s.checkAt)
        assertEquals(DomainError.InvalidSnoozeTime, actions.continueProcessing("anti", clock.now()).rejectedWith())
    }

    @Test
    fun effectiveAttentionState_projectsDueProcessingAsCheck_withoutMutating() = runTest {
        val checkAt = clock.now().plus(Duration.ofMinutes(5))
        actions.continueProcessing("claude", checkAt)
        val s = repo.getStream("claude")!!
        assertEquals(PROCESSING, effectiveAttentionState(s, clock.now()))
        assertEquals(CHECK, effectiveAttentionState(s, checkAt))
        assertEquals(PROCESSING, state("claude")) // nothing mutated
    }

    // ---------------------------------------------------------------- snooze

    @Test
    fun snooze_checkToSnoozed() = runTest {
        val until = clock.now().plus(Duration.ofMinutes(30))
        val s = actions.snoozeStream("anti", until).getOrNull()!!
        assertEquals(SNOOZED, s.state); assertEquals(until, s.snoozedUntil); assertEquals(until, s.checkAt)
        assertEquals(listOf(EventType.SNOOZED), events("anti"))
        assertNotNull(repo.getLatestSnapshot("anti"))
    }

    @Test
    fun snooze_processing_rejected() = runTest {
        val r = actions.snoozeStream("claude", clock.now().plus(Duration.ofMinutes(5))).rejectedWith()
        assertTrue(r is DomainError.InvalidTransition)
    }

    @Test
    fun snooze_pastTime_rejected() = runTest {
        assertEquals(DomainError.InvalidSnoozeTime, actions.snoozeStream("anti", clock.now().minusSeconds(1)).rejectedWith())
        assertEquals(DomainError.InvalidSnoozeTime, actions.snoozeStream("anti", clock.now()).rejectedWith())
    }

    @Test
    fun snoozed_explicitResume_toFocus_clearsSnooze() = runTest {
        actions.snoozeStream("anti", clock.now().plus(Duration.ofMinutes(30)))
        val s = actions.focusStream("anti").getOrNull()!!.focused
        assertEquals(FOCUS, s.state); assertNull(s.snoozedUntil)
        assertTrue(EventType.RESUMED in events("anti"))
    }

    // ---------------------------------------------------------------- ready / pause / block / complete

    @Test
    fun markReady_clearsTransientTimers() = runTest {
        actions.continueProcessing("claude", clock.now().plus(Duration.ofMinutes(5)))
        val s = actions.markReady("claude").getOrNull()!!
        assertEquals(READY, s.state); assertNull(s.checkAt); assertNull(s.processingStartedAt)
        assertEquals("next-claude", s.nextHumanAction) // context preserved
    }

    @Test
    fun pause_fromFocus_closesSession() = runTest {
        clock.advance(Duration.ofMinutes(3))
        actions.focusStream("notion")
        clock.advance(Duration.ofMinutes(4))
        actions.pauseStream("notion")
        assertEquals(PAUSED, state("notion"))
        assertNull(repo.getOpenFocusSession("notion"))
        assertEquals(Duration.ofMinutes(4), repo.getFocusSessions("notion").single().duration(clock.now()))
        assertNull(repo.getActiveFocus())
    }

    @Test
    fun block_fromFocus_storesReason_closesSession() = runTest {
        val s = actions.blockStream("psych", "Waiting for credentials").getOrNull()!!
        assertEquals(BLOCKED, s.state); assertEquals("Waiting for credentials", s.blockerReason)
        assertNull(repo.getActiveFocus())
        assertEquals(listOf(EventType.BLOCKED), events("psych"))
    }

    @Test
    fun unblock_toReady_neverAutoFocus() = runTest {
        val s = actions.unblockStream("cloud").getOrNull()!!
        assertEquals(READY, s.state); assertNull(s.blockerReason)
        assertEquals(FOCUS, state("psych")) // focus untouched
        assertEquals(DomainError.NotBlocked, actions.unblockStream("notion").rejectedWith())
    }

    @Test
    fun complete_readyToDone() = runTest {
        val s = actions.completeStream("notion").getOrNull()!!
        assertEquals(DONE, s.state); assertEquals(clock.now(), s.completedAt)
        assertEquals(listOf(EventType.COMPLETED), events("notion"))
    }

    @Test
    fun complete_fromFocus_closesSessionAndCycle() = runTest {
        actions.focusStream("notion"); clock.advance(Duration.ofMinutes(2))
        actions.completeStream("notion")
        assertNull(repo.getOpenFocusSession("notion"))
        assertNotNull(repo.getCycles("notion").single().endedAt)
        assertNull(repo.getActiveFocus())
    }

    @Test
    fun done_isTerminal() = runTest {
        actions.completeStream("notion")
        assertEquals(DomainError.StreamAlreadyDone, actions.focusStream("notion").rejectedWith())
        assertEquals(DomainError.StreamAlreadyDone, actions.pauseStream("notion").rejectedWith())
        assertEquals(DomainError.StreamAlreadyDone, actions.completeStream("notion").rejectedWith())
    }

    @Test
    fun unknownStream_notFound() = runTest {
        assertTrue(actions.focusStream("nope") is ActionResult.NotFound)
        assertTrue(actions.completeStream("nope") is ActionResult.NotFound)
        assertTrue(actions.addNote("nope", "x") is ActionResult.NotFound)
    }

    // ---------------------------------------------------------------- context / notes

    @Test
    fun updateContext_preservesOmittedFields_andClearsExplicitly() = runTest {
        val s1 = actions.updateContext("psych", ContextUpdate(waitingFor = Field.Set("Claude"))).getOrNull()!!
        assertEquals("Claude", s1.waitingFor)
        assertEquals("last-psych", s1.lastHumanAction)   // Keep
        assertEquals("next-psych", s1.nextHumanAction)   // Keep
        val s2 = actions.updateContext("psych", ContextUpdate(nextHumanAction = Field.Clear)).getOrNull()!!
        assertNull(s2.nextHumanAction)
        assertEquals("Claude", s2.waitingFor)
        assertEquals(listOf(EventType.CONTEXT_UPDATED, EventType.CONTEXT_UPDATED), events("psych"))
    }

    @Test
    fun updateContext_withNote_recordsNoteEvent() = runTest {
        actions.updateContext("psych", ContextUpdate(note = "Q17 half done"))
        assertEquals(listOf(EventType.CONTEXT_UPDATED, EventType.NOTE_ADDED), events("psych"))
        assertEquals("Q17 half done", repo.getEvents("psych").last().detail)
    }

    @Test
    fun addNote_createsHistory_rejectsBlank() = runTest {
        actions.addNote("claude", "  Try the other branch ")
        assertEquals("Try the other branch", repo.getEvents("claude").single().detail)
        assertEquals(DomainError.EmptyNote, actions.addNote("claude", "   ").rejectedWith())
    }

    // ---------------------------------------------------------------- time

    @Test
    fun focusSessionDuration_derivedFromTimestamps() = runTest {
        actions.focusStream("notion")                    // 10:00
        clock.advance(Duration.ofMinutes(8))            // 10:08
        actions.handOffStream("notion")
        assertEquals(Duration.ofMinutes(8), repo.getFocusSessions("notion").single().duration(clock.now()))
        assertEquals(Instant.parse("2026-09-11T10:08:00Z"), repo.getStream("notion")!!.processingStartedAt)
    }

    // ---------------------------------------------------------------- atomicity

    @Test
    fun transaction_publishesNothingWhenBlockThrows() = runTest {
        val before = repo.streams.value
        try {
            repo.transaction {
                saveStream(getStream("notion")!!.copy(state = FOCUS))
                throw IllegalStateException("boom")
            }
        } catch (_: IllegalStateException) { }
        assertEquals(before, repo.streams.value)
        assertEquals(READY, state("notion"))
    }
}

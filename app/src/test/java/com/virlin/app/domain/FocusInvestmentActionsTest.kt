package com.virlin.app.domain

import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.getOrNull
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.FocusInvestment
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.FOCUS
import com.virlin.app.domain.model.WorkStreamState.READY
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Cumulative human Focus invested time — derived from FocusSession timestamps.
 * Split-flap / focusInvestedSec remains current-session only; this suite guards
 * leave / resume / complete / double-commit and reminder non-counting.
 */
class FocusInvestmentActionsTest {

    private val t0: Instant = Instant.parse("2026-09-17T10:00:00Z")
    private lateinit var clock: FakeClock
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions

    @Before
    fun setUp() {
        clock = FakeClock(t0)
        repo = InMemoryWorkStreamRepository(
            seed = listOf(
                WorkStream(
                    id = "notes",
                    title = "Psychology Notes",
                    state = READY,
                    executionPreference = ExecutionPreference.HUMAN,
                    createdAt = t0,
                    updatedAt = t0
                )
            )
        )
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
    }

    private suspend fun invested(taskId: String): Long =
        FocusInvestment.total(repo.getFocusSessions("notes"), taskId, clock.now()).seconds

    private suspend fun prior(taskId: String): Long =
        FocusInvestment.priorClosedSeconds(repo.getFocusSessions("notes"), taskId)

    @Test
    fun firstFocus_thenLiveTotalEqualsSession() = runTest {
        val task = actions.createTask(CreateTask("Q17", workStreamId = "notes", id = "q17")).getOrNull()!!
        actions.setActiveTask("notes", task.id)
        actions.focusStream("notes")
        clock.advance(Duration.ofMinutes(5))
        assertEquals(5L * 60, invested("q17"))
        assertEquals(0L, prior("q17")) // still open
        assertEquals(FOCUS, repo.getStream("notes")!!.state)
    }

    @Test
    fun leave_commitsSessionOnce_reminderDurationNotCounted() = runTest {
        actions.createTask(CreateTask("Q17", workStreamId = "notes", id = "q17")).getOrNull()!!
        actions.setActiveTask("notes", "q17")
        actions.focusStream("notes")
        clock.advance(Duration.ofMinutes(7))
        val returnAt = clock.now().plus(Duration.ofMinutes(10))
        actions.leaveFocus("notes", returnAt)
        assertNull(repo.getOpenFocusSession("notes"))
        assertEquals(7L * 60, prior("q17"))
        assertEquals(7L * 60, invested("q17"))
        // Advance through the 10m break — must not grow invested time.
        clock.advance(Duration.ofMinutes(10))
        assertEquals(7L * 60, invested("q17"))
    }

    @Test
    fun resume_sessionResets_totalKeepsPrior() = runTest {
        actions.createTask(CreateTask("Q17", workStreamId = "notes", id = "q17")).getOrNull()!!
        actions.setActiveTask("notes", "q17")
        actions.focusStream("notes")
        clock.advance(Duration.ofMinutes(5))
        actions.leaveFocus("notes")
        assertEquals(5L * 60, prior("q17"))

        actions.focusStream("notes")
        assertEquals(0L, repo.getOpenFocusSession("notes")!!.duration(clock.now()).seconds)
        assertEquals(5L * 60, prior("q17"))
        assertEquals(5L * 60, invested("q17"))
    }

    @Test
    fun secondSession_liveTotalIsSum() = runTest {
        actions.createTask(CreateTask("Q17", workStreamId = "notes", id = "q17")).getOrNull()!!
        actions.setActiveTask("notes", "q17")
        actions.focusStream("notes")
        clock.advance(Duration.ofMinutes(5))
        actions.leaveFocus("notes")

        actions.focusStream("notes")
        clock.advance(Duration.ofMinutes(4))
        assertEquals(5L * 60, prior("q17"))
        assertEquals(9L * 60, invested("q17"))
    }

    @Test
    fun leaveAgain_persistsNineNotFourteen() = runTest {
        actions.createTask(CreateTask("Q17", workStreamId = "notes", id = "q17")).getOrNull()!!
        actions.setActiveTask("notes", "q17")
        actions.focusStream("notes")
        clock.advance(Duration.ofMinutes(5))
        actions.leaveFocus("notes")
        actions.focusStream("notes")
        clock.advance(Duration.ofMinutes(4))
        actions.leaveFocus("notes")
        assertEquals(9L * 60, prior("q17"))
        assertEquals(9L * 60, invested("q17"))
        assertEquals(2, repo.getFocusSessions("notes").size)
        assertTrue(repo.getFocusSessions("notes").all { !it.isOpen })
    }

    @Test
    fun complete_commitsOpenSessionOnce() = runTest {
        actions.createTask(CreateTask("Q17", workStreamId = "notes", id = "q17")).getOrNull()!!
        actions.setActiveTask("notes", "q17")
        actions.focusStream("notes")
        clock.advance(Duration.ofMinutes(20))
        actions.leaveFocus("notes")
        actions.focusStream("notes")
        clock.advance(Duration.ofMinutes(8))
        assertEquals(28L * 60, invested("q17"))

        actions.completeTask("q17")
        assertNull(repo.getOpenFocusSession("notes"))
        assertEquals(28L * 60, prior("q17"))
        assertEquals(28L * 60, invested("q17"))

        // Second complete rejected — session count unchanged (no double commit).
        val sessionsBefore = repo.getFocusSessions("notes").size
        actions.completeTask("q17")
        assertEquals(sessionsBefore, repo.getFocusSessions("notes").size)
        assertEquals(28L * 60, invested("q17"))
    }

    @Test
    fun leave_thenLeaveAgain_doesNotDoubleCommit() = runTest {
        actions.createTask(CreateTask("Q17", workStreamId = "notes", id = "q17")).getOrNull()!!
        actions.setActiveTask("notes", "q17")
        actions.focusStream("notes")
        clock.advance(Duration.ofMinutes(12))
        actions.leaveFocus("notes")
        assertEquals(12L * 60, prior("q17"))
        val n = repo.getFocusSessions("notes").size
        actions.leaveFocus("notes") // NotInFocus
        assertEquals(n, repo.getFocusSessions("notes").size)
        assertEquals(12L * 60, prior("q17"))
    }

    @Test
    fun acceptanceExample_twelveThenEightThenFifteen() = runTest {
        actions.createTask(CreateTask("Psychology Notes", workStreamId = "notes", id = "pn")).getOrNull()!!
        actions.setActiveTask("notes", "pn")

        actions.focusStream("notes")
        clock.advance(Duration.ofMinutes(12))
        actions.leaveFocus("notes", clock.now().plus(Duration.ofMinutes(5)))
        assertEquals(12L * 60, prior("pn"))

        actions.focusStream("notes")
        clock.advance(Duration.ofMinutes(8))
        assertEquals(20L * 60, invested("pn"))
        actions.leaveFocus("notes")
        assertEquals(20L * 60, prior("pn"))

        actions.focusStream("notes")
        clock.advance(Duration.ofMinutes(15))
        actions.completeTask("pn")
        assertEquals(35L * 60, invested("pn"))
    }
}

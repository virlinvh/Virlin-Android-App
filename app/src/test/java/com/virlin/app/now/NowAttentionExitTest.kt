package com.virlin.app.now

import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.screens.AttentionKind
import com.virlin.app.ui.screens.NowPresentation
import com.virlin.app.ui.screens.NowViewModel
import com.virlin.app.ui.screens.NowViewModel.Chooser
import com.virlin.app.ui.screens.NowViewModel.TimedIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Now's chooser + intent routing for the finalized attention exits (Pass 4). Real domain, fake clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class NowAttentionExitTest {

    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")
    private val dispatcher = StandardTestDispatcher()
    private lateinit var clock: FakeClock
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var vm: NowViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        clock = FakeClock(t0)
        repo = InMemoryWorkStreamRepository(seed = listOf(
            WorkStream("s1", "Psychology", state = WorkStreamState.FOCUS, activeTaskId = "q17", createdAt = t0, updatedAt = t0),
            WorkStream("s4", "Antigravity", state = WorkStreamState.PROCESSING, mode = WorkStreamMode.EXTERNAL, activeTaskId = "nl",
                checkAt = t0.plusSeconds(30), processingStartedAt = t0, createdAt = t0, updatedAt = t0)
        ))
        vm = NowViewModel(DefaultVirlinActions(repo, clock, SequentialIdProvider()), clock, repo)
    }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun s(id: String) = repo.streams.value.first { it.id == id }

    @Test fun projection_marksExternal_fromMode_notTitle() {
        assertTrue(NowPresentation.project(s("s4"), emptyList(), emptyList()).isExternal)
        assertFalse(NowPresentation.project(s("s1"), emptyList(), emptyList()).isExternal)
    }

    @Test fun leaveChooser_noReminder_ready_closesChooser() = runTest {
        vm.openLeave("s1"); assertEquals(Chooser.Leave("s1"), vm.chooser.value)
        vm.leave("s1", null); advanceUntilIdle()
        assertNull(vm.chooser.value)
        assertEquals(WorkStreamState.READY, s("s1").state); assertEquals("q17", s("s1").activeTaskId)
    }

    @Test fun leaveChooser_5m_snoozedHumanReturn() = runTest {
        vm.leave("s1", 5); advanceUntilIdle()
        val p = s("s1")
        assertEquals(WorkStreamState.SNOOZED, p.state); assertEquals(SnoozeReason.HUMAN_RETURN, p.snoozeReason)
        assertEquals(t0.plus(Duration.ofMinutes(5)), p.snoozedUntil)
    }

    @Test fun customMinutes_rejectsNonFuture_thenExecutes() = runTest {
        vm.openCustom("s1", TimedIntent.LEAVE)
        assertFalse(vm.customMinutes("s1", TimedIntent.LEAVE, 0))
        assertEquals(Chooser.Custom("s1", TimedIntent.LEAVE), vm.chooser.value)   // still open
        assertTrue(vm.customMinutes("s1", TimedIntent.LEAVE, 7)); advanceUntilIdle()
        assertNull(vm.chooser.value)
        assertEquals(t0.plus(Duration.ofMinutes(7)), s("s1").snoozedUntil)
    }

    @Test fun handOffChooser_5m_andNoCheck() = runTest {
        vm.leave("s1", null); advanceUntilIdle()
        clock.advance(Duration.ofSeconds(31)); vm.checkDue("s4"); advanceUntilIdle()
        vm.resultReadyNow("s4"); advanceUntilIdle()                             // external stream in Focus
        assertEquals(WorkStreamState.FOCUS, s("s4").state)
        vm.openHandOff("s4"); assertEquals(Chooser.HandOff("s4"), vm.chooser.value)
        vm.handOff("s4", 5); advanceUntilIdle()
        assertEquals(WorkStreamState.PROCESSING, s("s4").state); assertEquals(clock.now().plus(Duration.ofMinutes(5)), s("s4").checkAt)
        clock.advance(Duration.ofMinutes(6)); vm.checkDue("s4"); advanceUntilIdle()
        vm.resultReadyNow("s4"); advanceUntilIdle()
        vm.handOff("s4", null); advanceUntilIdle()
        assertEquals(WorkStreamState.PROCESSING, s("s4").state); assertNull(s("s4").checkAt); assertNotNull(s("s4").processingStartedAt)
    }

    @Test fun checkDue_classified_andCheckDoesNotFocus() = runTest {
        clock.advance(Duration.ofSeconds(31))
        vm.checkDue("s4"); advanceUntilIdle()
        assertEquals(AttentionKind.CHECK_DUE, NowPresentation.attention(repo.streams.value)["s4"])
        vm.openCheck("s4"); assertEquals(Chooser.CheckOutcome("s4"), vm.chooser.value)
        assertEquals("s1", repo.getActiveFocus()!!.id)                        // still Psychology
    }

    @Test fun stillRunning_backToProcessing() = runTest {
        clock.advance(Duration.ofSeconds(31)); vm.checkDue("s4"); advanceUntilIdle()
        vm.openStillRunning("s4"); vm.stillRunning("s4", 5); advanceUntilIdle()
        assertNull(vm.chooser.value)
        assertEquals(WorkStreamState.PROCESSING, s("s4").state); assertEquals(clock.now().plus(Duration.ofMinutes(5)), s("s4").checkAt)
        assertEquals("nl", s("s4").activeTaskId)
    }

    @Test fun resultReady_remindLater_defer_thenFocusNow() = runTest {
        clock.advance(Duration.ofSeconds(31)); vm.checkDue("s4"); advanceUntilIdle()
        vm.openResultReady("s4"); vm.resultReadyLater("s4", 10); advanceUntilIdle()
        val a = s("s4")
        assertEquals(WorkStreamState.SNOOZED, a.state); assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, a.snoozeReason)
        assertNull(a.processingStartedAt)
        clock.advance(Duration.ofMinutes(11)); vm.checkDue("s4"); advanceUntilIdle()
        assertEquals(AttentionKind.RESULT_READY, NowPresentation.attention(repo.streams.value)["s4"])
        vm.deferReturn("s4", 5); advanceUntilIdle()
        assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, s("s4").snoozeReason); assertEquals(WorkStreamState.SNOOZED, s("s4").state)
        clock.advance(Duration.ofMinutes(6)); vm.checkDue("s4"); advanceUntilIdle()
        vm.resultReadyNow("s4"); advanceUntilIdle()
        assertEquals(WorkStreamState.FOCUS, s("s4").state); assertEquals(WorkStreamState.READY, s("s1").state)   // one Focus
        assertEquals("nl", repo.getOpenFocusSession("s4")!!.taskId)
    }

    @Test fun humanReturnDue_classified_resumeAndDefer() = runTest {
        vm.leave("s1", 5); advanceUntilIdle()
        clock.advance(Duration.ofMinutes(6)); vm.checkDue("s1"); advanceUntilIdle()
        assertEquals(AttentionKind.RETURN_DUE, NowPresentation.attention(repo.streams.value)["s1"])
        vm.deferReturn("s1", 5); advanceUntilIdle()
        assertEquals(WorkStreamState.SNOOZED, s("s1").state); assertEquals(SnoozeReason.HUMAN_RETURN, s("s1").snoozeReason)
        clock.advance(Duration.ofMinutes(6)); vm.checkDue("s1"); advanceUntilIdle()
        vm.focus("s1"); advanceUntilIdle()                                     // RESUME
        assertEquals(WorkStreamState.FOCUS, s("s1").state); assertEquals("q17", repo.getOpenFocusSession("s1")!!.taskId)
    }

    @Test fun blockedFromCheck() = runTest {
        clock.advance(Duration.ofSeconds(31)); vm.checkDue("s4"); advanceUntilIdle()
        vm.block("s4"); advanceUntilIdle()
        assertEquals(WorkStreamState.BLOCKED, s("s4").state); assertNull(s("s4").checkAt); assertEquals("nl", s("s4").activeTaskId)
    }

}

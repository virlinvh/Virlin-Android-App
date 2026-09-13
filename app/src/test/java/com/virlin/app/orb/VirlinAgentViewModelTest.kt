package com.virlin.app.orb

import com.virlin.app.ui.orb.AgentMode
import com.virlin.app.ui.orb.VirlinAgentViewModel
import com.virlin.app.ui.orb.VirlinOrbInteractionState as S
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * State-machine tests for the single Orb/Agent state owner. Virtual time via runTest.
 * Timings here mirror the ViewModel defaults; the assertions are about ORDER and SAFETY,
 * not exact durations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirlinAgentViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: VirlinAgentViewModel
    private val t = VirlinAgentViewModel.Timing()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = VirlinAgentViewModel(t)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun openToReady() {
        vm.onOrbPressed()
        vm.onOrbReleased(tapped = true)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(S.Ready, vm.orbState.value)
    }

    // ---------------------------------------------------------------- open / close

    @Test
    fun openSequence_idlePressedOpeningReady() = runTest(dispatcher) {
        assertEquals(S.Idle, vm.orbState.value)
        vm.onOrbPressed()
        assertEquals(S.Pressed, vm.orbState.value)
        vm.onOrbReleased(tapped = true)
        assertEquals(S.Opening, vm.orbState.value)
        advanceTimeBy(t.openingMs + 1)
        assertEquals(S.Ready, vm.orbState.value)
    }

    @Test
    fun cancelledPress_returnsToIdle_withoutOpening() = runTest(dispatcher) {
        vm.onOrbPressed()
        vm.onOrbReleased(tapped = false)
        advanceUntilIdle()
        assertEquals(S.Idle, vm.orbState.value)
    }

    @Test
    fun closeSequence_readyClosingIdle() = runTest(dispatcher) {
        openToReady()
        vm.dismiss()
        assertEquals(S.Closing, vm.orbState.value)
        advanceTimeBy(t.closingMs + 1)
        assertEquals(S.Idle, vm.orbState.value)
    }

    @Test
    fun rapidRepeatedOpenRequests_doNotDuplicateOrCorrupt() = runTest(dispatcher) {
        vm.onOrbPressed(); vm.onOrbReleased(true)
        // Hammer it while Opening.
        repeat(5) { vm.onOrbPressed(); vm.onOrbReleased(true); vm.requestOpen() }
        assertEquals(S.Opening, vm.orbState.value)
        advanceTimeBy(t.openingMs + 1)
        assertEquals(S.Ready, vm.orbState.value)
        // And while Ready.
        repeat(5) { vm.onOrbPressed(); vm.onOrbReleased(true); vm.requestOpen() }
        advanceUntilIdle()
        assertEquals(S.Ready, vm.orbState.value)
    }

    // ---------------------------------------------------------------- composer

    @Test
    fun typing_thenSettle_readyWithInput() = runTest(dispatcher) {
        openToReady()
        vm.onComposerTextChanged("Give Claude five more minutes")
        assertEquals(S.Receiving, vm.orbState.value)
        advanceTimeBy(t.composerSettleMs + 1)
        assertEquals(S.ReadyWithInput, vm.orbState.value)
    }

    @Test
    fun clearingText_settlesBackToReady() = runTest(dispatcher) {
        openToReady()
        vm.onComposerTextChanged("x")
        vm.onComposerTextChanged("")
        advanceTimeBy(t.composerSettleMs + 1)
        assertEquals(S.Ready, vm.orbState.value)
    }

    // ---------------------------------------------------------------- pipelines

    @Test
    fun standardSubmit_understandingActingSuccessReady() = runTest(dispatcher) {
        openToReady()
        vm.onComposerTextChanged("Give Claude five more minutes")
        advanceTimeBy(t.composerSettleMs + 1)
        vm.submit()
        assertEquals(S.Understanding, vm.orbState.value)
        advanceTimeBy(t.understandingMs + 1)
        assertEquals(S.Acting, vm.orbState.value)
        advanceTimeBy(t.actingMs + 1)
        assertEquals(S.Success, vm.orbState.value)
        val nonceAtSuccess = vm.oneShotNonce.value
        advanceTimeBy(t.successMs + 1)
        assertEquals(S.Ready, vm.orbState.value)
        assertEquals(1L, nonceAtSuccess)
    }

    @Test
    fun captureMode_yieldsCaptureSuccess_thenReady() = runTest(dispatcher) {
        openToReady()
        vm.onModeSelected(AgentMode.CAPTURE)
        vm.onComposerTextChanged("Keep this prompt")
        advanceTimeBy(t.composerSettleMs + 1)
        vm.submit()
        advanceTimeBy(t.understandingMs + t.actingMs + 2)
        assertEquals(S.CaptureSuccess, vm.orbState.value)
        advanceTimeBy(t.captureSuccessMs + 1)
        assertEquals(S.Ready, vm.orbState.value)
    }

    @Test
    fun clarification_thenAnswer_completesToSuccess() = runTest(dispatcher) {
        openToReady()
        vm.onComposerTextChanged("Remind me to check it later")
        advanceTimeBy(t.composerSettleMs + 1)
        vm.submit()
        advanceTimeBy(t.understandingMs + 1)
        assertEquals(S.Clarification, vm.orbState.value)
        assertNotNull(vm.clarificationPrompt.value)
        vm.onClarificationAnswered("10 min")
        assertNull(vm.clarificationPrompt.value)
        assertEquals(S.Understanding, vm.orbState.value)
        advanceTimeBy(t.understandingMs + t.actingMs + 2)
        assertEquals(S.Success, vm.orbState.value)
        advanceTimeBy(t.successMs + 1)
        assertEquals(S.Ready, vm.orbState.value)
    }

    @Test
    fun speakingOutcome_returnsToReady() = runTest(dispatcher) {
        openToReady()
        vm.onComposerTextChanged("Tell me what's next")
        advanceTimeBy(t.composerSettleMs + 1)
        vm.submit()
        advanceTimeBy(t.understandingMs + 1)
        assertEquals(S.Speaking, vm.orbState.value)
        advanceTimeBy(t.speakingMs + 1)
        assertEquals(S.Ready, vm.orbState.value)
    }

    @Test
    fun errorOutcome_returnsToReady_neverStuck() = runTest(dispatcher) {
        openToReady()
        vm.onComposerTextChanged("This will fail")
        advanceTimeBy(t.composerSettleMs + 1)
        vm.submit()
        advanceTimeBy(t.understandingMs + t.actingMs + 2)
        assertEquals(S.Error, vm.orbState.value)
        advanceTimeBy(t.errorMs + 1)
        assertEquals(S.Ready, vm.orbState.value)
    }

    // ---------------------------------------------------------------- cancellation safety

    @Test
    fun closeDuringActing_noDelayedSuccessAfterClose() = runTest(dispatcher) {
        openToReady()
        vm.onComposerTextChanged("do it")
        advanceTimeBy(t.composerSettleMs + 1)
        vm.submit()
        advanceTimeBy(t.understandingMs + 1)
        assertEquals(S.Acting, vm.orbState.value)
        vm.dismiss()
        assertEquals(S.Closing, vm.orbState.value)
        // Wait well past when Success would have fired.
        advanceTimeBy(t.closingMs + t.actingMs + t.successMs + 100)
        assertEquals(S.Idle, vm.orbState.value)
        assertEquals(0L, vm.oneShotNonce.value) // Success never fired
    }

    @Test
    fun closeDuringSpeaking_cancelsSpeaking_returnsIdle() = runTest(dispatcher) {
        openToReady()
        vm.onComposerTextChanged("say hello")
        advanceTimeBy(t.composerSettleMs + 1)
        vm.submit()
        advanceTimeBy(t.understandingMs + 1)
        assertEquals(S.Speaking, vm.orbState.value)
        vm.dismiss()
        advanceUntilIdle()
        assertEquals(S.Idle, vm.orbState.value)
    }

    @Test
    fun closeDuringUnderstanding_cancelsPipeline() = runTest(dispatcher) {
        openToReady()
        vm.onComposerTextChanged("do it")
        advanceTimeBy(t.composerSettleMs + 1)
        vm.submit()
        assertEquals(S.Understanding, vm.orbState.value)
        vm.dismiss()
        advanceUntilIdle()
        assertEquals(S.Idle, vm.orbState.value)
        assertNull(vm.clarificationPrompt.value)
        assertEquals("", vm.composerText.value)
    }

    @Test
    fun closeDuringReceiving_cancelsSettle() = runTest(dispatcher) {
        openToReady()
        vm.onComposerTextChanged("typing…")
        assertEquals(S.Receiving, vm.orbState.value)
        vm.dismiss()
        advanceUntilIdle()
        assertEquals(S.Idle, vm.orbState.value)
    }

    @Test
    fun reopenAfterClose_isCleanReady() = runTest(dispatcher) {
        openToReady()
        vm.onComposerTextChanged("something")
        vm.dismiss()
        advanceUntilIdle()
        openToReady()
        assertEquals("", vm.composerText.value)
        assertEquals(S.Ready, vm.orbState.value)
    }

    @Test
    fun rapidOpenClose_tenTimes_endsConsistent() = runTest(dispatcher) {
        repeat(10) {
            vm.onOrbPressed(); vm.onOrbReleased(true)
            advanceTimeBy(30) // close while still Opening
            vm.dismiss()
            advanceTimeBy(30)
        }
        advanceUntilIdle()
        assertEquals(S.Idle, vm.orbState.value)
    }

    // ---------------------------------------------------------------- invalid / stale

    @Test
    fun staleEvents_areIgnored_stateNotCorrupted() = runTest(dispatcher) {
        // Composer events while Idle are ignored.
        vm.onComposerTextChanged("x"); vm.submit(); vm.onClarificationAnswered("5 min")
        vm.onModeSelected(AgentMode.CAPTURE)
        assertEquals(S.Idle, vm.orbState.value)
        assertEquals(AgentMode.CONTROL, vm.workspace.value.mode)
        // Release without press is ignored.
        vm.onOrbReleased(true)
        assertEquals(S.Idle, vm.orbState.value)
        // Submit with blank text is ignored.
        openToReady()
        vm.submit()
        assertEquals(S.Ready, vm.orbState.value)
        // Dismiss when already Idle is a no-op.
        vm.dismiss(); advanceUntilIdle()
        vm.dismiss()
        assertEquals(S.Idle, vm.orbState.value)
    }

    @Test
    fun submitWhileUnderstanding_isIgnored() = runTest(dispatcher) {
        openToReady()
        vm.onComposerTextChanged("do it")
        advanceTimeBy(t.composerSettleMs + 1)
        vm.submit()
        vm.onComposerTextChanged("more") // ignored: not interactive
        vm.submit()                      // ignored
        assertEquals(S.Understanding, vm.orbState.value)
        assertEquals("", vm.composerText.value)
    }
}

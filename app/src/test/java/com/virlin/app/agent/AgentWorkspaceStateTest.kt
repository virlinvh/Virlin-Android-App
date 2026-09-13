package com.virlin.app.agent

import com.virlin.app.model.CaptureType
import com.virlin.app.ui.agent.CreateType
import com.virlin.app.ui.agent.InputObject
import com.virlin.app.ui.agent.Receipt
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Workspace UI-state logic (modes, context, creation, attachments, receipts). Mock only. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentWorkspaceStateTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: VirlinAgentViewModel
    private val t = VirlinAgentViewModel.Timing()

    @Before fun setUp() { Dispatchers.setMain(dispatcher); vm = VirlinAgentViewModel(t) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun open() {
        vm.onOrbPressed(); vm.onOrbReleased(true); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(S.Ready, vm.orbState.value)
    }
    private val ws get() = vm.workspace.value

    @Test
    fun modeSwitching_preservesOpenState_andClearsReceipt() = runTest(dispatcher) {
        open()
        vm.onModeSelected(AgentMode.CREATE); assertEquals(AgentMode.CREATE, ws.mode)
        vm.onModeSelected(AgentMode.CAPTURE); assertEquals(AgentMode.CAPTURE, ws.mode)
        vm.onModeSelected(AgentMode.CONTROL); assertEquals(AgentMode.CONTROL, ws.mode)
        assertEquals(S.Ready, vm.orbState.value)              // switching never closes/changes Orb state
        // A receipt is cleared by a mode switch.
        vm.onComposerTextChanged("do it"); advanceTimeBy(t.composerSettleMs + 1); vm.submit()
        advanceTimeBy(t.understandingMs + t.actingMs + 2)
        assertNotNull(ws.receipt)
        vm.onModeSelected(AgentMode.CREATE)
        assertNull(ws.receipt)
    }

    @Test
    fun rapidModeSwitching_doesNotCorrupt() = runTest(dispatcher) {
        open()
        repeat(30) { vm.onModeSelected(AgentMode.values()[it % 3]) }
        assertEquals(AgentMode.CAPTURE, ws.mode)
        assertEquals(S.Ready, vm.orbState.value)
    }

    @Test
    fun controlContext_selectToggles_andDoesNotTouchStreams() = runTest(dispatcher) {
        open()
        val id = vm.controlContexts.first().id
        vm.onControlContextSelected(id); assertEquals(id, ws.selectedControlContextId)
        vm.onControlContextSelected(id); assertNull(ws.selectedControlContextId)   // toggle off
        vm.onControlContextSelected(id)
        assertTrue(vm.controlContexts.isNotEmpty() && vm.controlContexts.size <= 4)
    }

    @Test
    fun createType_andDestination_selection() = runTest(dispatcher) {
        open(); vm.onModeSelected(AgentMode.CREATE)
        assertEquals(CreateType.TASK, ws.createType)
        vm.onCreateTypeSelected(CreateType.WORKSTREAM); assertEquals(CreateType.WORKSTREAM, ws.createType)
        vm.onCreateTypeSelected(CreateType.REMINDER); assertEquals(CreateType.REMINDER, ws.createType)
        val d = vm.destinations.first().id
        vm.onCreateDestinationSelected(d); assertEquals(d, ws.createDestinationId)
        vm.onCreateDestinationSelected(d); assertNull(ws.createDestinationId)      // optional, toggles
    }

    @Test
    fun composerText_isRetainedAcrossModeSwitch_andClearedOnSubmit() = runTest(dispatcher) {
        open()
        vm.onComposerTextChanged("Test Orb state animation")
        vm.onModeSelected(AgentMode.CREATE)
        assertEquals("Test Orb state animation", vm.composerText.value)
        advanceTimeBy(t.composerSettleMs + 1)
        vm.submit()
        assertEquals("", vm.composerText.value)
    }

    @Test
    fun attachments_addAndRemove_mixedCaptureBundle() = runTest(dispatcher) {
        open(); vm.onModeSelected(AgentMode.CAPTURE)
        vm.onAttachmentMenuToggle(); assertTrue(ws.attachmentMenuOpen)
        vm.onAddDemoAttachment(CaptureType.VOICE)
        assertTrue(!ws.attachmentMenuOpen)                     // picking closes the menu
        vm.onAddDemoAttachment(CaptureType.PROMPT)
        vm.onAddDemoAttachment(CaptureType.LINK)
        vm.onAddDemoAttachment(CaptureType.IMAGE)
        vm.onAddDemoAttachment(CaptureType.FILE)
        assertEquals(5, ws.attachments.size)
        assertTrue(ws.attachments[0] is InputObject.Voice)
        assertTrue(ws.attachments[1] is InputObject.Prompt)
        assertTrue(ws.attachments[2] is InputObject.Link)
        assertTrue(ws.attachments[3] is InputObject.Image)
        assertTrue(ws.attachments[4] is InputObject.File)
        assertTrue((ws.attachments[1] as InputObject.Prompt).preview.contains("\n")) // structured text
        vm.onRemoveAttachment(ws.attachments[2].id)
        assertEquals(4, ws.attachments.size)
        assertTrue(ws.attachments.none { it is InputObject.Link })
        // Attachments alone (no text) count as input.
        advanceTimeBy(t.composerSettleMs + 1)
        assertEquals(S.ReadyWithInput, vm.orbState.value)
    }

    @Test
    fun saveToInbox_producesCaptureSuccess_andReceipt_andClearsTray() = runTest(dispatcher) {
        open(); vm.onModeSelected(AgentMode.CAPTURE)
        vm.onAddDemoAttachment(CaptureType.VOICE); vm.onAddDemoAttachment(CaptureType.PROMPT); vm.onAddDemoAttachment(CaptureType.LINK)
        vm.onComposerTextChanged("note about the orb")
        advanceTimeBy(t.composerSettleMs + 1)
        vm.saveToInbox()
        advanceTimeBy(t.understandingMs + t.actingMs + 2)
        assertEquals(S.CaptureSuccess, vm.orbState.value)
        val r = ws.receipt!!
        assertEquals(Receipt.Kind.CAPTURE_SUCCESS, r.kind)
        assertEquals("Saved to Inbox", r.status)
        assertEquals("4 items captured", r.primary)
        assertTrue(ws.attachments.isEmpty())
        advanceTimeBy(t.captureSuccessMs + 1)
        assertEquals(S.Ready, vm.orbState.value)
    }

    @Test
    fun createTask_receipt_carriesTextAndDestination() = runTest(dispatcher) {
        open(); vm.onModeSelected(AgentMode.CREATE); vm.onCreateTypeSelected(CreateType.TASK)
        val d = vm.destinations.first()
        vm.onCreateDestinationSelected(d.id)
        vm.onComposerTextChanged("Test Orb state animation"); advanceTimeBy(t.composerSettleMs + 1)
        vm.submit(); advanceTimeBy(t.understandingMs + t.actingMs + 2)
        val r = ws.receipt!!
        assertEquals("Task added", r.status)
        assertEquals("Test Orb state animation", r.primary)
        assertEquals(d.name, r.secondary)
        assertTrue("Undo" in r.actions)
    }

    @Test
    fun reminderWithoutTime_entersClarification_thenSucceeds() = runTest(dispatcher) {
        open(); vm.onModeSelected(AgentMode.CREATE); vm.onCreateTypeSelected(CreateType.REMINDER)
        vm.onComposerTextChanged("Remind me to check Claude."); advanceTimeBy(t.composerSettleMs + 1)
        vm.submit(); advanceTimeBy(t.understandingMs + 1)
        assertEquals(S.Clarification, vm.orbState.value)
        assertEquals("When should I remind you?", vm.clarificationPrompt.value)
        vm.onClarificationAnswered("10 min")
        advanceTimeBy(t.understandingMs + t.actingMs + 2)
        assertEquals(S.Success, vm.orbState.value)
        assertEquals("Reminder set", ws.receipt!!.status)
        assertTrue(ws.receipt!!.primary.endsWith("10 min"))
    }

    @Test
    fun undo_clearsReceiptOnly() = runTest(dispatcher) {
        open(); vm.onComposerTextChanged("do it"); advanceTimeBy(t.composerSettleMs + 1); vm.submit()
        advanceTimeBy(t.understandingMs + t.actingMs + t.successMs + 3)
        assertNotNull(ws.receipt)
        vm.onUndoReceipt()
        assertNull(ws.receipt)
        assertEquals(S.Ready, vm.orbState.value)
    }

    @Test
    fun errorPath_showsErrorReceipt_thenReady() = runTest(dispatcher) {
        open(); vm.onComposerTextChanged("this will fail"); advanceTimeBy(t.composerSettleMs + 1); vm.submit()
        advanceTimeBy(t.understandingMs + t.actingMs + 2)
        assertEquals(S.Error, vm.orbState.value)
        assertEquals(Receipt.Kind.ERROR, ws.receipt!!.kind)
        assertEquals("Couldn't complete that.", ws.receipt!!.primary)
        advanceTimeBy(t.errorMs + 1)
        assertEquals(S.Ready, vm.orbState.value)
    }

    @Test
    fun close_cleansTransientUiState_butKeepsPreferences() = runTest(dispatcher) {
        open(); vm.onModeSelected(AgentMode.CREATE); vm.onCreateTypeSelected(CreateType.REMINDER)
        vm.onCreateDestinationSelected(vm.destinations.first().id)
        vm.onAddDemoAttachment(CaptureType.LINK); vm.onAttachmentMenuToggle()
        vm.onComposerTextChanged("draft")
        vm.dismiss(); advanceUntilIdle()
        assertEquals(S.Idle, vm.orbState.value)
        assertTrue(ws.attachments.isEmpty()); assertTrue(!ws.attachmentMenuOpen)
        assertNull(ws.receipt); assertNull(ws.createDestinationId); assertNull(ws.selectedControlContextId)
        assertEquals("", vm.composerText.value)
        assertEquals(AgentMode.CREATE, ws.mode); assertEquals(CreateType.REMINDER, ws.createType)
    }

    @Test
    fun workspaceEvents_whileClosed_areIgnored() = runTest(dispatcher) {
        vm.onModeSelected(AgentMode.CAPTURE)
        vm.onAddDemoAttachment(CaptureType.VOICE)
        vm.onAttachmentMenuToggle()
        assertEquals(AgentMode.CONTROL, ws.mode)
        assertTrue(ws.attachments.isEmpty()); assertTrue(!ws.attachmentMenuOpen)
    }
}

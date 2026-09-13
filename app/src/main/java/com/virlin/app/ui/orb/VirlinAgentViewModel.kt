package com.virlin.app.ui.orb

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.virlin.app.mock.MockData
import com.virlin.app.model.CaptureType
import com.virlin.app.model.StreamState
import com.virlin.app.ui.agent.AgentWorkspaceUiState
import com.virlin.app.ui.agent.ControlContext
import com.virlin.app.ui.agent.CreateType
import com.virlin.app.ui.agent.Destination
import com.virlin.app.ui.agent.InputObject
import com.virlin.app.ui.agent.Receipt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.virlin.app.ui.orb.VirlinOrbInteractionState as S

/**
 * THE single owner of the Living Orb's interaction state AND the Agent workspace's UI state.
 *
 * Unidirectional: UI sends events in; state flows out through [orbState], [workspace],
 * [composerText], [clarificationPrompt]. Nothing else may hold a copy of any of it.
 *
 * ## Transient-state safety
 * Every timed transition runs in ONE [transientJob] inside [viewModelScope]. Starting a new
 * transient cancels the previous one, and [dismiss] cancels it outright — so a delayed
 * Success / CaptureSuccess / Speaking can never surface after the Agent has closed. After
 * every delay the job re-checks that the state is still what it expected before
 * transitioning, so a stale job can never corrupt state even if a cancellation raced.
 *
 * ## Scope
 * No AI, no command interpretation, no Action Layer, no persistence. Outcomes are
 * demo-selected purely to exercise the state machine and the workspace shell
 * (see [chooseDemoOutcome]); receipts are temporary UI confirmations built from what the
 * user entered, not from any real action.
 */
class VirlinAgentViewModel(
    private val timing: Timing = Timing()
) : ViewModel() {

    /** Demonstration timings. Real actions will replace the pipeline delays. */
    data class Timing(
        val openingMs: Long = 260,
        val closingMs: Long = 360,
        val composerSettleMs: Long = 900,
        val understandingMs: Long = 700,
        val actingMs: Long = 650,
        val successMs: Long = 800,
        val captureSuccessMs: Long = 950,
        val speakingMs: Long = 1_800,
        val errorMs: Long = 900
    )

    private val _orbState = MutableStateFlow<S>(S.Idle)
    val orbState: StateFlow<S> = _orbState.asStateFlow()

    private val _workspace = MutableStateFlow(AgentWorkspaceUiState())
    val workspace: StateFlow<AgentWorkspaceUiState> = _workspace.asStateFlow()

    private val _composerText = MutableStateFlow("")
    val composerText: StateFlow<String> = _composerText.asStateFlow()

    private val _clarificationPrompt = MutableStateFlow<String?>(null)
    val clarificationPrompt: StateFlow<String?> = _clarificationPrompt.asStateFlow()

    /** Increments for every one-shot so the renderer sees repeated events as distinct. */
    private val _oneShotNonce = MutableStateFlow(0L)
    val oneShotNonce: StateFlow<Long> = _oneShotNonce.asStateFlow()

    /** Display lists for the shell, derived from existing mock data. Not mutated here. */
    val controlContexts: List<ControlContext> = buildControlContexts()
    val destinations: List<Destination> = MockData.projects.take(4).map { Destination(it.id, it.name) }

    private var transientJob: Job? = null
    private var attachmentSeq = 0

    /** What the user submitted, kept only to build the receipt when the pipeline finishes. */
    private data class Submission(val mode: AgentMode, val text: String, val attachmentCount: Int,
                                  val createType: CreateType, val destination: String?, val context: String?)
    private var pendingSubmission: Submission? = null

    // ------------------------------------------------------------------ Orb gestures

    fun onOrbPressed() {
        if (_orbState.value == S.Idle) transition(S.Pressed)
    }

    fun onOrbReleased(tapped: Boolean) {
        if (_orbState.value != S.Pressed) return
        if (tapped) requestOpen() else transition(S.Idle)
    }

    /** Guarded so rapid repeated taps can never start a second open job or duplicate the surface. */
    fun requestOpen() {
        val from = _orbState.value
        if (from != S.Idle && from != S.Pressed) return
        // Every open starts at the entry sheet: the user chooses CONTROL / CREATE / CAPTURE first.
        _workspace.update { it.copy(modeChosen = false) }
        transition(S.Opening)
        runTransient(expectFrom = S.Opening, afterMs = timing.openingMs) { S.Ready }
    }

    // ------------------------------------------------------------------ Workspace: modes & context

    fun onModeSelected(mode: AgentMode) {
        if (!_orbState.value.isAgentSurfaceVisible) return
        _workspace.update { it.copy(mode = mode, modeChosen = true, receipt = null, attachmentMenuOpen = false) }
    }

    /** ← on a workspace: back to the entry selector. The Agent stays open; transient state clears. */
    fun returnToEntry() {
        if (!_orbState.value.isAgentSurfaceVisible) return
        _workspace.update { it.copy(modeChosen = false, receipt = null, attachmentMenuOpen = false) }
    }

    fun onControlContextSelected(id: String) {
        _workspace.update { it.copy(selectedControlContextId = if (it.selectedControlContextId == id) null else id) }
    }

    fun onCreateTypeSelected(type: CreateType) {
        _workspace.update { it.copy(createType = type, receipt = null) }
    }

    fun onCreateDestinationSelected(id: String) {
        _workspace.update { it.copy(createDestinationId = if (it.createDestinationId == id) null else id) }
    }

    // ------------------------------------------------------------------ Workspace: composer & attachments

    /** Any input arriving — typing, paste, transcription. Input-agnostic. */
    fun onComposerTextChanged(text: String) {
        if (!_orbState.value.isAgentInteractive) return
        _composerText.value = text
        transition(S.Receiving)
        settleComposer()
    }

    fun onAttachmentMenuToggle() {
        if (!_orbState.value.isAgentInteractive) return
        _workspace.update { it.copy(attachmentMenuOpen = !it.attachmentMenuOpen) }
    }

    /** Adds a demo input object of the given kind. Same Receiving path as typing. */
    fun onAddDemoAttachment(type: CaptureType) {
        if (!_orbState.value.isAgentInteractive) return
        val obj = demoAttachment(type) ?: return
        _workspace.update { it.copy(attachments = it.attachments + obj, attachmentMenuOpen = false) }
        transition(S.Receiving)
        settleComposer(forceInput = true)
    }

    /** Kept for callers that only know "something arrived". */
    fun onAttachmentAdded() = onAddDemoAttachment(CaptureType.FILE)

    fun onRemoveAttachment(id: String) {
        _workspace.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }
        if (_orbState.value.isAgentInteractive) {
            transition(S.Receiving)
            settleComposer()
        }
    }

    fun onUndoReceipt() {
        _workspace.update { it.copy(receipt = null) }
    }

    private fun settleComposer(forceInput: Boolean = false) {
        runTransient(expectFrom = S.Receiving, afterMs = timing.composerSettleMs) {
            val hasInput = forceInput || _composerText.value.isNotBlank() || _workspace.value.attachments.isNotEmpty()
            if (hasInput) S.ReadyWithInput else S.Ready
        }
    }

    /** Submit the composer (Send / Save). Starts the Understanding pipeline. */
    fun submit() {
        val from = _orbState.value
        if (from != S.ReadyWithInput && from != S.Receiving) return
        val ws = _workspace.value
        val text = _composerText.value
        if (text.isBlank() && ws.attachments.isEmpty()) return
        pendingSubmission = Submission(
            mode = ws.mode,
            text = text.trim(),
            attachmentCount = ws.attachments.size + (if (text.isNotBlank()) 1 else 0),
            createType = ws.createType,
            destination = destinations.firstOrNull { it.id == ws.createDestinationId }?.name,
            context = controlContexts.firstOrNull { it.id == ws.selectedControlContextId }?.title
        )
        _composerText.value = ""
        _workspace.update { it.copy(receipt = null, attachmentMenuOpen = false) }
        runPipeline(chooseDemoOutcome(text, ws))
    }

    /** Legacy demo path (shell without injected capture content). In the app, CAPTURE's SAVE TO INBOX is routed to `AgentCaptureViewModel.save` by `VirlinApp` (Pass 10). */
    fun saveToInbox() = submit()

    fun onClarificationAnswered(answer: String) {
        if (_orbState.value != S.Clarification) return
        _clarificationPrompt.value = null
        Log.d(TAG, "clarification answered: $answer")
        pendingSubmission = pendingSubmission?.let { it.copy(text = it.text + " · $answer") }
        runPipeline(DemoOutcome.SUCCESS)
    }

    /** Close from ANY open state. X, scrim, Android Back and gesture all route here. */
    fun dismiss() {
        val from = _orbState.value
        if (from == S.Idle || from == S.Closing) return
        if (from == S.Pressed) { transition(S.Idle); return }
        cancelTransient()
        _clarificationPrompt.value = null
        _composerText.value = ""
        pendingSubmission = null
        // Transient UI state is cleared; mode and creation type are user preferences and persist.
        _workspace.update {
            it.copy(selectedControlContextId = null, createDestinationId = null,
                attachments = emptyList(), attachmentMenuOpen = false, receipt = null)
        }
        transition(S.Closing)
        runTransient(expectFrom = S.Closing, afterMs = timing.closingMs) { S.Idle }
    }

    // ------------------------------------------------------------------ Demo pipeline

    enum class DemoOutcome { SUCCESS, CLARIFICATION, SPEAKING, ERROR }

    /** Demo-only outcome selection. NOT command parsing — just exercises the machine. */
    private fun chooseDemoOutcome(text: String, ws: AgentWorkspaceUiState): DemoOutcome {
        val t = text.lowercase()
        val reminderWithoutTime = ws.mode == AgentMode.CREATE && ws.createType == CreateType.REMINDER &&
            !t.any { it.isDigit() }
        return when {
            "fail" in t || "error" in t -> DemoOutcome.ERROR
            "say" in t || "speak" in t || "tell me" in t -> DemoOutcome.SPEAKING
            reminderWithoutTime || (("remind" in t || "later" in t) && !t.any { it.isDigit() }) -> DemoOutcome.CLARIFICATION
            else -> DemoOutcome.SUCCESS
        }
    }

    private fun runPipeline(outcome: DemoOutcome) {
        transition(S.Understanding)
        transientJob?.cancel()
        transientJob = viewModelScope.launch {
            delay(timing.understandingMs)
            if (!isActive || _orbState.value != S.Understanding) return@launch

            when (outcome) {
                DemoOutcome.CLARIFICATION -> {
                    _clarificationPrompt.value = "When should I remind you?"
                    transition(S.Clarification)
                }
                DemoOutcome.SPEAKING -> {
                    transition(S.Speaking)
                    delay(timing.speakingMs)
                    if (!isActive || _orbState.value != S.Speaking) return@launch
                    transition(S.Ready)
                }
                DemoOutcome.ERROR, DemoOutcome.SUCCESS -> {
                    transition(S.Acting)
                    delay(timing.actingMs)
                    if (!isActive || _orbState.value != S.Acting) return@launch
                    if (outcome == DemoOutcome.ERROR) {
                        _workspace.update { it.copy(receipt = errorReceipt()) }
                        transition(S.Error)
                        delay(timing.errorMs)
                        if (!isActive || _orbState.value != S.Error) return@launch
                    } else {
                        val capture = _workspace.value.mode == AgentMode.CAPTURE
                        _oneShotNonce.update { it + 1 }
                        _workspace.update { it.copy(receipt = successReceipt(), attachments = if (capture) emptyList() else it.attachments) }
                        transition(if (capture) S.CaptureSuccess else S.Success)
                        delay(if (capture) timing.captureSuccessMs else timing.successMs)
                        val expected = if (capture) S.CaptureSuccess else S.Success
                        if (!isActive || _orbState.value != expected) return@launch
                    }
                    pendingSubmission = null
                    transition(S.Ready)
                }
            }
        }
    }

    // ------------------------------------------------------------------ Receipts (mock)

    private fun successReceipt(): Receipt {
        val s = pendingSubmission ?: return Receipt(Receipt.Kind.SUCCESS, "Done", "")
        return when (s.mode) {
            AgentMode.CONTROL -> Receipt(
                kind = Receipt.Kind.SUCCESS,
                status = "Done",
                primary = s.text.ifBlank { "Instruction received" },
                secondary = s.context,
                actions = listOf("Undo")
            )
            AgentMode.CREATE -> Receipt(
                kind = Receipt.Kind.SUCCESS,
                status = s.createType.verb,
                primary = s.text.ifBlank { s.createType.label },
                secondary = s.destination,
                actions = if (s.createType == CreateType.REMINDER) listOf("Edit", "Undo") else listOf("Open", "Undo")
            )
            AgentMode.CAPTURE -> Receipt(
                kind = Receipt.Kind.CAPTURE_SUCCESS,
                status = "Saved to Inbox",
                primary = if (s.attachmentCount == 1) "1 item captured" else "${s.attachmentCount} items captured",
                secondary = null,
                actions = listOf("Open Inbox", "Undo")
            )
        }
    }

    private fun errorReceipt() = Receipt(
        kind = Receipt.Kind.ERROR,
        status = "Something went wrong",
        primary = "Couldn't complete that.",
        actions = listOf("Try again")
    )

    // ------------------------------------------------------------------ Demo objects

    private fun demoAttachment(type: CaptureType): InputObject? {
        val id = "a${++attachmentSeq}"
        return when (type) {
            CaptureType.PROMPT -> InputObject.Prompt(id, "Claude implementation prompt",
                "You are implementing the Virlin Living Orb…\n1. Preserve the shader\n2. Modulate by state", lineCount = 14)
            CaptureType.LINK -> InputObject.Link(id, "https://github.com/android/compose-samples", "github.com", "android/compose-samples")
            CaptureType.FILE -> InputObject.File(id, "orb-spec.pdf", "PDF", "1.2 MB")
            CaptureType.IMAGE -> InputObject.Image(id, "Screenshot", "screenshot.png")
            CaptureType.VOICE -> InputObject.Voice(id, durationSec = 38)
            CaptureType.TEXT, CaptureType.BUNDLE -> null
        }
    }

    private fun buildControlContexts(): List<ControlContext> {
        val projects = MockData.projects.associateBy { it.id }
        return MockData.streams.value
            .filter { it.state == StreamState.FOCUS || it.state == StreamState.NEEDS_YOU ||
                      it.state == StreamState.PROCESSING || it.state == StreamState.READY }
            .take(4)
            .map { s ->
                ControlContext(
                    id = s.id,
                    title = s.title.substringBefore(" · "),
                    subtitle = projects[s.projectId]?.name ?: s.subtitle,
                    stateLabel = when (s.state) {
                        StreamState.FOCUS -> "Focus"
                        StreamState.NEEDS_YOU -> "Check due"
                        StreamState.PROCESSING -> "Processing"
                        StreamState.READY -> "Ready"
                        else -> s.state.name.lowercase().replaceFirstChar { it.uppercase() }
                    }
                )
            }
    }

    // ------------------------------------------------------------------ Lab (dev only)

    fun debugForceState(state: S) {
        cancelTransient()
        if (state == S.Success || state == S.CaptureSuccess) _oneShotNonce.update { it + 1 }
        _clarificationPrompt.value = if (state == S.Clarification) "When should I remind you?" else null
        transition(state)
    }

    fun debugRunSequence(states: List<S>, dwellMs: Long) {
        cancelTransient()
        transientJob = viewModelScope.launch {
            for (s in states) {
                if (!isActive) return@launch
                if (s == S.Success || s == S.CaptureSuccess) _oneShotNonce.update { it + 1 }
                _clarificationPrompt.value = if (s == S.Clarification) "When should I remind you?" else null
                transition(s)
                delay(dwellMs)
            }
        }
    }

    // ------------------------------------------------------------------ Core

    private fun transition(to: S) {
        val from = _orbState.value
        if (from == to) return
        Log.d(TAG, "$from -> $to")
        _orbState.value = to
    }

    private fun runTransient(expectFrom: S, afterMs: Long, next: () -> S) {
        transientJob?.cancel()
        transientJob = viewModelScope.launch {
            delay(afterMs)
            if (isActive && _orbState.value == expectFrom) transition(next())
        }
    }

    private fun cancelTransient() {
        transientJob?.cancel()
        transientJob = null
    }

    override fun onCleared() {
        cancelTransient()
        super.onCleared()
    }

    private companion object {
        const val TAG = "VirlinAgent"
    }
}

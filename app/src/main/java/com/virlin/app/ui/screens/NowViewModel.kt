package com.virlin.app.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Stream intents from the Now screen and the Stream Detail screen. This ViewModel owns NO
 * domain rules: it forwards user intent to the unified [VirlinActions] and logs the
 * structured result. Screens observe the outcome through the domain repository → display
 * projection; they never mutate anything themselves.
 *
 * Proof slice: every state-changing control on Now (Focus, Leave & Remember, Done) and on
 * Stream Detail (Done, Focus, give it N more minutes, +2m, Blocked) goes through here.
 */
class NowViewModel(
    private val actions: VirlinActions = VirlinGraph.actions,
    private val clock: VirlinClock = VirlinGraph.clock,
    private val repository: WorkStreamRepository = VirlinGraph.repository
) : ViewModel(), AttentionIntents {

    /**
     * Hierarchy shown on the Current Focus card, derived from the domain on every change.
     * `WorkStream.activeTaskId` is the only current-task truth; nothing is cached here.
     */
    val currentFocus: StateFlow<CurrentFocusProjection?> = combine(
        repository.projects, repository.streams, repository.tasks
    ) { p, s, t -> NowPresentation.currentFocus(p, s, t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            NowPresentation.currentFocus(repository.projects.value, repository.streams.value, repository.tasks.value))

    /** Attention kinds for the Needs You cards (domain CHECK + snooze reason). */
    val attention: StateFlow<Map<String, AttentionKind>> = repository.streams
        .map { NowPresentation.attention(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPresentation.attention(repository.streams.value))

    /** Projects (identity source for Needs You cards): the same repository flow, no copy of icon data anywhere else. */
    val projects: StateFlow<List<com.virlin.app.domain.model.Project>> = repository.projects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.projects.value)

    /** When each Needs You stream started waiting (domain timestamp) — drives the live timer and ordering. */
    val waitingSince: StateFlow<Map<String, java.time.Instant>> = repository.streams
        .map { NowPresentation.waitingSince(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPresentation.waitingSince(repository.streams.value))

    /** Needs You display order (ids): explicit rank first, then longest waiting — `NeedsYouOrder`, never computed in UI. */
    val needsYouOrder: StateFlow<List<String>> = repository.streams
        .map { s -> com.virlin.app.domain.attention.NeedsYouOrder.order(s).map { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.virlin.app.domain.attention.NeedsYouOrder.order(repository.streams.value).map { it.id })

    /** The single lightweight chooser open on Now, if any. Never more than one at a time. */
    sealed interface Chooser {
        val streamId: String
        data class Leave(override val streamId: String) : Chooser
        data class HandOff(override val streamId: String) : Chooser
        data class CheckOutcome(override val streamId: String) : Chooser
        data class StillRunning(override val streamId: String) : Chooser
        data class ResultReady(override val streamId: String) : Chooser
        /** Push a pending/due return later (human return or result-ready), reason unchanged. */
        data class Defer(override val streamId: String) : Chooser
        /** Custom minutes for the given intent. */
        data class Custom(override val streamId: String, val intent: TimedIntent) : Chooser
    }
    enum class TimedIntent { LEAVE, HAND_OFF, STILL_RUNNING, RESULT_READY_LATER, DEFER_RETURN }

    /** Shared intent implementation (also used by the Agent's CONTROL mode). */
    private val intents = AttentionIntentController(actions, clock, repository, viewModelScope)
    override val chooser: StateFlow<Chooser?> get() = intents.chooser
    val feedback: StateFlow<String?> get() = intents.feedback
    val pendingWorkStreamCompletion: StateFlow<String?> get() = intents.pendingWorkStreamCompletion

    override fun openLeave(streamId: String) = intents.openLeave(streamId)
    override fun openHandOff(streamId: String) = intents.openHandOff(streamId)
    override fun openCheck(streamId: String) = intents.openCheck(streamId)
    override fun openStillRunning(streamId: String) = intents.openStillRunning(streamId)
    override fun openResultReady(streamId: String) = intents.openResultReady(streamId)
    override fun openDefer(streamId: String) = intents.openDefer(streamId)
    override fun openCustom(streamId: String, intent: TimedIntent) = intents.openCustom(streamId, intent)
    override fun dismissChooser() = intents.dismissChooser()
    override fun leave(streamId: String, minutes: Long?) = intents.leave(streamId, minutes)
    override fun handOff(streamId: String, minutes: Long?) = intents.handOff(streamId, minutes)
    override fun stillRunning(streamId: String, minutes: Long) = intents.stillRunning(streamId, minutes)
    override fun resultReadyNow(streamId: String) = intents.resultReadyNow(streamId)
    override fun resultReadyLater(streamId: String, minutes: Long) = intents.resultReadyLater(streamId, minutes)
    override fun deferReturn(streamId: String, minutes: Long) = intents.deferReturn(streamId, minutes)
    override fun block(streamId: String) = intents.block(streamId)
    override fun customMinutes(streamId: String, intent: TimedIntent, minutes: Long) = intents.customMinutes(streamId, intent, minutes)

    fun focus(streamId: String) = intents.focus(streamId)
    /** Move a Needs You item to a 1-based queue position; the others shift automatically (Phase 1 rule). */
    fun reorderNeedsYou(streamId: String, position: Int) = intents.reorderNeedsYou(streamId, position)
    /** A planned check/return time arrived (the in-app ticker's scheduling stand-in). */
    fun checkDue(streamId: String) = intents.checkDue(streamId)
    /** "Still running — give it N more minutes." From CHECK or PROCESSING. */
    fun continueProcessing(streamId: String, minutes: Long) = intents.continueProcessing(streamId, minutes)
    /** COMPLETE: task-first; whole-stream completion only after explicit confirmation. */
    fun complete(streamId: String) = intents.complete(streamId)
    fun confirmCompleteWorkStream() = intents.confirmCompleteWorkStream()
    fun dismissWorkStreamCompletion() = intents.dismissWorkStreamCompletion()
    /** Stream Detail's "Done" — explicit whole-stream completion from that screen. */
    fun completeStream(streamId: String) = intents.completeStream(streamId)

    private companion object { const val TAG = "NowActions" }
}

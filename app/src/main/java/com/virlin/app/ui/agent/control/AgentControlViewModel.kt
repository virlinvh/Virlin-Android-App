package com.virlin.app.ui.agent.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.command.TargetRef
import com.virlin.app.domain.command.VirlinCommand
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import com.virlin.app.ui.agent.control.AgentControlPresentation.Selection
import com.virlin.app.ui.hierarchy.HierarchyPresentation
import com.virlin.app.ui.screens.AttentionIntentController
import com.virlin.app.ui.screens.AttentionIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Agent CONTROL mode: a deterministic, structured alternate interface to the existing
 * domain. Observes the repository, projects [AgentControlState], and forwards every control
 * to the SAME `VirlinActions` (through the shared [AttentionIntentController]) that Now,
 * Needs You and the notification actions use. Holds only ephemeral selection/dialog state —
 * no durable truth, no second activeTaskId, no Room, no DAO, no scheduler, no notifications.
 *
 * Quick Actions use [selectedQuickAction] + [Selection.selectedTargetId]: selection alone
 * never mutates domain state. When both sides of a valid pair are present, execution goes
 * through [act] / [AttentionIntentController] (or typed clarification when no target exists
 * and the user re-taps an already-selected action that needs one).
 */
class AgentControlViewModel(
    private val actions: VirlinActions = VirlinGraph.actions,
    private val clock: VirlinClock = VirlinGraph.clock,
    private val repository: WorkStreamRepository = VirlinGraph.repository
) : ViewModel() {

    /** Shared chooser + attention intents — identical semantics to Now. */
    val intents = AttentionIntentController(actions, clock, repository, viewModelScope, tag = "AgentControl")
    val attention: AttentionIntents get() = intents

    private val _selection = MutableStateFlow(Selection())
    /** Ephemeral picker/dialog selection (UI only). */
    val selection: StateFlow<Selection> = _selection

    val state: StateFlow<AgentControlState> = combine(
        repository.projects, repository.streams, repository.tasks, _selection
    ) { p, s, t, ui -> AgentControlPresentation.build(p, s, t, clock.now(), ui) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            AgentControlPresentation.build(repository.projects.value, repository.streams.value, repository.tasks.value, clock.now(), Selection()))

    /** Re-projects with the current clock (details such as "check in 2m" are time-derived). */
    fun refresh() { _selection.update { it.copy() } }

    /** Snapshot projection for intent routing — does not require a StateFlow subscriber. */
    private fun projection(ui: Selection = _selection.value): AgentControlState =
        AgentControlPresentation.build(repository.projects.value, repository.streams.value, repository.tasks.value, clock.now(), ui)

    // ------------------------------------------------------------------ structured stream controls

    fun act(streamId: String, action: ControlAction) {
        when (action) {
            ControlAction.LEAVE -> intents.openLeave(streamId)
            ControlAction.HAND_OFF -> intents.openHandOff(streamId)
            ControlAction.COMPLETE -> intents.complete(streamId)
            ControlAction.FOCUS, ControlAction.RESUME -> intents.focus(streamId)
            ControlAction.FOCUS_NOW -> intents.resultReadyNow(streamId)
            ControlAction.DEFER -> intents.openDefer(streamId)
            ControlAction.CHECK -> intents.openCheck(streamId)
            ControlAction.TASKS -> openTasks(streamId)
        }
    }

    /** Select / deselect a Recent / Suggested WorkStream as the Control target. Selection ≠ execution. */
    fun selectTarget(streamId: String) {
        val ui = _selection.value
        val nextId = if (ui.selectedTargetId == streamId) null else streamId
        val action = ui.selectedQuickAction
        val item = nextId?.let { id -> projection(ui).suggested.firstOrNull { it.streamId == id } }
        _selection.update { it.copy(selectedTargetId = nextId) }
        if (action != null && item != null && ControlQuickRegistry.eligibleFor(action, item)) {
            executePair(action, item.streamId)
        }
    }

    /**
     * Select a Quick Action. Selection alone does not mutate domain.
     * - If a valid target is already selected → execute via existing intents.
     * - If the same action is re-tapped with no target → typed clarification (existing contract).
     * - If the action is disabled for the current target → keep selection, do not execute.
     */
    fun selectQuick(action: QuickAction, onCommand: (VirlinCommand) -> Unit) {
        val ui = _selection.value
        val st = projection(ui)
        val target = st.selectedTarget
        if (target != null && !ControlQuickRegistry.eligibleFor(action, target)) {
            _selection.update { it.copy(selectedQuickAction = action) }
            return
        }
        if (ui.selectedQuickAction == action && target == null) {
            requestClarification(action, onCommand)
            return
        }
        if (target != null && ControlQuickRegistry.eligibleFor(action, target)) {
            _selection.update { it.copy(selectedQuickAction = action) }
            executePair(action, target.streamId)
            return
        }
        _selection.update { it.copy(selectedQuickAction = action) }
    }

    /** Whether [action] is enabled given the current selected target (or always browsable when none). */
    fun isQuickEnabled(action: QuickAction): Boolean {
        val target = projection().selectedTarget ?: return true
        return ControlQuickRegistry.eligibleFor(action, target)
    }

    private fun executePair(action: QuickAction, streamId: String) {
        when (action) {
            QuickAction.BLOCK -> intents.block(streamId)
            else -> action.controlAction?.let { act(streamId, it) }
        }
        // Clear action selection after kicking off execution / chooser; keep target so chips stay useful.
        _selection.update { it.copy(selectedQuickAction = null) }
    }

    private fun requestClarification(action: QuickAction, onCommand: (VirlinCommand) -> Unit) {
        // Only actions that already had a typed clarification path without a target.
        // CHECK / FOCUS_NOW / DEFER / TASKS need a concrete stream — keep selection, do not invent times.
        when (action) {
            QuickAction.FOCUS, QuickAction.RESUME -> onCommand(VirlinCommand.Control.FocusStream(TargetRef.ThisStream))
            QuickAction.LEAVE -> onCommand(VirlinCommand.Control.LeaveStream(TargetRef.CurrentStream))
            QuickAction.HAND_OFF -> onCommand(VirlinCommand.Control.HandOffStream(TargetRef.CurrentStream))
            QuickAction.COMPLETE -> onCommand(VirlinCommand.Control.Complete(TargetRef.ThisStream))
            QuickAction.BLOCK -> onCommand(VirlinCommand.Control.BlockStream(TargetRef.CurrentStream))
            QuickAction.CHECK, QuickAction.FOCUS_NOW, QuickAction.DEFER, QuickAction.TASKS -> Unit
        }
    }

    // ------------------------------------------------------------------ task picker (recursive, any depth)

    fun openTasks(streamId: String) {
        val stream = repository.streams.value.firstOrNull { it.id == streamId } ?: return
        _selection.update {
            it.copy(selectedStreamId = streamId, selectedTaskId = null, nextCandidate = null, pendingCancelTaskId = null,
                expanded = HierarchyPresentation.ancestorIds(repository.tasks.value, stream.activeTaskId),
                selectedQuickAction = null)
        }
    }
    fun closeTasks() {
        _selection.update {
            it.copy(selectedStreamId = null, selectedTaskId = null, nextCandidate = null, pendingCancelTaskId = null, expanded = emptySet())
        }
    }
    fun toggleExpanded(taskId: String) { _selection.update { it.copy(expanded = if (taskId in it.expanded) it.expanded - taskId else it.expanded + taskId) } }
    fun selectTask(taskId: String?) { _selection.update { it.copy(selectedTaskId = taskId) } }

    fun setCurrent(taskId: String) {
        val streamId = _selection.value.selectedStreamId ?: return
        intents.setActiveTask(streamId, taskId)
    }

    /** Completes ONLY this task; afterwards offers (never selects) the next candidate. */
    fun completeTask(taskId: String) {
        val streamId = _selection.value.selectedStreamId
        viewModelScope.launch {
            val before = repository.getTask(taskId)?.status
            intents.completeTaskNow(taskId)
            val done = repository.getTask(taskId)?.status == com.virlin.app.domain.model.TaskStatus.DONE && before != com.virlin.app.domain.model.TaskStatus.DONE
            val next = if (done) streamId?.let { (actions.nextTaskCandidate(it) as? ActionResult.Success)?.value } else null
            _selection.update { it.copy(nextCandidate = next, selectedTaskId = null) }
        }
    }
    fun requestCancel(taskId: String) { _selection.update { it.copy(pendingCancelTaskId = taskId) } }
    fun dismissCancel() { _selection.update { it.copy(pendingCancelTaskId = null) } }
    fun confirmCancel() {
        val id = _selection.value.pendingCancelTaskId ?: return
        _selection.update { it.copy(pendingCancelTaskId = null, selectedTaskId = null) }
        intents.cancelTask(id)
    }
    fun dismissNextCandidate() { _selection.update { it.copy(nextCandidate = null) } }
}

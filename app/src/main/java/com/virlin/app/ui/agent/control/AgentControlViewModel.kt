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

/** The four Quick Actions of the Control workspace. Each maps to an existing control path only. */
enum class QuickAction(val label: String) { FOCUS("Focus"), LEAVE("Leave"), HAND_OFF("Hand Off"), BLOCK("Block") }

/**
 * Agent CONTROL mode: a deterministic, structured alternate interface to the existing
 * domain. Observes the repository, projects [AgentControlState], and forwards every control
 * to the SAME `VirlinActions` (through the shared [AttentionIntentController]) that Now,
 * Needs You and the notification actions use. Holds only ephemeral selection/dialog state —
 * no durable truth, no second activeTaskId, no Room, no DAO, no scheduler, no notifications.
 * No natural language is interpreted here; controls are chips and choosers.
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

    /** Recent / Suggested row tap: reveal / hide that item's structured controls. */
    fun toggleItem(streamId: String) { _selection.update { it.copy(expandedItemId = if (it.expandedItemId == streamId) null else streamId) } }

    /**
     * Quick Actions (Stitch Control UI). Deterministic target rule: the expanded row, else the
     * current FOCUS. With a target every action is the SAME structured control as the row chips
     * (shared chooser semantics); without one the typed command contract asks the existing
     * clarification ("Which WorkStream do you mean?" / "Nothing is in Focus right now.").
     */
    fun quick(action: QuickAction, onCommand: (VirlinCommand) -> Unit) {
        val st = state.value
        val expanded = st.expandedItemId?.let { id -> st.suggested.firstOrNull { it.streamId == id } }
        when (action) {
            QuickAction.FOCUS -> if (expanded != null) intents.focus(expanded.streamId)
                else onCommand(VirlinCommand.Control.FocusStream(TargetRef.ThisStream))
            QuickAction.LEAVE -> (expanded ?: st.currentFocus)?.let { intents.openLeave(it.streamId) }
                ?: onCommand(VirlinCommand.Control.LeaveStream(TargetRef.CurrentStream))
            QuickAction.HAND_OFF -> (expanded ?: st.currentFocus)?.let { intents.openHandOff(it.streamId) }
                ?: onCommand(VirlinCommand.Control.HandOffStream(TargetRef.CurrentStream))
            QuickAction.BLOCK -> (expanded ?: st.currentFocus)?.let { intents.block(it.streamId) }
                ?: onCommand(VirlinCommand.Control.BlockStream(TargetRef.CurrentStream))
        }
    }

    // ------------------------------------------------------------------ task picker (recursive, any depth)

    fun openTasks(streamId: String) {
        val stream = repository.streams.value.firstOrNull { it.id == streamId } ?: return
        _selection.update {
            it.copy(selectedStreamId = streamId, selectedTaskId = null, nextCandidate = null, pendingCancelTaskId = null,
                expanded = HierarchyPresentation.ancestorIds(repository.tasks.value, stream.activeTaskId))
        }
    }
    fun closeTasks() { _selection.update { Selection() } }
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

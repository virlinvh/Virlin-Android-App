package com.virlin.app.ui.agent.command

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.command.Clarification
import com.virlin.app.domain.command.CommandContext
import com.virlin.app.domain.command.CommandEngine
import com.virlin.app.domain.command.CommandResolution
import com.virlin.app.domain.command.CommandResult
import com.virlin.app.domain.command.NavigationTarget
import com.virlin.app.domain.command.Confirmation
import com.virlin.app.domain.command.QueryResult
import com.virlin.app.domain.command.ResolvedCommand
import com.virlin.app.domain.command.VirlinCommand
import com.virlin.app.domain.command.text.TextCommandInterpreter
import com.virlin.app.domain.command.text.TextInterpretation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The one ephemeral interaction the Agent composer drives in CONTROL / CREATE modes (Pass 12-13):
 * text -> deterministic `TextCommandInterpreter` -> `VirlinGraph.commands` (resolve) -> a pending
 * question, preview or result. There is no second interpreter: `Unsupported` is shown as such. Nothing here is persisted (typed text,
 * clarifications, confirmations, previews and in-flight requests may vanish on process death;
 * executed changes live in Room). The ViewModel never calls actions, DAOs, the scheduler,
 * notifications, a model or the network - only the interpreter and the command engine.
 */
sealed interface CommandPanelState {
    data object Idle : CommandPanelState
    data class Clarify(val clarification: Clarification) : CommandPanelState
    data class Confirm(val confirmation: Confirmation) : CommandPanelState
    /** A command waiting for explicit CREATE / CONFIRM (preview of what was understood). */
    data class Preview(val command: ResolvedCommand) : CommandPanelState
    data class Answer(val result: QueryResult) : CommandPanelState
    data class Feedback(val text: String, val isError: Boolean = false, val examples: List<String> = emptyList()) : CommandPanelState
}

class AgentCommandViewModel(
    private val engine: CommandEngine = VirlinGraph.commands,
    private val interpret: (String) -> TextInterpretation = { t -> VirlinGraph.interpreter.interpret(t) }
) : ViewModel() {

    private val _state = MutableStateFlow<CommandPanelState>(CommandPanelState.Idle)
    val state: StateFlow<CommandPanelState> = _state

    /** A resolved "open X": the host navigates to the detail route, then calls [navigated]. Nothing was mutated. */
    private val _navigation = MutableStateFlow<NavigationTarget?>(null)
    val navigation: StateFlow<NavigationTarget?> = _navigation
    fun navigated() { _navigation.value = null }

    /** "this" for the next command - the stream the Agent UI has explicitly selected, if any. */
    var context: CommandContext = CommandContext.None

    /** Diagnostics for tests: how many interpretations completed. */
    var interpretations: Int = 0; private set

    /**
     * Interpret deterministically and resolve. [onRecognised] runs only when the text became a
     * command (the caller clears the composer). Interpretation is pure and synchronous - no
     * provider, no network, no loading state.
     */
    fun submit(text: String, onRecognised: () -> Unit = {}) {
        val i = interpret(text)
        interpretations++
        when (i) {
            is TextInterpretation.Parsed -> { onRecognised(); run(i.command) }
            is TextInterpretation.NeedsTime -> { onRecognised(); _state.value = CommandPanelState.Clarify(i.clarification) }
            is TextInterpretation.Invalid -> _state.value = CommandPanelState.Feedback(i.reason + (i.hint?.let { " · e.g. $it" } ?: ""), isError = true)
            is TextInterpretation.Unsupported -> _state.value = CommandPanelState.Feedback(i.reason, isError = true, examples = TextCommandInterpreter.Examples.take(4))
        }
    }

    /** Submit an already-typed command (structured hooks / tests). */
    fun run(command: VirlinCommand) {
        when (val r = engine.resolve(command, context)) {
            is CommandResolution.NeedsClarification -> _state.value = CommandPanelState.Clarify(r.clarification)
            is CommandResolution.NeedsConfirmation -> _state.value = CommandPanelState.Confirm(r.confirmation)
            is CommandResolution.Rejected -> _state.value = CommandPanelState.Feedback(r.reason, isError = true)
            is CommandResolution.Ready ->
                // Structure creation is previewed first; attention controls, captures and queries run at once.
                if (r.command is ResolvedCommand.Create) _state.value = CommandPanelState.Preview(r.command)
                else execute(r.command)
        }
    }

    fun choose(clarification: Clarification, value: String) {
        if (clarification.candidates.none { it.value == value }) return
        val next = clarification.choose(value) ?: return
        run(next)
    }

    fun confirm(confirmation: Confirmation) = execute(confirmation.command)
    fun accept(preview: ResolvedCommand) = execute(preview)
    fun dismiss() { _state.value = CommandPanelState.Idle }

    private fun execute(command: ResolvedCommand) {
        viewModelScope.launch {
            _state.value = when (val res = engine.execute(command)) {
                is CommandResult.Executed -> CommandPanelState.Feedback(res.summary)
                is CommandResult.Answered -> CommandPanelState.Answer(res.result)
                is CommandResult.Navigate -> { _navigation.value = res.destination; CommandPanelState.Idle }
                is CommandResult.Rejected -> CommandPanelState.Feedback(res.reason, isError = true)
                is CommandResult.Failed -> CommandPanelState.Feedback(res.reason, isError = true)
            }
        }
    }
}

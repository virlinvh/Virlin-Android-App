package com.virlin.app.domain.command

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CaptureTaskTarget
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.CreateProject
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.CreateWorkStream
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.command.time.TemporalIntent
import com.virlin.app.domain.command.time.TimeFormatter
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.effectiveAttentionState
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * [ResolvedCommand] → existing capability. Explicit typed mapping only (no reflection, no
 * "tool name → function"). Mutations go through [VirlinActions] — the same calls Now, Control,
 * Create and Capture make — so domain rules, the single-Focus invariant, events, and (through
 * the scheduling decorator) alarms and notifications all follow naturally. The executor never
 * touches a DAO, Room, AlarmManager or a notification API, and never re-implements a transition.
 * Queries are pure reads of the repository and create no events.
 *
 * Resolution is a snapshot; execution re-validates through the domain. A target that changed in
 * between is rejected by the action, never mutated twice.
 */
class CommandExecutor(
    private val actions: VirlinActions,
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
    zone: ZoneId = ZoneId.systemDefault()
) {
    private val time = TimeFormatter(zone)

    suspend fun execute(c: ResolvedCommand): CommandResult = when (c) {
        is ResolvedCommand.Query -> CommandResult.Answered(query(c))
        is ResolvedCommand.FocusStream -> focus(c)
        is ResolvedCommand.LeaveStream -> leave(c)
        is ResolvedCommand.HandOffStream -> handOff(c)
        is ResolvedCommand.MarkReady -> map(actions.markReady(c.streamId), c) { "${it.title} result ready · waiting for you" }
        is ResolvedCommand.SnoozeStream -> withTime(c.at) { at -> map(actions.snoozeStream(c.streamId, at!!), c) { "${it.title} · back ${label(c.at)}" } }
        is ResolvedCommand.DeferReturn -> withTime(c.at) { at -> map(actions.deferReturn(c.streamId, at!!), c) { "${it.title} · back ${label(c.at)}" } }
        is ResolvedCommand.Open -> CommandResult.Navigate(c.destination, "Opening ${c.preview.fields.firstOrNull()?.value ?: ""}".trim())
        is ResolvedCommand.ResumeStream -> map(actions.focusStream(c.streamId), c) { "Resumed ${it.focused.title}" }
        is ResolvedCommand.LeaveCurrent -> withTime(c.returnAt) { at -> map(actions.leaveFocus(c.streamId, at), c) { "Left ${it.title}" + (c.returnAt?.let { t -> " · back ${label(t)}" } ?: "") } }
        is ResolvedCommand.HandOffCurrent -> withTime(c.checkAt) { at -> map(actions.handOffStream(c.streamId, checkAt = at), c) { "Handed off ${it.title}" + (c.checkAt?.let { t -> " · check ${label(t)}" } ?: "") } }
        is ResolvedCommand.StillRunning -> withTime(c.checkAt) { at -> map(actions.stillRunning(c.streamId, at!!), c) { "${it.title} still running · check ${label(c.checkAt)}" } }
        is ResolvedCommand.ResultReadyNow -> {
            // "X is ready, focus now" while still PROCESSING: the user IS the check — mark it due first (the same hook the alarm uses), then the accepted result-ready path.
            if (repository.getStream(c.streamId)?.state == WorkStreamState.PROCESSING) actions.checkDue(c.streamId)
            map(actions.resultReadyNow(c.streamId), c) { "Focused ${it.focused.title}" }
        }
        is ResolvedCommand.ResultReadyLater -> withTime(c.returnAt) { at -> map(actions.resultReadyLater(c.streamId, at!!), c) { "${it.title} · remind ${label(c.returnAt)}" } }
        is ResolvedCommand.BlockStream -> map(actions.blockStream(c.streamId, c.reason), c) { "${it.title} blocked" }
        is ResolvedCommand.SetCurrentTask -> map(actions.setActiveTask(c.streamId, c.taskId), c) { "Current task set for ${it.title}" }
        is ResolvedCommand.CompleteTask -> map(actions.completeTask(c.taskId), c) { "${it.title} completed" }
        is ResolvedCommand.CancelTask -> map(actions.cancelTask(c.taskId), c) { "${it.title} cancelled" }
        is ResolvedCommand.CompleteStream -> map(actions.completeStream(c.streamId), c) { "${it.title} completed" }

        is ResolvedCommand.CreateProject -> map(actions.createProject(CreateProject(title = c.title)), c) { "Created project · ${it.title}" }
        is ResolvedCommand.CreateWorkStream -> map(actions.createWorkStream(CreateWorkStream(title = c.title, projectId = c.projectId, mode = c.mode)), c) { "Created WorkStream · ${it.title}" }
        is ResolvedCommand.CreateTask -> map(actions.createTask(CreateTask(title = c.title, workStreamId = c.workStreamId, projectId = c.projectId, parentTaskId = c.parentTaskId, estimatedEffort = c.estimate)), c) { "Created task · ${it.title}" }

        is ResolvedCommand.CreateCapture -> map(actions.createCapture(CreateCapture(type = c.type, content = c.content, sourceUrl = c.url, context = CaptureContext(c.projectId, c.workStreamId, c.taskId))), c) {
            when (it.type) { CaptureType.NOTE -> "Saved to Inbox"; CaptureType.PROMPT -> "Saved prompt"; CaptureType.LINK -> "Saved link" } }
        is ResolvedCommand.ArchiveCapture -> map(actions.archiveCapture(c.captureId), c) { "Archived" }
        is ResolvedCommand.AttachCapture -> map(actions.attachCapture(c.captureId, CaptureContext(c.projectId, c.workStreamId, c.taskId)), c) { if (it.hasContext) "Attached" else "Context cleared" }
        is ResolvedCommand.ConvertCaptureToTask -> map(actions.convertCaptureToTask(c.captureId, CaptureTaskTarget(workStreamId = c.workStreamId, projectId = c.projectId, parentTaskId = c.parentTaskId)), c) { "Task created · ${it.title}" }
    }

    /** Focus the (owning) WorkStream; when the user named a Task it then becomes the current task — the same two accepted actions the UI uses. */
    private suspend fun focus(c: ResolvedCommand.FocusStream): CommandResult {
        val focused = actions.focusStream(c.streamId)
        if (focused !is ActionResult.Success) return map(focused, c) { "" }
        val title = focused.value.focused.title
        val displaced = focused.value.displaced?.let { d -> " · ${d.title} set aside" } ?: ""
        val taskId = c.activeTaskId ?: return CommandResult.Executed("Focused $title$displaced", c.preview)
        return map(actions.setActiveTask(c.streamId, taskId), c) { "Focused $title · now on ${c.preview.fields.lastOrNull()?.value ?: "task"}$displaced" }
    }
    /** Leave a named FOCUS stream (optional HUMAN return); a named Task is set as the position being left first, so the snapshot keeps it. */
    private suspend fun leave(c: ResolvedCommand.LeaveStream): CommandResult {
        position(c.streamId, c.activeTaskId, c)?.let { return it }
        return withTime(c.returnAt) { at -> map(actions.leaveFocus(c.streamId, at), c) { "Left ${it.title}" + (c.returnAt?.let { t -> " · back ${label(t)}" } ?: "") } }
    }
    /** Hand off a named external FOCUS stream (optional check) — the same action the Now HAND OFF button uses. */
    private suspend fun handOff(c: ResolvedCommand.HandOffStream): CommandResult {
        position(c.streamId, c.activeTaskId, c)?.let { return it }
        return withTime(c.checkAt) { at -> map(actions.handOffStream(c.streamId, checkAt = at), c) { "Handed off ${it.title}" + (c.checkAt?.let { t -> " · check ${label(t)}" } ?: "") } }
    }
    /** When a Task located the stream, make it the current task first (no-op when it already is). Null = fine. */
    private suspend fun position(streamId: String, taskId: String?, c: ResolvedCommand): CommandResult? {
        taskId ?: return null
        if (repository.getStream(streamId)?.activeTaskId == taskId) return null
        val set = actions.setActiveTask(streamId, taskId)
        return if (set is ActionResult.Success) null else map(set, c) { "" }
    }

    /**
     * Relative durations are evaluated against the clock NOW (execution), not when the text was
     * typed; absolute targets resolved earlier are re-validated — a target that slipped into the
     * past while a preview stayed open is rejected, never scheduled.
     */
    private suspend fun withTime(t: TemporalIntent?, block: suspend (Instant?) -> CommandResult): CommandResult {
        val now = clock.now()
        val at: Instant? = when (t) {
            null -> null
            is TemporalIntent.Relative -> now.plus(t.duration)
            is TemporalIntent.Absolute -> if (t.at.isAfter(now)) t.at else return CommandResult.Rejected("${time.format(t.at, now)} has already passed — choose another time", stale = true)
        }
        return block(at)
    }
    private fun label(t: TemporalIntent) = when (t) { is TemporalIntent.Relative -> "in ${time.relative(t.duration)}"; is TemporalIntent.Absolute -> "at ${time.format(t.at, clock.now())}" }

    // ------------------------------------------------------------------ ActionResult → CommandResult

    private fun <T> map(r: ActionResult<T>, c: ResolvedCommand, summary: (T) -> String): CommandResult = when (r) {
        is ActionResult.Success -> CommandResult.Executed(summary(r.value), c.preview)
        is ActionResult.Rejected -> CommandResult.Rejected(rejection(r.reason), stale = isStale(r.reason))
        is ActionResult.NotFound -> CommandResult.Rejected("That WorkStream no longer exists", stale = true)
        is ActionResult.Failure -> CommandResult.Failed("Something went wrong")
    }

    private fun isStale(e: DomainError) = when (e) {
        is DomainError.InvalidTransition, DomainError.NotInFocus, DomainError.NoActiveFocus, DomainError.TaskAlreadyClosed,
        DomainError.ProjectAlreadyDone, DomainError.CaptureAlreadyOrganized, is DomainError.TaskNotFound, is DomainError.ProjectNotFound, is DomainError.CaptureNotFound -> true
        else -> false
    }

    private fun rejection(e: DomainError): String = when (e) {
        is DomainError.InvalidTransition -> "Couldn't do that — the WorkStream has changed"
        DomainError.AlreadyFocused -> "That WorkStream is already in Focus"
        DomainError.NotInFocus -> "That WorkStream is not in Focus any more"
        DomainError.NoActiveFocus -> "Nothing is in Focus right now"
        DomainError.NotAnExternalCheck -> "That is not an external check"
        DomainError.NotAReturn -> "That is not a timed return"
        DomainError.TaskAlreadyClosed -> "That task is already closed"
        DomainError.EmptyTitle -> "Give it a name first"
        DomainError.EmptyCapture -> "Nothing to save yet"
        DomainError.InvalidLink -> "Enter a full link starting with http:// or https://"
        DomainError.OwnershipMismatch -> "Choose a WorkStream or Project"
        DomainError.CaptureAlreadyOrganized -> "Already turned into a task"
        is DomainError.TaskNotFound -> "That task no longer exists"
        is DomainError.ProjectNotFound -> "That project no longer exists"
        is DomainError.CaptureNotFound -> "That capture no longer exists"
        DomainError.TaskNotInWorkStream -> "That task belongs to another WorkStream"
        else -> "Couldn't do that"
    }

    // ------------------------------------------------------------------ read-only queries (no events, no mutation)

    private fun query(c: ResolvedCommand.Query): QueryResult {
        val projects = repository.projects.value; val streams = repository.streams.value; val tasks = repository.tasks.value
        fun projectOf(id: String?) = id?.let { pid -> projects.firstOrNull { it.id == pid } }
        val now = clock.now()
        return when (val q = c.query) {
            VirlinCommand.Query.GetCurrentFocus -> streams.firstOrNull { it.state == WorkStreamState.FOCUS }?.let { ws ->
                QueryResult.CurrentFocus(projectOf(ws.projectId), ws, ws.activeTaskId?.let { id -> tasks.firstOrNull { it.id == id } }) } ?: QueryResult.NoCurrentFocus
            VirlinCommand.Query.GetNeedsAttention -> QueryResult.NeedsAttention(
                streams.filter { effectiveAttentionState(it, now) == WorkStreamState.CHECK }.map { ws ->
                    QueryResult.NeedsAttention.Item(ws, when (ws.snoozeReason) {
                        SnoozeReason.HUMAN_RETURN -> QueryResult.AttentionKind.HUMAN_RETURN
                        SnoozeReason.EXTERNAL_RESULT_READY -> QueryResult.AttentionKind.EXTERNAL_RESULT_READY
                        null -> QueryResult.AttentionKind.EXTERNAL_CHECK
                    }, projectOf(ws.projectId)) })
            VirlinCommand.Query.GetProcessingStreams -> QueryResult.Processing(
                streams.filter { effectiveAttentionState(it, now) == WorkStreamState.PROCESSING }.map { QueryResult.Processing.Item(it, it.processingStartedAt, it.checkAt, projectOf(it.projectId)) })
            VirlinCommand.Query.GetReadyStreams -> QueryResult.Ready(streams.filter { it.state == WorkStreamState.READY })
            VirlinCommand.Query.GetProjects -> QueryResult.Projects(projects)
            VirlinCommand.Query.GetWorkStreams -> QueryResult.WorkStreams(streams.filter { !it.state.isTerminal })
            is VirlinCommand.Query.GetTasks -> {
                val ws = streams.first { it.id == c.streamId }
                QueryResult.Tasks(ws, tasks.filter { it.workStreamId == ws.id }, ws.activeTaskId)
            }
            VirlinCommand.Query.GetCaptureInbox -> QueryResult.CaptureInbox(repository.captures.value.filter { it.status == CaptureStatus.INBOX })
        }
    }
}

/**
 * Facade over resolver + executor: the one entry point future input layers call.
 * `submit` resolves (and executes immediately when nothing needs asking); `choose` answers a
 * clarification with a stable candidate value; `confirm` executes a confirmed command.
 * Pending clarifications/confirmations are values held by the caller — not persisted.
 */
class CommandEngine(private val resolver: CommandResolver, private val executor: CommandExecutor) {

    constructor(actions: VirlinActions, repository: WorkStreamRepository, clock: VirlinClock, zone: ZoneId = ZoneId.systemDefault()) :
        this(CommandResolver(repository, clock, zone), CommandExecutor(actions, repository, clock, zone))

    sealed interface Outcome {
        data class Done(val result: CommandResult) : Outcome
        data class Clarify(val clarification: Clarification) : Outcome
        data class Confirm(val confirmation: Confirmation) : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    fun resolve(command: VirlinCommand, context: CommandContext = CommandContext.None): CommandResolution = resolver.resolve(command, context)

    suspend fun submit(command: VirlinCommand, context: CommandContext = CommandContext.None): Outcome = when (val r = resolver.resolve(command, context)) {
        is CommandResolution.Ready -> Outcome.Done(executor.execute(r.command))
        is CommandResolution.NeedsClarification -> Outcome.Clarify(r.clarification)
        is CommandResolution.NeedsConfirmation -> Outcome.Confirm(r.confirmation)
        is CommandResolution.Rejected -> Outcome.Rejected(r.reason)
    }

    /** Answer a clarification with one of its candidate values; the command is re-resolved from scratch. */
    suspend fun choose(clarification: Clarification, value: String, context: CommandContext = CommandContext.None): Outcome {
        if (clarification.candidates.none { it.value == value }) return Outcome.Rejected("That is not one of the offered choices")
        val next = clarification.choose(value) ?: return Outcome.Rejected("This question has no choices")
        return submit(next, context)
    }

    suspend fun confirm(confirmation: Confirmation): CommandResult = executor.execute(confirmation.command)

    /** Execute an already-resolved command (after a preview was accepted). Same path as [confirm]. */
    suspend fun execute(command: ResolvedCommand): CommandResult = executor.execute(command)
}

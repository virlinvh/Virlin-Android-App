package com.virlin.app.domain.command

import com.virlin.app.domain.command.time.TemporalIntent
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import java.time.Duration
import java.time.Instant

/**
 * A command whose every reference is a stable id and whose required fields are present.
 * Only the [CommandResolver] creates these; only the [CommandExecutor] consumes them. Durations
 * stay typed until execution, when the executor turns them into instants with the domain clock.
 */
sealed interface ResolvedCommand {
    /** What Virlin understood — shown before executing when a layer needs a preview/confirmation. */
    val preview: CommandPreview
    /** High-impact commands ask first. Trivial ones execute immediately. */
    val requiresConfirmation: Boolean get() = false

    sealed interface Control : ResolvedCommand
    /** [activeTaskId]: when the user named a Task, it becomes the stream's current task after focusing. */
    data class FocusStream(val streamId: String, override val preview: CommandPreview, val activeTaskId: String? = null) : Control
    data class ResumeStream(val streamId: String, override val preview: CommandPreview) : Control
    data class LeaveCurrent(val streamId: String, val returnAt: TemporalIntent?, override val preview: CommandPreview) : Control
    /** Leave a named FOCUS stream; [activeTaskId] is the Task position being left (set first when it differs); [returnAt] = HUMAN_RETURN. */
    data class LeaveStream(val streamId: String, val activeTaskId: String?, override val preview: CommandPreview, val returnAt: TemporalIntent? = null) : Control
    /** Hand off a named external FOCUS stream; [activeTaskId] set first when a Task located it. */
    data class HandOffStream(val streamId: String, val activeTaskId: String?, val checkAt: TemporalIntent?, override val preview: CommandPreview) : Control
    /** "X is ready" without looking: PROCESSING/CHECK → READY, timers cleared, no Focus. */
    data class MarkReady(val streamId: String, override val preview: CommandPreview) : Control
    /** Human snooze from READY / PAUSED (HUMAN_RETURN). */
    data class SnoozeStream(val streamId: String, val at: TemporalIntent, override val preview: CommandPreview) : Control
    /** Move an existing timed return, keeping its reason. */
    data class DeferReturn(val streamId: String, val at: TemporalIntent, override val preview: CommandPreview) : Control
    data class HandOffCurrent(val streamId: String, val checkAt: TemporalIntent?, override val preview: CommandPreview) : Control
    data class StillRunning(val streamId: String, val checkAt: TemporalIntent, override val preview: CommandPreview) : Control
    data class ResultReadyNow(val streamId: String, override val preview: CommandPreview) : Control
    data class ResultReadyLater(val streamId: String, val returnAt: TemporalIntent, override val preview: CommandPreview) : Control
    data class BlockStream(val streamId: String, val reason: String?, override val preview: CommandPreview) : Control
    data class SetCurrentTask(val streamId: String, val taskId: String, override val preview: CommandPreview) : Control
    data class CompleteTask(val taskId: String, override val preview: CommandPreview) : Control
    data class CancelTask(val taskId: String, override val preview: CommandPreview) : Control { override val requiresConfirmation get() = true }
    data class CompleteStream(val streamId: String, override val preview: CommandPreview) : Control { override val requiresConfirmation get() = true }

    sealed interface Create : ResolvedCommand
    data class CreateProject(val title: String, override val preview: CommandPreview) : Create
    data class CreateWorkStream(val title: String, val projectId: String?, val mode: WorkStreamMode, override val preview: CommandPreview) : Create
    data class CreateTask(val title: String, val workStreamId: String?, val projectId: String?, val parentTaskId: String?, val estimate: Duration?, override val preview: CommandPreview) : Create

    sealed interface Capture : ResolvedCommand
    data class CreateCapture(val type: CaptureType, val content: String, val url: String?, val projectId: String?, val workStreamId: String?, val taskId: String?, override val preview: CommandPreview) : Capture
    data class ArchiveCapture(val captureId: String, override val preview: CommandPreview) : Capture
    data class AttachCapture(val captureId: String, val projectId: String?, val workStreamId: String?, val taskId: String?, override val preview: CommandPreview) : Capture
    data class ConvertCaptureToTask(val captureId: String, val workStreamId: String?, val projectId: String?, val parentTaskId: String?, override val preview: CommandPreview) : Capture

    /** Navigation only: open one entity's detail surface. Executing it changes no domain state. */
    data class Open(val destination: NavigationTarget, override val preview: CommandPreview) : ResolvedCommand

    /** Read-only. Carries the original query; ids inside were resolved. */
    data class Query(val query: VirlinCommand.Query, val streamId: String? = null, override val preview: CommandPreview) : ResolvedCommand
}

/** Presentation-neutral "what Virlin understood": a title and labelled fields. */
data class CommandPreview(val title: String, val fields: List<Field> = emptyList()) {
    data class Field(val label: String, val value: String)
}

/** A question the input layer must answer before the command can be resolved. */
data class Clarification(
    val question: String,
    val kind: Kind,
    /** Options to show; choosing one re-submits the command with that stable id / value. */
    val candidates: List<Candidate>,
    /** Re-builds the command from a chosen candidate value. Null when nothing can be chosen (e.g. not found). */
    internal val refill: ((String) -> VirlinCommand)? = null
) {
    enum class Kind {
        AMBIGUOUS_TARGET, TARGET_NOT_FOUND, NO_CURRENT_STREAM, NO_CURRENT_TASK, NO_SELECTED_STREAM, MISSING_MODE, MISSING_FIELD, INVALID_FIELD,
        // temporal (Pass 13)
        TIME_REQUIRED, AM_PM_REQUIRED, TIME_ALREADY_PASSED, DAYPART_ALREADY_PASSED, INVALID_LOCAL_TIME
    }
    /** [kind]: the entity type label ("Task", "WorkStream", "Project") when candidates can span kinds; [subtitle]: ancestry / context. */
    data class Candidate(val value: String, val title: String, val subtitle: String? = null, val kind: String? = null)
    val canChoose: Boolean get() = refill != null && candidates.isNotEmpty()
    /** The same command with the chosen candidate filled in (stable id or explicit value). */
    fun choose(value: String): VirlinCommand? = refill?.invoke(value)
}

data class Confirmation(val question: String, val command: ResolvedCommand)

/** Outcome of resolving a [VirlinCommand] against persisted state. Ambiguity is a value, never an exception. */
sealed interface CommandResolution {
    data class Ready(val command: ResolvedCommand) : CommandResolution
    data class NeedsClarification(val clarification: Clarification) : CommandResolution
    data class NeedsConfirmation(val confirmation: Confirmation) : CommandResolution
    data class Rejected(val reason: String) : CommandResolution
}

/** Outcome of executing a [ResolvedCommand]. `ActionResult`s are mapped here; exceptions never leak. */
sealed interface CommandResult {
    data class Executed(val summary: String, val preview: CommandPreview) : CommandResult
    /** The domain refused: the state is not what the command assumed (stale / already changed). */
    data class Rejected(val reason: String, val stale: Boolean = false) : CommandResult
    data class Answered(val result: QueryResult) : CommandResult
    /** Open a detail surface. The UI layer maps [destination] to its route; nothing was mutated. */
    data class Navigate(val destination: NavigationTarget, val summary: String) : CommandResult
    data class Failed(val reason: String) : CommandResult
}

/** Presentation-neutral "which detail surface": the UI owns the actual route. */
data class NavigationTarget(val kind: EntityKind, val id: String)

/** Transport/presentation-neutral answers to read-only queries. Nothing here is a Compose object. */
sealed interface QueryResult {
    data class CurrentFocus(val project: Project?, val workStream: WorkStream, val activeTask: Task?) : QueryResult
    data object NoCurrentFocus : QueryResult

    enum class AttentionKind { HUMAN_RETURN, EXTERNAL_CHECK, EXTERNAL_RESULT_READY }
    data class NeedsAttention(val items: List<Item>) : QueryResult { data class Item(val workStream: WorkStream, val kind: AttentionKind, val project: Project?) }

    data class Processing(val items: List<Item>) : QueryResult { data class Item(val workStream: WorkStream, val processingStartedAt: Instant?, val checkAt: Instant?, val project: Project?) }
    data class Ready(val workStreams: List<WorkStream>) : QueryResult
    data class Projects(val projects: List<Project>) : QueryResult
    data class WorkStreams(val workStreams: List<WorkStream>) : QueryResult
    data class Tasks(val workStream: WorkStream, val tasks: List<Task>, val activeTaskId: String?) : QueryResult
    data class CaptureInbox(val items: List<CaptureItem>) : QueryResult
}

package com.virlin.app.domain.command

import com.virlin.app.domain.command.time.TemporalIntent
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.WorkStreamMode
import java.time.Duration

/**
 * The deterministic command contract (Pass 11). Every future input layer — the deterministic text parser,
 * structured output, voice, quick actions, widgets — produces ONE of these typed commands and
 * nothing else. Commands name intents that already exist as product capabilities; they never
 * name DAOs, tables, or methods. Input layers are untrusted: the [CommandResolver] validates
 * type, target, required fields and current state before anything is executed.
 *
 * A command may carry UNRESOLVED references ([TargetRef]) — "Psychology", "current task". The
 * resolver turns them into stable ids ([ResolvedCommand]); the [CommandExecutor] accepts only
 * resolved commands.
 */
sealed interface VirlinCommand {

    // ================================================================ CONTROL (attention)
    sealed interface Control : VirlinCommand {
        /** Focus a stream (READY / CHECK / PAUSED …); displacement follows the single-Focus rule. */
        data class FocusStream(val target: TargetRef) : Control
        /** Resume a due human return — same domain action as Focus, named for intent. */
        data class ResumeStream(val target: TargetRef) : Control
        /**
         * LEAVE the current Focus; optional typed return time — [TemporalIntent.Relative] ("for 10
         * minutes", evaluated at execution) or [TemporalIntent.Absolute] ("until 5 PM"). Never PROCESSING.
         */
        data class LeaveCurrent(val returnAt: TemporalIntent? = null) : Control {
            constructor(returnAfter: Duration?) : this(returnAfter?.let { TemporalIntent.Relative(it) })
        }
        /**
         * LEAVE a NAMED WorkStream (Control 1/2): no reminder, or a typed HUMAN return time
         * ("for 10 minutes" / "until 4 PM"). A Task target leaves its owning WorkStream with that
         * Task as the position being left. The stream must be in FOCUS. NEVER PROCESSING.
         */
        data class LeaveStream(val target: TargetRef, val returnAt: TemporalIntent? = null) : Control
        /** HAND OFF a NAMED external WorkStream (Control 2): FOCUS → PROCESSING, optional typed check time. A Task locates its owner. */
        data class HandOffStream(val target: TargetRef, val checkAt: TemporalIntent? = null) : Control
        /**
         * "Check X in 5 minutes" (Control 2): schedule / move the external check of a named
         * external WorkStream. From FOCUS this is a hand-off with a check; while PROCESSING or on a
         * due check it moves the next check. Never a human reminder.
         */
        data class CheckStream(val target: TargetRef, val checkAt: TemporalIntent) : Control
        /** "X is ready" with no focus / remind modifier: the external result is in, nobody looks yet → READY. Never FOCUS. */
        data class ResultReady(val target: TargetRef) : Control
        /**
         * "Remind me about X in 20 minutes" / "Bring X back at 6 PM" (Control 2): a state-sensitive
         * human reminder. The resolver maps it to ONE existing semantic — leave-with-return from
         * FOCUS, snooze from READY/PAUSED, defer an existing return (keeps HUMAN_RETURN /
         * EXTERNAL_RESULT_READY), or the next external check while PROCESSING — or refuses.
         */
        data class RemindStream(val target: TargetRef, val at: TemporalIntent) : Control
        /** HAND OFF the current Focus to its external process; optional typed check time. */
        data class HandOffCurrent(val checkAt: TemporalIntent? = null) : Control {
            constructor(checkAfter: Duration?) : this(checkAfter?.let { TemporalIntent.Relative(it) })
        }
        /** Due external check: still running, look again at [checkAt]. Never focuses. */
        data class StillRunning(val target: TargetRef, val checkAt: TemporalIntent) : Control {
            constructor(target: TargetRef, checkAfter: Duration) : this(target, TemporalIntent.Relative(checkAfter))
        }
        data class ResultReadyNow(val target: TargetRef) : Control
        data class ResultReadyLater(val target: TargetRef, val returnAt: TemporalIntent) : Control {
            constructor(target: TargetRef, returnAfter: Duration) : this(target, TemporalIntent.Relative(returnAfter))
        }
        data class BlockStream(val target: TargetRef, val reason: String? = null) : Control
        data class SetCurrentTask(val stream: TargetRef, val task: TargetRef) : Control
        data class CompleteTask(val task: TargetRef) : Control
        data class CancelTask(val task: TargetRef) : Control
        /** Whole-WorkStream completion — confirmation-worthy. */
        data class CompleteStream(val target: TargetRef) : Control
        /**
         * "Complete X" with no entity word (Control 1): the resolver decides whether X is a Task
         * (→ complete task) or a WorkStream (→ confirmation-gated whole-stream completion); both
         * matching → typed clarification. Projects are never completion candidates.
         */
        data class Complete(val target: TargetRef) : Control
    }

    // ================================================================ NAVIGATE (inspection, no state change)
    sealed interface Navigate : VirlinCommand {
        /** Open / show one entity's detail surface. Never changes Focus. */
        data class Open(val target: TargetRef) : Navigate
    }

    // ================================================================ CREATE (structure)
    sealed interface Create : VirlinCommand {
        data class CreateProject(val title: String) : Create
        /**
         * [mode] is nullable on purpose: an input layer may not know it. It is REQUIRED and
         * never inferred — a null mode resolves to a clarification.
         */
        data class CreateWorkStream(val title: String, val project: TargetRef? = null, val mode: WorkStreamMode? = null) : Create
        data class CreateTask(val title: String, val owner: TaskOwnerRef, val estimate: Duration? = null) : Create
    }

    // ================================================================ CAPTURE (external memory)
    sealed interface Capture : VirlinCommand {
        data class CaptureNote(val content: String, val context: CaptureContextRef? = null) : Capture
        /** Stored verbatim. A prompt is DATA — it is never interpreted as a command. */
        data class CapturePrompt(val content: String, val context: CaptureContextRef? = null) : Capture
        data class CaptureLink(val url: String, val note: String? = null, val context: CaptureContextRef? = null) : Capture
        data class ArchiveCapture(val capture: TargetRef) : Capture
        data class AttachCapture(val capture: TargetRef, val context: CaptureContextRef?) : Capture
        data class ConvertCaptureToTask(val capture: TargetRef, val owner: TaskOwnerRef) : Capture
    }

    // ================================================================ QUERY (read-only)
    sealed interface Query : VirlinCommand {
        data object GetCurrentFocus : Query
        data object GetNeedsAttention : Query
        data object GetProcessingStreams : Query
        data object GetReadyStreams : Query
        data object GetProjects : Query
        data object GetWorkStreams : Query
        data class GetTasks(val stream: TargetRef) : Query
        data object GetCaptureInbox : Query
    }

    val family: String
        get() = when (this) { is Control -> "Control"; is Create -> "Create"; is Capture -> "Capture"; is Query -> "Query"; is Navigate -> "Navigate" }
}

/**
 * An UNRESOLVED reference to an entity. Which entity kind is expected comes from the field the
 * ref sits in (a `FocusStream.target` is a WorkStream, a `CompleteTask.task` is a Task), so a
 * Project and a WorkStream sharing a title can never be confused.
 */
sealed interface TargetRef {
    /** Stable id — resolves without lookup ambiguity (existence is still checked). */
    data class ById(val id: String) : TargetRef
    /** Title as the user said it. Exact normalized match, then unique case-insensitive match. */
    data class ByName(val name: String) : TargetRef
    /** The WorkStream currently in FOCUS. */
    data object CurrentStream : TargetRef
    /** The `activeTaskId` of the WorkStream currently in FOCUS. */
    data object CurrentTask : TargetRef
    /** "this" — the WorkStream the Agent UI has explicitly selected (passed in [CommandContext]). */
    data object ThisStream : TargetRef
    /**
     * A human-readable name whose entity KIND is not stated (Control 1: "Switch to Claude Build",
     * "Complete Testing"). The action decides which kinds are allowed; the resolver matches the
     * name across those kinds only and turns cross-kind or same-kind ambiguity into a clarification.
     */
    data class Named(val name: String) : TargetRef
    /** A chosen clarification candidate: stable id PLUS kind, so the choice resolves directly. */
    data class Entity(val kind: EntityKind, val id: String) : TargetRef
    /** "this task" for creation — the Task the Agent UI has selected ([CommandContext.selectedTaskId]), else the FOCUS stream's current task. */
    data object ThisTask : TargetRef
}

enum class EntityKind { PROJECT, WORKSTREAM, TASK;
    val label: String get() = when (this) { PROJECT -> "Project"; WORKSTREAM -> "WorkStream"; TASK -> "Task" }
}

/** Where a new Task lives. Carries the expected owner kind so titles are looked up in the right table. */
sealed interface TaskOwnerRef {
    data class WorkStream(val ref: TargetRef) : TaskOwnerRef
    data class Project(val ref: TargetRef) : TaskOwnerRef
    data class ParentTask(val ref: TargetRef) : TaskOwnerRef
    /**
     * "under X" with no entity word (Create V1): X may be a Project (standalone Project task), a
     * WorkStream (root task) or a Task (child task). The resolver matches across all three and
     * clarifies with typed candidates; a chosen candidate comes back as a [TargetRef.Entity].
     */
    data class Any(val ref: TargetRef) : TaskOwnerRef
}

/** Optional explicit capture context; null / all-null = global Inbox. Never inferred from content. */
data class CaptureContextRef(val project: TargetRef? = null, val workStream: TargetRef? = null, val task: TargetRef? = null) {
    val isEmpty: Boolean get() = project == null && workStream == null && task == null
}

/** What the input layer knows about the UI moment: an explicitly selected stream / task, if any. Never inferred. */
data class CommandContext(val selectedStreamId: String? = null, val selectedTaskId: String? = null) {
    companion object { val None = CommandContext() }
}

internal val CaptureType.commandLabel: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

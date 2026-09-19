package com.virlin.app.domain.command

import com.virlin.app.domain.command.CommandPreview.Field
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.EffectiveExecutionMode
import com.virlin.app.domain.model.ExecutionModeResolver
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.command.time.TemporalIntent
import com.virlin.app.domain.command.time.TimeFormatter
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import java.time.Duration
import java.time.ZoneId

/**
 * unresolved [VirlinCommand] + current persisted state → [CommandResolution].
 *
 * Deterministic and conservative: exact id, then exact normalized title, then a UNIQUE
 * case-insensitive normalized title, then a UNIQUE normalized prefix/word match. Anything
 * ambiguous becomes a [Clarification] with candidates — the resolver never silently picks one.
 * Missing required fields (WorkStream mode) become clarifications too. No fuzzy matching, no
 * inference from content, no lookups inside `VirlinActions`.
 */
class CommandResolver(
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
    zone: ZoneId
) {
    private val time = TimeFormatter(zone)

    private class Snapshot(val projects: List<Project>, val streams: List<WorkStream>, val tasks: List<Task>, val captures: List<CaptureItem>, val ctx: CommandContext)

    fun resolve(command: VirlinCommand, context: CommandContext = CommandContext.None): CommandResolution {
        val s = Snapshot(repository.projects.value, repository.streams.value, repository.tasks.value, repository.captures.value, context)
        val resolved = when (command) {
            is VirlinCommand.Control -> control(command, s)
            is VirlinCommand.Create -> create(command, s)
            is VirlinCommand.Capture -> capture(command, s)
            is VirlinCommand.Query -> query(command, s)
            is VirlinCommand.Navigate -> navigate(command, s)
        }
        return when (resolved) {
            is CommandResolution.Ready -> if (resolved.command.requiresConfirmation)
                CommandResolution.NeedsConfirmation(Confirmation(confirmQuestion(resolved.command), resolved.command)) else resolved
            else -> resolved
        }
    }

    private fun confirmQuestion(c: ResolvedCommand): String = when (c) {
        is ResolvedCommand.CompleteStream -> "Complete ${c.preview.fields.firstOrNull()?.value ?: "this WorkStream"}? The whole WorkStream is marked done."
        is ResolvedCommand.CancelTask -> "Cancel ${c.preview.fields.firstOrNull()?.value ?: "this task"}? It leaves the planned scope; it is not completed."
        else -> "Confirm: ${c.preview.title}?"
    }

    // ------------------------------------------------------------------ CONTROL

    private fun control(c: VirlinCommand.Control, s: Snapshot): CommandResolution = when (c) {
        is VirlinCommand.Control.FocusStream -> if (c.target.isNamed) attention(c.target, "Focus", s) { VirlinCommand.Control.FocusStream(it) }
            else stream(c.target, s, { VirlinCommand.Control.FocusStream(TargetRef.ById(it)) }) { ws -> ResolvedCommand.FocusStream(ws.id, preview("Focus", ws, s)) }
        is VirlinCommand.Control.ResumeStream -> if (c.target.isNamed) attention(c.target, "Resume", s) { VirlinCommand.Control.ResumeStream(it) }
            else stream(c.target, s, { VirlinCommand.Control.ResumeStream(TargetRef.ById(it)) }) { ws -> ResolvedCommand.ResumeStream(ws.id, preview("Resume", ws, s)) }
        is VirlinCommand.Control.LeaveStream -> temporal(c.returnAt) ?: namedStream(c.target, "Leave", s, { VirlinCommand.Control.LeaveStream(it, c.returnAt) }) { ws, task ->
            if (ws.state != WorkStreamState.FOCUS) CommandResolution.Rejected("${ws.title} isn't in Focus right now")
            else ready(ResolvedCommand.LeaveStream(ws.id, task?.id, preview("Leave", ws, s, *listOfNotNull(task?.let { Field("Task", it.title) }, c.returnAt?.let { timeField("Back", it) } ?: Field("Reminder", "none")).toTypedArray()), c.returnAt)) }
        // ---- Control 2: external processing by name. Mode comes from the WorkStream, never from its title.
        is VirlinCommand.Control.HandOffStream -> temporal(c.checkAt) ?: namedStream(c.target, "Hand off", s, { VirlinCommand.Control.HandOffStream(it, c.checkAt) }) { ws, task ->
            external(ws, "Hand off", s) ?: if (ws.state != WorkStreamState.FOCUS) CommandResolution.Rejected("${ws.title} isn't in Focus right now")
            else ready(ResolvedCommand.HandOffStream(ws.id, task?.id, c.checkAt, preview("Hand off", ws, s, *listOfNotNull(task?.let { Field("Task", it.title) }, c.checkAt?.let { timeField("Check", it) } ?: Field("Check", "none")).toTypedArray()))) }
        is VirlinCommand.Control.CheckStream -> temporal(c.checkAt) ?: namedStream(c.target, "Check", s, { VirlinCommand.Control.CheckStream(it, c.checkAt) }) { ws, task ->
            external(ws, "Check", s) ?: when {
                ws.state == WorkStreamState.FOCUS -> ready(ResolvedCommand.HandOffStream(ws.id, task?.id, c.checkAt, preview("Hand off", ws, s, timeField("Check", c.checkAt))))
                ws.state == WorkStreamState.PROCESSING || (ws.state == WorkStreamState.CHECK && ws.snoozeReason == null) ->
                    ready(ResolvedCommand.StillRunning(ws.id, c.checkAt, preview("Check", ws, s, timeField("Check", c.checkAt))))
                else -> CommandResolution.Rejected("${ws.title} isn't processing right now — nothing to check")
            } }
        is VirlinCommand.Control.ResultReady -> namedStream(c.target, "Result ready", s, { VirlinCommand.Control.ResultReady(it) }) { ws, _ ->
            external(ws, "Result ready", s) ?: if (ws.state != WorkStreamState.PROCESSING && ws.state != WorkStreamState.CHECK) CommandResolution.Rejected("${ws.title} isn't processing right now")
            else ready(ResolvedCommand.MarkReady(ws.id, preview("Result ready", ws, s, Field("Focus", "not yet")))) }
        is VirlinCommand.Control.RemindStream -> temporal(c.at) ?: namedStream(c.target, "Remind", s, { VirlinCommand.Control.RemindStream(it, c.at) }) { ws, task -> remind(ws, task, c.at, s) }
        is VirlinCommand.Control.LeaveCurrent -> temporal(c.returnAt) ?: stream(TargetRef.CurrentStream, s, null) { ws ->
            ResolvedCommand.LeaveCurrent(ws.id, c.returnAt, preview("Leave", ws, s, c.returnAt?.let { timeField("Back", it) } ?: Field("Reminder", "none"))) }
        is VirlinCommand.Control.HandOffCurrent -> temporal(c.checkAt) ?: when (val r = streamRef(TargetRef.CurrentStream, s, null)) {
            is Ask -> r.resolution
            is Found -> external(r.value, "Hand off", s) ?: ready(ResolvedCommand.HandOffCurrent(r.value.id, c.checkAt, preview("Hand off", r.value, s, c.checkAt?.let { timeField("Check", it) } ?: Field("Check", "none"))))
        }
        is VirlinCommand.Control.StillRunning -> temporal(c.checkAt) ?: if (c.target.isNamed) namedStream(c.target, "Still running", s, { VirlinCommand.Control.StillRunning(it, c.checkAt) }) { ws, _ ->
                external(ws, "Still running", s) ?: ready(ResolvedCommand.StillRunning(ws.id, c.checkAt, preview("Still running", ws, s, timeField("Check", c.checkAt)))) }
            else stream(c.target, s, { VirlinCommand.Control.StillRunning(TargetRef.ById(it), c.checkAt) }) { ws ->
                ResolvedCommand.StillRunning(ws.id, c.checkAt, preview("Still running", ws, s, timeField("Check", c.checkAt))) }
        is VirlinCommand.Control.ResultReadyNow -> if (c.target.isNamed) namedStream(c.target, "Result ready", s, { VirlinCommand.Control.ResultReadyNow(it) }) { ws, _ ->
                external(ws, "Result ready", s) ?: ready(ResolvedCommand.ResultReadyNow(ws.id, preview("Result ready · focus now", ws, s))) }
            else stream(c.target, s, { VirlinCommand.Control.ResultReadyNow(TargetRef.ById(it)) }) { ws ->
                ResolvedCommand.ResultReadyNow(ws.id, preview("Result ready · focus now", ws, s)) }
        is VirlinCommand.Control.ResultReadyLater -> temporal(c.returnAt) ?: if (c.target.isNamed) namedStream(c.target, "Result ready", s, { VirlinCommand.Control.ResultReadyLater(it, c.returnAt) }) { ws, _ ->
                external(ws, "Result ready", s) ?: ready(ResolvedCommand.ResultReadyLater(ws.id, c.returnAt, preview("Result ready · remind later", ws, s, timeField("Remind", c.returnAt)))) }
            else stream(c.target, s, { VirlinCommand.Control.ResultReadyLater(TargetRef.ById(it), c.returnAt) }) { ws ->
                ResolvedCommand.ResultReadyLater(ws.id, c.returnAt, preview("Result ready · remind later", ws, s, timeField("Remind", c.returnAt))) }
        // BLOCK is WorkStream-only: a Task never blocks its owner silently.
        is VirlinCommand.Control.BlockStream -> if (c.target.isNamed) when (val h = named(c.target, StreamOnly, "Block", s) { VirlinCommand.Control.BlockStream(it, c.reason) }) {
                is Ask -> h.resolution
                is Found -> ready(ResolvedCommand.BlockStream(h.value.id, c.reason, preview("Block", h.value.stream!!, s))) }
            else stream(c.target, s, { VirlinCommand.Control.BlockStream(TargetRef.ById(it), c.reason) }) { ws ->
                ResolvedCommand.BlockStream(ws.id, c.reason, preview("Block", ws, s)) }
        is VirlinCommand.Control.SetCurrentTask -> if (c.task.isNamed) when (val h = named(c.task, TaskOnly, "Set current", s) { VirlinCommand.Control.SetCurrentTask(c.stream, it) }) {
            is Ask -> h.resolution
            is Found -> when (val o = owning(h.value, s, "Set current")) {
                is Ask -> o.resolution
                is Found -> ready(ResolvedCommand.SetCurrentTask(o.value.id, h.value.task!!.id, preview("Set current task", o.value, s, Field("Task", h.value.task.title))))
            } }
        else when (val w = streamRef(c.stream, s) { VirlinCommand.Control.SetCurrentTask(TargetRef.ById(it), c.task) }) {
            is Ask -> w.resolution
            is Found -> when (val t = task(c.task, s, streamScope = w.value.id, refill = { VirlinCommand.Control.SetCurrentTask(TargetRef.ById(w.value.id), TargetRef.ById(it)) })) {
                is Found -> ready(ResolvedCommand.SetCurrentTask(w.value.id, t.value.id, preview("Set current task", w.value, s, Field("Task", t.value.title))))
                is Ask -> t.resolution
            } }
        is VirlinCommand.Control.CompleteTask -> if (c.task.isNamed) when (val h = named(c.task, TaskOnly, "Complete", s) { VirlinCommand.Control.CompleteTask(it) }) {
            is Found -> ready(ResolvedCommand.CompleteTask(h.value.id, taskPreview("Complete task", h.value.task!!, s)))
            is Ask -> h.resolution }
        else when (val t = task(c.task, s, null, { VirlinCommand.Control.CompleteTask(TargetRef.ById(it)) })) {
            is Found -> ready(ResolvedCommand.CompleteTask(t.value.id, taskPreview("Complete task", t.value, s)))
            is Ask -> t.resolution }
        is VirlinCommand.Control.CancelTask -> if (c.task.isNamed) when (val h = named(c.task, TaskOnly, "Cancel", s) { VirlinCommand.Control.CancelTask(it) }) {
            is Found -> ready(ResolvedCommand.CancelTask(h.value.id, taskPreview("Cancel task", h.value.task!!, s)))
            is Ask -> h.resolution }
        else when (val t = task(c.task, s, null, { VirlinCommand.Control.CancelTask(TargetRef.ById(it)) })) {
            is Found -> ready(ResolvedCommand.CancelTask(t.value.id, taskPreview("Cancel task", t.value, s)))
            is Ask -> t.resolution }
        is VirlinCommand.Control.CompleteStream -> stream(c.target, s, { VirlinCommand.Control.CompleteStream(TargetRef.ById(it)) }) { ws ->
            ResolvedCommand.CompleteStream(ws.id, preview("Complete WorkStream", ws, s)) }
        is VirlinCommand.Control.Complete -> when (val h = named(c.target, StreamOrTask, "Complete", s) { VirlinCommand.Control.Complete(it) }) {
            is Ask -> h.resolution
            is Found -> h.value.task?.let { t -> ready(ResolvedCommand.CompleteTask(t.id, taskPreview("Complete task", t, s))) }
                ?: h.value.stream!!.let { ws -> ready(ResolvedCommand.CompleteStream(ws.id, preview("Complete WorkStream", ws, s))) }
        }
    }

    /** Named WorkStream-or-Task target → the OWNING WorkStream (+ the Task, when one located it). */
    private inline fun namedStream(target: TargetRef, verb: String, s: Snapshot, noinline refill: (TargetRef) -> VirlinCommand, build: (WorkStream, Task?) -> CommandResolution): CommandResolution =
        if (!target.isNamed) when (val r = streamRef(target, s) { refill(TargetRef.ById(it)) }) { is Ask -> r.resolution; is Found -> build(r.value, null) }   // this / current / id
        else when (val h = named(target, StreamOrTask, verb, s, refill)) {
            is Ask -> h.resolution
            is Found -> when (val o = owning(h.value, s, verb)) { is Ask -> o.resolution; is Found -> build(o.value, h.value.task) }
        }
    /** External-processing verbs need effective EXTERNAL — from [ExecutionModeResolver], never from the title. */
    private fun external(ws: WorkStream, verb: String, s: Snapshot): CommandResolution? {
        val effective = ExecutionModeResolver.resolveCurrent(ws, s.projects, s.tasks)
        return if (effective == EffectiveExecutionMode.EXTERNAL) null
        else CommandResolution.Rejected("${ws.title} is human work — $verb needs work that can continue without you")
    }
    /**
     * "Remind me about X": ONE existing semantic chosen from the stream's state, or a refusal.
     * FOCUS → leave with a HUMAN return; READY/PAUSED → snooze (HUMAN_RETURN); an existing timed
     * return (SNOOZED, or a due return) → defer it keeping its reason (HUMAN_RETURN or
     * EXTERNAL_RESULT_READY); PROCESSING / due external check → move the next check. BLOCKED / DONE → no guess.
     */
    private fun remind(ws: WorkStream, task: Task?, at: TemporalIntent, s: Snapshot): CommandResolution = when {
        ws.state == WorkStreamState.FOCUS -> ready(ResolvedCommand.LeaveStream(ws.id, task?.id, preview("Leave", ws, s, *listOfNotNull(task?.let { Field("Task", it.title) }, timeField("Back", at)).toTypedArray()), at))
        ws.state == WorkStreamState.READY || ws.state == WorkStreamState.PAUSED -> ready(ResolvedCommand.SnoozeStream(ws.id, at, preview("Remind", ws, s, timeField("Back", at))))
        ws.state == WorkStreamState.SNOOZED || (ws.state == WorkStreamState.CHECK && ws.snoozeReason != null) ->
            ready(ResolvedCommand.DeferReturn(ws.id, at, preview(if (ws.snoozeReason == com.virlin.app.domain.model.SnoozeReason.EXTERNAL_RESULT_READY) "Result ready · remind later" else "Remind", ws, s, timeField("Back", at))))
        ws.state == WorkStreamState.PROCESSING || ws.state == WorkStreamState.CHECK -> ready(ResolvedCommand.StillRunning(ws.id, at, preview("Check", ws, s, timeField("Check", at))))
        else -> CommandResolution.Rejected("${ws.title} is ${ws.state.name.lowercase()} — there's no safe reminder for that; unblock or focus it first")
    }

    /** Focus / Resume aliases over a named target: a WorkStream focuses; a Task focuses its owning WorkStream and becomes its current task. */
    private fun attention(target: TargetRef, verb: String, s: Snapshot, refill: (TargetRef) -> VirlinCommand): CommandResolution =
        when (val h = named(target, StreamOrTask, verb, s, refill)) {
            is Ask -> h.resolution
            is Found -> when (val o = owning(h.value, s, verb)) {
                is Ask -> o.resolution
                is Found -> ready(ResolvedCommand.FocusStream(o.value.id, preview(verb, o.value, s, *listOfNotNull(h.value.task?.let { Field("Task", it.title) }).toTypedArray()), h.value.task?.id))
            }
        }

    // ------------------------------------------------------------------ NAVIGATE (no state change)

    private fun navigate(c: VirlinCommand.Navigate, s: Snapshot): CommandResolution = when (c) {
        is VirlinCommand.Navigate.Open -> when (val h = named(c.target, AnyKind, "Open", s) { VirlinCommand.Navigate.Open(it) }) {
            is Ask -> h.resolution
            is Found -> ready(ResolvedCommand.Open(NavigationTarget(h.value.kind, h.value.id), CommandPreview("Open", listOf(Field(h.value.kind.label, h.value.title)))))
        }
    }

    // ------------------------------------------------------------------ CREATE

    private fun create(c: VirlinCommand.Create, s: Snapshot): CommandResolution = when (c) {
        is VirlinCommand.Create.CreateProject -> if (c.title.isBlank()) missing("What should the project be called?") else
            ready(ResolvedCommand.CreateProject(c.title.trim(), CommandPreview("Create Project", listOf(Field("Title", c.title.trim())))))
        is VirlinCommand.Create.CreateWorkStream -> {
            if (c.title.isBlank()) missing("What should the WorkStream be called?")
            else when (val p = c.project?.let { project(it, s) { VirlinCommand.Create.CreateWorkStream(c.title, TargetRef.ById(it), c.mode) } }) {
                is Ask -> p.resolution
                else -> {
                    val project = (p as? Found)?.value
                    val mode = c.mode
                    if (mode == null) CommandResolution.NeedsClarification(Clarification(
                        "Who continues the work when you leave?", Clarification.Kind.MISSING_MODE,
                        listOf(Clarification.Candidate(WorkStreamMode.HUMAN.name, "I do the work"), Clarification.Candidate(WorkStreamMode.EXTERNAL.name, "It can continue without me")),
                        refill = { VirlinCommand.Create.CreateWorkStream(c.title, project?.let { TargetRef.ById(it.id) }, WorkStreamMode.valueOf(it)) }))
                    else ready(ResolvedCommand.CreateWorkStream(c.title.trim(), project?.id, mode, CommandPreview("Create WorkStream", listOfNotNull(
                        Field("Title", c.title.trim()), Field("Project", project?.title ?: "No Project"), Field("Mode", if (mode == WorkStreamMode.HUMAN) "I do the work" else "It can continue without me")))))
                }
            }
        }
        is VirlinCommand.Create.CreateTask -> if (c.title.isBlank()) missing("What should the task be called?") else
            when (val o = owner(c.owner, s) { VirlinCommand.Create.CreateTask(c.title, it, c.estimate) }) {
                is Ask -> o.resolution
                is Found -> ready(ResolvedCommand.CreateTask(c.title.trim(), o.value.workStreamId, o.value.projectId, o.value.parentTaskId, c.estimate,
                    CommandPreview("Create Task", listOf(Field("Title", c.title.trim()), Field(o.value.label, o.value.title)))))
            }
    }

    // ------------------------------------------------------------------ CAPTURE

    private fun capture(c: VirlinCommand.Capture, s: Snapshot): CommandResolution = when (c) {
        is VirlinCommand.Capture.CaptureNote -> captureCreate(CaptureType.NOTE, c.content, null, c.context, s) { VirlinCommand.Capture.CaptureNote(c.content, it) }
        is VirlinCommand.Capture.CapturePrompt -> captureCreate(CaptureType.PROMPT, c.content, null, c.context, s) { VirlinCommand.Capture.CapturePrompt(c.content, it) }
        is VirlinCommand.Capture.CaptureLink -> captureCreate(CaptureType.LINK, c.note ?: "", c.url, c.context, s) { VirlinCommand.Capture.CaptureLink(c.url, c.note, it) }
        is VirlinCommand.Capture.ArchiveCapture -> when (val cap = captureRef(c.capture, s) { VirlinCommand.Capture.ArchiveCapture(TargetRef.ById(it)) }) {
            is Ask -> cap.resolution
            is Found -> ready(ResolvedCommand.ArchiveCapture(cap.value.id, CommandPreview("Archive capture", listOf(Field("Capture", cap.value.shortLabel())))))
        }
        is VirlinCommand.Capture.AttachCapture -> when (val cap = captureRef(c.capture, s) { VirlinCommand.Capture.AttachCapture(TargetRef.ById(it), c.context) }) {
            is Ask -> cap.resolution
            is Found -> when (val ctx = captureContext(c.context, s) { VirlinCommand.Capture.AttachCapture(TargetRef.ById(cap.value.id), it) }) {
                is Ask -> ctx.resolution
                is Found -> ready(ResolvedCommand.AttachCapture(cap.value.id, ctx.value.projectId, ctx.value.workStreamId, ctx.value.taskId,
                    CommandPreview("Attach capture", listOf(Field("Capture", cap.value.shortLabel()), Field("To", ctx.value.label ?: "Global · Inbox")))))
            }
        }
        is VirlinCommand.Capture.ConvertCaptureToTask -> when (val cap = captureRef(c.capture, s) { VirlinCommand.Capture.ConvertCaptureToTask(TargetRef.ById(it), c.owner) }) {
            is Ask -> cap.resolution
            is Found -> when (val o = owner(c.owner, s) { VirlinCommand.Capture.ConvertCaptureToTask(TargetRef.ById(cap.value.id), it) }) {
                is Ask -> o.resolution
                is Found -> ready(ResolvedCommand.ConvertCaptureToTask(cap.value.id, o.value.workStreamId, o.value.projectId, o.value.parentTaskId,
                    CommandPreview("Convert capture to task", listOf(Field("Capture", cap.value.shortLabel()), Field(o.value.label, o.value.title)))))
            }
        }
    }

    private fun captureCreate(type: CaptureType, content: String, url: String?, context: CaptureContextRef?, s: Snapshot, refill: (CaptureContextRef?) -> VirlinCommand): CommandResolution {
        if (type != CaptureType.LINK && content.isBlank()) return missing("Nothing to save yet")
        if (type == CaptureType.LINK && url.isNullOrBlank()) return missing("Which link?")
        return when (val ctx = captureContext(context, s, refill)) {
            is Ask -> ctx.resolution
            is Found -> ready(ResolvedCommand.CreateCapture(type, content, url, ctx.value.projectId, ctx.value.workStreamId, ctx.value.taskId,
                CommandPreview("Save ${type.commandLabel.lowercase()}", listOf(Field("Content", (url ?: content).lineSequence().first().take(80)), Field("Context", ctx.value.label ?: "Global · Inbox")))))
        }
    }

    // ------------------------------------------------------------------ QUERY

    private fun query(q: VirlinCommand.Query, s: Snapshot): CommandResolution = when (q) {
        is VirlinCommand.Query.GetTasks -> stream(q.stream, s, { VirlinCommand.Query.GetTasks(TargetRef.ById(it)) }) { ws ->
            ResolvedCommand.Query(q, ws.id, CommandPreview("Tasks", listOf(Field("WorkStream", ws.title)))) }
        else -> ready(ResolvedCommand.Query(q, null, CommandPreview(q::class.simpleName ?: "Query")))
    }

    // ------------------------------------------------------------------ reference resolution

    private sealed interface Lookup<out T>
    private data class Found<T>(val value: T) : Lookup<T>
    private data class Ask(val resolution: CommandResolution) : Lookup<Nothing>

    private data class ResolvedContext(val projectId: String?, val workStreamId: String?, val taskId: String?, val label: String?)
    private data class ResolvedOwner(val workStreamId: String?, val projectId: String?, val parentTaskId: String?, val label: String, val title: String)

    private inline fun stream(ref: TargetRef, s: Snapshot, noinline refill: ((String) -> VirlinCommand)?, build: (WorkStream) -> ResolvedCommand): CommandResolution =
        when (val r = streamRef(ref, s, refill)) { is Found -> ready(build(r.value)); is Ask -> r.resolution }

    private fun streamRef(ref: TargetRef, s: Snapshot, refill: ((String) -> VirlinCommand)?): Lookup<WorkStream> {
        val live = s.streams.filter { !it.state.isTerminal }
        return when (ref) {
            is TargetRef.CurrentStream -> s.streams.firstOrNull { it.state == WorkStreamState.FOCUS }?.let { Found(it) }
                ?: Ask(clarify("Nothing is in Focus right now.", Clarification.Kind.NO_CURRENT_STREAM, live.map { candidate(it, s) }, refill))
            is TargetRef.ThisStream -> s.ctx.selectedStreamId?.let { id -> live.firstOrNull { it.id == id } }?.let { Found(it) }
                ?: Ask(clarify("Which WorkStream do you mean?", Clarification.Kind.NO_SELECTED_STREAM, live.map { candidate(it, s) }, refill))
            is TargetRef.CurrentTask, is TargetRef.ThisTask -> Ask(CommandResolution.Rejected("A task reference was given where a WorkStream is expected"))
            is TargetRef.ById -> live.firstOrNull { it.id == ref.id }?.let { Found(it) }
                ?: Ask(clarify("That WorkStream no longer exists.", Clarification.Kind.TARGET_NOT_FOUND, emptyList(), null))
            is TargetRef.ByName, is TargetRef.Named -> byName(nameOf(ref), live, { it.id }, { it.title }, "WorkStream", { candidate(it, s) }, refill)
            is TargetRef.Entity -> if (ref.kind != EntityKind.WORKSTREAM) Ask(CommandResolution.Rejected("A ${ref.kind.label} was given where a WorkStream is expected"))
                else live.firstOrNull { it.id == ref.id }?.let { Found(it) } ?: Ask(clarify("That WorkStream no longer exists.", Clarification.Kind.TARGET_NOT_FOUND, emptyList(), null))
        }
    }

    private fun project(ref: TargetRef, s: Snapshot, refill: (String) -> VirlinCommand): Lookup<Project> = when (ref) {
        is TargetRef.ById -> s.projects.firstOrNull { it.id == ref.id }?.let { Found(it) } ?: Ask(clarify("That Project no longer exists.", Clarification.Kind.TARGET_NOT_FOUND, emptyList(), null))
        is TargetRef.ByName, is TargetRef.Named -> byName(nameOf(ref), s.projects, { it.id }, { it.title }, "Project", { Clarification.Candidate(it.id, it.title) }, refill)
        is TargetRef.Entity -> if (ref.kind != EntityKind.PROJECT) Ask(CommandResolution.Rejected("A ${ref.kind.label} was given where a Project is expected"))
            else s.projects.firstOrNull { it.id == ref.id }?.let { Found(it) } ?: Ask(clarify("That Project no longer exists.", Clarification.Kind.TARGET_NOT_FOUND, emptyList(), null))
        else -> Ask(CommandResolution.Rejected("A Project must be named or identified"))
    }

    private fun task(ref: TargetRef, s: Snapshot, streamScope: String?, refill: (String) -> VirlinCommand): Lookup<Task> {
        val open = s.tasks.filter { !it.status.isTerminal && (streamScope == null || it.workStreamId == streamScope) }
        return when (ref) {
            is TargetRef.CurrentTask -> {
                val focus = s.streams.firstOrNull { it.state == WorkStreamState.FOCUS }
                    ?: return Ask(clarify("Nothing is in Focus right now.", Clarification.Kind.NO_CURRENT_STREAM, emptyList(), null))
                focus.activeTaskId?.let { id -> s.tasks.firstOrNull { it.id == id } }?.let { Found(it) }
                    ?: Ask(clarify("${focus.title} has no current task. Which task?", Clarification.Kind.NO_CURRENT_TASK,
                        open.filter { it.workStreamId == focus.id }.map { taskCandidate(it, s) }, refill))
            }
            is TargetRef.ById -> s.tasks.firstOrNull { it.id == ref.id }?.let { Found(it) } ?: Ask(clarify("That task no longer exists.", Clarification.Kind.TARGET_NOT_FOUND, emptyList(), null))
            is TargetRef.ThisTask -> {
                val selected = s.ctx.selectedTaskId?.let { id -> open.firstOrNull { it.id == id } }
                if (selected != null) Found(selected)
                else {
                    val focus = s.streams.firstOrNull { it.state == WorkStreamState.FOCUS }
                    focus?.activeTaskId?.let { id -> open.firstOrNull { it.id == id } }?.let { Found(it) }
                        ?: Ask(clarify("Which task should it go under?", Clarification.Kind.NO_CURRENT_TASK, open.filter { focus != null && it.workStreamId == focus.id }.map { taskCandidate(it, s) }, refill))
                }
            }
            is TargetRef.ByName, is TargetRef.Named -> byName(nameOf(ref), open, { it.id }, { it.title }, "task", { taskCandidate(it, s) }, refill)
            is TargetRef.Entity -> if (ref.kind != EntityKind.TASK) Ask(CommandResolution.Rejected("A ${ref.kind.label} was given where a task is expected"))
                else open.firstOrNull { it.id == ref.id }?.let { Found(it) } ?: Ask(clarify("That task no longer exists.", Clarification.Kind.TARGET_NOT_FOUND, emptyList(), null))
            else -> Ask(CommandResolution.Rejected("A task must be named or identified"))
        }
    }

    // ------------------------------------------------------------------ Control 1: one name, several possible kinds

    private class Hit(val kind: EntityKind, val id: String, val title: String, val project: Project? = null, val stream: WorkStream? = null, val task: Task? = null)
    private val TargetRef.isNamed: Boolean get() = this is TargetRef.Named || this is TargetRef.Entity
    private fun nameOf(ref: TargetRef) = when (ref) { is TargetRef.ByName -> ref.name; is TargetRef.Named -> ref.name; else -> "" }

    /** Every live entity of the given kinds. Terminal streams / closed tasks are never targets. */
    private fun pool(kinds: Set<EntityKind>, s: Snapshot): List<Hit> = buildList {
        if (EntityKind.PROJECT in kinds) s.projects.forEach { add(Hit(EntityKind.PROJECT, it.id, it.title, project = it)) }
        if (EntityKind.WORKSTREAM in kinds) s.streams.filter { !it.state.isTerminal }.forEach { add(Hit(EntityKind.WORKSTREAM, it.id, it.title, stream = it)) }
        if (EntityKind.TASK in kinds) s.tasks.filter { !it.status.isTerminal }.forEach { add(Hit(EntityKind.TASK, it.id, it.title, task = it)) }
    }
    private fun typedValue(h: Hit) = "${h.kind.name.lowercase()}:${h.id}"
    private fun entityOf(value: String): TargetRef.Entity? {
        val i = value.indexOf(':'); if (i <= 0) return null
        val kind = EntityKind.entries.firstOrNull { it.name.lowercase() == value.substring(0, i) } ?: return null
        return TargetRef.Entity(kind, value.substring(i + 1))
    }
    /** Candidate with type + ancestry so same-titled entities stay distinguishable. */
    private fun typedCandidate(h: Hit, s: Snapshot): Clarification.Candidate = Clarification.Candidate(typedValue(h), h.title, path(h, s), h.kind.label)
    private fun path(h: Hit, s: Snapshot): String? = when (h.kind) {
        EntityKind.PROJECT -> null
        EntityKind.WORKSTREAM -> h.stream!!.projectId?.let { id -> s.projects.firstOrNull { it.id == id }?.title } ?: "No Project"
        EntityKind.TASK -> {
            val t = h.task!!
            val stream = t.workStreamId?.let { id -> s.streams.firstOrNull { it.id == id } }
            val project = (stream?.projectId ?: t.projectId)?.let { id -> s.projects.firstOrNull { it.id == id } }
            val ancestors = com.virlin.app.domain.model.TaskHierarchy.ancestry(s.tasks, t.id)?.drop(1)?.asReversed()?.map { it.title } ?: emptyList()
            (listOfNotNull(project?.title, stream?.title) + ancestors).joinToString(" → ").ifEmpty { null }
        }
    }
    private fun kindsLabel(kinds: Set<EntityKind>) = kinds.sortedBy { it.ordinal }.joinToString(" or ") { it.label }

    /**
     * Resolve a [TargetRef.Named] across the kinds an action allows (exact id → exact normalized
     * title → unique case-insensitive → unique prefix/word → typed clarification). A name that only
     * exists as a DISALLOWED kind is refused plainly — never re-routed to a nearby action. An
     * [TargetRef.Entity] (a chosen candidate) resolves by id and kind without any title search.
     */
    private fun named(ref: TargetRef, kinds: Set<EntityKind>, verb: String, s: Snapshot, refill: (TargetRef) -> VirlinCommand): Lookup<Hit> {
        val allowed = pool(kinds, s)
        return when (ref) {
            is TargetRef.Entity -> {
                if (ref.kind !in kinds) return Ask(CommandResolution.Rejected("$verb can't take a ${ref.kind.label}"))
                allowed.firstOrNull { it.kind == ref.kind && it.id == ref.id }?.let { Found(it) } ?: Ask(clarify("That ${ref.kind.label} no longer exists.", Clarification.Kind.TARGET_NOT_FOUND, emptyList(), null))
            }
            is TargetRef.ById -> allowed.firstOrNull { it.id == ref.id }?.let { Found(it) } ?: Ask(clarify("That no longer exists.", Clarification.Kind.TARGET_NOT_FOUND, emptyList(), null))
            is TargetRef.Named, is TargetRef.ByName -> {
                val name = nameOf(ref)
                val r = byName(name, allowed, ::typedValue, { it.title }, kindsLabel(kinds), { typedCandidate(it, s) }, { v -> refill(entityOf(v) ?: TargetRef.Named(name)) })
                if (r is Ask && (r.resolution as? CommandResolution.NeedsClarification)?.clarification?.kind == Clarification.Kind.TARGET_NOT_FOUND) {
                    val elsewhere = pool(EntityKind.entries.toSet() - kinds, s).filter { normalize(it.title) == normalize(name) }
                    if (elsewhere.isNotEmpty()) return Ask(clarify("\"${elsewhere.first().title}\" is a ${elsewhere.map { it.kind.label }.distinct().joinToString(" / ")} — $verb needs a ${kindsLabel(kinds)}.", Clarification.Kind.TARGET_NOT_FOUND, emptyList(), null))
                }
                r
            }
            else -> Ask(CommandResolution.Rejected("$verb needs a name"))
        }
    }
    /** The WorkStream that owns a hit: the stream itself, or a Task's owning stream (standalone Project tasks have none). */
    private fun owning(h: Hit, s: Snapshot, verb: String): Lookup<WorkStream> = when (h.kind) {
        EntityKind.WORKSTREAM -> Found(h.stream!!)
        EntityKind.TASK -> h.task!!.workStreamId?.let { id -> s.streams.firstOrNull { it.id == id && !it.state.isTerminal } }?.let { Found(it) }
            ?: Ask(clarify("\"${h.title}\" is a standalone Project task — $verb needs a WorkStream.", Clarification.Kind.TARGET_NOT_FOUND, emptyList(), null))
        EntityKind.PROJECT -> Ask(clarify("\"${h.title}\" is a Project — $verb needs a WorkStream or Task.", Clarification.Kind.TARGET_NOT_FOUND, emptyList(), null))
    }

    private fun captureRef(ref: TargetRef, s: Snapshot, refill: (String) -> VirlinCommand): Lookup<CaptureItem> = when (ref) {
        is TargetRef.ById -> s.captures.firstOrNull { it.id == ref.id }?.let { Found(it) } ?: Ask(clarify("That capture no longer exists.", Clarification.Kind.TARGET_NOT_FOUND, emptyList(), null))
        is TargetRef.ByName -> byName(ref.name, s.captures.filter { it.status == CaptureStatus.INBOX }, { it.id }, { it.title ?: it.content.lineSequence().first() }, "capture",
            { Clarification.Candidate(it.id, it.shortLabel(), it.type.commandLabel) }, refill)
        else -> Ask(CommandResolution.Rejected("A capture must be identified"))
    }

    private fun owner(o: TaskOwnerRef, s: Snapshot, refill: (TaskOwnerRef) -> VirlinCommand): Lookup<ResolvedOwner> = when (o) {
        is TaskOwnerRef.WorkStream -> when (val r = streamRef(o.ref, s) { refill(TaskOwnerRef.WorkStream(TargetRef.ById(it))) }) {
            is Found -> Found(ResolvedOwner(r.value.id, null, null, "WorkStream", r.value.title)); is Ask -> r }
        is TaskOwnerRef.Project -> when (val r = project(o.ref, s) { refill(TaskOwnerRef.Project(TargetRef.ById(it))) }) {
            is Found -> Found(ResolvedOwner(null, r.value.id, null, "Project", r.value.title)); is Ask -> r }
        is TaskOwnerRef.ParentTask -> when (val r = task(o.ref, s, null) { refill(TaskOwnerRef.ParentTask(TargetRef.ById(it))) }) {
            is Found -> Found(ResolvedOwner(null, null, r.value.id, "Under", r.value.title)); is Ask -> r }
        // Create V1: "under X" — Project, WorkStream or Task; typed clarification when several match; identity after a choice.
        is TaskOwnerRef.Any -> when (val h = named(o.ref, AnyKind, "Create task", s) { refill(TaskOwnerRef.Any(it)) }) {
            is Ask -> h
            is Found -> Found(when (h.value.kind) {
                EntityKind.PROJECT -> ResolvedOwner(null, h.value.id, null, "Project", h.value.title)
                EntityKind.WORKSTREAM -> ResolvedOwner(h.value.id, null, null, "WorkStream", h.value.title)
                EntityKind.TASK -> ResolvedOwner(null, null, h.value.id, "Under", path(h.value, s)?.let { "${h.value.title} · $it" } ?: h.value.title)
            })
        }
    }

    private fun captureContext(c: CaptureContextRef?, s: Snapshot, refill: (CaptureContextRef?) -> VirlinCommand): Lookup<ResolvedContext> {
        if (c == null || c.isEmpty) return Found(ResolvedContext(null, null, null, null))
        c.task?.let { ref ->
            return when (val t = task(ref, s, null) { refill(CaptureContextRef(task = TargetRef.ById(it))) }) {
                is Found -> Found(ResolvedContext(t.value.projectId, t.value.workStreamId, t.value.id, t.value.title)); is Ask -> t }
        }
        c.workStream?.let { ref ->
            return when (val w = streamRef(ref, s) { refill(CaptureContextRef(workStream = TargetRef.ById(it))) }) {
                is Found -> Found(ResolvedContext(w.value.projectId, w.value.id, null, w.value.title)); is Ask -> w }
        }
        return when (val p = project(c.project!!, s) { refill(CaptureContextRef(project = TargetRef.ById(it))) }) {
            is Found -> Found(ResolvedContext(p.value.id, null, null, p.value.title)); is Ask -> p }
    }

    /** exact normalized title → unique case-insensitive → unique normalized prefix/word match → clarification. */
    private fun <T> byName(name: String, items: List<T>, id: (T) -> String, title: (T) -> String, kind: String, cand: (T) -> Clarification.Candidate, refill: ((String) -> VirlinCommand)?): Lookup<T> {
        val n = normalize(name)
        if (n.isEmpty()) return Ask(clarify("Which $kind?", Clarification.Kind.MISSING_FIELD, items.map(cand), refill))
        items.firstOrNull { id(it) == name }?.let { return Found(it) }
        val exact = items.filter { normalize(title(it)) == n }
        if (exact.size == 1) return Found(exact.single())
        if (exact.size > 1) return Ask(clarify("Which ${title(exact.first())}?", Clarification.Kind.AMBIGUOUS_TARGET, exact.map(cand), refill))
        val partial = items.filter { val t = normalize(title(it)); t.startsWith(n) || t.split(' ').contains(n) }
        return when (partial.size) {
            1 -> Found(partial.single())
            0 -> Ask(clarify("No $kind called \"$name\".", Clarification.Kind.TARGET_NOT_FOUND, emptyList(), null))
            else -> Ask(clarify("Which $kind — \"$name\" matches several.", Clarification.Kind.AMBIGUOUS_TARGET, partial.map(cand), refill))
        }
    }

    private fun normalize(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
    private companion object {
        val StreamOrTask = setOf(EntityKind.WORKSTREAM, EntityKind.TASK)
        val TaskOnly = setOf(EntityKind.TASK)
        val StreamOnly = setOf(EntityKind.WORKSTREAM)
        val AnyKind = EntityKind.entries.toSet()
    }
    private fun candidate(ws: WorkStream, s: Snapshot) = Clarification.Candidate(ws.id, ws.title, ws.projectId?.let { id -> s.projects.firstOrNull { it.id == id }?.title } ?: "No Project")
    private fun taskCandidate(t: Task, s: Snapshot) = Clarification.Candidate(t.id, t.title, t.workStreamId?.let { id -> s.streams.firstOrNull { it.id == id }?.title } ?: t.projectId?.let { id -> s.projects.firstOrNull { it.id == id }?.title })
    private fun clarify(q: String, k: Clarification.Kind, c: List<Clarification.Candidate>, refill: ((String) -> VirlinCommand)?) =
        CommandResolution.NeedsClarification(Clarification(q, k, c, refill))
    private fun ready(c: ResolvedCommand) = CommandResolution.Ready(c)
    private fun missing(q: String) = CommandResolution.NeedsClarification(Clarification(q, Clarification.Kind.MISSING_FIELD, emptyList()))
    private fun invalid(q: String) = CommandResolution.NeedsClarification(Clarification(q, Clarification.Kind.INVALID_FIELD, emptyList()))
    private fun preview(title: String, ws: WorkStream, s: Snapshot, vararg extra: Field) = CommandPreview(title, listOfNotNull(
        Field("WorkStream", ws.title), ws.projectId?.let { id -> s.projects.firstOrNull { it.id == id }?.title }?.let { Field("Project", it) }) + extra)
    private fun taskPreview(title: String, t: Task, s: Snapshot) = CommandPreview(title, listOfNotNull(
        Field("Task", t.title), t.workStreamId?.let { id -> s.streams.firstOrNull { it.id == id }?.title }?.let { Field("WorkStream", it) }))
    /** Relative durations must be positive; absolute targets must still be in the future at resolution. */
    private fun temporal(t: TemporalIntent?): CommandResolution? = when (t) {
        null -> null
        is TemporalIntent.Relative -> if (t.duration.isNegative || t.duration.isZero) invalid("The time must be in the future") else null
        is TemporalIntent.Absolute -> if (!t.at.isAfter(clock.now())) CommandResolution.NeedsClarification(Clarification(
            "${time.format(t.at, clock.now())} has already passed.", Clarification.Kind.TIME_ALREADY_PASSED, emptyList())) else null
    }
    /** "Back in 10m" for relative, "Back at Tomorrow · 9:00 AM" for absolute — the exact resolved local time. */
    private fun timeField(verb: String, t: TemporalIntent): Field = when (t) {
        is TemporalIntent.Relative -> Field("$verb in", time.relative(t.duration))
        is TemporalIntent.Absolute -> Field("$verb at", time.format(t.at, clock.now()))
    }
}

internal fun CaptureItem.shortLabel(): String = (title ?: sourceUrl ?: content).lineSequence().first().take(60)

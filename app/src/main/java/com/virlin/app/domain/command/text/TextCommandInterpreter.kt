package com.virlin.app.domain.command.text

import com.virlin.app.domain.command.CaptureContextRef
import com.virlin.app.domain.command.TargetRef
import com.virlin.app.domain.command.TaskOwnerRef
import com.virlin.app.domain.command.VirlinCommand
import com.virlin.app.domain.command.VirlinCommand.Capture
import com.virlin.app.domain.command.VirlinCommand.Control
import com.virlin.app.domain.command.VirlinCommand.Create
import com.virlin.app.domain.command.VirlinCommand.Navigate
import com.virlin.app.domain.command.VirlinCommand.Query
import com.virlin.app.domain.command.Clarification
import com.virlin.app.domain.command.time.TemporalClarification
import com.virlin.app.domain.command.time.TemporalIntent
import com.virlin.app.domain.command.time.TimeExpressionParser
import com.virlin.app.domain.command.time.TimeParse
import com.virlin.app.domain.model.WorkStreamMode

/** Result of interpreting one line of composer text. Never throws for bad input. */
sealed interface TextInterpretation {
    /** A typed, still-UNRESOLVED command. Names stay `TargetRef.ByName`; the resolver decides. */
    data class Parsed(val command: VirlinCommand) : TextInterpretation
    /** Recognised intent but a required piece is missing or malformed (e.g. a bad duration). */
    data class Invalid(val reason: String, val hint: String? = null) : TextInterpretation
    /** Not part of the command language. Nothing is guessed. */
    data class Unsupported(val reason: String) : TextInterpretation
    /**
     * The command is understood but its time phrase needs a decision ("tomorrow" → what time?,
     * "at 9" → AM or PM?, a passed time → tomorrow?). Candidates are full rewritten commands.
     */
    data class NeedsTime(val clarification: Clarification) : TextInterpretation
}

/**
 * Deterministic text → [VirlinCommand] (Pass 12). A compact, explicit command language —
 * NOT general NLP: leading keywords classify the family, each family grammar is a short list
 * of readable forms, payloads are kept verbatim (case, quotes stripped only when they wrap the
 * whole payload). The interpreter only constructs commands; it never resolves names, never
 * touches actions, repositories or the executor, and never executes anything.
 */
class TextCommandInterpreter(private val time: TimeExpressionParser) {

    companion object {
        const val UnsupportedMessage = "I couldn't map that to a Virlin command yet."
        val Examples = listOf(
            "focus psychology", "switch to claude build", "leave for 10 minutes", "leave until 5 PM", "hand off for 5m", "check again tomorrow at 9 AM", "result ready",
            "complete current task", "create project MBA Research", "create external workstream Claude Build",
            "capture note Investigate alarm precision", "what am I working on?"
        )
    }

    // lazy: the grammar captures template tables declared further down this class
    private val control by lazy { ControlGrammar() }

    fun interpret(input: String): TextInterpretation {
        val raw = collapseOutsideQuotes(input.trim())
        if (raw.isEmpty()) return TextInterpretation.Invalid("Type a command first")
        val lower = raw.lowercase()
        return QueryGrammar.parse(raw, lower)
            ?: CaptureGrammar.parse(raw, lower)
            ?: CreateGrammar.parse(raw, lower)
            ?: control.parse(raw, lower)
            ?: TextInterpretation.Unsupported(UnsupportedMessage)
    }

    // ================================================================ grammars

    /** Read-only questions. Trailing "?" and a leading "show " are tolerated. */
    private object QueryGrammar {
        private val forms: List<Pair<Set<String>, Query>> = listOf(
            setOf("what am i working on", "current focus", "what is my current focus", "what's my current focus", "show current focus") to Query.GetCurrentFocus,
            setOf("what needs my attention", "what needs attention", "show needs attention", "needs attention", "show needs you", "what needs me") to Query.GetNeedsAttention,
            setOf("what is working for me", "what's working for me", "show processing", "what is processing", "what's processing", "show working for you") to Query.GetProcessingStreams,
            setOf("what can i work on", "show ready", "what is ready", "what's ready", "show when you're free") to Query.GetReadyStreams,
            setOf("show capture inbox", "show inbox", "what did i capture", "show captures", "capture inbox") to Query.GetCaptureInbox,
            setOf("show projects", "what projects do i have", "list projects") to Query.GetProjects,
            setOf("show workstreams", "list workstreams", "what workstreams do i have") to Query.GetWorkStreams
        )
        fun parse(raw: String, lower: String): TextInterpretation? {
            val q = lower.trimEnd('?', '.', '!', ' ')
            forms.firstOrNull { (keys, _) -> q in keys }?.let { return TextInterpretation.Parsed(it.second) }
            val tasks = Regex("""^(?:show|list) tasks (?:in|for|of) (.+)$""").matchEntire(q)
            if (tasks != null) return TextInterpretation.Parsed(Query.GetTasks(nameRef(raw.substring(raw.length - tasks.groupValues[1].length).trimEnd('?', '.', '!', ' '))))
            return null
        }
    }

    /** Capture payloads are DATA: everything after the keyword is stored verbatim, never re-interpreted. */
    private object CaptureGrammar {
        fun parse(raw: String, lower: String): TextInterpretation? {
            payloadAfter(raw, lower, "capture note", "save note", "note", "remember")?.let { return note(it) }
            payloadAfter(raw, lower, "capture prompt", "save prompt")?.let { return prompt(it) }
            payloadAfter(raw, lower, "capture link", "save link")?.let { return link(it) }
            return null
        }
        private fun note(p: String) = if (p.isBlank()) TextInterpretation.Invalid("What should I remember?", "capture note <text>") else TextInterpretation.Parsed(Capture.CaptureNote(unquote(p)))
        private fun prompt(p: String) = if (p.isBlank()) TextInterpretation.Invalid("Paste the prompt after the command", "capture prompt <text>") else TextInterpretation.Parsed(Capture.CapturePrompt(unquote(p)))
        private fun link(p: String): TextInterpretation {
            val body = unquote(p)
            if (body.isBlank()) return TextInterpretation.Invalid("Which link?", "capture link <url>")
            val url = body.substringBefore(' ')
            val note = body.substringAfter(' ', "").trim().takeIf { it.isNotEmpty() }?.let(::unquote)
            return TextInterpretation.Parsed(Capture.CaptureLink(url, note))
        }
    }

    /**
     * Structure creation (Create V1). The entity word is REQUIRED (project / workstream / task /
     * subtask); the title is the complete remaining text; an owner is split off only at an
     * approved delimiter ("under" first, then "to" / "in", last occurrence). Mode comes only from
     * the words "human" / "external"; a missing mode reaches the resolver as null (it asks).
     * Adding a phrase alias = one entry in a `phrases` list below.
     */
    private object CreateGrammar {
        private val modeWords = mapOf("human" to WorkStreamMode.HUMAN, "external" to WorkStreamMode.EXTERNAL)
        private val verbs = listOf("create a new", "create another", "create a", "create an", "create", "add a new", "add another", "add a", "add an", "add", "new")
        private val streamWords = listOf("workstream", "work stream", "stream")
        private val called = listOf("called", "named", "titled")

        fun parse(raw: String, lower: String): TextInterpretation? {
            val verb = verbs.firstOrNull { lower == it || lower.startsWith("$it ") } ?: return null
            var rest = raw.substring(verb.length).trim(); var restL = rest.lowercase()
            if (rest.isEmpty()) return TextInterpretation.Invalid("Create what — a project, a workstream or a task?", "create task <title>")
            // ---- project
            afterWord(rest, restL, listOf("project"))?.let { return titled(stripCalled(it), "project") { t -> Create.CreateProject(t) } }
            // ---- workstream [human|external] [under|to|in project P] [as human|external]
            var mode: WorkStreamMode? = null
            for ((w, m) in modeWords) afterWord(rest, restL, listOf(w))?.let { mode = m; rest = it; restL = it.lowercase() }
            afterWord(rest, restL, streamWords)?.let { p -> return workStream(stripCalled(p), mode) }
            if (mode != null) { val w = modeWords.entries.first { it.value == mode }.key; return TextInterpretation.Invalid("Create a $w what? Say \"create $w workstream <title>\".") }
            // ---- subtask = child task (language alias only; persisted as a Task with parentTaskId)
            afterWord(rest, restL, listOf("subtask", "sub-task", "child task"))?.let { p -> return task(stripCalled(p), child = true) }
            afterWord(rest, restL, listOf("task"))?.let { p -> return task(stripCalled(p), child = false) }
            if (restL == "project" || restL in streamWords || restL == "task" || restL == "subtask") return titled("", restL) { Create.CreateProject(it) }
            return TextInterpretation.Invalid("Create what — a project, a workstream or a task? Say \"create task ${unquote(rest)}\".", "create task <title>")
        }
        private fun afterWord(rest: String, restL: String, words: List<String>): String? {
            for (w in words) { if (restL == w) return ""; if (restL.startsWith("$w ") || restL.startsWith("$w:")) return rest.substring(w.length).trimStart(':', ' ') }
            return null
        }
        /** "called X" / "named X" / ": X" → X. */
        private fun stripCalled(p: String): String {
            val l = p.lowercase()
            for (c in called) if (l.startsWith("$c ")) return p.substring(c.length + 1)
            return p
        }
        private inline fun titled(p: String, what: String, build: (String) -> VirlinCommand): TextInterpretation {
            val t = unquote(p); return if (t.isBlank()) TextInterpretation.Invalid("What should the ${if (what == "stream") "workstream" else what} be called?", "create $what <title>") else TextInterpretation.Parsed(build(t))
        }
        /** Split "<title> under|to|in <owner>" at the LAST approved delimiter ("under" preferred). Quoted titles are never split. */
        private fun owner(p: String, delimiters: List<String>): Pair<String, String?> {
            if (isQuoted(p)) return p to null
            val l = p.lowercase()
            for (d in delimiters) {
                val i = l.lastIndexOf(" $d ")
                if (i > 0) { val o = p.substring(i + d.length + 2).trim(); if (o.isNotEmpty()) return p.substring(0, i) to o }
            }
            return p to null
        }
        /** `<title> [under|to|in [project] P] [as human|external]` — a Project is the only valid owner. */
        private fun workStream(p: String, explicitMode: WorkStreamMode?): TextInterpretation {
            var rest = p; var mode = explicitMode
            if (!isQuoted(rest)) Regex("""^(.*?)\s+as (human|external)$""", RegexOption.IGNORE_CASE).matchEntire(rest)?.let { m -> rest = m.groupValues[1]; mode = modeWords[m.groupValues[2].lowercase()] }
            val (titleRaw, ownerRaw) = owner(rest, listOf("under", "in project", "to project", "to", "in"))
            val title = unquote(titleRaw)
            if (title.isBlank()) return TextInterpretation.Invalid("What should the WorkStream be called?", "create workstream <title>")
            val project = ownerRaw?.let { TargetRef.Named(unquote(it.removePrefix("project ").removePrefix("Project "))) }
            return TextInterpretation.Parsed(Create.CreateWorkStream(title, project, mode))
        }
        /**
         * `<title> [under|to|in <owner>]`. Owner kind is NOT stated: Project / WorkStream / Task
         * (resolver decides, [TaskOwnerRef.Any]). A subtask owner is a Task. No owner: a subtask
         * goes under "this task", a task under "this" WorkStream — the resolver asks when unsafe.
         */
        private fun task(p: String, child: Boolean): TextInterpretation {
            val (titleRaw, ownerRaw) = owner(p, listOf("under", "to", "in", "inside", "below"))
            val title = unquote(titleRaw)
            if (title.isBlank()) return TextInterpretation.Invalid("What should the ${if (child) "subtask" else "task"} be called?", "create task <title>")
            val owner: TaskOwnerRef = when {
                ownerRaw != null && child -> TaskOwnerRef.ParentTask(TargetRef.Named(unquote(ownerRaw.removePrefix("task ").removePrefix("Task "))))
                ownerRaw != null -> TaskOwnerRef.Any(TargetRef.Named(unquote(ownerRaw)))
                child -> TaskOwnerRef.ParentTask(TargetRef.ThisTask)
                else -> TaskOwnerRef.WorkStream(TargetRef.ThisStream)
            }
            return TextInterpretation.Parsed(Create.CreateTask(title, owner))
        }
    }

    /**
     * Control 1+2 — natural `[ACTION] + [TARGET] [+ TIME]` templates. The target is the COMPLETE
     * remaining text kept as a kind-less [TargetRef.Named]; the action's allowed kinds and the
     * resolver decide Project / WorkStream / Task. A temporal modifier is split off as text and
     * handed to the injected [TimeExpressionParser] — the parser never interprets time itself.
     * Adding a phrase alias for an existing intent is one entry in a template's phrase list.
     */
    private enum class TimeSlot { NONE, OPTIONAL, REQUIRED }
    private class TargetTemplate(val phrases: List<String>, val time: TimeSlot = TimeSlot.NONE, val build: (TargetRef.Named, TemporalIntent?) -> VirlinCommand)
    /** Leading-phrase templates, tried in order (a longer phrase must precede its prefix). */
    private val targetTemplates: List<TargetTemplate> = listOf(
        TargetTemplate(listOf("focus on", "focus", "switch back to", "switch to", "go back to", "back to", "return to", "continue")) { t, _ -> Control.FocusStream(t) },
        TargetTemplate(listOf("resume")) { t, _ -> Control.ResumeStream(t) },
        TargetTemplate(listOf("hand off", "handoff"), TimeSlot.OPTIONAL) { t, at -> Control.HandOffStream(t, at) },
        TargetTemplate(listOf("leave"), TimeSlot.OPTIONAL) { t, at -> Control.LeaveStream(t, at) },
        TargetTemplate(listOf("check"), TimeSlot.REQUIRED) { t, at -> Control.CheckStream(t, at!!) },
        TargetTemplate(listOf("remind me about", "bring back"), TimeSlot.REQUIRED) { t, at -> Control.RemindStream(t, at!!) },
        TargetTemplate(listOf("block")) { t, _ -> Control.BlockStream(t) },
        TargetTemplate(listOf("work on")) { t, _ -> Control.SetCurrentTask(TargetRef.CurrentStream, t) },
        TargetTemplate(listOf("complete", "finish")) { t, _ -> Control.Complete(t) },
        TargetTemplate(listOf("cancel")) { t, _ -> Control.CancelTask(t) },
        TargetTemplate(listOf("open", "show")) { t, _ -> Navigate.Open(t) }
    )
    /** Every leading verb phrase — used to refuse "… and <verb> …" chains (one action at a time). */
    private val actionPhrases: List<String> = targetTemplates.flatMap { it.phrases } + listOf("set", "make", "mark", "let", "come back to", "bring", "result ready", "remember", "create", "capture")
    /** Templates whose target sits in the MIDDLE or before a suffix; group 1 is the target, later groups an optional time tail. */
    private class SuffixTemplate(val regex: Regex, val prefixFor: (String) -> String, val build: (TargetRef.Named, TemporalIntent?) -> TextInterpretation)
    private val timeTail = """(?:,? (?:and )?check(?: again)?)?(?: (for|in|after|until|till|at))? (.+)"""
    private val contextual = setOf("this", "current", "it", "here", "this one", "the current one")
    /** Bulk words are never a target: no mass mutation, no "nearest" action. */
    private val massWords = setOf("everything", "all", "all tasks", "all my tasks", "all of them", "them all", "the rest", "all workstreams", "all streams", "all projects", "every task")

    /** Attention controls. Explicit / contextual forms first; then the natural target templates; then the rest. */
    private inner class ControlGrammar {
        fun parse(raw: String, lower: String): TextInterpretation? {
            // ---- explicit entity words stay as optional disambiguating forms
            payloadAfter(raw, lower, "focus on workstream", "focus workstream", "switch to workstream", "focus on stream", "focus stream")?.let { p ->
                if (p.isNotBlank()) return TextInterpretation.Parsed(Control.FocusStream(nameRef(p))) }
            payloadAfter(raw, lower, "focus on task", "focus task", "switch to task", "work on task")?.let { p ->
                if (p.isNotBlank()) return TextInterpretation.Parsed(Control.SetCurrentTask(TargetRef.CurrentStream, nameRef(p))) }
            payloadAfter(raw, lower, "open project", "show project")?.let { p -> if (p.isNotBlank()) return TextInterpretation.Parsed(Navigate.Open(nameRef(p))) }
            // ---- contextual focus / resume
            payloadAfter(raw, lower, "focus on", "focus")?.let { p ->
                if (p.isBlank()) return TextInterpretation.Invalid("Focus what?", "focus <workstream or task>")
                if (lowerOf(p) == "result now") return TextInterpretation.Parsed(Control.ResultReadyNow(TargetRef.ThisStream))
                if (lowerOf(p) in contextual) return TextInterpretation.Parsed(Control.FocusStream(streamRef(p)))
            }
            payloadAfter(raw, lower, "resume")?.let { p ->
                if (p.isBlank()) return TextInterpretation.Parsed(Control.ResumeStream(TargetRef.ThisStream))
                if (lowerOf(p) in contextual) return TextInterpretation.Parsed(Control.ResumeStream(streamRef(p)))
            }
            // ---- leave (current Focus only): "for <duration>" stays relative, "until <time>" is a calendar target
            Regex("""^(leave(?: this| current)?)(?: (for|in|until|till) (.+))?$""").matchEntire(lower)?.let { m ->
                val (head, kw, expr) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                if (expr.isEmpty()) return TextInterpretation.Parsed(Control.LeaveCurrent(returnAt = null))
                return temporal(expr, kw, "$head $kw") { TextInterpretation.Parsed(Control.LeaveCurrent(it)) }
            }
            // ---- hand off (current Focus only), incl. "and check in D" / "let this run" / "leave this running"
            Regex("""^(hand(?: this| it)? ?off(?: this| current| it)?|let (?:this|it) run|leave (?:this|it) running)(?:,? (?:and )?check(?: again)?)?(?: (?:(for|in|after|until|till|at) (.+)|with no check|no check))?$""").matchEntire(lower)?.let { m ->
                val (head, kw, expr) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                if (expr.isEmpty()) return TextInterpretation.Parsed(Control.HandOffCurrent(checkAt = null))
                return temporal(expr, kw, "$head $kw") { TextInterpretation.Parsed(Control.HandOffCurrent(it)) }
            }
            // ---- external check outcomes (contextual "this" stream)
            Regex("""^(?:still (?:running|working|processing|going)|it's still (?:running|working|processing))(?:,? (?:and )?check(?: again)?)?(?: (for|in|after|until|till|at) (.+))?$""").matchEntire(lower)?.let { m ->
                val (kw, expr) = m.groupValues[1] to m.groupValues[2]
                if (expr.isEmpty()) return TextInterpretation.Invalid("How long until the next check?", "still running, check in 10 minutes")
                return temporal(expr, kw, "still running, check $kw") { TextInterpretation.Parsed(Control.StillRunning(TargetRef.ThisStream, it)) }
            }
            Regex("""^check (?:this|it)(?: again)?(?: (in|at|after|for))? (.+)$""").matchEntire(lower)?.let { m ->
                val kw = m.groupValues[1]
                return temporal(m.groupValues[2], kw, "check this" + (if (kw.isEmpty()) "" else " $kw")) { TextInterpretation.Parsed(Control.CheckStream(TargetRef.ThisStream, it)) } }
            if (lower in setOf("focus it now", "focus this now", "focus now")) return TextInterpretation.Parsed(Control.ResultReadyNow(TargetRef.ThisStream))
            Regex("""^remind me(?: about (?:this|it))?(?: (in|at|after))? (.+)$""").matchEntire(lower)?.let { m ->
                val kw = m.groupValues[1]
                if (lower.startsWith("remind me about ") && !lower.startsWith("remind me about this") && !lower.startsWith("remind me about it")) return@let
                return temporal(m.groupValues[2], kw, "remind me" + (if (kw.isEmpty()) "" else " $kw")) { TextInterpretation.Parsed(Control.RemindStream(TargetRef.ThisStream, it)) } }
            Regex("""^check again(?: (in|at))? (.+)$""").matchEntire(lower)?.let { m ->
                val kw = m.groupValues[1]
                return temporal(m.groupValues[2], kw, "check again" + (if (kw.isEmpty()) "" else " $kw")) { TextInterpretation.Parsed(Control.StillRunning(TargetRef.ThisStream, it)) } }
            Regex("""^(result (?:is )?ready,? remind me|remind me about the result)(?: (in|at))? (.+)$""").matchEntire(lower)?.let { m ->
                val kw = m.groupValues[2]
                return temporal(m.groupValues[3], kw, m.groupValues[1] + (if (kw.isEmpty()) "" else " $kw")) { TextInterpretation.Parsed(Control.ResultReadyLater(TargetRef.ThisStream, it)) } }
            if (lower in setOf("result ready", "result is ready", "focus result now", "the result is ready")) return TextInterpretation.Parsed(Control.ResultReadyNow(TargetRef.ThisStream))
            // ---- block (contextual); named block is a target template (WorkStream only, resolver-enforced)
            if (lower in setOf("block this", "mark this blocked", "blocked", "block current", "mark this as blocked", "this is blocked", "it's blocked", "it is blocked")) return TextInterpretation.Parsed(Control.BlockStream(TargetRef.ThisStream))
            // ---- tasks (explicit / contextual)
            if (lower in setOf("complete current task", "complete this task", "finish current task", "finish this task")) return TextInterpretation.Parsed(Control.CompleteTask(TargetRef.CurrentTask))
            if (lower in setOf("cancel current task", "cancel this task")) return TextInterpretation.Parsed(Control.CancelTask(TargetRef.CurrentTask))
            payloadAfter(raw, lower, "complete task", "finish task")?.let { p -> if (p.isNotBlank()) return TextInterpretation.Parsed(Control.CompleteTask(nameRef(p))) }
            payloadAfter(raw, lower, "cancel task")?.let { p -> if (p.isNotBlank()) return TextInterpretation.Parsed(Control.CancelTask(nameRef(p))) }
            // ---- whole WorkStream: explicit wording only, confirmation-gated downstream
            if (lower in setOf("complete current workstream", "finish current workstream")) return TextInterpretation.Parsed(Control.CompleteStream(TargetRef.CurrentStream))
            if (lower in setOf("complete this workstream", "finish this workstream")) return TextInterpretation.Parsed(Control.CompleteStream(TargetRef.ThisStream))
            payloadAfter(raw, lower, "complete workstream", "finish workstream")?.let { p -> if (p.isNotBlank()) return TextInterpretation.Parsed(Control.CompleteStream(nameRef(p))) }
            if (lower in setOf("complete", "done", "finish", "complete this", "finish this")) return TextInterpretation.Invalid(
                "Complete what? Say \"complete current task\" or \"complete current workstream\".")
            if (lower in setOf("cancel", "open", "show", "leave it", "switch to", "go back to", "back to", "return to", "work on", "continue", "check", "block", "hand off", "remind me about")) return TextInterpretation.Invalid(
                "${lower.replaceFirstChar { it.uppercase() }} what?", "$lower <name>")
            // ---- Control 1+2: natural [ACTION] + [TARGET] [+ TIME]
            return targetTemplate(raw, lower)
        }

        // ================================================================ Control 1+2 target templates

        /** `set X as current` / `make X current` / `mark X done|blocked` / `X is still running…` / `X is ready…` / `result ready for X…` / `bring X back…` / `come back to X…` / `leave X running…` / `let X run…` */
        private val suffixTemplates: List<SuffixTemplate> = listOf(
            SuffixTemplate(Regex("""^(?:set (.+) as(?: the)? current(?: task)?|make (.+?)(?: the)? current(?: task)?)$"""), { "" }) { t, _ -> TextInterpretation.Parsed(Control.SetCurrentTask(TargetRef.CurrentStream, t)) },
            SuffixTemplate(Regex("""^mark (.+?) (?:as )?(?:done|complete|completed|finished)$"""), { "" }) { t, _ -> TextInterpretation.Parsed(Control.Complete(t)) },
            SuffixTemplate(Regex("""^(?:mark (.+?) (?:as )?blocked|(.+?) is blocked)$"""), { "" }) { t, _ -> TextInterpretation.Parsed(Control.BlockStream(t)) },
            SuffixTemplate(Regex("""^leave (.+?) running(?:$timeTail)?$"""), { "leave $it running" }) { t, at -> TextInterpretation.Parsed(Control.HandOffStream(t, at)) },
            SuffixTemplate(Regex("""^let (.+?) (?:run|keep running|continue)(?:$timeTail)?$"""), { "let $it run" }) { t, at -> TextInterpretation.Parsed(Control.HandOffStream(t, at)) },
            SuffixTemplate(Regex("""^(.+?) is still (?:running|working|processing|going)(?:$timeTail)?$"""), { "$it is still running, check" }) { t, at ->
                if (at == null) TextInterpretation.Invalid("How long until the next check?", "${t.name} is still running, check in 10 minutes") else TextInterpretation.Parsed(Control.StillRunning(t, at)) },
            SuffixTemplate(Regex("""^focus (.+?),? (?:the )?result(?: is)? ready$"""), { "" }) { t, _ -> TextInterpretation.Parsed(Control.ResultReadyNow(t)) },
            SuffixTemplate(Regex("""^(?:result(?: is)? ready for (.+?)|(.+?) (?:is ready|result is ready|is done processing|has finished|finished processing))(?:,? (?:and )?(?:focus(?: it| this)? now|remind me(?: (in|at|after))? (.+)))?$"""), { "result ready for $it, remind me" }) { t, at ->
                TextInterpretation.Parsed(if (at != null) Control.ResultReadyLater(t, at) else Control.ResultReady(t)) },
            SuffixTemplate(Regex("""^(?:bring (.+?) back|come back to (.+?))(?: (in|at|after))? (.+)$"""), { "bring $it back" }) { t, at -> TextInterpretation.Parsed(Control.RemindStream(t, at!!)) }
        )
        private val readyFocusNow = Regex(""",? (?:and )?focus(?: it| this)? now$""")
        private val handOffCheck = Regex(""",?\s(?:and\s)?check(?:\sagain)?(?=\s|$)""")
        private val noCheck = Regex(""",? (?:with no check|no check|without a check)$""")
        private val pastWords = listOf(" yesterday", " ago", " last night", " last week", " earlier")

        private fun targetTemplate(raw: String, lower: String): TextInterpretation? {
            // A. "X is ready, focus now" — the focus modifier is not a time tail
            readyFocusNow.find(lower)?.let { m ->
                val head = lower.substring(0, m.range.first)
                Regex("""^(?:result(?: is)? ready for (.+)|(.+?) (?:is ready|result is ready|has finished|finished processing))$""").matchEntire(head)?.let { h ->
                    return TextInterpretation.Parsed(Control.ResultReadyNow(named(raw, h.groups[1] ?: h.groups[2]!!)))
                }
            }
            // B. suffix / middle templates
            for (t in suffixTemplates) {
                val m = t.regex.matchEntire(lower) ?: continue
                val g = m.groups[1] ?: m.groups[2] ?: continue
                val target = named(raw, g)
                guard(target.name.lowercase())?.let { return it }
                val n = m.groups.size
                val exprGroup = if (n >= 4) m.groups[n - 1] else null
                val kw = if (n >= 4) m.groups[n - 2]?.value?.takeIf { it in setOf("for", "in", "after", "until", "till", "at") } ?: "" else ""
                if (exprGroup == null || exprGroup.value.isEmpty()) return t.build(target, null)
                val prefix = t.prefixFor(target.name)
                return temporal(exprGroup.value, kw, if (kw.isEmpty()) prefix else "$prefix $kw") { at -> t.build(target, at) }
            }
            // C. leading-phrase templates
            for (t in targetTemplates) {
                val p = payloadAfter(raw, lower, *t.phrases.toTypedArray()) ?: continue
                val phrase = t.phrases.first { lower.startsWith(it) }
                if (p.isBlank() || lowerOf(p) in contextual) return null                     // bare / contextual forms belong to the explicit grammar above
                if (t.time == TimeSlot.NONE) { guard(lowerOf(p))?.let { return it }; return TextInterpretation.Parsed(t.build(TargetRef.Named(unquote(p)), null)) }
                var body = p; var wantsNoCheck = false
                noCheck.find(body.lowercase())?.let { body = body.substring(0, it.range.first); wantsNoCheck = true }
                val checkAt = handOffCheck.find(body.lowercase())
                val (headRaw, tail) = if (checkAt != null) body.substring(0, checkAt.range.first) to body.substring(checkAt.range.last + 1).trim() else splitTime(body)
                val target = TargetRef.Named(unquote(headRaw.trim().removeSuffix(" again").removeSuffix(" Again")))   // "check X again in D"
                guard(target.name.lowercase())?.let { return it }
                if (pastWords.any { target.name.lowercase().endsWith(it) }) return TextInterpretation.Invalid("That time has already passed — choose a time ahead", "$phrase ${target.name.substringBeforeLast(' ')} in 10 minutes")
                if (tail.isEmpty()) return if (t.time == TimeSlot.REQUIRED) TextInterpretation.Invalid("${phrase.replaceFirstChar { it.uppercase() }} ${target.name} when?", "$phrase ${target.name} in 10 minutes")
                    else TextInterpretation.Parsed(t.build(target, null))
                if (wantsNoCheck) return TextInterpretation.Invalid("Say either a check time or \"no check\", not both")
                val (kw, expr) = keywordOf(tail)
                val prefix = "$phrase ${target.name}" + (if (checkAt != null) " and check" else "") + (if (kw.isEmpty()) "" else " $kw")
                return temporal(expr, kw, prefix) { at -> TextInterpretation.Parsed(t.build(target, at)) }
            }
            return null
        }
        private fun named(raw: String, g: MatchGroup) = TargetRef.Named(unquote(raw.substring(g.range.first, g.range.last + 1)))
        /** Refuse bulk words and "… and <verb> …" chains before any resolution. */
        private fun guard(target: String): TextInterpretation? {
            if (target in massWords) return TextInterpretation.Unsupported("Virlin changes one thing at a time — name the WorkStream or task.")
            val chained = Regex("""\b(?:and|then)\s+(.+)$""").find(target)?.groupValues?.get(1)
            if (chained != null && actionPhrases.any { chained == it || chained.startsWith("$it ") }) return TextInterpretation.Unsupported("Do one Virlin action at a time.")
            return null
        }
        /**
         * Split "<target> [kw] <time…>" at the LAST time keyword, else at the longest tail the time
         * parser understands ("Check Claude Build tomorrow morning"). Empty tail = no time given.
         */
        private fun splitTime(body: String): Pair<String, String> {
            // Every split point left → right: "<head> [kw] <tail>". The first tail the time layer RESOLVES wins
            // ("until Monday at 10 AM" splits at "until"; "tomorrow at 4:00 PM" keeps "tomorrow"); failing that,
            // the first it can CLARIFY; otherwise there is no time in the text.
            val lower = body.lowercase(); val words = body.split(' ')
            var clarify: Pair<String, String>? = null
            var offset = 0
            for (i in 1 until words.size) {
                offset += words[i - 1].length + 1
                val tail = body.substring(offset); val (kw, expr) = keywordOf(tail)
                val parsed = time.parse(if (kw == "for" || kw == "in" || kw == "after") "in $expr" else expr)
                if (parsed is TimeParse.Resolved) return body.substring(0, offset - 1) to tail
                if (parsed is TimeParse.Clarify && clarify == null) clarify = body.substring(0, offset - 1) to tail
            }
            check(lower.isNotEmpty())
            return clarify ?: (body to "")
        }
        private fun keywordOf(tail: String): Pair<String, String> {
            val m = Regex("""^(for|in|after|until|till|at) (.+)$""").matchEntire(tail.lowercase()) ?: return "" to tail
            return m.groupValues[1] to tail.substring(m.groups[2]!!.range.first)
        }
        private fun streamRef(p: String): TargetRef = when (lowerOf(p)) { "this" -> TargetRef.ThisStream; "current" -> TargetRef.CurrentStream; else -> nameRef(p) }
        /**
         * One time phrase → typed [TemporalIntent] through the injected [TimeExpressionParser].
         * "for <x>" and "in <x>" are relative; anything else is a calendar target. A temporal
         * clarification carries full rewritten commands ("<prefix> tomorrow at 3:00 PM") as candidates.
         */
        private inline fun temporal(expr: String, keyword: String, prefix: String, ok: (TemporalIntent) -> TextInterpretation): TextInterpretation {
            val phrase = if (keyword == "for" || keyword == "in") "in $expr" else expr
            return when (val r = time.parse(phrase)) {
                is TimeParse.Resolved -> ok(r.intent)
                is TimeParse.Unsupported -> TextInterpretation.Invalid(r.reason, "e.g. $prefix 10 minutes, $prefix 5 PM, $prefix tomorrow morning")
                is TimeParse.Clarify -> {
                    val c = r.clarification
                    val head = prefix.trim().removeSuffix(" in").removeSuffix(" for").removeSuffix(" at").removeSuffix(" until").removeSuffix(" till")
                    val joiner = if (prefix.trim().endsWith(" until") || prefix.trim().endsWith(" till")) " until " else if (prefix.trim().endsWith(" at") || prefix.trim().endsWith(" in")) " " else " "
                    val candidates = c.suggestions.map { sg -> Clarification.Candidate(value = "$head$joiner${sg.phrase}".replace("  ", " "), title = sg.label) }
                        .filter { interpret(it.value) is TextInterpretation.Parsed }
                    TextInterpretation.NeedsTime(Clarification(c.question, kindOf(c.kind), candidates,
                        refill = { text -> (interpret(text) as? TextInterpretation.Parsed)?.command ?: error("candidate no longer parses") }))
                }
            }
        }
        private fun kindOf(k: TemporalClarification.Kind) = when (k) {
            TemporalClarification.Kind.TIME_REQUIRED -> Clarification.Kind.TIME_REQUIRED
            TemporalClarification.Kind.AM_PM_REQUIRED -> Clarification.Kind.AM_PM_REQUIRED
            TemporalClarification.Kind.TIME_ALREADY_PASSED -> Clarification.Kind.TIME_ALREADY_PASSED
            TemporalClarification.Kind.DAYPART_ALREADY_PASSED -> Clarification.Kind.DAYPART_ALREADY_PASSED
            TemporalClarification.Kind.INVALID_LOCAL_TIME, TemporalClarification.Kind.TOO_FAR -> Clarification.Kind.INVALID_LOCAL_TIME
        }
    }

}

// ================================================================ helpers (pure string work)

/** Payload after the first matching keyword, if the text starts with it (keyword + end or space). */
private fun payloadAfter(raw: String, lower: String, vararg keywords: String): String? {
    for (k in keywords) {
        if (lower == k) return ""
        if (lower.startsWith("$k ")) return raw.substring(k.length).trim()
    }
    return null
}
private fun nameRef(p: String): TargetRef = TargetRef.ByName(unquote(p))
private fun lowerOf(p: String) = unquote(p).lowercase()
/** Wrapped in ONE pair of quotes (a quoted title followed by more quoted text is not one payload). */
private fun isQuoted(s: String): Boolean {
    if (s.length < 2) return false
    val pairs = listOf('"' to '"', '“' to '”', '\'' to '\'')
    return pairs.any { (o, c) -> s.first() == o && s.last() == c && s.count { it == o || it == c } == 2 }
}
/** Strip ONE pair of wrapping quotes; inner text (case, spacing, newlines) is untouched. */
internal fun unquote(s: String): String { val t = s.trim(); return if (isQuoted(t)) t.substring(1, t.length - 1) else t }
/** Collapse runs of spaces outside quotes; quoted payloads keep their internal spacing. */
private fun collapseOutsideQuotes(s: String): String {
    val out = StringBuilder(); var inQuote = false; var lastSpace = false
    for (ch in s) {
        if (ch == '"') { inQuote = !inQuote; out.append(ch); lastSpace = false; continue }
        if (!inQuote && ch == ' ') { if (!lastSpace) out.append(ch); lastSpace = true } else { out.append(ch); lastSpace = false }
    }
    return out.toString()
}


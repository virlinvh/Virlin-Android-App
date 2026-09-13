package com.virlin.app.command

import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.command.Clarification
import com.virlin.app.domain.command.CommandContext
import com.virlin.app.domain.command.CommandEngine
import com.virlin.app.domain.command.CommandEngine.Outcome
import com.virlin.app.domain.command.CommandResolution
import com.virlin.app.domain.command.CommandResult
import com.virlin.app.domain.command.EntityKind
import com.virlin.app.domain.command.ResolvedCommand
import com.virlin.app.domain.command.TargetRef
import com.virlin.app.domain.command.TaskOwnerRef
import com.virlin.app.domain.command.VirlinCommand.Capture
import com.virlin.app.domain.command.VirlinCommand.Control
import com.virlin.app.domain.command.VirlinCommand.Create
import com.virlin.app.domain.command.text.TextCommandInterpreter
import com.virlin.app.domain.command.text.TextInterpretation
import com.virlin.app.domain.command.time.TimeExpressionParser
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskHierarchy
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamMode.EXTERNAL
import com.virlin.app.domain.model.WorkStreamMode.HUMAN
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.FOCUS
import com.virlin.app.domain.model.WorkStreamState.READY
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Create V1 — natural structure creation: explicit entity word, whole-text titles, owner split at
 * approved delimiters only, kind-less owner resolved by the resolver (Project / WorkStream / Task),
 * mode never inferred, typed clarifications that continue the SAME pending Create (title, chosen
 * ids and mode preserved), and the existing Create preview before any mutation.
 */
class CreateNaturalTest {

    private val t0: Instant = Instant.parse("2026-09-12T10:00:00Z")
    private val zone = java.time.ZoneId.of("Asia/Kolkata")

    private inner class World(streams: List<WorkStream>, projects: List<Project>, tasks: List<Task>) {
        val clock = FakeClock(t0)
        val repo = InMemoryWorkStreamRepository(streams, projects, tasks)
        val engine = CommandEngine(DefaultVirlinActions(repo, clock, SequentialIdProvider()), repo, clock, zone)
        val interpreter = TextCommandInterpreter(TimeExpressionParser(clock, zone))
        fun interpret(t: String) = interpreter.interpret(t)
        fun parse(t: String) = (interpret(t) as TextInterpretation.Parsed).command
        fun resolve(t: String, ctx: CommandContext = CommandContext.None) = engine.resolve(parse(t), ctx)
        fun preview(t: String, ctx: CommandContext = CommandContext.None) = (resolve(t, ctx) as CommandResolution.Ready).command
        fun clarify(t: String, ctx: CommandContext = CommandContext.None) = (resolve(t, ctx) as CommandResolution.NeedsClarification).clarification
        /** The accepted Create flow: resolve → preview (Ready) → accept → execute. */
        suspend fun create(t: String, ctx: CommandContext = CommandContext.None): CommandResult.Executed = (engine.execute(preview(t, ctx)) as CommandResult.Executed)
        fun tasks() = repo.tasks.value; fun streams() = repo.streams.value; fun projects() = repo.projects.value
        fun task(title: String) = tasks().single { it.title == title }
        fun stream(title: String) = streams().single { it.title == title }
    }

    private fun ws(id: String, title: String, project: String? = null, mode: WorkStreamMode = HUMAN, state: WorkStreamState = READY, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, mode = mode, activeTaskId = active, createdAt = t0, updatedAt = t0)
    private fun task(id: String, title: String, stream: String?, project: String? = null, parent: String? = null) =
        Task(id = id, title = title, projectId = project, workStreamId = stream, parentTaskId = parent, createdAt = t0, updatedAt = t0)

    /**
     * Projects: Virlin Android App (p1) · Psychology (p2) · Career (p3).
     * Streams: Claude Build (s1, EXTERNAL, p1) · Psychology (s2, HUMAN, p2, FOCUS, active t1) · Personal Notes (s3, projectless).
     * Tasks: Notification Receiver (t1, s2) · Receiver (t2, s1) · Psychology (t3, s2, child of t1) ·
     *        deep chain under s1: Agent Development (t4) → Natural Language (t5) → Control (t6) → Temporal (t7) → Parser (t8) ·
     *        Contact HR (t9, standalone in p3).
     */
    private fun world(extraStreams: List<WorkStream> = emptyList(), extraProjects: List<Project> = emptyList(), extraTasks: List<Task> = emptyList()) = World(
        listOf(ws("s1", "Claude Build", "p1", EXTERNAL), ws("s2", "Psychology", "p2", HUMAN, FOCUS, active = "t1"), ws("s3", "Personal Notes")) + extraStreams,
        listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0), Project("p2", "Psychology", createdAt = t0, updatedAt = t0), Project("p3", "Career", createdAt = t0, updatedAt = t0)) + extraProjects,
        listOf(task("t1", "Notification Receiver", "s2", "p2"), task("t2", "Receiver", "s1", "p1"), task("t3", "Psychology", "s2", "p2", parent = "t1"),
            task("t4", "Agent Development", "s1", "p1"), task("t5", "Natural Language", "s1", "p1", "t4"), task("t6", "Control", "s1", "p1", "t5"),
            task("t7", "Temporal", "s1", "p1", "t6"), task("t8", "Parser", "s1", "p1", "t7"), task("t9", "Contact HR", null, "p3")) + extraTasks)

    private fun named(s: String) = TargetRef.Named(s)

    // ================================================================ parsing (templates, titles kept whole, owner split, entity word required)
    @Test fun templates_parse_with_whole_titles() {
        val w = world()
        assertEquals(Create.CreateProject("Psychology"), w.parse("Create project Psychology"))
        assertEquals(Create.CreateProject("Virlin Android App"), w.parse("Create a project called Virlin Android App"))
        assertEquals(Create.CreateProject("MBA Research"), w.parse("New project MBA Research"))
        assertEquals(Create.CreateProject("Placement Training"), w.parse("Add project Placement Training"))
        assertEquals(Create.CreateProject("Learning and Development Plan"), w.parse("Create project Learning and Development Plan"))
        assertEquals(Create.CreateWorkStream("Claude Build", null, EXTERNAL), w.parse("Create external workstream Claude Build"))
        assertEquals(Create.CreateWorkStream("Psychology Unit 23", null, HUMAN), w.parse("Create human workstream Psychology Unit 23"))
        assertEquals(Create.CreateWorkStream("Codex MBA Research", null, EXTERNAL), w.parse("New external workstream Codex MBA Research"))
        assertEquals(Create.CreateWorkStream("Claude Build", null, null), w.parse("Create workstream Claude Build"))                    // no inference from "Claude"
        assertEquals(Create.CreateWorkStream("Antigravity Fix", null, null), w.parse("Add workstream Antigravity Fix"))
        assertEquals(Create.CreateWorkStream("Claude Build", named("Virlin Android App"), EXTERNAL), w.parse("Create external workstream Claude Build under Virlin Android App"))
        assertEquals(Create.CreateWorkStream("Unit 23", named("MSc Psychology"), HUMAN), w.parse("Create human workstream Unit 23 under MSc Psychology"))
        assertEquals(Create.CreateWorkStream("Placement Preparation", named("Career"), null), w.parse("Create workstream Placement Preparation under Career"))
        assertEquals(Create.CreateWorkStream("Research", named("MBA"), null), w.parse("Add workstream Research to MBA"))
        assertEquals(Create.CreateTask("Notification Receiver", TaskOwnerRef.WorkStream(TargetRef.ThisStream)), w.parse("Create task Notification Receiver"))
        assertEquals(Create.CreateTask("Unit 23 Questions", TaskOwnerRef.WorkStream(TargetRef.ThisStream)), w.parse("Add task Unit 23 Questions"))
        assertEquals(Create.CreateTask("Grammar Parser", TaskOwnerRef.WorkStream(TargetRef.ThisStream)), w.parse("New task Grammar Parser"))
        assertEquals(Create.CreateTask("Notification Receiver", TaskOwnerRef.Any(named("Claude Build"))), w.parse("Create task Notification Receiver under Claude Build"))
        assertEquals(Create.CreateTask("Unit 23 Questions", TaskOwnerRef.Any(named("Psychology"))), w.parse("Create task Unit 23 Questions in Psychology"))
        assertEquals(Create.CreateTask("API Testing", TaskOwnerRef.Any(named("Virlin Development"))), w.parse("Add task API Testing to Virlin Development"))
        assertEquals(Create.CreateTask("Retry Logic", TaskOwnerRef.ParentTask(named("Notification Receiver"))), w.parse("Create subtask Retry Logic under Notification Receiver"))
        assertEquals(Create.CreateTask("Error Handling", TaskOwnerRef.ParentTask(named("Receiver"))), w.parse("Add subtask Error Handling to Receiver"))
        assertEquals(Create.CreateTask("Validation", TaskOwnerRef.ParentTask(TargetRef.ThisTask)), w.parse("Create subtask Validation"))
        // command words inside a title are data
        assertEquals(Create.CreateTask("Complete Notification Testing", TaskOwnerRef.WorkStream(TargetRef.ThisStream)), w.parse("Create task Complete Notification Testing"))
        assertEquals(Create.CreateTask("Check Receiver State", TaskOwnerRef.Any(named("Claude Build"))), w.parse("Create task Check Receiver State under Claude Build"))
        // entity word required — never guessed
        val i = w.interpret("Create Psychology"); assertTrue(i is TextInterpretation.Invalid); assertTrue((i as TextInterpretation.Invalid).reason.contains("project, a workstream or a task"))
        assertTrue(w.interpret("Create a project") is TextInterpretation.Invalid)
        assertTrue(w.interpret("Create task") is TextInterpretation.Invalid)
    }

    // ================================================================ project (1–6)
    @Test fun projects_preview_then_create() = runTest {
        val w = world()
        val p = w.preview("Create a project called Learning and Development Plan") as ResolvedCommand.CreateProject
        assertEquals("Learning and Development Plan", p.title); assertEquals(3, w.projects().size)                          // preview only
        assertEquals("Created project · Learning and Development Plan", w.create("Create a project called Learning and Development Plan").summary)
        assertTrue(w.projects().any { it.title == "Learning and Development Plan" })
        w.create("New project MBA Research"); w.create("Add project Placement Training")
        assertEquals(listOf("MBA Research", "Placement Training"), w.projects().takeLast(2).map { it.title })
    }

    // ================================================================ workstream (7–14, 41–43)
    @Test fun workstreams_mode_explicit_or_asked_never_inferred() = runTest {
        val w = world()
        w.create("Create external workstream Claude Build 2")
        w.stream("Claude Build 2").let { assertEquals(EXTERNAL, it.mode); assertNull(it.projectId) }                 // projectless
        w.create("Create human workstream Claude Work"); assertEquals(HUMAN, w.stream("Claude Work").mode)               // explicit wins over "Claude"
        w.create("Create external workstream Psychology Reading"); assertEquals(EXTERNAL, w.stream("Psychology Reading").mode)
        // mode omitted → asks; choice continues the SAME command (title preserved)
        val c = w.clarify("Create workstream Claude Build 3")
        assertEquals(Clarification.Kind.MISSING_MODE, c.kind); assertEquals(listOf("HUMAN", "EXTERNAL"), c.candidates.map { it.value })
        assertEquals(Create.CreateWorkStream("Claude Build 3", null, EXTERNAL), c.choose("EXTERNAL"))
        val o = w.engine.choose(c, "EXTERNAL")
        assertTrue(o is Outcome.Done); assertEquals(EXTERNAL, w.stream("Claude Build 3").mode)
        // project-backed
        w.create("Create external workstream Claude Build 4 under Virlin Android App"); assertEquals("p1", w.stream("Claude Build 4").projectId)
        w.create("Create human workstream Unit 23 under Psychology"); assertEquals("p2", w.stream("Unit 23").projectId)      // Project only: the Psychology WorkStream/Task are not owners
        val c2 = w.clarify("Create workstream Testing under Career")
        assertEquals(Clarification.Kind.MISSING_MODE, c2.kind)
        assertEquals(Create.CreateWorkStream("Testing", TargetRef.ById("p3"), HUMAN), c2.choose("HUMAN"))                 // owner already resolved
        w.engine.choose(c2, "HUMAN"); w.stream("Testing").let { assertEquals("p3", it.projectId); assertEquals(HUMAN, it.mode) }
        assertEquals(Clarification.Kind.TARGET_NOT_FOUND, w.clarify("Create workstream X under Nowhere").kind)
    }

    // ================================================================ project owner ambiguity + multi-step (15–17, 35–37)
    @Test fun project_owner_ambiguity_then_mode_no_reparse() = runTest {
        val w = world(extraProjects = listOf(Project("p4", "Virlin", createdAt = t0, updatedAt = t0), Project("p5", "Virlin", createdAt = t0, updatedAt = t0)),
            extraStreams = listOf(ws("s9", "Virlin")))
        val c = w.clarify("Create external workstream Claude Build 5 under Virlin")
        assertEquals(Clarification.Kind.AMBIGUOUS_TARGET, c.kind); assertEquals(listOf("p4", "p5"), c.candidates.map { it.value })        // WorkStream "Virlin" is not a candidate
        assertEquals(Create.CreateWorkStream("Claude Build 5", TargetRef.ById("p5"), EXTERNAL), c.choose("p5"))
        w.engine.choose(c, "p5"); w.stream("Claude Build 5").let { assertEquals("p5", it.projectId); assertEquals(EXTERNAL, it.mode) }
        // ambiguous Project + missing mode: two steps, title carried through both, no reparse
        val s1 = w.clarify("Create workstream Testing under Virlin")
        assertEquals(Clarification.Kind.AMBIGUOUS_TARGET, s1.kind)
        val step2 = w.engine.resolve(s1.choose("p4")!!) as CommandResolution.NeedsClarification
        assertEquals(Clarification.Kind.MISSING_MODE, step2.clarification.kind)
        assertEquals(Create.CreateWorkStream("Testing", TargetRef.ById("p4"), EXTERNAL), step2.clarification.choose("EXTERNAL"))
        val ready = w.engine.resolve(step2.clarification.choose("EXTERNAL")!!) as CommandResolution.Ready
        val pv = ready.command as ResolvedCommand.CreateWorkStream
        assertEquals("p4", pv.projectId); assertEquals("Testing", pv.title); assertEquals(EXTERNAL, pv.mode)
        w.engine.execute(pv); assertEquals("p4", w.stream("Testing").projectId)
    }

    // ================================================================ task owners (18–25)
    @Test fun tasks_under_workstream_project_and_task() = runTest {
        val w = world()
        w.create("Create task Notification Layer", CommandContext(selectedStreamId = "s1"))
        w.task("Notification Layer").let { assertEquals("s1", it.workStreamId); assertNull(it.parentTaskId); assertEquals("p1", it.projectId) }
        w.create("Add task Unit 23 Questions", CommandContext(selectedStreamId = "s2")); assertEquals("s2", w.task("Unit 23 Questions").workStreamId)
        // no safe context → asks where (no mutation)
        val before = w.tasks().size
        assertEquals(Clarification.Kind.NO_SELECTED_STREAM, w.clarify("Create task Orphan").kind); assertEquals(before, w.tasks().size)
        // explicit owners
        w.create("Create task Receiver Tests under Claude Build"); w.task("Receiver Tests").let { assertEquals("s1", it.workStreamId); assertNull(it.parentTaskId) }
        w.create("Create task Notes under Career"); w.task("Notes").let { assertNull(it.workStreamId); assertEquals("p3", it.projectId) }   // standalone Project task
        w.create("Create task Retry Logic under Notification Receiver"); w.task("Retry Logic").let { assertEquals("t1", it.parentTaskId); assertEquals("s2", it.workStreamId) }
        w.create("Add task API Tests to Claude Build"); assertEquals("s1", w.task("API Tests").workStreamId)
        w.create("Create task DST Tests under Parser")
        w.task("DST Tests").let { assertEquals("t8", it.parentTaskId); assertEquals("s1", it.workStreamId) }
        assertEquals(listOf("DST Tests", "Parser", "Temporal", "Control", "Natural Language", "Agent Development"), TaskHierarchy.ancestry(w.tasks(), w.task("DST Tests").id)!!.map { it.title })
        // preview shows the owner
        val pv = w.preview("Create task Follow up under Contact HR") as ResolvedCommand.CreateTask
        assertEquals("t9", pv.parentTaskId); assertEquals("Under", pv.preview.fields.last().label)
        // projectless WorkStream owns tasks; child of a standalone Project task stays standalone in that Project
        w.create("Create task Groceries under Personal Notes"); w.task("Groceries").let { assertEquals("s3", it.workStreamId); assertNull(it.projectId) }
        w.create("Create subtask Send CV under Contact HR"); w.task("Send CV").let { assertNull(it.workStreamId); assertEquals("p3", it.projectId); assertEquals("t9", it.parentTaskId) }
    }

    // ================================================================ subtask alias (26–30)
    @Test fun subtask_is_only_language_for_child_task() = runTest {
        val w = world()
        w.create("Create subtask Retry Logic under Notification Receiver"); assertEquals("t1", w.task("Retry Logic").parentTaskId)
        w.create("Add subtask Error Handling to Receiver"); assertEquals("t2", w.task("Error Handling").parentTaskId)
        w.create("Create subtask Validation", CommandContext(selectedStreamId = "s1", selectedTaskId = "t8")); assertEquals("t8", w.task("Validation").parentTaskId)
        w.create("Create subtask Focus Check"); assertEquals("t1", w.task("Focus Check").parentTaskId)                     // FOCUS stream's current task
        val w2 = world(extraStreams = emptyList()).also { it.repo }                                                      // no selection, focus stream without a current task
        val w3 = World(listOf(ws("s2", "Psychology", "p2", HUMAN, FOCUS)), listOf(Project("p2", "Psychology", createdAt = t0, updatedAt = t0)), listOf(task("t1", "Notification Receiver", "s2", "p2")))
        val c = w3.clarify("Create subtask Validation"); assertEquals(Clarification.Kind.NO_CURRENT_TASK, c.kind); assertEquals(listOf("t1"), c.candidates.map { it.value })
        assertEquals(1, w3.tasks().size)
        // a Project or WorkStream is not a subtask owner
        assertEquals(Clarification.Kind.TARGET_NOT_FOUND, w.clarify("Create subtask X under Claude Build").kind)
        // no Subtask / Stage / Step type anywhere in the model
        val model = java.io.File("src/main/java/com/virlin/app/domain/model").walkTopDown().filter { it.extension == "kt" }.joinToString { it.readText() }
        listOf("class Subtask", "class Stage", "class Step", "enum class HierarchyLevel").forEach { assertFalse(it, model.contains(it)) }
        assertTrue(w2.tasks().isNotEmpty())
    }

    // ================================================================ cross-kind owner ambiguity + pending state (31–34, 37)
    @Test fun cross_kind_owner_ambiguity_continues_original_create() = runTest {
        val w = world()
        val c = w.clarify("Create task Notes under Psychology")
        assertEquals(listOf("project:p2" to "Project", "workstream:s2" to "WorkStream", "task:t3" to "Task"), c.candidates.map { it.value to it.kind })
        assertEquals("Psychology → Psychology → Notification Receiver", c.candidates[2].subtitle)
        assertEquals(Create.CreateTask("Notes", TaskOwnerRef.Any(TargetRef.Entity(EntityKind.TASK, "t3"))), c.choose("task:t3"))
        assertTrue(w.engine.choose(c, "task:t3") is Outcome.Done)
        w.task("Notes").let { assertEquals("t3", it.parentTaskId); assertEquals("s2", it.workStreamId) }
        val w2 = world(); w2.engine.execute((w2.engine.resolve(w2.clarify("Create task Notes under Psychology").choose("workstream:s2")!!) as CommandResolution.Ready).command)
        w2.task("Notes").let { assertEquals("s2", it.workStreamId); assertNull(it.parentTaskId) }
        val w3 = world(); w3.engine.execute((w3.engine.resolve(w3.clarify("Create task Notes under Psychology").choose("project:p2")!!) as CommandResolution.Ready).command)
        w3.task("Notes").let { assertNull(it.workStreamId); assertEquals("p2", it.projectId) }
        // identity, not title search: the chosen id resolves even though the title is still ambiguous; the preview carries ids
        val pv = w3.engine.resolve(Create.CreateTask("Testing", TaskOwnerRef.Any(TargetRef.Entity(EntityKind.WORKSTREAM, "s2")))) as CommandResolution.Ready
        assertEquals("s2", (pv.command as ResolvedCommand.CreateTask).workStreamId)
        // Create previews before mutating — a resolved Create is never executed by resolve()
        assertTrue(w3.resolve("Create task Another under Claude Build") is CommandResolution.Ready)
        assertTrue(w3.tasks().none { it.title == "Another" })
    }

    // ================================================================ title safety + control / capture regressions (38–40, 50–54)
    @Test fun titles_are_data_and_control_capture_untouched() = runTest {
        val w = world()
        w.create("Create task Complete Notification Testing", CommandContext(selectedStreamId = "s1"))
        assertEquals("Complete Notification Testing", w.task("Complete Notification Testing").title)
        assertTrue(w.tasks().none { it.status == com.virlin.app.domain.model.TaskStatus.DONE })
        w.create("Create task Check Receiver State under Claude Build"); assertEquals(READY, w.stream("Claude Build").state); assertNull(w.stream("Claude Build").checkAt)
        // control stays exactly Control
        assertEquals(Control.FocusStream(named("Claude Build")), w.parse("Switch to Claude Build"))
        assertTrue(w.parse("Leave Psychology for 10 minutes") is Control.LeaveStream)
        assertTrue(w.parse("Hand off Claude Build and check in 5 minutes") is Control.HandOffStream)
        assertTrue(w.parse("Claude Build is still running, check in 5 minutes") is Control.StillRunning)
        assertTrue(w.parse("Result ready for Claude Build, focus now") is Control.ResultReadyNow)
        assertTrue(w.parse("Block Claude Build") is Control.BlockStream)
        assertEquals(Control.SetCurrentTask(TargetRef.CurrentStream, named("Notification Receiver")), w.parse("Make Notification Receiver current"))
        // capture stays raw
        assertEquals(Capture.CaptureNote("create project Psychology"), w.parse("Remember create project Psychology"))
        assertEquals(Capture.CaptureNote("create task Testing"), w.parse("Remember create task Testing"))
        // safety
        listOf("Don't create project Psychology", "Do not create project Psychology", "Can I create project Psychology?", "Should I create project Psychology?",
            "If I finish this create project Psychology", "Delete project Psychology", "Rename Psychology", "Move Psychology", "Merge Psychology and Career").forEach {
            assertTrue(it, w.interpret(it) is TextInterpretation.Unsupported) }
        assertEquals(3, w.projects().size)
    }
}

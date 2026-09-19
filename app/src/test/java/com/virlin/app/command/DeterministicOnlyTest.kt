package com.virlin.app.command

import com.virlin.app.domain.DemoHierarchySeed
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.command.Clarification
import com.virlin.app.domain.command.CommandContext
import com.virlin.app.domain.command.CommandEngine
import com.virlin.app.domain.command.CommandResolution
import com.virlin.app.domain.command.TargetRef
import com.virlin.app.domain.command.VirlinCommand.Capture
import com.virlin.app.domain.command.VirlinCommand.Control
import com.virlin.app.domain.command.text.TextCommandInterpreter
import com.virlin.app.domain.command.text.TextInterpretation
import com.virlin.app.domain.command.time.TemporalIntent
import com.virlin.app.domain.command.time.TimeExpressionParser
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.FOCUS
import com.virlin.app.domain.model.WorkStreamState.READY
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.agent.command.AgentCommandViewModel
import com.virlin.app.ui.agent.command.CommandPanelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Deterministic-only language reset: the composer text path is
 * `TextCommandInterpreter → VirlinCommand → CommandResolver → CommandExecutor → VirlinActions`
 * and nothing else. `Unsupported` stays `Unsupported`; no second interpreter, provider, model or
 * network can be reached, structurally or behaviourally.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeterministicOnlyTest {

    private val t0: Instant = Instant.parse("2026-09-12T10:00:00Z")
    private val zone = java.time.ZoneId.of("Asia/Kolkata")
    private val dispatcher = StandardTestDispatcher()
    private lateinit var clock: FakeClock
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var engine: CommandEngine
    private lateinit var interpreter: TextCommandInterpreter
    private lateinit var vm: AgentCommandViewModel

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, executionPreference = mode.toPreference(), activeTaskId = active, createdAt = t0, updatedAt = t0)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        clock = FakeClock(t0)
        val streams = listOf(
            ws("s1", "Psychology", FOCUS, null, active = "p_q17"),
            ws("s9", "Testing", READY, "p1"), ws("s10", "Testing", READY, "p2")
        )
        repo = InMemoryWorkStreamRepository(streams,
            listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0), Project("p2", "MBA Project", createdAt = t0, updatedAt = t0)),
            DemoHierarchySeed.tasks(streams, t0))
        engine = CommandEngine(DefaultVirlinActions(repo, clock, SequentialIdProvider()), repo, clock, zone)
        interpreter = TextCommandInterpreter(TimeExpressionParser(clock, zone))
        vm = AgentCommandViewModel(engine, interpreter::interpret)
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun parsed(text: String) = (interpreter.interpret(text) as TextInterpretation.Parsed).command

    // 1. deterministic parse → command pipeline
    @Test fun focus_psychology_is_a_deterministic_command() = runTest(dispatcher) {
        assertEquals(Control.FocusStream(TargetRef.Named("Psychology")), parsed("Focus Psychology"))
        var recognised = false
        vm.submit("focus testing") { recognised = true }; advanceUntilIdle()
        assertTrue(recognised)
        assertTrue(vm.state.value is CommandPanelState.Clarify)
    }

    // 2. unsupported → Unsupported, and the panel shows exactly that (no "unavailable", no loading)
    @Test fun unsupported_stays_unsupported_no_fallback() = runTest(dispatcher) {
        val i = interpreter.interpret("Switch me over to psychology, I'm stepping away for ten")
        assertTrue(i is TextInterpretation.Unsupported)
        assertEquals(TextCommandInterpreter.UnsupportedMessage, (i as TextInterpretation.Unsupported).reason)
        val before = repo.streams.value
        var recognised = false
        vm.submit("plan my entire week") { recognised = true }; advanceUntilIdle()
        assertFalse(recognised)
        val f = vm.state.value as CommandPanelState.Feedback
        assertEquals(TextCommandInterpreter.UnsupportedMessage, f.text)
        assertTrue(f.examples.isNotEmpty())
        assertEquals(before, repo.streams.value)
        assertEquals(1, vm.interpretations)
    }

    // 3. ambiguity is the resolver's, never the parser's
    @Test fun ambiguous_target_clarifies_in_resolver() {
        assertEquals(Control.FocusStream(TargetRef.Named("testing")), parsed("focus testing"))
        val r = engine.resolve(parsed("focus testing"), CommandContext.None)
        val c = (r as CommandResolution.NeedsClarification).clarification
        assertEquals(Clarification.Kind.AMBIGUOUS_TARGET, c.kind)
        assertEquals(setOf("workstream:s9", "workstream:s10"), c.candidates.map { it.value }.toSet())
    }

    // 4. temporal meaning comes from TimeExpressionParser
    @Test fun time_expressions_go_through_time_parser() {
        assertEquals(Control.LeaveCurrent(TemporalIntent.Relative(Duration.ofMinutes(10))), parsed("leave for 10 minutes"))
        val abs = parsed("leave until 5 PM") as Control.LeaveCurrent
        assertEquals(Instant.parse("2026-09-12T11:30:00Z"), (abs.returnAt as TemporalIntent.Absolute).at)   // 17:00 IST
        assertTrue(interpreter.interpret("leave until tomorrow") is TextInterpretation.NeedsTime)
    }

    // 5 + 6. confirmations stay in the resolver
    @Test fun cancel_task_and_complete_workstream_still_confirm() {
        assertTrue(engine.resolve(parsed("cancel current task"), CommandContext.None) is CommandResolution.NeedsConfirmation)
        assertTrue(engine.resolve(parsed("complete current workstream"), CommandContext.None) is CommandResolution.NeedsConfirmation)
    }

    // 7 + 8. capture payloads are data — via the command and via raw CAPTURE mode
    @Test fun capture_payloads_never_execute() = runTest(dispatcher) {
        assertEquals(Capture.CaptureNote("complete current workstream"), parsed("remember complete current workstream"))
        assertEquals(Capture.CapturePrompt("cancel current task"), parsed("capture prompt cancel current task"))
        vm.submit("remember complete current workstream"); advanceUntilIdle()
        assertEquals(FOCUS, repo.getStream("s1")!!.state)
        assertEquals(1, repo.captures.value.count { it.type == CaptureType.NOTE && it.content == "complete current workstream" })
        // CAPTURE mode: raw text is saved through the capture action; no interpretation happens at all.
        val raw = DefaultVirlinActions(repo, clock, SequentialIdProvider()).createCapture(com.virlin.app.domain.action.CreateCapture(CaptureType.NOTE, "complete current workstream", id = "raw_1"))
        assertTrue(raw.toString(), raw is com.virlin.app.domain.action.ActionResult.Success)
        assertEquals(FOCUS, repo.getStream("s1")!!.state)
        assertEquals(1, vm.interpretations)
    }

    // 10. determinism
    @Test fun same_input_same_result() {
        listOf("focus psychology", "leave for 10 minutes", "leave until 5 PM", "remember  keep   this", "plan my week", "create workstream Claude Build").forEach { text ->
            val first = interpreter.interpret(text)
            repeat(5) { assertEquals(text, first.toString(), interpreter.interpret(text).toString()) }
        }
    }

    // 9 + structural guard: nothing in the production command path can reach a provider, model, network or persistence.
    @Test fun production_command_path_has_no_provider_model_or_network() {
        val root = File("src/main/java/com/virlin/app")
        val commandPath = listOf("domain/command", "ui/agent/command").map { File(root, it) }
        val files = commandPath.flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        assertTrue(files.size > 5)
        val forbidden = listOf(
            "LanguageCommandInterpreter", "HybridCommandInterpreter", "FakeLanguageCommandInterpreter", "ProposedCommand", "LanguageContext", "languageFallback",
            "Gemini", "OpenAI", "Anthropic", "OpenRouter", "llama", "LiteRT", "tflite", "gguf", "onnx", "mediapipe",
            "java.net", "okhttp", "retrofit", "ktor", "HttpURLConnection", "Socket(",
            "androidx.room", "com.virlin.app.data", "Dao", "AlarmManager", "NotificationManager", "android.app.Notification"
        )
        // Code only: doc comments legitimately say what a class must NOT touch.
        fun code(f: File) = f.readLines().filterNot { val t = it.trim(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }.joinToString(" ")
        files.forEach { f ->
            val text = code(f)
            forbidden.forEach { word -> assertFalse("${f.path} must not reference $word", text.contains(word)) }
        }
        // The parser itself never touches actions or the executor either (resolver / executor are the only domain callers).
        File(root, "domain/command/text").listFiles()!!.filter { it.extension == "kt" }.forEach { f ->
            listOf("VirlinActions", "CommandExecutor", "CommandEngine", "WorkStreamRepository").forEach { w -> assertFalse("${f.name} must not reference $w", code(f).contains(w)) }
        }
        // No language package, no debug hook, no model asset anywhere in the app module.
        assertFalse(File(root, "domain/command/language").exists())
        assertFalse(File(root, "MainActivity.kt").readText().contains("fakeLanguage"))
        val assets = File("src/main/assets"); if (assets.exists()) assets.walkTopDown().forEach { assertFalse(it.name, it.extension in setOf("gguf", "tflite", "onnx", "bin")) }
    }
}

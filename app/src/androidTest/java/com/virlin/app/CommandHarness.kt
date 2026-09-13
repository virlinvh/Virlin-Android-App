package com.virlin.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.command.CommandEngine.Outcome
import com.virlin.app.domain.command.CommandResult
import com.virlin.app.domain.command.QueryResult
import com.virlin.app.domain.command.TargetRef
import com.virlin.app.domain.command.VirlinCommand.Capture
import com.virlin.app.domain.command.VirlinCommand.Control
import com.virlin.app.domain.command.VirlinCommand.Create
import com.virlin.app.domain.command.VirlinCommand.Query
import com.virlin.app.domain.model.WorkStreamMode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * Developer harness for the Pixel 8 manual proof (Pass 11 §43): drives the PRODUCTION command
 * engine on the device against the real Room database and leaves the state in place for
 * on-screen inspection. Skipped in the suite (runner argument gate); run explicitly with
 * `adb shell am instrument -w -e class com.virlin.app.CommandHarness -e harness true
 * com.virlin.app.test/androidx.test.runner.AndroidJUnitRunner`. Structured commands only — no text, no NLP.
 */
@RunWith(AndroidJUnit4::class)
class CommandHarness {

    private val tag = "VirlinCommandHarness"
    private fun log(step: String, value: Any?) { Log.i(tag, "$step → $value"); println("$step → $value") }

    @Test fun proof_A_to_G() = runBlocking {
        org.junit.Assume.assumeTrue("manual harness — pass -e harness true", InstrumentationRegistry.getArguments().getString("harness") == "true")
        VirlinGraph.init(InstrumentationRegistry.getInstrumentation().targetContext)
        val e = VirlinGraph.commands
        fun out(o: Outcome) = when (o) {
            is Outcome.Done -> when (val r = o.result) { is CommandResult.Executed -> "Executed: ${r.summary}"; is CommandResult.Answered -> "Answered: ${r.result}"; is CommandResult.Rejected -> "Rejected: ${r.reason}"; is CommandResult.Failed -> "Failed: ${r.reason}"; is CommandResult.Navigate -> "Navigate: ${r.destination}" }
            is Outcome.Clarify -> "Clarify: ${o.clarification.question} ${o.clarification.candidates.map { "${it.title} (${it.subtitle})" }}"
            is Outcome.Confirm -> "Confirm: ${o.confirmation.question}"
            is Outcome.Rejected -> "Rejected: ${o.reason}"
        }
        // A. current focus query
        val a = e.submit(Query.GetCurrentFocus)
        log("A GetCurrentFocus", ((a as Outcome.Done).result as CommandResult.Answered).result.let { if (it is QueryResult.CurrentFocus) "${it.project?.title} / ${it.workStream.title} / ${it.activeTask?.title}" else it })
        // B. unique WorkStream by title → focus
        log("B Focus 'Notion Transfer'", out(e.submit(Control.FocusStream(TargetRef.ByName("Notion Transfer")))))
        // C. duplicate titles → clarification, not execution
        log("C create 'Harness Dup' #1", out(e.submit(Create.CreateWorkStream("Harness Dup", null, WorkStreamMode.HUMAN))))
        log("C create 'Harness Dup' #2", out(e.submit(Create.CreateWorkStream("Harness Dup", TargetRef.ByName("Virlin Development"), WorkStreamMode.EXTERNAL))))
        log("C Focus 'Harness Dup'", out(e.submit(Control.FocusStream(TargetRef.ByName("Harness Dup")))))
        // D. Leave +5m through the executor (scheduler follows naturally)
        log("D LeaveCurrent +5m", out(e.submit(Control.LeaveCurrent(Duration.ofMinutes(5)))))
        // E. projectless WorkStream
        log("E CreateWorkStream 'Harness Walk' (no project)", out(e.submit(Create.CreateWorkStream("Harness Walk", null, WorkStreamMode.HUMAN))))
        // F. capture note
        log("F CaptureNote", out(e.submit(Capture.CaptureNote("Harness note via command executor"))))
        // G. complete whole WorkStream → confirmation first, then confirm
        val g = e.submit(Control.CompleteStream(TargetRef.ByName("Harness Walk")))
        log("G CompleteStream 'Harness Walk'", out(g))
        if (g is Outcome.Confirm) log("G confirm", (e.confirm(g.confirmation) as? CommandResult.Executed)?.summary)
    }
}

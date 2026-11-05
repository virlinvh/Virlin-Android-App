package com.virlin.app.hierarchy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.ui.hierarchy.WorkstreamControlsDialog
import com.virlin.app.ui.screens.AttentionIntentController
import com.virlin.app.ui.theme.VirlinTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class WorkControlSafetyUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun cleanUp() { scope.cancel() }
    private fun show(onRename: ((String) -> Unit)? = null, onComplete: (() -> Unit)? = null, descendants: Int = 0) {
        val now = Instant.parse("2026-10-04T08:00:00Z")
        val stream = WorkStream("s", "Example", state = WorkStreamState.FOCUS, createdAt = now, updatedAt = now)
        val repo = InMemoryWorkStreamRepository(seed = listOf(stream))
        val clock = object : VirlinClock { override fun now() = now }
        val ids = object : IdProvider { override fun newId(prefix: String) = "$prefix-test" }
        val intents = AttentionIntentController(DefaultVirlinActions(repo, clock, ids), clock, repo, scope)
        compose.setContent { VirlinTheme {
            WorkstreamControlsDialog(stream, EffectiveExecutionMode.HUMAN, "Focused · 24m",
                onDismiss = {}, onMode = {}, onFocus = {}, intents = intents, onRename = onRename,
                taskId = if (descendants > 0) "parent" else null, executionAllowed = descendants == 0,
                onCompleteTask = onComplete, descendantCount = descendants)
        } }
    }
    @Test fun rename_prefillsEditsAndSavesOnDevice() {
        var saved = ""
        show(onRename = { saved = it })
        compose.onNodeWithText("Edit name").performScrollTo().performClick()
        compose.onNodeWithTag("work_name_input").assertTextContains("Example").performTextReplacement("Renamed")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals("Renamed", saved) }
        compose.onNodeWithTag("workstream_control_dialog").assertIsDisplayed()
    }
    @Test fun containerCompletion_namesScopeAndRequiresConfirmation() {
        var completed = false
        show(onComplete = { completed = true }, descendants = 3)
        compose.onNodeWithText("Mark as complete").performClick()
        compose.onNodeWithText("Complete this task and its 3 remaining descendants and stop their timers. Recorded time is preserved. Other tasks stay unchanged.").assertIsDisplayed()
        compose.runOnIdle { assertFalse(completed) }
        compose.onNodeWithText("Complete task").performClick()
        compose.runOnIdle { assertTrue(completed) }
    }
}

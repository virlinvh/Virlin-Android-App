package com.virlin.app.hierarchy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.hierarchy.WorkstreamControlsDialog
import com.virlin.app.ui.screens.AttentionIntentController
import com.virlin.app.ui.theme.VirlinTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkstreamControlsUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-03T08:00:00Z")
    private fun show(state: WorkStreamState = WorkStreamState.FOCUS,
        preference: ExecutionPreference = ExecutionPreference.HUMAN,
        taskId: String? = null, onCompleteTask: (() -> Unit)? = null,
        conflict: String? = null, onOpenConflict: () -> Unit = {}, onRename: ((String) -> Unit)? = null,
        executionAllowed: Boolean = true, childActivities: List<Pair<String, String>> = emptyList()): InMemoryWorkStreamRepository {
        val stream = WorkStream("s", "Example", state = state, executionPreference = preference, createdAt = now, updatedAt = now)
        val repo = InMemoryWorkStreamRepository(seed = listOf(stream))
        val clock = FakeClock(now)
        val intents = AttentionIntentController(DefaultVirlinActions(repo, clock, SequentialIdProvider()),
            clock, repo, CoroutineScope(Dispatchers.Main))
        compose.setContent { VirlinTheme {
            WorkstreamControlsDialog(stream, EffectiveExecutionMode.HUMAN, "Focusing · 24m",
                onDismiss = {}, onMode = {}, onFocus = {}, intents = intents,
                taskId = taskId, onCompleteTask = onCompleteTask,
                externalStartConflict = conflict, onOpenConflictingWork = onOpenConflict, onRename = onRename,
                executionAllowed = executionAllowed, childActivities = childActivities)
        } }
        return repo
    }
    @Test fun taskCompletion_usesTaskCallback_notWorkstreamCompletion() {
        var completed = false
        val repo = show(taskId = "leaf", onCompleteTask = { completed = true })
        compose.onNodeWithText("Work controls").assertIsDisplayed()
        compose.onNodeWithTag("workstream_complete").performScrollTo().performClick()
        compose.onNodeWithText("Complete task?").assertIsDisplayed()
        compose.onNodeWithText("Complete task").performClick()
        assertEquals(true, completed)
        assertEquals(WorkStreamState.FOCUS, repo.streams.value.single().state)
    }
    @Test fun parentTaskControls_hideActivityAndExecution_keepManagement() {
        show(taskId = "parent", onCompleteTask = {}, onRename = {}, executionAllowed = false,
            childActivities = listOf("child" to "Subtask · paused"))
        compose.onNodeWithText("Activity in this section").assertDoesNotExist()
        compose.onNodeWithText("Subtask · paused").assertDoesNotExist()
        compose.onNodeWithText("Timer display").assertDoesNotExist()
        compose.onNodeWithText("Who does this?").assertDoesNotExist()
        compose.onNodeWithText("Pause focus").assertDoesNotExist()
        compose.onNodeWithText("Edit name").assertExists()
        compose.onNodeWithTag("workstream_complete").assertIsEnabled()
    }
    @Test fun conflictingRun_isExplainedBeforeChoosingTime() {
        var opened = false
        show(WorkStreamState.READY, ExecutionPreference.EXTERNAL, taskId = "other",
            conflict = "Build", onOpenConflict = { opened = true })
        compose.onNodeWithText("Start external run").assertDoesNotExist()
        compose.onNodeWithText("View active work").performClick()
        assertEquals(true, opened)
        compose.onNodeWithText("Confirm reminder").assertDoesNotExist()
    }
    // Text-editor regression runs on Android in WorkControlSafetyUiTest: the native
    // Robolectric renderer cannot idle this dialog's text-input layout reliably.
    @Test fun executionMode_isCollapsedUntilTapped() {
        show()
        compose.onNodeWithText("Use parent").assertDoesNotExist()
        compose.onNodeWithTag("workstream_mode_expand").performClick()
        compose.onNodeWithText("Use parent").assertIsDisplayed()
        compose.onNodeWithText("External").assertIsDisplayed()
    }
    @Test fun timerDisplay_isAvailableAndCanChangeRotation() {
        show()
        compose.onNodeWithText("Timer display").performClick()
        compose.onNodeWithText("5 s").performClick()
        compose.onNodeWithText("5 s · selected").assertExists()
        compose.onNodeWithText("6 s").performClick()
    }

    @Test fun parentControls_keepManagementAndHideExecution() {
        show(onRename = {}, executionAllowed = false)
        compose.onNodeWithText("Who does this?").assertDoesNotExist()
        compose.onNodeWithText("Pause focus").assertDoesNotExist()
        compose.onNodeWithText("Start external run").assertDoesNotExist()
        compose.onNodeWithText("Edit name").assertExists()
        compose.onNodeWithText("Timer display").assertDoesNotExist()
        compose.onNodeWithText("Activity in this section").assertDoesNotExist()
        compose.onNodeWithTag("workstream_complete").assertIsEnabled().performClick()
        compose.onNodeWithText("Complete workstream?").assertIsDisplayed()
    }

    @Test fun externalPausedWithoutBackgroundTracking_offersExternalStart() {
        show(WorkStreamState.PAUSED, ExecutionPreference.EXTERNAL)
        compose.onNodeWithText("Resume focus").assertDoesNotExist()
        compose.onNodeWithText("Start external run").assertExists()
    }
    @Test fun completion_requiresConfirmation_andCancelDoesNotMutate() {
        val repo = show()
        compose.onNodeWithTag("workstream_complete").performScrollTo().performClick()
        compose.onNodeWithText("Complete workstream?").assertIsDisplayed()
        assertEquals(WorkStreamState.FOCUS, repo.streams.value.single().state)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Complete workstream?").assertDoesNotExist()
        assertEquals(WorkStreamState.FOCUS, repo.streams.value.single().state)
    }
    @Test fun completed_hasNoPause_orEditableCompletion() {
        show(WorkStreamState.DONE)
        compose.onNodeWithTag("workstream_complete").assertIsNotEnabled()
        compose.onNodeWithText("Pause focus").assertDoesNotExist()
    }
    @Test fun checkDue_doesNotClaimResultReady() {
        show(WorkStreamState.CHECK, ExecutionPreference.EXTERNAL)
        compose.onNodeWithText("Check progress").performClick()
        compose.onNodeWithText("Still running").assertIsDisplayed()
        compose.onNodeWithText("Result ready").assertIsDisplayed()
        compose.onNodeWithTag("workstream_control_dialog").assertIsDisplayed()
    }
    @Test fun humanCheckDue_hasFocus_notExternalOutcomes() {
        show(WorkStreamState.CHECK, ExecutionPreference.HUMAN)
        compose.onNodeWithText("Focus now").assertIsDisplayed()
        compose.onNodeWithText("Check progress").assertDoesNotExist()
        compose.onNodeWithText("Remind later").assertIsDisplayed()
    }
    @Test fun pause_andCustom_areInternalSteps_andBackDoesNotMutate() {
        val repo = show()
        compose.onNodeWithText("Pause focus").performClick()
        compose.onNodeWithText("Confirm pause").assertIsNotEnabled()
        compose.onNodeWithText("5m").performClick()
        compose.onNodeWithText("Confirm pause").assertIsEnabled()
        assertEquals(WorkStreamState.FOCUS, repo.streams.value.single().state)
        compose.onNodeWithText("Custom time").performClick()
        compose.onNodeWithText("Minutes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("workstream_mode_expand").assertIsDisplayed()
        assertEquals(WorkStreamState.FOCUS, repo.streams.value.single().state)
    }
    @Test fun readyExternal_hasExternalStart_notHumanFocus() {
        show(WorkStreamState.READY, ExecutionPreference.EXTERNAL)
        compose.onNodeWithText("Start external run").assertIsDisplayed()
        compose.onNodeWithText("Focus now").assertDoesNotExist()
        compose.onNodeWithText("Mark as complete").assertIsDisplayed()
    }
    @Test fun blocked_hasUnblock_andReason_notFocusOrStart() {
        show(WorkStreamState.BLOCKED)
        compose.onNodeWithText("Unblock").assertIsDisplayed()
        compose.onNodeWithText("Edit reason").assertIsDisplayed()
        compose.onNodeWithText("Focus now").assertDoesNotExist()
        compose.onNodeWithText("Start external run").assertDoesNotExist()
    }
    @Test fun running_hasOneProgressEntry_andOutcomesOnlyInsideIt() {
        val repo = show(WorkStreamState.PROCESSING, ExecutionPreference.EXTERNAL)
        compose.onNodeWithText("Result ready").assertDoesNotExist()
        compose.onNodeWithText("Block workstream").assertDoesNotExist()
        compose.onNodeWithText("Check progress").performClick()
        compose.onNodeWithText("Still running").assertIsDisplayed()
        compose.onNodeWithText("Result ready").assertIsDisplayed()
        compose.onNodeWithText("Blocked").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Result ready").assertDoesNotExist()
        assertEquals(WorkStreamState.PROCESSING, repo.streams.value.single().state)
    }
    @Test fun externalPause_isInline_withTimedAndIndefiniteChoices() {
        show(WorkStreamState.PROCESSING, ExecutionPreference.EXTERNAL)
        compose.onNodeWithText("Pause tracking").performClick()
        compose.onNodeWithText("Pause external tracking").assertIsDisplayed()
        compose.onNodeWithText("10m").assertIsDisplayed()
        compose.onNodeWithText("Custom time").assertIsDisplayed()
        compose.onNodeWithText("Pause without reminder").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Check progress").assertIsDisplayed()
    }
}

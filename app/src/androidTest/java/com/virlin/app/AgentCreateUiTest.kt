package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.virlin.app.data.db.VirlinDatabase
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.orb.AgentMode
import com.virlin.app.ui.agent.create.AgentCreateErrorTag
import com.virlin.app.ui.agent.create.AgentCreateParentPickerTag
import com.virlin.app.ui.agent.create.AgentCreateSubmitTag
import com.virlin.app.ui.agent.create.AgentCreateSuccessTag
import com.virlin.app.ui.agent.create.AgentCreateTag
import com.virlin.app.ui.agent.create.AgentCreateTitleTag
import com.virlin.app.ui.agent.create.CreateKind
import com.virlin.app.ui.agent.create.CreateNextAddSubtask
import com.virlin.app.ui.agent.create.CreateNextAddTask
import com.virlin.app.ui.agent.create.CreateNextAddWorkStream
import com.virlin.app.ui.agent.create.CreateNextDone
import com.virlin.app.ui.agent.create.CreateNextFocusNow
import com.virlin.app.ui.agent.create.CreateNextSetCurrent
import com.virlin.app.ui.agent.create.TaskOwnerKind
import com.virlin.app.ui.agent.create.createKindTag
import com.virlin.app.ui.agent.create.createModeTag
import com.virlin.app.ui.agent.create.createOwnerTag
import com.virlin.app.ui.agent.create.createProjectTag
import com.virlin.app.ui.agent.create.createStreamTag
import com.virlin.app.ui.components.VirlinOrbTestTag
import com.virlin.app.ui.hierarchy.taskRowTag
import com.virlin.app.ui.screens.AgentShellTestTag
import com.virlin.app.ui.screens.agentModeTag
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pass 9 on the real app: Orb → Agent CREATE over persisted Room state. One ordered scenario
 * (the domain is process-wide): Project → WorkStream → Task → Subtask chain, pickers from
 * persisted data, validation, after-create actions, and durability proven by re-opening the
 * app's Room database directly. Process death is exercised in the manual Pixel 8 proof.
 */
@RunWith(AndroidJUnit4::class)
class AgentCreateUiTest {

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt(); var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) { composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8) }
        composeRule.mainClock.advanceTimeByFrame()
    }
    private fun tag(t: String) = composeRule.onNodeWithTag(t, useUnmergedTree = true)
    private fun touch(t: String, after: Long = 700) {
        runCatching { tag(t).performScrollTo() }.onSuccess { pump(600) }
        tag(t).performTouchInput { click() }; pump(after)
    }
    private fun type(text: String) { tag(AgentCreateTitleTag).performScrollTo(); pump(300); tag(AgentCreateTitleTag).performTextInput(text); pump(300)
        androidx.test.espresso.Espresso.closeSoftKeyboard(); pump(500) }
    private fun submit() = touch(AgentCreateSubmitTag, 1200)
    private fun repo() = VirlinGraph.repository
    private fun openCreate() {
        composeRule.onNodeWithTag(VirlinOrbTestTag).performTouchInput { click() }; pump(800)
        composeRule.onNodeWithTag(AgentShellTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(agentModeTag(AgentMode.CREATE)).performClick(); pump(400)
        tag(AgentCreateTag).assertIsDisplayed()
    }

    @Test fun create_project_workstream_task_subtask_pickers_validation_afterActions_durable() {
        pump(300)
        val projectsBefore = repo().projects.value.size
        openCreate()

        // A. Validation first: TASK without any owner → friendly message, nothing persisted.
        val tasksBefore = repo().tasks.value.size
        touch(createKindTag(CreateKind.TASK)); type("Orphan"); submit()
        tag(AgentCreateErrorTag).assertExists().performScrollTo()
        check(repo().tasks.value.size == tasksBefore) { "orphan task must not persist" }
        touch("agent_create_cancel")

        // B. PROJECT → persisted immediately.
        touch(createKindTag(CreateKind.PROJECT)); type("UI Thesis"); submit()
        tag(AgentCreateSuccessTag).assertContentDescriptionContains("Created project · UI Thesis", substring = true)
        val project = repo().projects.value.first { it.title == "UI Thesis" }
        check(repo().projects.value.size == projectsBefore + 1)

        // C. ADD WORKSTREAM (chained; project preselected) with explicit EXTERNAL mode.
        touch(CreateNextAddWorkStream)
        tag(createProjectTag(project.id)).assertContentDescriptionContains("selected", substring = true)
        touch(createModeTag(WorkStreamMode.EXTERNAL))
        type("Literature run"); submit()
        tag(AgentCreateSuccessTag).assertContentDescriptionContains("Created WorkStream · Literature run", substring = true)
        val ws = repo().streams.value.first { it.title == "Literature run" }
        check(ws.projectId == project.id && ws.mode == WorkStreamMode.EXTERNAL && ws.state == WorkStreamState.READY && ws.activeTaskId == null) { "$ws" }
        check(runBlocking { VirlinGraph.repository.getStream("s1") }!!.state == WorkStreamState.FOCUS) { "creation must not touch focus" }

        // D. ADD TASK (chained; stream preselected) → root task; SET CURRENT is explicit.
        touch(CreateNextAddTask)
        tag(createStreamTag(ws.id)).assertContentDescriptionContains("selected", substring = true)
        type("Collect sources"); submit()
        val root = repo().tasks.value.first { it.title == "Collect sources" }
        check(root.workStreamId == ws.id && root.projectId == project.id && root.parentTaskId == null && root.status == TaskStatus.TODO)
        check(runBlocking { repo().getStream(ws.id) }!!.activeTaskId == null) { "no auto active task" }

        // E. ADD SUBTASK (chained; parent preselected) → child inherits ownership.
        touch(CreateNextAddSubtask)
        tag(AgentCreateParentPickerTag).assertExists()
        type("Search Scholar"); submit()
        val child = repo().tasks.value.first { it.title == "Search Scholar" }
        check(child.parentTaskId == root.id && child.workStreamId == ws.id) { "$child" }
        // Deeper: subtask of the subtask.
        touch(CreateNextAddSubtask); type("Filter by year"); submit()
        val grand = repo().tasks.value.first { it.title == "Filter by year" }
        check(grand.parentTaskId == child.id && grand.workStreamId == ws.id)

        // F. SET CURRENT from the success card → activeTaskId, still READY.
        touch(CreateNextSetCurrent, 1000)
        runBlocking { repo().getStream(ws.id) }!!.let { check(it.activeTaskId == grand.id && it.state == WorkStreamState.READY) { "$it" } }

        // G. Manual pickers: TASK → WorkStream owner → pick the seeded projectless stream; nest under a seeded task via the recursive picker.
        touch(createKindTag(CreateKind.TASK)); touch(createOwnerTag(TaskOwnerKind.WORKSTREAM))
        touch(createStreamTag("s1"))
        touch(taskRowTag("p_q17"), 500)                                        // deep active task auto-expanded
        type("Question 17 notes"); submit()
        val nested = repo().tasks.value.first { it.title == "Question 17 notes" }
        check(nested.parentTaskId == "p_q17" && nested.workStreamId == "s1" && nested.projectId == null) { "$nested" }
        touch(CreateNextDone)

        // H. Standalone project task through the PROJECT owner picker.
        touch(createKindTag(CreateKind.TASK)); touch(createOwnerTag(TaskOwnerKind.PROJECT)); touch(createProjectTag(project.id))
        type("Order printer paper"); submit()
        val standalone = repo().tasks.value.first { it.title == "Order printer paper" }
        check(standalone.workStreamId == null && standalone.projectId == project.id)
        touch(CreateNextDone)

        // I. Projectless WorkStream (No Project) + FOCUS NOW → focusStream with displacement.
        touch(createKindTag(CreateKind.WORKSTREAM)); touch(createProjectTag(null)); type("Walk"); submit()
        val walk = repo().streams.value.first { it.title == "Walk" }
        check(walk.projectId == null && walk.mode == WorkStreamMode.HUMAN)
        touch(CreateNextFocusNow, 1200)
        check(runBlocking { repo().getStream(walk.id) }!!.state == WorkStreamState.FOCUS)
        check(runBlocking { repo().getStream("s1") }!!.state == WorkStreamState.READY) { "displacement" }

        // J. The last control scrolls fully above the pinned composer (§46).
        touch(createKindTag(CreateKind.TASK)); touch(createOwnerTag(TaskOwnerKind.WORKSTREAM)); touch(createStreamTag("s1"))
        tag(AgentCreateSubmitTag).performScrollTo(); pump(600)
        val submitBottom = tag(AgentCreateSubmitTag).fetchSemanticsNode().boundsInRoot.bottom
        val pinnedTop = composeRule.onNodeWithTag(com.virlin.app.ui.agent.AgentComposerTestTag).fetchSemanticsNode().boundsInRoot.top
        check(submitBottom <= pinnedTop) { "CREATE chip ($submitBottom) must sit above the pinned composer ($pinnedTop)" }
        touch("agent_create_cancel")

        // K. Durability: a fresh Room handle on the app database sees every created row.
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VirlinDatabase::class.java, VirlinDatabase.NAME).build()
        try {
            runBlocking {
                check(db.projects().byId(project.id)?.title == "UI Thesis")
                check(db.workStreams().byId(ws.id)?.mode == "EXTERNAL")
                check(db.tasks().byId(grand.id)?.parentTaskId == child.id)
                check(db.tasks().byId(standalone.id)?.workStreamId == null)
            }
        } finally { db.close() }

        // Restore the seeded focus context for the other classes.
        runBlocking { VirlinGraph.actions.focusStream("s1"); VirlinGraph.actions.setActiveTask("s1", "p_q17") }
    }
}

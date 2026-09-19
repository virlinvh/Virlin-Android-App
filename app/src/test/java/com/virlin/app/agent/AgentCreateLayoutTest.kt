package com.virlin.app.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.CreateProject
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.agent.create.AgentCreateArea
import com.virlin.app.ui.agent.create.AgentCreateBelongsToTag
import com.virlin.app.ui.agent.create.AgentCreateControlsTag
import com.virlin.app.ui.agent.create.AgentCreateEstimateTag
import com.virlin.app.ui.agent.create.AgentCreateFormHeaderTag
import com.virlin.app.ui.agent.create.AgentCreateListTag
import com.virlin.app.ui.agent.create.AgentCreateProjectLabelTag
import com.virlin.app.ui.agent.create.AgentCreateSubmitTag
import com.virlin.app.ui.agent.create.AgentCreateTag
import com.virlin.app.ui.agent.create.AgentCreateTitleTag
import com.virlin.app.ui.agent.create.AgentCreateViewModel
import com.virlin.app.ui.agent.create.CreateKind
import com.virlin.app.ui.agent.create.createEstimateOptionTag
import com.virlin.app.ui.agent.create.createKindTag
import com.virlin.app.ui.agent.create.createModeTag
import com.virlin.app.ui.agent.create.createProjectTag
import com.virlin.app.ui.agent.create.createStreamTag
import com.virlin.app.ui.theme.VirlinTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Create contextual layout: fixed form header + scrollable selection + fixed compact controls.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentCreateLayoutTest {

    @get:Rule val composeRule = createComposeRule()
    private val t0: Instant = Instant.parse("2026-09-15T10:00:00Z")

    private fun manyProjectsRepo(): InMemoryWorkStreamRepository {
        val projects = (1..20).map { i ->
            Project("p$i", "Project $i", createdAt = t0, updatedAt = t0)
        }
        val streams = listOf(
            WorkStream(
                id = "s1", title = "Psychology Unit 23", state = WorkStreamState.FOCUS,
                projectId = "p1", createdAt = t0, updatedAt = t0
            ),
            WorkStream(
                id = "s2", title = "Design Review", state = WorkStreamState.READY,
                projectId = "p2", createdAt = t0, updatedAt = t0
            )
        )
        return InMemoryWorkStreamRepository(seed = streams, seedProjects = projects)
    }

    @Test
    fun workStream_fixedHeaderAndControls_listScrollsIndependently() {
        val repo = manyProjectsRepo()
        val vm = AgentCreateViewModel(DefaultVirlinActions(repo, FakeClock(t0), SequentialIdProvider()), repo)
        composeRule.setContent {
            VirlinTheme {
                Box(Modifier.width(411.dp).height(720.dp)) {
                    AgentCreateArea(vm, Modifier.fillMaxSize())
                }
            }
        }
        composeRule.onNodeWithTag(createKindTag(CreateKind.WORKSTREAM), useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag(AgentCreateTitleTag, useUnmergedTree = true).performTextInput("Literature")

        composeRule.onNodeWithTag(AgentCreateFormHeaderTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentCreateTitleTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentCreateProjectLabelTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("PROJECT · OPTIONAL", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentCreateControlsTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(createModeTag(ExecutionPreference.HUMAN), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(createModeTag(ExecutionPreference.EXTERNAL), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentCreateSubmitTag, useUnmergedTree = true).assertIsDisplayed()

        composeRule.onNodeWithTag(AgentCreateListTag, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodes(hasTestTag(AgentCreateListTag) and hasScrollAction(), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        )

        val last = composeRule.onNodeWithTag(createProjectTag("p20"), useUnmergedTree = true)
        last.performScrollTo()
        last.assertIsDisplayed()

        // Fixed chrome still present after scrolling the list.
        composeRule.onNodeWithTag(AgentCreateTitleTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentCreateProjectLabelTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentCreateSubmitTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(createModeTag(ExecutionPreference.HUMAN), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun task_fixedBelongsTo_estimatePickerPersists() {
        val repo = manyProjectsRepo()
        val actions = DefaultVirlinActions(repo, FakeClock(t0), SequentialIdProvider())
        runBlocking { actions.createProject(CreateProject(title = "Extra", id = "px")) }
        val vm = AgentCreateViewModel(actions, repo)
        composeRule.setContent {
            VirlinTheme {
                Box(Modifier.width(411.dp).height(720.dp)) {
                    AgentCreateArea(vm, Modifier.fillMaxSize())
                }
            }
        }
        composeRule.onNodeWithTag(createKindTag(CreateKind.TASK), useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag(AgentCreateTitleTag, useUnmergedTree = true).performTextInput("Collect")

        composeRule.onNodeWithTag(AgentCreateBelongsToTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentCreateListTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentCreateEstimateTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentCreateControlsTag, useUnmergedTree = true).assertIsDisplayed()

        composeRule.onNodeWithTag(createStreamTag("s2"), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(AgentCreateTitleTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentCreateBelongsToTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentCreateEstimateTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentCreateSubmitTag, useUnmergedTree = true).assertIsDisplayed()

        composeRule.onNodeWithTag(AgentCreateEstimateTag, useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag(createEstimateOptionTag(45), useUnmergedTree = true).performClick()
        assertEquals("45", vm.form.value.estimateMinutes)
        composeRule.onNodeWithText("Est. 45m", useUnmergedTree = true).assertIsDisplayed()
    }
}

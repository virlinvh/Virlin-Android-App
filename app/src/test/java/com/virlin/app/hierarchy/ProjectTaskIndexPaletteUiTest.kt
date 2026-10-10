package com.virlin.app.hierarchy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProjectTaskIndexPaletteUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun selectedFilterUsesConfiguredProjectColor() {
        val palette = ProjectPalette.from(Color(0xFF7652C9))
        val actions = object : ProjectTaskIndexActions {
            override fun backToProject(projectId: String) {}
            override fun openTaskInHierarchy(projectId: String, workstreamId: String?, ancestorTaskIds: List<String>, taskId: String?) {}
            override fun searchProjectTasks(projectId: String) {}
            override fun showOptions(projectId: String) {}
            override fun retry(projectId: String) {}
        }
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalProjectPalette provides palette) {
                VirlinProjectTaskIndex(ProjectTaskIndexState("p", "Project", 0, 1, emptyList()), actions, {})
            }
        } }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Remaining 1").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Color(palette.secondary), layouts.single().layoutInput.style.color)
        compose.onNodeWithText("Done 0").performClick()
        layouts.clear()
        compose.onNodeWithText("Done 0").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Color(palette.secondary), layouts.single().layoutInput.style.color)
    }
}

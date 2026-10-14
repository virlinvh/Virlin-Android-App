package com.virlin.app.hierarchy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkstreamBlocksUiTest {
    @get:Rule val compose = createComposeRule()
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("project_block_order", 0)

    private fun show(onMove: (String, String?) -> Unit = { _, _ -> }) {
        val ui = ProjectPageUi("blocks", "Project", "", workstreams = List(3) {
            WorkstreamUi("s$it", "Stream $it", 0, 0)
        }, standaloneTasks = emptyList(), documents = emptyList())
        val actions = ProjectPageActions(onBack = {}, onSearch = {}, onProgress = {},
            onOpenWorkstream = {}, onAddWorkstream = {}, onOpenTask = {}, onToggleTask = {},
            onTaskMenu = {}, onAddTask = {}, onOpenDocument = {}, onAddDocument = {},
            onProjectSettings = {}, onEditProject = {}, onManageWorkstreams = {}, onReorderWorkstream = onMove)
        compose.setContent { VirlinProjectExperience(ProjectExperienceData(ui, emptyList(), emptyList(), activity = emptyList()),
            ProjectExperienceActions(actions,
                KnowledgeActions({}, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, onMoveMany = { _, _, _ -> }),
                ActivityActions({}, {}, {}, {}, {})), projectIcon = {}) }
    }

    @Test fun projectAndWorkstreamTitlesUseCompactTypeSizes() {
        show()
        fun fontSize(text: String): androidx.compose.ui.unit.TextUnit {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.fontSize
        }
        assertEquals(23.sp, fontSize("Project"))
        assertEquals(18.sp, fontSize("Workstreams"))
        assertEquals(14.sp, fontSize("Stream 0"))
    }

    @Test fun handlesAreOptInAndProjectScoped() {
        prefs.edit().clear().putBoolean("another-project", true).commit()
        show()
        compose.onNodeWithTag("workstream_drag_s0").assertDoesNotExist()
        compose.runOnIdle { prefs.edit().putBoolean("blocks", true).commit() }
        compose.onNodeWithTag("workstream_drag_s0", useUnmergedTree = true).assertExists()
        compose.runOnIdle { prefs.edit().putBoolean("blocks", false).commit() }
        compose.onNodeWithTag("workstream_drag_s0", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun accessibleMoveUsesStableAnchor() {
        prefs.edit().clear().putBoolean("blocks", true).commit()
        var moved: Pair<String, String?>? = null
        show { id, before -> moved = id to before }
        val actions = compose.onNodeWithTag("workstream_drag_s0", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == "Move down" }.action() }
        compose.runOnIdle { assertEquals("s0" to "s2", moved) }
    }

    @Test fun longPressDragCommitsOnlyOnDrop() {
        prefs.edit().clear().putBoolean("blocks", true).commit()
        var moved: Pair<String, String?>? = null
        show { id, before -> moved = id to before }
        val handle = compose.onNodeWithTag("workstream_drag_s0", useUnmergedTree = true)
        val distance = compose.onNodeWithTag("stream_row_s1").fetchSemanticsNode().boundsInRoot.center.y -
            compose.onNodeWithTag("stream_row_s0").fetchSemanticsNode().boundsInRoot.center.y + 2f
        handle.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        handle.performTouchInput { advanceEventTime(700); moveBy(androidx.compose.ui.geometry.Offset(0f, 1f)) }
        compose.onNodeWithTag("dragged_workstream_preview").assertExists()
        handle.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, distance)); up() }
        compose.runOnIdle { assertEquals("s0" to "s2", moved) }
    }

    @Test fun cancelledDragDoesNotWrite() {
        prefs.edit().clear().putBoolean("blocks", true).commit()
        var writes = 0
        show { _, _ -> writes++ }
        val handle = compose.onNodeWithTag("workstream_drag_s0", useUnmergedTree = true)
        handle.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        handle.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, 1f)); moveBy(androidx.compose.ui.geometry.Offset(0f, 100f)); cancel() }
        compose.runOnIdle { assertEquals(0, writes) }
    }
}

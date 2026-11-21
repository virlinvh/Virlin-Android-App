package com.virlin.app.ui.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextRange
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class NoteSizeStepperTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(initial: Int = 16, enabled: Boolean = true) {
        compose.setContent {
            var value by mutableIntStateOf(initial)
            NoteSizeStepper(value, enabled,
                onStep = { events += "step:$it"; value += it },
                onEditStart = { events += "edit" },
                onSet = { events += "set:$it"; value = it })
        }
        compose.waitForIdle()
    }

    @Test fun arrowsStepByOne() {
        show()
        compose.onNodeWithTag("notes_size_up").performClick()
        compose.onNodeWithTag("notes_size_up").performClick()
        compose.onNodeWithTag("notes_size_down").performClick()
        assertEquals(listOf("step:1", "step:1", "step:-1"), events)
    }

    @Test fun arrowsStopAtTheLimits() {
        show(initial = NOTE_MAX_TEXT_SIZE)
        compose.onNodeWithTag("notes_size_up").assertIsNotEnabled()
        compose.onNodeWithTag("notes_size_down").performClick()
        assertEquals(listOf("step:-1"), events)
    }

    @Test fun draggingUpIncreasesTheSize() {
        show()
        compose.onNodeWithTag("notes_size").performTouchInput { swipeUp(startY = bottom, endY = top - 120f) }
        compose.waitForIdle()
        assertTrue(events.isNotEmpty() && events.all { it == "step:1" })
    }

    @Test fun typedSizeIsAppliedAndClamped() {
        show()
        compose.onNodeWithTag("notes_size_value").performClick()
        compose.onNodeWithTag("notes_size_field").performTextReplacement("24")
        compose.onNodeWithTag("notes_size_field").performImeAction()
        compose.onNodeWithTag("notes_size_value").performClick()
        compose.onNodeWithTag("notes_size_field").performTextReplacement("99")
        compose.onNodeWithTag("notes_size_field").performImeAction()
        assertEquals(listOf("edit", "set:24", "edit", "set:96"), events)
    }

    @Test fun disabledStepperIgnoresInput() {
        show(enabled = false)
        compose.onNodeWithTag("notes_size_up").assertIsNotEnabled()
        compose.onNodeWithTag("notes_size_value").assertIsNotEnabled()
        assertTrue(events.isEmpty())
    }

    // ---------------------------------------------------------------- view model

    private val ids = SequentialIdProvider()
    private val actions = DefaultVirlinActions(InMemoryWorkStreamRepository(seedProjects = listOf(
        Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))), FakeClock(Instant.EPOCH), ids)

    private fun editorTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { body() } finally { Dispatchers.resetMain() }
    }

    @Test fun stepsResizeTheSelectionAndUndoAsOneStep() = editorTest {
        val vm = NotesViewModel("task-size", "Note", actions, ids)
        val id = vm.state.value.blocks.single().id
        vm.onFocusChanged(id, true)
        vm.onTextChanged(id, "small big small", TextRange(15))
        vm.onSelectionChanged(id, TextRange(6, 9))
        assertEquals("16", vm.toolState().sizeLabel)
        repeat(3) { vm.stepFontSize(1) }
        assertEquals("19", vm.toolState().sizeLabel)
        val runs = vm.state.value.blocks.single().runs
        assertEquals(listOf(19), runs.filter { it.start == 6 && it.end == 9 }.map { it.fontSizeSp })
        assertTrue(runs.none { it.start < 6 && it.fontSizeSp != null })
        vm.undo()
        assertTrue(vm.state.value.blocks.single().runs.all { it.fontSizeSp == null })
    }

    @Test fun typedSizeRestoresTheSelectionThatWasActiveWhenTypingStarted() = editorTest {
        val vm = NotesViewModel("task-size-typed", "Note", actions, ids)
        val id = vm.state.value.blocks.single().id
        vm.onFocusChanged(id, true)
        vm.onTextChanged(id, "keep this word", TextRange(14))
        vm.onSelectionChanged(id, TextRange(5, 9))
        val target = vm.styleTarget()
        // The size field taking focus may collapse the editor's selection.
        vm.onSelectionChanged(id, TextRange(9))
        vm.applyFontSize(200, target)
        val sized = vm.state.value.blocks.single().runs.filter { it.fontSizeSp != null }
        assertEquals(1, sized.size)
        assertEquals(Triple(5, 9, NOTE_MAX_TEXT_SIZE), sized.single().let { Triple(it.start, it.end, it.fontSizeSp) })
        assertNull(vm.state.value.dialog)
    }
}

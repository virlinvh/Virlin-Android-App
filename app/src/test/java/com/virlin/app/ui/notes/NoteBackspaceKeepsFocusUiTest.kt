package com.virlin.app.ui.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Backspace through empty lines hands focus straight to the line above. The editor never holds
 * no focus between presses, which is what closed and reopened the keyboard on every press.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class NoteBackspaceKeepsFocusUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backspaceOnEmptyLinesMovesFocusUpWithoutTheEditorLosingIt() {
        val ids = SequentialIdProvider()
        val vm = NotesViewModel("task-backspace-focus", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids)
        val first = vm.state.value.blocks.single().id
        vm.onFocusChanged(first, true)
        vm.onTextChanged(first, "Hello\n", TextRange(6))
        val second = vm.state.value.focusedBlockId!!
        vm.onTextChanged(second, "\n", TextRange(1))
        val third = vm.state.value.focusedBlockId!!
        assertEquals(3, vm.state.value.blocks.size)

        val editorHasFocus = mutableListOf<Boolean>()
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme {
                Box(Modifier.onFocusChanged { editorHasFocus += it.hasFocus }) {
                    if (!state.loading) NoteBlockEditor(state, vm)
                }
            }
        }
        compose.onNodeWithTag("notes_block_$third").performClick()
        compose.onNodeWithTag("notes_block_$third").assertIsFocused()
        compose.waitForIdle()
        editorHasFocus.clear()

        compose.onNodeWithTag("notes_block_$third").performKeyInput { pressKey(Key.Backspace) }
        compose.waitForIdle()
        compose.onNodeWithTag("notes_block_$third").assertDoesNotExist()
        compose.onNodeWithTag("notes_block_$second").assertIsFocused()
        assertEquals(listOf(first, second), vm.state.value.blocks.map { it.id })
        assertEquals(second, vm.state.value.focusedBlockId)
        assertEquals(TextRange(0), vm.state.value.selection)

        // And again, into the first line, where the caret lands after "Hello".
        compose.onNodeWithTag("notes_block_$second").performKeyInput { pressKey(Key.Backspace) }
        compose.onNodeWithTag("notes_block_$first").assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf(first), vm.state.value.blocks.map { it.id })
            assertEquals(TextRange(5), vm.state.value.selection)
            assertEquals("the editor lost focus between presses: $editorHasFocus",
                emptyList<Boolean>(), editorHasFocus.filter { !it })
        }
    }
}

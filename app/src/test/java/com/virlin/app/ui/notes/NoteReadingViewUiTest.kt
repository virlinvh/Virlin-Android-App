package com.virlin.app.ui.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** The reading view draws the editor's own blocks, and nothing in it can change the note. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteReadingViewUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theReadingViewShowsTheNoteButCannotEditIt() {
        val ids = SequentialIdProvider()
        val vm = NotesViewModel("task-reading-view", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids)
        val first = vm.state.value.blocks.single().id
        val pasted = "# Plan\n\nBody text\n\n- [ ] Call back"
        assertTrue(vm.pasteStructured(first, pasted, pasted))
        val before = vm.state.value.blocks
        val focusBefore = vm.state.value.focusedBlockId
        val heading = before.first { it.type == NoteDocBlockType.HEADING_1 }
        val check = before.first { it.type == NoteDocBlockType.CHECKLIST }
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme {
                NoteBlockEditor(state, vm, readOnly = true, readerHeader = {
                    androidx.compose.material3.Text("Reader title")
                })
            }
        }
        compose.onNodeWithText("Reader title").assertExists()
        runCatching { compose.onNodeWithTag("notes_block_${heading.id}").performTextReplacement("Changed") }
        runCatching { compose.onNodeWithTag("notes_block_${heading.id}").performTextInput(" more") }
        compose.onNodeWithText("Plan").assertExists()
        compose.onNodeWithTag("notes_check_${check.id}").performClick()
        compose.onNodeWithTag("notes_block_${check.id}").performClick()
        compose.runOnIdle {
            assertEquals(before, vm.state.value.blocks)
            assertEquals(focusBefore, vm.state.value.focusedBlockId)
        }
    }
}

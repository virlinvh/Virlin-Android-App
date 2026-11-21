package com.virlin.app.ui.notes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.navigation.compose.rememberNavController
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** The reconstructed route must drive the original editor, not an archived web bridge. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestoredNotesRouteTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test fun routeSelectsAcrossBlocksAndOpensTheApprovedFlashcardComposer() {
        val ids = SequentialIdProvider()
        val vm = NotesViewModel("restored-note", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids)
        val first = vm.state.value.blocks.single().id
        val text = "# Heading\n\nAnswer paragraph"
        check(vm.pasteStructured(first, text, text))
        compose.setContent { NotesRoute(rememberNavController(), "restored-note", "Note", vm) }
        compose.onNodeWithTag("notes_editor_scroll").assertExists()
        compose.onNodeWithTag("notes_tool_select_document").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("notes_select_all").performClick()
        compose.onNodeWithTag("notes_tool_flashcard").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("flashcard_create_dialog").assertExists()
        compose.onNodeWithTag("flashcard_source").assertExists()
    }

    @Test fun equationSourceIsHiddenUntilExplicitlyOpened() {
        val ids = SequentialIdProvider()
        val vm = NotesViewModel("restored-equation", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids)
        val first = vm.state.value.blocks.single().id
        val source = "```latex\nx^2+y^2=z^2\nE=mc^2\n```"
        check(vm.pasteStructured(first, source, source))
        val equation = vm.state.value.blocks.single().id
        compose.setContent { NotesRoute(rememberNavController(), "restored-equation", "Note", vm) }
        compose.onNodeWithTag("notes_block_$equation").assertDoesNotExist()
        compose.onNodeWithTag("notes_equation_source_$equation").performClick()
        compose.onNodeWithTag("notes_block_$equation").assertExists()
    }
}

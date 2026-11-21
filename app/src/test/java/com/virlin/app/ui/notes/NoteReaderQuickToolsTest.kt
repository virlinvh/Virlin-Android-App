package com.virlin.app.ui.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** The reading view's quick tools: the editor's fan, block selection and heading fold, view only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class NoteReaderQuickToolsTest {
    @get:Rule val compose = createComposeRule()

    private fun noteVm(): NotesViewModel {
        val ids = SequentialIdProvider()
        val actions = DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids)
        val owner = "task-reader-tools-${System.nanoTime()}"
        val blocks = listOf(
            NoteDocBlock("h", NoteDocBlockType.HEADING_1, "Chapter"),
            NoteDocBlock("p", NoteDocBlockType.PARAGRAPH, "Answer paragraph"),
            NoteDocBlock("h2", NoteDocBlockType.HEADING_1, "Next chapter"),
            NoteDocBlock("q", NoteDocBlockType.PARAGRAPH, "More text"),
        )
        kotlinx.coroutines.runBlocking { actions.saveNoteDoc(owner, "Note", blocks) }
        return NotesViewModel(owner, "Note", actions, ids)
    }

    private fun exists(tag: String) =
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun readerFanOnlyOpensAndClosesWithoutTheTripleTap() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                NoteAddFan(NoteToolState(), "Show heading controls", onTool = {}, onAddBlock = null,
                    modifier = Modifier.fillMaxSize(), now = { 1_000L })
            }
        }
        val plus = compose.onNodeWithTag("notes_add_block")
        plus.performClick(); compose.waitForIdle()
        assertEquals(true, exists("notes_add_fan_select_document"))
        plus.performClick(); compose.waitForIdle()
        assertEquals(false, exists("notes_add_fan_select_document"))
        plus.performClick(); compose.waitForIdle()
        assertEquals("a third quick tap just opens the fan again", true, exists("notes_add_fan_select_document"))
        val config = plus.fetchSemanticsNode().config
        assertFalse("no Add block action in the reading view", SemanticsActions.CustomActions in config)
    }

    @Test fun headingFoldInTheReaderNeverChangesTheNote() {
        val vm = noteVm()
        var fold by mutableStateOf<NoteReaderFold?>(null)
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm, headingFoldMode = true,
                readOnly = true, readerFold = fold) }
        }
        compose.waitForIdle()
        val before = vm.state.value.blocks
        assertEquals(true, exists("notes_block_p"))
        fold = NoteReaderFold(1, 1)
        compose.waitForIdle()
        assertEquals("level 1 headings fold their text away", false, exists("notes_block_p"))
        assertEquals(false, exists("notes_block_q"))
        assertEquals(true, exists("notes_block_h"))
        compose.runOnIdle { assertEquals(before, vm.state.value.blocks) }
        // A single heading opens with its own control, still view only.
        compose.onNodeWithTag("notes_heading_toggle_h").performClick()
        compose.waitForIdle()
        assertEquals(true, exists("notes_block_p"))
        fold = NoteReaderFold(null, 2)
        compose.waitForIdle()
        assertEquals(true, exists("notes_block_q"))
        compose.runOnIdle { assertEquals(before, vm.state.value.blocks) }
    }

    @Test fun blockSelectionInTheReaderFeedsAFlashcardWithoutChangingTheNote() {
        val vm = noteVm()
        var selection = emptySet<String>()
        compose.setContent {
            val state by vm.state.collectAsState()
            var selected by androidx.compose.runtime.remember { mutableStateOf(emptySet<String>()) }
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm, documentSelectionMode = true,
                selectedBlockIds = selected, onSelectedBlockIdsChange = { selected = it; selection = it },
                readOnly = true) }
        }
        compose.waitForIdle()
        val before = vm.state.value.blocks
        compose.onNodeWithTag("notes_select_block_h").performClick()
        compose.onNodeWithTag("notes_select_block_p").performClick()
        compose.runOnIdle {
            assertEquals(setOf("h", "p"), selection)
            assertEquals(listOf("Chapter", "Answer paragraph"),
                flashcardBlocks(vm.state.value.blocks.filter { it.id in selection }).map { it.text })
            assertEquals(before, vm.state.value.blocks)
        }
    }

    @Test fun readerHeaderScrollsAwayAndFadesOnScrollDownThenReturnsOnScrollUp() {
        val header = NoteReaderHeaderScroll(reducedMotion = false)
        compose.setContent {
            Column(Modifier.fillMaxSize().nestedScroll(header)) {
                Box(Modifier.noteReaderHeader(header).fillMaxWidth().height(57.dp))
                LazyColumn(Modifier.fillMaxSize().testTag("reader_list")) {
                    items(60) { i -> Text("Line $i", Modifier.fillMaxWidth().height(48.dp)) }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1f, header.shown, 0.001f) }
        compose.onNodeWithTag("reader_list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("scrolling down hides the bar", 0f, header.shown, 0.001f)
            assertTrue(header.fullPx > 0f)
        }
        compose.onNodeWithTag("reader_list").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 200f)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("scrolling up brings it back", 1f, header.shown, 0.001f) }
    }

    @Test fun readerPlusSitsHigherThanTheEditorsByItsLift() {
        var lift by mutableStateOf(0.dp)
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                NoteAddFan(NoteToolState(), "Show heading controls", onTool = {}, onAddBlock = null,
                    modifier = Modifier.fillMaxSize(), bottomLift = lift)
            }
        }
        val editorTop = compose.onNodeWithTag("notes_add_block").fetchSemanticsNode().positionInRoot.y
        lift = 44.dp
        compose.waitForIdle()
        val readerTop = compose.onNodeWithTag("notes_add_block").fetchSemanticsNode().positionInRoot.y
        val density = compose.density.density
        assertEquals(44f * density, editorTop - readerTop, 1f)
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun selectedReaderTextIsReportedForTheFlashcardTool() {
        val vm = noteVm()
        val reader = NoteReaderTextSelection()
        compose.setContent {
            val state by vm.state.collectAsState()
            CompositionLocalProvider(LocalNoteReaderSelection provides reader) {
                MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm, readOnly = true) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("notes_block_p").performTextInputSelection(TextRange(0, 6))
        compose.runOnIdle {
            assertEquals("p", reader.blockId)
            assertEquals(TextRange(0, 6), reader.range)
            val block = vm.state.value.blocks.first { it.id == "p" }
            assertEquals("Answer", com.virlin.app.domain.notedoc.flashcardSelection(block, 0, 6)?.text)
        }
    }
}

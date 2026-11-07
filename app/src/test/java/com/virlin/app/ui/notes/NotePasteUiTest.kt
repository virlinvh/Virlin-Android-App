package com.virlin.app.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotePasteUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun documentSelectionModeCopiesAcrossHeadingAndParagraphWithoutEditing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val ids = SequentialIdProvider()
        val vm = NotesViewModel("task-cross-block-copy", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids)
        val first = vm.state.value.blocks.single().id
        val pasted = "# Main heading\n\n## Subheading\n\nBody text"
        assertTrue(vm.pasteStructured(first, pasted, pasted))
        val before = vm.state.value.blocks
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm,
                documentSelectionMode = true) }
        }
        compose.onNodeWithTag("notes_document_selection_text").assertExists()
        compose.onNodeWithTag("notes_copy_all").performClick()
        compose.runOnIdle {
            assertEquals("Main heading\n\nSubheading\n\nBody text",
                clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertEquals(before, vm.state.value.blocks)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun crossBlockSelectionFeedsFlashcardWithoutClipboardCopy() {
        val ids = SequentialIdProvider()
        val vm = NotesViewModel("task-direct-flashcard-selection", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids)
        val first = vm.state.value.blocks.single().id
        val pasted = "# Heading\n\nAnswer paragraph"
        assertTrue(vm.pasteStructured(first, pasted, pasted))
        var selection = TextRange.Zero
        compose.setContent {
            val state by vm.state.collectAsState()
            var selected by remember { mutableStateOf(TextRange.Zero) }
            MaterialTheme {
                NoteBlockEditor(state, vm, documentSelectionMode = true,
                    documentSelection = selected,
                    onDocumentSelectionChange = { selected = it; selection = it })
            }
        }
        val projection = selectableNoteText(vm.state.value.blocks)
        compose.onNodeWithTag("notes_document_selection_text")
            .performTextInputSelection(TextRange(0, projection.length))
        compose.runOnIdle {
            assertEquals(TextRange(0, projection.length), selection)
            val source = flashcardSourceFromCopiedText(vm.state.value.blocks,
                projection.text.substring(selection.min, selection.max), selection.min)
            assertEquals("Heading\n\nAnswer paragraph", source?.text)
            assertEquals(vm.state.value.blocks.size, 2)
        }
    }

    @Test fun changingMidDocumentBlockStyleKeepsViewportNearEditedLine() {
        val ids = SequentialIdProvider()
        val vm = NotesViewModel("task-scroll-anchor", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids)
        val first = vm.state.value.blocks.single().id
        val content = (1..80).joinToString("\n\n") { "Paragraph $it stays here." }
        assertEquals(true, vm.pasteStructured(first, content, content))
        val middle = vm.state.value.blocks[40].id
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm) }
        }
        // LazyColumn does not compose off-screen blocks until its container scrolls.
        compose.onNodeWithTag("notes_editor_scroll").performScrollToIndex(40)
        compose.onNodeWithTag("notes_block_$middle").performClick()
        val before = compose.onNodeWithTag("notes_editor_scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle { vm.setBlockType(NoteDocBlockType.HEADING_2) }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("notes_editor_scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Viewport jumped from $before to $after", kotlin.math.abs(after - before) < 120f)
    }

    /** A stored 80-paragraph note opened fresh, so no line is focused yet. */
    private fun longNote(owner: String): NotesViewModel {
        val ids = SequentialIdProvider()
        val actions = DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids)
        val blocks = (1..80).map {
            com.virlin.app.domain.notedoc.NoteDocBlock("p$it", NoteDocBlockType.PARAGRAPH, "Paragraph $it stays here.")
        }
        kotlinx.coroutines.runBlocking { actions.saveNoteDoc(owner, "Note", blocks) }
        val vm = NotesViewModel(owner, "Note", actions, ids)
        assertEquals(80, vm.state.value.blocks.size)
        return vm
    }

    /** The first block below the note's opening space, which scrolls with the text. */
    private fun topBlockIndex(vm: NotesViewModel): Int {
        val blocks = vm.state.value.blocks
        val editorTop = compose.onNodeWithTag("notes_editor_scroll").fetchSemanticsNode().boundsInRoot.top
        val contentTop = editorTop + with(compose.density) { NoteDocumentTopSpace.toPx() }
        return blocks.indexOfFirst { block ->
            compose.onAllNodesWithTag("notes_block_${block.id}").fetchSemanticsNodes()
                .any { it.boundsInRoot.bottom > contentTop }
        }
    }

    @Test fun reopeningTheNoteContinuesAtTheSamePlace() {
        val vm = longNote("task-reopen-position-${System.nanoTime()}")
        var shown by mutableStateOf(true)
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (shown && !state.loading) NoteBlockEditor(state, vm, positionKey = "reopen-test") }
        }
        compose.onNodeWithTag("notes_editor_scroll").performScrollToIndex(50)
        compose.waitForIdle()
        val before = topBlockIndex(vm)
        assertTrue(before >= 45)
        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()
        assertEquals(before, topBlockIndex(vm))
    }

    @Test fun selectAcrossOnAndOffKeepsThePlace() {
        val vm = longNote("task-select-position")
        var selecting by mutableStateOf(false)
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm, documentSelectionMode = selecting) }
        }
        compose.onNodeWithTag("notes_editor_scroll").performScrollToIndex(50)
        compose.waitForIdle()
        val before = topBlockIndex(vm)
        selecting = true
        compose.waitForIdle()
        selecting = false
        compose.waitForIdle()
        val after = topBlockIndex(vm)
        assertTrue("Top block moved from $before to $after", kotlin.math.abs(after - before) <= 1)
    }

    @Test fun addingABlockWithNothingFocusedPutsItWhereTheUserIsLooking() {
        val vm = longNote("task-insert-position")
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm) }
        }
        compose.onNodeWithTag("notes_editor_scroll").performScrollToIndex(50)
        compose.waitForIdle()
        val top = topBlockIndex(vm)
        compose.runOnIdle {
            assertEquals(null, vm.state.value.focusedBlockId)
            vm.onTool(NoteTool.TOGGLE)
        }
        compose.waitForIdle()
        val blocks = vm.state.value.blocks
        assertEquals(NoteDocBlockType.TOGGLE, blocks[top + 1].type)
        assertTrue(topBlockIndex(vm) in top - 1..top + 1)
    }

    @Test fun systemClipboardPasteCreatesHeadingAndCodeBlocks() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val pasted = "# Roadmap\n\n```kotlin\nval x = 1\n```"
        clipboard.setPrimaryClip(ClipData.newPlainText("note", pasted))
        val ids = SequentialIdProvider()
        val vm = NotesViewModel("task-paste-ui", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(),
                FakeClock(Instant.EPOCH), ids), ids)
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm) }
        }
        val id = vm.state.value.blocks.single().id
        compose.onNodeWithTag("notes_block_$id").performTextInput(pasted)
        compose.runOnIdle {
            assertEquals(vm.state.value.blocks.map { it.text }.toString(),
                listOf(NoteDocBlockType.HEADING_1, NoteDocBlockType.CODE),
                vm.state.value.blocks.map { it.type })
            assertEquals("Roadmap", vm.state.value.blocks[0].text)
            assertEquals("val x = 1", vm.state.value.blocks[1].text)
        }
    }

    @Test fun htmlClipboardPasteKeepsBoldAndHeading() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val pasted = "Roadmap\nBold detail"
        clipboard.setPrimaryClip(ClipData.newHtmlText("note", pasted,
            "<h1>Roadmap</h1><p><strong>Bold</strong> detail</p>"))
        val ids = SequentialIdProvider()
        val vm = NotesViewModel("task-rich-paste-ui", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(),
                FakeClock(Instant.EPOCH), ids), ids)
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm) }
        }
        val id = vm.state.value.blocks.single().id
        compose.onNodeWithTag("notes_block_$id").performTextInput(pasted)
        compose.runOnIdle {
            assertEquals(listOf(NoteDocBlockType.HEADING_1, NoteDocBlockType.PARAGRAPH),
                vm.state.value.blocks.map { it.type })
            org.junit.Assert.assertTrue(vm.state.value.blocks[1].runs.any {
                NoteDocMark.BOLD in it.marks &&
                    vm.state.value.blocks[1].text.substring(it.start, it.end) == "Bold"
            })
        }
    }

    @Test fun styledClipboardMarkdownCreatesHeadingTableAndCodeInEditor() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val pasted = "### Results\n\n| Name | Status |\n| --- | --- |\n| API | Ready |" +
            "\n\n```json\n{\"status\":\"error\"}\n```"
        val html = pasted.lines().joinToString("") {
            "<p><span style='font-family:Arial'>${it.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")}</span></p>"
        }
        clipboard.setPrimaryClip(ClipData.newHtmlText("note", pasted, html))
        val ids = SequentialIdProvider()
        val vm = NotesViewModel("task-styled-markdown-paste-ui", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids)
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm) }
        }
        val id = vm.state.value.blocks.single().id
        compose.onNodeWithTag("notes_block_$id").performTextInput(pasted)
        compose.runOnIdle {
            assertEquals(listOf(NoteDocBlockType.HEADING_3, NoteDocBlockType.TABLE,
                NoteDocBlockType.CODE), vm.state.value.blocks.map { it.type })
            assertEquals("Ready", vm.state.value.blocks[1].table!!.cell(1, 1))
        }
    }

    @Test fun multiLineStructuredInsertionWorksWithoutMatchingClipboardText() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("stale", "unrelated text"))
        val pasted = "### A heading\n\n```prompt\nCheck this result.\n```"
        val ids = SequentialIdProvider()
        val vm = NotesViewModel("task-stale-clipboard-ui", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids)
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm) }
        }
        val id = vm.state.value.blocks.single().id
        compose.onNodeWithTag("notes_block_$id").performTextInput(pasted)
        compose.runOnIdle {
            assertEquals(listOf(NoteDocBlockType.HEADING_3, NoteDocBlockType.PROMPT),
                vm.state.value.blocks.map { it.type })
            assertEquals("Check this result.", vm.state.value.blocks[1].text)
        }
    }

    @Test fun styledClipboardInlineMarkdownBecomesBoldInEditor() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val pasted = "Yes. Treat this as a **layout-only refactor**.\n\n" +
            "Use the current app as the **functional source of truth**."
        val html = "<p><span style='color:#202124'>Yes. Treat this as a " +
            "**layout-only refactor**.</span></p><p><span style='color:#202124'>" +
            "Use the current app as the **functional source of truth**.</span></p>"
        clipboard.setPrimaryClip(ClipData.newHtmlText("note", pasted, html))
        val ids = SequentialIdProvider()
        val vm = NotesViewModel("task-inline-markdown-paste-ui", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids)
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm) }
        }
        val id = vm.state.value.blocks.single().id
        compose.onNodeWithTag("notes_block_$id").performTextInput(pasted)
        compose.runOnIdle {
            assertEquals(2, vm.state.value.blocks.size)
            org.junit.Assert.assertTrue(vm.state.value.blocks.none { "**" in it.text })
            org.junit.Assert.assertTrue(vm.state.value.blocks.all { block ->
                block.runs.any { NoteDocMark.BOLD in it.marks }
            })
        }
    }
}

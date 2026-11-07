package com.virlin.app.domain

import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteRuns
import com.virlin.app.domain.notedoc.visibleNoteBlocks
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.notes.NoteSaveState
import com.virlin.app.ui.notes.NoteTool
import com.virlin.app.ui.notes.NotesViewModel
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The Notes page editor. Every formatting action is asserted against the DOCUMENT, not against a
 * toolbar flag, so a control that merely lit up would fail here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesEditorTest {

    @Test fun headingToolbarCycleFoldsDeepestFirstThenRestoresAll() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val mainId = vm.focusFirst()
        vm.setBlockType(NoteDocBlockType.HEADING_1)
        vm.onTextChanged(mainId, "Main", TextRange(4))
        vm.insertBlock(NoteDocBlockType.HEADING_2)
        val sectionId = vm.state.value.focusedBlockId!!
        vm.onTextChanged(sectionId, "Section", TextRange(7))
        vm.insertBlock(NoteDocBlockType.HEADING_3)
        val subsectionId = vm.state.value.focusedBlockId!!
        vm.onTextChanged(subsectionId, "Subsection", TextRange(10))
        vm.insertBlock(NoteDocBlockType.PARAGRAPH)
        val bodyId = vm.state.value.focusedBlockId!!
        vm.onTextChanged(bodyId, "Body", TextRange(4))

        vm.foldHeadingsAtLevel(3)
        assertEquals(listOf(mainId, sectionId, subsectionId),
            visibleNoteBlocks(vm.state.value.blocks).map { it.id })
        vm.foldHeadingsAtLevel(2)
        assertEquals(listOf(mainId, sectionId),
            visibleNoteBlocks(vm.state.value.blocks).map { it.id })
        vm.foldHeadingsAtLevel(1)
        assertEquals(listOf(mainId), visibleNoteBlocks(vm.state.value.blocks).map { it.id })
        vm.undo()
        assertEquals(listOf(mainId, sectionId),
            visibleNoteBlocks(vm.state.value.blocks).map { it.id })
        vm.foldHeadingsAtLevel(1)
        vm.foldHeadingsAtLevel(null)
        assertEquals(listOf(mainId, sectionId, subsectionId, bodyId),
            visibleNoteBlocks(vm.state.value.blocks).map { it.id })
    }

    @Test fun toggleHeadingsCollapseTheirSectionAndConvertBackKeepingContent() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val headingId = vm.focusFirst()
        vm.setParagraphStyle(NoteDocBlockType.HEADING_2, toggle = true)
        assertEquals("Toggle heading 2", vm.toolState().paragraphLabel)
        vm.onTextChanged(headingId, "Section", TextRange(7))
        vm.onTextChanged(headingId, "Section\n", TextRange(8))
        val bodyId = vm.state.value.focusedBlockId!!
        assertNotEquals(headingId, bodyId)
        vm.onTextChanged(bodyId, "Body", TextRange(4))
        val heading = vm.state.value.blocks.first { it.id == headingId }
        assertEquals(NoteDocBlockType.HEADING_2, heading.type)
        assertTrue(heading.toggleHeading)

        vm.toggleCollapsed(headingId)
        assertEquals(listOf(headingId), visibleNoteBlocks(vm.state.value.blocks, foldHeadings = false).map { it.id })

        vm.onFocusChanged(headingId, true)
        vm.onSelectionChanged(headingId, TextRange(7))
        vm.onTextChanged(headingId, "Section\n", TextRange(8))
        assertFalse("Enter on a collapsed toggle heading opens it",
            vm.state.value.blocks.first { it.id == headingId }.isCollapsed)

        vm.onFocusChanged(headingId, true)
        vm.onSelectionChanged(headingId, TextRange(0))
        vm.toggleCollapsed(headingId)
        vm.setParagraphStyle(NoteDocBlockType.HEADING_2, toggle = false)
        val plain = vm.state.value.blocks.first { it.id == headingId }
        assertFalse(plain.toggleHeading)
        assertFalse(plain.isCollapsed)
        assertTrue(vm.state.value.blocks.any { it.id == bodyId && it.text == "Body" })

        vm.setParagraphStyle(NoteDocBlockType.PARAGRAPH, toggle = true)
        assertEquals(NoteDocBlockType.TOGGLE, vm.state.value.blocks.first { it.id == headingId }.type)
        vm.setParagraphStyle(NoteDocBlockType.PARAGRAPH, toggle = true)
        assertEquals(NoteDocBlockType.PARAGRAPH, vm.state.value.blocks.first { it.id == headingId }.type)
    }

    @Test fun toggleToolPutsSelectedTextInsideANewToggleAndUndoRestoresIt() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val firstId = vm.focusFirst()
        vm.onTextChanged(firstId, "Speak naturally", TextRange(15))
        vm.onTextChanged(firstId, "Speak naturally\n", TextRange(16))
        val secondId = vm.state.value.focusedBlockId!!
        vm.onTextChanged(secondId, "Keep this", TextRange(9))
        val before = vm.state.value.blocks

        vm.onFocusChanged(firstId, true)
        vm.onSelectionChanged(firstId, TextRange(0, 15))
        vm.onTool(NoteTool.TOGGLE)
        val blocks = vm.state.value.blocks
        assertEquals(3, blocks.size)
        assertEquals(NoteDocBlockType.TOGGLE, blocks[0].type)
        assertEquals("", blocks[0].text)
        assertEquals(blocks[0].id, vm.state.value.focusedBlockId)
        assertEquals(firstId, blocks[1].id)
        assertEquals("Speak naturally", blocks[1].text)
        assertEquals(1, blocks[1].indent)
        assertEquals(before[1], blocks[2])

        vm.undo()
        assertEquals(before, vm.state.value.blocks)

        vm.onFocusChanged(secondId, true)
        vm.onSelectionChanged(secondId, TextRange(9))
        vm.onTool(NoteTool.TOGGLE)
        val inserted = vm.state.value.blocks
        assertEquals("a caret alone still adds a new toggle", 4, inserted.size)
        assertEquals(NoteDocBlockType.TOGGLE, inserted[2].type)
        assertEquals(before, inserted.filter { it.id in before.map(NoteDocBlock::id) })
    }

    private val clock = FakeClock(Instant.parse("2026-09-30T09:00:00Z"))
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    private fun vm(repo: InMemoryWorkStreamRepository = InMemoryWorkStreamRepository()): NotesViewModel {
        val ids = SequentialIdProvider()
        return NotesViewModel("owner-1", "Pixel 8 Validation", DefaultVirlinActions(repo, clock, ids), ids)
    }

    /** Focus the first block and select `[start, end)`. */
    private fun NotesViewModel.focusFirst(start: Int = 0, end: Int = 0): String {
        val id = state.value.blocks.first().id
        onFocusChanged(id, true)
        onSelectionChanged(id, TextRange(start, end))
        return id
    }

    // ---- lifecycle ------------------------------------------------------------------------

    @Test fun opensWithOneEmptyParagraph_andNoChanges() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.blocks.size)
        assertEquals(NoteDocBlockType.PARAGRAPH, vm.state.value.blocks.first().type)
        assertEquals("", vm.state.value.blocks.first().text)
        assertEquals(NoteSaveState.NO_CHANGES, vm.state.value.save)
        assertEquals("No changes", vm.saveLabel())
    }

    @Test fun typing_persistsAndReportsSaved() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val vm = vm(repo)
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "hello", TextRange(5))
        assertEquals(NoteSaveState.EDITED, vm.state.value.save)
        advanceUntilIdle()
        assertEquals(NoteSaveState.SAVED, vm.state.value.save)
        assertEquals("hello", repo.getNoteDoc("owner-1")!!.blocks.single().text)
    }

    @Test fun reopening_readsTheStoredDocumentBack() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val first = vm(repo)
        advanceUntilIdle()
        val id = first.focusFirst()
        first.onTextChanged(id, "persisted", TextRange(9))
        first.onSelectionChanged(id, TextRange(0, 9))
        first.onTool(NoteTool.BOLD)
        advanceUntilIdle()

        val second = vm(repo)
        advanceUntilIdle()
        val block = second.state.value.blocks.single()
        assertEquals("persisted", block.text)
        assertTrue(NoteRuns.rangeHasMark(block.runs, 0, 9, NoteDocMark.BOLD))
    }

    @Test fun mixedPastedNoteSurvivesSaveAndReopenWithStructure() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val first = vm(repo)
        advanceUntilIdle()
        val id = first.state.value.blocks.single().id
        val pasted = "## Deployment plan\n\n" +
            "Use the **existing** application.\n\n" +
            "- Verify storage\n- Verify rendering\n\n" +
            "```json\n{\"status\":\"ready\"}\n```\n\n" +
            "```prompt\nCheck the saved note.\n```\n\n" +
            "\$\$x^2+y^2=z^2\$\$"
        assertTrue(first.pasteStructured(id, pasted, pasted))
        advanceUntilIdle()

        val reopened = vm(repo)
        advanceUntilIdle()
        val blocks = reopened.state.value.blocks
        assertEquals(listOf(NoteDocBlockType.HEADING_2, NoteDocBlockType.PARAGRAPH,
            NoteDocBlockType.BULLET, NoteDocBlockType.BULLET, NoteDocBlockType.CODE,
            NoteDocBlockType.PROMPT, NoteDocBlockType.CODE), blocks.map { it.type })
        assertTrue(blocks[1].runs.any { NoteDocMark.BOLD in it.marks })
        assertEquals("{\"status\":\"ready\"}", blocks[4].text)
        assertTrue(com.virlin.app.domain.notedoc.NoteMath.isEquation(blocks.last()))
        assertFalse(blocks.any { it.text.contains("**") || it.text.startsWith("## ") })
    }

    @Test fun editingLegacyMultilineParagraphDoesNotSplitAtOldNewline() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.state.value.blocks.single().id
        vm.onTextChanged(id, "First line\nSecond line\nThird line", TextRange(33))
        assertEquals(1, vm.state.value.blocks.size)
        vm.onTextChanged(id, "First line\nSecond line\nThird line!", TextRange(34))
        assertEquals(1, vm.state.value.blocks.size)
        assertEquals("First line\nSecond line\nThird line!", vm.state.value.blocks.single().text)
    }

    @Test fun identicalSave_doesNotGrowTheRevision() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val vm = vm(repo)
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "x", TextRange(1))
        advanceUntilIdle()
        val first = repo.getNoteDoc("owner-1")!!.revision
        vm.flush()
        advanceUntilIdle()
        assertEquals(first, repo.getNoteDoc("owner-1")!!.revision)
    }

    // ---- inline marks --------------------------------------------------------------------

    @Test fun boldOnSelection_marksOnlyThoseCharacters() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "hello world", TextRange(11))
        vm.onSelectionChanged(id, TextRange(0, 5))
        vm.onTool(NoteTool.BOLD)
        val runs = vm.state.value.blocks.single().runs
        assertTrue(NoteRuns.rangeHasMark(runs, 0, 5, NoteDocMark.BOLD))
        assertFalse(NoteRuns.rangeHasMark(runs, 5, 11, NoteDocMark.BOLD))
    }

    @Test fun insertedImage_isBetweenEditableParagraphsAndPersists() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val vm = vm(repo)
        advanceUntilIdle()
        val first = vm.focusFirst()
        vm.insertImage("123e4567-e89b-12d3-a456-426614174000")
        val blocks = vm.state.value.blocks
        assertEquals(first, blocks[0].id)
        assertEquals(NoteDocBlockType.IMAGE, blocks[1].type)
        assertEquals("123e4567-e89b-12d3-a456-426614174000", blocks[1].imagePath)
        assertEquals(NoteDocBlockType.PARAGRAPH, blocks[2].type)
        advanceUntilIdle()
        assertEquals(blocks, repo.getNoteDoc("owner-1")!!.blocks)
    }

    @Test fun toggleCreatesNestedParagraphAndCollapsePersists() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val vm = vm(repo)
        advanceUntilIdle()
        vm.focusFirst()
        vm.onTool(NoteTool.TOGGLE)
        val toggle = vm.state.value.blocks[1]
        val child = vm.state.value.blocks[2]
        assertEquals(NoteDocBlockType.TOGGLE, toggle.type)
        assertEquals(toggle.indent + 1, child.indent)
        vm.toggleCollapsed(toggle.id)
        assertTrue(vm.state.value.blocks[1].isCollapsed)
        advanceUntilIdle()
        assertTrue(repo.getNoteDoc("owner-1")!!.blocks[1].isCollapsed)
        vm.toggleCollapsed(toggle.id)
        assertFalse(vm.state.value.blocks[1].isCollapsed)
    }

    @Test fun toolbarBlurRetainsSelectionForInlineFormatting() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "first second third", TextRange(18))
        vm.onSelectionChanged(id, TextRange(6, 12))
        vm.onFocusChanged(id, false) // tapping the formatting rail blurs BasicTextField
        assertEquals(TextRange(6, 12), vm.state.value.selection)
        vm.onTool(NoteTool.BOLD)
        vm.onTool(NoteTool.ITALIC)
        vm.onTool(NoteTool.CODE)
        val runs = vm.state.value.blocks.single().runs
        for (mark in listOf(NoteDocMark.BOLD, NoteDocMark.ITALIC, NoteDocMark.CODE)) {
            assertTrue(NoteRuns.rangeHasMark(runs, 6, 12, mark))
            assertFalse(NoteRuns.rangeHasMark(runs, 0, 5, mark))
        }
    }

    @Test fun boldTwice_removesIt() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "hello", TextRange(5))
        vm.onSelectionChanged(id, TextRange(0, 5))
        vm.onTool(NoteTool.BOLD)
        vm.onTool(NoteTool.BOLD)
        assertFalse(NoteRuns.rangeHasMark(vm.state.value.blocks.single().runs, 0, 5, NoteDocMark.BOLD))
    }

    @Test fun boldAtCaret_appliesToTheNextTypedCharacters() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "ab", TextRange(2))
        vm.onSelectionChanged(id, TextRange(2))
        vm.onTool(NoteTool.BOLD)          // collapsed caret: pending style, nothing stored yet
        assertTrue(vm.state.value.blocks.single().runs.isEmpty())
        vm.onTextChanged(id, "abCD", TextRange(4))
        val runs = vm.state.value.blocks.single().runs
        assertTrue(NoteRuns.rangeHasMark(runs, 2, 4, NoteDocMark.BOLD))
        assertFalse(NoteRuns.rangeHasMark(runs, 0, 2, NoteDocMark.BOLD))
    }

    @Test fun allFourCharacterMarksAreStored() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "styled", TextRange(6))
        vm.onSelectionChanged(id, TextRange(0, 6))
        listOf(NoteTool.BOLD, NoteTool.ITALIC, NoteTool.UNDERLINE, NoteTool.STRIKE, NoteTool.CODE)
            .forEach { vm.onTool(it) }
        val runs = vm.state.value.blocks.single().runs
        listOf(
            NoteDocMark.BOLD, NoteDocMark.ITALIC, NoteDocMark.UNDERLINE,
            NoteDocMark.STRIKE, NoteDocMark.CODE,
        ).forEach { assertTrue("$it missing", NoteRuns.rangeHasMark(runs, 0, 6, it)) }
    }

    @Test fun fontSizeColourAndHighlight_areStoredOnTheRun() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "attrs", TextRange(5))
        vm.onSelectionChanged(id, TextRange(0, 5))
        vm.setFont("Serif")
        vm.setFontSize(24)
        vm.setTextColor(0xFFB3261EL)
        vm.setHighlight(0xFFFFF3BFL)
        val run = vm.state.value.blocks.single().runs.single()
        assertEquals("Serif", run.fontFamily)
        assertEquals(24, run.fontSizeSp)
        assertEquals(0xFFB3261EL, run.color)
        assertEquals(0xFFFFF3BFL, run.highlight)
    }

    @Test fun boldSurvivesLaterTypingElsewhere() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "keep bold", TextRange(9))
        vm.onSelectionChanged(id, TextRange(5, 9))
        vm.onTool(NoteTool.BOLD)
        // Type at the very start; the bold range must move, not vanish.
        vm.onTextChanged(id, "XXkeep bold", TextRange(2))
        assertTrue(NoteRuns.rangeHasMark(vm.state.value.blocks.single().runs, 7, 11, NoteDocMark.BOLD))
    }

    // ---- links ---------------------------------------------------------------------------

    @Test fun link_normalisesBareDomain() {
        assertEquals("https://example.com", NotesViewModel.normalizeUrl("example.com"))
        assertEquals("https://a.test/x", NotesViewModel.normalizeUrl(" https://a.test/x "))
    }

    @Test fun link_rejectsUnusableInput() {
        listOf("", "   ", "no dot", "notadomain", "ftp://x.com", "mailto:a@b.com", "a b.com")
            .forEach { assertNull("accepted '$it'", NotesViewModel.normalizeUrl(it)) }
    }

    @Test fun link_storedOnSelection_andRemovable() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "see docs", TextRange(8))
        vm.onSelectionChanged(id, TextRange(4, 8))
        vm.setLink("example.com")
        assertEquals(
            "https://example.com",
            vm.state.value.blocks.single().runs.single { it.linkUrl != null }.linkUrl,
        )
        vm.onSelectionChanged(id, TextRange(4, 8))
        vm.setLink(null)
        assertTrue(vm.state.value.blocks.single().runs.none { it.linkUrl != null })
    }

    @Test fun link_badUrlIsRefusedAndNothingIsStored() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "text", TextRange(4))
        vm.onSelectionChanged(id, TextRange(0, 4))
        vm.setLink("nonsense")
        assertTrue(vm.state.value.blocks.single().runs.none { it.linkUrl != null })
        assertTrue(vm.state.value.dialog is com.virlin.app.ui.notes.NoteDialog.Message)
    }

    // ---- block shape ---------------------------------------------------------------------

    @Test fun listsQuoteChecklistAndPrompt_changeTheBlockType() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "item", TextRange(4))
        listOf(
            NoteTool.CHECKLIST to NoteDocBlockType.CHECKLIST,
            NoteTool.QUOTE to NoteDocBlockType.QUOTE,
            NoteTool.PROMPT to NoteDocBlockType.PROMPT,
        ).forEach { (tool, type) ->
            vm.onTool(tool)
            assertEquals(type, vm.state.value.blocks.first().type)
            vm.onTool(tool)  // toggling back returns to a plain paragraph
            assertEquals(NoteDocBlockType.PARAGRAPH, vm.state.value.blocks.first().type)
        }
        // Bullets and numbering make the list and open their style strip; the same tool closes
        // the strip and keeps the list, and the strip's None returns to a plain paragraph.
        listOf(
            NoteTool.BULLETS to NoteDocBlockType.BULLET,
            NoteTool.NUMBERING to NoteDocBlockType.NUMBERED,
        ).forEach { (tool, type) ->
            vm.onTool(tool)
            assertEquals(type, vm.state.value.blocks.first().type)
            assertEquals(com.virlin.app.ui.notes.NoteDialog.ListStyle(type), vm.state.value.dialog)
            vm.onTool(tool)
            assertEquals(type, vm.state.value.blocks.first().type)
            assertNull(vm.state.value.dialog)
            vm.removeListFromFocused()
            assertEquals(NoteDocBlockType.PARAGRAPH, vm.state.value.blocks.first().type)
        }
    }

    @Test fun listStyle_appliesToTheWholeListContinuesOnEnterAndUndoes() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "one", TextRange(3))
        vm.onTool(NoteTool.NUMBERING)
        vm.onTextChanged(id, "one\ntwo", TextRange(4))
        val second = vm.state.value.blocks[1].id
        assertEquals(second, vm.state.value.focusedBlockId)
        assertTrue(vm.setListStyle(NoteDocBlockType.NUMBERED, "lower-roman"))
        assertEquals(listOf("lower-roman", "lower-roman"), vm.state.value.blocks.map { it.listStyle })
        vm.undo()
        assertEquals(listOf<String?>(null, null), vm.state.value.blocks.map { it.listStyle })
        vm.redo()
        assertEquals(listOf("lower-roman", "lower-roman"), vm.state.value.blocks.map { it.listStyle })
        vm.onTextChanged(second, "two\n", TextRange(4))
        assertEquals("lower-roman", vm.state.value.blocks[2].listStyle)
        assertEquals(NoteDocBlockType.NUMBERED, vm.state.value.blocks[2].type)
        // Picking the default stores nothing, so the note is written exactly as before.
        assertTrue(vm.setListStyle(NoteDocBlockType.NUMBERED, "decimal"))
        assertTrue(vm.state.value.blocks.all { it.listStyle == null })
    }

    @Test fun spacingStrip_chipTogglesItAndPicksKeepItOpen() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "Plan", TextRange(4))
        vm.onTool(NoteTool.LINE_SPACING)
        assertEquals(com.virlin.app.ui.notes.NoteDialog.LineSpacing, vm.state.value.dialog)
        vm.setLineSpacing(1.5f, keepOpen = true)
        vm.setLineSpacing(2f, keepOpen = true)
        assertEquals(2f, vm.state.value.blocks.single().lineSpacing)
        assertEquals(com.virlin.app.ui.notes.NoteDialog.LineSpacing, vm.state.value.dialog)
        vm.onTool(NoteTool.LINE_SPACING)
        assertEquals(null, vm.state.value.dialog)
        vm.undo()
        assertEquals(1.5f, vm.state.value.blocks.single().lineSpacing)
    }

    @Test fun paragraphStrip_chipTogglesItAndPicksKeepItOpen() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "Plan", TextRange(4))
        vm.onTool(NoteTool.PARAGRAPH)
        assertEquals(com.virlin.app.ui.notes.NoteDialog.Paragraph, vm.state.value.dialog)
        vm.setParagraphStyle(NoteDocBlockType.HEADING_2, toggle = false, keepOpen = true)
        assertEquals(NoteDocBlockType.HEADING_2, vm.state.value.blocks.single().type)
        assertEquals(com.virlin.app.ui.notes.NoteDialog.Paragraph, vm.state.value.dialog)
        vm.setBlockType(NoteDocBlockType.QUOTE, keepOpen = true)
        assertEquals(NoteDocBlockType.QUOTE, vm.state.value.blocks.single().type)
        assertEquals(com.virlin.app.ui.notes.NoteDialog.Paragraph, vm.state.value.dialog)
        vm.onTool(NoteTool.PARAGRAPH)
        assertEquals(null, vm.state.value.dialog)
        vm.undo(); vm.undo()
        assertEquals(NoteDocBlockType.PARAGRAPH, vm.state.value.blocks.single().type)
    }

    @Test fun wordCount_isAlwaysAvailableAndCountsTheWholeNote() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "plan the week", TextRange(13))
        assertTrue(NoteTool.WORD_COUNT in vm.toolState().enabled)
        vm.onTool(NoteTool.WORD_COUNT)
        assertEquals(com.virlin.app.ui.notes.NoteDialog.WordCount, vm.state.value.dialog)
        assertEquals(3, vm.wordCount().words)
        assertEquals(13, vm.wordCount().characters)
        assertEquals("plan the week", vm.state.value.blocks.single().text)
    }

    @Test fun changeCase_cyclesTheSelectionKeepsMarksAndUndoes() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "plan the week", TextRange(13))
        vm.onSelectionChanged(id, TextRange(0, 4))
        vm.onTool(NoteTool.BOLD)
        vm.onSelectionChanged(id, TextRange(0, 13))
        assertTrue(NoteTool.CHANGE_CASE in vm.toolState().enabled)
        vm.onTool(NoteTool.CHANGE_CASE)
        assertEquals("PLAN THE WEEK", vm.state.value.blocks.single().text)
        vm.onTool(NoteTool.CHANGE_CASE)
        assertEquals("Plan The Week", vm.state.value.blocks.single().text)
        vm.onTool(NoteTool.CHANGE_CASE)
        assertEquals("plan the week", vm.state.value.blocks.single().text)
        assertEquals(TextRange(0, 13), vm.state.value.selection)
        assertTrue(NoteRuns.rangeHasMark(vm.state.value.blocks.single().runs, 0, 4, NoteDocMark.BOLD))
        vm.undo()
        assertEquals("Plan The Week", vm.state.value.blocks.single().text)
        // With only a caret, the word under it changes ("The" is title case, so lowercase is next).
        vm.onSelectionChanged(id, TextRange(6))
        vm.onTool(NoteTool.CHANGE_CASE)
        assertEquals("Plan the Week", vm.state.value.blocks.single().text)
        vm.onTool(NoteTool.CHANGE_CASE)
        assertEquals("Plan THE Week", vm.state.value.blocks.single().text)
    }

    @Test fun listStyle_onAPlainLineMakesItThatList() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "point", TextRange(5))
        assertTrue(vm.setListStyle(NoteDocBlockType.BULLET, "arrow"))
        val block = vm.state.value.blocks.single()
        assertEquals(NoteDocBlockType.BULLET, block.type)
        assertEquals("arrow", block.listStyle)
        vm.onTool(NoteTool.QUOTE)
        assertNull(vm.state.value.blocks.single().listStyle)
    }

    @Test fun alignIndentAndSpacing_areStoredOnTheBlock() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "para", TextRange(4))
        vm.onTool(NoteTool.ALIGN_CENTER)
        assertEquals(NoteDocAlign.CENTER, vm.state.value.blocks.first().align)
        vm.onTool(NoteTool.ALIGN_RIGHT)
        assertEquals(NoteDocAlign.RIGHT, vm.state.value.blocks.first().align)
        vm.onTool(NoteTool.INDENT)
        vm.onTool(NoteTool.INDENT)
        assertEquals(2, vm.state.value.blocks.first().indent)
        vm.onTool(NoteTool.OUTDENT)
        assertEquals(1, vm.state.value.blocks.first().indent)
        assertEquals("Single", vm.toolState().spacingLabel)
        vm.setLineSpacing(1.5f)
        assertEquals(1.5f, vm.state.value.blocks.first().lineSpacing)
        assertEquals("1.5", vm.toolState().spacingLabel)
        vm.setLineSpacing(2f)
        assertEquals("Double", vm.toolState().spacingLabel)
    }

    @Test fun indentNeverGoesNegative() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.focusFirst()
        repeat(3) { vm.onTool(NoteTool.OUTDENT) }
        assertEquals(0, vm.state.value.blocks.first().indent)
    }

    @Test fun dividerAndTable_insertRealBlocks() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.focusFirst()
        vm.onTool(NoteTool.DIVIDER)
        assertTrue(vm.state.value.blocks.any { it.type == NoteDocBlockType.DIVIDER })
        vm.insertBlock(NoteDocBlockType.TABLE)
        val table = vm.state.value.blocks.first { it.type == NoteDocBlockType.TABLE }
        assertNotNull(table.table)
        assertEquals(4, table.table!!.cells.size)
        // A block that cannot hold the caret is followed by a paragraph that can.
        val after = vm.state.value.blocks[vm.state.value.blocks.indexOf(table) + 1]
        assertEquals(NoteDocBlockType.PARAGRAPH, after.type)
    }

    @Test fun tableCell_writesIntoTheDocument() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.focusFirst()
        vm.insertBlock(NoteDocBlockType.TABLE)
        val id = vm.state.value.blocks.first { it.type == NoteDocBlockType.TABLE }.id
        vm.onTableCellChanged(id, 1, 1, "cell")
        val t = vm.state.value.blocks.first { it.id == id }.table!!
        assertEquals("cell", t.cell(1, 1))
        assertEquals("", t.cell(0, 0))
    }

    @Test fun checklistToggle_persistsChecked() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val vm = vm(repo)
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "do it", TextRange(5))
        vm.onTool(NoteTool.CHECKLIST)
        vm.onChecklistToggled(id)
        advanceUntilIdle()
        assertTrue(repo.getNoteDoc("owner-1")!!.blocks.first().checked)
    }

    // ---- Enter and Backspace ---------------------------------------------------------------

    @Test fun enter_splitsIntoTwoBlocksAtTheCaret() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "onetwo", TextRange(6))
        vm.onTextChanged(id, "one\ntwo", TextRange(4))
        assertEquals(2, vm.state.value.blocks.size)
        assertEquals("one", vm.state.value.blocks[0].text)
        assertEquals("two", vm.state.value.blocks[1].text)
        assertEquals(vm.state.value.blocks[1].id, vm.state.value.focusedBlockId)
    }

    @Test fun enter_inAListContinuesTheList() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "first", TextRange(5))
        vm.onTool(NoteTool.BULLETS)
        vm.onTextChanged(id, "first\n", TextRange(6))
        assertEquals(NoteDocBlockType.BULLET, vm.state.value.blocks[1].type)
    }

    @Test fun enter_onEmptyListItemLeavesTheList() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTool(NoteTool.BULLETS)
        vm.onTextChanged(id, "\n", TextRange(1))
        assertEquals(1, vm.state.value.blocks.size)
        assertEquals(NoteDocBlockType.PARAGRAPH, vm.state.value.blocks.first().type)
    }

    @Test fun enter_afterHeadingReturnsToNormalText() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "Title", TextRange(5))
        vm.setBlockType(NoteDocBlockType.HEADING_1)
        vm.onTextChanged(id, "Title\n", TextRange(6))
        assertEquals(NoteDocBlockType.PARAGRAPH, vm.state.value.blocks[1].type)
    }

    @Test fun enter_inCodeBlockKeepsOneBlockWithANewline() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "line1", TextRange(5))
        vm.setBlockType(NoteDocBlockType.CODE)
        vm.onTextChanged(id, "line1\n", TextRange(6))
        assertEquals(1, vm.state.value.blocks.size)
        assertEquals("line1\n", vm.state.value.blocks.single().text)
    }

    @Test fun enter_keepsFormattingOnBothHalves() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "boldtail", TextRange(8))
        vm.onSelectionChanged(id, TextRange(0, 8))
        vm.onTool(NoteTool.BOLD)
        vm.onTextChanged(id, "bold\ntail", TextRange(5))
        val (a, b) = vm.state.value.blocks
        assertTrue(NoteRuns.rangeHasMark(a.runs, 0, 4, NoteDocMark.BOLD))
        assertTrue(NoteRuns.rangeHasMark(b.runs, 0, 4, NoteDocMark.BOLD))
    }

    @Test fun backspaceAtStart_demotesAStyledBlockFirst() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "quoted", TextRange(6))
        vm.onTool(NoteTool.QUOTE)
        vm.onSelectionChanged(id, TextRange(0))
        assertTrue(vm.onBackspaceAtStart(id))
        assertEquals(NoteDocBlockType.PARAGRAPH, vm.state.value.blocks.first().type)
    }

    @Test fun backspaceAtStart_mergesParagraphsAndKeepsRuns() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "one", TextRange(3))
        vm.onSelectionChanged(id, TextRange(0, 3))
        vm.onTool(NoteTool.BOLD)
        vm.onSelectionChanged(id, TextRange(3))
        vm.onTextChanged(id, "one\ntwo", TextRange(4))
        val second = vm.state.value.blocks[1].id
        assertTrue(vm.onBackspaceAtStart(second))
        val merged = vm.state.value.blocks.single()
        assertEquals("onetwo", merged.text)
        assertTrue(NoteRuns.rangeHasMark(merged.runs, 0, 3, NoteDocMark.BOLD))
        assertFalse(NoteRuns.rangeHasMark(merged.runs, 3, 6, NoteDocMark.BOLD))
    }

    @Test fun backspaceAtStartOfTheFirstBlock_doesNothing() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "only", TextRange(4))
        vm.onSelectionChanged(id, TextRange(0))
        assertFalse(vm.onBackspaceAtStart(id))
        assertEquals(1, vm.state.value.blocks.size)
    }

    // ---- undo ----------------------------------------------------------------------------

    @Test fun undo_reversesFormattingAndRedoReappliesIt() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "word", TextRange(4))
        vm.onSelectionChanged(id, TextRange(0, 4))
        vm.onTool(NoteTool.BOLD)
        assertTrue(NoteRuns.rangeHasMark(vm.state.value.blocks.single().runs, 0, 4, NoteDocMark.BOLD))
        vm.undo()
        assertFalse(NoteRuns.rangeHasMark(vm.state.value.blocks.single().runs, 0, 4, NoteDocMark.BOLD))
        vm.redo()
        assertTrue(NoteRuns.rangeHasMark(vm.state.value.blocks.single().runs, 0, 4, NoteDocMark.BOLD))
    }

    @Test fun undo_reversesABlockTypeChange() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "t", TextRange(1))
        vm.onTool(NoteTool.BULLETS)
        vm.undo()
        assertEquals(NoteDocBlockType.PARAGRAPH, vm.state.value.blocks.first().type)
    }

    @Test fun undo_reversesAnInsertedDivider() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.focusFirst()
        val before = vm.state.value.blocks.size
        vm.onTool(NoteTool.DIVIDER)
        vm.undo()
        assertEquals(before, vm.state.value.blocks.size)
        assertTrue(vm.state.value.blocks.none { it.type == NoteDocBlockType.DIVIDER })
    }

    @Test fun undoIsUnavailableUntilSomethingChanges() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.state.value.canUndo)
        assertFalse(NoteTool.UNDO in vm.toolState().enabled)
        val id = vm.focusFirst()
        vm.onTextChanged(id, "a", TextRange(1))
        assertTrue(NoteTool.UNDO in vm.toolState().enabled)
    }

    // ---- toolbar honesty -------------------------------------------------------------------

    @Test fun characterToolsAreDisabledWithNoFocusedBlock() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val tools = vm.toolState()
        listOf(NoteTool.BOLD, NoteTool.ITALIC, NoteTool.FONT, NoteTool.SIZE)
            .forEach { assertFalse("$it should be disabled", it in tools.enabled) }
    }

    @Test fun colourAndLinkNeedCharactersToAttachTo() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        assertFalse(NoteTool.LINK in vm.toolState().enabled)
        assertFalse(NoteTool.TEXT_COLOR in vm.toolState().enabled)
        vm.onTextChanged(id, "now there is text", TextRange(17))
        assertTrue(NoteTool.LINK in vm.toolState().enabled)
        assertTrue(NoteTool.TEXT_COLOR in vm.toolState().enabled)
    }

    @Test fun toolbarLabelsFollowTheFocusedBlock() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        assertEquals("Normal text", vm.toolState().paragraphLabel)
        vm.onTextChanged(id, "Title", TextRange(5))
        vm.setBlockType(NoteDocBlockType.HEADING_2)
        assertEquals("Heading 2", vm.toolState().paragraphLabel)
        vm.onSelectionChanged(id, TextRange(0, 5))
        vm.setFontSize(28)
        assertEquals("28", vm.toolState().sizeLabel)
        vm.setFont("Mono")
        assertEquals("Mono", vm.toolState().fontLabel)
    }

    @Test fun activeSetReflectsTheSelectionNotTheLastTap() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "half bold", TextRange(9))
        vm.onSelectionChanged(id, TextRange(0, 4))
        vm.onTool(NoteTool.BOLD)
        assertTrue(NoteTool.BOLD in vm.toolState().active)
        // Move the selection onto unbolded text: the button must go quiet.
        vm.onSelectionChanged(id, TextRange(5, 9))
        assertFalse(NoteTool.BOLD in vm.toolState().active)
    }

    @Test fun exportIsHonestlyUnavailable() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onExport()
        val dialog = vm.state.value.dialog
        assertTrue(dialog is com.virlin.app.ui.notes.NoteDialog.Message)
        assertTrue((dialog as com.virlin.app.ui.notes.NoteDialog.Message).text.contains("not built yet"))
    }

    // ---- isolation -------------------------------------------------------------------------

    @Test fun oneOwnerCannotSeeAnotherOwnersNote() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val ids = SequentialIdProvider()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val a = NotesViewModel("owner-A", "A", actions, ids)
        advanceUntilIdle()
        val aId = a.state.value.blocks.first().id
        a.onFocusChanged(aId, true)
        a.onTextChanged(aId, "secret of A", TextRange(11))
        advanceUntilIdle()

        val b = NotesViewModel("owner-B", "B", actions, ids)
        advanceUntilIdle()
        assertEquals("", b.state.value.blocks.single().text)
        assertNull(repo.getNoteDoc("owner-C"))
        assertEquals("secret of A", repo.getNoteDoc("owner-A")!!.blocks.single().text)
    }

    @Test fun theNotesPageNeverWritesATaskNoteOrACapture() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val vm = vm(repo)
        advanceUntilIdle()
        val id = vm.focusFirst()
        vm.onTextChanged(id, "self contained", TextRange(14))
        advanceUntilIdle()
        // Nothing leaked into the capture Inbox or any other surface.
        assertTrue(repo.captures.value.isEmpty())
        assertTrue(repo.tasks.value.isEmpty())
        assertNotNull(repo.getNoteDoc("owner-1"))
    }
}

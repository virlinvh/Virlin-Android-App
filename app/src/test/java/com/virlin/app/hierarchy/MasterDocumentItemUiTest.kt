package com.virlin.app.hierarchy

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.hierarchy.MasterDocumentEntry
import com.virlin.app.ui.hierarchy.MasterDocumentItem
import com.virlin.app.ui.hierarchy.masterDocumentItems
import com.virlin.app.ui.hierarchy.rememberMasterDocumentExpansion
import com.virlin.app.ui.hierarchy.previewableNoteBlocks
import com.virlin.app.ui.hierarchy.noteCardTitle
import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.VirlinNoteDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MasterDocumentItemUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun todoContentReadsInlineAndStillOpensItsEditor() {
        var opened = false
        compose.setContent {
            MasterDocumentItem(
                MasterDocumentEntry("todo-1", "To-do", "To-do", "☐ Review the draft",
                    "todo-route"),
                ink = Color.Black,
                accent = Color(0xFF6033B1),
                onOpen = { opened = true },
            )
        }
        compose.onNodeWithText("☐ Review the draft").assertDoesNotExist()
        compose.onNodeWithTag("map_page_item_todo-1").performClick()
        compose.onNodeWithText("☐ Review the draft").assertExists()
        compose.onNodeWithTag("map_page_open_todo-1").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun noteCardPreviewsTheNoteItselfAndStillOpensIt() {
        var opened = false
        val blocks = listOf(
            NoteDocBlock("h", NoteDocBlockType.HEADING_1, "Launch plan", align = NoteDocAlign.CENTER),
            NoteDocBlock("b", NoteDocBlockType.BULLET, "Ship the beta"),
            NoteDocBlock("p", NoteDocBlockType.PARAGRAPH, "Remember the notes"),
        )
        compose.setContent {
            MasterDocumentItem(
                MasterDocumentEntry("note-1", "Notes", "Note", "FLATTENED TEXT", "note-route",
                    noteBlocks = blocks),
                ink = Color.Black,
                accent = Color(0xFF6033B1),
                onOpen = { opened = true },
            )
        }
        compose.onNodeWithTag("map_page_note_preview_note-1").assertDoesNotExist()
        compose.onNodeWithTag("map_page_item_note-1").performClick()
        compose.onNodeWithTag("map_page_note_preview_note-1").assertExists()
        compose.onNodeWithText("Launch plan").assertExists()
        compose.onNodeWithText("Ship the beta", substring = true).assertExists()
        compose.onNodeWithText("FLATTENED TEXT").assertDoesNotExist()
        compose.onNodeWithTag("map_page_open_note-1").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun headerOpenButtonOpensTheNoteWithoutExpandingTheCard() {
        var opened = 0
        compose.setContent {
            MasterDocumentItem(
                MasterDocumentEntry("note-2", "Prompt Checking", "Note", "Body", "note-route",
                    noteBlocks = listOf(NoteDocBlock("p", NoteDocBlockType.PARAGRAPH, "Inside"))),
                ink = Color.Black,
                accent = Color(0xFF6033B1),
                onOpen = { opened++ },
            )
        }
        compose.onNodeWithTag("map_page_open_top_note-2").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNodeWithTag("map_page_note_preview_note-2").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open Note Prompt Checking").assertExists()
        compose.onNodeWithTag("map_page_item_note-2").performClick()
        compose.onNodeWithTag("map_page_open_top_note-2").performClick()
        compose.runOnIdle { assertEquals(2, opened) }
    }

    @Test fun anOpenLongNoteInAPageListOnlyComposesTheBlocksOnScreen() {
        var opened = 0
        val blocks = (0 until 400).map { NoteDocBlock("b$it", NoteDocBlockType.PARAGRAPH, "Line number $it") }
        compose.setContent {
            val expansion = rememberMasterDocumentExpansion("page")
            LazyColumn(Modifier.fillMaxSize().testTag("page_list")) {
                masterDocumentItems(
                    listOf(MasterDocumentEntry("long", "Long note", "Note", "FLAT", "route", noteBlocks = blocks)),
                    Color.Black, Color(0xFF6033B1), null, expansion, gap = 12.dp,
                    onOpen = { opened++ },
                )
            }
        }
        compose.onNodeWithText("Line number 0").assertDoesNotExist()
        compose.onNodeWithTag("map_page_item_long").performClick()
        compose.onNodeWithText("Line number 0").assertExists()
        val composed = compose.onAllNodes(hasText("Line number", substring = true)).fetchSemanticsNodes().size
        assertTrue("only the visible part of a 400-block note is composed, was $composed", composed in 1..60)
        compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag("map_page_open_long"))
        compose.onNodeWithText("Line number 399").assertExists()
        compose.onNodeWithTag("map_page_open_long").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag("map_page_item_long"))
        compose.onNodeWithTag("map_page_item_long").performClick()
        compose.onNodeWithText("Line number 0").assertDoesNotExist()
        compose.onNodeWithTag("map_page_open_top_long").performClick()
        compose.runOnIdle { assertEquals(2, opened) }
    }

    @Test fun noteCardsAreTitledWithTheNotesOwnName() {
        assertEquals("Launch plan", noteCardTitle(VirlinNoteDoc("task:x", title = "  Launch \n plan ")))
        assertEquals("Notes", noteCardTitle(VirlinNoteDoc("task:x", title = "   ")))
        assertEquals("Notes", noteCardTitle(VirlinNoteDoc("task:x")))
        assertEquals("Notes", noteCardTitle(null))
        assertEquals("Note", noteCardTitle(null, fallback = "Note"))
    }

    @Test fun unreadableOrEmptyNotesKeepTheTextPreview() {
        assertNull(previewableNoteBlocks(null))
        assertNull(previewableNoteBlocks(VirlinNoteDoc("task:x")))
        assertNull(previewableNoteBlocks(VirlinNoteDoc("task:x",
            blocks = listOf(NoteDocBlock("a", text = "kept")), loadError = "bad json")))
        assertEquals(1, previewableNoteBlocks(VirlinNoteDoc("task:x",
            blocks = listOf(NoteDocBlock("a", text = "kept"))))?.size)
    }
}

package com.virlin.app.ui.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tapping words in the flashcard passage picks them, in tap order, for the Front or the Back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class FlashcardWordPicksTest {
    @get:Rule val compose = createComposeRule()

    private val blocks = listOf(
        NoteDocBlock("h", NoteDocBlockType.HEADING_1, "Start here"),
        NoteDocBlock("p", NoteDocBlockType.PARAGRAPH, "You already build real things, it's true.",
            runs = listOf(NoteDocRun(18, 22, setOf(NoteDocMark.BOLD)))),
    )
    private val text get() = selectableNoteText(flashcardBlocks(blocks)).text

    @Test fun wordAtFindsTheWholeWordAndSkipsSpacesAndPunctuation() {
        val t = "You already build, it's"
        assertEquals(TextRange(4, 11), flashcardWordAt(t, 6))
        assertEquals("a tap on a word's end still picks it", TextRange(4, 11), flashcardWordAt(t, 11))
        assertNull(flashcardWordAt("a , b", 2))
        assertEquals("it's", flashcardWordAt(t, t.length)?.let { t.substring(it.min, it.max) })
    }

    @Test fun picksKeepTapOrderToggleOffAndKeepMarks() {
        val passage = flashcardBlocks(blocks)
        fun word(w: String) = text.indexOf(w).let { TextRange(it, it + w.length) }
        var picks = emptyList<TextRange>()
        listOf("things", "real", "build").forEach { picks = togglePickedWord(passage, picks, word(it)) }
        picks = togglePickedWord(passage, picks, word("Start"))
        picks = togglePickedWord(passage, picks, word("Start"))
        val card = flashcardPickedBlocks(passage, picks).single()
        assertEquals("things real build", card.text)
        assertEquals(NoteDocBlockType.PARAGRAPH, card.type)
        assertEquals("bold 'real' keeps its mark", listOf(NoteDocRun(7, 11, setOf(NoteDocMark.BOLD))), card.runs)
    }

    @Test fun tappingWordsInTheDialogSendsThemInTapOrderToTheFront() {
        var saved: List<NoteDocBlock>? = null
        compose.setContent {
            MaterialTheme {
                FlashcardCreateDialog(blocks, saving = false, error = null, onDismiss = {}) { front, _, _, _, _ ->
                    saved = front
                }
            }
        }
        compose.waitForIdle()
        val source = compose.onNodeWithTag("flashcard_source")
        val results = mutableListOf<TextLayoutResult>()
        source.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.single()
        fun tap(word: String) {
            val at = text.indexOf(word) + 1
            val box = layout.getBoundingBox(at)
            source.performTouchInput { click(box.center) }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
        tap("things"); tap("real"); tap("build")
        compose.onNodeWithTag("flashcard_selection_status").assertTextEquals("3 words picked")
        compose.onNodeWithTag("flashcard_picked_words").assertTextEquals("things real build")
        tap("real")
        compose.onNodeWithTag("flashcard_picked_words").assertTextEquals("things build")
        tap("Start")
        compose.onNodeWithTag("flashcard_to_front").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_selection_status").assertTextEquals("Whole passage")
        compose.onNodeWithTag("flashcard_save").performClick()
        compose.runOnIdle { assertEquals(listOf("things build Start"), saved?.map { it.text }) }
    }
}

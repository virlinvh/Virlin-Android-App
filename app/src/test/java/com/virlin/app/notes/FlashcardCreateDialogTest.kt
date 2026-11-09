package com.virlin.app.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.ui.notes.FlashcardCreateDialog
import com.virlin.app.ui.notes.flashcardFrontFitsCardStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class FlashcardCreateDialogTest {
    @get:Rule val compose = createComposeRule()

    // Projected as "Heading\n\nAnswer text": the heading is 0..7, the answer 9..20.
    private val source = listOf(
        NoteDocBlock("h", NoteDocBlockType.HEADING_2, "Heading"),
        NoteDocBlock("p", NoteDocBlockType.PARAGRAPH, "Answer text",
            runs = listOf(NoteDocRun(0, 6, setOf(NoteDocMark.BOLD)))),
    )

    @Test fun backStartsAsWholePassageAndFrontStartsEmpty() {
        var submitted: Pair<List<NoteDocBlock>, List<NoteDocBlock>>? = null
        compose.setContent {
            MaterialTheme {
                FlashcardCreateDialog(source, saving = false, error = null, onDismiss = {},
                    onSave = { front, back, _, _, _ -> submitted = front to back })
            }
        }
        compose.onNodeWithTag("flashcard_save").assertIsNotEnabled()
        compose.onNodeWithTag("flashcard_source").performTextInputSelection(TextRange(0, 7))
        compose.onNodeWithTag("flashcard_to_front").performClick()
        compose.onNodeWithTag("flashcard_save").assertIsEnabled().performClick()
        assertEquals(listOf(NoteDocBlockType.HEADING_2), submitted!!.first.map { it.type })
        assertEquals("Heading", submitted!!.first.single().text)
        assertEquals(listOf("Heading", "Answer text"), submitted!!.second.map { it.text })
        assertEquals(source[1].runs, submitted!!.second[1].runs)
    }

    @Test fun selectedWordsGoToTheChosenSideWithTheirFormatting() {
        var submitted: Pair<List<NoteDocBlock>, List<NoteDocBlock>>? = null
        var plan: Pair<String, Int>? = null
        compose.setContent {
            MaterialTheme {
                FlashcardCreateDialog(source, saving = false, error = null, onDismiss = {},
                    onSave = { front, back, difficulty, days, _ ->
                        submitted = front to back
                        plan = difficulty to days
                    })
            }
        }
        compose.onNodeWithTag("flashcard_source").performTextInputSelection(TextRange(0, 7))
        compose.onNodeWithTag("flashcard_to_front").performClick()
        compose.onNodeWithTag("flashcard_source").performTextInputSelection(TextRange(9, 20))
        compose.onNodeWithTag("flashcard_to_back").performClick()
        compose.onNodeWithText("Hard").performScrollTo().performClick()
        compose.onNodeWithText("3d").performScrollTo().performClick()
        compose.onNodeWithTag("flashcard_save").performClick()
        val back = submitted!!.second.single()
        assertEquals(NoteDocBlockType.PARAGRAPH, back.type)
        assertEquals("Answer text", back.text)
        assertEquals(listOf(NoteDocRun(0, 6, setOf(NoteDocMark.BOLD))), back.runs)
        assertEquals("HARD" to 3, plan)
    }

    @Test fun clearingAFaceDisablesSaveWithoutChangingTheNote() {
        compose.setContent {
            MaterialTheme {
                FlashcardCreateDialog(source, saving = false, error = null,
                    onDismiss = {}, onSave = { _, _, _, _, _ -> })
            }
        }
        compose.onNodeWithTag("flashcard_to_front").performClick()
        compose.onNodeWithTag("flashcard_save").assertIsEnabled()
        compose.onNodeWithTag("flashcard_answer_remove").performScrollTo().performClick()
        compose.onNodeWithTag("flashcard_save").assertIsNotEnabled()
        assertEquals("Answer text", source[1].text)
    }

    @Test fun frontCardStyleStartsOnAndCanBeTurnedOff() {
        val saved = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                FlashcardCreateDialog(source, saving = false, error = null, onDismiss = {},
                    onSave = { _, _, _, _, cardStyle -> saved += cardStyle })
            }
        }
        compose.onNodeWithTag("flashcard_source").performTextInputSelection(TextRange(0, 7))
        compose.onNodeWithTag("flashcard_to_front").performClick()
        compose.onNodeWithTag("flashcard_front_style").performScrollTo().assertIsOn()
        compose.onNodeWithTag("flashcard_save").performClick()
        compose.onNodeWithTag("flashcard_front_style").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("flashcard_save").performClick()
        assertEquals(listOf(true, false), saved)
    }

    @Test fun frontWithCodeKeepsItsFormattingAndRestsTheCardStyle() {
        val code = listOf(NoteDocBlock("c", NoteDocBlockType.CODE, "val x = 1"))
        compose.setContent {
            MaterialTheme {
                FlashcardCreateDialog(code, saving = false, error = null, onDismiss = {},
                    onSave = { _, _, _, _, _ -> })
            }
        }
        compose.onNodeWithTag("flashcard_to_front").performClick()
        compose.onNodeWithTag("flashcard_front_style").performScrollTo().assertIsNotEnabled()
        assertTrue(flashcardFrontFitsCardStyle(source))
        assertFalse(flashcardFrontFitsCardStyle(code))
    }
}

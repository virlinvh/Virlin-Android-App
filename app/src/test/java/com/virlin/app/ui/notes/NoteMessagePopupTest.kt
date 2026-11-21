package com.virlin.app.ui.notes

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class NoteMessagePopupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsTheMessageAndOkCloses() {
        var closed = 0
        compose.setContent { NoteMessagePopup("Select blocks first.") { closed++ } }
        compose.onNodeWithTag("notes_message_text").assertTextEquals("Select blocks first.")
        compose.onNodeWithTag("notes_message_ok").performClick()
        assertEquals(1, closed)
    }
}

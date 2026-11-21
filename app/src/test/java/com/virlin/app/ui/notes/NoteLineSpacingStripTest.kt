package com.virlin.app.ui.notes

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
class NoteLineSpacingStripTest {
    @get:Rule val compose = createComposeRule()

    private val picked = mutableListOf<Float>()
    private var closed = 0

    private fun show(current: Float? = 1f) {
        compose.setContent {
            NoteLineSpacingStrip(NoteLineSpacingStripState(
                current = current, onPick = { picked += it }, onClose = { closed++ },
            ))
        }
        compose.waitForIdle()
    }

    private fun tap(label: String) {
        compose.onNodeWithTag("notes_spacing_options").performScrollToNode(hasTestTag("notes_spacing_$label"))
        compose.onNodeWithTag("notes_spacing_$label").performClick()
    }

    @Test fun everySpacingIsOfferedAndPicksItsValue() {
        show()
        listOf("Single", "1.25", "1.5", "Double").forEach(::tap)
        assertEquals(listOf(1f, 1.25f, 1.5f, 2f), picked)
        assertEquals(0, closed)
    }

    @Test fun currentSpacingIsMarked() {
        show(1.5f)
        compose.onNodeWithTag("notes_spacing_1.5").assertIsSelected()
        compose.onNodeWithTag("notes_spacing_Single").assertIsNotSelected()
    }

    @Test fun closeButtonCloses() {
        show()
        compose.onNodeWithTag("notes_spacing_close").performClick()
        assertEquals(1, closed)
    }
}

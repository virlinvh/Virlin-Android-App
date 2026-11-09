package com.virlin.app.ui.notes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
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
class NoteFontPickerSheetTest {
    @get:Rule val compose = createComposeRule()

    private val picked = mutableListOf<String>()

    private fun show(current: String = "Inter") {
        compose.setContent { NoteFontPickerSheet(current, onPick = { picked += it }, onDismiss = {}) }
        compose.waitForIdle()
    }

    @Test fun featuredListsBasicAndCuratedFontsAndPicksByName() {
        show()
        compose.onNodeWithTag("notes_font_Inter").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("notes_font_Serif").performClick()
        compose.onNodeWithTag("notes_font_list")
            .performScrollToKey("f:Handwritten:${NoteFontCategory.HANDWRITING}:Caveat")
        compose.onNodeWithTag("notes_font_Caveat").performClick()
        assertEquals(listOf("Serif", "Caveat"), picked)
    }

    @Test fun searchFindsFontsAcrossTheWholeCatalog() {
        show()
        compose.onNodeWithTag("notes_font_search").performTextInput("great vi")
        compose.waitForIdle()
        compose.onNodeWithTag("notes_font_Great Vibes").assertIsDisplayed().performClick()
        assertEquals(listOf("Great Vibes"), picked)
    }

    @Test fun categoryTabShowsThatStyle() {
        show(current = "Pacifico")
        val count = NoteFonts.inCategory(NoteFontCategory.HANDWRITING).size
        compose.onNodeWithTag("notes_font_tab_Handwriting $count").performClick()
        compose.waitForIdle()
        val first = NoteFonts.inCategory(NoteFontCategory.HANDWRITING).first().name
        compose.onNodeWithTag("notes_font_$first").assertIsDisplayed()
    }
}

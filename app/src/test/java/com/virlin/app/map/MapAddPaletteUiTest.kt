package com.virlin.app.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.map.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapAddPaletteUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allOptionsRestNeutralAndOnlyThePressedOptionUsesProjectColor() {
        val projectColor = Color(0xFF7F30D8)
        val idle = addChoiceStyle(MapAddKind.TODO, pressed = false, projectColor)
        assertEquals(Color.White, idle.background)
        MapAddKind.entries.forEach { kind ->
            assertEquals("$kind should not look preselected", idle, addChoiceStyle(kind, false, projectColor))
            val pressed = addChoiceStyle(kind, true, projectColor)
            assertEquals(true, pressed.background != idle.background)
            assertEquals(true, pressed.border != idle.border)
        }
    }

    @Test fun knowledgeEntryUsesTheSameNeutralClickableOptions() {
        val selected = mutableListOf<MapAddKind>()
        compose.setContent { MaterialTheme {
            MapAddPalette("Project knowledge", {}, { selected += it }, knowledgeMode = true)
        } }
        compose.onNodeWithTag("mind_map_add_todo").performClick()
        compose.onNodeWithTag("mind_map_add_note").performClick()
        compose.runOnIdle { assertEquals(listOf(MapAddKind.TODO, MapAddKind.NOTE), selected) }
    }

    @Test fun centeredPaletteKeepsEveryOptionVisibleAndClickable() {
        val selected = mutableListOf<MapAddKind>()
        compose.setContent {
            MaterialTheme {
                MapAddPalette("Project", {}, { selected += it }, dialogMode = true)
            }
        }
        MapAddKind.entries.forEach { kind ->
            compose.onNodeWithTag("mind_map_add_${kind.name.lowercase()}")
                .performScrollTo().assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(MapAddKind.entries.toList(), selected) }
    }

    @Test fun onlySupportedOptionsRemainAndTheirCallbacksAreUnchanged() {
        val selected = mutableListOf<MapAddKind>()
        var dismissed = false
        compose.setContent { MaterialTheme { MapAddPalette("Task", { dismissed = true }, { selected += it }) } }
        compose.onNodeWithText("Sticker").assertDoesNotExist()
        compose.onNodeWithText("Illustration").assertDoesNotExist()
        compose.onNodeWithText("Dimmed options have nowhere to be saved in this build yet.").assertDoesNotExist()
        val expected = listOf(MapAddKind.TODO, MapAddKind.NOTE, MapAddKind.TASK, MapAddKind.LINK,
            MapAddKind.PDF, MapAddKind.ATTACHMENT, MapAddKind.IMAGE, MapAddKind.AUDIO)
        assertEquals(expected.toSet(), MapAddKind.entries.toSet())
        expected.forEach { kind -> compose.onNodeWithTag("mind_map_add_${kind.name.lowercase()}").performScrollTo().performClick() }
        compose.runOnIdle { assertEquals(expected, selected) }
        compose.onNodeWithContentDescription("Close Add menu").performClick()
        compose.runOnIdle { assertEquals(true, dismissed) }
    }
}

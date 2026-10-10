package com.virlin.app.hierarchy

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.components.LocalOrbBounds
import com.virlin.app.ui.hierarchy.*
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KnowledgeOrbUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun addPaletteHidesOrbAndRestoresItOnDismiss() {
        var visible by mutableStateOf(true)
        compose.setContent {
            if (visible) com.virlin.app.ui.map.MapAddPalette(
                selectedTaskTitle = "Project", onDismiss = {}, onChoose = {}, knowledgeMode = true)
        }
        compose.runOnIdle { assertTrue(KnowledgeOrbVisibility.hidden) }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(KnowledgeOrbVisibility.hidden) }
    }
    @Test fun equalWidthCardsKeepMenusAccessibleWhenOrbCrossesThem() {
        var nearMenu by mutableStateOf(true)
        var opened = ""
        val actions = KnowledgeActions({}, { opened = it.id }, { _, _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, onMoveMany = { _, _, _ -> })
        compose.setContent {
            val density = LocalDensity.current
            val orb = with(density) { val top = if (nearMenu) 10.dp.toPx() else 160.dp.toPx()
                Rect(290.dp.toPx(), top, 342.dp.toPx(), top + 52.dp.toPx()) }
            CompositionLocalProvider(LocalOrbBounds provides orb) {
                Row(Modifier.width(360.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("left", "right").forEach { id -> Box(Modifier.weight(1f)) {
                        KnowledgeGalleryCard(KnowledgeItem(id, "p", KnowledgeType.PROMPT, id, updatedAtMillis = 0),
                            ZoneId.of("UTC"), emptyMap(), { _, _ -> }, actions, false, false, {}, {}, {}, {}, {})
                    } }
                }
            }
        }
        val left = compose.onNodeWithTag("knowledge_card_left").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("knowledge_card_right").fetchSemanticsNode().boundsInRoot
        assertEquals(left.width, right.width, 1f) // odd physical pixels split between weighted columns
        val shifted = compose.onNodeWithContentDescription("Options for right").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Options for right").performClick()
        compose.onNodeWithText("Duplicate").assertIsDisplayed()
        compose.onNodeWithText("Duplicate").performClick()
        compose.runOnIdle { nearMenu = false }
        val restored = compose.onNodeWithContentDescription("Options for right").fetchSemanticsNode().boundsInRoot
        assertEquals("Knowledge menus no longer reserve space for a hidden orb", shifted.right, restored.right, 1f)
        compose.onNodeWithTag("knowledge_card_left").performClick()
        compose.runOnIdle { assertEquals("left", opened) }
    }
}

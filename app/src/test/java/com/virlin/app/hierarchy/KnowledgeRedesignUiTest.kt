package com.virlin.app.hierarchy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp")
class KnowledgeRedesignUiTest {
    @get:Rule val compose = createComposeRule()
    private val items = listOf(
        KnowledgeItem("note", "p", KnowledgeType.DOCUMENT, "Architecture decisions", "Engine-independent foundation", updatedAtMillis = 2000, kindLabel = "Note"),
        KnowledgeItem("prompt", "p", KnowledgeType.PROMPT, "Build assistant", "Implement the foundation", updatedAtMillis = 1000, kindLabel = "Prompt"),
        KnowledgeItem("pdf", "p", KnowledgeType.DOCUMENT, "Foundation brief", updatedAtMillis = 3000, kindLabel = "PDF"))
    private fun show(onOpen: (KnowledgeItem) -> Unit = {}) {
        val actions = KnowledgeActions({}, onOpen, { _, _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> },
            { _, _, _ -> }, onMoveMany = { _, _, _ -> })
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalProjectPalette provides ProjectPalette.from(Color(0xFF2563EB))) {
                VirlinProjectKnowledgeLibrary("p", items, workstreams = emptyList(), tasks = emptyList(), actions = actions, imagePreview = { _, _ -> })
            }
        } }
    }
    @Test fun notesAndMediaFiltersUseActualContentKinds() {
        show()
        compose.onNodeWithText("Notes 1").performClick()
        compose.onNodeWithText("Architecture decisions").assertExists()
        compose.onNodeWithText("Foundation brief").assertDoesNotExist()
        compose.onNodeWithText("Media 1").performScrollTo().performClick()
        compose.onNodeWithText("Foundation brief").assertExists()
        compose.onNodeWithText("Architecture decisions").assertDoesNotExist()
    }
    @Test fun searchAndOpenUseTheExistingItemIdentity() {
        var opened = ""
        show { opened = it.id }
        compose.onNode(hasSetTextAction()).performTextInput("Architecture")
        compose.onNodeWithTag("knowledge_card_note").performClick()
        compose.runOnIdle { assertEquals("note", opened) }
        compose.onNodeWithTag("knowledge_card_prompt").assertDoesNotExist()
    }
    @Test fun gridListAndRecentControlsWorkWithoutChangingItems() {
        show()
        val gridWidth = compose.onNodeWithTag("knowledge_card_pdf").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithContentDescription("Show list").performClick()
        val listWidth = compose.onNodeWithTag("knowledge_card_pdf").fetchSemanticsNode().boundsInRoot.width
        assertTrue(listWidth > gridWidth)
        compose.onNodeWithText("Recent ↓").performClick()
        compose.onNodeWithText("Oldest ↑").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show grid").assertIsDisplayed()
    }
    @Test fun projectPaletteControlsCardTypeInkAndLibrarySuppressesOrb() {
        show()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("PDF").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(readableOn(Color(ProjectPalette.from(Color(0xFF2563EB)).secondary)), layouts.single().layoutInput.style.color)
        compose.runOnIdle { assertTrue(KnowledgeOrbVisibility.hidden) }
    }
}

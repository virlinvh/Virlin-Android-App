package com.virlin.app.now

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.screens.NowEmptyFocusCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowEmptyFocusCardTest {
    @get:Rule val compose = createComposeRule()

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.first()
    }

    @Test @Config(qualifiers = "w320dp-h640dp")
    fun headlineAndPromptEachFitOneLineOnANarrowPhone() {
        var chosen = 0
        compose.setContent {
            Box(Modifier.width(288.dp)) {
                NowEmptyFocusCard(chooseTaskLabel = "Show tasks ready to focus") { chosen++ }
            }
        }
        compose.onNodeWithText("READY TO FOCUS").assertExists()
        listOf("Your attention is free.", "Choose where you want to make progress next.").forEach {
            val result = layout(it)
            assertEquals("$it stays on one line", 1, result.lineCount)
            assertEquals("$it is not cut off", false, result.isLineEllipsized(0))
        }
        assertEquals(FontStyle.Italic, layout("Choose where you want to make progress next.").layoutInput.style.fontStyle)
        compose.onNodeWithTag("now_choose_task").assert(SemanticsMatcher("labelled for When you're free") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "Show tasks ready to focus"
        }).performClick()
        assertEquals(1, chosen)
    }
}

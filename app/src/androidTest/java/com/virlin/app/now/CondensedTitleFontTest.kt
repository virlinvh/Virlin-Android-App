package com.virlin.app.now

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.screens.VirlinCondensedTitle
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The reference title is condensed. This proves the family actually resolves to a narrower face on
 * the device rather than silently falling back to the default sans — the same string, same size,
 * must measure narrower.
 */
class CondensedTitleFontTest {

    @get:Rule val composeRule = createComposeRule()

    private val sample = "Navigation · Route structure decision"

    @Test fun condensedFamilyMeasuresNarrowerThanTheDefaultSans() {
        composeRule.setContent {
            Box(Modifier.width(2000.dp)) {
                Text(sample, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
                    fontSize = 15.sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.testTag("wide"))
                Text(sample, fontFamily = VirlinCondensedTitle, fontWeight = FontWeight.Bold,
                    fontSize = 15.sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.testTag("condensed"))
            }
        }
        composeRule.waitForIdle()
        val wide = composeRule.onNodeWithTag("wide").fetchSemanticsNode().size.width
        val condensed = composeRule.onNodeWithTag("condensed").fetchSemanticsNode().size.width
        assertTrue("condensed ($condensed) must be narrower than default ($wide)", condensed < wide)
    }
}

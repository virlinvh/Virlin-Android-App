package com.virlin.app.hierarchy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.Color
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProjectAppearanceUiTest {
    @get:Rule val compose=createComposeRule()
    private val prefs get()=ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("project_appearance",0)
    @Test fun paletteRoundTripsAndInvalidDataFallsBack() {
        assertEquals(24,projectColorChoices.size)
        val p=ProjectPalette.from(Color(0xFF2563EB))
        assertEquals(p,ProjectPalette.decode(p.encode()))
        assertNull(ProjectPalette.decode("broken"))
        assertEquals(Color.Black,readableOn(Color.White))
        assertEquals(Color.White,readableOn(Color.Black))
        projectColorChoices.forEach { (name,value) -> assertTrue("$name preset contrast",readablePalette(ProjectPalette.from(Color(value)))) }
    }
    @Test fun draftsDoNotPersistUntilSave_andOtherProjectIsUntouched() {
        prefs.edit().clear().putString("other","unchanged").commit()
        compose.setContent { ProjectColorsEditor("p") {} }
        compose.onNodeWithText("Ocean").performClick()
        assertFalse(prefs.contains("p"))
        compose.onNodeWithTag("save_project_colors").performClick()
        assertEquals(0xFF2563EB.toInt(),ProjectPalette.decode(prefs.getString("p",null))!!.main)
        assertEquals("unchanged",prefs.getString("other",null))
    }
    @Test fun discardKeepsSavedPalette() {
        val p=ProjectPalette.from(Color(0xFF166534))
        prefs.edit().clear().putString("p",p.encode()).commit()
        compose.setContent { ProjectColorsEditor("p") {} }
        compose.onNodeWithText("Ocean").performClick()
        compose.onNodeWithText("Discard preview").performClick()
        assertEquals(p,ProjectPalette.decode(prefs.getString("p",null)))
    }
    @Test fun unreadableCustomText_cannotBeSaved() {
        prefs.edit().clear().commit()
        compose.setContent { ProjectColorsEditor("p") {} }
        compose.onNodeWithText("Ocean").performClick()
        compose.onNodeWithText("Heading text").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("#FFFFFF")
        compose.onNodeWithText("Use hex color").performScrollTo().performClick()
        compose.onNodeWithTag("save_project_colors").assertIsNotEnabled()
        assertFalse(prefs.contains("p"))
    }
    @Test fun resetRemovesOnlySelectedProject_onSave() {
        prefs.edit().clear().putString("p",ProjectPalette.from(Color.Blue).encode()).putString("other","keep").commit()
        compose.setContent { ProjectColorsEditor("p") {} }
        compose.onNodeWithText("Reset to original").performScrollTo().performClick()
        assertTrue(prefs.contains("p"))
        compose.onNodeWithTag("save_project_colors").performClick()
        assertFalse(prefs.contains("p"));assertEquals("keep",prefs.getString("other",null))
    }
}

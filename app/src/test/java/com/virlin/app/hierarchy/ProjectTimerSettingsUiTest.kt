package com.virlin.app.hierarchy

import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.virlin.app.domain.model.Project
import com.virlin.app.ui.hierarchy.ProjectSettingsDialog
import com.virlin.app.ui.hierarchy.timerRotationSeconds
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class ProjectTimerSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun projectSettings_exposesAndPersistsTimerRotation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("hierarchy_timer_display", 0)
        prefs.edit().clear().commit()
        val now = Instant.parse("2026-10-04T08:00:00Z")
        compose.setContent {
            ProjectSettingsDialog(Project("p", "Project", createdAt = now, updatedAt = now), emptyList(),
                onDismiss = {}, onRename = {}, onEditIcon = {}, onExecutionDefault = {},
                onRestoreTask = {}, onCompleteProject = {})
        }
        compose.onNodeWithText("Timers & display").performScrollTo().performClick()
        compose.onNodeWithText("5 s").performClick()
        compose.onNodeWithText("5 s · selected").assertExists()
        assertEquals(5, prefs.getInt("project:p", 0))
    }

    @Test fun projectPreference_updatesDescendantDisplaysAndIsolatesOtherProjects() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("hierarchy_timer_display", 0)
        prefs.edit().clear().putInt("leaf", 7).putInt("project:p", 5).commit()
        compose.setContent {
            Text("Own ${timerRotationSeconds("leaf", "p")}")
            Text("Other ${timerRotationSeconds("other-leaf", "other")}")
        }
        compose.onNodeWithText("Own 5").assertExists()
        compose.onNodeWithText("Other 6").assertExists()
        compose.runOnIdle { prefs.edit().putInt("project:p", 7).commit() }
        compose.onNodeWithText("Own 7").assertExists()
        compose.onNodeWithText("Other 6").assertExists()
    }
}

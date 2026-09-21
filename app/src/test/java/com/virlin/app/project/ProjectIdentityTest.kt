package com.virlin.app.project

import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.Field
import com.virlin.app.domain.action.ProjectUpdate
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.ProjectIdentity
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.components.ProjectIconFallback
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import androidx.compose.ui.graphics.luminance

/** Project identity icon foundation — pure rules + persistence through the existing action layer. */
class ProjectIdentityTest {

    private val t0: Instant = Instant.parse("2026-09-21T09:00:00Z")
    private fun project(id: String, title: String, iconPath: String? = null) = Project(id = id, title = title, createdAt = t0, updatedAt = t0, iconPath = iconPath)

    // ------------------------------------------------------------------ deterministic fallback initials

    @Test fun initials_toolAndProject() = assertEquals("CV", ProjectIdentity.initials("Claude · Virlin"))
    @Test fun initials_singleWord() = assertEquals("A", ProjectIdentity.initials("Antigravity"))
    @Test fun initials_threeWords_takesFirstTwo() = assertEquals("CM", ProjectIdentity.initials("Codex · MBA Research"))
    @Test fun initials_plainProjectTitle() = assertEquals("VD", ProjectIdentity.initials("Virlin Development"))
    @Test fun initials_lowercaseAndPunctuation() = assertEquals("AF", ProjectIdentity.initials("app-fix"))
    @Test fun initials_noLetters_fallsBackToQuestionMark() = assertEquals("?", ProjectIdentity.initials(" · · "))
    @Test fun initials_areStable() = assertEquals(ProjectIdentity.initials("Claude · Virlin"), ProjectIdentity.initials("Claude · Virlin"))

    // ------------------------------------------------------------------ deterministic colour

    @Test fun hue_isDeterministic_perId() {
        assertEquals(ProjectIdentity.hue("p1"), ProjectIdentity.hue("p1"))
        assertTrue(ProjectIdentity.hue("p1") in 0..359)
    }

    @Test fun hue_differsBetweenDemoProjects() {
        val hues = listOf("p1", "p2", "p3", "p4", "p5").map { ProjectIdentity.hue(it) }
        assertEquals(hues.size, hues.distinct().size)
    }

    @Test fun fallbackColours_areDeterministic_andContrast() {
        assertEquals(ProjectIconFallback.container("p1"), ProjectIconFallback.container("p1"))
        assertNotEquals(ProjectIconFallback.container("p1"), ProjectIconFallback.container("p2"))
        val c = ProjectIconFallback.container("p3"); val f = ProjectIconFallback.foreground("p3")
        assertTrue("light container, dark initials", c.luminance() > 0.7f && f.luminance() < 0.2f)
    }

    // ------------------------------------------------------------------ custom icon presence + resolution

    @Test fun hasCustomIcon_rules() {
        assertFalse(ProjectIdentity.hasCustomIcon(null)); assertFalse(ProjectIdentity.hasCustomIcon("  "))
        assertTrue(ProjectIdentity.hasCustomIcon("p1/icon-1.png"))
    }

    @Test fun resolve_taskToProject_viaProjectId() {
        val projects = listOf(project("p1", "Virlin Development", "p1/icon-1.png"), project("p4", "App Fix"))
        val stream = WorkStream(id = "s3", title = "Claude · Virlin", projectId = "p1", state = WorkStreamState.CHECK, createdAt = t0, updatedAt = t0)
        val resolved = ProjectIdentity.resolve(stream.projectId, projects)
        assertEquals("p1", resolved?.id); assertEquals("p1/icon-1.png", resolved?.iconPath)
    }

    @Test fun resolve_sameProject_sameIdentity_forTwoStreams() {
        val projects = listOf(project("p1", "Virlin Development", "p1/icon-1.png"))
        val a = ProjectIdentity.resolve("p1", projects); val b = ProjectIdentity.resolve("p1", projects)
        assertEquals(a, b)
    }

    @Test fun resolve_missingOrProjectless_isNull() {
        val projects = listOf(project("p1", "Virlin Development"))
        assertNull(ProjectIdentity.resolve("gone", projects)); assertNull(ProjectIdentity.resolve(null, projects))
    }

    @Test fun invalidReference_readsAsNoCustomIcon_whenCleared() {
        // A blank/whitespace stored reference never counts as a custom icon → fallback avatar.
        assertFalse(ProjectIdentity.hasCustomIcon(""))
    }

    // ------------------------------------------------------------------ persistence through the action layer (single source of truth)

    @Test fun updateProject_setsAndClearsIconPath_only() = runBlocking {
        val repo = InMemoryWorkStreamRepository(seedProjects = listOf(project("p1", "Virlin Development")))
        val actions = DefaultVirlinActions(repo, FakeClock(t0), SequentialIdProvider())
        val set = actions.updateProject("p1", ProjectUpdate(iconPath = Field.Set("p1/icon-1.png")))
        assertTrue(set is ActionResult.Success)
        assertEquals("p1/icon-1.png", repo.getProject("p1")?.iconPath)
        assertEquals("Virlin Development", repo.getProject("p1")?.title)          // nothing else touched
        val cleared = actions.updateProject("p1", ProjectUpdate(iconPath = Field.Clear))
        assertTrue(cleared is ActionResult.Success)
        assertNull(repo.getProject("p1")?.iconPath)
        val kept = actions.updateProject("p1", ProjectUpdate(title = Field.Set("Virlin")))
        assertTrue(kept is ActionResult.Success)
        assertNull(repo.getProject("p1")?.iconPath)                                // Keep leaves it alone
    }

    @Test fun projectIcon_isNotDuplicatedOnStreams() {
        // The WorkStream/Task models carry no icon field: identity is always resolved via projectId.
        val fields = WorkStream::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("icon", ignoreCase = true) })
    }
}

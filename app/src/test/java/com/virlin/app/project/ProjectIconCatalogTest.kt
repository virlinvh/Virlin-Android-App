package com.virlin.app.project

import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.Field
import com.virlin.app.domain.action.ProjectUpdate
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.ProjectIconCatalog
import com.virlin.app.domain.model.ProjectIconSelection
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.components.BuiltInProjectIcons
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Built-in icon library, automatic resolver, selection priority and persistence (Phase 3). */
class ProjectIconCatalogTest {

    private val t0: Instant = Instant.parse("2026-09-21T09:00:00Z")
    private fun project(id: String, title: String, iconPath: String? = null, iconId: String? = null) =
        Project(id = id, title = title, createdAt = t0, updatedAt = t0, iconPath = iconPath, iconId = iconId)

    // ------------------------------------------------------------------ library

    @Test fun catalog_has16to24_uniqueKnownIds_withLooks() {
        assertTrue(ProjectIconCatalog.ids.size in 16..24)
        assertEquals(ProjectIconCatalog.ids.size, ProjectIconCatalog.ids.distinct().size)
        ProjectIconCatalog.ids.forEach { id ->
            assertTrue(id, ProjectIconCatalog.isKnown(id)); assertTrue(id, BuiltInProjectIcons.lookOf(id) != null)
            assertEquals(id, ProjectIconCatalog.label(id), BuiltInProjectIcons.lookOf(id)!!.label)
        }
        assertFalse(ProjectIconCatalog.isKnown("not-an-icon")); assertNull(BuiltInProjectIcons.lookOf("not-an-icon"))
    }

    @Test fun looks_areDeterministic_andDistinctSurfaces() {
        assertEquals(BuiltInProjectIcons.lookOf("code"), BuiltInProjectIcons.lookOf("code"))
        val surfaces = BuiltInProjectIcons.all.map { it.surface }
        assertTrue(surfaces.distinct().size >= surfaces.size - 2)   // near-unique low-saturation surfaces
    }

    // ------------------------------------------------------------------ A: automatic defaults (generic keyword rules)

    @Test fun auto_demoProjects_resolveAsExpected() {
        assertEquals("code", ProjectIconCatalog.autoIconId("Virlin Development", "p1"))
        assertEquals("business", ProjectIconCatalog.autoIconId("MBA Project", "p2"))
        assertEquals("brain", ProjectIconCatalog.autoIconId("Psychology", "p3"))
        assertEquals("tools", ProjectIconCatalog.autoIconId("App Fix", "p4"))
        assertEquals("education", ProjectIconCatalog.autoIconId("IFET Skills Lab", "p5"))
    }

    @Test fun auto_isGenericRuleBased_notDemoSpecific() {
        assertEquals("code", ProjectIconCatalog.autoIconId("Software rewrite", "x"))
        assertEquals("research", ProjectIconCatalog.autoIconId("Thesis research", "x"))
        assertEquals("cloud", ProjectIconCatalog.autoIconId("Cloud migration", "x"))
        assertEquals("design", ProjectIconCatalog.autoIconId("Brand design refresh", "x"))
        assertEquals("automation", ProjectIconCatalog.autoIconId("Release pipeline automation", "x"))
    }

    @Test fun auto_keywordsMatchWordStartsOnly() {
        // "ai" must not fire inside "maintenance"; "fix" → tools does.
        assertEquals("tools", ProjectIconCatalog.autoIconId("Maintenance fix", "x"))
        assertEquals("ai", ProjectIconCatalog.autoIconId("AI assistant", "x"))
    }

    @Test fun auto_noMatch_isDeterministicGeneric() {
        val a = ProjectIconCatalog.autoIconId("Zzz", "pq"); val b = ProjectIconCatalog.autoIconId("Zzz", "pq")
        assertEquals(a, b); assertTrue(ProjectIconCatalog.isKnown(a))
    }

    // ------------------------------------------------------------------ selection priority

    @Test fun selection_priority_custom_builtIn_auto() {
        assertEquals(ProjectIconSelection.Custom("p/i.png"), ProjectIconSelection.of(project("p1", "Virlin Development", "p/i.png", "rocket")))
        assertEquals(ProjectIconSelection.BuiltIn("rocket"), ProjectIconSelection.of(project("p1", "Virlin Development", null, "rocket")))
        assertEquals(ProjectIconSelection.Auto("code"), ProjectIconSelection.of(project("p1", "Virlin Development")))
        assertEquals(ProjectIconSelection.Auto("code"), ProjectIconSelection.of(project("p1", "Virlin Development", iconId = "bogus")))  // G: unknown id → auto
    }

    // ------------------------------------------------------------------ B–F: persistence through the action layer

    @Test fun builtIn_persists_switches_andResetsToAuto() = runBlocking {
        val repo = InMemoryWorkStreamRepository(seedProjects = listOf(project("p3", "Psychology")))
        val actions = DefaultVirlinActions(repo, FakeClock(t0), SequentialIdProvider())
        // A: without explicit choice → automatic brain
        assertEquals(ProjectIconSelection.Auto("brain"), ProjectIconSelection.of(repo.getProject("p3")!!))
        // B: select built-in → persists (C: re-read from the repository = reload)
        assertTrue(actions.updateProject("p3", ProjectUpdate(iconId = Field.Set("rocket"))) is ActionResult.Success)
        assertEquals("rocket", repo.getProject("p3")!!.iconId)
        assertEquals(ProjectIconSelection.BuiltIn("rocket"), ProjectIconSelection.of(repo.getProject("p3")!!))
        // D: built-in → custom: custom wins
        actions.updateProject("p3", ProjectUpdate(iconPath = Field.Set("p3/icon-1.png")))
        assertEquals(ProjectIconSelection.Custom("p3/icon-1.png"), ProjectIconSelection.of(repo.getProject("p3")!!))
        // E: custom → built-in (editor clears the image and sets the id)
        actions.updateProject("p3", ProjectUpdate(iconId = Field.Set("brain"), iconPath = Field.Clear))
        assertEquals(ProjectIconSelection.BuiltIn("brain"), ProjectIconSelection.of(repo.getProject("p3")!!))
        // F: reset to Auto → deterministic automatic icon returns
        actions.updateProject("p3", ProjectUpdate(iconId = Field.Clear, iconPath = Field.Clear))
        assertEquals(ProjectIconSelection.Auto("brain"), ProjectIconSelection.of(repo.getProject("p3")!!))
        // unknown ids never persist
        actions.updateProject("p3", ProjectUpdate(iconId = Field.Set("nope")))
        assertNull(repo.getProject("p3")!!.iconId)
    }
}

package com.virlin.app.now

import com.virlin.app.domain.model.*
import com.virlin.app.ui.screens.WhenFreePresentation
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WhenFreePresentationTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    @Test fun hierarchy_nearestParentFirst_doesNotRepeatLeaf() {
        val p = Project("p", "Project", createdAt = now, updatedAt = now)
        val w = WorkStream("w", "Workstream", projectId = p.id, state = WorkStreamState.READY, createdAt = now, updatedAt = now)
        val root = Task("root", "Root", workStreamId = w.id, createdAt = now, updatedAt = now)
        val parent = root.copy(id = "parent", title = "Parent", parentTaskId = root.id)
        val leaf = parent.copy(id = "leaf", title = "Leaf", parentTaskId = parent.id)
        assertEquals("Parent › Root › Workstream › Project", WhenFreePresentation.hierarchy(leaf.id, listOf(p), listOf(root, parent, leaf), listOf(w)))
        assertEquals("Project", WhenFreePresentation.hierarchy(w.id, listOf(p), emptyList(), listOf(w)))
    }
    @Test fun zeroFocus_isNotAnEstimate() {
        assertEquals("Focused 0m", WhenFreePresentation.focusedLabel("w", emptyList(), emptyList(), now))
    }
    @Test fun recordedFocus_excludesSiblings_andDoesNotDoubleCountSessions() {
        val leaf = Task("leaf", "Leaf", workStreamId = "w", createdAt = now, updatedAt = now)
        val own = FocusSession("s", "w", null, now.minusSeconds(1500), now, leaf.id)
        val sibling = own.copy(id = "other", taskId = "sibling", startedAt = now.minusSeconds(300))
        assertEquals("Focused 25m", WhenFreePresentation.focusedLabel(leaf.id, listOf(leaf), listOf(own, own, sibling), now))
        assertEquals("Focused 30m", WhenFreePresentation.focusedLabel("w", listOf(leaf), listOf(own, own, sibling), now))
    }
}

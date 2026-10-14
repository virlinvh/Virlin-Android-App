package com.virlin.app.map

import com.virlin.app.ui.map.*
import org.junit.Assert.*
import org.junit.Test

class MapBranchFoldingTest {
    @Test fun toolbarMovesOneLevelAtATimeAndStopsAtBothEnds() {
        val tree = listOf(MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            MapTopic("w", "p", "Workstream", kind = MapKind.WORKSTREAM),
            MapTopic("t", "w", "Task", kind = MapKind.TASK),
            MapTopic("s1", "t", "Subtask", kind = MapKind.TASK),
            MapTopic("s2", "s1", "Nested subtask", kind = MapKind.TASK),
            MapTopic("short", "p", "Short branch", kind = MapKind.TASK))
        var folded = emptySet<String>()
        listOf(5, 4, 3, 1, 1).forEach { count ->
            folded = collapseMapLevel(tree, folded)
            assertEquals(count, foldedMapTopics(tree, folded).size)
        }
        listOf(3, 4, 5, 6, 6).forEach { count ->
            folded = expandMapLevel(tree, folded)
            assertEquals(count, foldedMapTopics(tree, folded).size)
        }
        assertEquals(tree, foldedMapTopics(tree, folded))
        // Opening an individually folded root must not reveal all descendants at once.
        assertEquals(3, foldedMapTopics(tree, expandMapLevel(tree, setOf("p"))).size)
    }
    private val topics = listOf(
        MapTopic("p", null, "Project", kind = MapKind.PROJECT),
        MapTopic("a", "p", "Branch", kind = MapKind.TASK),
        MapTopic("b", "a", "Child", kind = MapKind.TASK),
        MapTopic("c", "b", "Grandchild", kind = MapKind.TASK),
        MapTopic("d", "p", "Sibling", kind = MapKind.TASK))
    @Test fun countsIncludeAllDescendantLevelsButNotParent() {
        assertEquals(setOf("b", "c"), mapDescendants(topics, "a"))
        assertEquals(4, mapDescendants(topics, "p").size)
        assertTrue(mapDescendants(topics, "d").isEmpty())
    }
    @Test fun focusUsesSelectedTaskAsViewRootWithoutChangingCanonicalTree() {
        val focused = focusedMapTopics(topics, "a")
        assertEquals(listOf("a", "b", "c"), focused.map { it.id })
        assertNull(focused.first().parentId)
        assertEquals("p", topics[1].parentId)
        assertEquals(setOf("b"), collapseMapLevel(focused, emptySet()))
        assertEquals(listOf("a", "b"), foldedMapTopics(focused, setOf("b")).map { it.id })
        assertEquals(topics, focusedMapTopics(topics, null))
    }
    @Test fun selectedBranchControlsFoldOnlyItsDescendantsOneLevelAtATime() {
        val tree = listOf(
            MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            MapTopic("a", "p", "Selected", kind = MapKind.WORKSTREAM),
            MapTopic("a1", "a", "Task", kind = MapKind.TASK),
            MapTopic("a2", "a1", "Subtask", kind = MapKind.TASK),
            MapTopic("b", "p", "Other", kind = MapKind.WORKSTREAM),
            MapTopic("b1", "b", "Other task", kind = MapKind.TASK),
        )
        var folded = collapseMapBranchLevel(tree, setOf("b"), "a")
        assertEquals(setOf("a1", "b"), folded)
        assertEquals(listOf("p", "a", "a1", "b"), foldedMapTopics(tree, folded).map { it.id })
        folded = collapseMapBranchLevel(tree, folded, "a")
        assertEquals(setOf("a", "a1", "b"), folded)
        assertEquals(listOf("p", "a", "b"), foldedMapTopics(tree, folded).map { it.id })
        folded = expandMapBranchLevel(tree, folded, "a")
        assertEquals(setOf("a1", "b"), folded)
        folded = expandMapBranchLevel(tree, folded, "a")
        assertEquals(setOf("b"), folded)
        assertEquals(listOf("p", "a", "a1", "a2", "b"), foldedMapTopics(tree, folded).map { it.id })
        assertEquals(folded, collapseMapBranchLevel(tree, folded, "missing"))
        assertEquals(folded, expandMapBranchLevel(tree, folded, "a2"))
    }
    @Test fun collapseAndReopenPreserveCanonicalTopicsAndSibling() {
        assertEquals(listOf("p", "a", "d"), foldedMapTopics(topics, setOf("a")).map { it.id })
        assertEquals(topics, foldedMapTopics(topics, emptySet()))
        assertEquals(5, topics.size)
    }
    @Test fun nestedFoldIsRememberedAndExpandAllRestoresEveryNode() {
        assertEquals(listOf("p", "a", "d"), foldedMapTopics(topics, setOf("a", "b")).map { it.id })
        assertEquals(listOf("p", "a", "b", "d"), foldedMapTopics(topics, setOf("b")).map { it.id })
        assertEquals(topics, foldedMapTopics(topics, emptySet()))
        assertEquals(listOf("p"), foldedMapTopics(topics, setOf("p", "a", "b")).map { it.id })
    }
    @Test fun staleIdsAndCyclesDoNotLoop() {
        assertEquals(topics, foldedMapTopics(topics, setOf("deleted")))
        val cycle = listOf(topics[1].copy(parentId = "b"), topics[2])
        assertEquals(setOf("b"), mapDescendants(cycle, "a"))
    }
    @Test fun deepWorkstreamAndSubtaskHierarchyHasNoDepthLimit() {
        val deep = listOf(topics[0], MapTopic("w", "p", "Workstream", kind = MapKind.WORKSTREAM)) +
            (1..1000).map { level ->
                MapTopic("t$level", if (level == 1) "w" else "t${level - 1}", "Task $level", kind = MapKind.TASK)
            }
        assertEquals(1001, mapDescendants(deep, "p").size)
        assertEquals(listOf(deep.first()), foldedMapTopics(deep, setOf("p")))
        assertEquals(deep, foldedMapTopics(deep, emptySet()))
    }
}

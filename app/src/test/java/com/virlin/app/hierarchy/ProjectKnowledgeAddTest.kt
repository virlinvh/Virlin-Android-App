package com.virlin.app.hierarchy

import com.virlin.app.ui.hierarchy.projectKnowledgeBlocks
import com.virlin.app.domain.model.NoteBlockType
import org.junit.Assert.*
import org.junit.Test

class ProjectKnowledgeAddTest {
    @Test fun checklistPreservesLinesAndCanBeEditedAsRealCheckboxBlocks() {
        val blocks = projectKnowledgeBlocks("First\n\nSecond", true)
        assertEquals(listOf("First", "Second"), blocks.map { it.plainText })
        assertTrue(blocks.all { it.type == NoteBlockType.CHECKBOX && !it.checked })
        assertEquals(2, blocks.map { it.id }.toSet().size)
    }
    @Test fun notePreservesContentExactly() {
        val body = "First\n\n  Second"
        val block = projectKnowledgeBlocks(body, false).single()
        assertEquals(body, block.plainText)
        assertEquals(NoteBlockType.TEXT, block.type)
    }
}

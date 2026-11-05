package com.virlin.app.todo

import com.virlin.app.ui.todo.TodoBulkImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TodoBulkImportTest {
    @Test fun multilinePasteFlattensMarkersAndPreservesOrder() {
        assertEquals(listOf("Review requirements", "Check the first version", "Verify the final result"),
            TodoBulkImport.parse(TodoBulkImport.sample))
        assertEquals(listOf("A", "B", "C", "D"),
            TodoBulkImport.parse("- [ ] A\n  * B\n2) C\n\nD"))
    }

    @Test fun rejectsInvalidInputBeforeAnyWrite() {
        assertTrue(runCatching { TodoBulkImport.parse("  \n ") }.isFailure)
        assertTrue(runCatching { TodoBulkImport.parse("a".repeat(201)) }.isFailure)
        assertTrue(runCatching { TodoBulkImport.parse("a\n".repeat(501)) }.isFailure)
        assertTrue(runCatching { TodoBulkImport.parse("""{"version":2,"items":[]}""") }.isFailure)
    }
}

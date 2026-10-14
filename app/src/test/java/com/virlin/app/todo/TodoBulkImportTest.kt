package com.virlin.app.todo

import com.virlin.app.ui.todo.TodoBulkImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TodoBulkImportTest {
    @Test fun sampleUsesExistingBulkFormatAndPreservesOrder() {
        assertEquals(listOf("Review requirements", "Check final result"),
            TodoBulkImport.parse(TodoBulkImport.sample))
    }

    @Test fun rejectsNestedOrInvalidItemsBeforeAnyWrite() {
        assertTrue(runCatching { TodoBulkImport.parse("""{"version":2,"items":[{"name":"A","children":[{"name":"B"}]}]}""") }.isFailure)
        assertTrue(runCatching { TodoBulkImport.parse("""{"version":2,"items":[{"name":"A"},{"name":"a"}]}""") }.isFailure)
        assertTrue(runCatching { TodoBulkImport.parse("not json") }.isFailure)
    }
}

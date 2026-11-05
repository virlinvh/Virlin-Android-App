package com.virlin.app.hierarchy

import com.virlin.app.domain.model.Task
import com.virlin.app.ui.page.taskPageBranchIds
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskPageBranchTest {
    private fun task(id: String, parent: String? = null, order: Int = 0) = Task(
        id = id, title = id, projectId = "project", workStreamId = "stream",
        parentTaskId = parent, order = order, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH
    )

    @Test fun branchIncludesRootAndNestedSubtasksButNotSiblings() {
        val tasks = listOf(task("root"), task("sibling"), task("first", "root"),
            task("nested", "first"), task("second", "root", 1))
        assertEquals(listOf("root", "first", "nested", "second"), taskPageBranchIds(tasks, "root"))
        assertEquals(listOf("first", "nested"), taskPageBranchIds(tasks, "first"))
    }
}

package com.virlin.app.hierarchy

import com.virlin.app.domain.action.*
import com.virlin.app.ui.hierarchy.taskCreationError
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskCreationErrorTest {
    @Test fun activityRejectionIsNotHiddenByGenericMessage() {
        val reason = "End this item's activity and reminders before adding children. Recorded time will remain saved."
        assertEquals(reason, taskCreationError(ActionResult.Rejected(DomainError.PlacementRejected(reason))))
    }
    @Test fun blankNamesExplainCorrection() {
        assertEquals("Enter a task name.", taskCreationError(ActionResult.Rejected(DomainError.EmptyTitle)))
    }
}

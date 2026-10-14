package com.virlin.app.ui.hierarchy

internal fun taskCreationError(result: com.virlin.app.domain.action.ActionResult<*>): String = when (result) {
    is com.virlin.app.domain.action.ActionResult.Rejected -> when (val reason = result.reason) {
        is com.virlin.app.domain.action.DomainError.PlacementRejected -> reason.reason
        com.virlin.app.domain.action.DomainError.EmptyTitle -> "Enter a task name."
        com.virlin.app.domain.action.DomainError.TaskAlreadyClosed -> "This parent task is closed. Restore it before adding a subtask."
        else -> "Task could not be added. Check its destination and try again."
    }
    is com.virlin.app.domain.action.ActionResult.NotFound -> "This workstream no longer exists."
    else -> "Task could not be saved. Please try again."
}

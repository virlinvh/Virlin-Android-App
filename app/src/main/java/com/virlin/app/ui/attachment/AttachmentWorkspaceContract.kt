package com.virlin.app.ui.attachment

/**
 * Routes for the task-scoped Attachment workspace.
 *
 * Ownership travels as an explicit id, following the same shape Prompt, Link, Audio and PDF use.
 * Nothing here routes by display name: a file called `report.pdf` is treated as a PDF because its
 * resolved [com.virlin.app.domain.model.AttachmentKind] says so, never because of its title.
 */
const val AttachmentWorkspaceTaskRoute = "attachment_workspace/new/task/{taskId}"

fun attachmentWorkspaceForTask(taskId: String): String {
    require(taskId.isNotBlank()) { "A task-owned attachment space requires a task id" }
    return "attachment_workspace/new/task/$taskId"
}

const val AttachmentWorkspaceTag = "attachment_workspace"
const val AttachmentImportButtonTag = "attachment_import_button"
const val AttachmentRowTag = "attachment_row"
const val AttachmentDetailTag = "attachment_detail"

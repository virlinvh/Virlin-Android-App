# Project knowledge capture

All Add options save project-owned knowledge, not mind-map nodes or executable
tasks. The Knowledge tab is the canonical library for reopening and organizing it.

The palette opens the same Notes, Image, PDF, Attachment, Prompt and Link interfaces
used by the mind map. Notes now use the newer rich Notes workspace, not the retired
Text Note UI. Knowledge drafts have independent stable IDs and project ownership.
The domain adapter commits the rich document, capture and compatibility preview
in one transaction. Empty untouched drafts create no saved item. Legacy notes are
loaded without mutation, expanded toggles keep text/order/indentation, and an exact
original JSON recovery snapshot is retained on first conversion. Project/branch
deletion removes rich documents and these recovery snapshots with their captures.
Duplication copies rich formatting as well as the compatibility document.

Image/Attachment workspaces filter by project and import into that project; PDF
drafts receive project context. Existing image/PDF Knowledge entries reopen the
corresponding full workspace. Audio still offers recording and audio-file import.
The Notes workspace's Export action is an existing explicit placeholder, not a
completed export feature; this change does not pretend otherwise.

To-do reuses `VirlinTodoScreen` with a capture-backed adapter, not the task-owned
`task_steps` adapter. Adds are asynchronous and clear the entry field only after
successful persistence. Edits, checks, ordering, removal and list renaming update
the same capture/document. An empty checkbox marker preserves the list's type
after its final row is removed and is not displayed as an item. Existing saved
checklists reopen in this interface from the project Knowledge library.

| Option | Durable representation |
| --- | --- |
| To-do | NOTE capture + editable CHECKBOX NoteDocument blocks |
| Note | NOTE capture + full NoteDocument content |
| Prompt | PROMPT capture containing the full input |
| Link | LINK capture with validated canonical HTTP(S) URL |
| PDF / Attachment / Image | FILE capture + AttachmentDocument; bytes copied into managed storage |
| Audio: Record | VOICE capture + VoiceDocument; managed recording clips |
| Audio: Import | FILE capture + AUDIO AttachmentDocument; managed original bytes |

Captures initially have projectId only, no workStreamId/taskId, and INBOX status.
Library Move/Organize explicitly assigns context later; it preserves capture IDs
and content. There is no implicit conversion to a task during capture.

Safeguards: project validation in domain transactions, saveable text drafts,
file-picker re-entry prevention, busy-state dismissal guards, empty-content and
link validation, failed-import managed-file cleanup, and audio-specific library
classification. Cancelling the picker does not create an empty item.

Verification includes all content-type save paths, project ownership, zero
hierarchy creation, later attachment, Room repository reload, draft restoration,
and existing note/link/attachment/voice regression tests. Media tests use fixtures;
the user's project and source files are not altered by verification.

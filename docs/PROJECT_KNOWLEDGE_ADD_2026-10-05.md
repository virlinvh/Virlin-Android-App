# Project knowledge Add

Both Overview and Knowledge Add use the same eight-option palette as the mind map.
Palette marks, highlights, and form accents use the selected project's persisted palette.

Notes and To-do lists are project-owned CaptureItem NOTE + NoteDocument records. To-do lines
are real editable checkbox blocks, not hidden tasks. Prompt and Link use CreateCapture with
explicit project context; the existing rich editors hydrate/open these persisted captures.
PDF, Image and Attachment imports copy files into managed attachment storage and then create
project-owned CaptureItem FILE + AttachmentDocument records. Failed imports remove only their
new managed file tree. Audio opens the existing recorder with explicit project context and is
persisted by its canonical recording save flow. No schema change is required.

Empty text cannot be submitted; domain rejections and import failures are shown, and forms remain
open for correction. Canceling a picker creates nothing. A save completes before reporting success.

Existing Knowledge Move/Organize actions reassign ownership without recreating the saved content.
Project task destinations now include subtasks as well as roots. The existing domain validates
ownership and derives a selected task's project/workstream. No cross-project task is offered.

Tests cover checkbox content preservation and project-owned note reattachment to a subtask,
preserving capture ID and document blocks and creating no implicit tasks.

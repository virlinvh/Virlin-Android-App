# Notes cleanup and legacy compatibility

Status: implemented redirect boundary. This document prevents the two historical Note systems
from being confused or destructively merged.

## Canonical behavior for new Notes

All visual **new Note** entry points open the redesigned owner-scoped Notes workspace in
`ui/notes`. Owner keys are opaque and constructed centrally:

- task: `TaskPageTypeKeys.noteOwner(taskId)` -> `task-<taskId>`
- project: `project-<projectId>`
- global: `global`

Task Notes participate in the task Page through the `task.note` Page block. The ORB resolves the
current Task Detail, Task Page, or To-do route first; then a project route; otherwise it opens the
global Notes document. It never guesses the focused task.

## Legacy compatibility boundary

The old editor in `ui/note` stores `CaptureItem(NOTE) + note_documents`. New-draft navigation to
the bare `text_note` route has been removed. The parameterized `text_note/{captureId}` route is
retained solely so existing Inbox, Activity, and Knowledge records remain readable/editable.

Do not delete yet:

- `CaptureType.NOTE`, `NoteDocument`, `NoteBlock`, or their codec/serializer;
- `note_documents`, DAO, repository, mapper, or `NoteActions`;
- the parameterized compatibility route.

Prompt documents currently share `NoteBlock`, and existing Capture NOTE rows appear in Inbox,
Activity, and Knowledge. Removing these pieces without a separate migration would break Prompt
and lose access to user data.

Natural-language commands such as `Save note ...` remain quick Inbox captures. That is a command
capture contract, not a visual Notes-editor creation path, and requires a separate product choice
before migration.

## Future removal gate

The compatibility editor may be deleted only after a tested migration defines destinations for
global, project-, WorkStream-, and task-attached Capture Notes, including multiple legacy notes
for one task. The migration must preserve ordering, titles, formatting, provenance, and rollback
safety. Until then, old rows remain untouched and no destructive schema migration is allowed.
## 2026-09-30 hardening follow-up

- `textNoteRoute` now accepts only a non-blank capture ID and can produce only
  `text_note/{captureId}`. The unused bare `TextNoteRoute` constant and nullable fallback were
  removed, preventing a future caller from navigating to an unregistered new-draft route.
- The task Note reconciliation test now saves through `DefaultVirlinActions.saveNoteDoc`, the same
  action boundary used by the redesigned editor, before asserting that `loadAndReconcile` creates
  the canonical `task.note` Page block with the shared owner key.

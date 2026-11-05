# Project Settings foundation

Updated: 2026-10-01

## Purpose and entry point

The Project page overflow exposes one configuration entry: **Project settings**. WorkStream
creation remains the existing `+ New` action. The duplicate `Edit project` and `Manage workstreams`
overflow actions are intentionally removed.

`ProjectSettingsDialog` is a centered, upper-middle modal rather than a bottom sheet. Its header and
Cancel/Save footer are fixed; only the category/content viewport scrolls. Categories provide the
stable extension boundary for future settings.

## Categories

Identity & status; Defaults & workflow; Organization; Attention & reminders; Content & appearance;
Storage & export; and Project management. Import Project and Project Template are intentionally not
part of this contract.

## Live controls

- Project name uses `VirlinActions.updateProject(ProjectUpdate.title)`.
- Project icon reuses the canonical built-in/custom managed icon editor and store.
- Default execution uses `VirlinActions.setProjectExecutionDefault` and its processing safeguards.
- Completed tasks lists DONE tasks in the project, supports multi-selection, and requires explicit
  confirmation before `VirlinActions.restoreTask` returns them to TODO.
- Complete project lives only in Project settings > Project management and reuses the existing
  confirmed completion flow. It is intentionally absent from the project overflow menu so this
  consequential action has one deliberate, discoverable home.

## Planned rows and schema ownership

The complete approved information architecture is visible, but controls without real persistence
are labelled **Planned** and do not pretend to save. The parallel Mind-map Node Preview feature owns
Room migration 17→18, so Project Settings creates no competing migration. Persisted Project color
and other policy fields must use the next migration reserved through the schema queue after v18 is
integrated. Project color must be a Project-owned semantic token resolved by descendants, never
copied into WorkStream or Task rows.

## Restoration invariant

`restoreTask(id)` accepts only `TaskStatus.DONE`, writes `TODO`, clears `completedAt`, updates
`updatedAt`, and records `TASK_UPDATED` for WorkStream tasks. It refuses CANCELLED tasks, preserves
ownership/order/content, and never changes Focus.

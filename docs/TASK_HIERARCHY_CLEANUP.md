# Green Task Hierarchy Migration

Date: 2026-09-30

## Decision

Virlin has one canonical interactive task experience: the green WorkStream hierarchy in
`VirlinStreamTasks.kt`. The former cream `TaskDetailScreen` has been removed. Do not introduce a
second task-detail screen.

## Navigation contract

`task_detail/{id}` is deliberately retained as a compatibility entry route. It is not a UI page.
`TaskHierarchyRedirectScreen` resolves the canonical task record and immediately replaces itself:

- WorkStream task: `workstream_detail/{workStreamId}?path={root,...,selectedTask}`.
- Standalone project task: `project_task_index/{projectId}`.
- Missing or unplaced task: a non-destructive missing-location state; no destination is guessed.

All existing callers—including the mind-map **Open Task** action, Agent commands, Pulse, project
Activity and restored back stacks—may continue using `taskDetail(id)`. They all converge through
the resolver. New code should also use this entry point unless it already has a complete, validated
WorkStream hierarchy path.

The resolver is the pure `taskHierarchyDestination(...)` function. It walks stable parent IDs,
guards against cycles, and never resolves by title or list position.

## Focus and Delegate

Both actions now live in the selected task's overflow menu on the green hierarchy level.

- Focus calls the existing `HierarchyViewModel.focusWorkItem`. If another focus would be displaced,
  the existing `SwitchFocusDialog` is shown and no mutation occurs until confirmation.
- Delegate opens the existing `DelegateDialog` and calls the existing `HierarchyViewModel.delegate`.
- Completed and cancelled tasks do not expose Focus or Delegate.
- No task records, execution preferences, focus sessions or external-work records were duplicated.

The old **Open details** menu action was removed because the green hierarchy level is now the task's
canonical detail context.

## Removed legacy surface

Removed from production:

- `TaskDetailScreen` and its cream-layout implementation.
- Legacy task-detail-only tags and presentation helpers.
- The obsolete task-detail screenshot golden entry.

Domain execution inheritance remains intact. Human, External and Inherit are still used by the
green hierarchy and must not be deleted as part of visual cleanup.

## Extension rules

When adding future task functionality:

1. Add task-level actions to `StreamTaskActions` and surface them in `VirlinStreamTasks`.
2. Implement mutations through `HierarchyViewModel` and `VirlinActions`; never write directly from UI.
3. Keep task identity stable and use IDs for hierarchy paths.
4. Do not restore a separate cream/gray task page.
5. Preserve `task_detail/{id}` compatibility until an explicit navigation-schema migration removes
   old notifications, saved state and external callers.

## Verification

- Debug application Kotlin compilation: passed.
- Debug unit-test and Android-test source compilation: passed.
- Pure routing tests cover nested WorkStream tasks, standalone tasks, missing tasks and unplaced tasks.
- A corrupt parent-cycle case explicitly verifies that the resolver's visited-ID guard terminates
  and produces a bounded, stable-ID path. Domain actions still prevent such cycles from being created.
- Full debug unit suite: 1,049 tests executed; the existing unrelated
  `CaptureBoundaryTest.types_are_explicit_and_payloads_raw` failure remains.

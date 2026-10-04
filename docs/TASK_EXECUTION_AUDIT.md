# Task execution audit — 2026-10-04

## Fixed and verified

- Task and recursive subtask menus expose one Work controls entry instead of Current, Focus,
  Delegate and Execution. Controls resolve the selected item's responsibility and ancestry.
- Human focus uses the existing globally exclusive session and switch confirmation. Selecting
  controls does not start work. External start carries the selected task identity.
- Completing/cancelling the exact active task closes its session, saves external tracked time,
  clears live tracking/reminders and returns the stream to Ready. It does not finish the stream,
  start another task, or leave a ghost item in Now.
- A different task cannot overwrite an external run or its paused tracking attribution.
- Now focus and external cards show the task ancestry. Attention cards show the exact leaf and
  project/workstream/ancestor context. Human return-due items differ from external check-due and
  confirmed-result items. Timer expiry never confirms a result or automatically starts focus.
- Task completion uses a task callback, not workstream completion. Confirmation stays inside
  the existing controls dialog.

Verification: 1,178 unit/UI tests passed; Android instrumentation sources compiled; debug APK
installed. Compilation is not instrumentation execution. Existing v19 migration was tested in
the preceding change. No new storage migration is included in this audit pass.

## Previous limitation (superseded by version 20 below)

2026-10-04 follow-up: task rows and nested task headings expose direct Work/status tags using
the same controls dialog. Inactive external tasks now show a preflight conflict and View active
work instead of collecting a timer and failing afterward. The selected timer was never saved
in that rejected case. This is a safe interaction correction, not concurrent task execution.
Verified on emulator with Waited Test 02 and its existing active sibling; no activity changed.

Execution remains stored on WorkStream with one activeTaskId. This is not the complete requested
task execution foundation. Multiple external runs across different streams work; independent runs
inside the same stream do not. The new guard refuses overwriting rather than pretending concurrency.
Inactive tasks cannot retain independent Paused/Waiting/Result-ready records. Now queues remain
stream-keyed. Archive/restore and reopen are not implemented. MULTI RUN remains dormant.

The next foundation change needs persisted per-task execution records, task-keyed reminders and
external intervals, transactional migration of legacy activity (including taskless streams), atomic
focus displacement retaining the previous task state, and descendant aggregate projections.
Only then should multiple task queues and MULTI RUN be enabled. Tests must cover simultaneous
siblings, nested tasks, cross-project focus switches, restart recovery and migration before release.

## Version 20 implementation

Task-owned persisted execution now supports independent external siblings and one global human
focus. Focus displacement pauses the previous task. Responsibility inheritance is resolved separately
from activity; incompatible activity is stopped safely when its effective responsibility changes,
without automatically starting another timer. Explicit descendant overrides remain independent.

Task-keyed reminders, Now projections, recursive timing totals, and MULTI RUN aggregate states are
wired to these records. Manage exposes Timer display directly, with a scoped 5/6/7-second rotation
setting. Legacy taskless workstream activity remains supported. Migration transfers only exact
active-task matches and leaves session ownership/history unchanged.

Verification: on-device 19-to-20 migration passed. Independent concurrency, focus displacement,
mode changes, timer rotation and timer controls have targeted regression tests. The last full-suite
run had one existing rename-dialog AppNotIdleException; it is not a clean full-suite pass.
Archive/move lifecycle edge cases and a complete external interval ledger are not certified by
this change; external timing uses persisted accumulated active seconds plus the current interval.

## Leaf-only execution audit

- Any child, including a completed child, makes its parent a container. Only leaf tasks and empty workstreams can select responsibility or start/pause/resume execution. Domain actions reject container requests, so alternate entry points cannot silently choose the first child.
- Parent controls retain timer-display settings, activity summaries and management. Their activity list targets leaves; structural rename, add, duplicate, organize/move and completion/cancellation remain separate from execution.
- Project defaults and existing container defaults remain inheritance sources; explicit leaf overrides remain local. Responsibility changes on containers are refused. Human-to-External changes clear incompatible focus/pause/return state without automatically starting work.
- Startup reconciliation ends legacy container/incompatible activity, preserving focus history and accumulated external seconds, and never transfers execution to an arbitrary leaf. A pre-install emulator archive includes databases, files and shared preferences at `C:/tmp/virlin-before-leaf-only-audit.tar`.
- Adding children to an active leaf requires ending activity first. Reparenting active branches is refused; sibling reordering stays allowed. End activity saves current tracked seconds and clears reminders.
- New tests cover leaf-only API guards, local mode switching, safe child creation, idempotent reconciliation, parent control visibility, and paused External primary-action selection.

## Container workstream settings follow-up

Container workstreams now expose only Edit name and Mark as complete; their duplicated activity and timer-display settings are removed from this popup. Task-container management and empty-workstream execution remain unchanged. Future project-level tools may host timer-display preferences; no new tools UI is added here.

Confirmed workstream completion atomically completes its remaining owned tasks at every depth, ends their tracking/cycles/reminders, and preserves history and accumulated time. Already cancelled/completed tasks and other workstreams are untouched. The confirmation states this scope explicitly. Targeted verification: 72 passing tests and successful debug assembly; full-suite rename idle limitation remains documented.

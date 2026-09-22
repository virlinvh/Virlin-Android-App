---
name: virlin-data-domain
description: Virlin's domain model and Action Layer rules — WorkStream as the primary object, canonical attention states and the transition table, the single-Focus invariant, Processing/Snooze/Pause/Block semantics, timestamps as the time source of truth, ContextSnapshot/Cycle/FocusSession/Event models, the VirlinActions facade and the repository boundary. Load ONLY when changing domain models, actions, repositories, persistence, or anything that mutates WorkStream state. Do not load for pure UI, motion, or QA work.
---

# Virlin — Data & Domain

Load on demand. UI rules live in `virlin-android-compose`; verification in `virlin-mobile-qa`.

## The model

- Hierarchy: `PROJECT ── WORKSTREAM ── TASK ── TASK …` plus standalone project tasks.
  **Project is optional**: a projectless WorkStream owns recursive Tasks like any other.
  `Task` nests via `parentTaskId` — **no Stage/Step/Substep types, no depth limit**. A task
  is rooted in a WorkStream (`workStreamId`, project optional) or standalone in a Project
  (`workStreamId == null`, `projectId != null`); never ownerless. `Task.projectId` is a
  nullable DERIVED copy of the stream's project — the stream is the source of truth.
- Task status: `TODO/IN_PROGRESS/DONE/CANCELLED`. **DONE = terminal + completed. CANCELLED =
  terminal, NOT completed.** Use `isTerminal` / `isCompleted` / `isActivePlanned`; never treat
  cancelled as done. Runtime timers stay on the WorkStream.
- `WorkStream.activeTaskId` is the source of truth; the active path is **derived**
  (`TaskHierarchy.ancestry`), never stored. Independent of attention state.
- Progress lives ONLY in `ProgressCalculator`: executable leaves only (no double-counting);
  **cancelled leaves are excluded from numerator AND denominator**; `COUNT_BASED` unless every
  active leaf has an estimate (`EFFORT_WEIGHTED`), never mixed; no leaves → `Unstructured`,
  all cancelled → `NoActiveWork`, never 0/0 and never "complete". Parent status is never
  auto-mutated.
- **WorkStream** is the primary attention object — an ongoing chain of work through many
  human ↔ external-process **Cycles**. It is not a task; a task is not the central abstraction.
- **FocusSession** = real human attention, `startedAt`/`endedAt` timestamps. Duration is
  always derived; never persist ticks. **ContextSnapshot** = mental state on every exit
  (where am I · what happened last · waiting for · when to look again · what next).
  **WorkStreamEvent** = append-only semantic history. Current state lives on the WorkStream
  row; events are history, not event sourcing.
- Files: `domain/model/WorkStreamModels.kt`, `domain/model/WorkStreamTransitions.kt`.

## States and rules

`FOCUS · PROCESSING · CHECK · READY · SNOOZED · BLOCKED · PAUSED · DONE`

- **One human FOCUS.** Enforced centrally in `focusStream`: the displaced stream → READY,
  its session closed, its context snapshotted, events for both — in one transaction.
- **Many PROCESSING** at once is normal and required. PROCESSING consumes no attention.
- PROCESSING defers attention via `checkAt`; it is never SNOOZED. SNOOZED is a human
  choice not to look until a time and carries `snoozeReason` (`HUMAN_RETURN` from Leave,
  `EXTERNAL_RESULT_READY` from a finished external check) — the reason is explicit, never
  inferred from field combinations, and survives the due CHECK so the UI can word it.
  `WorkStream.mode` (HUMAN | EXTERNAL) is explicit too — never derived from titles in code.
  PAUSED = "not now" (no wake time). BLOCKED = "cannot proceed". Keep these distinct.
- LEAVE ≠ HAND OFF. Leaving Focus is `leaveFocus`; only an external process being handed
  work is `handOffStream`. Tapping CHECK never enters Focus by itself.
- Timed returns/checks are durable rows AND Android wake-ups: `SchedulingWorkStreamRepository`
  syncs `AttentionScheduler` after every commit from `AttentionSchedule.of(stream)`. Never call
  AlarmManager elsewhere; never trust alarm extras — `AttentionAlarmReceiver.validate` reads
  Room and reconciles via `checkDue` (idempotent with the ticker/startup). Startup and boot run
  `rescheduleAll()`. Tests use `FakeAttentionScheduler`.
- DONE is terminal until an explicit reopen exists.
- `WorkStreamTransitions.canTransition` is the only transition table. Never assign a state
  directly. There is no `setState`.
- `effectiveAttentionState(stream, now)` is a pure projection (due PROCESSING/SNOOZED reads
  as CHECK). It never mutates; scheduling makes it real later via `checkDue`.

## The Action Layer

- `VirlinActions` (`domain/action/`) is the ONE product-facing API. Now UI, Agent Control,
  notifications, the future interpreter, voice and AI all call the same operations.
- Actions express intent: `focusStream`, **`leaveFocus(returnAt?)`** (human exit → READY or
  SNOOZED/HUMAN_RETURN — never PROCESSING), `handOffStream(checkAt?)` (external exit →
  PROCESSING), `checkStream` (read-oriented), `checkDue`, **`stillRunning`**,
  **`resultReadyNow`**, **`resultReadyLater`** (→ SNOOZED/EXTERNAL_RESULT_READY, processing
  fields cleared), **`deferReturn`** (same reason, later time), `continueProcessing`,
  `snoozeStream`, `markReady`, `pauseStream`, `blockStream`, `unblockStream`,
  `completeStream`, `updateContext`, `addNote`; structure:
  `createProject/updateProject/completeProject`, `createWorkStream` (project optional,
  explicit mode, enters READY), `createTask/addSubtask/updateTask/
  completeTask/cancelTask`, `setActiveTask/clearActiveTask/activePath/nextTaskCandidate`
  (composed via internal `StructureActions` — do not split into a second facade); capture:
  `createCapture/updateCapture/attachCapture/archiveCapture/restoreCapture/convertCaptureToTask`
  (internal `CaptureActions`; `CaptureItem` is NOT a Task — see `virlin-agent-capture`).
- Ownership on create: child inherits parent's project+stream (explicit values must match);
  stream task derives project from the stream (null is fine); standalone needs an explicit
  project. Self-parent, cycles and a closed (DONE/CANCELLED) parent are rejected. `completeTask`/`cancelTask` clear an active task
  and never auto-advance.
- Every mutating action: load → validate → close open session → snapshot → save → events,
  inside ONE `repository.transaction { }`. Follow that shape when adding one.
- Results are values: `ActionResult.Success / Rejected(DomainError) / NotFound / Failure`.
  `DomainError` is typed and human-mappable; no user-facing copy in the domain.
- `ContextUpdate` uses `Field.Keep / Clear / Set` so "leave" and "clear" never collide.
- Time only from `VirlinClock`; ids only from `IdProvider`. Tests use `FakeClock` and
  `SequentialIdProvider` — no sleeps, no real time.

## Needs You attention queue (Phase 03)

`NeedsYouOrder.queue(streams)` is the ONE canonical Needs You ordering: CHECK items, explicit
`attentionRank` block first then longest-waiting, each entry carrying its EFFECTIVE rank (dense
1..N position). Identity is the stream id and never depends on position. `VirlinActions.
reorderNeedsYou(id, rank)` is the only move: it shifts the displaced items, re-densifies the whole
queue in one transaction, clamps out-of-range targets, and never touches `updatedAt`, `checkAt` or
the waiting basis. Leaving CHECK clears that item's rank and re-densifies the rest. New arrivals are
unranked and append. UI (`NowViewModel.needsYouQueue`) only reads this projection — never compute an
order or a rank in a composable, and never add a second ordering source.

## Boundaries

- `WorkStreamRepository` (which also implements the small `CaptureRepository`, same transaction
  so convert-to-Task is atomic) is the persistence boundary; writes go through
  `transaction { WorkStreamWriter }`. Production is `RoomWorkStreamRepository`
  (`data/db/`, one `withTransaction` per action, publish-on-commit); tests use
  `InMemoryWorkStreamRepository`. Never call a DAO from UI/ViewModels; never put a rule in a
  DAO. Entities ≠ domain models — map in `VirlinMappers`; enums by name, instants as epoch
  millis; no countdowns, no Active Path column; no FKs/cascades (no delete API yet).
- Schema v1 is exported to `app/schemas`. Any schema change needs a real migration — never
  `fallbackToDestructiveMigration()`.
- `VirlinGraph` is the composition root until Hilt: `init(context)` opens the DB,
  `DemoSeed.applyIfEmpty` seeds once (marker + empty check), `start()` runs `reconcileDue()`
  so due checks/returns become CHECK via `checkDue` on reopen without any ticker.
- **UI never mutates domain state.** ViewModels call `VirlinActions` and log results; the
  UI observes the repository (today via `DomainDisplayBridge` → MockData display list).
  `MockData.updateStreamState` is deprecated at ERROR level on purpose.
- `MockData` holds DEMO DISPLAY DATA only (titles, per-second display counters). The
  display bridge is one-way, domain → display. `MockTimerEngine` advances counters and asks
  the domain for `checkDue`; it never sets state.
- Capture (`CaptureActions`, composed into `DefaultVirlinActions`) is preservation, not
  execution: `createCapture` / `attachCapture` / `archiveCapture` / `restoreCapture` /
  `convertCaptureToTask` touch no WorkStream state, Focus, `activeTaskId`, alarm or notification;
  convert-to-Task creates exactly one Task in the same transaction and rejects a second conversion.

## Now Current Focus (Pass 3)

- `NowPresentation.currentFocus()` / `NowViewModel.currentFocus` is the only projection of the
  focused stream's hierarchy. Resolve `activeTaskId` to the Task; never infer "current" from
  `IN_PROGRESS`, never cache it, never show an ancestor.
- COMPLETE is task-first: `completeTask(activeTaskId)` when present; otherwise require explicit
  confirmation (`pendingWorkStreamCompletion`) before `completeStream`.
- `nextHumanAction` (context) and `nextTaskCandidate` (structure) are different things — never
  substitute one for the other.

## Notification actions (Pass 7)

- Buttons → `NotificationActionReceiver` → `NotificationActionHandler.execute` → the same
  `VirlinActions` as Needs You (`focusStream`, `deferReturn`, `stillRunning`). Validate against
  Room first (`presentsAs(stream, kind)`); stale/terminal/double tap = no mutation. CHECK and
  the body tap are navigation only (`NotificationNavigation` → Now). Text/actions come from
  `AttentionNotificationModel.build` on persisted titles — never MockData, never ids/notes.

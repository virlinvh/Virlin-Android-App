# Virlin architecture context

Authoritative details: `CLAUDE.md`, `docs/DEVELOPMENT_STATUS.md`, and the
`virlin-data-domain` / `virlin-agent-command` skills. This file is the map, not the spec.

## Data flow

```
Room → Flow → Repository → Domain (VirlinActions) → ViewModel → StateFlow → Compose
UI events travel back: Compose → ViewModel → VirlinActions → Repository → Room
```

UI never touches DAOs; no domain rule lives in a DAO. All WorkStream state changes go through
the `VirlinActions` facade (`VirlinGraph.actions`), which validates transitions against
`WorkStreamTransitions`, enforces the single-Focus invariant, snapshots context, records
events. `MockData` is display-only.

## Composition root

`domain/VirlinGraph.kt` (object): `init(context)` opens `virlin.db` (called from
`MainActivity`); `start()` runs `reconcileDue()` + `rescheduleAll()`. Exposes
`repository`, `actions`, `commands` (`CommandEngine`), `timeParser`, `interpreter`,
`scheduler`, `clock` (`VirlinClock`), `ids` (`IdProvider`), `zone`. Tests inject fakes
(`FakeClock`, `SequentialIdProvider`, `AttentionScheduler` fakes).

## Work-structure hierarchy

```
PROJECT (optional) ── WORKSTREAM ── TASK ── TASK ── TASK …  (recursive, no depth limit)
        └────────────── TASK (standalone in Project)
```

- No Stage, Step, or Subtask entity. "Subtask" is only language for a child Task
  (`parentTaskId`). A WorkStream may exist without a Project and without Tasks.
- `WorkStream.activeTaskId` is the single current-task truth; the **active path** is derived
  (`TaskHierarchy.ancestry` / `VirlinActions.activePath`), never stored. FocusSession captures
  `taskId` at session start; ContextSnapshot carries the exact task to return to.
- Task statuses: `TODO / IN_PROGRESS / DONE / CANCELLED`. DONE = completed; CANCELLED =
  terminal but NOT completed (excluded from progress numerator and denominator). Progress is
  derived by `ProgressCalculator` from executable leaves only — COUNT_BASED by default,
  EFFORT_WEIGHTED only when every active leaf has a positive estimate. Never mixed; never
  computed in UI.

## WorkStream states & transitions

`FOCUS · PROCESSING · CHECK · READY · SNOOZED · BLOCKED · PAUSED · DONE`.
`WorkStreamTransitions` is the only place a state change is allowed (no `setState`). One
FOCUS at a time (displacement enforced in `focusStream`); many PROCESSING allowed; PROCESSING
never consumes attention and is never SNOOZED.

- **LEAVE** (`leaveFocus`): FOCUS → READY (no return time) or FOCUS → SNOOZED
  (`SnoozeReason.HUMAN_RETURN`, future `returnAt`). Never PROCESSING.
- **HAND OFF** (`handOffStream`): FOCUS → PROCESSING, optional `checkAt`; requires
  `mode == EXTERNAL` (read from data, never inferred from the title).
- **Check flow** (due external check): `stillRunning(checkAt)` stays PROCESSING with a new
  check; `resultReadyNow` → FOCUS; `resultReadyLater` → SNOOZED (`EXTERNAL_RESULT_READY`);
  `blockStream` → BLOCKED. `deferReturn` pushes a return later without changing its reason.
- **Snooze vs Pause vs Block**: snooze = timed deferral; pause = "not now" (no wake time);
  block = "cannot proceed" (reason kept).

## Time model

Timestamps are the source of truth (epoch millis in Room; `VirlinClock` injected). Elapsed
time is derived, never counted into the database. No per-second DB writes; the display ticker
(`MockTimerEngine`) only refreshes UI and calls `checkDue` when a countdown ends. Distinct
kinds of time: human focus time (FocusSession), external processing time (`processingStartedAt`),
check timer (`checkAt`), return reminder (`snoozedUntil`), estimated effort, due/target times.

## Persistence (Room = source of truth)

`data/db/`: `VirlinDatabase` (`virlin.db`, schema **v2**, exported to `app/schemas/{1,2}.json`,
`MIGRATION_1_2` registered, **no destructive fallback** — every schema change ships a real
migration proven by `VirlinMigrationTest`). Entities (`VirlinEntities.kt`) are separate from
domain models and totally mapped (`VirlinMappers.kt`). Enums by NAME; instants as epoch
millis. Ids by convention with indices — no SQL foreign keys, no cascades (no delete API).
Every action runs in ONE `db.withTransaction {}` serialized by a mutex; publish-on-commit
re-reads touched tables into StateFlows. `InMemoryWorkStreamRepository` remains for JVM tests.
Demo seed is idempotent (`meta.demo_seed` marker).

## Scheduling & wake-ups

```
Virlin action → Room commit → SchedulingWorkStreamRepository (decorator)
  → AttentionScheduler.sync → AndroidAttentionScheduler (AlarmManager, one PendingIntent
    per stream, virlin://attention/<streamId>) → AttentionAlarmReceiver
  → reads Room, validates kind+dueAt (stale → re-arm only) → VirlinActions.checkDue
  → notification worded from persisted data
```

Room holds the state; Android scheduling is only the wake-up mechanism. Startup
(`reconcileDue` + `rescheduleAll`) and `BootRescheduleReceiver` (BOOT_COMPLETED /
MY_PACKAGE_REPLACED) heal missed alarms. Exact alarms only when already granted.

## Notifications

`platform/AttentionNotificationModel.kt` builds typed actions (`RESUME · DEFER_5 · CHECK ·
FOCUS_NOW · CHECK_AGAIN_5`) with `NotificationTarget { WORKSTREAM_DETAIL, CHECK_FLOW }`.
`NotificationActionReceiver → NotificationActionHandler` validates against Room (stale →
dismiss, no mutation) and calls the same `VirlinActions`. **UI/notification code never mutates
Room directly.** One notification per stream, grouped; body/CHECK open the app without mutating.

## Deterministic command pipeline (no LLM, no network, no model)

```
composer text (CONTROL/CREATE)
  → TextCommandInterpreter (grammar: Query → Capture → Create → Control families)
  → TimeExpressionParser / DurationParser (temporal text → TemporalIntent, injected clock+zone)
  → VirlinCommand (typed, sealed) → CommandResolver
      (exact id → exact normalized title → unique case-insensitive → unique prefix/word
       → else Clarification with typed candidates; CompleteStream/CancelTask → Confirmation;
       every Create → Preview)
  → CommandExecutor → VirlinActions → Room   (+ AlarmManager via the decorator)
```

`TargetRef.Named` is kind-less — the action's allowed kinds plus the resolver decide what
matches. Pending clarifications/confirmations survive as values; the chosen candidate refills
the SAME pending command. Results: `Executed / Rejected / Failed` — never a first-match guess.
The hybrid LLM fallback layer was removed on 2026-09-12; `DeterministicOnlyTest` structurally
guards against its return. `Unsupported` text stops with feedback — there is no second
interpreter behind it.

## Capture raw boundary (critical)

CAPTURE workspace submission: `AgentCaptureViewModel.save` → `createCapture` → repository →
Room. It must NEVER route through `TextCommandInterpreter` — command-looking text ("Leave
Psychology for 10 minutes") is stored verbatim. In CONTROL/CREATE, explicit prefixes
(`remember | note | save note | save prompt | save link`) recognize capture intent, then the
payload terminates as raw data. Saving a capture has zero side effects on Focus, streams,
alarms or notifications. `convertCaptureToTask` is the one explicit atomic conversion.

## Root navigation & UI surfaces

Routes (`ui/navigation/VirlinApp.kt`): root tabs `now`, `streams`, `pulse`, `inbox`
(`VirlinBottomNav.kt`, bottom-attached, owns the navigation-bar inset); detail routes
(`ProjectDetailRoute`, `WorkStreamDetailRoute`, `TaskDetailRoute` from `ui/hierarchy/`,
legacy `stream_detail/{id}`, `focus_clock`) stay nested without the bar. The single Orb lives
in the root `OrbTravelLayout` overlay — never screen content. Agent sheet = in-window
(`ui/screens/AgentSheet.kt`) with entry step → one mode workspace (no mode tabs), shared
`AttentionIntentController` for leave/hand-off/check choosers on Now, Stream Detail and Control.

## Package map (app/src/main/java/com/virlin/app/)

| Package | Contents |
|---|---|
| `domain/model/` | `WorkStreamModels`, `StructureModels`, `CaptureModels`, `TaskHierarchy`, `WorkStreamTransitions` |
| `domain/action/` | `VirlinActions` facade, `DefaultVirlinActions`, `StructureActions`, `CaptureActions`, `ActionResult`, `ContextUpdate` |
| `domain/command/` | `VirlinCommand`, `CommandResolver`, `CommandExecutor`, `ResolvedCommand`; `text/` interpreter + `DurationParser`; `time/` `TimeExpressionParser`, `TemporalIntent` |
| `domain/progress/` | `ProgressCalculator` |
| `domain/repository/` | `WorkStreamRepository` contract, `InMemoryWorkStreamRepository`, `CaptureRepository` |
| `domain/schedule/` | `AttentionScheduler` contract, `SchedulingWorkStreamRepository` decorator |
| `domain/time/`, `domain/id/` | `VirlinClock`, `IdProvider` (injectable) |
| `domain/` | `VirlinGraph`, `DemoSeed`, `DemoHierarchySeed` |
| `data/db/` | Room: `VirlinDatabase` (+ `MIGRATION_1_2`), `VirlinEntities`, `VirlinMappers`, `RoomWorkStreamRepository` |
| `platform/` | `AndroidAttentionScheduler`, `AttentionAlarmReceiver`, `AttentionNotifications`, `AttentionNotificationModel`, `BootRescheduleReceiver`, `NotificationActionReceiver/Handler`, `NotificationNavigation` |
| `mock/`, `model/` | `MockData`, `MockTimerEngine`, `DomainDisplayBridge`, `Models.kt` — display-only demo layer |
| `ui/screens/` | Now/Streams/Pulse/Inbox/StreamDetail/FocusClock, `NowViewModel`, `NowPresentation`, `AttentionIntentController`, `AgentSheet` |
| `ui/hierarchy/` | Project/WorkStream/Task detail screens, `HierarchyViewModel`, `HierarchyPresentation` |
| `ui/agent/` | Composer/receipt/workspace state; `control/`, `create/`, `capture/`, `command/` areas + ViewModels |
| `ui/orb/` | `VirlinAgentViewModel` (sole Orb-state owner), `VirlinOrbInteractionState` |
| `ui/components/` | `VirlinOrb` (Stitch liquid, AGSL w/ Canvas fallback), `SplitFlapTimer`, `ProcessingWave` |
| `ui/theme/` | Theme/Color/Type + `VirlinTokens.kt` (approved design-token foundation) |

Notes: `ui/theme/Color.kt` has two stale entries; the rendered truth lives in `VirlinTokens.kt`
and package-level vals in `NowScreen.kt`. `gradle/libs.versions.toml` exists but the `:app`
module declares dependencies directly — see `.glm/rules/ANDROID.md` for the real pins.

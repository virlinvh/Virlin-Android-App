# Execution and timing foundation

Status: canonical contract for Project, WorkStream, Task and Now execution surfaces.

## Product rule

Structure order suggests what comes next; the user chooses what actually runs; timestamped sessions
record what truly happened. Reordering, renaming and changing parents never rewrite time history.

## Separate concepts

- **Structure:** Project > WorkStream > recursive Task. `Task.order` controls sibling presentation.
- **Attention:** `WorkStream.state` is the sole execution/attention state. At most one WorkStream is
  `FOCUS`; several may be `PROCESSING`.
- **Position:** `WorkStream.activeTaskId` is the exact current Task. Its ancestor path is derived.
- **History:** `FocusSession(startedAt, endedAt, workStreamId, taskId)` records human attention.

No elapsed counter or parent total is persisted. Timestamps are the source of truth.

## State presentation

| State | UI wording | Timing meaning |
|---|---|---|
| `FOCUS` | Focusing | One open human FocusSession; live elapsed time |
| `PROCESSING` | Running | External process elapsed from `processingStartedAt` |
| `CHECK` | Needs you | Planned check is due |
| `READY` | Ready | Available, no running timer |
| `SNOOZED` | Waiting | Countdown to `checkAt` |
| `BLOCKED` | Blocked | No focus time accrues |
| `PAUSED` | Paused | No focus time accrues |
| `DONE` | Complete | Closed historical totals only |

## Selection and order

`nextTaskCandidate` is the deterministic suggestion: first open leaf in depth-first sibling order.
It never starts a timer. Focusing any other open Task is always allowed. Focusing a container resolves
to its first open leaf; a terminal Task or exhausted container is rejected.

Changing Current on a focused WorkStream is a real timing boundary. The old session closes and a new
session attributed to the new Task opens in the same repository transaction. Hierarchy UI routes this
through the existing switch confirmation; the domain action repeats the protection for Agent and
future callers. On a non-focused WorkStream, Current only remembers position.

## Roll-ups

`ExecutionTiming` is the shared read-only projection:

- exact Task time = sessions whose immutable `taskId` equals that Task;
- parent time = exact time plus every descendant Task session;
- WorkStream time = all its FocusSessions, including historical unattributed sessions;
- Project time = sessions belonging to its WorkStreams.

The same session may appear in a leaf, its ancestors, its WorkStream and its Project report because
those are nested views of one fact. It is stored once and never double-counted within a scope.

Human focus, external processing, snoozed/waiting and blocked durations are different measures and
must not be merged into one productivity total.

## UI ownership

- Project rows show the projected state, exact active Task and relevant live timing.
- WorkStream header shows the same projection; Task rows show exact or descendant focus roll-up.
- Now remains the canonical live Focus surface and continues to use the same open FocusSession.
- Pulse remains historical reporting and consumes the same sessions; no alternate timer is added.

One display coroutine may refresh `now`; ticks cause no database write. UI reads timing through the
ViewModel/repository boundary and never through a DAO.

## Completion

Completing the active Task closes its open FocusSession once and clears `activeTaskId`. The domain
does not auto-start another task. `nextTaskCandidate` may be offered by UI as an explicit next choice.

## Compatibility and schema

This foundation adds no table, column or migration. Existing FocusSessions remain valid, including
older sessions with `taskId == null`; those contribute to WorkStream/Project totals but cannot be
invented into a Task total.

## Extension rules

- Never infer identity from title or list position.
- Never store parent, WorkStream or Project focus counters.
- Never change `activeTaskId` during FOCUS without closing/opening the attribution boundary.
- Never silently auto-focus a suggested next task.
- New execution UI must consume `ExecutionTiming`, not recreate state wording or roll-up math.

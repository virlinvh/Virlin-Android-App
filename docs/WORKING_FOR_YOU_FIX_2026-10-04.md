# Working for you — identity and ongoing-run audit

Implemented in the main Android Studio checkout on `feature/attachment-workspace`.

## Root causes

1. NowScreen used `ExternalWorkItem.actorName` as the card title. Without an assigned
   actor/tool this resolves to the literal “External process”. It is a fallback label,
   not an injected demo task. The actual task title was available but not used.
2. ExternalWork filtered out PROCESSING items once their check time arrived and all
   CHECK items. A due check does not stop external tracking, so a genuinely running
   task disappeared from Working for you while its hierarchy dial still said running.

## Corrected contract

- Card title is the exact task/subtask name; task-less workstreams use their own name.
- All concurrent external runs retain their independent stable IDs, even in one workstream.
- PROCESSING runs remain visible when their reminder is overdue.
- CHECK runs stay visible only while processingStartedAt indicates ongoing tracking.
- Due items also remain in Needs You: one is the running-work overview, the other is
  the reminder queue. This is two views of one execution, not two running timers.
- Paused, blocked, completed and result-ready/stopped items are excluded.
- Existing order, routes, hierarchy context, actor metadata and task execution are preserved.
- No stored data, demo fixtures or history were deleted, and no extra execution was started.

Regression tests cover two canonical leaves in one workstream, one overdue check,
exact titles, rename refresh, unique IDs, task-less fallback and nonrunning exclusions.
The existing due-boundary test now checks continued visibility rather than hiding the run.

Verification: full local suite 1,230 passed, zero failures/errors; lintDebug and
assembleDebug passed. APK generated under this checkout's app/build/outputs/apk/debug.
No device installation/data reset, commit or remote push performed for this fix.

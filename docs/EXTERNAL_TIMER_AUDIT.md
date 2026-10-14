# External timing audit — 7 October 2026

## Time definitions

- External elapsed: persisted `externalTrackedSeconds` (closed run segments) plus nonnegative seconds since `processingStartedAt`, unless tracking is paused.
- Check countdown / overdue: `checkAt - now`. This is a reminder, not external elapsed time. Becoming CHECK does not mean processing has finished.
- Focus elapsed: persisted FocusSession start/end timestamps. It is a separate category and is never included in external elapsed.
- Parent task: its own recorded external time plus every descendant's exact time, once each.
- Workstream and project: sum exact owned records, not already-inclusive parent totals. Concurrent run time is additive task-time, not union-of-wall-clock time.

## Findings and corrections

1. `startNextExternalStage` and re-delegating an already-running item replaced the active start timestamp without banking that segment. Both now bank the segment transactionally before restarting it.
2. Hierarchy external rollups used inline calculations and omitted legacy task-less workstream activity. The shared ExternalTiming projection now includes that activity, excludes paused live segments, and walks task ancestry iteratively.
3. Task rows displayed focused history without external elapsed. They now label the categories independently; project overview exposes both totals and workstream summaries expose external elapsed.
4. Home Working For You and Needs You showed reminder timing but not exact elapsed time. Both now show the exact execution's external elapsed, also shown in external detail.

## Persistence and isolation

Task execution timestamps, accumulated seconds, pause flags and check deadlines already round-trip through Room task entities. TaskExecutionWriter routes mutations to the exact task ID inside the repository transaction. Pause banks the active segment and clears its start; resume starts a new segment while retaining the accumulated value. Completion banks and stops only that exact task. Events and snapshots retain transition timestamps. No database migration or destructive rewriting of existing timer history is required.

Previously discarded elapsed segments cannot be inferred safely and are not fabricated. Persisted timestamps use Instant; elapsed values are calculated at whole-second precision. A manual device clock change can affect a currently open timestamp-based segment; negative deltas are clamped to zero.

Human home timer behavior remains unchanged. Existing focus attribution/rollups remain separate from external tracking.

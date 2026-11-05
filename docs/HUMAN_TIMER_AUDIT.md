# Human focus timing audit — 8 October 2026

## Behavior contract

Only one human leaf task can focus across the application. Starting another closes
the previous session at the same timestamp and marks that task Paused, atomically.
Paused and never-started tasks accumulate no time. Returning starts a new session;
previous investment remains. External work is independent and continues running.

Example: A runs for 10 minutes, B for 5 minutes, then A for another 5 minutes.
A shows 15 minutes, B 5 minutes; their common parent/workstream/project shows 20.
Cross-project switching assigns each duration only to its owning project.

## Storage and calculation

FocusSession stores an ID, exact task ID, owning workstream, cycle ID, startedAt and
nullable endedAt in the existing database. Closed durations use endedAt-startedAt;
only the open session uses now-startedAt. No frame-based counters or new migration.
Completion and leaving close the session. Navigation alone does not end focus.
App restart preserves timestamps; focus continues until explicitly ended/switched.

Task detail includes its own historical sessions plus descendants. Workstream totals
sum original sessions once, not parent rollups; project totals sum owned workstream
sessions once. Existing standalone tasks use their persisted execution owner.
Historical parent-attributed sessions remain valid after adding descendants.

## Findings and fixes

- Switching already paused the previous task, but its activity event incorrectly
  said Ready. The event now matches the saved state.
- Repeated Focus taps split the same exact task's session, potentially losing
  fractional seconds to whole-second display rounding. They are now idempotent.
- Hierarchy totals recursively traversed every subtree. Iterative ancestor walks
  avoid stack overflow at deep nesting, with cycle guards and session-ID deduplication.
- Existing hierarchy headers already display inclusive clock totals, project overview
  separates focused/external totals, and task cards use compact focused durations.
  These existing design conventions are retained; human home layout is unchanged.

## Verification and limits

Deterministic tests cover cross-project switching, pause exclusion, resume, completion,
restored action engine, repeated taps, fractional elapsed display, 10,001 nested tasks
and duplicate-session protection. Existing focus, hierarchy and external tests run too.
Durations display whole seconds; system clock changes can affect an open interval.
This does not fabricate missing historical sessions or repair old corrupted records.

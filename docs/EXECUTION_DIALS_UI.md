# Project execution signals

Verified against the implementation: 2026-10-02

## Purpose

The Project Overview makes the existing WorkStream execution state visible without introducing a
second scheduler, timer, or activity model. Every dial is a read-only presentation of the canonical
`WorkStream.state`; row subtitles continue to come from `ExecutionTiming.workStream`. A circle is
reserved for a future truthful Multi signal; individual states use distinct silhouettes.

## State mapping

| Canonical WorkStream state | Project dial | Motion | Pill |
| --- | --- | --- | --- |
| `FOCUS` | four-corner emerald aperture with teal diamond | breathing convergence | `FOCUS` |
| `PROCESSING` | cobalt horizontal comet waves | continuous forward flow | `RUNNING` |
| future concurrent projection | four independently orbiting execution ribbons with active count | mixed-speed orbital flow | `MULTI RUN` |
| `CHECK` | open saffron inspection ribbons with gold diamond | short side-to-side scan | `REVIEW` |
| `READY` | quiet grey marker | static | none |
| `SNOOZED` | opposing grey suspension curves | gentle floating | `WAITING` |
| `BLOCKED` | horizontal blocks stopped by a coral locked gap | blocks approach the fixed lock | `BLOCKED` |
| `PAUSED` | opposing grey suspension curves | gentle floating | `PAUSED` |
| `DONE` | mint rounded-square green check on the left | static | none |

Short bars below the subtitle repeat the same meaning without depending on colour alone. Cards keep
their neutral border; state colour and animation stay inside the card. Existing row taps still open
the same WorkStream destination.

Active signals use state-specific silhouettes: four Focus aperture corners breathe toward a teal
diamond, Running advances as layered cobalt waves, and Review scans a gold diamond between two
open saffron inspection ribbons. Blocked segments move horizontally toward a fixed coral lock and
never cross its gap, so motion exposes stalled work without implying progress. The exact current
Task reuses the WorkStream signal and a matching internal wash, so the visual language remains
consistent from Project to hierarchy.

The implemented Multi Run renderer uses independently orbiting emerald, cobalt, saffron and violet
ribbons around an exact active-item count. Its pill is always the single-line label `MULTI RUN`.
The renderer and its instrumentation fixture are ready, but production mapping remains deliberately
dormant: a WorkStream currently has one canonical `activeTaskId`. Activate it only after the domain
supplies a truthful concurrent-active projection; never infer it from preferences or descendants.

## Project summary

The `Execution dials` strip counts Project-owned WorkStreams in `FOCUS` and `PROCESSING`. It does
not count tasks by execution preference, because Human/External preference is eligibility and not
proof that work is currently running.

## Important model boundary

Virlin permits one human `FOCUS` WorkStream and multiple `PROCESSING` WorkStreams. An individual
WorkStream still owns one canonical `activeTaskId`. The UI therefore shows parallel external work
truthfully across multiple WorkStreams, but does not invent multiple simultaneously running tasks
inside one WorkStream. If that domain contract changes later, extend the canonical projection first;
do not infer concurrency from descendants or execution preferences in Compose.

## Ownership

- Domain state: `domain/model/WorkStreamModels.kt`
- Timing and wording: `domain/model/ExecutionTiming.kt`
- Domain-to-UI mapping: `ui/hierarchy/HierarchyScreens.kt`
- Active project renderer: `ui/hierarchy/VirlinProjectExperience.kt`
- Shared/retained renderer and dial components: `ui/hierarchy/VirlinProjectPage.kt`

No database, route, task ordering, or alternate timer is owned here.

## Workstream controls (2026-10-03)

The active Project Overview removes per-card percentages, retains the global project progress,
and reserves an 82dp aligned status touch target. Tapping it opens centered Workstream controls;
the rest of the project is dimmed by the modal without a RenderEffect blur. Row navigation remains
unchanged. Task and subtask menus now expose scoped Work controls; see TASK_EXECUTION_AUDIT.md
for the verified routing and the remaining per-task persistence limitation.

Execution preference is collapsed initially and expands to equal-sized Inherit / Human / External
segments, with the project default explained below rather than stuffed into the segment label.
Changing preference does not start work. Activity controls delegate to the existing
AttentionIntentController, preserving scheduling, preset durations and permission requests. The
Project popup renders its own consistent inline steps rather than opening NowChooserDialog.
Focus uses the hierarchy's existing pending-switch intent and globally exclusive session.
Check due and result-ready reminders are distinguished by SnoozeReason, never timer expiry alone.

Completion has a separate text-only action row away from the heading, without a checkbox. It requires an
explicit confirmation before completeStream; unfinished tasks are not completed and no next item
auto-starts. DONE removes the right status tag and replaces the left animation with a static check.
CHECK/SNOOZED cannot transition directly to DONE under the existing domain: the completion control
is disabled until the reminder is resolved or explicitly ended with Make ready. Archive is omitted
because there is no archival domain action. No fake archive or reopen is implemented.

All steps share one Dialog, fixed heading, workstream identity, status, typography and palette.
Crossfade transitions the stable-height body for completion, pause timing, handoff timing, check outcomes,
review reminders and focus-switch confirmation. Back returns to the main controls without writing;
Close dismisses and clears transient chooser/switch state. Feedback appears inline, never in a
second AlertDialog. Duration chips select only; Confirm executes the existing timed action. Positive
custom minutes and contextual notification permission remain required. No Now-screen UI is changed.

Pause without a reminder now uses PAUSED; Resume later schedules a human-return reminder without
starting focus. Blocked work supports Unblock and editing its persisted reason. Ready external work
can start directly after checking the active task's effective eligibility, without displacing human
focus elsewhere. Handoff still requires Focus and effective external eligibility.

Confirmed external results stop external tracking and preserve EXTERNAL_RESULT_READY in CHECK.
Review later preserves that reason; ordinary check expiry remains Check due, not Result ready.
While running or check-due, the main popup has a single Check progress entry. Still running,
Result ready and Blocked appear only in its inline outcomes step, not as duplicate main shortcuts.
Mark as complete is distinct: it closes the workstream rather than requesting review of its output.
No concurrent task ledger, archive/restore, reopen, new timer, schema, or route is introduced.

## External tracking pause/resume (v19)

Running/check-due external work offers Pause tracking inside the same modal, with preset/custom
minutes or no reminder. This stops Virlin tracking only, not the external process. The pause closes
the active interval into persisted externalTrackedSeconds and sets externalTrackingPaused. Return
reminders use the existing scheduler; expiry surfaces CHECK but keeps the paused marker and never
starts a session. Resume external tracking opens a new timestamp interval without changing human
focus. Paused intervals are excluded. Change return reminder supports extending or removing it.
Now's resume intent and notification resume route paused external tracking to the same domain action.
The additive 18->19 migration defaults both fields to zero, preserving existing start timestamps.

## Full execution plan: remaining foundation

## Unified task cards and management — 2026-10-04

Task/subtask cards now use the shared execution icon on the left and status action on the right.
Completed tasks use the shared left check. Name/card taps navigate; icon/status taps open controls.
Row arrows, overflow menus, numbering tiles and direct completion circles are removed. Organization
uses explicit Select text actions rather than checkboxes. Nested headings use the same signals.
Controls contain one Manage section with inline Rename, Add subtask, Move, Duplicate and confirmed
Cancel actions. Moves exclude the task and descendants. Workstream controls add inline Rename;
title-only changes preserve timing timestamps. This does not add independent task execution records.

Human-controls correction: Workstream focus resolves its active task or next actionable leaf,
with a direct stream compatibility path when no task exists. Both paths request confirmation
before replacing another global human focus. Cancel does not write. Human CHECK without an
external active interval offers Focus now/Remind later, not external progress outcomes. Human
pause/resume reuses the canonical sessions and reminder flow. Changing a paused external stream
to effective Human clears the external-pause marker while preserving tracked seconds. Running
external work must be paused before it can resolve Human. Errors are shown inline rather than
only logged. No automatic focus starts on a responsibility change.

The requested task/subtask execution plan is not fully implemented by these workstream controls.
Remaining work includes persisted per-task execution records, concurrent external runs within a
workstream, atomic focus displacement to Paused/Waiting with task attribution, ancestor projections
without duplicate timers, task-path Now queues, archive/restore and reopen actions, and migration of
legacy stream activity including taskless streams. These require a coordinated storage/domain
change before the UI can truthfully expose them. Do not activate MULTI RUN from task preferences.

Verification: 2026-10-03, 1,168 unit/UI tests passed, Android instrumentation sources compiled,
and the debug APK installed on the Pixel 8 emulator. Instrumentation compilation is not execution.

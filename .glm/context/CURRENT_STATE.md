# Virlin current state (GLM summary)

`docs/DEVELOPMENT_STATUS.md` is the authoritative, pass-by-pass truth. This file is a compact
load-every-session summary. If it ever disagrees with DEVELOPMENT_STATUS or the source, they win.

As of 2026-09-13. Nothing is scheduled next — the next feature starts only when a pass asks for it.

## Frozen / approved surfaces

- **Now UI** — approved: Header · Current Focus (split-flap timer) · Needs You · Working For
  You · When You're Free · floating Orb · bottom-attached nav (Now/Streams/Pulse/Inbox).
- **Virlin Orb** — Stitch liquid-glass 52dp sphere (AGSL shader, API 33+, Canvas fallback),
  root `OrbTravelLayout` overlay, interaction state machine owned by `VirlinAgentViewModel`.
- **Fullscreen Focus Clock** — `FocusClockScreen`, grouped two-card flip, black canvas,
  same FocusSession (never a second timer).
- **Agent shell** — in-window sheet, Stitch entry step ("How can I help?" + Control/Create/
  Capture cards + "Or just tell me…" pill) → one mode workspace (no mode tabs), pinned composer.
- **Agent deterministic V1** — Control V1 · Create V1 · Capture V1 complete and frozen.
- **Bottom navigation** — standard bottom-attached Material 3 bar; Inbox tab = the capture
  Inbox with a live badge.

## Completed passes (condensed)

| Pass | Delivered |
|---|---|
| Domain + Action Layer | `WorkStream` model, transition table, single-Focus invariant, `VirlinActions`, repository boundary, `VirlinClock`/`IdProvider` injection |
| Hierarchy | Project + recursive Task, `activeTaskId`, `ProgressCalculator` (leaves only), structure actions |
| Hierarchy UI (Pass 2) | Streams → Project/WorkStream/Task detail, derived progress + breadcrumbs |
| Now hierarchy card (Pass 3) | Project/WorkStream/deepest-Task on Current Focus; task-first COMPLETE |
| Attention exits (Pass 4) | LEAVE vs HAND OFF, chooser presets, check outcome flow |
| Room persistence (Pass 5) | `virlin.db` schema v1→v2, transactional repository, process-death recovery, overdue reconciliation |
| Scheduling (Pass 6) | `AttentionScheduler` → AlarmManager, stale validation, boot reschedule |
| Notifications (Pass 7) | Actionable attention notifications (RESUME/FOCUS NOW/+5 MIN/CHECK), identity/grouping |
| Agent Control (Pass 8) | Structured control over persisted state via `AttentionIntentController` |
| Agent Create (Pass 9) | Project/WorkStream/Task creation, chaining, explicit mode |
| Agent Capture (Pass 10) | Durable Capture Inbox (NOTE/PROMPT/LINK), archive/organize/convert, Room v2 |
| Command contract (Pass 11) | `VirlinCommand → CommandResolver → CommandExecutor`, clarification/confirmation values |
| Text commands (Pass 12) | `TextCommandInterpreter`, command panel (clarification · confirmation · preview · answer) |
| Time language (Pass 13) | `TimeExpressionParser`, `TemporalIntent`, daypart policy, temporal clarifications |
| Deterministic-only reset | Pass 14 hybrid LLM fallback **removed**; guarded by `DeterministicOnlyTest` |
| Control V1 (2 passes) | Natural `[ACTION] + [TARGET] [+ TIME]`, kind-less targets, timed leave/hand off/check/reminders/block, open/show navigation |
| Create V1 | Natural creation, entity word required, mode never inferred, owner resolution, preview |
| Capture V1 | Raw boundary verified + `save note` grammar addition |
| Nav + Inbox | Bottom-attached bar, Inbox destination + badge, Orb slot fix |
| Orb V2 + entry sheet | Stitch liquid Orb; "How can I help?" entry; workspace refinement (Quick Actions / cards) |

Last verified totals: unit ≈430, Roborazzi 23 goldens, instrumented 60/60 (+1 skipped manual
harness) on Pixel 8 / API 35 — see DEVELOPMENT_STATUS for the exact current numbers.

## Deliberately NOT implemented (do not "fill in" unasked)

- No LLM, local/cloud model, API key, network interpretation, general chatbot, autonomous agent.
- No voice, TTS, Wispr, audio recording. `speechEnergy` is a hook only.
- No reminder entity (`createReminder` deliberately absent from the command contract).
- No Capture voice/file/image (UI cards exist but disabled — no picker, storage or persistence).
- No recurring time, no Task due-date NLP, no multi-command utterances, no bulk/conditional commands.
- No cloud sync, authentication, Supabase. DataStore is preferences-only (not yet used much).
- No dependency upgrades: AGP 8.5.1 / Gradle 8.9 / Kotlin 2.0.0 / Compose BOM 2024.06.00 are
  intentional pins.

## Repo-root notes

Python files at the repo root (`components.py`, `screens.py`, `orb_test.py`, …) are design/
prototype artifacts, not app code. `maestro/` holds optional-future flows (Maestro has no
native Windows support and is not required). `graphify-out/wiki/` does not currently exist.

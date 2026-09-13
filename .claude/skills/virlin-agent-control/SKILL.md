---
name: virlin-agent-control
description: Virlin Agent CONTROL mode rules — the Agent as a deterministic, structured alternate interface to existing VirlinActions; action boundaries, Control semantics, forbidden direct mutations, the no-NLP-yet rule and the frozen Orb. Load ONLY when changing Agent Control behaviour or its presentation. Do not load for Orb motion, Now, hierarchy screens or persistence work.
---

# Virlin — Agent Control

Load on demand. Domain rules: `virlin-data-domain`. Compose rules: `virlin-android-compose`.

## What Control is

- The Agent shell (Orb → in-window sheet, CONTROL/CREATE/CAPTURE) is frozen; only the CONTROL
  content is real (Pass 8). It is an **action surface over existing work**, not a second Now:
  Current Focus · Needs You · Working For You · Ready, each with structured controls.
- Files: `ui/agent/control/AgentControlState.kt` (`ControlKind`, `ControlAction`,
  `ControlItem`, `AgentControlPresentation.build`), `AgentControlViewModel.kt`,
  `AgentControlArea.kt`. Wired through `AgentShell(controlContent = …)` in `VirlinApp`.

## Boundaries (hard)

- Every control is one existing `VirlinActions` path via the shared
  `AttentionIntentController` (`ui/screens/AttentionIntentController.kt`) — the SAME object Now
  uses. Never re-implement focus/leave/hand-off/check/complete/cancel/active-task rules here.
- Never call a DAO, `AttentionScheduler`, AlarmManager or notification APIs from Agent code;
  alarms and notifications follow from committed state through the scheduling decorator.
- No durable Agent state, no cached `activeTaskId`, no stored Active Path, no Agent history DB.
  `Selection` (picker, expanded ids, pending cancel, offered next candidate) is ephemeral.
- Stale selections are safe: the domain validates on every action; a rejection shows
  "Couldn't do that — X has changed" and the state re-projects.

## Semantics (identical to Now)

- FOCUS_HUMAN: LEAVE (shared chooser) · COMPLETE (task-first; whole-stream needs confirmation)
  · TASKS. FOCUS_EXTERNAL adds HAND OFF (1/2/5/10/15/custom/no check — never a silent 5m).
- RETURN_DUE/PENDING: RESUME · DEFER (3/5/10/15/custom, reason unchanged). CHECK_DUE and
  PROCESSING: CHECK → "What happened?" (never focuses). RESULT_READY_*: FOCUS NOW · DEFER.
- Tasks: recursive picker (`HierarchyPresentation.rows`, any depth, indent capped at 3,
  auto-expanded to the active task); SET CURRENT = `setActiveTask`; COMPLETE = `completeTask`
  (next candidate is offered, never selected); CANCEL needs confirmation and stays ≠ done.
- Projectless streams show no project line; no placeholders anywhere.

## Not yet (do not add without a pass that asks for it)

- Composer text in CONTROL runs the deterministic command language (`virlin-agent-text-command`)
  through the typed contract (`virlin-agent-command`) onto the same actions — **Control V1 is
  complete (Control 1 + 2)**: natural `[ACTION] + [TARGET] [+ TIME]` (focus / leave / hand off /
  check / still running / result ready / remind / block / complete / cancel / open) with the
  same Leave ≠ Hand off, Check ≠ Reminder, Ready ≠ Focus, Block-is-WorkStream-only semantics as
  the structured controls. No awareness/query command layer (Now is the awareness surface). No
  LLM, no local model, no voice/Wispr/TTS, no general NLP. Create is real (Pass 9, see
  `virlin-agent-create`); Capture is real (Pass 10, see `virlin-agent-capture`).
- Orb: shader, palette, geometry, motion, tap affordance — untouched.

---
name: virlin-agent-capture
description: Virlin Agent CAPTURE mode rules — the durable local Capture Inbox (NOTE / PROMPT / LINK) as low-friction external memory; Capture is not Task creation, context is optional and explicit, no parsing or AI, archive over delete, Room migration discipline, frozen Orb/shell. Load ONLY when changing Capture behaviour, its persistence or its presentation. Do not load for Control, Create, Orb motion, Now or hierarchy work.
---

# Virlin — Agent Capture

Load on demand. Domain rules: `virlin-data-domain`. Control/Create: `virlin-agent-control`,
`virlin-agent-create`. Compose rules: `virlin-android-compose`.

## What Capture is

- **Low-friction external memory**: "don't make me decide where this belongs — just don't
  lose it." Orb → CAPTURE → type/paste in the pinned composer → SAVE TO INBOX. Seconds.
- **Capture ≠ Task.** `CaptureItem` (`domain/model/CaptureModels.kt`) is its own durable
  object: `CaptureType {NOTE, PROMPT, LINK}`, `CaptureStatus {INBOX, ORGANIZED, ARCHIVED}`
  (never Task statuses), verbatim `content`, optional `title`, `sourceUrl` (LINK), optional
  explicit context (`projectId` / `workStreamId` / `taskId`), `convertedTaskId`, timestamps.
- Files: `domain/action/CaptureActions.kt` (composed into `DefaultVirlinActions`),
  `domain/repository/CaptureRepository.kt` (`CaptureRepository` + `CaptureWriter`, implemented
  by the SAME repository/transaction as WorkStreams), `data/db` (`CaptureEntity`, `CaptureDao`,
  schema v2), `ui/agent/capture/` (`AgentCaptureViewModel`, `AgentCaptureArea`,
  `CapturePresentation`). Wired through `AgentShell(captureContent = …)`; in CAPTURE mode only,
  the composer's SAVE TO INBOX calls `AgentCaptureViewModel.save(text)`.

## Two surfaces, one domain

- **Agent CAPTURE (Orb)** = quick capture composer (type · optional context · SAVE TO INBOX) plus the inbox.
- **Bottom-nav Inbox tab** = view / manage previously captured items: `InboxScreen` → the shared
  `CaptureInbox` composable → its own `AgentCaptureViewModel` → the same repository. The tab's
  badge is the live `CaptureStatus.INBOX` count. Never build a second capture list.

## Stitch Capture UI (2026-09-13)

Five cards (Text Note · Prompt · Link · File / Image · Voice) + pill composer. Only the first three are
backed (`CaptureType`); File / Image and Voice are disabled placeholders — no picker, storage, recorder or
speech exists. Do not wire them to the demo `InputObject` chips and do not fake captures for them.

## Boundaries (hard)

- Every write is one `VirlinActions` intent: `createCapture`, `updateCapture`,
  `attachCapture` (ATTACH: stays a capture, gains context), `archiveCapture` /
  `restoreCapture`, `convertCaptureToTask` (CONVERT: real Task via the structure rules + capture
  ORGANIZED in ONE transaction; once only). UI never touches DAOs.
- Saving a capture touches nothing else: no Focus, no `activeTaskId`, no WorkStream state, no
  alarm, no notification, no event. Only an explicit organize/convert changes structure.
- Context is **explicit and visible** ("Global · Inbox" vs "Attached to · X"); the
  ATTACH TO CURRENT chip is a one-tap choice, never silent. Context resets after each save.
  The domain validates context (unknown / mismatched / stale ids are rejected) and derives
  ancestry from a task — never trust the form.
- **No interpretation**: no NLP, regex, heuristics, classification, summarisation, tagging,
  reminders or auto-context from text. PROMPT text is stored exactly and never executed or
  sent anywhere. LINK URLs are stored, never fetched (no network, no previews).
- **Capture V1 (frozen)**: CAPTURE-mode composer text is saved raw — `TextCommandInterpreter`
  is never on that path, so "Leave Psychology for 10 minutes" typed in CAPTURE mode is a NOTE.
  CONTROL/CREATE text runs the deterministic command language (`virlin-agent-text-command`), where
  `remember | note | capture note | save note <text>`, `save prompt | capture prompt <text>` and
  `save link | capture link <url>` are recognised FIRST and their payload terminates as raw data
  (never re-parsed). Guarded by `CaptureBoundaryTest` (structural + behavioural).
- Content is user data: never log it, never put it in notifications, local Room only.
- Archive over delete: ARCHIVED keeps content/history; there is no hard delete.
- Form/draft state is ephemeral; a saved capture is durable the moment SAVE returns.

## Room migration discipline

- `VirlinDatabase` v2 adds `captures` via `MIGRATION_1_2` (additive). Every schema change:
  bump `version`, keep `exportSchema`, write an explicit `Migration`, add it to `MIGRATIONS`,
  and extend `VirlinMigrationTest` (old version from `schemas/…/N.json`, representative rows,
  `runMigrationsAndValidate`). **Never `fallbackToDestructiveMigration`.**

## Not yet (do not add without a pass that asks for it)

- Voice / Wispr / speech, files, images, Storage Access Framework, share-sheet receiver,
  full-text search / FTS / embeddings, RESPONSE type / prompt-response pairing, cloud sync.
- Orb, Agent shell, tabs and composer visuals are untouched.

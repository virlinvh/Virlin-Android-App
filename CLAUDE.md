# Virlin — Claude Code Project Instructions

## Product

Virlin is a native Android **human-attention orchestration system** for people managing many simultaneous work streams.

Core principle:

> External tools/processes can work in parallel. Human attention is usually serial. Virlin decides what needs human attention now while preserving the context of everything else.

Virlin is NOT a conventional task manager.

---

## Current Development Stage

Frontend-first development.

### FROZEN / APPROVED

The main **Now UI is approved**.

Do NOT redesign it unless explicitly requested.

Approved Now structure:

1. Header
2. Current Focus
3. Needs You
4. Working For You
5. When You're Free
6. Floating Virlin Orb
7. Bottom navigation: Now / Streams / Pulse / Inbox (bottom-attached bar; Inbox = the capture Inbox with a live INBOX-count badge)

The Now screen is the primary attention surface.

### CURRENT TASK

Current development focus:

> **Agent input layers** — CONTROL, CREATE, CAPTURE, the typed command contract, the deterministic text + time language and natural `[ACTION] + [TARGET] [+ TIME]` control (**Control V1 complete**: kind-less named targets, action-filtered global resolution, typed clarification continuation, timed leave / hand off / check / still running / result ready / reminders / block by name, open/show navigation) and natural Create (**Create V1 complete**: project / [human|external] workstream [under P] / task [under Project|WorkStream|Task] / subtask alias, typed owner + mode clarification continuing the pending create, preview before create) and raw Capture (**Capture V1 complete**: CAPTURE mode never interprets; `remember / save note / save prompt / save link` prefixes keep their payload raw) are real. **The Agent's deterministic V1 — Control · Create · Capture — is complete and frozen.** **V1 language is DETERMINISTIC ONLY**: no LLM, no local/cloud model, no model download, no API key. Voice comes only when a pass asks for it

The Orb's visual, its interaction/state system, and the Agent workspace shell are done.
**CONTROL is real (Pass 8):** structured controls over persisted state through the same
`VirlinActions` as Now — see the `virlin-agent-control` skill. **CREATE is real (Pass 9):**
structured Project / WorkStream / Task creation through `VirlinActions` — see the
`virlin-agent-create` skill. **CAPTURE is real (Pass 10):** a durable local Capture Inbox
(NOTE / PROMPT / LINK, optional explicit context, archive, organize/convert) through
`VirlinActions` and Room schema v2 — see the `virlin-agent-capture` skill. **The command contract is real
(Pass 11):** `VirlinGraph.commands` — typed `VirlinCommand` → `CommandResolver` (clarification /
confirmation as values) → `CommandExecutor` → the same `VirlinActions`; see the `virlin-agent-command`
skill. **Composer text is a deterministic command language (Pass 12):** CONTROL / CREATE submit →
`TextCommandInterpreter` → `VirlinCommand` → resolver → command panel (clarification · confirmation ·
create preview · answer · feedback) — see `virlin-agent-text-command`. CAPTURE-mode raw text stays a
capture. **Time language (Pass 13):** `leave until 3 PM`, `hand off until tomorrow morning`, `check again
Monday at 9 AM` resolve on an injected clock + device `ZoneId` (`TimeExpressionParser`, `TemporalIntent`
Relative vs Absolute, `TimeLanguagePolicy` dayparts 09:00 / 15:00 / 19:00 / 20:00) — see
`virlin-agent-time-language`. Past times and ambiguous phrases clarify; nothing recurs. **Deterministic only (2026-09-12 reset):** `Unsupported` text is shown as
unsupported with a few central examples and STOPS there — there is no second interpreter, provider,
model or network behind the parser (the Pass 14 hybrid/fallback layer was removed; guarded by
`DeterministicOnlyTest`). No general chatbot, no
autonomous agent, no voice.
Do NOT redesign or replace the Orb unless explicitly requested.

The Agent is ONE in-window workspace opened by the Orb — never a chat page, a fourth
navigation destination, a second dashboard, or a transcript. Its real behaviour is next.

Approved Agent architecture:

**CONTROL · CREATE · CAPTURE**

- **Control** — inspect/control existing WorkStreams and processes.
- **Create** — create new work in the appropriate project/context.
- **Capture** — instantly preserve thoughts, prompts, links, voice, files, images, notes, etc. Capture first; organize later.

Do not turn the Agent into another Now dashboard.

---

## Frozen Now UI Rules

### Current Focus

Represents:

> My human attention is here.

Contains:
- Current Focus status
- stream/context
- split-flap Focus timer
- FOCUS INVESTED
- NEXT action
- LEAVE (opens the return chooser)
- COMPLETE (human) / HAND OFF (external)

The split-flap timer is a signature Virlin interaction.

Do not replace it with a normal digital timer.

### Fullscreen Focus Clock

Status: **IMPLEMENTED**.

Tapping the Now split-flap Focus timer opens `FocusClockScreen` (route `focus_clock`,
`ui/screens/FocusClockScreen.kt`) — a distraction-free landscape fullscreen clock.

It uses the SAME FocusSession/timer state.

It must never create a second timer or reset/pause the existing timer.
`FocusClockScreen` owns NO timing state: it observes `MockData.streams`, the same
StateFlow that `MockTimerEngine` ticks and that Now observes. Never add a local
counter, ticker or timestamp to it.

It contains, in this visual hierarchy and nothing else:

1. the flip timer (dominant)
2. the device wall-clock time above it (extremely subtle)
3. a FOCUS INVESTED caption below it (extremely subtle)
4. a close control (lowest prominence)

No Orb, no navigation, no cards.

**Approved fullscreen visual specification** — this surface deliberately shares no visual
tokens with Now. It is a physical split-flap desk clock on a black canvas:

- Background: pure black (`#000000`), edge to edge, immersive landscape. No pearl, no
  gradient, no panel, no card around the clock.
- **GROUPED two-digit flip cards.** The timer is TWO cards — `[MM] : [SS]` — not four
  digit tiles. Card surface `#212121`, 20–28dp corner radius, with a continuous centre
  hinge running the full width of each card.
- Numerals: huge, white/off-white, filling most of the card height.
- Colon: two stacked subtle light-grey dots, not inside a card.
- Depth: restrained only — a darker body behind each card and a hairline edge. No glow.
- Wall clock: device local time (honours the user's 12/24h setting), small, centred, white
  at ~0.35 alpha, refreshed once per minute. It is NOT focus elapsed time.
- FOCUS INVESTED: uppercase, centred, white at ~0.40 alpha, small, extra letter spacing.
  No card, no pill, no icon. (The Now screen keeps its own separate FOCUS INVESTED row.)
- Close control: **top-right**, a bare white X glyph with no enclosing circle, 26dp visual
  inside a 48dp touch target, resting ~0.32 alpha and brightening to ~0.85 while pressed.
  `contentDescription = "Close focus clock"`.
- Clock group occupies roughly 65–75% of usable landscape width, sized responsively
  (`BoxWithConstraints`) so it never clips or touches the edges.

**Fullscreen flip behaviour — grouped, not per-digit.** Each card is ONE physical surface
driven by ONE animation progress. When a card's two-character value changes, the WHOLE card
flips, even if only one character differs (`27` → `28` flips the entire seconds card). The
complete two-character string is rendered once and clipped into top and bottom halves, so
both halves share one baseline and one horizontal centre. Digits inside a card must never
animate independently, and a card must never become a single pre-rendered bitmap — it is a
live two-half flip. The minutes card flips only when the minute value changes.

This grouped behaviour is fullscreen-only and self-contained in `FocusClockScreen.kt`
(`GroupedFlipCard` / `CardHalf`). **The Now screen keeps its existing per-digit
`SplitFlapDigit` animation, where only the changed digit flips.** `SplitFlapTimer.kt` is not
involved in fullscreen rendering and must not be changed to serve it.

It is landscape and immersive for its lifetime only. Orientation, system bars and system-bar
colours are restored on exit. Exiting (X or Android Back) returns to Now; the Now UI stays frozen.

Tapping the large timer inside this screen does nothing by design.

### Needs You

Represents streams requiring human attention.

Uses:
- semantic warm surfaces
- attention beacon
- slow border emphasis
- slow arrive → hold → leave attention animation

No rolling/travelling border.
No diagonal animation.
No flashing text.
No bouncing cards.

### Working For You

Represents external processes progressing without human attention.

Uses compact processing rows and subtle animated processing indicators.

PROCESSING does not consume human Focus.

### When You're Free

Represents Ready work.

Keep calm and visually subordinate to Focus and Needs You.

### Virlin Orb

Signature living Agent object.

Current custom implementation is authoritative. **Interaction system: IMPLEMENTED** — one
semantic state model (`ui/orb/VirlinOrbInteractionState.kt`), one owner
(`VirlinAgentViewModel`), the existing renderer modulated by parameters, and the single Orb
travelling into an in-window Agent shell. Details in `docs/DEVELOPMENT_STATUS.md`; rules in
the `virlin-motion-interaction` and `virlin-android-compose` skills.

Do NOT replace it with a generic:
- microphone
- chatbot icon
- sparkle
- brain
- assistant logo

**Stitch Orb (2026-09-13)**: a 52dp liquid sphere rendered by the Stitch design's WebGL liquid shader ported to AGSL (`STITCH_LIQUID_AGSL`, pale matcha / pistachio, domain-warped fbm, gentle 4.5 s float; API < 33 or software canvas falls back to the previous Canvas liquid) above the bottom bar at bottom-right, owned by the root `OrbTravelLayout` (never by screen content — it cannot scroll with Now). A tap opens the Agent sheet on the Stitch **entry step** (white 34dp sheet, compact Orb, "How can I help?" / "Turn your thoughts into action.", Control · Create · Capture cards, "Or just tell me…" pill); choosing a card transforms the same sheet into that one workspace (← back · Orb · mode icon/title/subtitle · × close · only that mode's content · its composer) — the mode tabs no longer exist. Do not modify the Orb or the entry sheet unless explicitly requested.

---

## Core Domain Model

Primary object:

`WorkStream`

A WorkStream is a persistent chain of work, not a one-off task.

Core states:

- `FOCUS`
- `PROCESSING`
- `CHECK`
- `READY`
- `SNOOZED`
- `BLOCKED`
- `PAUSED`
- `DONE`

Normally:
- many streams may be PROCESSING
- only one stream consumes human FOCUS

Important cycle:

`FOCUS → HAND OFF → PROCESSING → CHECK → FOCUS → HAND OFF ...`

Human work leaves Focus with LEAVE (Pass 4, implemented):

`FOCUS → LEAVE → READY` or `FOCUS → LEAVE + return time → SNOOZED(HUMAN_RETURN) → RESUME → FOCUS`

LEAVE never produces PROCESSING; only HAND OFF does. A due external check offers
STILL RUNNING / RESULT READY (Focus now or remind later → SNOOZED(EXTERNAL_RESULT_READY)) /
BLOCKED, and never steals Focus. Timed returns/checks are persisted and wake the user via
AlarmManager + actionable notifications (RESUME / FOCUS NOW / +5 MIN / CHECK → in-app flow,
Passes 6–7); Room is the truth, alarms and notifications are only delivery.

Do not introduce a single-active-task architecture.

---

## Architecture

Native Android only.

Current/target stack:

- Kotlin
- Jetpack Compose
- Material 3 foundation
- Navigation Compose
- ViewModel
- Coroutines
- StateFlow / Flow
- Hilt
- Room
- DataStore
- WorkManager / AlarmManager
- Android Notifications
- Android TextToSpeech

Target test device:

**Pixel 8 / Android API 35**

Long-term architecture:

`Room → Flow → Repository → Domain/Use Case → ViewModel → StateFlow → Compose`

UI events travel back through the domain/repository layer.

Room IS the local source of truth (Pass 5).

DataStore is for preferences only.

Do not let UI directly mutate persistence.

**Work-structure hierarchy UI (Pass 2) is implemented:** Streams → Project Detail →
WorkStream Detail (recursive task tree) → Task Detail live in `ui/hierarchy/`; Projects are a
section inside the Streams tab, never a bottom-navigation destination (the fourth tab is the capture Inbox). Progress, current task
and breadcrumbs are always derived from the domain (`ProgressCalculator`, `activeTaskId`,
`TaskHierarchy`) — never computed or stored in UI.

**Now is hierarchy-aware (Pass 3):** the Current Focus card shows optional Project /
WorkStream / deepest active Task from `NowViewModel.currentFocus` (domain projection; layout
otherwise unchanged). DONE completes the active Task via `completeTask`; with no active Task it
asks for confirmation before `completeStream`. Never restore "DONE always completes the
WorkStream".

**Attention exits are finalized (Pass 4):** Current Focus shows `LEAVE` + `COMPLETE`
(human) or `LEAVE` + `HAND OFF` (external, from `WorkStream.mode`). See the
`virlin-data-domain` skill for the intent actions and the snooze-reason model.

**Persistence is Room (Pass 5):** `VirlinGraph.init(context)` opens `virlin.db`; state
survives process death and due returns/checks are reconciled on reopen. UI never touches DAOs.
Schema is **v2** (Pass 10 added `captures` via an explicit `MIGRATION_1_2`, proven by
`VirlinMigrationTest`); every schema change ships a real migration — never a destructive fallback.
Details in the `virlin-data-domain` skill.

**The domain layer exists (`com.virlin.app.domain`).** All WorkStream state changes go
through the unified `VirlinActions` facade (`VirlinGraph.actions`), which validates
transitions against `WorkStreamTransitions`, enforces the single-Focus invariant, snapshots
context and records events. `MockData` is display-only. See the `virlin-data-domain` skill.

Do not add Supabase, authentication, cloud sync or remote AI unless explicitly requested.

---

## Agent Architecture

The Agent must eventually use the same application Action Layer as normal UI interactions.

Conceptually:

`Voice/Text → Interpretation → VirlinCommand → CommandResolver → CommandExecutor → VirlinActions → App State → Response`

The typed command contract (`domain/command`, Pass 11) is the safety boundary: input layers
only construct `VirlinCommand`s; ambiguity becomes a `Clarification`, high-impact commands a
`Confirmation`; the executor maps to existing actions only.

The Agent must NOT directly mutate the database.

Examples of future actions:

- focusStream
- handOffStream
- snoozeStream
- markReady
- markBlocked
- completeStream
- updateContext
- addNote
- createReminder
- getNeedsAttention
- getProcessingStreams
- getStreamContext
- createTask
- createStream
- captureThought

All of these exist as typed commands now (`VirlinCommand.Control / Create / Capture / Query`);
`createReminder` is deliberately absent (no reminder model). Interpretation layers come later.

---

## Design Principles

Virlin is an:

> attention-control interface for parallel work

NOT a prettier task manager.

Visual hierarchy:

1. Current Focus
2. Needs You
3. Virlin Agent
4. Working For You
5. Ready work

Motion must communicate state.

Avoid decorative animation without meaning.

Prefer:

**high information density + low perceived complexity**

Avoid:
- generic Material dashboard redesigns
- Trello/Notion/To-Do appearance
- excessive gradients
- excessive glow
- neon
- constant bouncing
- casino-style gamification
- unnecessary forms

Preserve existing approved design tokens/components whenever possible.

---

## Development Rules

### Rule 1 — Scope

Modify ONLY what the current request requires.

If asked to implement Agent UI:

DO NOT redesign Now.

If asked to fix FlipClock:

DO NOT modify Orb.

If asked to change Capture:

DO NOT refactor unrelated navigation.

### Rule 2 — Inspect Before Editing

Before coding:

1. Read this file.
2. Read `docs/DEVELOPMENT_STATUS.md` if present.
3. Inspect the existing implementation of the requested feature.
4. Inspect only directly related files/references.
5. Reuse existing components where appropriate.

Do NOT repeatedly explore the entire repository without a concrete reason.

### Rule 3 — Preserve Working Code

Do not rewrite working components merely to make them cleaner.

Prefer the smallest safe change.

Do not perform unrelated refactoring during feature work.

### Rule 4 — No Unrequested Redesign

Never reinterpret an approved UI while implementing functionality.

If existing implementation conflicts with a requested design, change only the affected component.

### Rule 5 — One Source of Truth

Never create duplicate state for convenience.

Especially:
- Focus timer
- WorkStream state
- reminders
- processing timestamps
- FocusSession

UI surfaces should observe shared state.

### Rule 6 — Real Time

Persist timestamps as source of truth.

Do not depend on continuously incremented UI counters as authoritative time.

### Rule 7 — Performance

Keep animations localized.

Avoid full-screen recomposition at animation-frame frequency.

Avoid unnecessary allocations inside animation frames.

### Rule 8 — Accessibility

Maintain:
- adequate touch targets
- readable contrast
- semantic labels
- screen-reader descriptions
- reduced-motion compatibility

State must never be communicated by animation/color alone.

### Rule 9 — Graph-First Code Navigation

For structural/codebase discovery:

1. Query Graphify first (`graphify query` / `explain` / `path` / `affected`).
2. Use it to identify the relevant symbols, files and dependencies.
3. Read only the exact source files needed for implementation.
4. Use Grep/Glob/broad repository exploration only when Graphify is insufficient or stale.
5. Never treat the graph as authoritative for implementation detail — read the source
   when the actual code matters.

### Rule 10 — Knowledge Sync After Changes

After a meaningful code change:

1. Build/test.
2. Update `docs/DEVELOPMENT_STATUS.md` if project status changed.
3. Update the relevant Virlin feature documentation if behaviour/design changed.
4. Refresh the graph: `graphify update .` (incremental, AST-only, no API cost).
   Do not do a full `graphify extract` after small edits.
5. Remove stale documentation when a previous decision was superseded.

### Rule 11 — Tool & Skill Selection

Internal structure → Graphify. External/current library docs → Context7 (`find-docs`).
Specialized Android workflow → load the one relevant official Android skill. Live device
behaviour → `android layout`. Frozen-UI visual regression → Roborazzi
(`gradlew.bat verifyRoborazziDebug`). Full reference: `docs/DEVELOPMENT_TOOLCHAIN.md`.

**Virlin skills are ON-DEMAND. Load the one that fits the task — never preload all.**

| Skill | Load when |
|---|---|
| `virlin-project` | Product, domain, frozen-UI rules, project status |
| `virlin-motion-interaction` | Animation, transitions, gestures, Orb interaction states |
| `virlin-android-compose` | Writing/modifying Virlin Compose code |
| `virlin-mobile-qa` | Verifying, testing, or diagnosing (incl. suspicious screenshots) |
| `virlin-data-domain` | Domain models, `VirlinActions`, repositories, persistence, anything that mutates WorkStream state |
| `virlin-agent-control` | Agent CONTROL behaviour/presentation, its action boundaries, the no-NLP-yet rule |
| `virlin-agent-create` | Agent CREATE behaviour (Project / WorkStream / Task structured creation), chaining, its boundaries |
| `virlin-agent-capture` | Agent CAPTURE (durable Inbox: note / prompt / link), Capture ≠ Task, Room migration discipline |
| `virlin-agent-command` | The typed command contract (`VirlinCommand` → resolver → executor), clarification/confirmation, building input layers on it |
| `virlin-agent-text-command` | The composer's deterministic command grammar, `TextCommandInterpreter`, duration parsing, the command panel |
| `virlin-agent-time-language` | Calendar/time phrases: `TimeExpressionParser`, `TemporalIntent`, daypart policy, clock/zone rules, temporal clarifications |

Official Android skills (`testing-setup`, `android-cli`, `adaptive`, `edge-to-edge`) and
`find-docs` are likewise on-demand.

**Golden screenshots are protection.** If `verifyRoborazziDebug` fails, inspect the diff and
fix the regression. Re-record a golden ONLY when the user has explicitly approved that
visual change — never to make a failing test pass.

---

## Build & Verification

Use the repository's Gradle wrapper.

Windows:

```bash
gradlew.bat assembleDebug
```

Verify on the target device (Pixel 8 / API 35) before reporting a UI change complete.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

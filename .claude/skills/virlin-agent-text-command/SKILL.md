---
name: virlin-agent-text-command
description: Virlin's deterministic text command language (Pass 12) — TextCommandInterpreter turns composer text into typed VirlinCommands (never executes), the supported Control / Create / Capture / Query grammar, duration-only time parsing, the command panel (clarification · confirmation · preview · answer · feedback) and the rule that CAPTURE-mode raw text stays data. Load ONLY when changing the grammar, the interpreter or the command panel. Do not load for Orb, Now, hierarchy, persistence or the command contract itself (see virlin-agent-command).
---

# Virlin — Agent Text Commands

Load on demand. Contract: `virlin-agent-command`. Modes: `virlin-agent-control`, `virlin-agent-create`,
`virlin-agent-capture`.

## Pipeline (mandatory)

`composer text → TextCommandInterpreter → VirlinCommand → CommandEngine.resolve → Ready | NeedsClarification | NeedsConfirmation | Rejected → AgentCommandPanel → CommandEngine.execute / confirm`

Files: `domain/command/text/TextCommandInterpreter.kt` (`TextInterpretation.Parsed / Invalid /
Unsupported`, family grammars `QueryGrammar`, `CaptureGrammar`, `CreateGrammar`, `ControlGrammar`),
`domain/command/text/DurationParser.kt`, `ui/agent/command/AgentCommandViewModel.kt`
(`CommandPanelState`), `ui/agent/command/AgentCommandPanel.kt`. Wired in `VirlinApp`: CONTROL /
CREATE composer submit → `AgentCommandViewModel.submit(text)`; CAPTURE submit → capture save.

## Rules (hard)

- **Deterministic grammar only.** Leading keywords classify the family; each grammar is a short
  readable list of forms. No general NLP, no typo/fuzzy matching, no giant unordered regex list,
  no AI classification. Unsupported text → `Unsupported(...)`; nothing is guessed or executed here,
  and **nothing sits behind it**: V1 language is deterministic only (the Pass 14 hybrid/LLM fallback
  was removed). Never add a model, provider, API or network call to this path without an explicit
  product decision; `DeterministicOnlyTest` guards the package structurally.
- **The interpreter only constructs `VirlinCommand`s.** It never resolves names (they stay
  `TargetRef.ByName`), never calls `VirlinActions`, the executor, repositories, DAOs, Room,
  AlarmManager or notifications (guarded by a unit test). Only `CommandEngine` resolves/executes.
- **Intent before time:** `create project 5 min` is a title. Time phrases go through the injected
  `TimeExpressionParser` (`virlin-agent-time-language`, Pass 13): `for|in <duration>` stays relative;
  `until|at <calendar phrase>` (3 PM, 15:30, tomorrow morning, Monday at 9 AM) is absolute. Unsupported
  phrases (after lunch, next weekend, every day) are refused.
- **Payloads are verbatim**: case, spacing and newlines are kept; one wrapping quote pair is
  stripped. Capture payloads (`capture note/prompt/link`, `note`, `remember`, `save prompt`) are
  DATA even when they read like commands.
- **Ambiguity → clarification, high-impact → confirmation.** `focus testing` with two Testing
  streams shows candidates; `create workstream X` without a mode asks "Who continues the work when
  you leave?"; `complete current workstream` and `cancel …task` need CONFIRM; whole-stream
  completion needs the explicit wording (`complete` / `done` / `finish` alone are refused).
- **Create commands preview first** (title / project / mode) and execute only on CREATE.
  Attention controls, captures and queries execute immediately once resolved.
- **"this"** = the WorkStream the Control task picker has selected (`CommandContext`); with no
  selection the resolver asks. `current` = the FOCUS stream; `current task` = its `activeTaskId`.
- **CAPTURE mode raw text is never interpreted** — SAVE TO INBOX stores it as a capture.
- Pending text / clarification / confirmation / preview are ephemeral (not persisted); executed
  results are Room. No command history or chat transcript.

## Control 1 — natural `[ACTION] + [TARGET]` (implemented)

`focus [on] X` · `switch to X` · `resume X` · `continue X` · `go back to X` · `leave X` ·
`set X as [the] current [task]` · `make X current` · `work on X` · `complete X` · `finish X` ·
`cancel X` · `open X` · `show X`. X is the COMPLETE remaining text and becomes a kind-less
`TargetRef.Named` — the parser never tokenizes it and never decides Project / WorkStream / Task.
Adding a phrase alias for an existing intent = one entry in `TextCommandInterpreter.targetTemplates`;
bulk words (`everything`, `all tasks`) are refused there. Explicit forms (`complete task X`,
`complete workstream X`, `focus workstream|task X`, `open project X`) stay as optional
disambiguators.

## Control 2 — timed / external / reminders / block by name (implemented, Control V1 complete)

`leave X for D | until T` (HUMAN_RETURN, never PROCESSING) · `come back to X in D | at T` · `hand off X`,
`let X run`, `leave X running` `[for D | and check in D | and check at T | with no check]` · `check X in D |
after D | at T | again in D | tomorrow morning` · `X is still running|working|processing, check [again] in D | at T`
(no time → asks) · `X is ready` / `result ready for X` / `X result is ready` (→ READY, no focus) `[, focus now |
, remind me in D | at T]` · `focus X, result ready` · `remind me about X in D | at T` · `bring X back in D | at T`
(state-sensitive) · `block X` · `mark X blocked` · `X is blocked` (WorkStream only) · `mark X done`.
Contextual: `leave this for D`, `hand this off [and check in D]`, `let it run`, `check this in D`, `check again in D`,
`still running, check again in D`, `result ready`, `focus it now`, `remind me in D`, `block this`.
The time tail is split off as TEXT (`splitTime`: first split the time layer resolves) and typed by
`TimeExpressionParser` before the command exists; the typed `TemporalIntent` rides through clarification.
Rejected on purpose: stop / pause / kill / end / close / freeze / dismiss / ignore / skip, `… and <verb> …`,
`if/when …`, bulk words, past-time words. No status / awareness queries by design (Now is the surface).

## Grammar (Pass 12–13, still implemented)

Control: `focus [on] <ws|this>` · `resume <ws|this|current>` · `leave [this|current] [for D]` ·
`hand off | handoff | hand this off [for D | with no check]` · `still running for D` ·
`check again in D` · `result ready | result is ready | focus result now` ·
`result ready remind me in D | remind me about the result in D` · `block this | mark this
blocked | blocked | block <ws>` · `complete current|this task` · `complete task <name>` ·
`cancel current|this task` · `cancel task <name>` · `set <task> as current | make <task> current |
work on <task>` · `complete current|this workstream`.
Create (V1): verb = `create [a|another|a new] | add [a|another] | new` · `<verb> project [called]
<title>` · `<verb> [human|external] workstream|stream <title> [under|in project|to <project>] [as
human|external]` · `<verb> task <title> [under|in|to <owner>]` (owner = Project / WorkStream / Task,
resolver decides: `TaskOwnerRef.Any`) · `<verb> subtask|child task <title> [under|to <task>]` ·
contextual `create task X` → selected WorkStream, `create subtask X` → selected/current task. Entity
word required (`create X` → asks); titles are whole text; owner split at `under` first, then `to` /
`in`, last occurrence; quoted titles never split.
Capture: `capture note|save note|note|remember <text>` · `capture prompt|save prompt <text>` · `capture
link|save link <url> [note]` — recognised first; the payload is raw data, never re-parsed.
Query: `what am I working on` · `current focus` · `what needs (my) attention` · `show needs
attention` · `what is|what's working for me` · `show processing` · `what can I work on` · `show
ready` · `show inbox` · `what did I capture` · `show projects` · `show workstreams` · `show tasks in
<ws>` (trailing `?` tolerated).

## Not yet

No LLM/local model/cloud model/model download/API key, no embeddings, no fuzzy semantic matching, no voice/Wispr/TTS, no cloud; no recurring/generic reminders, no task due-date language; no fuzzy typo correction.
Orb, shell, tabs and composer visuals untouched.

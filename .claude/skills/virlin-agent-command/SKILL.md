---
name: virlin-agent-command
description: Virlin's deterministic command contract (Pass 11) — typed VirlinCommand families (Control / Create / Capture / Query), unresolved TargetRefs vs resolved ids, CommandResolver clarification and confirmation, CommandExecutor over existing VirlinActions, read-only queries, and the rule that input layers (deterministic text today; voice only if a pass asks) are untrusted that may only emit commands. Load ONLY when changing the command layer or building an input layer on top of it. Do not load for Now, Orb, hierarchy, persistence or Agent UI work.
---

# Virlin — Agent Command Contract

Load on demand. Domain: `virlin-data-domain`. Modes: `virlin-agent-control`, `virlin-agent-create`,
`virlin-agent-capture`.

## The pipeline (mandatory shape)

`input layer → VirlinCommand → CommandResolver → ResolvedCommand → CommandExecutor → VirlinActions → repository → Room`

Files in `domain/command/`: `VirlinCommand.kt` (commands, `TargetRef`, `TaskOwnerRef`,
`CaptureContextRef`, `CommandContext`), `ResolvedCommand.kt` (`ResolvedCommand`, `CommandPreview`,
`Clarification`, `Confirmation`, `CommandResolution`, `CommandResult`, `QueryResult`),
`CommandResolver.kt`, `CommandExecutor.kt` (+ `CommandEngine` facade). Entry point:
`VirlinGraph.commands` (`submit` / `choose` / `confirm` / `resolve`).

## Rules (hard)

- **Typed commands only.** Sealed `VirlinCommand.Control | Create | Capture | Query | Navigate`. Never
  `Map<String, Any>`, JSON, method names or reflection. No "tool name → invoke" mechanism.
- **Unresolved ≠ resolved.** `TargetRef.ById / ByName / Named / Entity / CurrentStream / CurrentTask / ThisStream / ThisTask`
  live only in `VirlinCommand`; `ResolvedCommand` carries stable ids and all required fields.
  The executor's signature accepts `ResolvedCommand` only. The expected entity kind comes from
  the field (`TaskOwnerRef.WorkStream/Project/ParentTask`, `CaptureContextRef`), so same-titled
  Projects and WorkStreams are never confused.
- **Resolution is conservative and deterministic:** exact id → exact normalized title → unique
  case-insensitive title → unique prefix/word match. Anything else is a `Clarification` with
  candidates. Never pick the first match. `CurrentStream` = FOCUS stream, `CurrentTask` = its
  `activeTaskId`, `ThisStream` = `CommandContext.selectedStreamId` — none is guessed.
- **Control 1 — one name, several kinds.** `TargetRef.Named` is resolved by `CommandResolver.named`
  over the union pool of the kinds the ACTION allows (Open/Show: Project·WorkStream·Task;
  Focus/Resume/Leave/Complete: WorkStream·Task; Set current/Cancel: Task) — filtered BEFORE
  ambiguity, so "Cancel Psychology" with only a Project/WorkStream of that name is refused
  plainly, never re-routed. Candidates carry `kind` + ancestry and values `kind:id`; choosing one
  re-submits the SAME command with `TargetRef.Entity(kind, id)` (identity, no title re-search).
  A Task target for an attention action resolves to its OWNING WorkStream (+ `activeTaskId`);
  Projects and standalone Project tasks are never Focus targets. `Navigate.Open` →
  `CommandResult.Navigate` never changes Focus.
- **Control 2 — state semantics live in the resolver, not the parser.** `LeaveStream(returnAt)` →
  HUMAN_RETURN only; `HandOffStream` / `CheckStream` / `StillRunning` / `ResultReady*` require
  `mode == EXTERNAL` (`external()`), never a name hint; `CheckStream` becomes hand-off+check from
  FOCUS or a moved check while PROCESSING; `ResultReady` (no modifier) → `MarkReady`, never Focus;
  `RemindStream` → exactly one of leave-with-return / `SnoozeStream` / `DeferReturn` (reason kept) /
  next check, else `Rejected`; `BlockStream(Named)` is WorkStream-only. Commands carry typed
  `TemporalIntent`s, so a clarification continuation never reparses time.
- **Create V1 — owner resolution.** `TaskOwnerRef.Any(Named)` resolves across Project /
  WorkStream / Task with the Control 1 typed clarification; the chosen `TargetRef.Entity` comes back
  inside the same `CreateTask`. `CreateWorkStream.project` resolves among Projects only. Missing
  mode → `MISSING_MODE` refill keeps title + resolved project id (multi-step, no reparse).
  `TargetRef.ThisTask` = `CommandContext.selectedTaskId`, else the FOCUS stream's current task.
- **Capture payloads are data.** `Capture.CaptureNote/Prompt/Link` carry the payload verbatim;
  the resolver / executor only validate context and call `createCapture`. Nothing in a capture
  payload is ever interpreted, and CAPTURE-mode text never enters the interpreter at all.
- **Required fields are asked, not inferred:** `CreateWorkStream(mode = null)` → MISSING_MODE
  clarification ("I do the work" / "It can continue without me"). Never infer from a title/tool.
- **Confirmation** is a value (`CommandResolution.NeedsConfirmation`) for `CompleteStream` and
  `CancelTask`; nothing executes until `confirm`. Pending clarifications/confirmations are held
  by the caller and are NOT persisted (they may vanish on process death; domain changes are Room).
- **Executor = explicit mapping to existing capabilities.** It re-implements no transition and
  never touches a DAO, Room, `AttentionScheduler`, AlarmManager or notification API; alarms and
  notifications follow from committed state through the scheduling decorator. `ActionResult`
  is mapped to `CommandResult.Executed / Rejected(stale) / Failed` — no exceptions leak; a stale
  target is rejected by the domain, never mutated twice.
- **Queries are read-only** (`QueryResult`, presentation-neutral, no Compose types) and create no
  events. Needs-attention keeps HUMAN_RETURN / EXTERNAL_CHECK / EXTERNAL_RESULT_READY distinct.
- Times are typed `TemporalIntent`s in commands (`Relative(Duration)` evaluated by the executor's
  clock at execution; `Absolute(Instant)` resolved in the user's zone and re-validated at execution —
  see `virlin-agent-time-language`). No strings like "5m" or "tomorrow" reach the domain.
- **Input layers are untrusted**: they may only construct `VirlinCommand`s and never call
  `VirlinActions`, repositories or DAOs. In V1 the only input layer is the deterministic
  `TextCommandInterpreter` (`virlin-agent-text-command`); there is no LLM/provider layer.

## Not yet (do not add without a pass that asks for it)

- Text input exists only as the deterministic grammar in `virlin-agent-text-command` (Pass 12) and
  the calendar/time language in `virlin-agent-time-language` (Pass 13); no general NLP, no fuzzy matching.
- No LLM, embeddings, voice, Wispr/TTS, cloud. Orb, shell, tabs and composer visuals untouched.

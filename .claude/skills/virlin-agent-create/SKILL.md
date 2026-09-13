---
name: virlin-agent-create
description: Virlin Agent CREATE mode rules — deterministic structured creation of Project / WorkStream / Task through VirlinActions; chaining, after-create actions, validation copy, ephemeral form state, forbidden inference and the frozen shell. Load ONLY when changing Agent Create behaviour or its presentation. Do not load for Control, Capture, Orb motion, Now, hierarchy screens or persistence work.
---

# Virlin — Agent Create

Load on demand. Domain rules: `virlin-data-domain`. Control: `virlin-agent-control`.
Compose rules: `virlin-android-compose`.

## What Create is

- The Agent shell is frozen; CREATE content is real since Pass 9. It is a **structured form
  surface** — type chips → compact form → pickers → CREATE — never a chat, never a wizard with
  many pages, never a plan generator.
- Files: `ui/agent/create/AgentCreateViewModel.kt` (`CreateKind`, `TaskOwnerKind`, `Created`,
  `AgentCreateForm`, `AgentCreateState`) and `AgentCreateArea.kt`. Wired through
  `AgentShell(createContent = …)` in `VirlinApp`; without injection the shell still renders
  its original display-only create strip (that is what the approved `agent_create.png` shows).

## Boundaries (hard)

- One `VirlinActions` call per CREATE: `createProject`, `createWorkStream`, `createTask`
  (child tasks pass `parentTaskId`; ownership is inherited by the domain). After-create:
  `setActiveTask` (SET CURRENT, WorkStream tasks only) and `focusStream` (FOCUS NOW).
- Never call a DAO, `AttentionScheduler`, AlarmManager or notification APIs from Create code.
- Form state is ephemeral (`AgentCreateForm`); created items are durable the moment the
  action returns `Success`. Never buffer several items and "save later"; never keep a draft
  after process death.
- Pickers read the repository only (projects, non-terminal WorkStreams,
  `HierarchyPresentation.rows` for parents). Never read `MockData` for choices.
- Nothing implicit: no auto-focus, no auto `activeTaskId`, new WorkStream = READY, new Task =
  TODO. `WorkStreamMode` is an explicit chip ("I do the work" / "It can continue without me")
  — never inferred from the title, a tool name or keywords.
- Validation is the domain's (`StructureActions`, `DomainError`); the ViewModel only maps
  errors to short copy ("Couldn't create that · Choose a WorkStream or Project", "Give it a
  name first"). Nothing persists on a rejection; the form keeps its context.

## Not yet (do not add without a pass that asks for it)

- The composer text in CREATE runs the deterministic command language (`virlin-agent-text-command`)
  — **Create V1 complete**: `create|add|new project|[human|external] workstream|task|subtask X
  [under|in|to OWNER]`; entity word required, title kept whole, mode never inferred, Project
  optional, task owner Project / WorkStream / Task via `TaskOwnerRef.Any` with typed clarification
  continuing the same pending Create; every create is previewed before executing. No general NLP,
  no LLM, no voice.
- No Stage/Step/Subtask domain types (a "subtask" is a Task with a parent), no bulk plan
  creation, no reminder model, no Capture persistence, no cloud sync.
- Orb and the Agent shell/tabs/composer are untouched.

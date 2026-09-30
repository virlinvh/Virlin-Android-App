# Virlin architecture

Verified against the live workspace: 2026-09-30

## Product boundary

Virlin is a local-first Android human-attention orchestration app. It coordinates Projects,
WorkStreams, recursive Tasks, attention/focus, external work, reminders and captured knowledge.
There is no account, cloud sync or production LLM dependency in the current architecture.

## Runtime dependency flow

```text
Compose screen
  -> screen ViewModel / UI action contract
  -> VirlinActions
  -> domain action implementation
  -> WorkStreamRepository transaction/read API
  -> Room + managed app files
  -> Flow / StateFlow back to UI
```

The UI may observe repository flows, but mutations must go through `VirlinActions` or a domain
action class. Compose must never call a DAO or directly mutate Room.

## Application root

`VirlinGraph` is the application service locator and startup coordinator. It exposes the canonical
repository, actions, clock, IDs and deterministic command engine. Startup uses
`BootstrappingWorkStreamRepository`: Compose can render before Room hydration completes, while
receivers can await `ensureReady()`.

`MainActivity` owns the single Compose host. `VirlinApp` owns the navigation graph, app scaffold,
bottom navigation, Orb, contextual Note launching and compatibility routes.

## Layers

### Domain

- `domain/model`: Projects, WorkStreams, Tasks, captures, execution, attachments, tags, steps and
  Page blocks.
- `domain/action`: the `VirlinActions` facade and cohesive action implementations.
- `domain/repository`: canonical persistence contract, Room adapter boundary and in-memory test
  implementation.
- `domain/command`: deterministic Agent command parsing/resolution/execution.
- `domain/activity`, `progress`, `structure`: derived projections and hierarchy policies.
- `domain/notedoc`: redesigned owner-scoped Note document contract and codec.

### Data

- `data/db`: Room entities, DAOs, mappings, migrations and `RoomWorkStreamRepository`.
- `data/attachment`, `data/voice`, `data/projecticon`: managed file stores.

### UI

- `ui/navigation`: root navigation and bottom bar.
- `ui/screens`: Now, project directory and attention surfaces.
- `ui/hierarchy`: project experience, green task hierarchy, Knowledge, Activity and tags.
- `ui/map`: project mind map, structure editor, add palette and map import/export.
- `ui/page`: task Page block document.
- `ui/todo`, `ui/notes`, `ui/prompt`, `ui/link`, `ui/file`, `ui/voice`: content workspaces.
- `ui/agent`, `ui/orb`: deterministic Agent and Orb surfaces.
- `ui/pulse`, `ui/apps`: Pulse and Apps destinations.

## Canonical invariants

- Room is the durable structured-data source of truth.
- IDs, never titles or list positions, identify and route entities.
- Only one human Focus is active at a time.
- Task hierarchy may be arbitrarily deep and must reject cycles.
- Completion/cancellation are domain states, not UI-only flags.
- Execution inheritance (Human, External, Inherit) is domain behavior and must survive UI changes.
- The green `VirlinStreamTasks` hierarchy is the canonical interactive task interface.
- Task Page blocks reference canonical content; they do not copy content documents.
- New visual Notes use owner-scoped `virlin_notes`; legacy Capture Notes remain compatibility data.
- Capture files live in managed app storage; database rows contain metadata and relative paths.

## Extension rule

New content features should be built as isolated domain/data/UI modules and expose a narrow wiring
contract. Shared roots—navigation, palette, action facade, repository interface, Room database and
Task Page registration—are integration-owner files during parallel development.

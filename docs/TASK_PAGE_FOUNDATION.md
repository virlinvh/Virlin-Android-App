# Task Page foundation (schema v17)

Status: implementation contract for Codex, Claude, and future contributors.

## Product contract

Each mind-map task owns one Page. The Page is a clean, mixed document view of content created
from that task's `Add` palette. Content remains owned by its canonical feature: To-do uses
`task_steps`, Note uses the task-scoped `virlin_notes` document, and Prompt/Link use `captures`
plus their companion payload. A Page block is only an ordered reference; it never copies or
serializes another feature's content.

The mind-map remains structural. Page blocks, thumbnails, and previews are not rendered as map
nodes. `Open Task` and `Page` remain separate actions.

## Persistence and migration

Schema v17 adds `task_page_blocks`:

| Column | Meaning |
| --- | --- |
| `id` | Stable placement id. |
| `taskId` | Page owner. Indexed. |
| `typeKey` | Open string discriminator, not a Room/Kotlin enum. |
| `contentId` | Canonical content id or owner key. |
| `sortOrder` | Explicit mixed-document order. |
| `createdAt`, `updatedAt` | Durable timestamps. |

The `(taskId, typeKey, contentId)` tuple is unique. This makes registration idempotent when an
editor is reopened or a save is retried. Migration 16->17 creates only this table and indices;
it does not rewrite existing content. Existing task-scoped content is projected into a Page by
the action layer when the Page first loads, preserving a deterministic order.

## Stable type-key convention

- `task.todo` - the task's aggregate checklist; `contentId == taskId`.
- `task.note` - the Notes document; `contentId == "task-<taskId>"` (the canonical existing
  `virlin_notes.ownerKey` format, constructed only by `TaskPageTypeKeys.noteOwner`).
- `capture.<lowercase CaptureType>` - any task-scoped capture; `contentId == capture.id`.

Unknown keys must remain readable as an unsupported/fallback block and must never crash or be
deleted. This is the forward-compatibility boundary.

## Extension contract for every future Add item

1. Store the content in its own canonical model/table.
2. Give the creation flow a task context.
3. In the same repository transaction as creation, call the generic Page registration helper
   with a stable `typeKey` and canonical `contentId`. Capture-based types get
   `capture.<type>` automatically from `CaptureActions` when `taskId` is present.
4. Add the feature mapping to every current Page presentation seam in `TaskPageScreen.kt`:
   `TaskPageBlockRegistry.label`, row projection/preview, `iconFor`, and `openBlock`. These are not
   yet one pluggable registry, so parallel feature branches must request this shared wiring from the
   integration owner. Do not add a new Page table or Page-specific copy of the payload.
5. Add tests for idempotent registration, ordering, unknown-key fallback, editor routing, and
   migration. Update `CODEX_CHANGES.md` or `CLAUDE_CHANGES.md` according to authorship.

Following this contract makes newly enabled capture types appear automatically in the Page data
projection; only their visual renderer/editor route is feature-specific.

The current registry decision is to **document and centralize ownership before checkpoint**, not to
perform a speculative production refactor. A future dedicated pass may replace the four seams with
one descriptor registry after existing behavior is covered by focused tests.

## Action boundary

`TaskPageActions` is the only Page mutation surface. It validates task ownership and provides:

- `loadAndReconcile(taskId)` - reads ordered placements and registers legacy/current canonical
  task content that has no placement yet.
- `ensureBlock(...)` - idempotent append used by Add/editor flows.
- `reorder(taskId, orderedIds)` - validates an exact permutation and rewrites dense order.
- `move(blockId, destinationTaskId)` - moves the placement and, for captures, its task context.
- `duplicate(blockId, destinationTaskId)` - creates an independent canonical copy where that is
  safe; unsupported types are rejected rather than silently linked.

All multi-record mutations are one `WorkStreamRepository.transaction`, so observers cannot see
half a move or half a registration.

## UI and navigation

- Mind map `Page` opens `task_page/{taskId}`.
- Normal mode is a document-style list with compact type label, content preview, and a quiet pen.
- The pen opens the canonical To-do, Notes, Prompt, or Link editor.
- Organize mode exposes drag/reorder affordances and per-block Move/Duplicate actions.
- Move/Duplicate uses a task destination picker scoped to the current project.
- Empty Pages explain that content added from the task's `+` palette will appear here.

## Safety and non-goals

- No destructive migration and no fallback-to-destructive Room configuration.
- No Page content on the map canvas.
- No embedded editor state or payload JSON in `task_page_blocks`.
- Deleting canonical content is intentionally outside the first Page foundation; a stale
  placement renders a recoverable unavailable block until a later explicit cleanup policy.
- Duplicate is rejected for a type without a defined deep-copy operation.

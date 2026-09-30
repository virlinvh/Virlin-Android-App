# Frozen contracts (Cursor summary)

“Frozen” = **do not change accidentally**. A deliberate user-requested pass may change one
with tests and status updates. Authoritative detail: `CLAUDE.md`, skills, source.

## Hierarchy & tasks

- Optional **Project** · **WorkStream** · recursive **Task** (`parentTaskId`)
- No Stage / Step / Subtask entity (“subtask” = child Task)
- Projectless WorkStreams allowed; WorkStreams may have no Tasks
- Task: `TODO / IN_PROGRESS / DONE / CANCELLED` — **DONE ≠ CANCELLED**
- Progress from executable leaves only; cancelled excluded from num & den
- **Active Path** derived from `activeTaskId` (+ ancestry) — never a stored mutable path

## Attention

- States: `FOCUS · PROCESSING · CHECK · READY · SNOOZED · BLOCKED · PAUSED · DONE`
- Single Focus; many PROCESSING; PROCESSING never consumes attention / never SNOOZED
- **LEAVE ≠ HAND OFF** (Leave → READY or SNOOZED/`HUMAN_RETURN`; Hand off → PROCESSING)
- Human return ≠ external check ≠ `EXTERNAL_RESULT_READY`
- Check flow: still running / result ready (now|later) / blocked — never steals Focus alone

## Time & persistence

- Timestamps are truth; elapsed derived; no per-second DB writes
- Room is source of truth (schema v2; real migrations only; no destructive fallback)
- Mutations only via **VirlinActions** (UI/Agent/notifications never touch DAOs)
- Scheduler = wake-up only; Room remains authority
- Notifications: validate from Room → VirlinActions (stale → dismiss, no mutation)

## Agent V1 (deterministic)

- Control V1 · Create V1 · Capture V1 complete/frozen for casual edits
- Capture content is **data** — CAPTURE mode never runs the command interpreter
- Capture backends today: NOTE / PROMPT / LINK (File/Image/Voice UI may exist but are disabled)
- Create: Project / WorkStream / Task only; mode never inferred
- Command path: text → interpreter → `VirlinCommand` → resolver → executor → actions
- No LLM / model / network behind interpretation (`DeterministicOnlyTest`)

## Navigation & surfaces

- Roots: **Now / Streams / Pulse / Inbox** (bottom-attached); Projects inside Streams
- Agent: Orb → entry → **one** mode workspace (no mode tabs)
- Pulse is largely **demo/static** — not real analytics

## Orb (current working-tree direction)

- Root-owned overlay (`OrbTravelLayout`); 52dp; above bottom nav; does not scroll with content
- Stitch liquid AGSL + Canvas fallback
- Visible root→Agent **travel removed**; Orb **rides the sheet** while opening
- Invisible / non-interactive Orb must **not** intercept workspace touches
- Do not redesign Orb without an explicit task

## Control layout (current working-tree direction)

- **Fixed top:** back/close · Control identity · Quick Actions · RECENT/SUGGESTED heading
- **Scrollable middle:** suggested cards / task picker only
- **Fixed bottom:** composer
- Do not reintroduce whole-workspace scrolling for Control

## Also protected

- Approved Now structure; split-flap (Now per-digit; Focus Clock grouped)
- Roborazzi goldens / `app/schemas/**` — no rewrite without explicit approval
- MockData is **display bridge**, not domain authority — do not “fix” casually

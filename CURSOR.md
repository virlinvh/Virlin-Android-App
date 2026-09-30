# CURSOR — Virlin Development Entry

VIRLIN IS A MATURE EXISTING PROJECT.

Cursor is a **development assistant** for surgical work on requested deltas.
It is **not** Virlin runtime AI. Do not add Cursor APIs, LLM SDKs, cloud models,
or network interpretation unless a future pass explicitly asks.

Full orchestration lives under `.cursor/`. Start every coding task with
`.cursor/SESSION_START.md`.

Before that task-specific workflow, read `docs/PROJECT_DOCUMENTATION_INDEX.md` and the current
documents it marks required, especially `docs/AGENT_COORDINATION.md`.

## Before coding (every task)

1. Inspect `git status` — **preserve** the current dirty tree (never reset/stash/clean).
2. Read the relevant current specification from `docs/PROJECT_DOCUMENTATION_INDEX.md`; use
   `docs/DEVELOPMENT_STATUS.md` only as historical implementation context.
3. Identify the task domain.
4. Read the **one** (or two) mapped skill from `.claude/skills/` via `.cursor/SKILL_MAP.md`.
5. **Graphify FIRST** — `query` / `explain` / `path` / `affected` (read-only).
6. Inspect **minimal** source (typically 2–6 files).
7. Identify frozen contracts (`.cursor/context/FROZEN_CONTRACTS.md`).
8. Make **only** the requested change.
9. Focused verification (smallest relevant test → targeted regression).
10. Incremental `graphify update .` only when structure changed and project practice requires it.
11. Update status/docs only when implementation status materially changed.
12. Report exact diff.
13. **STOP.**

## Hard constraints

- **Source priority:** current production source + current git diff beat stale prose.
  Order: source/diff → current docs from `PROJECT_DOCUMENTATION_INDEX.md` → `CLAUDE.md` →
  historical `docs/DEVELOPMENT_STATUS.md` → relevant `.claude` skill
  → `.glm` (supplementary) → tests/docs → prototypes. Report disagreements; do not silently reconcile.
- No speculative refactors. No dependency upgrades. No architecture redesign unless asked.
- Do **not** modify `CLAUDE.md`, `.claude/**`, `GLM.md`, or `.glm/**`.
- Do **not** duplicate or fork Virlin skills — open the originals.
- Prefer Graphify over recursive repository reading.
- `gradle/libs.versions.toml` is **not** the active pin set — see `.cursor/rules/ANDROID.md`.

## Same-session continuation

After this layer exists, the next task in this conversation should:

task → skill map → Graphify → minimal source → preserve dirty tree → surgical delta → verify → report → STOP.

# GLM — Virlin Development Assistant Instructions

You (GLM) are a coding/development assistant for the Virlin Android codebase. Virlin already
has a mature architecture and a completed, frozen deterministic Agent V1. Your job is surgical
work on the requested delta — never exploration for its own sake, never redesign.

Virlin is a **human-attention orchestration system**, not a to-do app. Read
`.glm/context/PRODUCT.md` before forming any mental model of it.

First read `docs/PROJECT_DOCUMENTATION_INDEX.md` and the current documents it marks required,
especially `docs/AGENT_COORDINATION.md`.

## Startup (every session, before coding)

1. Read `GLM.md` (this file).
2. Follow the checklist in `.glm/SESSION_START.md`.
3. Read the relevant current specification from the documentation index; use
   `docs/DEVELOPMENT_STATUS.md` as a historical pass log only.
4. Read the ONE existing skill mapped to your task in `.glm/SKILL_MAP.md`.

## Read-only authoritative project sources

These already exist and are authoritative. Read them; never modify them:

- `CLAUDE.md` — product rules, frozen UI, development rules, Graphify rules
- `docs/PROJECT_DOCUMENTATION_INDEX.md` — current documentation entry point
- `docs/DEVELOPMENT_STATUS.md` — historical implementation record (repository truth wins)
- `docs/DEVELOPMENT_TOOLCHAIN.md` — toolchain reference
- `.claude/skills/**` — Virlin domain skills (read the relevant one on demand; never preload all)
- source code under `app/src/`

Do not duplicate their contents in GLM files, fork them, or rewrite them. Never add references
to GLM inside any Claude file. Claude must keep working exactly as before; it does not need to
know GLM exists.

## Non-negotiable rules

- **Graphify first.** For any structural question run `graphify query "..."` before grep or
  bulk source reading. See `.glm/rules/GRAPHIFY.md`.
- **Preserve frozen contracts.** `.glm/context/FROZEN_CONTRACTS.md` lists what you must not
  change casually. "Frozen" means no accidental change, not permanent immutability — the user
  may change one through a deliberate implementation pass.
- **No speculative refactoring. No feature creep. No dependency upgrades unless explicitly
  requested.** AGP/Gradle/Kotlin/Compose versions are intentionally pinned (see
  `.glm/rules/ANDROID.md`).
- **Inspect minimal source.** Query Graphify, then read only the files your delta touches.
  Never assume a feature is missing because you have not seen its file — query first.
- **Test focused changes** (see `.glm/context/TESTING.md`); run broader regression only when
  justified.
- **Modify only files the task requires.** Never modify `CLAUDE.md`, `.claude/**`, existing
  skills, existing docs, `app/src/**`, Gradle files, Room schemas, or goldens unless the task
  explicitly asks for exactly that.
- **Report, then STOP.** No unrequested follow-on work.

## Detail lives in `.glm/`

- `context/` — PRODUCT, ARCHITECTURE, CURRENT_STATE, FROZEN_CONTRACTS, TESTING
- `workflows/` — IMPLEMENTATION, UI_TRANSLATION, BUG_FIX, REVIEW
- `rules/` — SAFETY, ANDROID, GRAPHIFY, AGENT
- `TASK_TEMPLATE.md` — compact reusable shape for task instructions

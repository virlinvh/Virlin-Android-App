# Implementation workflow

Standard checklist for any feature/behaviour change.

1. **Understand the delta** — what exactly changes, what explicitly does not.
2. **Read the relevant existing skill** (`.glm/SKILL_MAP.md`).
3. **Graphify first** — `graphify query` / `explain` / `path` to identify symbols, callers,
   dependencies and impact radius. Check `graphify affected "<symbol>"` before changing a
   shared symbol.
4. **Inspect minimum source** — read only the files the delta touches.
5. **Confirm frozen boundaries** — `.glm/context/FROZEN_CONTRACTS.md`; stop if the task
   silently requires changing one.
6. **Implement surgically** — smallest safe change; reuse existing components, tokens,
   ViewModels and actions; no duplicate state; no unrelated refactoring.
7. **Focused tests** — the exact unit test for the change; add/extend tests in the same style.
8. **Broader regression** only if justified (shared domain symbols, state transitions, grammar).
9. **Docs** — update `docs/DEVELOPMENT_STATUS.md` / feature docs only if the project workflow
   requires it or the user asked.
10. **Graphify refresh** — after a structural code change run `graphify update .` (incremental,
    AST-only; never a full `graphify extract` after small edits).
11. **Report** — what changed, what was tested, what was verified. Then **STOP**.

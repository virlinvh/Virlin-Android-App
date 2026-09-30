# Cursor session start

Complete before coding. Skip nothing that applies.

- [ ] `git status` / `git diff --stat` — understand dirty tree; **never** reset/stash/clean
- [ ] Read relevant `docs/DEVELOPMENT_STATUS.md` (and uncommitted status notes if present)
- [ ] Classify task domain (product / domain / UI / Agent mode / command / QA / …)
- [ ] Open mapped skill(s) from `.cursor/SKILL_MAP.md` → `.claude/skills/<name>/SKILL.md`
- [ ] Graphify FIRST: `query` / `explain` / `path` (/ `affected` if risk)
- [ ] Inspect minimal source (prefer 2–6 files)
- [ ] Identify frozen contracts (`.cursor/context/FROZEN_CONTRACTS.md`)
- [ ] Implement **requested delta only**
- [ ] Focused verification (smallest test → targeted regression; classify env vs product)
- [ ] `graphify update .` only if structural code changed and workflow requires it
- [ ] Update DEVELOPMENT_STATUS / docs only when status materially changed
- [ ] Report exact files/diff → **STOP**

See also: `CURSOR.md`, `.cursor/workflows/IMPLEMENTATION.md`, `.cursor/workflows/GRAPHIFY_FIRST.md`.

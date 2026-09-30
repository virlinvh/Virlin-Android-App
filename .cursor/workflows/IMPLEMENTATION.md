# Implementation workflow

1. Understand the user’s requested **delta** (and only that).
2. Inspect `git status` / dirty tree (`.cursor/context/CURRENT_WORKING_STATE.md`).
3. Classify task type → `.cursor/SKILL_MAP.md`.
4. Read the mapped `.claude/skills/.../SKILL.md` (one or two max).
5. **Graphify first** — see `GRAPHIFY_FIRST.md`.
6. Inspect minimal source (prefer 2–6 files).
7. State expected files to touch before editing.
8. Identify frozen boundaries; stop if the request would silently break one.
9. Implement surgically — smallest safe change; match surrounding style.
10. Focused unit/UI verification (see testing notes in `CURSOR.md` / mobile-qa skill).
11. `assembleDebug` when UI/build risk justifies it — not by default for every tiny doc change.
12. Incremental `graphify update .` if structural code changed.
13. Update `docs/DEVELOPMENT_STATUS.md` only when implementation status materially changes.
14. Report exact files/diff and verification.
15. **STOP** — no unrequested follow-on work.

Never: speculative refactor, dependency upgrade, redesign frozen UI, or runtime AI integration.

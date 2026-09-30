# Source priority (Cursor)

When understanding Virlin, use this order:

1. **Current production source** (`app/src/main/…`)
2. **Current git diff / uncommitted implementation**
3. `docs/DEVELOPMENT_STATUS.md`
4. `CLAUDE.md`
5. Relevant `.claude/skills/<name>/SKILL.md`
6. `GLM.md` / `.glm/**` (supplementary index only)
7. Tests (`app/src/test`, `app/src/androidTest`)
8. Other documentation
9. Prototypes / reference experiments (root `*.py`, `maestro/`, debug lab)

## Conflict rule

If sources disagree:

- **Do not silently choose.**
- Treat **production source + current diff** as strongest evidence of actual behaviour.
- Report the discrepancy in the task report.
- Prefer implementing against what the code does (or the requested deliberate change), not stale prose.

Known examples to watch:

- Orb travel wording in older prose vs ride-the-sheet placement in the working tree
- Roborazzi golden counts in early status sections vs committed screenshot set
- `gradle/libs.versions.toml` vs versions actually pinned in Gradle files

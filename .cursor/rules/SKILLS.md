# Skills rules (Cursor)

Cursor does **not** own a separate Virlin skill library.

The shared canonical skills live at:

`.claude/skills/<skill-name>/SKILL.md`

Use `.cursor/SKILL_MAP.md` to choose which skill(s) to open.

## Rules

- Open **only** relevant skills (usually one; two when domains truly overlap).
- **Never** copy skill bodies into `.cursor/`.
- **Never** edit `.claude/skills/**` for Cursor convenience.
- Claude and GLM must keep using the same skill files unchanged.
- Virlin domain skill beats generic Android guidance for Virlin behaviour.

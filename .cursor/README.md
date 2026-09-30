# `.cursor/` — Cursor development support layer

This directory is a **lightweight orchestration layer** for Cursor sessions on Virlin.

## Why it exists

So Cursor can start fast, work surgically, and avoid re-reading the whole repository —
while reusing the **same** product and engineering contracts already used by Claude and GLM.

## What it is / is not

| It is | It is not |
|---|---|
| Startup checklists, skill routing, workflows | A second architecture specification |
| Pointers into authoritative Virlin context | A fork of Claude skills |
| Cursor-native thin `.mdc` bridges under `rules/` | Application code or build input |
| Protection notes for the current dirty tree | Runtime AI / LLM integration |

## Authoritative sources (do not duplicate)

1. **Production source** under `app/src/` (+ current git diff)
2. `docs/DEVELOPMENT_STATUS.md`
3. `CLAUDE.md`
4. `.claude/skills/**` — shared canonical skill library
5. `GLM.md` / `.glm/**` — supplementary index only (separate assistant layer)
6. Tests and other docs
7. Prototypes / experiments (never treat as runtime truth)

Claude infrastructure remains authoritative for product rules.
Skills are **reused by path**, never copied into `.cursor/`.
GLM is independent; Cursor must not modify Claude or GLM files, and must not add
Cursor references inside them.

All assistants operate on **one** Virlin architecture.

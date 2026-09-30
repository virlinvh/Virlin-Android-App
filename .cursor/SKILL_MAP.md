# Cursor → existing skill map

Cursor does **not** own Virlin skills. Before work in a domain, **read** the original file
under `.claude/skills/<name>/SKILL.md`. Load only what fits — never preload all.
Do not copy, rewrite, or fork these skills into `.cursor/`.

## Virlin skills

| Task type | Read |
|---|---|
| Product / frozen UI / status | `.claude/skills/virlin-project/SKILL.md` |
| Domain models, `VirlinActions`, Room, persistence, WorkStream mutation | `.claude/skills/virlin-data-domain/SKILL.md` |
| Orb / animation / gestures / interaction states | `.claude/skills/virlin-motion-interaction/SKILL.md` |
| Writing or modifying Compose UI | `.claude/skills/virlin-android-compose/SKILL.md` |
| Verify / test / diagnose (incl. screenshots) | `.claude/skills/virlin-mobile-qa/SKILL.md` |
| Agent CONTROL | `.claude/skills/virlin-agent-control/SKILL.md` |
| Agent CREATE | `.claude/skills/virlin-agent-create/SKILL.md` |
| Agent CAPTURE / Inbox / capture migrations | `.claude/skills/virlin-agent-capture/SKILL.md` |
| Typed command contract (`VirlinCommand` → resolver → executor) | `.claude/skills/virlin-agent-command/SKILL.md` |
| Deterministic text grammar / command panel | `.claude/skills/virlin-agent-text-command/SKILL.md` |
| Calendar / time language | `.claude/skills/virlin-agent-time-language/SKILL.md` |

## Official Android / docs skills

| Task type | Read |
|---|---|
| Test setup / harness | `.claude/skills/testing-setup/SKILL.md` |
| adb / device / live UI | `.claude/skills/android-cli/SKILL.md` |
| Window size / adaptive layouts | `.claude/skills/adaptive/SKILL.md` |
| Edge-to-edge / insets | `.claude/skills/edge-to-edge/SKILL.md` |
| External library docs (Context7) | `.claude/skills/find-docs/SKILL.md` |

## Multi-skill (only when the task truly spans)

| Example | Skills |
|---|---|
| Orb UI bug | `virlin-motion-interaction` + `virlin-android-compose` |
| Control UI | `virlin-agent-control` + `virlin-android-compose` |
| Control regression | `virlin-agent-control` + `virlin-mobile-qa` |
| Room / domain change | `virlin-data-domain` + `virlin-mobile-qa` |
| Text command + time phrase | `virlin-agent-text-command` + `virlin-agent-time-language` |

Precedence: Virlin skill for the domain wins over generic Android guidance;
`CLAUDE.md` + current source/diff win over summaries.

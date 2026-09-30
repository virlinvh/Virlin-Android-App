# GLM → existing skill map

GLM does **not** duplicate or copy any skill. Before working in a domain, read the existing
skill under `.claude/skills/<name>/SKILL.md`. Load only the one that fits — never preload all.

| Task type | Read (existing skill) |
|---|---|
| Product / domain rules / frozen UI / status | `virlin-project` |
| Animation, transitions, gestures, Orb interaction states | `virlin-motion-interaction` |
| Writing or modifying Compose code | `virlin-android-compose` |
| Verifying, testing, diagnosing (incl. suspicious screenshots) | `virlin-mobile-qa` |
| Domain models, `VirlinActions`, repositories, persistence, anything that mutates WorkStream state | `virlin-data-domain` |
| Agent CONTROL behaviour / boundaries | `virlin-agent-control` |
| Agent CREATE behaviour | `virlin-agent-create` |
| Agent CAPTURE / Inbox / Room migration discipline | `virlin-agent-capture` |
| Typed command contract (`VirlinCommand` → resolver → executor) | `virlin-agent-command` |
| Deterministic text grammar (`TextCommandInterpreter`, command panel) | `virlin-agent-text-command` |
| Calendar/time language (`TimeExpressionParser`, `TemporalIntent`, policy) | `virlin-agent-time-language` |

## Official Android skills (also in `.claude/skills/`, on demand)

| Task type | Read |
|---|---|
| Setting up or diagnosing tests | `testing-setup` |
| adb / device interaction / live UI inspection | `android-cli` |
| Window size classes / adaptive layouts | `adaptive` |
| Edge-to-edge / insets work | `edge-to-edge` |
| External library documentation lookup | `find-docs` (Context7) |

Precedence: the Virlin skill for the domain always wins over generic guidance; `CLAUDE.md`
rules win over everything.

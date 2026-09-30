# `.glm/` — GLM compatibility layer

`.glm/` is a parallel development-support layer for GLM sessions on the Virlin Android
project. It exists so a GLM session can start fast, work surgically, and avoid re-reading the
whole repository — while the existing Claude infrastructure stays untouched and authoritative.

## Relationship to the existing (Claude) layer

- `CLAUDE.md`, `docs/`, `.claude/skills/**` and the source tree remain the **authoritative**
  project knowledge. `.glm/` files are a **supplementary index** into them, not a second
  specification. Where any `.glm/` file disagrees with the repository, `docs/DEVELOPMENT_STATUS.md`
  and the source win.
- Claude files are never modified for GLM's benefit, and Claude is never told GLM exists.
- Nothing in `.glm/` is build input, application code, or runtime configuration. It is
  documentation only. There is no GLM SDK, API, model or network anywhere in the app.

## What lives here

| Path | Purpose |
|---|---|
| `SESSION_START.md` | The checklist every GLM session follows before coding |
| `SKILL_MAP.md` | Task type → which existing `.claude/skills/` skill to read (never copy them) |
| `TASK_TEMPLATE.md` | Compact reusable template for task instructions |
| `context/PRODUCT.md` | What Virlin is; attention orchestration; UX principles |
| `context/ARCHITECTURE.md` | Domain model, action layer, persistence, scheduling, command pipeline, package map |
| `context/CURRENT_STATE.md` | Which passes are complete/frozen; what is deliberately not implemented |
| `context/FROZEN_CONTRACTS.md` | What GLM must not change casually |
| `context/TESTING.md` | Real test inventory, commands, Roborazzi rules, emulator-instability guidance |
| `workflows/IMPLEMENTATION.md` | Standard implementation checklist |
| `workflows/UI_TRANSLATION.md` | Design-source → Compose translation rules |
| `workflows/BUG_FIX.md` | Minimal root-cause fix workflow |
| `workflows/REVIEW.md` | Audit workflow and issue classification |
| `rules/SAFETY.md` | Hard safety boundaries |
| `rules/ANDROID.md` | Android/Compose coding rules and the pinned stack |
| `rules/GRAPHIFY.md` | Graph-first navigation and graph maintenance |
| `rules/AGENT.md` | Control / Create / Capture contracts; GLM is not a runtime provider |

## Maintenance

Update a `.glm/` file only when GLM-specific context needs adjustment (e.g. after a deliberate
pass changes a frozen contract). Keep files compact — they are loaded every session. Do not
turn them into an encyclopedia; link to the authoritative source instead.

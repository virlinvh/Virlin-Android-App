# Agent rules (Cursor)

Virlin Agent = one in-window sheet from the Orb: entry → **Control | Create | Capture**.

| Mode | Role |
|---|---|
| **CONTROL** | Act on existing work (focus, leave, hand off, check, block, complete, …) |
| **CREATE** | Create Project / WorkStream / Task structure |
| **CAPTURE** | Store information (NOTE / PROMPT / LINK); organize later |

## Contracts

- Capture raw input is **data** — never execute Control/Create commands from CAPTURE mode.
- Runtime interpretation is **deterministic only** (interpreter → command → resolver → executor → `VirlinActions`).
- No LLM, local model, API key, or network behind the parser unless a future pass explicitly adds it.
- Create: no Stage/Step entities; WorkStream mode never inferred.
- Control: ambiguity → clarification; destructive → confirmation.

## Cursor’s role

Cursor is a **development** assistant editing the Android project.

Cursor is **not** Virlin’s runtime agent.

Do not add Cursor API / LLM SDK / cloud AI into the app unless explicitly requested.

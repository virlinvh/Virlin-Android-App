# Graphify rules (Cursor)

## Core

**Graphify first → minimal source second.**

Graph: `graphify-out/graph.json`. Do not reinstall Graphify. Do not invent another graph system.

## Allowed read operations (confirmed installed)

- `graphify query "<question>"`
- `graphify explain "<X>"`
- `graphify path "<A>" "<B>"`
- `graphify affected "<X>"`

## After implementation

- Prefer `graphify update .` (incremental, AST-only) when structural code changed.
- Do **not** run `extract`, `watch`, `cluster-only`, `label`, or full rebuilds for ordinary surgical edits.
- Never invent commands. If unsure whether a command mutates graph state, do not run it.

## Efficiency

Do not recursively browse the repo “to orient”. Use skill + Graphify + 2–6 files.

Graphify locates symbols; **source** proves behaviour.

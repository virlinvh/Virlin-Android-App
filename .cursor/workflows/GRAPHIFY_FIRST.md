# Graphify-first workflow

Graph exists at `graphify-out/graph.json` (~3000+ nodes). Prefer Graphify over recursive repo reads.

## Read-only discovery (confirmed)

```bash
graphify query "<question>"
graphify explain "<symbol or concept>"
graphify path "<A>" "<B>"
graphify affected "<X>"
```

Use `--budget` / narrower queries when output truncates. Undirected path if directed path fails.

## Strategy

| Kind | Approach |
|---|---|
| UI | `query` screen/component → `explain` composable → callers → 2–6 source files |
| Domain | `query` action/model → `explain` → `path` UI→Action→Repository → relevant files |
| Bug | `query` failing symbol → `path` callers → ownership → minimal reproduction surface |
| Risky change | `affected "<symbol>"` if needed |

## After code changes

- Prefer incremental: `graphify update .`
- Do **not** run `extract`, `watch`, `cluster-only`, `label`, or full rebuilds for ordinary surgical edits
- Never invent Graphify syntax — if unsure a command mutates, don’t run it until confirmed

## Token efficiency

Bad: open dozens of Kotlin files “to get oriented”.  
Good: skill → Graphify → 2–6 files → implement.

**Graphify points to code. Source proves behaviour.** Never treat graph output as authoritative implementation detail without reading the file.

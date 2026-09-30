# Graphify rules

Graphify (v0.9.57, `graphify-out/`) is the project's code-knowledge graph. It is a **primary
tool**: prefer it over grep-heavy or manual scanning.

## Workflow

1. **Query before reading.** For any structural question:

   ```bash
   graphify query "where is the split-flap timer rendered"   # scoped subgraph for a question
   graphify explain "SplitFlapTimer"                          # focused concept
   graphify path "TextCommandInterpreter" "VirlinActions"     # relationship between two things
   graphify affected "ProgressCalculator"                     # impact radius before edits
   ```

   (`god-nodes` exists for broad architecture review.) These return a scoped subgraph —
   usually far smaller than `GRAPH_REPORT.md` or raw grep output.

2. **Identify** the relevant symbols, files, callers, dependencies and impact radius.
3. **Read only the relevant source** — the graph is navigation, never implementation truth.
4. Use Grep/Glob/broad exploration only when Graphify is insufficient or stale.
5. **After a structural code change:** `graphify update .` — incremental, AST-only, no API
   cost. Do NOT run a full `graphify extract` after small edits.

## Facts about this installation

- Output lives in `graphify-out/` (`graph.json`, `GRAPH_REPORT.md`, `manifest.json`, dated
  snapshot dirs). **`graphify-out/wiki/` does not currently exist** — ignore that step of
  generic Graphify advice until it does.
- `.graphifyignore` excludes build output/IDE state/binaries; `app/src/`, `docs/`,
  `CLAUDE.md`, `.claude/` are indexed. Repo-root Python prototype files are indexed too —
  they are design artifacts, not app code.
- Advisory PreToolUse hooks in `.claude/settings.json` (run by Claude) nudge graph-first
  search/read behaviour; do not modify them.
- Never modify Graphify configuration, never create a second graph, never invent commands —
  the commands above are the ones documented in `CLAUDE.md` Rule 9/10 and
  `docs/DEVELOPMENT_TOOLCHAIN.md`.

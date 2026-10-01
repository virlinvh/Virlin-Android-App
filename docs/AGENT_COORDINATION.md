# Parallel-agent coordination

Status: baseline policy for Codex, Claude, Cursor and Antigravity/other coding agents.

## Current safety warning

The shared baseline has been checkpointed and feature worktrees exist. Agents must branch from the
explicit combined commit named in their task, not from an older `integration` or `main` tip by
assumption. Image work remains isolated until its emulator audit is accepted.

## Required topology after checkpoint

```text
integration                 verified combined branch
codex/<feature>             Codex worktree
claude/<feature>            Claude worktree
cursor/<review-or-feature>  Cursor worktree
antigravity/<prototype>     design/prototype worktree
```

Agents must not perform parallel feature implementation inside the same physical worktree.

## Ownership model

Feature agents own their isolated module, tests, feature specification and their own change log.
The integration owner exclusively edits shared roots while parallel branches are active:

- `VirlinApp.kt` and bottom navigation
- `MapAddPalette.kt` and `ProjectMapScreen.kt`
- `VirlinActions.kt` / `DefaultVirlinActions.kt`
- repository interfaces and production/in-memory implementations
- Room entities, DAOs, database version and migrations
- Android manifest and Gradle files
- `TaskPageScreen.kt`, `TaskPageTypeKeys` and Page reconciliation
- `app/src/test/screenshots/` committed golden baselines

The Page currently has a small `TaskPageBlockRegistry` for labels plus separate type-specific
branches for row projection, icons and editor routing. Until those seams are deliberately unified,
all four are integration-owner territory. Palette work also includes `isSupported`, row pairing and
icon mapping—not only the enum.

Feature branches request these edits through `docs/handoffs/<FEATURE>_WIRING_REQUEST.md`.

## Portable agent tooling

- `.cursor/` and `.glm/` are project onboarding/configuration assets and must be committed with the
  baseline because `CURSOR.md` and `GLM.md` require them on a fresh clone.
- `maestro/` contains shared emulator smoke flows and is committed project test infrastructure.
- `graphify-out/` is generated and ignored. Refresh it locally with `graphify update .`; never add
  the generated graph to a checkpoint.
- `.mcp.json` is machine-local and ignored because it can contain local paths or connector details.

## Start-of-task protocol

Every agent must:

1. Read `PROJECT_DOCUMENTATION_INDEX.md` and all documents it marks required.
2. Inspect `git status`, current branch and worktree path.
3. Read all agent change logs since its branch point.
4. State its feature, owned paths, shared files it will not edit and migration reservation.
5. Verify current code before trusting a chat prompt or memory.

## End-of-task protocol

Every agent must provide and record:

- Summary and user-visible behavior.
- Exact files changed.
- Contracts/data/routes introduced.
- Compatibility and migration impact.
- Tests run with exact results.
- Known limitations and follow-up work.
- Shared wiring request.
- Commit hash once committed.

Codex appends only `CODEX_CHANGES.md`; Claude appends only `CLAUDE_CHANGES.md`. Other agents use
their own logs. No agent rewrites another agent's entries.

## Conflict protocol

- Stop if another agent has uncommitted changes in an owned file.
- Never solve conflicts by taking “ours” or “theirs” wholesale.
- Never renumber a Room migration independently.
- Never delete compatibility data/UI without a tested migration.
- Merge one feature branch at a time, compile after each, then run combined tests.

## Documentation protocol

Every feature must have one authoritative specification under `docs/`. Update it in the same commit
as architectural behavior. Change logs report history; specifications describe the current contract.

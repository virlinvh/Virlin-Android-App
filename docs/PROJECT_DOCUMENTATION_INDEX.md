# Virlin project documentation index

Verified against the live workspace: 2026-09-30

This is the starting point for every coding agent. The live workspace currently contains major
uncommitted and untracked application work beyond Git commit `1e6d821`; agents must not assume the
last commit represents the running app.

## Token-efficient code discovery

Use Graphify before broad source searches. The local graph is generated in the ignored
`graphify-out/` directory and is a discovery aid, not an architectural authority.

1. Start with an exact symbol or owned file from `FEATURE_REGISTRY.md` or the relevant feature
   contract, for example `graphify query "TaskPageActions TaskPageScreen TaskPageTypeKeys" --budget 1800`.
2. Avoid broad terms such as `Page`, `Task`, or `Block` by themselves; they collide across several
   Virlin subsystems and can exhaust the result budget.
3. Confirm important Graphify results with a narrow `rg` search and direct reads of the identified
   files. Code remains the final source of truth.
4. After source changes, run `graphify update .`. This is an incremental, AST-only refresh and does
   not require an API key. Do not run LLM relabelling merely to refresh code symbols.
5. If an expected symbol is missing, compare source and graph timestamps, refresh once, then fall
   back to targeted `rg`; never compensate by recursively reading the whole repository.

The 2026-09-30 refresh contains `TaskPageActions`, `TaskPageScreen`, and `TaskPageTypeKeys`, the
shared extension foundation needed by future Plus-menu features.

## Read first

1. `ARCHITECTURE.md` — system boundaries and dependency direction.
2. `DATA_AND_STORAGE.md` — Room schema, repositories, managed files and migration rules.
3. `NAVIGATION_AND_FEATURES.md` — routes, canonical screens, compatibility routes and feature state.
4. `TESTING_AND_RELEASE.md` — build, test and emulator expectations.
5. `AGENT_COORDINATION.md` — parallel-work rules, ownership and handoff protocol.
6. `FEATURE_REGISTRY.md` — feature owners and current integration state.
7. `DATABASE_MIGRATION_QUEUE.md` — the only place schema versions may be reserved.

8. `GOLDEN_BASELINE_REVIEW.md` — current screenshot mismatch classification and approval gate.

## Foundational feature contracts

- `TASK_PAGE_FOUNDATION.md`
- `TASK_HIERARCHY_CLEANUP.md`
- `NOTE_SYSTEM_CLEANUP.md`
- `PDF_FEATURE_FOUNDATION.md`
- `PROMPT_FEATURE.md`
- `LINK_FEATURE.md`
- `FILE_ATTACHMENT_FEATURE.md`
- `VOICE_FEATURE.md`
- `PDF_WORKSPACE_FEATURE.md` — task PDF specialization and capability boundaries
- `ATTACHMENT_WORKSPACE_FEATURE.md` — universal task file space and preview matrix
- `AUDIO_FEATURE.md` — Audio v1 (record-only) and the shared Task Page routing correction
- `AUDIO_FEATURE_AUDIT.md` — the audit behind the Audio v1 decision
- `ATTACHMENT_WORKSPACE_FEATURE.md` — universal task-scoped file space and viewer routing
- `IMAGE_WORKSPACE_FEATURE.md` — task-scoped image library and non-destructive raster editor

These are normative for their feature boundaries. Future feature documents must link back to the
relevant contract rather than restating it differently.

## Historical and operational references

- `NON_IMAGE_PR_READINESS_AUDIT.md` — verified combined baseline and final Image integration gate.
- `IMAGE_EMULATOR_AUDIT.md` — the reproduced recycled-bitmap crash, its root cause and its fix.
- `FINAL_CONTENT_WORKSPACES_INTEGRATION.md` — the integrated PDF/Audio/Attachment/Image baseline,
  its conflict resolutions, verification and emulator acceptance matrix.
- `DEVELOPMENT_STATUS.md` — detailed historical pass log; useful, but its 2026-09-18 header means
  it is not the sole authority for features added afterward.
- `DEVELOPMENT_TOOLCHAIN.md` — tooling and screenshot practices.
- `REFERENCES.md` — external/design references.
- `README.md` — product overview and basic commands.
- `CLAUDE.md`, `CURSOR.md`, `GLM.md` — tool-specific instructions; architecture claims in these
  files must agree with the documents above.
- `CODEX_CHANGES.md`, `CLAUDE_CHANGES.md` — append-only agent work logs, not architecture specs.

## Documentation rule

Code is the final source of truth. If code and documentation disagree, stop feature work, record the
conflict, verify intended behavior, and update the authoritative document in the same change as the
fix. Never make a hidden architecture decision only in chat or an agent memory store.

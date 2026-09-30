# Safety rules (Cursor)

- Preserve the dirty working tree. Never `reset` / `restore` / `checkout` / `clean` / `stash` to “tidy up”.
- No force push. No automatic commits unless the user explicitly asks.
- Never skip hooks or amend unless the user’s commit rules allow it.
- Do not expose secrets (tokens, keys, local.properties credentials).
- No dependency / AGP / Kotlin / Compose upgrades unless explicitly requested.
- Do not delete tests, Roborazzi goldens, Room schemas, or docs to clear failures.
- No Room `fallbackToDestructiveMigration`. Schema changes need real migrations + tests.
- No fake backends (Capture File/Image/Voice, Pulse analytics, etc.).
- No speculative refactors or drive-by cleanups.
- No silent semantic changes to frozen contracts.
- Do **not** modify `CLAUDE.md`, `.claude/**`, `GLM.md`, or `.glm/**`.
- Do not add runtime Cursor/LLM/cloud AI into the app unless a pass explicitly asks.
- Prototypes (root `*.py`, `maestro/`, debug Orb lab) are not production runtime.

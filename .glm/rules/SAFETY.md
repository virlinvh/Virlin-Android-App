# Safety rules (hard boundaries)

- **No existing Claude infrastructure modification** — `CLAUDE.md`, `.claude/**`, existing
  skills, Claude rules/workflows stay untouched; never reference GLM inside them.
- **No secret exposure** — no API keys, tokens or credentials in code, docs, logs or commits;
  `local.properties` is machine-local and excluded from Graphify indexing.
- **No destructive git** — no force push, no history rewrite, no `reset --hard` on user work,
  no deleting branches/tags.
- **No arbitrary schema migration** — every Room schema change ships a real exported schema +
  a proven migration; `fallbackToDestructiveMigration` must never be introduced.
- **No dependency upgrades unless explicitly requested** — versions are intentionally pinned
  (AGP 8.5.1 / Gradle 8.9 / Kotlin 2.0.0 / Compose BOM 2024.06.00). Do not "modernize" the
  build; do not start using the unused `gradle/libs.versions.toml` catalog as a reason to bump.
- **No deleting docs/tests/goldens/schema files** — Roborazzi goldens are committed on
  purpose; re-record only with explicit user approval of the diff.
- **No fake backend behaviour** — never simulate success for an unimplemented capability
  (precedent: disabled Capture File/Image/Voice cards).
- **No silent semantic changes** — DONE vs CANCELLED, LEAVE vs HAND OFF, check-vs-reminder,
  single-Focus, and command resolution order are semantics; changing any of them silently is
  a violation even if the diff is small.
- **No GLM in the runtime** — no GLM/Zhipu SDK, model client, HTTP inference layer, API key
  or runtime provider added to the app. GLM is a development assistant only.
- **Modify only what the task requires** — report anything else discovered (see
  `.glm/workflows/REVIEW.md`) instead of fixing it unasked.

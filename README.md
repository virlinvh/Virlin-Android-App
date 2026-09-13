# Virlin

Virlin is a native Android **human-attention orchestration system** for people who run many
simultaneous work streams. External tools and processes can work in parallel; human attention
is serial. Virlin decides what needs a human *now* while preserving the context of everything
else — it is not a conventional task manager.

**Status:** early, frontend-first development. Room is the local source of truth; the Now UI,
the Agent shell and the deterministic Agent V1 (Control · Create · Capture) are implemented.
No cloud, accounts, sync or AI/LLM features exist.

## Main areas

| Area | What it is |
|---|---|
| **Now** | The primary attention surface: Current Focus (split-flap focus timer), Needs You, Working For You, When You're Free. |
| **Streams** | Projects → WorkStreams → recursive Tasks; progress and the active task are derived from the domain. |
| **Pulse** | Timeline / activity view. |
| **Inbox** | The durable Capture Inbox (notes, prompts, links) with a live badge. |
| **Agent** | One in-window sheet opened by the Virlin Orb: an entry selector, then **Control** (act on existing work), **Create** (Project / WorkStream / Task) or **Capture** (raw notes, prompts, links). Text commands use a deterministic grammar — no model, no network. |

## Architecture

`Room → Flow → Repository → Domain (VirlinActions) → ViewModel → StateFlow → Compose`

- All WorkStream state changes go through the `VirlinActions` facade, which validates transitions
  and enforces the single-Focus invariant. UI never touches DAOs.
- The Agent's typed command contract: `VirlinCommand → CommandResolver → CommandExecutor → VirlinActions`,
  with clarification / confirmation as values; composer text is parsed by `TextCommandInterpreter`.
- Timed returns and checks are persisted and delivered through AlarmManager + notifications;
  Room remains the truth.

## Tech stack

Kotlin · Jetpack Compose · Material 3 · Navigation Compose · ViewModel · Coroutines / StateFlow ·
Room · WorkManager / AlarmManager · Roborazzi (golden screenshots) · Compose UI tests.

Target device: Pixel 8 / Android API 35.

## Build & run

```bash
gradlew.bat assembleDebug
```

```bash
gradlew.bat testDebugUnitTest
```

```bash
gradlew.bat verifyRoborazziDebug
```

Instrumented tests: `gradlew.bat connectedDebugAndroidTest` with a Pixel 8 / API 35 emulator.
`local.properties` (SDK path) is machine-local and not committed.

## Project documentation

- `CLAUDE.md` — product rules, frozen UI, architecture and development rules.
- `docs/DEVELOPMENT_STATUS.md` — pass-by-pass development status (current state of every feature).
- `docs/DEVELOPMENT_TOOLCHAIN.md` — tooling, testing and golden-screenshot rules.
- `.claude/skills/` — project skills used during development.

Repository: https://github.com/virlinvh/Virlin-Android-App

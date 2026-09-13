# Virlin Development Toolchain

Practical reference for the AI-assisted Android development toolchain in this repository.
Set up 2026-09-11. No secrets belong in this file.

Project baseline (do **not** upgrade to satisfy tooling): AGP 8.5.1, Gradle 8.9,
Kotlin 2.0.0, Compose BOM 2024.06.00, compileSdk 34, minSdk 26, targetSdk 34, Java 17.

---

## Graphify

**Purpose** — internal code structure: where a symbol lives, what calls it, what depends on it.

**Scope / status** — project-scoped, INSTALLED / VERIFIED. `graphify` 0.9.57 (installed with
`uv tool install graphifyy`). Advisory PreToolUse hooks in `.claude/settings.json` (not strict).
Exclusions in `.graphifyignore`; `app/src/`, `docs/`, `.claude/`, `CLAUDE.md` are indexed.

```bash
graphify query "where is the split-flap timer rendered"
```

```bash
graphify explain "SplitFlapTimer"
```

```bash
graphify update .
```

`update` is incremental and AST-only — no API key, no LLM cost. Run it after code changes.
Other commands: `path "<A>" "<B>"`, `affected "<symbol>"`, `god-nodes`.

---

## Google Android Skills

**Purpose** — current official Google guidance for specialized Android workflows.

**Scope / status** — project-scoped in `.claude/skills/`, INSTALLED / VERIFIED.
Installed via the official Android CLI (`Google.AndroidCLI` 1.0.16261425, winget).

Installed on purpose (only what Virlin needs today):

| Skill | Use for |
|---|---|
| `testing-setup` | Test infrastructure, Compose/UI testing |
| `android-cli` | Driving the emulator: layout inspection, screenshots |
| `adaptive` | Window sizes / layout adaptation (e.g. landscape Focus Clock) |
| `edge-to-edge` | System bars, insets, cutouts |

```bash
android skills list
```

```bash
android skills add <skill> --agent=claude-code --project=.
```

**Deliberately NOT installed** — `navigation-3` (Virlin uses Navigation Compose 2.7.7; that
skill targets Navigation 3 and would push an unwanted migration), plus `android-profiler`,
`r8-analyzer`, `android-intent-security` (install when that specific work starts).

**Usage rule** — load the one relevant skill for the task at hand. Never load all of them;
the goal is high expertise at low context cost.

---

## Virlin focused skills (on-demand)

The monolithic `virlin-project` skill is retained for product/domain/frozen-UI rules. Three
focused skills split out the cross-phase knowledge so a task loads only what it needs:

| Skill | Load when | Do not load for |
|---|---|---|
| `virlin-motion-interaction` | Animation, transitions, gestures, Orb interaction states, split-flap behaviour | Layout, data, testing, build |
| `virlin-android-compose` | Writing or modifying Virlin Compose code | Pure motion design, QA, product questions |
| `virlin-mobile-qa` | Verifying, testing, diagnosing; suspicious screenshots | Designing or writing feature code |

**Never preload all skills.** Selection rule is CLAUDE.md Rule 11.

Future skills, not yet needed: Accessibility, Performance, Data/Room, Notifications /
Background Work, Security, Agent Action Layer.

---

## Design tokens

`app/src/main/java/com/virlin/app/ui/theme/VirlinTokens.kt` — `VirlinColors`, `VirlinSpacing`,
`VirlinShapes`, `VirlinMotion`.

**Foundation only; nothing migrated.** Values are transcribed exactly from the rendered UI and
no call site changed, so appearance is provably unaffected. New code should reference tokens;
frozen surfaces must be migrated one at a time, with approval, verifying goldens after each
step. The file documents the `Color.kt`-vs-`NowScreen.kt` shadowing trap in detail.

---

## Android CLI (live device)

**Purpose** — inspect and drive the emulator natively on Windows. This is the working
substitute for Maestro while Maestro is blocked.

```bash
android layout
```

Returns the full accessibility tree as JSON — class, text, `content-desc`, bounds, center
and interactions. Use it to assert UI state and to find tap coordinates.

Other useful commands: `android emulator`, `android screen`, `android run`, `android info`,
`android docs`.

---

## Context7

**Purpose** — current external library/API documentation.

**Mode** — CLI + Skills (not MCP), chosen for lower persistent context overhead and
on-demand retrieval.

**Status — INSTALLED / VERIFIED.** Authenticated (`ctx7 whoami` → "Logged in"), with the
`find-docs` skill at `.claude/skills/find-docs` and a usage rule at `.claude/rules/context7.md`,
both project-scoped. Retrieval verified end-to-end against `/android/compose-samples`.

```bash
npx ctx7 library roborazzi
```

```bash
npx ctx7 docs /takahirom/roborazzi "gradle setup and task names"
```

Never put a Context7 API key in source, committed config, `CLAUDE.md` or docs.

**When to use** — external libraries only: Compose/Material APIs, Navigation, Room, Hilt,
WorkManager, testing frameworks, Roborazzi, Maestro. **Not** for Virlin's own code,
symbols, product requirements or design decisions — that is Graphify and the project docs.

---

## Maestro

**Status — DEFERRED / BLOCKED. Manual action required.**

Maestro's CLI has no native Windows build and no winget package; it requires WSL2 with a
real Linux distribution. This machine has WSL2 enabled but only Docker Desktop's internal
utility distro, which is not usable for Maestro. Maestro MCP depends on the CLI and is
therefore also deferred.

To enable it (user action):

1. `wsl --install -d Ubuntu` (may require a reboot)
2. In Ubuntu: `curl -Ls "https://get.maestro.mobile.dev" | bash`
3. Point Maestro at the Windows adb server: `export ADB_SERVER_SOCKET=tcp:127.0.0.1:5037`
4. Verify: `maestro --version` then `maestro test maestro/smoke/launch-app.yaml`
5. Claude Code must be restarted to pick up any new MCP server.

Flows are already authored in `maestro/` — see `maestro/README.md`. Until then, use
`android layout` for live device inspection.

---

## Roborazzi (visual regression)

**Status — CONFIGURED / VERIFIED.** Plugin `io.github.takahirom.roborazzi` 1.26.0,
Robolectric 4.12.2, test-only (`testImplementation`) — no production or runtime impact.

```bash
gradlew.bat recordRoborazziDebug
```

```bash
gradlew.bat verifyRoborazziDebug
```

```bash
gradlew.bat compareRoborazziDebug
```

Golden baselines: `app/src/test/screenshots/` — **committed on purpose**; they are the
protection. Per-run output under `build/outputs/roborazzi/` is git-ignored.

**Two complementary goldens:**

| Golden | Protects | Does NOT cover |
|---|---|---|
| `now_screen.png` | Now content: header, Current Focus, timer, Needs You, Working For You | bottom nav, Orb |
| `app_scaffold.png` | Scaffold geometry: bottom navigation, Orb bounds/position, surrounding layout | the Orb's liquid shader |

The scaffold golden exists specifically to protect the geometry **around the Orb** before the
Orb interaction work begins. Neither golden replaces the other.

**Determinism** — `MockData` seeds fixed values in its initializer and `MockTimerEngine` is
only started by `MainActivity`, so rendering `NowScreen` directly gives a frozen clock
(32:35). The test also sets `mainClock.autoAdvance = false` to pin infinite transitions.

**Known limitation — the Orb's shader cannot render under Robolectric.** Robolectric's SkSL
validator rejects the production AGSL program (`'main' parameters must be (float2, ...)`), so
`RuntimeShader` construction returns null under test and the Orb falls back to flat Canvas
geometry. The scaffold golden therefore protects the Orb's **placement and bounds**, not its
**material**. The Now golden is unaffected — the Orb is not part of `NowScreen`.

This is accepted, not worked around. The shader source is untouched; only construction
catches `IllegalArgumentException` — the exception hwui's JNI raises for an AGSL/SkSL compile
failure (verified against the API 34 framework source and observed verbatim under
Robolectric's real-native shadow) — so a rejecting validator or GPU driver degrades to the
fallback the code already expected instead of killing the composition. The catch is
deliberately narrow: JVM/system errors still propagate. A single `Log.d` records the fallback. On Pixel 8 API 35
the shader compiles and the full liquid material renders — verified visually.

**Do not modify or simplify the production Orb shader to make screenshot testing easier.**

### GOLDEN SCREENSHOT RULE — read before re-recording

When `verifyRoborazziDebug` fails:

1. Inspect the diff.
2. Decide whether the change was intentional.
3. If accidental — fix the regression.
4. Update the golden **only** when the user explicitly approves that visual change.

Never let "test failed → re-record" become routine. Do not run `recordRoborazziDebug` to
clear a failure.

---

## Compose UI + accessibility tests

**Status — IMPLEMENTED (UI) / PARTIAL (accessibility).**

```bash
gradlew.bat connectedDebugAndroidTest
```

`app/src/androidTest/.../VirlinUiTest.kt` — 4 tests, passing on Pixel_8(AVD) API 35:
Now shows the focus stream; bottom navigation present; the split-flap timer is one merged
accessible element; the Orb has stable semantics and a ≥48dp touch target.

Selectors are semantic (text / testTag / contentDescription), never coordinates.

**Gotcha** — the Now screen runs `rememberInfiniteTransition` animations, so the Compose
test clock never goes idle. Tests set `mainClock.autoAdvance = false`; without it every
assertion times out.

**Accessibility limitation** — Compose's built-in `enableAccessibilityChecks()` landed in
Compose 1.7. Virlin is on Compose BOM 2024.06.00 (1.6.8), and upgrading is out of scope.
Current coverage is therefore semantics-based assertions (labels, merged nodes, touch-target
size). Full automated a11y checks are deferred until the Compose BOM is upgraded for
independent reasons.

---

## Tool decision matrix

| Question | Tool |
|---|---|
| Where is this Virlin code / what depends on it? | Graphify |
| What is the current external API/library usage? | Context7 |
| What does Google recommend for this Android workflow? | The relevant Android Skill |
| Does the UI actually work on a device? | `android layout` (Maestro once unblocked) |
| Did a frozen UI change visually? | Roborazzi |
| Does it build? | Gradle |
| Is the final UI aesthetically right? | Pixel 8 emulator + user approval |

---

## Token-efficient workflow

**Before implementing** — read lean project knowledge; query Graphify for structure; load
only the relevant Android skill; query Context7 only for external API docs; read only the
exact files needed.

**Implement** — surgical change, no unrelated refactor.

**Verify** — build; run targeted tests; check live behaviour with `android layout` when UI
behaviour changed; run Roborazzi when a frozen visual surface may be affected.

**Sync** — update `docs/DEVELOPMENT_STATUS.md`; run `graphify update .`.

Do not rediscover the whole repository each session.

---

## Evaluated / deferred tools

| Tool | Why deferred |
|---|---|
| Serena MCP | Overlaps Graphify. Keep ONE navigation system until Graphify is proven insufficient |
| Repomix | Useful for external audits/snapshots only; Claude already has direct repo access + Graphify |
| Aider (repo map) | Graphify already fills this role; no reason to add a second coding agent |
| Tinder StateMachine | Runtime dependency. The Orb's states fit a sealed interface + StateFlow more cleanly |
| Cash App Molecule | Runtime dependency; Virlin's StateFlow direction already covers this |
| Community Android skill packs (`olafhirsch/claude-android`, `rcosteira79/android-skills`) | Would compete with official Google guidance and waste context |
| `rinkuniks/android-claude-skill` | Possible periodic *auditor*; do not load a large general Android rulebook into every task |
| Compose HotSwan | Conditional — needs newer Kotlin/AGP. **Do not upgrade Virlin just to install it** |
| Slack Compose Lints | Useful once the codebase grows; adds build config churn now |
| Compose Preview Screenshot Testing | Official but experimental; would need AGP/Kotlin changes. Roborazzi chosen instead — see `REFERENCES.md` |
| Baseline Profiles / `android-profiler` skill | Profile *after* the Orb interaction exists, not before |
| Generic UI component library | Would erase Virlin's custom identity — deliberate rejection |
| Third-party "liquid orb"/metaball repos | Current Orb renderer is approved; risk of artifacts and aesthetic drift |

Reference material (read, don't install) is indexed in **`docs/REFERENCES.md`** — including
`SharedTransitionLayout` for the Orb→Agent transition, Now in Android, official Compose
samples, Material 3, and Compose animation references.

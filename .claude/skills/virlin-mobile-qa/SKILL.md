---
name: virlin-mobile-qa
description: Virlin verification workflow — build checks, Pixel 8 API 35 validation, Roborazzi golden screenshots and their safety rules, Compose instrumented tests, accessibility semantics checks, emulator diagnostics, and how to tell a screenshot artifact from a real visual regression. Load ONLY when verifying, testing, or diagnosing Virlin. Do not load while designing or writing feature code.
---

# Virlin — QA & Verification

Load on demand. Implementation rules live in `virlin-android-compose`.

## Verification ladder

```bash
gradlew.bat assembleDebug
```

```bash
gradlew.bat testDebugUnitTest
```

```bash
gradlew.bat verifyRoborazziDebug
```

```bash
gradlew.bat connectedDebugAndroidTest
```

Then install and look at it on Pixel 8 API 35. A build that compiles is not a verified change.

## Roborazzi goldens

Two baselines in `app/src/test/screenshots/`, both committed on purpose:

| Golden | Protects | Does NOT cover |
|---|---|---|
| `now_screen.png` | Now content: header, Current Focus, timer, Needs You, Working For You | bottom nav, Orb |
| `app_scaffold.png` | Scaffold geometry: bottom nav, Orb bounds/position, surrounding layout | the Orb's liquid shader |

**Shader limitation (expected, documented, acceptable):** Robolectric's SkSL validator
rejects the production AGSL program, so `RuntimeShader` construction returns null under test
and the Orb falls back to flat Canvas geometry. The scaffold golden therefore protects the
Orb's *placement*, not its *material*. Validate the real Orb material visually on Pixel 8.
**Never modify or simplify the production shader to make screenshots easier.**

### GOLDEN SAFETY RULE

When `verifyRoborazziDebug` fails:

1. Inspect the diff.
2. Decide whether the change was intentional.
3. If accidental — fix the regression.
4. Re-record **only** when the user has explicitly approved that visual change.

Never let "test failed → re-record" become routine. `recordRoborazziDebug` is not a way to
make a failure go away. Also: actually *look* at a newly recorded golden — a passing test
proves nothing about whether the image is correct.

## Determinism

`MockData` seeds fixed values in its initializer, and `MockTimerEngine` only starts from
`MainActivity`. Rendering a screen directly in a test therefore gives a frozen clock (32:35).
Always set `mainClock.autoAdvance = false` so infinite transitions pin to frame 0.

## Instrumented tests

`app/src/androidTest/.../VirlinUiTest.kt`. Selectors are semantic — text, testTag,
contentDescription — never pixel coordinates.

**Gotcha:** Virlin's screens run `rememberInfiniteTransition`, so the Compose test clock
never reports idle. Without `mainClock.autoAdvance = false` in `@Before`, every assertion
times out. This is configuration, not flakiness.

Note `connectedDebugAndroidTest` **uninstalls the app** when it finishes — reinstall before
doing manual device checks.

**Two clocks.** ViewModel transients (`viewModelScope`) run on the REAL main looper; Compose
animations and recomposition run on the Compose test clock. `mainClock.advanceTimeBy` alone
does not move the ViewModel, and real sleeps alone do not recompose. `VirlinUiTest.pump(ms)`
advances both — and guarantees a minimum frame count, because on a cold app one frame
iteration can take far longer than 16ms of real time (the first test otherwise sees a
half-risen sheet).

**Interaction ground truth on device is logcat**, tag `VirlinAgent` (`from -> to` per
transition). Use it to prove sequences and cancellation rather than timing screenshots. The
`VirlinOrb` tag logs only if the AGSL shader was rejected — it must stay silent on Pixel 8.
When driving the Agent with `adb shell input`, re-resolve button coordinates with
`android layout` after typing: the IME shifts the sheet.

Dev-only Orb lab: `adb shell am start -n com.virlin.app/.debug.OrbInteractionLabActivity`.

**More instrumented-test gotchas (learned on the Agent shell):**
- `performScrollTo()` runs an *animated* scroll on the Compose clock — `pump()` after it,
  before asserting visibility, or the node is still off-screen.
- Do not assert transient Orb status text (Success/CaptureSuccess/Speaking last <1s) in
  instrumented tests; real-time pumping runs slower than nominal. Assert the durable
  evidence (receipt, shell, semantics) and leave transient sequencing to the virtual-time
  unit tests and logcat.
- Free-text matchers can become ambiguous once a value appears twice (a destination chip and
  a receipt line). Prefer the receipt's merged `contentDescription` ("<status>: <primary>").
- Goldens for the Agent render `AgentShell` standalone with fixed state — deterministic,
  no ViewModel, no Orb travel. Verify the existing goldens **before** recording new ones.

**PowerShell gotcha:** `gradlew … | Select-String … | Select-Object -First N` terminates
the pipeline once N lines match — and kills the Gradle process with it (leaving corrupted
cache entries: "Failed to create MD5 hash"). Redirect Gradle output to a file, then grep
the file. If it has already happened: `gradlew --stop`, delete `app/build`, rerun.
A crash in `com.android.cli.interact.instrumentation` is the Android CLI's layout server,
not Virlin — check `pidof com.virlin.app` against the FATAL's PID.

## Accessibility

Current coverage is semantics-based: merged nodes, content descriptions, ≥48dp touch targets.
Compose's built-in `enableAccessibilityChecks()` needs Compose 1.7; Virlin is on BOM
2024.06.00 (1.6.8) and is not being upgraded for tooling. Assert semantics explicitly instead.

## Live device inspection

```bash
android layout
```

Full accessibility tree as JSON — class, text, `content-desc`, bounds, center, interactions.
Use it to assert real UI state and to find tap coordinates. `android screen capture` takes a
screenshot; `adb shell uiautomator dump` is an independent cross-check.

Maestro is deferred (no native Windows support) — the stack above is sufficient.

## Suspicious screenshot? Diagnose before concluding — LESSON LEARNED

A black or wrong-looking capture is **not** immediately a product regression, and **not**
immediately a capture artifact. Both conclusions have been wrong here. Cross-check before
deciding:

1. **Hierarchy** — `adb shell uiautomator dump` / `android layout`. Correct text present
   means the app composed fine and the problem is in capture or GPU output.
2. **Logs** — `adb logcat -d *:E` for crashes; also look for the APK being deleted, which
   means the process is stale after a reinstall.
3. **Test results** — does `verifyRoborazziDebug` still pass? That is strong evidence the
   composition is unchanged.
4. **Alternate capture** — try `android screen capture` as well as `adb exec-out screencap`.
5. **Emulator state** — does SystemUI (status/nav bar) render while the app area is black?
   That asymmetry points at a wedged GPU surface, not at your code.

Real case: after a long session of repeated installs and instrumentation, both capture paths
returned black while `uiautomator` showed a complete correct hierarchy and Roborazzi passed.
Root cause was a wedged emulator GPU surface; `adb reboot` restored it. Reinstalling over a
running process also produces a stale-process black screen — force-stop and cold start.

Report what you verified and how. Do not claim "no visual regression" from a single capture.

## Instrumented tests and the durable database (Pass 5+)

- The seeded UI scenarios (`AttentionExitUiTest`, `NowHierarchyUiTest`, `HierarchyUiTest`,
  `AgentWorkspaceUiTest`) assume the demo seed state. Room now persists across reinstalls, so
  run `adb shell pm clear com.virlin.app` before `connectedDebugAndroidTest` after any manual
  session on the device (a test run that ends normally uninstalls the app and leaves it clean).
- `am force-stop` puts the package in Android's *stopped* state and the OS withholds alarms/
  broadcasts; to model process death use `am kill com.virlin.app` (app in background) instead.

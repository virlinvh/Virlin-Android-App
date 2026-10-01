# Virlin testing and release baseline

Verified against `feature/attachment-workspace`: 2026-10-01

## Toolchain

- Android application, Kotlin 2.0, Java 17.
- compile/target SDK 34; minimum SDK 26.
- Compose + Material 3 + Navigation Compose.
- Room 2.6.1 with KSP and exported schemas.
- JUnit, coroutine test, Robolectric, Compose UI tests and Roborazzi.
- Primary validation device: Pixel 8 API 35 emulator.

## Required checks by change class

### UI-only module change

1. `:app:compileDebugKotlin`
2. Relevant unit/screenshot tests.
3. `:app:compileDebugAndroidTestKotlin`
4. Install latest APK and manually inspect the exact flow.

### Domain/repository change

1. Focused domain tests using `InMemoryWorkStreamRepository`.
2. Full unit-test compilation and suite.
3. Android-test compilation.
4. Verify every mutation still passes through actions/repository transactions.

### Room/schema change

1. All domain checks above.
2. New exported schema JSON.
3. Forward migration test from the previous version.
4. Long-chain migration test coverage.
5. Install/upgrade verification without clearing emulator data.

### Managed-file change

Test successful import, cancellation, partial failure cleanup, reopen, duplicate, delete, missing-file
behavior, sharing permissions and process restart.

## Commands

```powershell
gradlew.bat :app:compileDebugKotlin
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:compileDebugAndroidTestKotlin
gradlew.bat :app:connectedDebugAndroidTest
gradlew.bat :app:verifyRoborazziDebug
gradlew.bat :app:installDebug
```

On this machine, the existing Gradle 8.9 installation/cache may be used when the wrapper sandbox
cannot write its default cache.

## Current audit result

- 86 JVM test source files and 46 instrumentation test source files are present.
- Application, JVM-test and Android-test Kotlin compilation passed after the latest changes.
- `testDebugUnitTest` currently executes 1,105 tests successfully. The combined content-workspace
  baseline adds Image coverage to the previous 1,091: 7 `ImageWorkspaceContractTest`, 2
  `ImageBitmapLifecycleTest` and 5 `ImageBackNavigationTest`. The connected
  `ImageRenderEngineTest` contributes 2 instrumented tests, both passing on the emulator.
- `verifyRoborazziDebug` executes the same suite with comparison enabled and currently reports
  17 reviewed screenshot failures. This red visual baseline is under review and must not be mass
  re-recorded.
- Focused task-routing, cycle-guard and Note reconciliation tests pass.

Committed golden baselines live in `app/src/test/screenshots/`. Do not describe either suite as
green until its recorded failures are resolved or explicitly accepted. Never overwrite Roborazzi
goldens merely to clear a failure; inspect every difference and obtain approval.

The current per-image decision is recorded in `GOLDEN_BASELINE_REVIEW.md`. `NowScreenScreenshotTest`
and `ScaffoldScreenshotTest` must hydrate the in-memory graph before capture; otherwise they protect
a transient startup shell instead of the intended screen.

## Emulator freshness

Compilation does not update the emulator. After source changes, run `installDebug`, force-stop the
old process and relaunch. Preserve app data unless a test specifically requires a clean install.

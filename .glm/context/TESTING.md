# Virlin testing context

Reference: the `virlin-mobile-qa` skill. Target device: **Pixel 8 / API 35 emulator**
(`sdk_gphone64_x86_64`); physical Pixel 8 hardware is not part of verification.

## Commands (Windows, Gradle wrapper)

```bash
gradlew.bat assembleDebug              # build
gradlew.bat testDebugUnitTest          # JVM unit tests (Robolectric-backed where needed)
gradlew.bat verifyRoborazziDebug       # screenshot regression (verify goldens)
gradlew.bat connectedDebugAndroidTest  # instrumented tests on the emulator
android layout                         # live UI dump via Android CLI (device behaviour)
```

## Test inventory (real paths)

- **Unit** `app/src/test/java/com/virlin/app/` — `domain/` (VirlinActions, StructureActions,
  AttentionExit, AttentionScheduling, NotificationAction), `command/` (CommandEngine,
  TextCommandInterpreter, TimeExpressionParser, ControlTarget, ControlTimed, CreateNatural,
  CaptureBoundary, DeterministicOnly, integration tests), `agent/`, `now/`, `hierarchy/`,
  `orb/VirlinAgentViewModelTest`, `screenshot/` (Roborazzi tests).
- **Instrumented** `app/src/androidTest/java/com/virlin/app/` — Compose UI tests per area
  (VirlinUi, BottomNavUi, Agent*Ui, AttentionExitUi, HierarchyUi, NowHierarchyUi,
  StreamDetailHandOffUi), `data/` (RoomPersistence, VirlinMigration),
  `platform/` (AttentionScheduler, NotificationAction), plus `CommandHarness`
  (runner-arg-gated manual harness — skipped in the suite).
- **Goldens** `app/src/test/screenshots/` — 23 committed PNGs (Now, scaffold, hierarchy,
  agent areas, command panel states). Committed on purpose.

## Roborazzi rules (protection, not decoration)

- A `verifyRoborazziDebug` failure means inspect the diff and fix the regression.
- **Never re-record a golden to make a failing test pass** — only when the user explicitly
  approves that visual change.
- Some approved goldens are kept byte-identical and no longer verified; current candidates are
  the `*_hierarchy` variants — follow DEVELOPMENT_STATUS if unsure which apply.

## Order of verification for a change

1. Focused test first (the exact unit test class for the domain/command change).
2. Relevant regression (neighbouring classes in the same package).
3. Build (`assembleDebug`) for UI changes; verify visually on the API 35 emulator before
   reporting a UI change complete.
4. Do NOT run hours of unrelated instrumented tests for a small visual change.

## Emulator instability — distinguish environment from regression

Known recurring test-environment failures: `RootViewWithoutFocusException`,
"UiAutomationService already registered", emulator device disconnect/wedge.

When a suspicious failure appears: first **retry** the relevant test → restart the emulator if
it repeats → isolate the class → only then treat it as a real regression. Do not rewrite
product code because an emulator is unhealthy.

## Room test precondition

Seeded instrumented scenarios assume the demo seed. After any manual device session run
`adb shell pm clear com.virlin.app` before `connectedDebugAndroidTest`.

## Injection for tests

`VirlinClock` (system / `FakeClock`), `IdProvider` (UUID / `SequentialIdProvider`),
`AttentionScheduler` fakes; `InMemoryWorkStreamRepository` for JVM tests;
`vm = viewModel(key = "inbox_tab")` pattern for the Inbox tab's own ViewModel instance.

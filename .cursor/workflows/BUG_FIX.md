# Bug-fix workflow

## Classify first

| Class | Meaning |
|---|---|
| PRODUCT BUG | Wrong behaviour in production code |
| TEST ASSUMPTION | Test expects outdated/wrong contract |
| EMULATOR/DEVICE FAILURE | Flaky device/UI automation |
| ENVIRONMENT FAILURE | Host policy, missing DLL, ADB wedge, WAC block |
| STALE DOCUMENTATION | Prose disagrees with source |
| LEGACY/DEAD CODE | Unused path confusing diagnosis |
| EXPECTED NEW BEHAVIOR | Intentional change; goldens/docs need deliberate update |

## Process

1. Reproduce (or capture failing evidence).
2. Graphify: query failing symbol → explain → path callers → `affected` if risky.
3. Trace ownership (who mutates / who displays).
4. Prove root cause with source evidence.
5. Minimum fix only.
6. Focused regression (same test + nearest neighbours).
7. Report class + cause + fix + what was *not* changed.

## Hard rules

- Never rewrite production code merely to satisfy a flaky test.
- Known emulator noise: `RootViewWithoutFocusException`, “UiAutomationService already registered”, ADB disconnect — retry / restart emulator before treating as product.
- Known host issue: Windows Application Control blocking `robolectric-nativeruntime.dll` → **environment**, not app. Do not bypass security.
- Do not re-record Roborazzi goldens without explicit user approval of the visual change.

# Android / Compose rules

## Stack (authoritative pins — do not upgrade unasked)

Root `build.gradle.kts` + `app/build.gradle.kts` are the truth:

- AGP 8.5.1 · Gradle 8.9 · Kotlin 2.0.0 · KSP 2.0.0-1.0.24
- Compose BOM 2024.06.00 · Material 3 · Navigation Compose 2.7.7
- Room 2.6.1 (KSP, schemas exported to `app/schemas/`) · coroutines 1.8.1 (test)
- Roborazzi 1.26.0 · Robolectric 4.12.2 · JUnit 4
- compileSdk 34 · minSdk 26 · targetSdk 34 · Java 17 · single module `:app`
- `gradle/libs.versions.toml` exists with newer versions but is **not consumed** by the
  module — do not trust or "apply" it.

## Compose rules

- Native Jetpack Compose + Material 3 foundation. Use existing design tokens
  (`ui/theme/VirlinTokens.kt`) and existing components first; preserve approved visuals.
- Preserve system insets: the bottom bar owns the navigation-bar inset; the Scaffold supplies
  `innerPadding`; no duplicated/faked padding, no fake status bars.
- Keep UI wired to real state: unidirectional `StateFlow` → `collectAsState`; UI never
  mutates persistence; one source of truth (Focus timer, WorkStream state, processing
  timestamps, FocusSession — never duplicate state for convenience).
- Respect lifecycle and configuration: e.g. `FocusClockScreen` restores orientation/bars on
  dispose; `MainActivity` uses `configChanges` + `adjustResize` deliberately.
- Motion communicates state; animations stay localized; avoid full-screen recomposition at
  frame frequency and per-frame allocations; reduced-motion must keep state legible.
- Accessibility: adequate touch targets, readable contrast, semantic labels/content
  descriptions, state never communicated by animation/colour alone.
- Room is the source of truth; use the injected `VirlinClock`/`IdProvider` where established
  (never `Instant.now()` in domain/command logic); preserve the Android scheduling
  architecture (Room commit → `SchedulingWorkStreamRepository` → `AttentionScheduler`).
- `mock/` + `model/` are display-only (demo timers/seed projection). Do not reattach them as
  a state authority.
- Surgical modification discipline: match surrounding code style, smallest safe change, no
  unrelated refactoring.

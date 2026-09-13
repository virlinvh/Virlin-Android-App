# Virlin — Curated Reference Index

Official sources Claude should consult **instead of searching random blogs or GitHub**.
These are references to read from, not dependencies to install. Nothing here is added to
the Virlin build.

Retrieval: use Context7 (`npx ctx7 docs <libraryId> "<query>"`) or `android docs` for
current API text; use these links for architecture and pattern guidance.

---

## Directly relevant to the NEXT task (Orb → Agent interaction)

### SharedTransitionLayout / shared elements — READ BEFORE IMPLEMENTING THE ORB TRANSITION

https://developer.android.com/develop/ui/compose/animation/shared-elements

Compose provides `SharedTransitionLayout`, `sharedElement()` and `sharedBounds()` for
visual continuity of the same content across UI states. This is the technically correct
way to express the approved requirement that the Orb should *expand into* the Agent rather
than disappear while a separate sheet appears.

**Caveat — verify before committing to it.** Google documents limitations with
`Dialog`/`AndroidView`. Virlin's Agent is a Material 3 `ModalBottomSheet`, which renders in
its own window; shared elements may not cross that boundary. Claude MUST inspect
`AgentSheet.kt` and confirm the approach works before designing around it. If it does not,
the fallback is a coordinated enter animation keyed off the Orb's bounds.

### Compose animation & motion

- https://developer.android.com/develop/ui/compose/animation/introduction
- `skydoves/compose-animations` — https://github.com/skydoves/compose-animations
  Isolated, tweakable animation examples (easing, damping, duration). **Reference only —
  do not copy the library into Virlin.**

---

## Architecture references

### Now in Android — https://github.com/android/nowinandroid

Google's official reference app: Kotlin + Compose, offline-first, modularized, tested.
Consult when Virlin reaches Room, repositories, offline-first sync, modularization or
testing architecture. **Reference only — do not import its architecture wholesale.**

### Official Compose samples — https://github.com/android/compose-samples

Production Compose patterns across many use cases. Prefer this over ad-hoc GitHub searches.

---

## Design system

### Material 3 in Compose — https://developer.android.com/develop/ui/compose/designsystems/material3

Use M3 as the **technical primitive layer underneath Virlin** — sheets, interaction states,
touch targets, typography metrics, accessibility. Do **not** adopt M3 as Virlin's visual
identity.

### Custom design systems — https://developer.android.com/develop/ui/compose/designsystems

Guidance for building a custom design system on top of Compose. Relevant to the planned
Virlin token system (see "Recommended next infrastructure" in `DEVELOPMENT_STATUS.md`).

### Guardrails (deliberate decisions, not oversights)

- **Do not add a generic UI component library.** Virlin's identity is its custom pieces —
  the split-flap clock, the Living Orb, the Needs You attention behaviour. A generic
  component pack would make Virlin look like every other Compose app.
- **Do not drop in third-party "liquid orb"/metaball implementations.** The current Orb
  renderer is approved and working. The open work is interaction architecture, not
  replacing the renderer. Third-party shaders risk artifacts, dependencies and a different
  aesthetic.

---

## Testing & quality

### Compose Preview Screenshot Testing — https://developer.android.com/training/testing/ui-tests/screenshot

Google's official screenshot testing. **Evaluated and not chosen (yet):** still marked
experimental, and adopting it would likely require AGP/Kotlin changes that Section 0 of the
toolchain setup forbids. Roborazzi was chosen instead — it works on Virlin's current
versions today. Revisit if the project upgrades AGP for independent reasons.

### Accessibility testing — https://developer.android.com/guide/topics/ui/accessibility/testing

Automated checks for touch targets, contrast and traversal. Virlin currently does
semantics-based assertions only; full automated checks need Compose 1.7. See
`DEVELOPMENT_TOOLCHAIN.md`.

---

## Performance (defer until after the Orb interaction exists)

- https://developer.android.com/develop/ui/compose/performance
- Baseline Profiles — https://developer.android.com/develop/ui/compose/performance/baseline-profiles

Relevant to Virlin because the Orb runs a continuously animated AGSL shader. **Profile
after implementation** rather than adding complexity pre-emptively. When that work starts,
install the official `android-profiler` skill.

---

## Environment

### Android Studio — https://developer.android.com/ai-in-android

Even with Claude Code as the primary coding agent, Android Studio remains the authoritative
environment for builds, emulator behaviour, profilers and final manual visual inspection.

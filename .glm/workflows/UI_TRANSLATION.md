# UI translation workflow

Much current Virlin work translates design sources (Stitch, HTML reference, screenshot, exact
spec) into Compose. The design is the **visual contract**.

## Rules

- Treat the supplied design as the source of truth. **Do not "improve" or redesign it**
  without request; do not re-interpret approved surfaces while implementing functionality.
- Translate to **native Jetpack Compose** — no WebView, no HTML port, no fake system bars.
- **Wire to real data**: connect the existing ViewModels, `VirlinActions` and repository
  state. Never hardcode design sample data where real data exists; never invent backend
  functionality.
- An unsupported capability may stay visually present **only when specifically requested**,
  and must never fake backend success (precedent: Capture's File/Image and Voice cards are
  visible but disabled — `stateDescription = "Not available yet"`).
- Preserve system bars/insets; the bottom bar owns the navigation-bar inset; don't add
  duplicated padding (`navigationBarsPadding()` where the Scaffold already supplies it).
- Use the approved design tokens (`ui/theme/VirlinTokens.kt`) and existing components first.
- Respect the interaction language: motion communicates state, no decorative animation
  (`virlin-motion-interaction` skill).
- Keep UI state unidirectional and observed (`StateFlow` → `collectAsState`); no duplicate
  domain logic because UI changed.

## Verification

- Build (`gradlew.bat assembleDebug`) and run on the API 35 emulator.
- Compare the rendered result against the design (screenshot vs source) before reporting.
- If a Roborazzi golden legitimately changed because of this approved design, ask the user to
  approve the diff before re-recording — never re-record to clear a failure silently.
- Update `docs/DEVELOPMENT_STATUS.md` only if the project workflow requires it.

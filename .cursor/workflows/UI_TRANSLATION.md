# UI translation workflow

When the user supplies screenshots, Stitch HTML, or design references:

**The provided design is the visual contract.** Translate it into native Jetpack Compose.

## Do

- Match structure, hierarchy, spacing, and interaction intent of the design
- Wire **existing** ViewModels / `VirlinActions` / repository flows
- Preserve system insets, keyboard/IME behaviour, scroll ownership, touch targets
- Keep real domain semantics (LEAVE ≠ HAND OFF, Capture = data, etc.)
- Prefer existing tokens/components (`VirlinTokens`, shared Agent shell pieces)

## Do not

- Redesign, “simplify”, or “improve” the approved composition
- Remove required elements or invent decorative extras
- Invent backend behaviour that does not exist
- Hard-code sample data where real StateFlow/domain state exists
- Fake success for File/Image/Voice Capture or other disabled backends
- Scroll the wrong region (Control: fixed header + scrollable cards + pinned composer)

## After translation

Focused visual check on Pixel 8 / API 35 when the task is UI; report what matches and what the design asked for that has no backend yet (disabled, not faked).

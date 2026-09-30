# Review workflow

When reviewing code, diffs, or behaviour — **classify**, then decide whether to fix.

| Class | Use when |
|---|---|
| BUG | Incorrect behaviour vs contracts/source |
| UX ISSUE | Usable but confusing / wrong hierarchy |
| VISUAL ISSUE | Layout/motion/token mismatch |
| PERFORMANCE ISSUE | Excess recomposition, per-frame alloc, etc. |
| ARCHITECTURE ISSUE | Boundary violation (DAO from UI, duplicate timer, etc.) |
| TEST GAP | Missing coverage for a real contract |
| NOT IMPLEMENTED | Placeholder / demo (e.g. Pulse metrics) |
| DESIGN DEBT | Known temporary bridge (e.g. MockData display) |
| ENVIRONMENT ISSUE | WAC, emulator automation, tooling |

## Rules

- Do **not** automatically fix every discovery.
- Prefer reporting with severity and recommended owner (this task vs future pass).
- Never expand scope into unrelated cleanup during a review unless asked.
- Source/diff beats stale docs — flag doc drift as STALE DOCUMENTATION, not a silent rewrite.

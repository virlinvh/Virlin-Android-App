# Bug-fix workflow

1. **Reproduce** — on the API 35 emulator (or via a failing unit test) before changing code.
2. **Graphify the relevant path** — locate the involved symbols/callers
   (`graphify query`, `path`, `affected`).
3. **Determine the root cause** — read only the involved source.
4. **Distinguish test-infrastructure failures** — `RootViewWithoutFocusException`,
   "UiAutomationService already registered", emulator wedge/disconnect are environment
   failures: retry → restart emulator → isolate class before believing a regression exists.
5. **Minimal fix** — smallest safe change at the correct layer (domain rule in the domain,
   UI bug in the UI); no unrelated cleanup or refactor in the same change.
6. **Focused regression** — the failing test plus its package neighbours; keep goldens intact
   unless the user approved the visual change.
7. Report cause, fix, and verification. Then STOP.

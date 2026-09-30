# Review / audit workflow

For "review/audit the code" requests. The deliverable is a report — not fixes.

1. Read `.glm/context/ARCHITECTURE.md` and `docs/DEVELOPMENT_STATUS.md`.
2. Graphify for structure (`graphify query` / `explain`); inspect minimal source where the
   graph is insufficient.
3. **Report issues before making large changes.** Do not automatically implement unrelated
   discoveries; the user decides what becomes a pass.
4. Classify every finding:
   - **BUG** — contradicts intended/frozen behaviour
   - **ALIAS** — behaviour differs from the user's words but matches the spec (naming/UX wording)
   - **UX CHANGE** — works as specified; the request is a different experience
   - **NEW CAPABILITY** — not implemented by design (see `.glm/context/CURRENT_STATE.md`)
   - **ARCHITECTURAL CHANGE** — touches a frozen contract or the action/persistence layer
5. For each finding: location (`file:line`), evidence, impact, and the smallest possible fix
   sketch — but change nothing without an explicit go-ahead.

Known deliberate limitations (report as NEW CAPABILITY, not bugs): no LLM/voice/TTS, no
reminder entity, no recurring time, no capture voice/file/image, no cloud sync, disabled
Capture cards.

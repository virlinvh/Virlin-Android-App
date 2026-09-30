# GLM session start checklist

Before coding, in order:

- [ ] Read `GLM.md`
- [ ] Read `docs/DEVELOPMENT_STATUS.md` (current implementation status — the truth)
- [ ] Identify the task category (implementation · UI translation · bug fix · review)
- [ ] Read the mapped existing skill from `.glm/SKILL_MAP.md` (one only)
- [ ] Query Graphify (`graphify query / explain / path`) to locate the relevant symbols
- [ ] Inspect minimal source — only the files the delta touches
- [ ] Identify frozen contracts affected — check `.glm/context/FROZEN_CONTRACTS.md`
- [ ] If a frozen contract must change: stop and confirm the user explicitly asked for that change
- [ ] Implement only the requested delta
- [ ] Run focused tests (`.glm/context/TESTING.md`), then relevant regression if justified
- [ ] Report (what changed, what was verified) — then STOP

Avoid: large repository scans · speculative refactors · feature creep · reimplementing working
features · dependency upgrades · changing architecture for convenience.

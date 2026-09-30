# Golden baseline review

Date: 2026-09-30  
Reviewer: Codex  
Source command: `:app:verifyRoborazziDebug`

## Decision

Do not bulk-record or bulk-accept the current screenshots. The failures are understood, but changing committed goldens remains a user-approved visual decision.

The audit originally reported 18 failures: 17 Roborazzi failures and one ordinary unit-test failure. The ordinary failure was a stale expectation for domain-shaped links and has been corrected without weakening URL safety. A fresh run now passes all 1,053 ordinary JVM tests and reports exactly the 17 visual failures classified below.

## Roborazzi classification

### Coherent product redesigns — candidates for individual approval

- `workstream_detail_nested.png`
- `projectless_workstream_detail.png`
- `project_detail.png`
- `agent_entry.png`
- `agent_create.png`
- `agent_create_live.png`
- `agent_control.png`
- `agent_control_live.png`
- `agent_capture.png`
- `now_focus_human.png`
- `now_focus_external.png`
- `needs_you_return_due.png`
- `needs_you_result_ready.png`
- `needs_you_check_due.png`

These comparisons show coherent, readable changes that match the current green hierarchy, refreshed Now/attention cards, or redesigned agent surfaces. They are not pixel noise. They still require explicit visual approval before their reference PNGs are replaced.

### New baseline missing — candidate for first-time approval

- `agent_capture_live_launcher.png`

The test exists and renders the canonical capture launcher, but no committed reference image exists. The produced image is not automatically authoritative; it needs the same explicit approval as a changed golden.

### Test-harness defect corrected — still awaiting visual approval

- `now_screen_hierarchy.png`
- `app_scaffold_hierarchy.png`

The tests captured the startup loading shell because `NowScreen` now refuses to render mock data until `VirlinGraph.startupReadiness` is `Ready`. Their harnesses now reset and hydrate the in-memory graph before capture and reset it afterward. Production startup behavior was not changed.

After that correction both tests render the complete current Now experience. They still fail, correctly, because the committed candidates are older. Do not accept them without reviewing the full redesign, particularly the day-summary header, Current Focus card, ranked Needs You cards, bottom navigation labels, and Orb placement.

## Stale output warning

Old compare artifacts can remain in `app/build/outputs/roborazzi` after earlier runs. Current failure accounting must come from `app/build/test-results`, not from simply counting `*_compare.png` files. Observed stale outputs included `now_screen_compare.png`, `app_scaffold_compare.png`, `task_detail_compare.png`, `agent_command_clarification_compare.png`, and `agent_capture_live_compare.png`.

## Safe approval procedure

1. Run `:app:verifyRoborazziDebug` from a clean-enough working tree.
2. Review each current compare image, grouped by feature.
3. Obtain explicit user approval for the named images.
4. Record only those approved baselines; never mass-record the whole suite.
5. Re-run verification and ensure an unexpected image was not changed.
6. Record the approved image names and rationale in the active agent log.

## Current gate

Parallel feature implementation may proceed only after the shared baseline is checkpointed and agents agree not to touch screenshot references outside their assigned feature. Golden approval itself is still pending.

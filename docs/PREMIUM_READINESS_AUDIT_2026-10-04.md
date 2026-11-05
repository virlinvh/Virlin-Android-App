# Virlin development checkpoint and readiness audit

Date: 2026-10-04. Audited checkout: `C:\tmp\virlin-execution-timing`, branch `codex/now-live-execution-audit`.

Follow-up: `AUDIT_REMEDIATION_2026-10-04.md` records subsequently authorized fixes and updated verification. Results below are the historical pre-fix baseline.

## Decision summary

The app has a substantial local-first foundation, but it is **not yet verified as production-ready**. Continue development after addressing the high-priority findings below. A clearly labelled development checkpoint commit is reasonable after owner approval; do not treat that commit as a release approval. No application behavior, user data or Git history was changed during this audit. This report and documentation-index update are the only intended edits.

This is a source-review and automated-test audit, not a penetration-test certification or proof that every feature works on every device. Instagram/Facebook-scale quality requires operational evidence, not simply a successful build. Virlin currently has no account/cloud-sync backend in its documented architecture, so server scaling, tenant isolation and moderation are outside the current implementation.

## What has been developed

The following records the recent development sequence, grouped by feature rather than implying each step was separately committed. `CODEX_CHANGES.md` contains the more granular change history; existing feature contracts remain authoritative for older modules.

| Area | Current behavior / implementation |
| --- | --- |
| Execution foundation | Independent task execution records, Room schema 20 and forward migrations; human focus and external elapsed tracking have separate state and history. |
| Leaf-only execution | Only the final node can start/control execution. Workstreams with children and parent tasks remain management containers; no arbitrary automatic first-child start. Empty workstreams retain their own execution controls. |
| Responsibility | Project/workstream/task inheritance and explicit overrides resolve human/external responsibility; child overrides do not rewrite the project default. |
| Concurrency | One human focus target across the canonical execution view; multiple external tasks can run. Aggregate parent indicators show focus, running or multi-run. |
| Mode changes | Incompatible execution states are reconciled at mode boundaries; history is preserved. Active structure edits are guarded; startup reconciliation handles legacy container execution. |
| Timers | Scoped Focused/External totals, compact digits and smooth rotating display; section/project display preferences support 5/6/7-second rotation. Project settings contain timer management. |
| Now focus | Canonical task identity replaces generic focus titles; project title, compact ancestry and exact running leaf are connected. Full hierarchy remains available to accessibility text. Fabricated day-summary values no longer stand in for real metrics. |
| Hierarchy presentation | Right chevrons on task/subtask cards; status dials/badges aggregate real activity. Feathered project/task header collapse preserves the relevant heading and restores content on reverse scroll. |
| Controls | Container activity lists and timer/execution clutter removed. Structural editing remains. Workstream completion is confirmed and cascades through its remaining tasks, closing timers and preserving history. Task-container completion still has the separate finding below. |
| Project appearance | 24 curated colors, including Sand; custom wheel, brightness, hex input and shades; five static color roles plus main/muted text; preview, Save, Discard and Reset. Contrast validation protects relevant text. Execution/status colors are intentionally not recolored. |
| Existing content foundation | Projects/workstreams/recursive tasks, notes, links, PDF/file/audio/voice/image workspaces, captured knowledge, reminders, command actions, managed files and migration contracts are documented in the documentation index. This audit does not newly certify every content format or editor interaction. |

## Verification ledger

| Check | Evidence / result |
| --- | --- |
| Full JVM/Robolectric/Compose suite | `:app:testDebugUnitTest`: **1,213 executed, 1,212 passed, 1 failed**. Failure: `WorkstreamControlsUiTest.rename_staysInsideControls_andPrefillsName`, `AppNotIdleException` at line 82. Build exited 1. |
| Lint and release assembly | Run separately because the full test failure stopped the combined command. Final result is recorded in the addendum below. |
| Prior focused appearance checks | Previous development verification recorded 19 targeted tests passing and contrast checks for all 24 presets. These do not replace the full-suite failure. |
| Room source | Version 20; exported schemas 19/20; migrations registered through 19→20. No destructive migration fallback found in the inspected database builder. |
| Connected migration/device tests | **Not rerun** on the user-data emulator: connected test installation previously removed app data. Must use an isolated disposable emulator with fixture data and a backup before release. |
| Visual baselines | Existing `TESTING_AND_RELEASE.md` records 17 reviewed screenshot mismatches. Screenshot comparison was not rerun or baselines re-recorded here. That prior record is not a current pass. |
| Load/performance/accessibility | No new frame-time, ANR, battery, large-tree, TalkBack, RTL or font-scale benchmark completed. These remain release gates. |

Local test report: `app/build/reports/tests/testDebugUnitTest/index.html`. The older testing document's 1,105-test total is historical; use this dated report for the current checkpoint.

## Prioritized findings and proposed fixes

### F1 — High: document parsing limits are enforced too late

Evidence: `domain/attachment/OoxmlReaders.kt`, `readXlsx` reads the entire input with `readBytes()` before checking the 12 MB threshold. `readPptx` buffers whole decompressed slide entries; `extractTextNodes` collects all matching text before display limits apply. DOCX paragraph and XLSX row counts do not bound decompressed bytes, text-node size or parse duration. `ui/map/ProjectMapScreen.kt` also reads an import entirely into memory.

Risk: a large or highly compressed imported document can exhaust memory or CPU and terminate the app. This is resource-exhaustion exposure, not evidence of script execution or data theft.

Proposal: bounded streaming input, total/per-entry decompressed budgets, entry-count and text-length budgets, cancellation/time budgets and explicit friendly rejection. Test oversized inputs, high compression ratios, malformed XML and cancellation on a disposable device. Do not rely solely on provider-reported file size.

### F2 — High: parent-task completion is inconsistent with workstream completion

Evidence: `StructureActions.completeTask` delegates to `close`, which writes only the selected task. It does not traverse descendants or reject a non-leaf. `HierarchyScreens.kt` supplies this action to the container controls. A child can therefore remain open/running beneath a completed parent. Workstream completion uses a separate cascade.

Proposal: define one explicit container-completion policy. Recommended: confirmation stating the number of remaining descendants, then one transaction completing the subtree and closing its exact focus/external sessions, cycles and reminders. Alternatively reject completion until all children are terminal. Add tests for nested active children, unrelated siblings and rollback on write failure.

### F3 — High release gate: fresh installs still receive demo data

Evidence: `VirlinGraph.prepareRepository` invokes `DemoSeed.applyIfEmpty` in the durable database path without a debug-only condition. `DemoSeed` constructs projects/streams/tasks from `MockData` and writes a seed marker. This does not overwrite a populated database, but it creates sample records on a fresh production install.

Proposal: debug/sample onboarding only, opt-in and explicitly labelled. Never delete existing records merely because they match a demo title: they may have become real user data. Decide on a non-destructive migration/onboarding policy.

### F4 — High release gate: target SDK and release packaging

Evidence: `app/build.gradle.kts` uses compile/target SDK 34, version 1.0/code 1, no configured release signing, and minification disabled. The manifest still uses the Android generic launcher icon.

Proposal: update the SDK/toolchain to the applicable current Play requirement, regression-test resulting permission/background behavior, configure secure release signing outside source control, verify the signed bundle and replace the launcher icon. Disabled minification is not itself a vulnerability; optimize only after a working release baseline.

Android's current target requirements must be checked at submission time: [official target-SDK requirements](https://developer.android.com/google/play/requirements/target-sdk).

### F5 — Medium: rename UI test is red

Evidence: full-suite failure above. The dialog already avoids crossfading text editors, but the test still cannot reach idle.

Proposal: determine whether a perpetual animation/remeasurement or test synchronization is responsible; use a deterministic Compose clock and bounded assertions where appropriate. Do not silence the test or claim rename is broken solely from this timeout. Reproduce manually, then rerun the isolated test and full suite.

### F6 — Medium: backup/privacy policy needs a deliberate decision

Evidence: manifest enables Android backup; `backup_rules.xml` and `data_extraction_rules.xml` include all database, shared preferences and app files. Notes/media can therefore participate in configured OS backup. This is not proof that data is publicly accessible. No application-level database encryption is established by this review.

Proposal: document local storage and OS-backup behavior, choose sensitive-file inclusions and encryption-capability rules intentionally, verify restore integrity with managed files, provide deletion/export expectations, and complete privacy/data-safety disclosures for network previews and recording. Avoid disabling backups casually: recovery is valuable.

### F7 — Medium: static-theme coverage is not yet fully consistent

Evidence: `ProjectAppearance.kt` persists project palettes in SharedPreferences; scoped project/task/settings surfaces use it, but other knowledge/activity/control surfaces retain their own static tokens. White feather overlays can mismatch a custom non-white surface. Appearance is not part of the domain export/duplication contract observed here.

Proposal: a central project-static token boundary covering all intended screens; semantic execution/status tokens remain separate. Make feather overlays use the current surface. Decide whether palette duplication/export should travel with the project and test Save/Discard/Reset/restart/export. Do not recolor focus, running, review, paused or multi-run semantic signals.

### F8 — Medium: large hierarchies and lifecycle quality remain unproven

Evidence: overview uses eager scroll content; recursive traversal and repeated all-task projections exist. One emulator and JVM tests do not establish smoothness on low-memory devices or very deep trees.

Proposal: benchmark 1k/10k task fixtures, bounded hierarchy depth or iterative traversal, indexed projections and lazy lists where measurements justify them. Test process death, restore, low storage, denied/revoked permissions, clock/time-zone changes, reboot, rapid double taps and notification actions. Establish frame-time/startup/memory/battery budgets before optimization.

### F9 — Capability boundary: standalone execution

Evidence: `DefaultVirlinActions.resolveTarget` rejects a task without `workStreamId`; `TaskExecutionWriter` maps histories to a required owning stream. Standalone task management exists, but this architecture does not provide standalone focus equivalently to stream tasks.

Proposal: keep unsupported execution controls absent and document the boundary, or explicitly design durable standalone ownership before offering them. This is not automatically a crash: the focus entry point currently rejects the missing owner.

## Positive safety evidence

- Room writes use a serialized repository transaction and publish after successful commit; failed writes roll back in the inspected adapter.
- Explicit schema migrations and history preservation are preferable to destructive database reset.
- Alarm/notification receivers and FileProvider are non-exported; the boot receiver validates supported actions and finishes its asynchronous result in `finally`.
- FileProvider roots are limited to notes, attachments and voices, not a broad filesystem root.
- Inspected YouTube WebView disables file/content access, popups and mixed content, enables Safe Browsing, and restricts main-frame navigation to YouTube hosts. JavaScript is scoped to the player; this is not a general guarantee for every network path.
- Debug-only showcase activity resides in the debug source set; verify release manifest packaging before shipping.
- Project palette decoding tolerates corrupt preferences and offers a Reset path. Status colors are structurally separate from static palette roles.

These are useful controls, not a comprehensive vulnerability scan. No claim is made that dependencies are free of known vulnerabilities; a current dependency/SBOM scan is still required.

## Premium-quality release gate

1. Resolve F1–F4, F10 and the red UI test; approve parent completion semantics.
2. Run full tests green, review—not blindly regenerate—screenshot baselines, lint and signed release build.
3. Run Room previous-version and long-chain migrations, managed-file backup/restore and upgrade tests on disposable devices.
4. Run accessibility (TalkBack, 200% font, contrast/touch sizes), RTL, tablet/foldable, API 26 through current supported API and real low-end device checks.
5. Exercise denial/revocation, background/Doze, restart/reboot, storage failures and malformed/untrusted imports.
6. Add consent-appropriate crash/ANR diagnostics, release monitoring, staged rollout and rollback procedures. No private task titles/media should be collected by default.
7. Review privacy, permissions, deletion/export, dependency security, release signing and store declarations.

Benchmarks: [Android core quality](https://developer.android.com/docs/quality-guidelines/core-app-quality), [Android vitals](https://developer.android.com/topic/performance/vitals/). Large-user-app readiness requires measured crash/ANR and performance results; this audit cannot promise their outcomes.

## Recovery and commit proposal

No commit made. Preserve this branch's modified and untracked files. Before committing, inspect the diff, include new schema/test/source files, exclude generated outputs/secrets/local SDK paths, and retain the prior data archives. The latest known pre-appearance data archive is `C:\tmp\virlin-before-project-colors.tar`; it is a recovery aid, not a newly verified backup of today's complete state. Source-only scroll backups do not replace a full Git checkpoint.

Recommended next choice: approve a **development checkpoint commit with known audit findings**, then fix each concern in small reviewable commits. Do not reset user data or run connected test installation against the current populated emulator. No change to the current UI/functionality is authorized by the audit alone.

## Build/lint addendum

### F10 — High release gate: lint is not clean

`:app:lintDebug` failed with **19 errors and 122 warnings**. Error breakdown:

| Category | Count | Location |
| --- | --- | --- |
| NewApi | 3 | `ui/components/VirlinOrb.kt:288–290`, RuntimeShader use |
| WrongConstant | 4 | `ui/map/VirlinMindMap.kt:669,1195`, Typeface arguments |
| StateFlowValueCalledInComposition | 4 | `ui/prompt/PromptEditorScreen.kt:370–371`, `ui/note/TextNoteEditorScreen.kt:775–776` |
| UnrememberedMutableInteractionSource | 6 | `ui/agent/AgentComposer.kt:372`, `ui/screens/AgentSheet.kt:284,349,358`, `ui/hierarchy/HierarchyComponents.kt:122,141` |
| UnrememberedMutableState | 2 | Instrumentation test `now/NeedsYouPriorityUiTest.kt:189,224` |

The Orb shader factory already checks `SDK_INT < 33` and returns null, so the lint findings **do not alone prove an API 26 crash**. Isolate API-33 operations behind a clearly guarded helper and test the fallback. Correct actual Typeface misuse, collect StateFlows reactively, remember interaction sources and fix test-state creation. Triage the warnings separately; some are polish/deprecation rather than security defects. Do not add a blanket suppression/baseline simply to turn the report green.

Lint evidence: `app/build/reports/lint-results-debug.html`, `.txt` and `.xml`.

`:app:assembleRelease` was then rerun independently and **passed**. Output: `app/build/outputs/apk/release/app-release-unsigned.apk` (14,144,570 bytes). Release assembly runs the narrower vital-lint checks; it does not mean full debug lint passed. The inspected release merged manifest did not contain the debug showcase activity or an enabled debuggable flag. No APK was installed during this audit.

`git diff --check` passed (line-ending conversion warnings only). No commit, staging, reset, application-data mutation or functional fix was performed.

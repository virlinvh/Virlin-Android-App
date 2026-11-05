# Audit remediation — 2026-10-04

Checkout: `C:\tmp\virlin-execution-timing`; branch: `codex/now-live-execution-audit`.
This follows the historical findings in `PREMIUM_READINESS_AUDIT_2026-10-04.md`.
The original report is retained as baseline evidence, not the current pass/fail result.

## Scope and safeguards

Implemented the requested code-level fixes without pushing, staging, resetting Git,
deleting existing records, or installing on the populated user emulator (`emulator-5554`).
Existing feature changes remain in the working tree. Device tests run exclusively on
the disposable `Virlin_Audit_20261004` emulator (`emulator-5556`, Android 15/API 35).
No cloud backend, analytics service, account system or external telemetry was added.

## Remediation by finding

| Baseline | Remediation | Boundary |
| --- | --- | --- |
| F1: unbounded inputs | Shared import budgets bound compressed/decompressed bytes, skipped ZIP entries, entry count, text nodes, row cells and elapsed parsing time. OOXML read/parse errors become friendly results. Office/map IO is interruptible. Remote images bound download size, dimensions and decoded resolution. | This is resource-exhaustion mitigation, not antivirus or universal parser certification. |
| F2: parent completion | Completing/cancelling a task closes its nonterminal subtree in one transaction. Exact sessions/cycles end; reminders and active tracking clear; recorded time survives. Unrelated siblings remain untouched. Restoring a descendant reopens completed task ancestors without starting execution; cancelled ancestors reject restoration. | Container closing requires explicit confirmation, including standalone project containers. |
| F3: release demo seed | Durable production startup no longer inserts sample projects/tasks; debug builds retain fixtures. Existing data is not erased. | Release startup requires its own fresh-install check before publication. |
| F4: release configuration | compile/target SDK 36, AGP 8.9.1 and Gradle 8.11.1; wrapper SHA-256 pinned. Added a project-owned adaptive launcher icon. Release signing accepts a complete four-variable environment configuration and rejects partial credentials. | Without owner credentials the release artifact is intentionally unsigned. No keys were created, committed or requested in chat. |
| F5: rename verification | Dialog uses a stable single editor page and Material text input. Android instrumentation checks prefill, editing, Save callback and return to controls. | Duplicate native Robolectric editor test moved to Android instrumentation after repeated renderer-idle failures; it was not silently ignored. |
| F6: backup/privacy | API 26/27 excludes sensitive automatic backups; API 28+ requires client-side encryption for included data. Android 12+ cloud encryption capability and device-transfer rules are explicit. Added `SECURITY_AND_PRIVACY.md`. | Database is not independently encrypted. Cloud restore, OEM transfer behavior and public privacy disclosures still need validation/owner approval. |
| F7: static appearance | Knowledge/Activity screens and control dialog use project static palette tokens; feather overlays use the project surface. Execution/status colors remain semantic and unchanged. | Palette/timer preferences remain device-local/OS-backup settings; portable export/duplication is not promised. |
| F8: scale/lifecycle | Iterative visited-set hierarchy traversal, batched bottom-up progress, lazy project overview rows and lifecycle-aware Now ticking. Deep hierarchy tests cover 10,000 nodes and cyclic input termination. | Whole-app performance, memory/ANR rates, large media libraries and physical-device accessibility are not certified by these tests. |
| F9: ownerless execution | Explicitly reject standalone task execution before cycle/session writes when no owning workstream exists. Structural standalone management remains available. | This boundary is intentional, not a new standalone execution feature. |
| F10: lint/build | Corrected API guards, remembered Compose interaction/state holders, collected flow state and locale handling. Refactored producer effects to explicit state/effects instead of suppressing lint errors. | Dependency-version and style warnings remain reviewed follow-up work. |

## Important behavioral contracts

- Execution is leaf-only. A parent mode/default does not automatically start a child.
- One human focus target remains canonical; external runs can coexist.
- Completion/cancellation preserves history and accumulated time, never deletes it.
- Restore is not resume. A restored subtree has no automatically running timer.
- Changing a project palette never recolors focus/external/review/multi-run semantics.
- Office/map input limits are counted from actual reads, not caller-supplied lengths.
- Import limits do not delete stored attachments; oversized previews explain the limitation.
- Release builds do not create demo data in the durable database.

## Verification

Final local regression: **1,228 tests passed, zero failures/errors/skips**.
This includes input limits/malformed XML, subtree timer closure, transaction rollback,
restore consistency, deep/cyclic hierarchy and batch progress equivalence, backup rules,
existing execution/Now/appearance/collapse/navigation coverage.

`lintDebug`: **zero errors; 127 warnings and 12 informational notices**.
Warnings mostly concern dependency updates and style; they are not an assertion that
dependencies are vulnerability-free. No blanket lint baseline or suppression added.

Debug APK, Android-test APK and unsigned release APK assemble successfully.
Release APK size at this check: 14,104,446 bytes. Artifact size is not a performance benchmark.

Database/device checks use real on-disk Room and close/reopen recovery, schema 1→20
and intermediate migrations, priority/rank persistence, canonical task focus/external
history and dialog controls. Old schema-version assertions and parent-execution fixtures
were updated to the current contract. Every action expected to start work in the updated
canonical fixture is explicitly asserted successful.

Final isolated Android run: **40 tests passed** (`virlin-device-final3.log`, 17.089 seconds):
WorkControlSafetyUiTest, VirlinMigrationTest, RoomPersistenceTest,
PriorityPersistenceRoomTest and NeedsYouRankPersistenceTest. This includes the editor
regression relocated from Robolectric, not a disabled/ignored rename test.
After the final instrumentation fixture edits, `lintDebug` passed again.

Verification logs are temporary local artifacts under `C:\tmp\virlin-*.log`, not application
data or a telemetry destination. Working-tree whitespace check passed with Windows
CRLF recognized (`core.autocrlf=false`, `core.whitespace=cr-at-eol`).

## Remaining release gates — do not confuse a code push with publication

1. Owner supplies production signing through a secure environment/CI secret store and
   verifies certificate/update continuity. Never commit signing keys or passwords.
2. Validate release fresh-install/update, Android 16 target behavior and supported-device
   matrix, including API 26, low memory, process death, offline use and large media.
3. Exercise encrypted backup/restore and transfer on disposable accounts/devices; retain
   recovery evidence. Existing historical backups are not retroactively erased.
4. Run physical-device frame/ANR/memory profiling and accessibility checks (TalkBack,
   larger text, RTL, contrast, reduced-motion behavior). Algorithm tests are not substitutes.
5. Review dependency advisories/SBOM and native-library 16-KiB page-size compatibility.
   A newer-version lint notice alone is neither proof of a vulnerability nor a safe upgrade.
6. Owner approves privacy policy/Data safety disclosures and a staged-release crash/ANR
   monitoring policy before distribution. No undisclosed telemetry should be introduced.

## Rollback and push preparation

No commit or push was performed. Review this report and the complete working-tree diff
before a named checkpoint commit. There are substantial pre-existing feature edits;
never use `reset --hard` or discard them to undo this remediation. Prefer a reviewed
checkpoint followed by `git revert` for rollback; preserve Room schema 20 and migrations
when preparing any rollback build for devices that already upgraded. An older schema APK
is not a safe data rollback strategy. Appearance also retains its user-facing reset option.

Passing checks improve confidence but do not establish Instagram/Facebook-scale safety
or guarantee that every possible device/input works perfectly.

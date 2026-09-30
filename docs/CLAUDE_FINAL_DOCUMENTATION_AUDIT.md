# Claude final documentation and coordination audit

Auditor: Claude · Date: 2026-09-30 · Mode: read-only
Workspace: `C:\Users\tre21\Documents\Android Apps\Virlin`
Branch `feature/now-day-summary`, HEAD `1e6d821`, worktree dirty (61 modified, 83 untracked, 1 deleted).

Findings only. This document is not an authoritative specification and amends nothing under `docs/`.

---

## 1. Executive verdict

**Approved with corrections.**

Every substantive correction from `docs/CLAUDE_PROJECT_AUDIT_REPORT.md` has been applied and I
independently re-verified each one. The documentation set is now accurate, cross-linked and
genuinely usable as a working baseline: an agent can identify a feature's UI owner, storage owner,
route and specification without reading the codebase.

Two results are materially better than the previous audit:

- **The ordinary JVM suite is now fully green**: 1053 tests, **0 failures** (independently rerun).
  The `CaptureBoundaryTest` failure that had persisted through several audits is genuinely fixed.
- **`GOLDEN_BASELINE_REVIEW.md` is exact.** All 17 visual failures match, including the subtle
  `_hierarchy` filename suffixes and the genuinely-absent `agent_capture_live_launcher.png`.

One blocking correction remains, and it is not in the documentation — it is in the tooling the
documentation now depends on. **The Graphify index is stale in the worst possible place**: the
Task Page subsystem, the project's designated extension point for all future features, has **zero
nodes in the graph**. Every agent entry file instructs agents to query Graphify first. Doing so
today returns the wrong subsystem or nothing.

The 17 visual mismatches are an explicit user-approval gate, not a code failure, and I treat them
as such throughout.

---

## 2. Documentation authority map

| Role | File | Status |
|---|---|---|
| **Single entry index** | `docs/PROJECT_DOCUMENTATION_INDEX.md` | Authoritative; referenced by all four entry files |
| Agent entry (Claude) | `CLAUDE.md` | Routes to index ✓, schema claim now **v17** ✓ |
| Agent entry (Cursor) | `CURSOR.md` | Routes to index ✓ (3 references) |
| Agent entry (GLM) | `GLM.md` | Routes to index ✓ (2 references) |
| Product entry | `README.md` | Routes to index + architecture + coordination ✓ |
| Architecture | `docs/ARCHITECTURE.md` | Authoritative |
| Data/storage | `docs/DATA_AND_STORAGE.md` | Authoritative; `workstreams` naming corrected ✓ |
| Navigation | `docs/NAVIGATION_AND_FEATURES.md` | Authoritative; `stream_detail/{id}` now documented ✓ |
| Testing/release | `docs/TESTING_AND_RELEASE.md` | Authoritative |
| Coordination | `docs/AGENT_COORDINATION.md` | Authoritative |
| Feature ownership | `docs/FEATURE_REGISTRY.md` | Authoritative |
| Schema reservation | `docs/DATABASE_MIGRATION_QUEUE.md` | Authoritative; integration owner now named ✓ |
| Visual gate | `docs/GOLDEN_BASELINE_REVIEW.md` | Authoritative; verified exact |
| Feature contracts | `TASK_PAGE_FOUNDATION`, `TASK_HIERARCHY_CLEANUP`, `NOTE_SYSTEM_CLEANUP`, `PDF_FEATURE_FOUNDATION`, `PROMPT_FEATURE`, `LINK_FEATURE`, `FILE_ATTACHMENT_FEATURE`, `VOICE_FEATURE` | Authoritative per feature |
| Handoff protocol | `docs/HANDOFF_TEMPLATE.md`, `docs/handoffs/README.md` | Authoritative; directory now exists ✓ |
| **Historical, non-authoritative** | `docs/DEVELOPMENT_STATUS.md` | Self-demoted in its own header; **no entry file now treats it as current architecture** ✓ |
| **History, not specification** | `CODEX_CHANGES.md`, `CLAUDE_CHANGES.md`, `docs/CLAUDE_PROJECT_AUDIT_REPORT.md`, this file | Logs and findings |

**Scope A verdict: PASS.** All four agents are directed to the same index and coordination
protocol, and no entry file presents `DEVELOPMENT_STATUS.md` as the current architectural authority.
This was the previous audit's second critical finding; it is resolved.

---

## 3. Graphify readiness — **BLOCKING**

**Configuration.** Guidance lives in the agent entry files: `CLAUDE.md` (11 mentions; Rule 9
"Graph-First Code Navigation" and Rule 10 "refresh with `graphify update .`"), `CURSOR.md` (4,
"Graphify FIRST"), `GLM.md` (5). Output is `graphify-out/` (`graph.json`, `GRAPH_REPORT.md`,
`graph.html`, `manifest.json`, dated caches), gitignored.

**Freshness: STALE, and in the worst place.**

| Check | Result |
|---|---|
| `graph.json` mtime | 2026-09-30 10:08 |
| Kotlin files newer than the index | **36 of 335** |
| `TaskPageActions` nodes in `graph.json` | **0** |
| `TaskPageScreen` nodes | **0** |
| `TaskPageTypeKeys` nodes | **0** |
| `NotesViewModel` / `VirlinNoteCodec` / `MapAddKind` nodes | 48 / 22 / 19 (present) |

The pattern is explicable: the index was last refreshed mid-morning, capturing the Notes work;
Codex's v17 Task Page work landed afterwards and was never indexed.

**Consequence.** `TASK_PAGE_FOUNDATION.md` designates `TaskPageScreen.kt` and `TaskPageActions` as
the seams every future feature must touch. An agent obeying Rule 9 — query Graphify first — is told
they do not exist, and will either conclude wrongly or fall back to full-repository reading, which
is the exact cost the tooling exists to avoid.

**Demonstrated.** `graphify query "Task Page block registration and extension points"` returned 140
nodes centred on `ui/hierarchy/VirlinProjectPage.kt` — the *Project* page, a different feature — and
did not surface `ui/page/TaskPageScreen.kt` at all. The query also truncated at ~2000 tokens with
93 nodes cut. Two lessons worth documenting: the index is stale, and "Page"/"Task"/"Block" are
high-collision tokens in this codebase, so feature queries need a symbol name or `--budget`.

**Smallest safe refresh** (from `CLAUDE.md` Rule 10; incremental, AST-only, no API cost, not a
rebuild):

```
graphify update .
```

I did not run it: this audit is read-only over generated assets, and the instructions direct me to
document the command rather than execute it.

**Gap.** `docs/PROJECT_DOCUMENTATION_INDEX.md` contains **zero** Graphify mentions. The designated
single entry point never tells an agent that a code-discovery index exists, how to query it, or when
to prefer direct source inspection. The guidance survives only in the three agent-specific files,
which is exactly the fragmentation the index was created to end.

---

## 4. Verified architecture

All checks run against the live tree today, after 61 files changed since my previous audit.

| Claim | Result | Evidence |
|---|---|---|
| Compose never touches Room/DAOs | **PASS** | `VirlinDatabase` / `.dao()` in `app/src/main/.../ui/`: **0 hits** |
| Local-first, no cloud/network/LLM runtime | **PASS** | retrofit/okhttp/ktor/supabase/firebase/openai/anthropic in `app/build.gradle.kts`: **0**. `INTERNET` used only for link previews and one WebView (`ui/link/YouTubeInlinePlayer.kt`); `DeterministicOnlyTest` guards the no-model rule |
| Room version 17, no destructive fallback | **PASS** | `VirlinDatabase.kt:247`; no `fallbackToDestructive*` anywhere |
| Exported schemas continuous | **PASS** | 17 files, `1.json`–`17.json` |
| Owner/type keys centrally constructed | **PASS** | `TaskPageTypeKeys.noteOwner` → `task-<taskId>`; `NotesRoute.notesForTask` derives from it |
| ID-based navigation canonical | **PASS** | `taskHierarchyDestination` walks `parentTaskId` with a `visited` cycle guard; no title/index lookup |
| Mutations via `VirlinActions` or a documented cohesive action | **PASS as now documented** | `TaskPageActions` is a documented cohesive domain action; `ARCHITECTURE.md` permits it explicitly. Note it is still constructed inline in Compose at `ProjectMapScreen.kt:482` and `:500` |
| Repository interface is the domain/storage boundary | **PASS** | Room, in-memory, bootstrapping and scheduling implementations all satisfy one interface; compiler-enforced parity |

---

## 5. Canonical versus legacy matrix

| Surface | Canonical | Compatibility | Removal constraint |
|---|---|---|---|
| Task interface | Green `ui/hierarchy/VirlinStreamTasks.kt` | — | Cream `TaskDetailScreen` **removed**; no definition exists anywhere. Must not return |
| Task detail entry | Green hierarchy level | `task_detail/{id}` → `TaskHierarchyRedirectScreen` | Keep until a migration handles notifications, saved state, deep links and every caller |
| Notes (visual new) | `ui/notes` + `virlin_notes` | — | Owner keys only via centralized helpers |
| Notes (existing captures) | — | `ui/note` + `text_note/{captureId}` | **Cannot be deleted**: existing captures depend on it *and* Prompt shares the legacy `NoteBlock` structure |
| Task content aggregation | `task_page_blocks` + `ui/page` | — | References only; never copies canonical content |
| Legacy stream screen | — | `stream_detail/{id}` → `StreamDetailScreen` | **Obsolete/unreachable**: registered, **zero production navigations**. Now correctly documented at `NAVIGATION_AND_FEATURES.md:32-33` as awaiting a deliberate cleanup pass |
| NL capture | `CaptureType.NOTE` quick Inbox capture | — | Not a visual editor flow; needs a product decision to change |

**Task Page extension system — hybrid, and now honestly documented.** `TaskPageBlockRegistry`
exists (`TaskPageScreen.kt:45`) but owns **labels only** (lines 47–53). Row projection (:82–84),
`iconFor` (:157) and `openBlock` (:160–163) remain separate `when` branches.
`TASK_PAGE_FOUNDATION.md:51-53` states this accurately and instructs feature branches to request the
wiring from the integration owner — precisely the right call, and it matches the code.

**One doc/code conflict, recorded not fixed:** the KDoc at `TaskPageScreen.kt:44` reads *"One
registry is the Page's only type-specific seam."* That contradicts both the file beneath it and
`TASK_PAGE_FOUNDATION.md`. An agent trusting the comment would add a label and ship a block with a
default icon and no open action. The document is right; the comment is wrong.

---

## 6. Feature documentation matrix

| Feature | UI owner | Domain/storage owner | Route | Task Page integration | Specification | Status |
|---|---|---|---|---|---|---|
| Green task hierarchy | `ui/hierarchy/VirlinStreamTasks.kt` | `HierarchyViewModel` → `VirlinActions` | `workstream_detail/{id}?path=` | n/a (hosts Page entry) | `TASK_HIERARCHY_CLEANUP.md` | Implemented |
| Task Page | `ui/page/TaskPageScreen.kt` | `TaskPageActions`, `task_page_blocks` | `task_page/{taskId}` | Is the surface | `TASK_PAGE_FOUNDATION.md` | Implemented |
| To-do | `ui/todo` | `TagActions`, `task_steps` | `task_todo/{id}` | `task.todo`, `contentId = taskId` | `TASK_PAGE_FOUNDATION.md` | Implemented |
| Notes (redesigned) | `ui/notes` | `NoteDocActions`, `virlin_notes` | `notes/{ownerKey}?title=` | `task.note`, `contentId = noteOwner(taskId)` | `NOTE_SYSTEM_CLEANUP.md` | Implemented (canonical) |
| Notes (legacy) | `ui/note` | `NoteActions`, `note_documents` | `text_note/{captureId}` | `capture.note` | `NOTE_SYSTEM_CLEANUP.md` | Compatibility-only |
| Prompt | `ui/prompt` | `PromptActions`, `prompt_documents` | `prompt_editor`, `/{captureId}`, `/new/{taskId}` | `capture.prompt`, auto | `PROMPT_FEATURE.md` | Implemented |
| Link | `ui/link` (+ `YouTubeInlinePlayer`) | `CaptureActions`, `LinkDocument` in `captures.content` | `link_editor` (+3 variants) | `capture.link`, auto | `LINK_FEATURE.md` | Implemented |
| File / Attachment | `ui/file` | `AttachmentActions`, `attachment_documents`, `filesDir/attachments` | `file_viewer`, `/{captureId}` | `capture.file`, auto | `FILE_ATTACHMENT_FEATURE.md` | Implemented |
| Voice | `ui/voice` | `VoiceActions`, `voice_documents`, `filesDir/voices` | `voice_editor`, `/{captureId}` | `capture.voice` (**labelled "Audio"** in Page) | `VOICE_FEATURE.md` | Implemented |
| Mind map + palette | `ui/map` | map appearance JSON + domain structure | `project_map/{id}` | Registers blocks on Add | `NAVIGATION_AND_FEATURES.md` | Implemented |
| Now / attention | `ui/screens` | `NowViewModel` → `VirlinActions` | `now` | n/a | `CLAUDE.md` frozen rules | Implemented (frozen) |
| Pulse | `ui/pulse` | `domain/activity` | `pulse`, `pulse_project/{id}` | n/a | `NAVIGATION_AND_FEATURES.md` | Implemented |
| Apps / Mind Maps | `ui/apps` | — | `apps`, `apps_mind_maps[/{id}]` | n/a | `NAVIGATION_AND_FEATURES.md` | Implemented |
| ORB / Capture | `ui/orb`, `ui/agent` | `domain/command` (deterministic) | Sheet, not a route | Ensures `task.note` on Note | `CLAUDE.md` frozen V1 | Implemented (frozen) |
| Inbox | `ui/screens/VirlinInboxScreen.kt` | `CaptureRepository` | `inbox` | Source of capture blocks | `NAVIGATION_AND_FEATURES.md` | Implemented |
| **PDF** | — | — | — | — | `PDF_FEATURE_FOUNDATION.md` | **Foundational** (label + icon; `isSupported` false) |
| **Audio** | — | — | — | — | `VOICE_FEATURE.md` §Audio | **Blocked on product decision** |
| Attachment / Image / Sticker / Illustration tiles | — | — | — | — | `NAVIGATION_AND_FEATURES.md` | Planned (disabled) |

**No implemented feature now lacks a contract.** That gap — Prompt, Link, File and Voice having only
changelog entries — was raised in the previous audit and is closed; all four specs exist, are
indexed at `PROJECT_DOCUMENTATION_INDEX.md:27-30`, and the ones I sampled are accurate and
appropriately warn about their compatibility constraints.

### The unresolved Voice / Audio / File / PDF distinction — user decision required

`VOICE_FEATURE.md:14-21` states the position honestly: task-scoped Voice captures already render as
`capture.voice` blocks **labelled "Audio"** on the Page, while the disabled mind-map Audio tile has
no product contract or separate persistence, and a second incompatible audio table or file lifecycle
must not be introduced without approval. `PDF_FEATURE_FOUNDATION.md:30` likewise leaves specialize-
versus-separate-contract open.

**What must be decided before parallel PDF and Audio work begins** — these are yours, not an
implementing agent's:

1. **Is mind-map "Audio" the same product as Capture Voice?** Voice is a multi-clip, Inbox-shaped
   recorder. If task Audio means "one imported or recorded clip attached to a task", that is a
   different product sharing a file lifecycle. Today the Page already calls Voice "Audio", so
   shipping a second Audio concept would put two different things under one word.
2. **Is Audio imported, recorded, or both?** Import is the File/SAF pipeline; recording is the Voice
   pipeline. This determines which existing subsystem Audio extends.
3. **Is PDF a specialized attachment or its own document?** `AttachmentKind.PDF` already exists and
   the universal viewer already renders PDFs. The binding rule from `PDF_FEATURE_FOUNDATION.md` is
   that one imported file must not produce two records.
4. **Does either need schema v18?** Only after 1–3. The queue allows one owner per version.

My recommendation is unchanged from the previous audit — **specialize the existing Capture
contracts for both** — because it satisfies the one-record rule, needs at most one reserved version,
and reuses working import, permission, managed-storage and viewer code. I am not treating that as
decided.

---

## 7. Parallel-development readiness

| Lane | Owner | Owned paths | Must not touch |
|---|---|---|---|
| **PDF** | Codex | new `domain/*` + `ui/*` PDF module, its tests, `PDF_FEATURE_FOUNDATION.md` | shared roots below |
| **Audio** | Claude | new `domain/*` + `ui/*` Audio module, its tests, its spec | shared roots below |
| **Integration** | Codex primary integration session (named in `DATABASE_MIGRATION_QUEUE.md:5`) | all shared roots | — |

**Shared roots (integration-owner only).** `AGENT_COORDINATION.md:26` already lists `VirlinApp.kt`,
`MapAddPalette.kt`, `ProjectMapScreen.kt`, `VirlinActions.kt`/`DefaultVirlinActions.kt`, repository
interfaces and implementations, Room entities/DAOs/version/migrations, manifest and Gradle, and the
Task Page registry/reconciliation.

**Prohibited overlaps** — the concrete collision points both lanes would otherwise hit:

1. `TaskPageScreen.kt` — **four seams**, not one: `TaskPageBlockRegistry.label` (:47), row
   projection (:82), `iconFor` (:157), `openBlock` (:160).
2. `MapAddPalette.kt` — the enum, `isSupported` (:78), the `rows` pairs (:85), and `iconFor` (:240).
3. `ProjectMapScreen.kt` — the single `addChoice` `when`.
4. `VirlinApp.kt` — route registration **and** `STREAMS_SUB_ROUTES`.
5. `AndroidManifest.xml` — Audio may need `RECORD_AUDIO`; PDF may need provider paths.
6. `app/src/test/screenshots/` — see golden ownership.

**Schema ownership.** v17 is current; **v18 is unassigned** and reservable only through the named
integration owner. Neither lane may claim a version in chat.

**Golden ownership.** No feature agent may record or replace any golden. All 17 pending images are
held by the approval gate in `GOLDEN_BASELINE_REVIEW.md`; new features add new images only, and only
with approval.

**Automatic Page integration.** Capture-backed types register automatically via `CaptureActions`
when `taskId` is present, so a capture-backed PDF or Audio gets its `capture.<type>` block for free.
Only the four presentation seams are feature-specific — which is exactly why they must be
integration-owned.

**Verdict:** the *rules* are complete and correct. Readiness is gated on §10.

---

## 8. Verification state

| Evidence | Command | Result | Date | Source |
|---|---|---|---|---|
| Ordinary JVM suite | `gradlew.bat :app:testDebugUnitTest` (52 s) | **1053 tests, 0 failures — BUILD SUCCESSFUL** | 2026-09-30 | **Independently rerun by Claude** |
| Visual baseline | `gradlew.bat :app:verifyRoborazziDebug` (52 s) | **1053 tests, 17 failures** — all visual | 2026-09-30 | **Independently rerun by Claude** |
| Failure-name match | test-results XML vs `GOLDEN_BASELINE_REVIEW.md` | **Exact match, 17/17**, including `_hierarchy` filenames and the absent `agent_capture_live_launcher.png` | 2026-09-30 | **Independently verified** |
| App Kotlin compilation | via the above tasks | PASS | 2026-09-30 | Independently verified |
| Android-test Kotlin compilation | — | PASS | 2026-09-30 | **Carried from prior audit** (compiled clean then; not rerun today) |
| Emulator install | `adb shell dumpsys package com.virlin.app` | installed, `lastUpdateTime=2026-09-30 20:24:49` | 2026-09-30 | **Partially verified**: install confirmed from the package manager; I did **not** relaunch or re-verify the running UI |
| No golden replaced | `ls app/src/test/screenshots/` | 24 images, `agent_capture_live_launcher.png` still absent | 2026-09-30 | **Independently verified** |

The 17 mismatches map 1:1 onto the three classes in `GOLDEN_BASELINE_REVIEW.md`: 14 coherent
redesigns, 1 missing first-time baseline, 2 harness-corrected. **These are a user-approval gate, not
code failures.**

Honest qualification: the emulator row is the weakest evidence here. A recent `lastUpdateTime`
proves an install happened, not that the current build launches cleanly. If launch confirmation
matters for the checkpoint, it needs a deliberate install-and-open pass.

---

## 9. Git and remote-update readiness

**State.** Branch `feature/now-day-summary`, HEAD `1e6d821`, 61 modified / 83 untracked / 1 deleted
(`ui/screens/PulseScreen.kt`, superseded by `ui/pulse`). The live tree, not HEAD, is the application.

**Ignored artifacts — previous critical finding resolved ✓**

| Path | Size | Now |
|---|---|---|
| `videos/` | 330 MB | **IGNORED** |
| `tmp-diff/` | 22 MB | **IGNORED** |
| `.tmp/` | 16 MB | **IGNORED** |
| `tmp-blackscreen/` | 6.2 MB | **IGNORED** |
| `tmp-acceptance/` | 2.2 MB | **IGNORED** |
| `.mcp.json` | small | **IGNORED** |
| `graphify-out/` | 229 MB | IGNORED (unchanged) |
| `local.properties` | — | IGNORED ✓ |

~380 MB of disposable artifacts can no longer enter history.

**Safe to checkpoint.** Production Kotlin, tests, `app/schemas/13–17.json`, all `docs/`, the agent
logs, `CURSOR.md`, `GLM.md`, drawable resources. I scanned the untracked set: all legitimate source,
schema or documentation. **No secret-bearing file is at risk**; `local.properties` (SDK paths) and
`.mcp.json` are both ignored, and no keystore, `.env` or credential file appears anywhere.

**Requiring a decision before checkpoint** — small, so this is ownership, not size:

| Path | Size | Question |
|---|---|---|
| `maestro/` | 10 KB | UI test flows — project asset or local scratch? |
| `.cursor/` | 88 KB | Cursor orchestration that `CURSOR.md` depends on — commit so Cursor works from a clone? |
| `.glm/` | 96 KB | Same question for GLM |

`.claude/` is already tracked, which argues for committing `.cursor/` and `.glm/` by symmetry — but
that is your call, and `CURSOR.md`/`GLM.md` reference those directories as required reading, so
leaving them out would break those agents on a fresh clone.

**Push preparation: safe**, once §10 items 1 and 2 are settled.

---

## 10. Corrections required before remote update

Smallest first. Not implemented during this audit.

### Blocking

1. **Refresh the Graphify index** — `graphify update .` (incremental, AST-only, no API cost).
   Without it, every agent's mandated first step misses the Task Page subsystem entirely.
   *File:* `graphify-out/` (generated).
2. **Decide `maestro/`, `.cursor/`, `.glm/`** — commit or ignore, before the checkpoint commit.
   *File:* `.gitignore` or the checkpoint's file selection.

### Non-blocking, should fix before parallel development

3. **Add Graphify guidance to `docs/PROJECT_DOCUMENTATION_INDEX.md`** — how to query, when to prefer
   direct inspection, and the refresh command. Include the caution that "Page"/"Task"/"Block" are
   high-collision tokens, so feature queries need a symbol name or `--budget`.
   *File:* `docs/PROJECT_DOCUMENTATION_INDEX.md`.
4. **Correct the KDoc at `TaskPageScreen.kt:44`** — it claims one registry is the only type-specific
   seam; there are four. The documentation is right and the comment is wrong, so this is a one-line
   comment fix, not a refactor. *File:* `app/src/main/java/com/virlin/app/ui/page/TaskPageScreen.kt`.
5. **Resolve the orphaned golden** `app/src/test/screenshots/task_detail.png` — tracked, 73 KB, for a
   deleted screen, referenced by no test. Delete it deliberately or record why it is kept.
6. **Add a "specification" column to `docs/FEATURE_REGISTRY.md`** so a missing contract is visible
   at a glance rather than by absence.

### Optional

7. Rename `MapAddKind.TASK` (labelled "Prompt") to `PROMPT` — UI-only enum, not persisted, safe.
8. Note the eager `task.note` registration consequence in `TASK_PAGE_FOUNDATION.md`: opening Notes
   and backing straight out leaves a Page row for a document that was never written.
9. Document a `virlin.db` snapshot step before destructive on-device testing.

---

## 11. Final recommended sequence

1. **Refresh Graphify** (`graphify update .`) and spot-check that `TaskPageActions` and
   `TaskPageScreen` now resolve.
2. **Decide `maestro/`, `.cursor/`, `.glm/`.**
3. **Visual approval pass** — review the 17 compare images grouped by the three classes in
   `GOLDEN_BASELINE_REVIEW.md`. Approve or reject **by name**; do not bulk-record.
4. **Selective golden recording**, only for approved names, then re-run `verifyRoborazziDebug` and
   confirm no unapproved image changed.
5. **Verification** — `testDebugUnitTest` (expect 1053/0), `verifyRoborazziDebug` (expect only
   unapproved images), `compileDebugAndroidTestKotlin`, and one `installDebug` + manual launch to
   close the emulator evidence gap in §8.
6. **Checkpoint** on an `integration` branch, with the exact test numbers in the commit message.
   This will be the project's first real restore point.
7. **Remote update** from that checkpoint.
8. **Decide the PDF/Audio product contract** (§6) and reserve **v18** only if schema work is needed,
   to exactly one agent.
9. **Create worktrees** `codex/pdf` and `claude/audio` from `integration`.
10. **Implement separately**, each raising `docs/handoffs/<FEATURE>_WIRING_REQUEST.md` for the four
    Task Page seams and any palette/route/manifest change.
11. **Merge one lane at a time**, compiling and running both suites after each.
12. **Combined emulator verification** on one APK: both palette entries, Page ordering with both new
    block types present, and an **upgrade over existing app data** rather than a clean install.

---

## 12. Five explicit confirmations

1. **No production code was changed.** ✓
2. **No schema or database change was made.** ✓ — still v17; no migration added; no version reserved.
3. **No golden screenshot was changed, recorded or deleted.** ✓ — 24 images unchanged;
   `agent_capture_live_launcher.png` still absent.
4. **No Git mutation was performed.** ✓ — only `git status`, `git log`, `git rev-parse`,
   `git check-ignore`. No stage, commit, push, reset, clean, stash, checkout or remote change.
5. **Codex documentation and log entries were not overwritten.** ✓ — `CODEX_CHANGES.md` untouched;
   every `docs/` file authored by Codex left byte-identical; this is a new file.

Also: **Graphify was not regenerated** — the stale index is reported with its refresh command, per
the audit's own instruction.

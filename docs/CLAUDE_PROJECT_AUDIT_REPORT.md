# Claude independent project audit

Auditor: Claude · Date: 2026-09-30 · Mode: read-only
Audited against the **live filesystem**, not Git HEAD.
Branch `feature/now-day-summary`, HEAD `1e6d821`, worktree dirty (54 modified, 82 untracked, 1 deleted).

This document contains **findings only**. It is not an authoritative specification and does not
replace or amend any document under `docs/`.

---

## A. Executive conclusion

**Verdict: Approved with corrections.**

The new documentation baseline is structurally sound and unusually accurate. The architecture it
describes matches the code on every load-bearing claim I tested: Room v17 with a continuous,
non-destructive 1→17 migration chain and complete exported schemas; UI that never touches a DAO;
ID-based routing with cycle guards; the green hierarchy canonical and the cream `TaskDetailScreen`
genuinely deleted; owner-scoped Notes canonical with a hardened legacy compatibility route; and a
Task Page layer that references rather than copies content. Test-file counts are exact.

It is **not yet safe to begin parallel PDF and Audio work**, for two reasons that are both
documentation/process problems rather than code defects:

1. **The frozen-UI protection is currently red and no document says so.** `verifyRoborazziDebug`
   fails 18 tests, including the approved Now and app-scaffold baselines. Every document reports
   exactly one known failure.
2. **The coordination protocol is unreachable from the places agents actually start.** Of the four
   agent entry files, only `README.md` references the new baseline. `CLAUDE.md`, `CURSOR.md` and
   `GLM.md` contain zero references to `PROJECT_DOCUMENTATION_INDEX.md`, `ARCHITECTURE.md`,
   `AGENT_COORDINATION.md` or `TASK_PAGE_FOUNDATION.md`.

Both are cheap to fix. With the corrections in section J.1 applied, the baseline is a good
foundation for four-agent development.

---

## B. Critical findings

### B1. Golden screenshot verification is broadly failing — CRITICAL

`gradlew.bat :app:verifyRoborazziDebug` produces **18 failures out of 1053 tests**, not one:

```
AgentCaptureScreenshotTest.agentCaptureLive_launcherOnly
AgentControlScreenshotTest.agentControlLive
AgentCreateScreenshotTest.agentCreateLive
AgentShellScreenshotTest.agentCapture / agentControl / agentCreate / agentEntry
AttentionExitScreenshotTest.focusExternal / focusHuman
AttentionExitScreenshotTest.needsYouCheckDue / needsYouResultReady / needsYouReturnDue
HierarchyScreenshotTest.projectDetail / projectlessWorkStreamDetail / workStreamDetailNested
NowScreenScreenshotTest.nowScreen_matchesApprovedBaseline
ScaffoldScreenshotTest.appScaffold_matchesApprovedBaseline
CaptureBoundaryTest.types_are_explicit_and_payloads_raw   (the known one)
```

Why this is critical rather than cosmetic:

- Two of the failures are `nowScreen_matchesApprovedBaseline` and
  `appScaffold_matchesApprovedBaseline` — the **frozen, approved** Now UI, the single most
  protected surface in the project per `CLAUDE.md`.
- Goldens in `app/src/test/screenshots/` are dated 2026-09-11 to 09-19. Heavy UI work landed
  09-28 to 09-30. The baselines are simply stale.
- `CLAUDE.md` instructs agents to re-record a golden **only** with explicit user approval. Four
  agents each meeting a wall of 17 red screenshots is a strong incentive to mass re-record, which
  would silently destroy the approved baseline — exactly the failure mode the rule exists to
  prevent.
- `TESTING_AND_RELEASE.md` says "Do not describe the full suite as green until the known
  CaptureBoundary failure is resolved". That sentence implies the CaptureBoundary failure is the
  *only* obstacle, which is not true under the verification task.

Note the distinction that makes the docs *technically* defensible but *practically* misleading:
plain `testDebugUnitTest` reports 1 failure, because comparison does not run. Only
`verifyRoborazziDebug` compares. The documents cite the former and describe the latter's rules.

**No golden was re-recorded during this audit.**

### B2. Agent entry points do not reference the new baseline — CRITICAL for parallel work

| File | `PROJECT_DOCUMENTATION_INDEX` | `ARCHITECTURE.md` | `AGENT_COORDINATION` | `TASK_PAGE_FOUNDATION` |
|---|---|---|---|---|
| `CLAUDE.md` | 0 | 0 | 0 | 0 |
| `CURSOR.md` | 0 | 0 | 0 | 0 |
| `GLM.md` | 0 | 0 | 0 | 0 |
| `README.md` | 1 | 1 | 1 | 0 |

`AGENT_COORDINATION.md` states every agent must read `PROJECT_DOCUMENTATION_INDEX.md` first — but
no agent's actual entry file tells it to. Worse, `CURSOR.md` and `GLM.md` both direct the agent to
bootstrap from `docs/DEVELOPMENT_STATUS.md`, and that file's own header now says it is historical
and "does not include every feature added after its last-updated date". Cursor and Antigravity
would therefore start from a document that disclaims its own authority and would never see the
Task Page, Notes or hierarchy contracts.

### B3. A naive checkpoint would commit ~380 MB of disposable artifacts — CRITICAL

These are untracked and **not** covered by `.gitignore`, so `git add -A` would put them in history
permanently:

| Path | Size | Nature |
|---|---|---|
| `videos/` | 330 MB | screen recordings |
| `tmp-diff/` | 22 MB | validation artifacts |
| `.tmp/` | 16 MB | validation artifacts |
| `tmp-blackscreen/` | 6.2 MB | validation artifacts |
| `tmp-acceptance/` | 2.2 MB | validation artifacts |
| `maestro/` | 10 KB | UI test flows — possibly wanted |
| `.mcp.json` | small | agent config — possibly wanted |

`graphify-out/` (229 MB) is already ignored. Git cannot cheaply forget large blobs afterwards, so
this must be settled **before** the checkpoint commit, not after.

### B4. `CLAUDE.md` states the wrong schema version

`CLAUDE.md:308` says **"Schema is v16"**; the live database is **v17**
(`VirlinDatabase.kt:247`). The sentence also stops at `virlin_notes` and never mentions v17's
`task_page_blocks`. An agent trusting `CLAUDE.md` would reserve v17 for a new migration and collide
with the existing one. (For provenance: that line was last edited by Claude before Codex introduced
v17, so this is a Claude-authored staleness, not a Codex error.)

---

## C. Documentation-by-document verification

Legend: **V** verified correct · **I** correct but incomplete · **A** ambiguous · **S** stale ·
**X** incorrect.

### `PROJECT_DOCUMENTATION_INDEX.md`
- **V** — the warning that HEAD is not the running app is correct and important (54 modified /
  82 untracked files confirm it).
- **V** — the "code is the final source of truth" rule and the prohibition on architecture
  decisions living only in chat or an agent memory store.
- **I** — it is the designated entry point but nothing routes agents to it (see B2). An index that
  nobody is told to open cannot function as an index.
- **I** — lists no document for Prompt, Link, File or Voice. Those four shipped features have no
  authoritative specification anywhere; their only description is in `CODEX_CHANGES.md`, which this
  very document says is "not an architecture spec". That is a self-acknowledged gap worth naming
  explicitly.

### `ARCHITECTURE.md`
- **V** — dependency flow, layer inventory, `VirlinGraph` as service locator and startup
  coordinator. Verified: `MainActivity` calls `VirlinGraph.init(applicationContext)` then
  `setContent`, so Compose renders while hydration proceeds, and receivers
  (`AttentionAlarmReceiver`, `BootRescheduleReceiver`, `NotificationActionReceiver`) call
  `ensureReady()` first. `BootstrappingWorkStreamRepository` throws a clear error if used early.
- **V** — "Compose must never call a DAO or directly mutate Room." I searched `ui/` for
  `VirlinDatabase`, `Dao` and `.dao()`: **zero hits**. This invariant genuinely holds.
- **V** — local-first with no cloud/account/LLM dependency. No `retrofit`, `okhttp`, `ktor`,
  `firebase`, `supabase`, `openai`, `anthropic`, `tensorflow` or `mlkit` in `app/build.gradle.kts`.
  `INTERNET` exists, used only by link previews and one WebView (`ui/link/YouTubeInlinePlayer.kt`).
  `DeterministicOnlyTest` guards the no-model rule.
- **V** — every domain package it lists exists.
- **A → see B4/C-conflict** — "mutations must go through `VirlinActions` **or a domain action
  class**". `CLAUDE.md` says all state changes go through "the unified `VirlinActions` facade".
  These are different rules, and the looser one reads as retrofitted to the code. `TaskPageActions`
  is **not** on the facade and is constructed directly in UI in four places, including inline
  inside Compose click handlers at `ProjectMapScreen.kt:482` and `:500`. Since the Task Page is the
  shared extension point every future feature must touch, the project should state one rule
  deliberately rather than let two coexist.

### `DATA_AND_STORAGE.md`
- **V** — Room version 17; exported schemas `1.json`–`17.json` all present; migrations
  `MIGRATION_1_2` … `MIGRATION_16_17` registered with no gaps; **no `fallbackToDestructive*`
  anywhere in the codebase**.
- **V** — owner-key contract (`task-<taskId>`, `project-<projectId>`, `global`) matches
  `TaskPageTypeKeys.noteOwner` and `NotesRoute`.
- **V** — FileProvider paths. `res/xml` declares `cache-path notes/`, `files-path attachments/`,
  `files-path voices/`, matching the documented attachment, voice and legacy-note-PDF locations.
- **V** — the PDF distinction (`AttachmentKind.PDF` is real and separate from the disabled
  `MapAddKind.PDF`).
- **X** — the table is listed as **`work_streams`**. The real table name is **`workstreams`**
  (no underscore), per `VirlinEntities.kt`. All 19 other names are correct and the count (20) is
  right. This matters more than a typo: raw SQL in a future migration written from this document
  would fail at runtime, and an agent grepping `work_streams` finds nothing.
- **I** — "Feature code must implement both sides when extending the repository contract" is true
  but understates the mechanism: both implementations satisfy a Kotlin interface, so the compiler
  already enforces it. Worth saying, because it tells an agent the failure mode is a build error,
  not a silent runtime gap.
- **I** — `filesDir/project-icons/` and `filesDir/map_appearance.json` are listed as managed files
  but are deliberately *not* FileProvider-shared. Not stated.

### `NAVIGATION_AND_FEATURES.md`
- **V** — every documented route exists with the documented pattern; the `task_detail/{id}`
  redirect description is exact; the Add-palette enabled/disabled split matches
  `MapAddKind.isSupported` precisely (TODO, TASK, LINK, NOTE enabled; PDF, ATTACHMENT, IMAGE,
  AUDIO, STICKER, ILLUSTRATION disabled).
- **V** — permissions section matches the manifest.
- **X (omission)** — **`stream_detail/{id}` is registered at `VirlinApp.kt:292` and is not in this
  document.** It renders `ui/screens/StreamDetailScreen.kt`. I searched the whole of
  `app/src/main` for navigation to it: **zero callers**. It is also absent from
  `STREAMS_SUB_ROUTES`, so it would render without the shared footer or Orb. This is a second
  unreachable legacy surface alongside the removed cream Task Detail, and the document asserts a
  completeness it does not have.
- **I** — the document never states **which routes receive the bottom bar and Orb**, and the live
  behaviour is inconsistent in a way agents will trip over: `notes/` **is** in `STREAMS_SUB_ROUTES`
  and gets the shared shell, while `task_page/` is **not** and supplies its own `Scaffold`
  (`TaskPageScreen.kt:126`). Both are full-screen task surfaces reached the same way. The rule
  needs writing down, whichever way it is decided.
- **A** — the palette entry "Prompt" is the enum constant **`MapAddKind.TASK`**. An agent reading
  `MapAddKind.TASK` would reasonably assume it creates a task. Harmless today, a trap tomorrow.

### `TESTING_AND_RELEASE.md`
- **V** — toolchain (Kotlin 2.0, Java 17, Room 2.6.1 + KSP, Roborazzi 1.26.0), the per-change-class
  checklists, and the command list.
- **V (exact)** — "83 JVM test source files and 46 instrumentation test source files": I counted
  **83** and **46**. Precise.
- **S** — "1,049 tests". Live count is **1053** (83 test classes).
- **X (material)** — "one known unrelated failure" is wrong for the verification task; there are
  18 (see B1). This is the most consequential inaccuracy in the baseline.
- **I** — the golden-baseline location (`app/src/test/screenshots/`, committed on purpose, as
  `.gitignore` explains) is never stated here. It took a `.gitignore` comment to establish it.

### `AGENT_COORDINATION.md`
- **V** — the dirty-worktree warning, the ownership model, the merge-one-at-a-time rule, the
  "never renumber a migration independently" rule, and per-agent change-log ownership. The shared-
  roots list is accurate and well chosen.
- **I** — the shared-file list omits real collision points (section I).
- **I** — it references `docs/handoffs/<FEATURE>_WIRING_REQUEST.md`; the `docs/handoffs/`
  directory does not exist yet.
- **X (process)** — the start-of-task protocol cannot execute, because no agent entry file points
  here (B2).

### `FEATURE_REGISTRY.md`
- **V** — every status label matches what I found in code, including "PDF palette feature:
  name/icon foundation only; disabled".
- **I** — no column for "authoritative specification document", which would have exposed that
  Prompt, Link, File and Voice have none.

### `DATABASE_MIGRATION_QUEUE.md`
- **V** — v17 current; v18 the next reservable version; the governance rules are sound and this is
  the right mechanism for four agents.
- **I** — does not name the current integration owner, so "available only through integration
  owner" has no addressee.

### `PDF_FEATURE_FOUNDATION.md`
- **V** — all six claims verified: `MapAddKind.TOPIC_LINK` is **absent** from the entire codebase;
  `PDF("PDF")` exists; its icon is `Icons.Outlined.PictureAsPdf`; it sits left of `ATTACHMENT` in
  row three; `isSupported` excludes it; and `MapAddKind` is a UI-only enum that is never persisted,
  so the rename needed no migration.

### `TASK_PAGE_FOUNDATION.md`, `TASK_HIERARCHY_CLEANUP.md`, `NOTE_SYSTEM_CLEANUP.md`
- **V** — these three I had already verified line-by-line in three prior synchronization passes
  recorded in `CLAUDE_CHANGES.md`, and I re-confirmed their key claims here. `contentId ==
  "task-<taskId>"` now matches the implementation; `textNoteRoute` takes a non-null id and
  `require`s non-blank; no bare `"text_note"` target exists; `TaskDetailScreen` has no definition
  anywhere; `taskHierarchyDestination` is pure and ID-based with a cycle guard that now has a
  direct test.
- **S (minor)** — `TASK_HIERARCHY_CLEANUP.md` lists "The obsolete task-detail screenshot golden
  entry" as removed. The **test** entry is indeed gone (no screenshot test references
  `task_detail`), but the **file** `app/src/test/screenshots/task_detail.png` is still on disk and
  still tracked by Git. An orphaned 73 KB baseline for a deleted screen.
- **I** — `TASK_PAGE_FOUNDATION.md`'s extension contract says to "add one renderer/editor mapping
  to `TaskPageBlockRegistry`", but there is no type named `TaskPageBlockRegistry`; the mapping is
  done by `when` expressions over `typeKey` inside `TaskPageScreen.kt` (label at :48, row at :84,
  icon at :157, edit routing at :161). A future agent will search for a registry that does not
  exist. Section D lists the real files.
- **A** — eager registration: `ProjectMapScreen.kt:500` registers the `task.note` Page block when
  the palette item is tapped, before any document exists. Opening Notes and backing straight out
  leaves a Page row for a note that was never written. Consistent with "register in the creation
  flow", but the consequence is undocumented.

### `CLAUDE.md` / `CURSOR.md` / `GLM.md` / `README.md`
- **X** — `CLAUDE.md:308` schema v16 vs live v17 (B4).
- **X (process)** — none of `CLAUDE.md`, `CURSOR.md`, `GLM.md` route to the new baseline (B2).
- **A** — `CLAUDE.md` and `ARCHITECTURE.md` state different mutation rules (see C/`ARCHITECTURE`).
- **V** — `README.md` is accurate, including "No cloud, accounts, sync or AI/LLM features exist",
  and it is the only entry file that links the new docs.
- **V** — `DEVELOPMENT_STATUS.md`'s new header correctly demotes itself to history. The problem is
  that two agent entry files still treat it as current.

---

## D. Independently reconstructed architecture

Virlin is a single-module, offline Android application. Nothing leaves the device except user-
initiated link opening and two optional network reads (link preview thumbnails, the restricted
YouTube iframe). There is no account, no sync, and no model of any kind; the Agent's language layer
is a hand-written deterministic parser guarded by a test that fails if a provider is introduced.

**Startup.** `MainActivity.onCreate` calls `VirlinGraph.init(applicationContext)` and then
`setContent`. `VirlinGraph` is a hand-rolled service locator holding the repository, the
`VirlinActions` facade, the clock, the id provider and the command engine. Room opens
asynchronously behind `BootstrappingWorkStreamRepository`, which lets Compose draw immediately and
makes any premature write fail loudly rather than silently. Broadcast receivers suspend on
`ensureReady()` before touching state. The consequence worth internalising: **UI may render before
the database exists, so no screen may assume hydrated data on first composition.**

**The spine.** Compose → ViewModel or a UI action interface → a domain action class → a
`WorkStreamRepository.transaction` → Room. Reads come back as `StateFlow`. The rule that the UI
never touches a DAO is real and holds today with zero exceptions.

**Domain.** `WorkStream` is the primary object, carrying attention state; `Task` nests arbitrarily
deep via `parentTaskId` with cycles rejected on create and guarded again on read; a single human
`FOCUS` is enforced centrally; execution preference (Human/External/Inherit) resolves through
inheritance and survived the cream-UI deletion untouched. Derived projections — progress, activity,
the active path — are computed, never stored.

**Content model.** Two generations coexist deliberately. Capture-era content (`CaptureItem` plus a
typed companion document) backs Prompt, Link, File, Voice and legacy Notes. The redesigned era is
owner-scoped (`virlin_notes` keyed by an opaque owner string). Both are bound together by
`task_page_blocks`: an ordered **reference** table — `taskId`, open-string `typeKey`, `contentId`,
`sortOrder` — that copies nothing. Unknown `typeKey` values must survive and render as a fallback.
This is the project's real extension seam, and its quality is the main reason parallel feature work
is plausible at all.

**Files.** Bytes live in managed app storage under `filesDir` with relative paths in the database;
sharing goes through `FileProvider` with three declared roots.

**Files a new Page-backed feature must touch** (the real answer to "what will collide"):
`MapAddPalette.kt` (enum + `isSupported` + icon + row layout), `ProjectMapScreen.kt` (the
`addChoice` branch), `VirlinApp.kt` (route registration and possibly `STREAMS_SUB_ROUTES`),
`TaskPageScreen.kt` (**four separate `when` blocks**: label :48, row builder :84, icon :157, edit
routing :161), `TaskPageTypeKeys` if a new key form is needed, and — only if new persistence is
required — `VirlinEntities.kt`, `VirlinDatabase.kt`, `VirlinMappers.kt`, `WorkStreamRepository.kt`,
`RoomWorkStreamRepository.kt`, `InMemoryWorkStreamRepository.kt`,
`BootstrappingWorkStreamRepository.kt`, `VirlinActions.kt`, `DefaultVirlinActions.kt`.

---

## E. Route inventory (verified)

C = canonical · K = compatibility · O = obsolete/unreachable.
"Shell" = receives shared bottom bar + Orb.

| Route | Helper | Screen | Args | Class | Shell |
|---|---|---|---|---|---|
| `now` | — | `NowScreen` | — | C | yes |
| `streams` | — | `StreamsScreen` | — | C | yes |
| `pulse` | — | `pulse.PulseScreen` | — | C | yes |
| `pulse_project/{id}` | `PulseProjectRoute` | Pulse project | id | C | yes |
| `apps` | — | Apps directory | — | C | yes |
| `apps_mind_maps` | `MindMapsHomeRoute` | Mind Maps home | — | C | yes |
| `apps_mind_maps/{id}` | `MindMapsFolderRoute` | Mind Maps folder | id | C | yes |
| `inbox` | — | `InboxScreen` | — | C | yes |
| `project_detail/{id}` | `ProjectDetailRoute` | Project experience | id | C | yes |
| `workstream_detail/{id}?path={path}` | `WorkStreamDetailRoute` | Green hierarchy | id, path | C | yes |
| `project_task_index/{id}` | `ProjectTaskIndexRoute` | Project task index | id | C | yes |
| `project_map/{id}` | `ProjectMapRoute` | Mind map | id | C | yes |
| `task_page/{taskId}` | `TaskPageRoute` | Task Page | taskId | C | **no** (own Scaffold) |
| `task_todo/{id}` | `TaskTodoRoute` | To-do | id | C | yes |
| `task_detail/{id}` | `TaskDetailRoute` | `TaskHierarchyRedirectScreen` | id | **K** | yes |
| `notes/{ownerKey}?title={title}` | `NOTES_ROUTE` | Redesigned Notes | ownerKey, title | C | yes |
| `text_note/{captureId}` | `textNoteRoute(String)` | Legacy Capture Note | captureId | **K** | no |
| `prompt_editor` | `PromptRoute` | Prompt (new) | — | C | no |
| `prompt_editor/{captureId}` | — | Prompt (existing) | captureId | C | no |
| `prompt_editor/new/{taskId}` | — | Prompt (task-scoped) | taskId | C | no |
| `link_editor` | `LinkEditorRoute` | Link (new) | — | C | no |
| `link_editor/{captureId}` | — | Link (existing) | captureId | C | no |
| `link_editor/new/project/{projectId}` | — | Link (project) | projectId | C | no |
| `link_editor/new/task/{taskId}` | — | Link (task) | taskId | C | no |
| `file_viewer` | `FileViewerRoute` | File import/new | — | C | no |
| `file_viewer/{captureId}` | — | File viewer | captureId | C | no |
| `voice_editor` | `VoiceEditorRoute` | Voice (new) | — | C | no |
| `voice_editor/{captureId}` | — | Voice (existing) | captureId | C | no |
| `focus_clock` | `FocusClockRoute` | Fullscreen focus clock | — | C | no (immersive) |
| `stream_detail/{id}` | — | `StreamDetailScreen` | id | **O — zero callers** | no |

**Missing-ID handling.** Nullable arguments are read defensively (`arguments?.getString(...)`) and
resolvers return null rather than guessing; `task_detail` shows a non-destructive missing-location
state. `textNoteRoute` now rejects blank ids at construction.

**The cream `TaskDetailScreen` is genuinely gone** — no definition, no reference, no reachable path.

---

## F. Data and storage inventory

- **Room version: 17.** No destructive fallback anywhere.
- **Migrations:** `1_2 … 16_17`, continuous, all registered in `MIGRATIONS`.
- **Exported schemas:** `1.json` … `17.json`, complete.
- **20 tables:** `projects`, **`workstreams`**, `tasks`, `external_stages`, `priority_preferences`,
  `captures`, `cycles`, `focus_sessions`, `context_snapshots`, `events`, `meta`, `note_documents`,
  `prompt_documents`, `attachment_documents`, `voice_documents`, `project_tags`, `tag_links`,
  `virlin_notes`, `task_steps`, `task_page_blocks`.
- **Managed files:** `filesDir/attachments/{attachmentId}/original`,
  `filesDir/voices/{captureId}/{clipId}.m4a`, `filesDir/project-icons/`,
  `filesDir/map_appearance.json`, `cacheDir/notes/` (shared legacy-note PDFs).
- **FileProvider roots:** `cache-path notes/`, `files-path attachments/`, `files-path voices/`.
- **Ownership keys:** task `task-<taskId>` via `TaskPageTypeKeys.noteOwner`; project
  `project-<projectId>`; global `global`. Page type keys `task.todo`, `task.note`,
  `capture.<lowercase type>`, open string with fallback.
- **Compatibility stores:** `note_documents` (legacy Capture Notes; also structurally shared with
  Prompt through `NoteBlock`).

**Data-loss risks.**
1. A stale Page block whose canonical content is deleted renders as unavailable by design; there is
   no cleanup policy yet. Intentional and documented.
2. `NoteBlock` is shared by legacy Notes **and** Prompt. Any future "delete the legacy Note system"
   change risks Prompt. Documented in `NOTE_SYSTEM_CLEANUP.md` — the single most important
   compatibility warning in the project.
3. No automated backup of `virlin.db` exists before destructive on-device testing; this has already
   caused one unintended real-data change earlier in the project's history.

---

## G. Feature status matrix

| Feature | Status | Evidence |
|---|---|---|
| Now / attention surfaces | Implemented (frozen) | routes + screens; **goldens currently failing** |
| Fullscreen Focus Clock | Implemented | `focus_clock`, own timing-free screen |
| Projects / WorkStreams / Tasks | Implemented | green hierarchy canonical |
| Green task hierarchy | Implemented | `VirlinStreamTasks`, Focus/Delegate in overflow |
| Cream `TaskDetailScreen` | **Obsolete — removed** | no definition anywhere |
| `task_detail/{id}` | Compatibility-only | `TaskHierarchyRedirectScreen` |
| `stream_detail/{id}` | **Obsolete — unreachable** | registered, zero callers |
| Mind map + Add palette | Implemented | `ui/map`, 4 of 10 kinds enabled |
| Task Page | Implemented | `task_page_blocks`, reference-only |
| To-do | Implemented | `task_steps` |
| Notes (redesigned) | Implemented (canonical) | `ui/notes`, `virlin_notes`, 40 tests |
| Notes (legacy capture) | Compatibility-only | `text_note/{captureId}` |
| Prompt | Implemented | routes + `prompt_documents`; **no spec doc** |
| Link | Implemented | validation, preview, YouTube segments; **no spec doc** |
| File / Attachment | Implemented | SAF import, managed store, viewer; **no spec doc** |
| Voice | Implemented | multi-clip, managed store; **no spec doc** |
| Agent (Control/Create/Capture) | Implemented (frozen V1) | deterministic, guarded by test |
| Inbox | Implemented | durable capture review |
| Pulse | Implemented | `ui/pulse` (old `PulseScreen.kt` deleted) |
| Apps / Mind Maps | Implemented | `ui/apps` |
| PDF palette | Placeholder | `MapAddKind.PDF`, `isSupported` false |
| Audio palette | Placeholder | disabled, no contract |
| Attachment / Image / Sticker / Illustration palette | Placeholder | disabled |

---

## H. Test and verification report

All commands run from the repository root on Windows, against the live dirty worktree. Nothing was
modified, recorded or committed.

| Command | Duration | Result |
|---|---|---|
| `gradlew.bat :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin` | 7 s | **BUILD SUCCESSFUL** — all three source sets compile |
| `gradlew.bat :app:testDebugUnitTest` | 40 s | **1053 tests, 1 failure** — `CaptureBoundaryTest.types_are_explicit_and_payloads_raw` |
| `gradlew.bat :app:verifyRoborazziDebug` | 64 s | **1053 tests, 18 failures** — 17 stale goldens + the known CaptureBoundary failure (full list in B1) |

Counts: **83** JVM test source files, **46** instrumentation test source files, **83** executed test
classes, **24** committed goldens in `app/src/test/screenshots/`.

Not run: `connectedDebugAndroidTest` and `installDebug`. Instrumented tests write to the real
on-device database, and this audit is read-only; the migration suite's results are therefore carried
from Codex's report rather than independently reproduced, and I flag that as **unverified by me**.

**No golden was updated. No test was modified.**

---

## I. Parallel-development readiness

**Not ready today.** Three blockers, all in B: stale goldens (B1), unreachable coordination
protocol (B2), and the checkpoint artifact problem (B3).

`AGENT_COORDINATION.md` is otherwise a good policy. Its shared-roots list should gain these
**hidden collision points** I found, each of which two agents adding a Page type would both edit:

1. **`TaskPageScreen.kt` — four separate `when` blocks** (lines 48, 84, 157, 161). Not one registry.
   Two agents adding a type touch all four; every one is a conflict.
2. **`MapAddPalette.kt`** beyond the enum: `isSupported`, the `rows` layout pairs, and `iconFor`.
3. **`ProjectMapScreen.kt`** `addChoice` branch — one `when` both PDF and Audio must extend.
4. **`VirlinApp.kt`** `STREAMS_SUB_ROUTES` and the `openRedesignedNotes`-style context helpers, not
   only route registration.
5. **`TaskPageTypeKeys`** if either feature needs a new key form.
6. **`app/src/test/screenshots/`** — any agent re-recording goldens rewrites shared binaries.
7. **`AndroidManifest.xml`** — Audio needs `RECORD_AUDIO`; PDF may need provider paths.

### PDF and Audio: the architectural choice

I will not decide this silently. The three options:

**(a) Reuse the Capture contracts as-is.** PDF becomes `AttachmentKind.PDF`; Audio becomes existing
Capture Voice. *For:* zero schema work, no v18, both already have import/managed-storage/viewer or
recorder/player, and `capture.<type>` Page registration happens automatically today. *Against:* the
palette items become aliases of existing features rather than distinct products; Voice is
multi-clip and inbox-shaped, which may not be what a mind-map "Audio" block should be; and the
`MapAddKind.PDF`/`AttachmentKind.PDF` duplication that `PDF_FEATURE_FOUNDATION.md` warns about
becomes permanent.

**(b) Specialize the Capture contracts.** Keep `CaptureItem` + companion document, add
task-scoped behaviour and a distinct `typeKey`. *For:* one record per file, reuses import,
managed storage and FileProvider, still no schema change if the companion document suffices, and
it preserves the "don't create two records for one imported file" rule. *Against:* needs care so a
specialized row still renders correctly in Inbox, Activity and Knowledge.

**(c) New Page-facing document contracts.** New tables, new owner keys, v18 and v19. *For:*
cleanest product semantics. *Against:* two migrations, two agents, one queue — the highest-risk
option, and it abandons working import/permission/viewer code.

**My recommendation: (b) for both, with a caveat.** It satisfies the no-duplicate-records rule,
needs at most one reserved version rather than two, and keeps PDF inside the attachment system that
already renders PDFs. The caveat is that Audio and Voice must be explicitly distinguished *as
products* before implementation — if mind-map Audio is "one clip attached to a task" while Capture
Voice is "a multi-clip inbox recording", that difference belongs in a written contract first, or
Virlin ends up with two incompatible audio models, which `FEATURE_REGISTRY.md` already flags as the
risk. This is a product decision and should be yours, not the implementing agent's.

---

## J. Recommended documentation corrections

### J.1 Must fix before checkpoint

1. **Resolve the golden baselines.** Either re-record with your explicit approval (they are stale
   against intended UI) or record the 17 failures as a known, dated baseline in
   `TESTING_AND_RELEASE.md`. Do not leave four agents facing an unexplained red verify.
2. **Correct `TESTING_AND_RELEASE.md`:** 1053 tests, and state both numbers — 1 failure under
   `testDebugUnitTest`, 18 under `verifyRoborazziDebug` — with the reason they differ.
3. **Fix `CLAUDE.md:308`** to v17 including `task_page_blocks`.
4. **Fix `work_streams` → `workstreams`** in `DATA_AND_STORAGE.md`.
5. **Decide the fate of `videos/`, `tmp-*`, `.tmp/`, `maestro/`, `.mcp.json`** and extend
   `.gitignore` before any commit.

### J.2 Should fix before parallel development

6. **Add a "read this first" block to `CLAUDE.md`, `CURSOR.md` and `GLM.md`** pointing at
   `PROJECT_DOCUMENTATION_INDEX.md`, and correct `CURSOR.md`/`GLM.md` so they no longer present
   `DEVELOPMENT_STATUS.md` as current status.
7. **Document `stream_detail/{id}`** in `NAVIGATION_AND_FEATURES.md` as obsolete, or delete the
   route and `StreamDetailScreen.kt` in a deliberate change.
8. **State the bottom-bar/Orb rule per route**, and decide whether `task_page/` should match
   `notes/`.
9. **Replace the `TaskPageBlockRegistry` reference** in `TASK_PAGE_FOUNDATION.md` with the four
   real `when` sites, or introduce an actual registry so the document becomes true.
10. **Reconcile the mutation rule** between `CLAUDE.md` and `ARCHITECTURE.md`, and decide whether
    `TaskPageActions` should join the `VirlinActions` facade.
11. **Expand the shared-roots list** in `AGENT_COORDINATION.md` with the seven collision points in
    section I, and create `docs/handoffs/`.
12. **Name the integration owner** in `DATABASE_MIGRATION_QUEUE.md`.

### J.3 Optional improvements

13. Give Prompt, Link, File and Voice one authoritative spec each; add a "spec document" column to
    `FEATURE_REGISTRY.md`.
14. Delete the orphaned `app/src/test/screenshots/task_detail.png`.
15. Rename `MapAddKind.TASK` to `PROMPT` (UI-only enum, not persisted — safe).
16. Note the eager `task.note` registration consequence in `TASK_PAGE_FOUNDATION.md`.
17. Document a DB-snapshot step before destructive on-device testing.

---

## K. Suggested next execution plan

1. **Decide the five J.1 items** — especially the goldens, which need your approval either way.
2. **Extend `.gitignore`**, then confirm `git status` shows only intended files.
3. **Apply the J.1 documentation corrections** (docs only; no production code).
4. **Re-run** `compileDebugKotlin`, `testDebugUnitTest` and `verifyRoborazziDebug`; record the
   agreed baseline numbers in `TESTING_AND_RELEASE.md`.
5. **Install on the emulator and smoke-test** Now, a project, the green hierarchy, a Task Page,
   Notes, and one legacy Capture Note — the surfaces most affected by recent work.
6. **Checkpoint:** commit the live state on `integration` with a message recording the exact test
   numbers. This is the first safe restore point the project will have had.
7. **Apply J.2**, then commit again.
8. **Write the PDF and Audio product contracts** and make the (a)/(b)/(c) decision. Reserve **v18**
   in the queue only if the decision needs schema work, and assign it to exactly one agent.
9. **Create worktrees** `codex/pdf` and `claude/audio` from `integration`.
10. **Implement separately**, each agent touching only owned files and raising
    `docs/handoffs/<FEATURE>_WIRING_REQUEST.md` for shared roots.
11. **Merge one branch at a time** into `integration`, compiling and running the full suite plus
    golden verification after each.
12. **Combined emulator verification** on a single APK: both palette items, Page ordering with both
    new block types present, and an upgrade over existing app data rather than a clean install.

---

## Audit closing confirmations

1. **No production code was changed.** ✓
2. **No database or schema change was made.** ✓ (still v17; no migration added)
3. **No existing Codex documentation was overwritten.** ✓ (this is a new file; `CODEX_CHANGES.md`
   untouched)
4. **No Git cleanup, reset, stash, checkout, branch or commit was performed.** ✓ (only
   `git status`, `git log`, `git ls-files`, `git check-ignore` — all read-only)
5. **No golden screenshot was updated** and no test file was modified. ✓
6. **Safe to checkpoint:** yes, once J.1 item 5 (ignore rules) is applied — otherwise ~380 MB of
   disposable artifacts enter history permanently.
7. **Safe to begin parallel PDF and Audio work:** not yet. Complete J.1 and J.2, checkpoint, and
   make the PDF/Audio contract decision in section I first.

# Codex Change Log

This is the append-only handoff log for changes made by Codex in this working tree.
It exists so Claude, Codex, and the human developer can see what Codex changed without
having to infer intent from a large, shared diff.

## Coordination rules

- Codex adds one dated entry for every source, configuration, documentation, or asset change.
- Existing entries are not rewritten or removed; corrections are added as a new entry.
- Each entry identifies the exact files touched, the requested outcome, behavior impact, and verification performed.
- Before editing a file, check this log and `git status` for concurrent work.
- This file records changes; it does not claim ownership of a file or replace Git history.
- Claude should use a separate `CLAUDE_CHANGES.md` log if it needs to record its own work. Keeping agent logs separate avoids both agents appending to the same file concurrently.

---

## 2026-09-30 — Audit corrections and parallel-work baseline hardening

- Routed `CLAUDE.md`, `CURSOR.md`, and `GLM.md` through the authoritative documentation index and coordination protocol.
- Corrected stale documentation: Room schema v17, `workstreams` table name, Task Page's actual hybrid dispatch structure, shell ownership, and the unreachable compatibility `stream_detail/{id}` route.
- Added authoritative Prompt, Link, File/Attachment, and Voice feature contracts plus the handoff directory guide.
- Ignored large local-only video, temporary comparison, acceptance, and MCP configuration artifacts without deleting any user data; `maestro/` remains visible because its ownership must be decided deliberately.
- Reviewed all current Roborazzi failures and recorded per-group findings in `docs/GOLDEN_BASELINE_REVIEW.md`; no golden image was recorded or replaced.
- Fixed the Now/scaffold screenshot harnesses so they hydrate the in-memory graph before capture. Production startup behavior is unchanged.
- Updated the Capture boundary test for canonical `example.com` to `https://example.com` behavior and changed stale validation copy to `Enter a valid web link`; unsafe and non-web schemes remain rejected.

### Final audit blockers resolved

- Refreshed the ignored Graphify index with `graphify update .` and verified discovery of
  `TaskPageActions`, `TaskPageScreen`, and `TaskPageTypeKeys` using an exact-symbol bounded query.
- Added the token-efficient Graphify workflow to `PROJECT_DOCUMENTATION_INDEX.md`, including the
  high-collision query warning and targeted-source verification rule.
- Declared `.cursor/`, `.glm/`, and the already tracked `maestro/` flows as portable project
  infrastructure; Graphify output and `.mcp.json` remain local-only.
- Corrected `TaskPageScreen.kt` KDoc so it no longer claims the label registry is the only
  type-specific extension seam.

---

## 2026-09-29 — Rename the mind-map add option from Task to Prompt

**Requested by:** User

**Status:** Completed

**Files changed:**

- `app/src/main/java/com/virlin/app/ui/map/MapAddPalette.kt`

**Change:**

- Changed the visible `MapAddKind.TASK` label from `Task` to `Prompt` in the “Add to [task]” palette.

**Behavior impact:**

- Presentation-only change.
- Selecting the renamed option still follows the existing `MapAddKind.TASK` path and creates a child task.
- No enum names, persistence models, routes, or domain behavior were changed.

**Verification:**

- Confirmed `MapAddPalette.kt` contains `TASK("Prompt")` at line 60.
- No build or test suite was run because this was a string-only label edit.

---

## 2026-09-29 — Add parallel-agent change documentation

**Requested by:** User

**Status:** Completed

**Files changed:**

- `CODEX_CHANGES.md`
- `CLAUDE.md`

**Change:**

- Created this append-only Codex handoff log.
- Added a parallel-agent handoff rule to `CLAUDE.md` that directs Claude to read this log and use a separate `CLAUDE_CHANGES.md` for its own edits.

**Behavior impact:**

- Documentation only; application behavior is unchanged.
- Separate per-agent logs reduce the chance of Claude and Codex editing the same changelog concurrently.

**Verification:**

- Confirmed the handoff section is present in `CLAUDE.md` immediately before Rule 1.

---

## 2026-09-29 — Implement the Prompt paste-and-save workspace

**Requested by:** User, following approval of the Prompt/Code/Response mockups

**Status:** Completed

**Files changed:**

- `app/src/main/java/com/virlin/app/domain/model/PromptModels.kt`
- `app/src/main/java/com/virlin/app/domain/note/NotePlainTextSerializer.kt`
- `app/src/main/java/com/virlin/app/domain/prompt/PromptDocumentCodec.kt`
- `app/src/main/java/com/virlin/app/domain/prompt/PromptContentParser.kt` (new)
- `app/src/main/java/com/virlin/app/domain/action/VirlinActions.kt`
- `app/src/main/java/com/virlin/app/domain/action/DefaultVirlinActions.kt`
- `app/src/main/java/com/virlin/app/domain/action/PromptActions.kt`
- `app/src/main/java/com/virlin/app/data/db/VirlinMappers.kt`
- `app/src/main/java/com/virlin/app/ui/prompt/PromptEditorViewModel.kt`
- `app/src/main/java/com/virlin/app/ui/prompt/PromptEditorScreen.kt`
- `app/src/main/java/com/virlin/app/ui/navigation/VirlinApp.kt`
- `app/src/main/java/com/virlin/app/ui/map/ProjectMapScreen.kt`
- `app/src/main/java/com/virlin/app/ui/map/MapAddPalette.kt`
- `app/src/test/java/com/virlin/app/domain/PromptDocumentTest.kt`

**Change:**

- Replaced the old block-oriented Prompt editor UI with the approved paste-and-save workspace.
- Added Prompt and Code modes, a manual language picker, deterministic language suggestions, monospaced code editing, and lightweight syntax highlighting.
- Added a user-pasted Response flow. Markdown fenced code is displayed separately from prose, with Copy All and per-code-block Copy controls.
- The original prompt/code and response strings are persisted exactly; parsing and highlighting are display-only projections.
- Extended the existing prompt JSON payload to version 2 while retaining its legacy `blocks` field. This is backward compatible and requires no Room schema migration.
- Legacy prompt blocks project into source text when first opened/saved in the new editor.
- Wired the mind-map `Prompt` palette option to open the new editor with the selected task as its capture context. It no longer opens the old Add subtask dialog.

**Verification:**

- `compileDebugKotlin`: passed.
- Targeted `PromptDocumentTest`: 9 tests passed.
- Full JVM suite: 974 tests executed; 973 passed. The single failure is the unrelated existing `CaptureBoundaryTest.types_are_explicit_and_payloads_raw`, whose expectation that `developer.android.com` is invalid conflicts with the current domain-shaped URL normalization behavior.
- `assembleDebug`: passed.
- Installed the APK on `emulator-5554` and manually verified palette → Prompt navigation, selected-task context, Prompt/Code switch, language picker, source editor, Add response editor, and save affordance.

---

## 2026-09-30 — Rename the mind-map Web link option to Link

**Requested by:** User

**Status:** Completed

**Files changed:**

- `app/src/main/java/com/virlin/app/ui/map/MapAddPalette.kt`
- `CODEX_CHANGES.md`

**Change:**

- Renamed the visible palette label from `Web link` to `Link`.
- Renamed the internal `MapAddKind.WEB_LINK` enum entry to `MapAddKind.LINK`.
- Updated palette row composition and icon mapping to use the new internal name.

**Behavior impact:**

- The palette now consistently identifies this future feature as `Link` in both UI and code.
- The option remains disabled with `Not yet`; no link editor behavior was added in this rename pass.
- Existing Capture Inbox `CaptureType.LINK` and its current Link editor are untouched.

**Verification:**

- Searched production and test code to confirm no `MapAddKind.WEB_LINK` or `Web link` palette references remain.

## 2026-09-30 — Full-page Link workspace

Implemented the approved Link feature as a self-contained full-page Capture workspace. It does **not** create, render, or connect a node/thumbnail on the mind-map canvas.

### Behavior

- Enabled the `Link` choice in the project map Add palette; it navigates away to the full-page editor and carries only the project context (`Attached to · <project>`).
- Added explicit create/save, saved detail, and minimal pencil-edit states.
- Validates and canonicalizes HTTP(S) and domain-shaped links through the existing `LinkUrl` boundary; non-web schemes remain rejected.
- Added optional preview UI. YouTube watch/short/embed/youtu.be URLs deterministically derive the official `i.ytimg.com` thumbnail. Other websites/video providers receive a graceful provider card when no safe deterministic thumbnail URL is available.
- Added provider recognition for YouTube, Instagram, direct video files, and ordinary websites.
- Added optional playback start/end fields. YouTube open URLs receive the saved `t=<seconds>s` start parameter; direct video files use a media fragment. Instagram is preserved unchanged because its external player does not accept a reliable timestamp contract. End time is stored and shown, but the UI explicitly avoids promising that an external provider will stop playback.
- Added Copy Link, Open Link, Delete/archive, Save changes, show-preview toggle, optional title/note, validation feedback, and save confirmation.
- Added `INTERNET` permission solely for user-enabled remote preview thumbnail loading. Opening links still uses an explicit `ACTION_VIEW` user action and does not hard-code a browser package.

### Persistence and compatibility

- Added `LinkDocument` / `LinkDocumentCodec` in `domain/capture/LinkDocument.kt`.
- Preview and playback settings are stored backward-compatibly inside the existing `CaptureItem.content`; no Room table or schema migration was added.
- Legacy Link captures whose `content` is a plain note still decode correctly.
- Inbox detail and capture-to-task conversion decode the Link payload so JSON metadata is never presented as user content.

### Files changed by Codex for this feature

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/virlin/app/domain/capture/LinkDocument.kt` (new)
- `app/src/main/java/com/virlin/app/domain/action/CaptureActions.kt`
- `app/src/main/java/com/virlin/app/ui/link/LinkEditorViewModel.kt`
- `app/src/main/java/com/virlin/app/ui/link/LinkEditorScreen.kt`
- `app/src/main/java/com/virlin/app/ui/map/MapAddPalette.kt`
- `app/src/main/java/com/virlin/app/ui/map/ProjectMapScreen.kt`
- `app/src/main/java/com/virlin/app/ui/navigation/VirlinApp.kt`
- `app/src/main/java/com/virlin/app/ui/agent/capture/AgentCaptureArea.kt`
- `app/src/test/java/com/virlin/app/domain/LinkCaptureTest.kt`

### Verification

- `:app:testDebugUnitTest --tests com.virlin.app.domain.LinkCaptureTest`: **17 tests passed, 0 failures**.
- `:app:assembleDebug`: **passed**.
- Installed the debug APK on `emulator-5554` (Pixel 8 AVD).

---

## 2026-09-30 — Safe inline YouTube segment playback

**Requested by:** User

**Status:** Completed

**Files changed:**

- `app/src/main/java/com/virlin/app/domain/capture/LinkDocument.kt`
- `app/src/main/java/com/virlin/app/ui/link/LinkEditorScreen.kt`
- `app/src/main/java/com/virlin/app/ui/link/YouTubeInlinePlayer.kt` (new)
- `app/src/test/java/com/virlin/app/domain/LinkCaptureTest.kt`
- `CODEX_CHANGES.md`

**Change:**

- Saved YouTube links now show a Play control over their thumbnail.
- Play replaces the thumbnail with YouTube's official IFrame player inside the same Link page.
- `cueVideoById` receives the persisted start and end seconds. It cues without autoplay; the user initiates playback.
- Videos that disallow embedding retain the external `Open link` fallback.
- Instagram remains preview/external-open because it has no reliable start/end segment contract.

**Safety boundary:**

- Generated HTML accepts only a strictly validated YouTube video ID and integer time values.
- JavaScript is enabled only for this dedicated official IFrame player; no JavaScript bridge is exposed.
- File/content access, popups, multiple windows, and mixed content are disabled.
- Safe Browsing is enabled, off-provider main-frame navigation is blocked, and playback requires a user gesture.
- The WebView follows lifecycle pause/resume and is stopped, blanked, and destroyed when removed.

**Verification:**

- Targeted `LinkCaptureTest`: **20 tests passed, 0 failures**, including YouTube host/ID validation, exact segment cue generation, no-autoplay, and invalid-end omission.
- `:app:assembleDebug`: passed.
- Installed the final APK on `emulator-5554`. A saved YouTube Link shows `Play video here` and transitions into the in-page player; the offline emulator follows the loading/fallback path without autoplaying or navigating away.
- Live emulator verification confirmed: Add palette shows Link enabled; tapping it opens the full-page Link editor; project context reads `Attached to · DragTest`; no Link node or map thumbnail is created.

---

## 2026-09-30 — Foundational task Page and automatic Add integration

**Status:** Implemented. The durable contributor contract is in
`docs/TASK_PAGE_FOUNDATION.md`.

- Added schema v17 `task_page_blocks`: ordered references only; canonical To-do, Note, Prompt,
  Link, and future content remain the source of truth.
- Added open string type keys, repository support, migration 16→17, exported schema 17,
  idempotent reconciliation, dense reorder, same-project move, and safe independent duplication
  for capture-backed blocks.
- Every task-scoped capture now registers automatically as `capture.<type>` in the same creation
  transaction. Unknown future keys survive and render through a fallback rather than crashing.
- The mind-map `Page` action opens the full-screen mixed document. Edit routes to the canonical
  editor; Organize persists order and offers Move/Duplicate destination selection.
- Add To-do/Note register immediately. Prompt already carries task context. Link now carries task
  context instead of project-only context. No Page block is rendered on the map canvas.
- Aggregate To-do/Note cross-task moves are refused until a content-merge policy exists; the UI
  never silently aliases task-owned content.

**Primary files:** `domain/model/TaskPageModels.kt`, `domain/action/TaskPageActions.kt`,
`ui/page/TaskPageScreen.kt`, `docs/TASK_PAGE_FOUNDATION.md`, plus v17 data/navigation wiring.

**Verification:** `compileDebugKotlin` and `assembleDebug` passed. Targeted Page + Link suite:
**30 passed**. Full suite: **1046 tests, 1 pre-existing unrelated failure** in
`CaptureBoundaryTest.types_are_explicit_and_payloads_raw`; no Page test failed.
The focused on-device `VirlinMigrationTest` suite passed all **15 tests**, including 16→17 and a
fresh v17 install. The debug APK was installed on `emulator-5554`; Virlin launched normally and
the emulator was left running for inspection.

---

## 2026-09-30 — Notes UI cleanup: redesigned workspace is canonical, legacy remains readable

**Status:** Implemented non-destructively. Full compatibility policy:
`docs/NOTE_SYSTEM_CLEANUP.md`.

- Fixed the task Note owner-key mismatch without a schema migration. The existing persisted
  `task-<taskId>` form is now constructed centrally by `TaskPageTypeKeys.noteOwner`; Notes routes
  and Page reconciliation share it.
- ORB Capture now labels the action `Note · Open your notes workspace` and opens `ui/notes`, not
  the old Capture Text Note editor.
- ORB context is explicit: Task Detail, Task Page, and To-do open that task's Note and ensure its
  `task.note` Page block; project routes open project Notes; all other routes open global Notes.
  The resolver never guesses a focused task.
- Apps Pages, Inbox's new text capture button, project document creation, Knowledge Add, and
  Add-to-task now open the redesigned owner-scoped Notes workspace.
- Removed the bare `text_note` new-draft destination. `text_note/{captureId}` remains solely for
  existing `CaptureType.NOTE` rows opened from Inbox, Activity, or Knowledge.
- No capture, `note_documents`, or existing `virlin_notes` row was migrated, merged, or deleted.
  Prompt's shared `NoteBlock` model remains intact.
- Natural-language `Save note ...` remains an explicit quick Inbox-capture command pending a
  separate product decision; it is not a visual legacy-editor entry point.

**Verification:** production and unit-test Kotlin compilation passed. Focused Page, redesigned
Notes, and ORB launcher suite: **51 tests passed**. Full suite: **1,050 tests, 1 pre-existing
unrelated failure** in `CaptureBoundaryTest.types_are_explicit_and_payloads_raw`. Final
`:app:assembleDebug` passed. APK installation succeeded on `emulator-5554`; live navigation
confirmed ORB → Capture shows `Note · Open your notes workspace`, and tapping it opens the
redesigned `Notes · Note` workspace with `No changes`, not the legacy `Text Note / Global · Inbox`
screen. No content was entered during verification.
# 2026-09-30 — Green task hierarchy cleanup

- Removed the legacy cream `TaskDetailScreen`; task opening now resolves into the canonical green
  WorkStream hierarchy or the green project task index.
- Kept `task_detail/{id}` as a compatibility redirect so mind-map, Agent, Pulse, Activity and restored
  navigation callers do not break.
- Added Focus and Delegate to the green task overflow menu using the existing domain actions and
  switch-confirmation/delegation dialogs; terminal tasks hide these actions.
- Removed the redundant **Open details** action and obsolete legacy-screen test hooks.
- Added pure navigation resolver coverage and documented the architecture in
  `docs/TASK_HIERARCHY_CLEANUP.md`.
# 2026-09-30 — Note and hierarchy hardening follow-up

- Removed the nullable/bare legacy Text Note route API; `textNoteRoute` now requires a non-blank
  existing capture ID and can only produce the registered compatibility destination.
- Strengthened task Note reconciliation coverage to save through the same `VirlinActions.saveNoteDoc`
  boundary used by the redesigned editor before Page reconciliation.
- Added explicit corrupt-parent-cycle coverage for the task hierarchy destination resolver.
- App and Android-test sources compile; focused Note and task-routing tests pass.
# 2026-09-30 — PDF palette foundation

- Renamed the mind-map Add palette intent from `TOPIC_LINK` / “Topic link” to `PDF` / “PDF”.
- Replaced the topic-tree icon with the outlined PDF document icon.
- Kept PDF disabled as **Not yet**; no import, editing, persistence or Page integration was added.
- Documented the boundary and future integration rules in `docs/PDF_FEATURE_FOUNDATION.md`.
# 2026-09-30 — Project architecture and parallel-agent audit

- Audited the live source tree, build configuration, routes, Room v17 schema/migrations, managed
  storage, canonical/compatibility surfaces, test inventory and dirty-worktree risk.
- Added the authoritative documentation index and separate current-state specifications for
  architecture, data/storage, navigation/features, testing/release and agent coordination.
- Added the feature registry, migration reservation queue and standard handoff template.
- Marked the older development-status file as a historical pass log and linked the new index from
  README.
- Parallel feature work remains intentionally blocked until the live dirty state is reviewed,
  verified and checkpointed into an integration branch.

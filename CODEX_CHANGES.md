# Codex Change Log

## 2026-10-05 — Workstream/task/subtask permanent deletion

- Extended the atomic deletion/cleanup receipt to branch roots, preserving their project, parent/siblings, shared tags, palette/icon and project notes. Existing project receipts remain backward-compatible.
- Added exact-name confirmation and destructive control rows for workstreams, tasks and nested/terminal subtasks; project overview and standalone menus are wired. Existing cancellation is unchanged.
- Iterative cycle-safe traversal removes arbitrary-depth descendants. Clears surviving active-task pointers, stops legacy tracking tied to deleted work, removes task events on parent streams and prunes map-color overrides without wiping project appearance.
- Added a 5,000-level regression, Room branch ownership/rollback/recovery checks and controls UI regressions. Documented in `docs/HIERARCHY_DELETION_2026-10-05.md`. No real user item deleted, data reset, migration, commit or push.
- Verification: full regression suite passed 1,301 tests. After the final retained-conversion-reference safeguard, debug assembly and all 30 focused deletion/control checks passed; whitespace check passed. Retained source knowledge is kept, with only its stale deleted-task pointer cleared.

## 2026-10-05 — Permanent project deletion

- Audited every owned Room table and managed-storage root; replaced the Project management deletion placeholder with a separate typed-name permanent-deletion confirmation.
- Deletes hierarchy, captures/documents, tags, rich notes, map previews, execution history and preferences in one database transaction. Durable cleanup receipts protect interrupted file/platform cleanup and retry at startup; unrelated projects and original/exported external files remain untouched.
- Cleans exact managed attachment, voice, icon and preview directories, alarms/notifications and appearance/timer settings. Serialized map appearance and delayed-autosave fences prevent resurrection.
- Added isolated in-memory, Room cascade/rollback/retry/path-safety and Compose confirmation regressions. See `docs/PROJECT_DELETION_2026-10-05.md` for ownership and legacy-export boundaries. No existing user project was deleted for testing; no migration/reset/commit/push.
- Verification: full regression run passed 1,296 tests with zero failures/errors/skips. Final debug assembly and all seven deletion-focused tests passed after the timer-key correction and destructive-row styling; CRLF-aware diff check passed.

## 2026-10-05 — Circular clearance across project navigation

- Replaced rectangular project-row reservations with full-width card surfaces and local circular orb cutouts. Directory/filter cards, overview workstreams/knowledge previews/disclosures, shared task/subtask cards and task-index rows use protected inner content bands; Activity uses circle-aware content clearance.
- Knowledge retains individual text/menu/selection clearance and equal-width grid columns. Existing footer/Add Task reservations, callbacks and data remain unchanged. Mind map and unrelated app screens remain excluded.

## 2026-10-05 — Local circular Knowledge orb clearance

- Restored equal-width Knowledge gallery pairs. Cards keep their full measurement and use a local circular cutout around the orb plus 8dp, rather than shrinking the whole row.
- Individual text bands and menu/selection controls reserve circle-chord clearance only on intersection. Other project screens retain existing protection; storage and callbacks are unchanged.
- Added circle geometry and equal-width/menu interaction regression tests.

## 2026-10-05 — Project navigation orb coverage

- Extended dynamic orb clearance to project directory cards and filtered results, shared workstream/task/subtask rows (including standalone tasks), project task index, Knowledge gallery rows and Activity timeline rows.
- Preserved bottom Add Task/Subtask clearance, footer, existing controls and data. Mind map and unrelated app screens intentionally excluded. Coverage details: `docs/PROJECT_ORB_COVERAGE_2026-10-05.md`.

## 2026-10-05 — Dynamic project-page orb clearance

- Published the real shell orb slot bounds to page content. Project overview rows reserve end clearance plus an 8dp gap only while intersecting the orb; rows above or below it keep full width.
- Applied clearance to workstream/standalone cards, disclosure headers, knowledge previews, empty labels and View all actions. Header labels can wrap to two lines rather than crowding the Add/arrow targets.
- No orb repositioning, data changes or task-control changes. Existing footer and Add Task reservations remain intact. Added geometry and measured-layout regression checks.

## 2026-10-05 — Project overview section disclosures

- Standalone tasks and Project knowledge start collapsed with separate Add buttons and accessible right-arrow disclosures. Each section opens independently; existing task cards and latest-knowledge/View all actions are preserved.
- Header icon and background shades derive from the live project appearance palette. Execution status colors, workstreams, footer and storage are unchanged.
- Added disclosure callback/color regression checks and updated the existing project scrolling regression to expand the standalone list explicitly.

## 2026-10-05 — Mind-map Add menu cleanup

- Removed the unsupported Sticker and Illustration choices and their obsolete dimmed-options message from the shared mind-map Add palette. The eight supported choices, their routes, selection behavior and close action are unchanged; no storage or map-data changes.
- Added a UI regression check for option removal and all remaining choice callbacks.

## 2026-10-05 — Standalone task parity

- Standalone project tasks reuse workstream TaskRow visuals, nested-task navigation and shared work controls. The add form exposes execution inheritance and sibling placement.
- Existing IDs, null workstream ownership, sessions and descendants are preserved. Moves use validated project branch placement, not a synthetic workstream mutation.
- Full regression suite: 1,260 tests passed; final targeted navigation/projection checks also passed. See `docs/STANDALONE_TASK_PARITY_2026-10-05.md`.

## 2026-10-05 — Popup system-navigation/device compatibility

- Audited custom dialogs, Material alerts/sheets and anchored options after the Galaxy S21 report. Added `docs/POPUP_DEVICE_COMPATIBILITY_AUDIT_2026-10-05.md` with cause, coverage, fixes and physical-device acceptance steps.
- Added `SafeDialog.kt` and routed all direct custom UI dialog windows through it. Platform-constrained measurement plus native/Compose safe inset union avoids the configuration-height overflow reproduced on a compact three-button emulator. Keyboard resize, Back/outside policies and existing callbacks preserved.
- Settings minimum height removed; actions remain separate from scrolling categories. Long custom forms, task execution/move sheets, activity date selection and block transfer choices are scrollable. No database/engine changes or data clearing.
- Added `PopupViewportUiTest.kt`: API 28/34 compact, large-font, landscape and long-form scroll checks. Final full debug unit/Compose suite passed: 1,258 tests, zero failures/errors. Work-controls execution disclosure avoids size animation to prevent repeated window measurement; options/callbacks retained.
- Live compact three-button settings check passed after the platform-measurement fix; Back/Home/Recents remained available. Native display/density and gesture navigation restored. Latest APK installed with `-r` without clearing data; Samsung physical retest remains required. `assembleDebug` and `lintDebug` passed; lint retains 127 existing warnings and 12 informational issues, no errors. Whitespace check passed. No commit or push.

## 2026-10-05 — Approved minimal task creation styling

- New `TaskCreationCard.kt`: reusable text-first form, quiet medium-weight 16sp heading, small project-accent add emblem, muted parent name, soft 18sp name input, compact optional-control slot, close/cancel and validated trimmed creation. No automatic execution.
- `VirlinStreamTasks.kt`: placement and execution become compact dropdown selectors; correct Add task/Add subtask wording. Existing in-content centered responsive overlay and data callbacks retained.
- `HierarchyComponents.kt` / `HierarchyScreens.kt`: standalone project task creation uses the same card in a footer-safe in-content overlay. Existing optional effort estimate remains behind a compact disclosure; only supported options are offered.
- `MapStructureEditor.kt` / `ProjectMapScreen.kt`: mind-map child creation uses the shared card with project palette and original inherited/appended placement semantics. Existing map dialog hosting is retained.
- `WorkstreamControlsDialog.kt`: add-child step uses the shared form while rename/other controls remain unchanged. Existing parent-inherited child creation callback retained.
- `AddTaskDialogUiTest.kt`: added selection-menu regression for explicit external mode and at-beginning placement, preserving existing compact layout/blank validation/cancel checks. Full verification recorded after completion; no data clearing, engine changes or remote writes.
- Verification: full debug unit/Compose suite passed (1,252 tests, zero failures/errors); `lintDebug` and `assembleDebug` succeeded; whitespace check passed. APK installed in place without clearing data. Live emulator check confirmed the quiet heading, name-first layout, compact selectors and visible footer on the workstream task form; no test task was saved. Existing map/control dialog hosting remains unchanged, so this is not a claim of universal device/keyboard certification.

## 2026-10-05 — Centered responsive task creation

- `VirlinStreamTasks.kt`: task/subtask creation uses a centered, width-bounded, scrollable in-content overlay rather than a bottom-sheet window. The viewport excludes the real app footer. Back/scrim/cancel dismiss, title validation and existing placement/execution/save callbacks are preserved. Draft fields use saved state. No creation-engine changes.
- `VirlinApp.kt`: root Scaffold respects IME padding so the actual navigation footer remains above the keyboard. Child IME padding consumes inherited insets, avoiding double application. System navigation insets remain managed by the existing shell.
- `AddTaskDialogUiTest.kt`: compact viewport footer separation, blank validation, trimmed save with original defaults and cancel-without-save checks. These tests and task/subtask scroll regressions passed (four tests); final shell build verification follows. No reset, commit or push.

## 2026-10-05 — Project-colored hierarchy timer

- `HierarchyTimer.kt`: digit color reads the live LocalProjectPalette main accent, keeping existing green only when no custom palette exists. Applies to task/subtask header focused and external display without changing timing, rotation, animation, labels or semantic execution colors.
- `HierarchyTimerUiTest.kt`: verifies gold-to-blue palette updates with unchanged elapsed time. Color regression uses the exact render color exposed in timer semantics because Robolectric window capture timed out. Existing hour-boundary and category-rotation tests remain.
- Verification: all three timer Compose tests and debug APK assembly passed; whitespace check passed. Emulator updated in place without clearing data. No commit or push.

## 2026-10-05 — Task/subtask list-first header reveal

- `VirlinStreamTasks.kt`: recursive task lists now use the same ProjectHeaderScrollConnection as project overview. Downward gestures scroll children back to the beginning before revealing hidden details. Existing upward collapse, feather gradient, timers, filters, buttons and navigation remain unchanged. Added a details test tag only.
- `ProjectHeaderScrollConnection.kt`: documentation now reflects use at all hierarchy levels; behavior unchanged.
- New `TaskListCollapseUiTest.kt`: root and two-level-nested lists with 30 entries verify collapse, list-first reverse scrolling and eventual header reveal. Project scroll regressions also rerun. No data or execution-engine changes; rollback is limited to the task-page connection wiring.
- Verification: five targeted unit/Compose tests and debug assembly passed; whitespace check passed. Pixel_8 updated with data-preserving APK installation. No commit or push.

## 2026-10-05 — Project list-first header reveal

- `VirlinProjectExperience.kt` and new `ProjectHeaderScrollConnection.kt`: collapse the details before upward list scrolling, but allow the overview list to consume downward movement first. Reveal hidden details only from remaining downward movement after the list reaches its top. Pinned project title/Workstreams heading and existing feather gradient remain unchanged. Other tabs and task/subtask scrolling are untouched.
- `ProjectHeaderScrollConnectionTest.kt`: tests bounded collapse, list-consumed downward movement, partial leftover reveal, fully expanded boundary, disabled tabs and zero-height behavior.
- `ProjectCollapseUiTest.kt`: updated the UI journey to verify header stays collapsed away from list top and reveals after returning to the beginning. No migration, execution changes, reset, commit or push. Rollback is limited to this connection and its project-page wiring.
- Verification: all three targeted scroll/unit/Compose tests and debug assembly passed. Zero-height consumption explicitly returns Offset.Zero. Whitespace diff check passed; Pixel_8 updated in place without clearing user data.

## 2026-10-05 — Execution legend mockup visual correction

- `VirlinProjectPage.kt`: restored the mockup's mint circular pulse emblem and compact four semantic chips with distinct circular count badges, reduced chip typography/padding, and violet Multi-run. Retains user-requested right-facing arrow, arrow-only disclosure, wrapping at larger font sizes, and all original summary counts.
- `ExecutionDisclosureTest.kt`: checks pulse presence and accessible combined chip descriptions alongside expand/collapse and inert-label behavior. Targeted Compose test and debug assembly passed; whitespace check passed. In-place emulator update preserves data. No engine/parent UI changes or remote writes.

## 2026-10-05 — Compact execution disclosure

- `VirlinProjectPage.kt`: replaced the tall Execution signals legend and tagline with a 44dp compact Execution row, existing live active count, and right-facing arrow button. Only the arrow expands/collapses read-only Focus/Running/Multi-run/Review chips. FlowRow handles narrow widths; expansion is saved per project. Active totals remain human + external, never double-counting multi-run summaries.
- `VirlinProjectExperience.kt`: supplies existing workstream-derived Multi-run and Review summary counts to the shared legend. No parent page, execution engine, filters or persistence changes.
- `ExecutionDisclosureTest.kt`: verifies initial collapsed state, absence of tagline, inert label tap, all four counts after arrow click and collapse after second click.
- Debug build and targeted Compose regression passed; whitespace diff check passed. APK installed in place on Pixel_8 without clearing data. No commit or push.

## 2026-10-05 — Correct selected-tag styling to approved mockup

- `FilteredProjectTasks.kt`: replaced default purple Material filter chips and outlined cards with the approved full-width mint/gray segmented control, white borderless rounded cards, compact inline semantic status badges, explicit directory typography and muted ancestry/timing. Project icon/count/down-chevron headers enclose the first result; subsequent results remain separately virtualized cards. Active overdue external work is labelled RUNNING, not mistaken for stopped review work.
- All directory styling, filtering projection, stored data, task navigation and execution behavior remain unchanged. Group collapse and toggle semantics retained.
- Targeted regression/Compose tests (eight) and debug APK assembly passed after the final group layout change. Installed as an in-place update without clearing emulator data. No commit/push.

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
# 2026-10-01 - Isolated PDF workspace foundation

- Created `codex/pdf-workspace` in a separate worktree while Claude owns Audio changes.
- Specialized the existing managed Attachment contract; no new entity, table or migration.
- Added strict PDF import, dashboard, mobile viewer/page overlay, page organization, normalized crop,
  rasterized PDF extraction and PNG conversion with task-context Page registration.
- Kept Annotation and OCR honest capability gates pending a durable sidecar contract and approved
  bundled offline OCR dependency.
- Added `PDF_WORKSPACE_FEATURE.md` and a shared-file wiring request; no active Audio/shared root was
  edited.
- Verification and final commit details will be appended after the complete test pass.
- Verification: `compileDebugKotlin`, focused PDF tests, all 1,057 JVM tests,
  `compileDebugAndroidTestKotlin`, and `assembleDebug` passed. `verifyRoborazziDebug` reported exactly
  the already documented 17 visual differences; no golden was changed.

# 2026-10-01 - PDF and Audio integration wiring

- Combined the independently verified Audio and PDF feature commits on
  `codex/pdf-audio-integration`; the feature branches remain available as isolated provenance.
- Registered task-scoped new-PDF and existing-capture PDF workspace destinations in the shared
  navigation graph.
- Enabled PDF in the mind-map Add palette and routed it to the selected task's PDF workspace.
- Made Task Page attachment rows retain their attachment kind, label PDF captures as **PDF**, and
  open PDF captures in the dedicated workspace while preserving the generic viewer for other files.
- Installed the integration APK over the existing emulator app without clearing data and visually
  verified task -> Add -> PDF -> full-page PDF workspace.
- Combined verification passed: `testDebugUnitTest`, `compileDebugAndroidTestKotlin`, and
  `assembleDebug`. No Room schema change and no golden update.

# 2026-10-01 - Non-Image PR readiness audit

- Audited the committed Attachment/PDF/Audio baseline together with To-do, Notes, Prompt, Link and
  Task Page contracts while Image remained isolated with Claude.
- Corrected stale authoritative documentation that still described PDF, Audio, or Attachment as
  disabled or awaiting integration; no production code or schema changed.
- Added `NON_IMAGE_PR_READINESS_AUDIT.md` with architecture boundaries, feature status, known
  limitations, and the final Image integration gate.
- Verification passed: 1,091 ordinary JVM tests, Android-test compilation, and debug APK assembly.
  Roborazzi reported exactly the 17 previously documented differences; no golden was changed.
# 2026-10-01 - Image workspace foundation

- Created `codex/image-workspace` from Claude's clean Attachment commit `fe7eb90`, preserving the
  existing PDF, Audio and universal Attachment contracts.
- Specialized managed `AttachmentKind.IMAGE`; no entity, table or Room migration was added.
- Added task-scoped image library/import and existing-capture routes, light-theme non-destructive
  adjustments, filters, crop, rotate/flip/straighten, markup, undo/redo and Save-copy PNG output.
- Enabled Image in the mind-map Add palette and routed IMAGE Page blocks by persisted attachment
  kind to the canonical Image workspace.
- Added the authoritative `IMAGE_WORKSPACE_FEATURE.md` contract and focused pure contract tests.
- Verification passed: all 1,098 JVM tests, Android-test compilation, APK assembly and both
  on-device renderer tests. The APK installed over existing emulator data and launched cleanly.
  Roborazzi remained at the 17 documented pre-existing differences; no golden was changed.
## 2026-10-01 - Remove dormant Sticker and Illustration palette placeholders

- Removed the Sticker and Illustration entries, icons, and disabled row from the mind-map Add
  palette. Neither feature had a route, model, storage, or saved user data, so no migration or
  compatibility reader was required.
- Removed the now-obsolete disabled-option footer because every remaining palette choice has a
  real destination.
- Added a contract test that fixes the palette to the eight current product features and verifies
  every visible choice is supported.
- Updated the feature registry and navigation contract to record that these concepts are outside
  the current product scope. A future implementation must be specified as a new feature rather
  than reviving the removed placeholders implicitly.
- Kept To-do, Note, Prompt, Link, PDF, Attachment, Image, and Audio behavior unchanged.

## 2026-10-01 - Project Settings foundation and completed-task recovery

- Replaced the single-control Project settings alert with a centered, animated category-first
  modal with fixed header/footer and a scrollable settings viewport.
- Connected Project name, the canonical icon editor, Default execution, confirmed multi-task
  restoration, and the existing confirmed Project completion flow.
- Added `VirlinActions.restoreTask`: DONE to TODO only, clearing `completedAt` while preserving
  ownership and content; CANCELLED tasks are refused. Added domain coverage.
- Removed duplicate Edit project and Manage workstreams overflow entries; `+ New` remains the sole
  WorkStream creation control.
- Made no schema change because the parallel Mind-map Node Preview lane owns migration 17 to 18;
  future policy fields are honestly marked Planned and documented for the next reserved migration.
- Consolidated Project completion under `Project settings > Project management`; removed the
  duplicate three-dot-menu action while preserving the existing confirmation and mutation path.

## 2026-10-02 - Execution and timing foundation

- Added the canonical `ExecutionTiming` projection for WorkStream state wording, live session
  timing, exact Task time, recursive parent roll-ups and Project totals without persisted counters.
- Made Current changes during active Focus an atomic timing boundary: close the old attributed
  session and open a new one for the selected Task. The hierarchy UI also uses the existing switch
  confirmation, while the domain protects Agent and future callers.
- Connected projected state/timing to Project WorkStream rows and WorkStream task rows. Now and
  Pulse continue reading the same FocusSessions; no second timer system was introduced.
- Added `EXECUTION_TIMING_FOUNDATION.md` and focused coverage for roll-ups, live summaries,
  Project isolation and mid-focus task switching. No Room or managed-file migration.

## 2026-10-02 - Project execution dials

- Replaced ambiguous Project WorkStream icons and amber row highlighting with internal execution
  dials: green Focus, violet external Running, amber Needs-you Review, and static grey inactive
  states. Card borders remain neutral and existing navigation is unchanged.
- Added a Project execution summary using exact `FOCUS` and `PROCESSING` WorkStream counts, plus
  redundant state bars and text pills so meaning does not depend on colour alone.
- Refined active dials with restrained two-tone orbital motion and shimmer accents, and carried the
  same canonical dial into the exact current Task/current-path card in the WorkStream hierarchy.
- Replaced the shared spinner grammar with distinct approved motion: breathing emerald Focus with
  traveling glint, cobalt comet Running, stationary heartbeat Review crescents, and squared static
  Blocked. Multi remains reserved until a real concurrent-active domain projection exists.
- Increased motion legibility after emulator review: Focus gaps now migrate visibly at quarter
  speed, Running retains its faster comet flow, and Review crescents visibly open and close.
- Made all active-state motion unmistakable: Focus rotates clockwise, Running uses the faster cobalt
  flow, and Review rotates counter-clockwise while pulsing. Inactive states remain deliberately static.
- Replaced the rejected circle-only treatment with the approved signal language: breathing Focus
  brackets, flowing cobalt Running waves, a traveling Review zigzag, a static broken Blocked bridge,
  and floating Waiting curves. The multicolor circle remains reserved exclusively for truthful Multi.
- Kept the presentation honest to the current model: parallel external activity is represented by
  multiple processing WorkStreams; no fake multi-task state is inferred inside a WorkStream, whose
  canonical pointer remains `activeTaskId`.
- Documented the mapping and extension boundary in `docs/EXECUTION_DIALS_UI.md`.
- Verified JVM tests, Android-test compilation, APK assembly, data-preserving emulator install,
  Project navigation, animated rendering, and a clean AndroidRuntime crash check. No schema,
  navigation, action, timing, data, or golden-baseline change.

## 2026-10-03 - Execution signal icon refinement

- Replaced the Focus arcs with the approved four-corner emerald aperture and pulsing teal diamond.
- Replaced the Review zigzag with open saffron inspection ribbons and a scanning gold diamond.
- Animated Blocked segments horizontally toward a fixed coral lock while keeping the status and
  domain behavior unchanged; the blocks cannot cross the locked center gap.
- Updated the authoritative execution-signal specification. No route, action, timing, schema,
  persisted data, task ordering, or golden baseline changed.

## 2026-10-03 - Multi Run visual contract

- Added the production-ready `MULTI_RUN` presentation state with a single-line `MULTI RUN` pill,
  exact active-item count, four color-coded state bars, and independently orbiting execution ribbons.
- Added an instrumentation-only Project fixture that renders the real production component and
  verifies the label and accessible active count on an emulator.
- Left Waiting unchanged and kept Multi Run dormant in production until the canonical domain can
  report multiple active task IDs inside one WorkStream. No fake inference, schema change, or data
  mutation was introduced.

## 2026-10-03 - Workstream execution controls

- Project Overview status tags now have aligned 82dp clickable targets; removed per-workstream
  percentages while retaining project progress and task counts. Completed rows replace the left
  animation with a static mint-square check and remove the right status badge.
- Added centered, scrollable Workstream controls with background blur, expandable segmented
  execution preference, separate completion checkbox and explicit confirmation. Controls use
  existing attention intents/choosers and hierarchy focus-switch confirmation, not new timers.
- Preserved CHECK/Result-ready reason distinctions and guarded existing legal transitions.
  CHECK/SNOOZED completion requires resolving or ending the reminder first. Archive remains
  disabled; task/subtask controls and domain rules remain unchanged.
- Owned changes: HierarchyScreens.kt, HierarchyViewModel.kt, VirlinProjectExperience.kt,
  VirlinProjectPage.kt, new WorkstreamControlsDialog.kt, WorkstreamControlsUiTest.kt,
  docs/EXECUTION_DIALS_UI.md and this log. Fixed the pre-existing MultiRunSignalUiTest reference
  to its existing DebugMultiRunShowcaseActivity so instrumentation compilation succeeds.
- Verification: full JVM suite 1,160 tests, zero failures/errors; Android-test Kotlin compilation
  passed; debug APK built and installed data-preservingly on Pixel_8 API 35. Four new UI tests cover
  collapse/expand, completion confirmation/cancel, terminal controls, and check-due controls.
- No Room migration, route change, golden recording, commit or push. Graphify invocation failed
  with uv trampoline canonicalization; used targeted source reads and did not claim graph refresh.

## 2026-10-03 - Unified Workstream controls visual correction

- Replaced the mismatched outlined-button stack with navy text, muted supporting labels, soft
  neutral action rows, restrained emerald primary actions and equal-width Inherit/Human/External
  segments. Completion is a separated low-emphasis checkbox row, not part of the heading.
- Completion, pause/return timing, handoff checks, check outcomes, review reminders and human-focus
  switch confirmation now replace the body of one centered Dialog. A stable scrollable viewport,
  crossfade transitions, fixed header, Back and Close keep steps visually consistent. Feedback is
  inline; no Project-level NowChooserDialog or stacked AlertDialog remains for this flow.
- Preserved all domain actions, transition guards, preset durations, contextual notification
  permission and single-focus protection. Timing chips select first; Confirm executes. Archive
  remains unsupported and is omitted rather than presenting a misleading disabled action.
- Added Back/custom-time regression coverage. Compose input measurement failed the idle test;
  replaced only this numeric field with a styled platform EditText inside AndroidView. The five
  focused popup tests now pass, including custom entry and non-mutating Back/Cancel.
- Emulator inspection verified main controls, inline pause/custom and inline completion confirmation
  without pausing/completing the real stream. No schema, route, task/subtask menu, Now-screen visual,
  golden, commit or push change. Owned files: WorkstreamControlsDialog.kt, HierarchyScreens.kt,
  WorkstreamControlsUiTest.kt, docs/EXECUTION_DIALS_UI.md and this log.
- Final verification: 1,161 JVM tests, zero failures/errors; Android-test compilation passed;
  data-preserving install passed. Final emulator captures: C:/tmp/controls-final.png,
  C:/tmp/controls-pause.png and C:/tmp/controls-completion.png. Golden comparison was not rerun
  and no reference image was changed.
# 2026-10-03 — Category-relevant workstream controls

- Removed the completion checkbox; text action opens confirmation inside the same modal.
- Added Ready external start, paused Resume later, blocked Unblock/Edit reason, and inline external result confirmation.
- Preserved external-result readiness separately from check expiry, including review reminders.
- Verification: 1,168 tests passed, instrumentation Kotlin compiled, debug APK installed on Pixel 8.
- Scope remains workstream-level. Task-ledger concurrency, archive/reopen, migration and per-task Now wiring are not implemented; see docs/EXECUTION_DIALS_UI.md.

## External progress controls deduplication

- Removed Result ready and Block workstream shortcuts from Running and ordinary Check due main controls. Their single entry point is Check progress, with all three outcomes inside the same modal.
- Retained Mark as complete as a distinct workstream completion action; output readiness does not imply workstream completion.
- Eight WorkstreamControlsUiTest tests passed, including outcome placement and non-mutating Back navigation. Updated debug build installed on the emulator. No timing or domain transitions changed.

## External tracking pause/resume — 2026-10-03

- Added Pause tracking with preset/custom return times or indefinite pause, Resume external tracking and Change return reminder in the existing single popup.
- Persisted accumulated active seconds and the external-paused marker through additive Room 18->19 migration. Paused wall time is excluded; reminder expiry requests attention without restarting tracking.
- Existing human focus remains untouched. Now and notification Resume intents resume external tracking instead of opening human focus for paused external work.
- Verification: 1,171 unit/UI tests passed. Real 18->19 migration/persistence instrumentation test passed on Pixel 8. Latest APK installed, Project popup and timing chooser inspected without changing real work; no AndroidRuntime crash logged.
- Work remains workstream-level; concurrent task-ledger execution is still outside this change.

## Human workstream controls correction

- Fixed workstream IDs incorrectly sent to task-only focus resolution. Resolve active/next task, or use explicit taskless compatibility focus, with global-switch confirmation in either case.
- Human check-due work offers Focus now and Remind later, not external progress outcomes. Existing human timed/indefinite pause and resume now reach the corrected focus path.
- Clearing paused external responsibility to Human clears the external-paused marker, preserving saved external time. Active external intervals must first be paused; failures are visible inline.
- Verified 1,175 tests passed. Installed build inspected on emulator: Human Focus now opens the global switch warning with current and target names; Cancel preserves current focus. No crashes logged. No commit created.

## Task/subtask execution audit — 2026-10-04

- Added task-scoped Work controls and ancestry context; selected external starts retain task identity.
- Completion closes exact active activity/reminders instead of leaving ghost focus/runs. Updated old
  tests that expected that obsolete behavior and added cleanup/overwrite/callback regression tests.
- Guarded other-task external-run overwrites. Now shows task paths and category-correct attention.
- Verified 1,178 unit/UI tests, instrumentation compilation and debug installation. Full task-ledger
  concurrency is NOT implemented; documented limitations in docs/TASK_EXECUTION_AUDIT.md.

## Popup rendering inspection — 2026-10-04

- Reproduced divergent semantics and pixels: UI hierarchy changed while emulator screencap returned
  an identical older frame, even after app force-stop. Reboot recovered compositor rendering.
- Removed Project RenderEffect backdrop blur as a GPU compatibility safeguard; no proof that blur
  alone caused the frozen emulator. Standard modal dimming and internal transitions remain.
- Fixed the actual Now Working For You section to display project/workstream/task ancestry, not
  only the separate detail card. Verified active Build appears there, with check-due items above
  in attention. Current Build has no activeTaskId, so its context correctly ends at the stream.
- Rebuilt/installed; 1,178 tests passed. Visually inspected Focus and Running Project popups after
  reboot; both show the correct controls. AndroidRuntime log empty. No real task state changed.

## External start conflict and recursive controls — 2026-10-04

- Explain another active task before asking for a timer; View active work opens that exact task's
  controls without replacing its activity. Previously the rejected start returned Ready and invited
  repeated timer selection. Task-specific transient mode/start state now resets on task identity.
- Added aligned clickable Work/status tags to task rows and nested task headings using the shared
  popup. Child progress remains in subtitle instead of occupying the action slot.
- 1,179 tests passed, including conflict preflight UI regression. Installed and checked actual
  Waited Test 02 -> View active work -> BUild controls on emulator without mutating tasks.
- Independent simultaneous runs and inactive-task state persistence remain outstanding.

## Unified task card redesign — 2026-10-04

- Removed task row overflow, arrow, numbered tile and direct completion circle. Shared execution
  icons and clickable status tags remain; completed items use a left check. Nested headings match.
- Integrated Rename, Add subtask, Move, Duplicate and confirmed Cancel inside Work controls. Move
  choices exclude self/descendants. Workstream Rename preserves execution timestamps and state.
- Emulator card layout and inline management navigation inspected without modifying real tasks.
- Verification is NOT clean: latest full run had 1,180 passing tests and one new rename UI test
  failing with Robolectric AppNotIdleException. Domain rename regression passed. Editor layout
  simplified; focused regression remains under investigation. Do not report this as release-ready.

## Approved recursive scroll header — 2026-10-04

- Task pages now have pinned title/adaptive global-focus elapsed text, stable-ID breadcrumbs,
  static local sibling completion counts, and All/Active/Done plus Organize at every depth.
- Secondary header consumes scroll delta and clips beneath a white feathered mask; upward scroll
  hides it, downward scroll reveals it. Bottom actions stay fixed. Project Overview is unchanged.
- Build/install succeeded and targeted elapsed-format test passed. Emulator inspection confirmed
  collapsed header retains title/time while removing progress/filters/Organize, and reverse scroll
  restores content. No real task mutations. Full suite not rerun; prior rename UI idle failure remains.
# Header feather and elapsed color correction

- Confined the feather overlay to partially collapsed header details; expanded breadcrumbs and task cards no longer sit beneath it.
- Added 8 dp breathing room above breadcrumbs and list cards; changed elapsed digits and underline to slate gray.
- Verified debug build, HierarchyElapsedTest, debug APK installation, and expanded/collapsed emulator screenshots. Shared recursive layout applies the correction at every task depth. No execution data or project overview changes.
- Full suite not rerun; previously recorded rename UI test issue remains unresolved.
# Green rolling hierarchy timer

- Replaced the labeled slate timer with green, monospace rolling digits; removed ELAPSED and decorative underline.
- Only changed digit slots animate with a 280 ms vertical slide/fade. Timestamp-driven focus timing, recursive header placement, and conditional hour formatting remain unchanged. Accessibility exposes one complete time description rather than each animated digit.
- Debug build and HierarchyElapsedTest passed; APK installed on emulator. Added HierarchyTimerUiTest for hour rollover, label removal, and no-focus semantics. Full suite not rerun.
# Compact feathered timer refinement

- Changed timer to layout lime green, bold 20 sp tabular sans-serif digits with tighter font-scaled slots (12 sp digits / 5 sp separators).
- Smoothed transitions to 420 ms eased vertical motion with 340 ms fades and a digit-local alpha feather; no overlay touches hierarchy details or cards.
- Debug build, HierarchyElapsedTest, and HierarchyTimerUiTest passed; APK installed on emulator. Full suite not rerun.
# Scoped rotating timing and workstream Manage

- Top-right timer rotates Focused/External at six-second intervals, without task counts. Focus is accumulated section/descendant session time rather than the unrelated global open session.
- External display uses the current timestamp-backed interval only, shown for its owning task or ancestor section. Legacy workstream-level accumulated external time is deliberately not attributed to individual tasks; historical external roll-ups and concurrent independent same-stream runs remain outside this change.
- Added Manage at stream root, opening the existing workstream controls with responsibility, focus-switch confirmation, reminder actions, and rename; task/subtask Manage retains exact task controls.
- Timer formatting and new six-second category rotation UI tests passed; debug build passed. Full suite not rerun; prior rename test issue remains.

# Independent task execution and visible timer controls

- Added Room v20 task execution persistence and exact legacy activity migration without rewriting focus-session history.
- Enabled concurrent external siblings alongside one global human focus, task-specific reminders and Now presentation, recursive Focused/External totals, and mixed-mode MULTI RUN icons.
- Separated responsibility inheritance from live state; changing effective responsibility ends incompatible activity safely, preserves tracked time, and does not automatically start new work.
- Moved Timer display into the visible controls list; scoped rotation settings offer 5, 6, or 7 seconds.
- On-device migration test passed. Last full suite: 1187 tests, one existing rename-dialog idle failure. Targeted tests and debug assembly are checked separately.
- Instrumentation runner removed the emulator installation; the pre-test database archive was restored and existing tasks verified. No user task activity was started or completed during manual UI inspection.

# Now focus identity correction

- Removed display rows absent from the live execution projection instead of retaining stale demo Focus cards. Persisted titles and next actions now overwrite cached display text, including clearing missing next actions.
- Now selects its hero by the domain focus identity, not the first cached FOCUS row. Exact task/subtask names and ancestry remain domain-derived; context navigation opens the owning workstream at the exact task path.
- Removed the invented two-minute next-action estimate. Added nested task-owned focus/rename/navigation regression coverage.
- Debug assembly and NowHierarchyTest / TaskExecutionFoundationTest passed; updated APK installed without clearing app data. Full suite not rerun for this correction.

# Now live execution audit (codex/now-live-execution-audit)

- Corrected constructor-parameter shadowing: Now ViewModel projections explicitly read the task execution repository, not raw workstreams. Added ViewModel regression coverage for focused task 51 under a READY owner.
- Ready focus cards now use exact task titles and ancestor context; navigation targets their actual owning stream and task path. Recommendations exclude external work, terminal tasks and non-executable parent nodes.
- Disconnected the demo counter ticker from MainActivity. Display bridge redraws from persisted sessions/check timestamps once per second instead of incrementing cached demo counters.
- Removed invented summary numbers and demo greeting identity, retaining the existing summary layout with unavailable-value markers. Persisted projects/tasks, including demo-named ones, were not deleted.
- Debug assembly and targeted Now/task-execution tests passed. Updated APK installed without clearing data. On-device Now hierarchy inspection confirmed Virlin Development → Claude · Virlin → 51 → sb 1 as the actual focused leaf; no fake Psychology hero appeared. Full suite not rerun.
- Work remains uncommitted on the dedicated branch; existing unrelated worktree changes were preserved. Demo implementation files remain available for recovery but the demo ticker is no longer started by the app.

# Compact Focus card context segment

- Changed only the Focus card title segment: main project heading, responsive one-word ancestor breadcrumb, then the full focused task name. Breadcrumbs wrap at available width; long first words receive display-only ellipsis. Full ancestor names remain in accessibility descriptions.
- Caller passes ancestor labels separately, excluding the focused leaf to avoid repetition. Persisted titles/IDs, context navigation, timers, actions and the rest of the screen remain unchanged.
- Debug build and 28 targeted tests passed, including ten-level narrow-width Compose rendering, context/completion callbacks, label formatting and existing Now focus/exit logic. Updated APK installed without clearing app data; full suite not rerun.

# Leaf-only execution system audit

- Added one structural eligibility rule: only tasks without children and empty workstreams execute. Parent controls retain management, summaries and timer-display settings but hide responsibility/execution controls. API guards prevent starting an arbitrary child through a parent.
- Fixed paused Human-to-External mode changes to become READY for external start, never Resume focus. Startup repairs incompatible old leaf/container activity, preserving history/totals without starting another item. Explicit external-result review is marked separately.
- Adding children to active leaves and reparenting active branches are refused until activity ends; sibling reordering remains available. End activity accumulates current external seconds and clears reminders.
- Preserved legacy repository adapter compatibility while production uses independent leaf records. Now queues and alarms use leaf projections.
- Full-suite run: 1201 tests, one known rename-dialog idle failure after other regressions were corrected. Final targeted execution, parent-controls, completion and Now checks passed. Debug assembly passed and APK installed without clearing data.
- Emulator inspection confirmed parent popup execution controls absent; original management UI retained. Pre-install database/files/preferences backup: C:/tmp/virlin-before-leaf-only-audit.tar. No tasks were started/completed during manual checks; startup reconciliation may end invalid container/incompatible activity as described above.

# Compact container workstream settings

- Container workstream controls now show only Edit name and Mark as complete in a content-sized popup, without duplicate activity lists, timer settings, or container guidance. Leaf task and empty-workstream execution controls remain unchanged.
- Completion is enabled for containers and explicitly confirms completing all remaining owned tasks/subtasks. One transaction closes focus sessions and external tracking, clears pending reminders/cycles, preserves recorded time/history, and leaves cancelled tasks and other workstreams unchanged.
- Debug assembly and 72 targeted domain/UI tests passed, including nested mixed-mode completion, unrelated workstream isolation, repeat-completion rejection, popup visibility, and confirmation/cancellation. Full suite was not rerun; the previously documented rename idle test remains unresolved.
- Updated debug APK installed without clearing app data. Pre-install recovery archive: C:/tmp/virlin-before-compact-workstream-controls.tar. No real tasks were completed for testing.

# Project timer display management

- Added Project settings > Timers & display with persisted 5/6/7-second Focused/External rotation selection. Explains immediate saving and display-only behavior; no activity controls or timing data are changed.
- Passed actual project identity to hierarchy timers. A saved project preference applies across all its sections and overrides older section rotation preferences; other projects retain their settings.
- Four targeted timer/settings UI tests and debug assembly passed. Updated APK installed without clearing app data; no schema changes. Backup: C:/tmp/virlin-before-project-timer-settings.tar.

# Task and subtask navigation arrows

- Added the same auto-mirrored 22dp trailing chevron used by workstreams to the shared task card, including completed cards and every hierarchy depth. Organization-mode selection/reordering remains unchanged; arrows are decorative and reuse the existing card navigation.
- Debug assembly and 16 targeted tests passed, including arrow presence across task/subtask/deeper/completed rows and unchanged navigation/work-control callbacks. Updated APK installed with data preserved.

# Parent task controls cleanup

- Removed the Activity in this section list from shared work controls at every hierarchy depth. Parent task/subtask popups also omit timer-display settings and redundant container guidance, while preserving rename/add/move/duplicate/cancel/completion management.
- Leaf execution controls and empty-workstream controls are unchanged; project timer display management remains available. Targeted parent/leaf UI and execution-foundation checks plus debug assembly passed. Installed updated APK without clearing user data.

# Project overview feathered collapse

- Pinned project identity and Workstreams heading; upward overview scroll collapses progress, tabs and execution signals beneath a feathered edge. Downward scrolling reveals details before scrolling the body. Cards never receive the fade. Knowledge/Activity keep bounded content and reset collapse on entry.
- No domain data, mutations or action callbacks changed. Debug assembly and 16 targeted tests passed, including bidirectional collapse, pinned headings and Knowledge/Activity access.
- Pre-change source copy: C:/tmp/virlin-project-scroll-rollback/VirlinProjectPage.kt. To roll back this layout, restore that copy to app/src/main/java/com/virlin/app/ui/hierarchy/VirlinProjectPage.kt, remove ProjectCollapseUiTest.kt, then rebuild/install. No database migration or data rollback is needed.

# Active project screen collapse correction

- Audit identified that ProjectExperienceHost renders VirlinProjectExperience, not the secondary VirlinProjectPage component changed previously. Restored that secondary component and applied collapse to the live screen.
- Live Overview pins project identity and Workstreams/Mind map/New heading; progress, tabs, message and execution signals collapse/reveal together. Cards remain outside the feather. Knowledge/Activity remain bounded and reset the overview offset on entry.
- Updated ProjectCollapseUiTest to instantiate the actual production component. Sixteen targeted tests and debug assembly passed. Installed APK and emulator inspection confirmed collapsed headings with details hidden, then tabs/signals restored on downward swipe. No task mutations were performed.
- Actual-screen rollback source: C:/tmp/virlin-project-scroll-rollback/VirlinProjectExperience.kt. Restore that file and remove the collapse-specific test, rebuild/install; no database changes are involved. Earlier rollback instructions targeted the secondary component only and are superseded by this correction.

# Project appearance palette

- Added Content & appearance > Project colors (also reachable from Identity) with 23 named presets, six lighter/darker shades, hue/saturation wheel, brightness slider, hex input, live miniature preview, five palette roles and two text roles. Save/Discard are pinned; Reset is draft-only until saved.
- Local per-project SharedPreferences persistence and reactive listeners preserve settings across restart without schema migration. Missing/corrupt preference falls back to the original look. Preferences are device-local and not part of existing domain project-export/duplication payloads; do not claim cross-device palette transfer.
- Connected static accents on the real project experience, directory rings, project settings and recursive task screens. Semantic execution washes/dials/pills/timer digits, destructive colors, custom images and global navigation are not themed. Neutral surfaces/library details retain some existing defaults; no domain activity data changes.
- Contrast validation covers custom text against page/white/status-card surfaces and secondary links against page/white; filled task buttons choose readable foregrounds. All 23 presets satisfy validation. Labels describe warm/cool/neutral families without psychological guarantees.
- Debug assembly and 19 targeted tests passed: palette serialization/fallback, all preset contrast, draft/save/discard/reset, scope isolation, unreadable custom text, live-page scrolling, task navigation, timer settings and execution foundation. Full suite not rerun. Final APK installed with data preserved; pre-install recovery archive C:/tmp/virlin-before-project-colors.tar.
- User-facing rollback: Project colors > Reset to original > Save palette restores the original static appearance without touching execution data.

## 24th preset

- Added Sand (#C2A878), completing the six-row/four-column preset grid. Updated displayed counts and count assertion to 24. All preset contrast validation and five appearance tests passed; debug assembly and data-preserving APK update succeeded.

## 2026-10-04 — Development checkpoint and premium-readiness audit

- Added `docs/PREMIUM_READINESS_AUDIT_2026-10-04.md` and linked it from `docs/PROJECT_DOCUMENTATION_INDEX.md`. Documents recent execution, hierarchy, Now, controls, timers and 24-color appearance work, findings and proposed release gates.
- Full unit/UI suite: 1,213 executed, 1,212 passed, rename-dialog idle timeout failed. Debug lint: 19 errors and 122 warnings. Independent release assembly passed and produced an unsigned APK; release manifest excludes debug showcase.
- Identified bounded-import parsing, parent-task completion consistency, production demo seeding, target/signing readiness, backup/privacy, palette coverage and scale-validation concerns. Suggestions only; no application behavior changed.
- No commit/staging, install, data reset or connected instrumentation run. Current user-data emulator preserved. Owner decides checkpoint commit and follow-up fixes after reviewing the report.

## 2026-10-04 — Authorized audit remediation

- Bounded attachment parsing and remote images: new `ImportBudget.kt`, `OoxmlReaders.kt`, `UniversalFileViewer.kt`, `ProjectMapScreen.kt`, `LinkEditorScreen.kt`; added import-budget regressions.
- Transactional subtree completion/cancellation, history-safe restore and explicit container confirmations: `StructureActions.kt`, `HierarchyScreens.kt`, `WorkstreamControlsDialog.kt`; updated structure regressions and added `HierarchySafetyTest.kt`.
- Release demo seed guard: `VirlinGraph.kt`. Backup transport rules: `res/xml/backup_rules.xml`, `res/xml-v28/backup_rules.xml`, `res/xml/data_extraction_rules.xml`; added `BackupPolicyTest.kt` and security/privacy contract.
- SDK 36 / AGP 8.9.1 / Gradle 8.11.1, verified wrapper checksum, optional secure-environment release signing and native-test fork/heap bounds: root/app build scripts and wrapper properties. Added owned adaptive launcher resources and manifest icon.
- Iterative hierarchy and batched progress: `TaskHierarchy.kt`, `HierarchyPresentation.kt`, `ProgressCalculator.kt`, `ExecutionSummary.kt`; lazy overview/static palette surfaces in project experience/knowledge/activity/task screens. Lifecycle-aware clocks in `NowScreen.kt` and `WaitingTime.kt`.
- Lint state/API fixes in mind-map rendering, context pickers, composer/sheet interactions, hierarchy components, ProjectIcon and MapPreviewRendering; locale fixes in AttachmentKindResolver and NowDaySummary. No blanket baseline added.
- Standalone execution owner guard in `DefaultVirlinActions.kt`. Android regression coverage in new `WorkControlSafetyUiTest.kt`; native Robolectric rename test relocated to this real Android test after persistent idle incompatibility. Room/migration instrumentation fixtures now assert schema 20 and canonical leaf execution.
- Local suite: 1,228 passed / 0 failed / 0 skipped. Lint: 0 errors, 127 warnings, 12 notices. Debug/test/release assembly succeeded. Isolated Android device regression results and release gates are recorded in `docs/AUDIT_REMEDIATION_2026-10-04.md`.
- No staging, commit, push or user-emulator installation/reset. All Android instrumentation used disposable emulator-5556; the populated emulator-5554 was untouched.
- Final Android pass: 40/40 passed, covering migrations through schema 20, durable persistence/reload, priority/rank recovery and rename/completion confirmation. Final lint rerun and CRLF-aware whitespace check also passed.

## 2026-10-04 — Main checkout integration and Working for you repair

- Fast-forwarded the clean Documents checkout's `feature/attachment-workspace` to the reviewed latest checkpoint. Preserved old HEAD as `codex/attachment-before-latest-20261004`; verified debug assembly from the requested location. No remote push or user data changes.
- `ExternalWork.kt`: exact work-item display title and live external-run projection that retains overdue processing and actively tracking CHECK items. Paused/stopped/result-ready items remain excluded.
- `NowScreen.kt`: Working for you title uses task identity, not actor fallback. `NowViewModel.kt`: corrected contract comment; due reminders remain in Needs You without hiding ongoing work.
- `ExternalWorkTest.kt`: concurrent same-workstream leaf identity, overdue visibility, rename/fallback and stopped-state regressions; updated existing due-boundary expectation. Added `docs/WORKING_FOR_YOU_FIX_2026-10-04.md` explaining causes and contract.
- Verification: full local suite 1,230 passed with zero failures/errors, lintDebug and assembleDebug passed. Documentation index links the fix. No device install/reset, commit or push.

## 2026-10-04 — When you're free icon consistency

- `ProjectIdentity.kt`: added cycle-safe exact work-item project resolution through task ownership/ancestry and workstream. Returns the actual project object, retaining its custom/built-in icon metadata; never borrows an unrelated project.
- `NowViewModel.kt`: exposes canonical owner workstreams for identity lookup. `NowScreen.kt`: all When you're free cards render an icon; project-backed tasks/subtasks use their project icon, genuinely projectless items use the existing deterministic icon fallback.
- `ProjectIdentityTest.kt`: regressions for nested tasks with missing direct project IDs, owning workstream/custom icon preservation, missing projects and cyclic/projectless ancestry. Focus actions, ordering and stored task data unchanged.
- Verification: project-identity regression tests and debug APK assembly passed; CRLF-aware diff check passed. No commit/push or data reset.

## 2026-10-04 — Approved When you're free card layout


- Removed the optional estimate pill from `VirlinWhenFreeCards.kt`; rendered muted accumulated focused time separately beneath the single-line hierarchy. Original estimates remain stored and untouched.
- New read-only `WhenFreePresentation.kt` resolves ancestry nearest-parent first, then workstream and project, without repeating the current title. Long paths use one-line ellipsis in the card. Focus totals use recorded sessions with duplicate-ID protection, zero as 0m and no invented estimates.
- `NowViewModel.kt` refreshes focus history labels on task/workstream changes; `NowScreen.kt` supplies hierarchy and focused labels while retaining icon/action wiring.
- Added `WhenFreePresentationTest.kt` for reverse ancestry, task-less hierarchy, zero focus, sibling exclusion and duplicate-session protection. No execution/data migration or remote push.

## 2026-10-04 — Projects search bar cleanup

- `VirlinProjectDirectory.kt`: removed trailing green filter glyph, divider and click target from the search bar. Search input and status chips remain unchanged. Debug assembly passed; updated emulator through data-preserving APK installation.

## 2026-10-05 — Status-filtered Projects / Tasks directory

- `DirectoryWorkProjection.kt`: read-only leaf execution projection, consistent status matching, exact names, ancestry search, recorded timing and project ownership. Overdue running work intentionally also matches Needs You. Paused external work is not Ready.
- `StreamsScreen.kt`: All remains the original project directory. Status selections default to grouped Tasks with a Projects / Tasks switch; navigation uses stable task IDs and ancestry paths. Lifecycle-aware ticking updates due classifications and timing.
- `FilteredProjectTasks.kt`: collapsible project groups, projectless group, project icons, single-line reverse ancestry, timing, status labels and navigation chevrons. Controls never start execution.
- `VirlinProjectDirectory.kt`: finite status chips; clearer labels and Paused filter; edge fades only when scrolling is possible.
- Added six projection regressions and two Compose interaction regressions. Documented behavior/rollback in `docs/PROJECT_DIRECTORY_FILTERS_2026-10-05.md` and linked the documentation index. No migration, reset, commit or push.
- Verification: 1,243 tests passed; debug assembly and lint passed (zero errors, 127 warnings); whitespace diff check passed. APK installed with `adb install -r` on Pixel_8; live UI verified All and Running/Tasks with exact nested task results. Test fixture corrected to use domain `checkAt`; a transient overlapping-build file lock was resolved by rerunning verification.

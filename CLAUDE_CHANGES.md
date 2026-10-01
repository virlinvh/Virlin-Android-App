# Claude change log

Append-only. Codex keeps its own log in `CODEX_CHANGES.md`; the two logs are separate so the
agents never append to the same file at the same time.

---

## 2026-09-30 — Notes page: the approved shell made fully functional, self-contained

**Requested by:** User

**Status:** Implemented and verified on the emulator. Physical-device verification is PENDING —
no phone was connected, so nothing here is claimed as phone-verified.

**Constraint the user set:** the approved UI must not change; the feature must be fully
functional; and it must NOT be connected to the existing note machinery, because the eventual
owner is a later decision.

### Interpretation of "do not connect this with any of the existing things related to this"

Read as: no coupling to the other NOTE surfaces — `NoteDocument`, the capture Text Note editor,
or the legacy plain `tasks.notes` string. The page still needs an entry point to be reachable,
so it opens from the mind-map Add palette and uses the selected task id ONLY to namespace the
document key and to title the header. Nothing on the page reads or writes any task field.
The database diff below proves that empirically.

### Files changed

New:
- `app/src/main/java/com/virlin/app/domain/notedoc/VirlinNoteDoc.kt` — own block/run model plus
  `NoteRuns` (pure run algebra: normalize, applyMark, applyAttribute, shiftForEdit, diff).
- `app/src/main/java/com/virlin/app/domain/notedoc/VirlinNoteCodec.kt` — own versioned JSON codec
  with its own minimal parser (no third-party or Android JSON, so JVM tests and Room agree).
- `app/src/main/java/com/virlin/app/domain/action/NoteDocActions.kt` — the only write path.
- `app/src/main/java/com/virlin/app/ui/notes/VirlinNotesScreen.kt` — the user's approved shell,
  restored. Layout untouched; the ONLY edit was the `ImageVector` import path, which the shell
  had as `ui.graphics.ImageVector` (an older Compose snapshot) instead of
  `ui.graphics.vector.ImageVector`, and removing imports the file never used.
- `app/src/main/java/com/virlin/app/ui/notes/NotesViewModel.kt` — document, caret, selection,
  undo history, debounced save.
- `app/src/main/java/com/virlin/app/ui/notes/NoteBlockEditor.kt` — one `BasicTextField` per block
  with a `VisualTransformation` that paints the stored runs.
- `app/src/main/java/com/virlin/app/ui/notes/NotesRoute.kt` — host, pickers, link dialog, preview.
- `app/src/test/java/com/virlin/app/domain/VirlinNoteDocTest.kt` (26 tests).
- `app/src/test/java/com/virlin/app/domain/NotesEditorTest.kt` (40 tests).

Modified:
- `data/db/VirlinDatabase.kt`, `data/db/VirlinEntities.kt`, `data/db/VirlinMappers.kt` — schema
  **v15 -> v16**.
- `domain/repository/WorkStreamRepository.kt`, `InMemoryWorkStreamRepository.kt`,
  `BootstrappingWorkStreamRepository.kt`, `data/db/RoomWorkStreamRepository.kt` — read + writer.
  `SchedulingWorkStreamRepository` needed nothing: it delegates with `by inner`.
- `domain/action/VirlinActions.kt`, `DefaultVirlinActions.kt` — `loadNoteDoc` / `saveNoteDoc`.
- `ui/navigation/VirlinApp.kt` — the `notes/{ownerKey}?title={title}` route, and `"notes/"` added
  to `STREAMS_SUB_ROUTES` so the shared footer and Orb come from the app Scaffold.
- `ui/map/MapAddPalette.kt` — `NOTE` is now supported; availability comment brought up to date.
- `ui/map/ProjectMapScreen.kt` — the `NOTE` branch navigates to the Notes page.
- `androidTest/.../VirlinMigrationTest.kt` — 15 -> 16 migration test; fresh-install test now v16.

### Schema v16

Adds `virlin_notes` (`ownerKey` PK, `title`, `documentJson`, `revision`, `createdAt`,
`updatedAt`) and DROPS `task_note_documents`. That v15 table was created but never wired to a
mapper, repository or codec, so it was provably empty in every build that has existed; the
migration test also inserts a row into it first to prove the upgrade still succeeds if one
somehow existed. No destructive fallback.

### What works

Typing, IME, paste, Unicode; selection-range and caret-pending bold/italic/underline/strike/
inline-code; font, size, text colour, highlight; left/centre/right alignment; bulleted, numbered
and checklists; indent/outdent; line spacing; links with URL validation (bare domains get
`https://`, other schemes refused); quote, code, user-authored Prompt callout; divider; editable
table; headings; Enter splitting blocks with list/quote continuation and empty-item exit;
Backspace-at-start demote-then-merge keeping runs; undo/redo with typing coalesced; debounced
autosave with a flush on close; read-only Preview.

Deliberately NOT pretended: Export says it is not built and writes nothing. Image and attachment
blocks are stated as absent in the Add-block sheet. Tools with nothing to act on are disabled
rather than inert.

### Known defect, deliberately NOT fixed

The floating Orb sits on top of the shell's mint `+` Add-a-block button and swallows its taps
(evidence: tapping it opens the Agent sheet). Fixing it means changing the approved layout, which
this request forbade, so it was left alone and reported instead. Block insertion is not blocked
meanwhile: every block type is reachable from the toolbar.

### Verification

- `:app:testDebugUnitTest`: **1043 tests, 1 failure** — `CaptureBoundaryTest
  .types_are_explicit_and_payloads_raw`, pre-existing and unrelated (Codex's URL normalisation),
  previously reproduced on an untouched baseline.
- `:app:assembleDebug`: passed. Installed on `emulator-5554` (Pixel 8, API 35).
- Migration proven on the real emulator database: `user_version` 15 -> 16, `virlin_notes` created,
  `task_note_documents` dropped, and row counts identical (projects 6, workstreams 12, tasks 35,
  captures 4, task_steps 7, events 107).
- Live run in the disposable **DragTest** project only, on task `S4`: palette shows Note enabled;
  the page renders exactly as approved; typing flipped the header "No changes" -> "Saved" after a
  real write; caret-pending bold produced the stored document
  `{"text":"HellBOLDo Virlin notes","runs":[{"s":4,"e":8,"m":["BOLD"]}]}`; Preview showed the same
  structure; closing and reopening restored text and formatting; the map returned with its
  hierarchy, zoom and layout unchanged and no node created for the note.
- Full before/after diff of all 19 pre-existing tables: **no changes to any of them**. The feature
  wrote only to `virlin_notes`.

---

## 2026-09-30 — Notes page: lift the add-block control clear of the Orb, drop its label and rule

**Requested by:** User ("The add block + icon is hidden behind the ORB so kindly keep it little
above the orb and remove the anotation 'add block ____________' there at the left side.")

**Status:** Completed and verified on the emulator. Physical device PENDING.

**Files changed:**

- `app/src/main/java/com/virlin/app/ui/notes/VirlinNotesScreen.kt`

**Change:**

- Removed the `Add a block…` label and the horizontal rule from that row. The row now holds the
  mint `+` alone, aligned to the end.
- Added `OrbClearance = 77.dp` bottom padding, derived from the Orb's own geometry (52dp Orb +
  12dp bottom inset + 13dp gap) rather than from a screen position, so the control sits above the
  Orb instead of underneath it.
- This supersedes the "Known defect, deliberately NOT fixed" note in the entry above: the user has
  now explicitly authorised changing this row.

**Verification:**

- `:app:assembleDebug` passed; installed on `emulator-5554`.
- In DragTest on task `S4`: the label and rule are gone, the `+` renders above the Orb, and
  tapping it opens the "Add a block" sheet — previously that tap opened the Agent sheet because
  the Orb swallowed it.
- Table insertion confirmed live: the stored document gained
  `{"type":"TABLE","table":{"rows":2,"cols":2,...}}` followed by a trailing paragraph, which is
  the intended rule that a block unable to hold the caret is followed by one that can.

**Unintended write during this verification — reported, not silently reverted:**

While navigating with taps, one mis-tap opened Codex's Link editor and backing out of it created
a capture. Exactly one row was added to `captures`:

- id `cap-2aff53ba-f553-49d9-8f9f-9ec8592f91fd`, type `LINK`, status `INBOX`,
  sourceUrl `https://www.youtube.com/watch?v=j2z9eVlxK5U`,
  projectId `proj-c5602e16-9fd7-4e63-a935-0fb3acb6a0d4` (DragTest), created 1790742927868.

The Inbox badge went 4 -> 5 as a result. No other pre-existing table changed. It has been left in
place for the user to decide how to remove it.

Also, a stray tap inserted an empty 2x2 table into the scratch note on DragTest task `S4`
(`virlin_notes`, revision 9). That is this feature's own test note, not user data.

---

## 2026-09-30 — Synchronization with Codex's Task Page foundation (no production changes)

**Requested by:** User ("synchronize your understanding… do not modify production behavior
during this synchronization step unless you find a concrete correctness issue; report any such
issue before changing it.")

**Status:** Read-only synchronization. **No production file was modified.** One correctness
issue was found and is reported below rather than changed.

Read `docs/TASK_PAGE_FOUNDATION.md`, Codex's v17 entry in `CODEX_CHANGES.md`, and the Task Page
model, actions, screen, repository/Room wiring, navigation and tests. The contract is recorded in
Claude's project memory as `virlin-task-page-architecture`.

### Correctness issue found: the `task.note` contentId does not match the key the Notes editor uses

`TaskPageTypeKeys.noteOwner(taskId)` returns `"task:$taskId"`
(`domain/model/TaskPageModels.kt:20`), but the Notes page persists its document under a key built
by `notesForTask` as `"task-$taskId"` (`ui/notes/NotesRoute.kt:63`) — a hyphen, not a colon.
Because task ids already begin with `task-`, the value actually stored in `virlin_notes.ownerKey`
is `task-task-<uuid>`, while the Page block records `contentId = task:task-<uuid>`.

Consequences:

1. `TaskPageActions.loadAndReconcile` guards note registration with
   `noteDocOf(TaskPageTypeKeys.noteOwner(taskId)) != null`
   (`domain/action/TaskPageActions.kt:21`). That lookup can never succeed, so the branch is
   effectively dead: a task that already has a Notes document but no placement — any note written
   before v17, or by any path that does not eagerly register — will never be projected onto its
   Page.
2. The stored `contentId` for every `task.note` block is dangling. Nothing resolves note content
   by `contentId` today, so no user-visible breakage exists yet, but any future preview text,
   stale-content cleanup, or note deep-copy that follows `contentId` will silently find nothing.

Not affected: the edit path still works, because the Page row routes through
`notesForTask(row.block.taskId, …)` (`ui/page/TaskPageScreen.kt:161`) and rebuilds the same
`"task-"` key rather than using `contentId`; and the row's label and subtitle are static strings,
not document content.

Why the suite did not catch it: the only NOTE assertion in `TaskPageActionsTest` calls
`pages.ensure(task.id, NOTE, noteOwner(task.id))` directly (line 24). No test creates a note
through the Notes editor's own `saveNoteDoc` and then asserts that `loadAndReconcile` picks it
up, so both sides agree on the wrong key.

Suggested fix, for the user to approve, and noting that `notesForTask` is Claude-authored while
`TaskPageModels`/`TaskPageActions` are Codex-authored: make `notesForTask` derive its owner key
from `TaskPageTypeKeys.noteOwner(taskId)` so there is one definition. That changes the key of
already-stored documents, so it needs either a tiny data migration or a deliberate decision to
strand existing notes. The only note currently stored on `emulator-5554` is Claude's own
DragTest/`S4` scratch note.

### Secondary observation (not a defect, flagged for the record)

`ProjectMapScreen` registers the NOTE Page block eagerly when the palette's Note option is tapped
(line 500), before any document exists. A user who opens Notes and immediately backs out is left
with a Page row for a note that was never written. That matches the documented
"register in the creation flow" rule, so it is being reported rather than treated as a bug.

---

## 2026-09-30 — Synchronization with Codex's Notes cleanup (no production changes)

**Requested by:** User. **Status:** Read-only synchronization. **No production file was
modified.** No correctness problem was found; the one issue Claude reported in the previous
entry has been fixed by Codex.

Read `docs/NOTE_SYSTEM_CLEANUP.md`, `docs/TASK_PAGE_FOUNDATION.md`, both change logs, and the
redesigned Notes, legacy compatibility, navigation, ORB capture and Task Page implementations.
Recorded in Claude's project memory as `virlin-notes-canonical-vs-legacy` (the Task Page contract
is already recorded as `virlin-task-page-architecture`).

### Previously reported issue: RESOLVED

The `task.note` owner-key mismatch reported in the prior entry is fixed.
`TaskPageTypeKeys.noteOwner(taskId)` now returns `"task-$taskId"`, matching the format already
persisted in `virlin_notes`, and `NotesRoute.notesForTask()` derives its owner key from
`noteOwner()` instead of concatenating its own. `TaskPageActions.loadAndReconcile` therefore looks
up a key that can actually exist, so an existing task Note is now projected onto its Page. Because
the hyphen form was kept, no data migration was required and no stored row was stranded — a better
resolution than the colon form the documentation originally specified.

### Implementation verified against the stated contract

- Only `text_note/{captureId}` is registered (`VirlinApp.kt:394`); the bare new-draft destination
  is gone. Both remaining callers (`HierarchyScreens.kt:184`, `:705`) pass a capture id.
- The two `textNoteRoute(id)` call sites in `VirlinApp.kt` (lines 331 and 604) each guard with
  `if (id == null) openRedesignedNotes(null, null)`, so new drafts go to the redesigned workspace
  and only existing captures reach the legacy route.
- ORB capture: the NOTE card reads "Note / Open your notes workspace" and calls
  `onOpenTextNote(null)` (`AgentCaptureArea.kt:194, :249`) -> redesigned. An existing Inbox NOTE row
  passes `row.item.id` (`:359`) -> legacy. Correct split.
- `openRedesignedNotes` (`VirlinApp.kt:125-151`) resolves explicit ids first, then `routeTaskId`
  (Task Detail / To-do / Task Page arguments only) then `routeProjectId`, else global. It reads
  route arguments only and never consults the focused or active stream, and it ensures the
  `task.note` Page block before navigating.
- Legacy layer intact: `TextNoteEditorScreen`, `TextNoteViewModel`, `NoteActions`, `NoteModels`,
  `NoteDocumentCodec`, `NotePlainTextSerializer` and the `note_documents` DAO all still present.
- Natural-language capture unchanged: `CommandResolver.kt:222` still maps `CaptureNote` to
  `CaptureType.NOTE`.
- Test counts: `NotesEditorTest` 40, `TaskPageActionsTest` 4, `AgentCaptureLauncherTest` 7.

### Two observations, neither a defect

1. `textNoteRoute(captureId: String?)` (`TextNoteEditorScreen.kt:91-92`) still returns the bare
   `"text_note"` string when given null, and `const val TextNoteRoute = "text_note"` still exists,
   although no destination with that route is registered any more. Every current caller guards
   against null, so nothing is broken; it is only a latent trap for a future caller that forgets
   the guard. Left alone deliberately, since removing the null branch touches a shared legacy file.
2. No test writes a Note through the redesigned editor's own `saveNoteDoc` and then asserts
   `TaskPageActions.loadAndReconcile` registers it. The owner-key mismatch class is now
   structurally prevented by the single `noteOwner()` definition, so this is a coverage gap rather
   than a risk, but such a test would pin the two sides together permanently.

---

## 2026-09-30 — Synchronization with Codex's green task-hierarchy migration (no production changes)

**Requested by:** User. **Status:** Read-only synchronization. **No production file was
modified.** No correctness problem found.

Read `docs/TASK_HIERARCHY_CLEANUP.md` plus the Page and Notes contracts, and inspected
`VirlinStreamTasks.kt`, `HierarchyScreens.kt`, `HierarchyViewModel.kt`, `VirlinApp.kt`,
`ProjectMapScreen.kt` and `TaskHierarchyDestinationTest.kt`. Recorded in Claude's project memory
as `virlin-green-task-hierarchy`, alongside `virlin-task-page-architecture` and
`virlin-notes-canonical-vs-legacy`.

This closes the Task Detail audit Claude raised in an earlier session, when the user asked why the
old cream task/subtask pages were still reachable. The two blockers named then — rehoming FOCUS
(`vm.focusWorkItem`) and DELEGATE (`vm.delegate`), and finding a destination for standalone project
tasks — are exactly what this migration resolved.

### Verified against the stated contract

- `TaskDetailScreen` has no definition anywhere in `app/src`: removed, not merely unreferenced.
- `TaskDetailRoute` renders `TaskHierarchyRedirectScreen` (`VirlinApp.kt:366-367`), and
  `"task_detail/"` remains in the Streams sub-route list so the footer/Orb behaviour is unchanged.
- `taskHierarchyDestination` (`HierarchyScreens.kt:73-96`) is pure and ID-based: it walks
  `parentTaskId` with a `visited` set guarding cycles, returns `workStreamDetailAt(workStreamId,
  lineage.map { it.id })` for WorkStream tasks, `projectTaskIndex(projectId)` for standalone tasks,
  and `null` otherwise. No title or index lookup anywhere in it.
- Focus/Delegate gating: `TaskMenu(allowWorkActions = ...)` omits both entries unless the task is
  non-terminal — `parent?.terminal != true` (line 215) and `!task.terminal` (line 274). No
  "Open details" entry remains.
- All task mutations route through the ViewModel, never the repository: `focus` ->
  `vm.focusWorkItem`, `delegate` -> `DelegateDialog` -> `vm.delegate`, plus rename/complete/move/
  duplicate/cancel (`HierarchyScreens.kt:407-429`). `SwitchFocusDialog` is driven by `pendingSwitch`
  and mutates only on `vm::confirmSwitch`.
- The Delegate path re-checks `task.status.isTerminal` at render time and dismisses itself
  (`HierarchyScreens.kt:442-444`), so terminal tasks are blocked twice over, not only by the menu.
- Execution domain preserved: `setExecution` / `setStreamExecution` still map INHERIT to
  `resetTaskExecutionPreference` and HUMAN/EXTERNAL to `setTaskExecutionPreference`, and
  `ExecutionPreference` remains on the domain model.
- `TaskHierarchyDestinationTest` has 3 tests covering all four documented cases: nested WorkStream
  task, standalone task, and a third asserting null for both missing and unplaced tasks.

### One observation, not a defect

The resolver's cycle guard (`visited`) has no test exercising a `parentTaskId` cycle. The guard is
correct by inspection and a cycle should be impossible given `StructureActions` rejects self-parent
and cycles on create, so this is a coverage gap rather than a risk. Noted for whoever next touches
the resolver.

---

## 2026-09-30 — Synchronization with Codex's hardening of Claude's three audit observations

**Requested by:** User. **Status:** Read-only synchronization. **No production file was
modified.** No correctness problem found. All three maintenance observations Claude raised in the
two previous sync entries are now closed.

Re-read the three architecture docs and both change logs, inspected the implementation, and
**re-ran the relevant suites in this working tree** rather than relying on the reported results.

### Confirmations

1. **The nullable/bare Text Note route trap is gone.** `textNoteRoute(captureId: String)` now takes
   a non-null id and `require`s it to be non-blank
   (`ui/note/TextNoteEditorScreen.kt:91-94`), so it can only return `text_note/$captureId`. The
   unused `TextNoteRoute = "text_note"` constant is removed, and a repository-wide search finds no
   remaining bare `"text_note"` navigation target. The guard has moved from a convention the caller
   had to remember into the type signature, which is the stronger fix.
2. **Existing Capture Notes still open through `text_note/{captureId}`.** The destination is still
   registered (`VirlinApp.kt:396`), and the two Inbox/ORB call sites still branch explicitly —
   `if (id == null) openRedesignedNotes(null, null) else navigate(textNoteRoute(id))`
   (`VirlinApp.kt:329-331` and `:604-606`) — so new drafts reach the redesigned workspace and only
   existing captures reach the legacy editor. The Knowledge/Activity paths
   (`HierarchyScreens.kt:230`, `:595`) still pass an id.
3. **The reconciliation test now saves through the production action boundary.**
   `TaskPageActionsTest.existing_redesigned_task_note_reconciles_using_the_shared_owner_key` calls
   `DefaultVirlinActions(...).saveNoteDoc(ownerKey = TaskPageTypeKeys.noteOwner(task.id), ...)`,
   then `TaskPageActions.loadAndReconcile(task.id)`, and asserts both
   `block.typeKey == TaskPageTypeKeys.NOTE` **and** `block.contentId == owner`. That second
   assertion is precisely the invariant whose absence allowed the original owner-key mismatch, so
   the regression is now pinned rather than merely fixed.
4. **The cycle guard has direct coverage.**
   `TaskHierarchyDestinationTest.corruptParentCycle_isBoundedAndStillUsesStableIds` builds a true
   two-node cycle (`first.parentTaskId = "second"`, `second.parentTaskId = "first"`) and asserts
   resolution terminates with the bounded, ID-based `workstream_detail/ws?path=second,first`.
5. **No documentation conflicts remain.** `docs/TASK_PAGE_FOUNDATION.md:37-38` now states
   `contentId == "task-<taskId>"` with the rationale that it is the canonical existing
   `virlin_notes.ownerKey` format constructed only by `TaskPageTypeKeys.noteOwner`. No `task:<taskId>`
   form survives anywhere in `docs/`. The owner-key statements in `NOTE_SYSTEM_CLEANUP.md:11-13`
   match the implementation.

### Verification performed by Claude in this tree

`:app:testDebugUnitTest` for the four relevant classes: **55 tests, 0 failures, 0 errors** —
`TaskPageActionsTest` 4, `TaskHierarchyDestinationTest` 4, `NotesEditorTest` 40,
`AgentCaptureLauncherTest` 7. No production or test file was changed to achieve this.

Claude's project memory `virlin-notes-canonical-vs-legacy` was updated with the hardened route
signature and the rule not to reintroduce a nullable or bare new-draft Text Note route.

---

## 2026-09-30 — Independent read-only project audit (no production changes)

**Requested by:** User. **Status:** Complete. **No production code, schema, migration, test, golden
or Gradle file was modified. No Git commit, branch, reset, stash or clean was performed.**

Full findings are in `docs/CLAUDE_PROJECT_AUDIT_REPORT.md` (new file; contains findings only and
amends no authoritative specification). `CODEX_CHANGES.md` was not touched.

**Verdict: Approved with corrections.** The baseline is accurate on every load-bearing claim tested
— Room v17, continuous non-destructive 1→17 migrations, complete exported schemas, UI never
touching a DAO, ID-based routing with cycle guards, the cream `TaskDetailScreen` genuinely deleted,
and exact test-file counts (83 JVM / 46 instrumentation).

**Four critical findings:**

1. `verifyRoborazziDebug` fails **18 of 1053** tests, not one — 17 stale goldens plus the known
   `CaptureBoundaryTest` failure — including `nowScreen_matchesApprovedBaseline` and
   `appScaffold_matchesApprovedBaseline`, the frozen Now UI. Plain `testDebugUnitTest` reports 1
   because it does not compare; the docs cite that number while describing the other task's rules.
2. `CLAUDE.md`, `CURSOR.md` and `GLM.md` contain **zero** references to the new documentation
   baseline, so `AGENT_COORDINATION.md`'s start-of-task protocol is unreachable. `CURSOR.md` and
   `GLM.md` still present `DEVELOPMENT_STATUS.md` as current status although it self-describes as
   historical.
3. `videos/` (330 MB), `tmp-diff/`, `.tmp/`, `tmp-blackscreen/`, `tmp-acceptance/`, `maestro/` and
   `.mcp.json` are untracked and not gitignored; a naive checkpoint would put ~380 MB into history.
4. `CLAUDE.md:308` says schema **v16**; live is **v17**. This line is Claude-authored staleness
   predating Codex's v17, not a Codex error.

**Other findings:** `work_streams` should be `workstreams` in `DATA_AND_STORAGE.md`;
`stream_detail/{id}` is registered with zero callers and is undocumented; `TaskPageBlockRegistry`
does not exist (four `when` blocks in `TaskPageScreen.kt` instead); `task_page/` gets no shared
footer/Orb while `notes/` does, and the rule is unwritten; `TaskPageActions` is not on the
`VirlinActions` facade yet is constructed inline in Compose; the orphaned `task_detail.png` golden
is still tracked; Prompt, Link, File and Voice have no authoritative specification.

**Verification run by Claude:** compile of all three source sets 7 s BUILD SUCCESSFUL;
`testDebugUnitTest` 40 s → 1053 tests / 1 failure; `verifyRoborazziDebug` 64 s → 1053 tests / 18
failures. `connectedDebugAndroidTest` was deliberately not run (writes to the on-device database).

**Recommendation:** not yet safe to start parallel PDF and Audio work. Resolve the goldens, fix the
ignore rules, correct the four stale statements, route the agent entry files to the index,
checkpoint on an integration branch, then make the PDF/Audio contract decision (reuse vs specialize
vs new document contract — tradeoffs in section I; Claude recommends specializing the existing
Capture contracts, with Audio and Voice distinguished as products first).

---

## 2026-09-30 — Final documentation and coordination audit (no production changes)

**Requested by:** User. **Status:** Complete. Read-only. Report:
`docs/CLAUDE_FINAL_DOCUMENTATION_AUDIT.md`. `CODEX_CHANGES.md` and all Codex-authored `docs/` files
were left untouched.

**Verdict: Approved with corrections.** Every correction from the previous audit
(`docs/CLAUDE_PROJECT_AUDIT_REPORT.md`) has been applied by Codex and independently re-verified:
all four entry files now route to `PROJECT_DOCUMENTATION_INDEX.md` and `AGENT_COORDINATION.md`;
`CLAUDE.md` states schema v17 including `task_page_blocks`; `DATA_AND_STORAGE.md` says
`workstreams`; `stream_detail/{id}` is documented as obsolete/unreachable; `videos/`, `tmp-*`,
`.tmp/` and `.mcp.json` are now gitignored; Prompt, Link, File/Attachment and Voice each have a
specification and are indexed; `docs/handoffs/` exists; and the integration owner is named.

**Independently rerun today:**

- `:app:testDebugUnitTest` — **1053 tests, 0 failures**. The long-standing
  `CaptureBoundaryTest.types_are_explicit_and_payloads_raw` failure is genuinely fixed; the ordinary
  suite is green for the first time across this audit series.
- `:app:verifyRoborazziDebug` — **1053 tests, 17 visual failures**, matching
  `GOLDEN_BASELINE_REVIEW.md` exactly, including the `_hierarchy` filename suffixes and the
  genuinely absent `agent_capture_live_launcher.png`. Treated as a user-approval gate, not code
  failures. **No golden was recorded, replaced or deleted.**

**One blocking correction, and it is tooling rather than documentation:** the Graphify index is
stale — 36 Kotlin files are newer than `graphify-out/graph.json`, and `TaskPageActions`,
`TaskPageScreen` and `TaskPageTypeKeys` have **zero nodes**. Since every entry file instructs agents
to query Graphify first, the project's designated extension point is currently invisible to that
workflow. Smallest safe fix, documented but deliberately not run: `graphify update .`. Second
blocking item: decide whether `maestro/`, `.cursor/` and `.glm/` are committed or ignored before the
checkpoint, since `CURSOR.md` and `GLM.md` depend on those directories.

**Non-blocking:** `PROJECT_DOCUMENTATION_INDEX.md` has no Graphify guidance; the KDoc at
`TaskPageScreen.kt:44` claims one registry is the Page's only type-specific seam when there are four
(the documentation at `TASK_PAGE_FOUNDATION.md:51-53` is correct — the comment is not); the orphaned
`app/src/test/screenshots/task_detail.png` is still tracked with no test.

**User decision still required before parallel PDF/Audio work:** whether mind-map Audio is the same
product as Capture Voice (the Page already labels `capture.voice` blocks "Audio"), whether Audio is
imported or recorded, whether PDF specializes `AttachmentKind.PDF` or becomes its own document, and
whether either needs v18. Claude's recommendation remains to specialize the existing Capture
contracts, but this is explicitly not decided here.

**Safe to checkpoint and to update the remote** once the two blocking items are settled. Parallel
PDF and Audio lanes may begin after the visual approval pass and the checkpoint.

---

## 2026-09-30 — Post-remediation verification before checkpoint (no production changes)

**Requested by:** User. **Status:** Read-only re-check of Codex's remediation. No production code,
schema, golden or Git state was changed. Deliberately did **not** create a third audit document —
the previous audit flagged duplicated authorities as a documentation smell, so this delta is
recorded here and reported in chat instead.

**Independently verified — every Codex claim holds:**

- Graphify refreshed (`graph.json` 2026-09-30 23:13). `TaskPageActions`, `TaskPageScreen` and
  `TaskPageTypeKeys` now resolve in the graph; they had **zero** nodes before. Only one Kotlin file
  is newer than the index — `TaskPageScreen.kt`, whose sole change is the KDoc fix below, so the
  graph is structurally current.
- `PROJECT_DOCUMENTATION_INDEX.md` now carries the token-efficient Graphify workflow.
- `AGENT_COORDINATION.md:46-48` documents `.cursor/`, `.glm/` and `maestro/` as commit-required
  project infrastructure.
- `maestro/` is already tracked (4 files).
- The `TaskPageScreen.kt` KDoc now reads "Label registry only. Row projection, icons, and open
  routing are separate guarded seams below." — accurate, and it matches
  `TASK_PAGE_FOUNDATION.md:51-53`.
- Ignore rules intact: `graphify-out/`, `.mcp.json`, `videos/`, `tmp-*`, `.tmp/` and
  `local.properties` all ignored.

**Independent push-safety scan of `.cursor/` and `.glm/`:** 38 files, all Markdown, 184 KB total.
Two credential-pattern hits (`.cursor/rules/SAFETY.md:6`, `.glm/rules/SAFETY.md:5`) are
*prohibitions against* secrets, not secrets. No machine-specific absolute paths. Safe to commit.

**Verification rerun by Claude:** `:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin`
— **BUILD SUCCESSFUL, 1053 tests, 0 failures** (2 m 32 s). The 17 Roborazzi differences remain an
untouched user-approval gate; **no golden was recorded, replaced or deleted** (24 images, clean
`git status`).

**Checkpoint footprint:** 61 modified, 1 deleted, 84 untracked, **3.1 MB** total. The only file over
1 MB is `res/drawable-nodpi/virlin_app_pages.png`, a legitimate app drawable.

**Recommendation:** ready to checkpoint and push. Claude advised committing **without** recording
the 17 goldens, because the user's phrase "exclude these" is not the explicit approval that
`CLAUDE.md` requires before overwriting committed baselines — and recording them later is a
separate, reviewable commit, whereas an unapproved overwrite of the frozen Now baseline is not
cheaply undone.

---

## 2026-09-30 — Integration checkpoint commit `38ee49e`

**Requested by:** User. **Status:** Complete. Local commit only — **no push, no PR, no merge into
`main`, no rebase/reset/clean/stash, no golden change.** `CODEX_CHANGES.md` untouched.

Created branch `integration` from `feature/now-day-summary` @ `1e6d821` and committed the verified
baseline as `38ee49e` — **205 files, +37,016 / -2,148** (148 added, 56 modified, 1 deleted).

**History preserved:** all 26 commits that were unmerged into `main` remain ancestors of the
checkpoint; `feature/now-day-summary` still points at `1e6d821`; `main` still equals `origin/main`
at `e65c6e0`. Nothing was squashed, rebased or duplicated.

**Verification immediately before committing:** `:app:testDebugUnitTest` +
`:app:compileDebugAndroidTestKotlin` — BUILD SUCCESSFUL, **1053 tests, 0 failures**;
`:app:verifyRoborazziDebug` — **exactly 17** visual differences, the documented approval gate.
Goldens unchanged before and after (24 tracked = 24 on disk; `agent_capture_live_launcher.png`
still absent).

**Safety checks:** `git add -A` dry-run proved no ignored path could enter the index, and the
committed file list contains no ignored artifact and no screenshot. Only one file exceeds 1 MB —
`res/drawable-nodpi/virlin_app_pages.png`, verified as a real 1254x1254 PNG referenced by
`VirlinAppsScreen.kt`. No credential values anywhere. The deletion of
`ui/screens/PulseScreen.kt` was confirmed superseded by `ui/pulse/` with no dangling references.

**Two findings reported rather than silently handled:**

1. A **stale `.git/index.lock`** blocked staging — 0 bytes, dated 2026-09-29 23:59 (about 24 hours
   old), with no `git.exe` process running and `.git/index` intact at 358 tracked files. Diagnosed
   as a crashed earlier process, not a live agent, and removed. No other Git state was touched.
2. `docs/CLAUDE_FINAL_DOCUMENTATION_AUDIT.md:4` contains the absolute workspace path
   `C:\Users\tre21\...` in its provenance header. Harmless documentation, not a build path or
   secret, so it was committed as-is; it can be genericized later if preferred.

`core.autocrlf=true` normalized line endings, which is why 61 modified files produced 56 content
diffs — the other five differed only in line endings.

---

## 2026-10-01 — Publication complete: integration merged into `main` as `454d2ae`

**Requested by:** User. **Status:** Complete. No force-push, rebase, squash, reset, clean or
stash; `38ee49e` was not amended; no golden was recorded, replaced or approved; no app data was
cleared; `CODEX_CHANGES.md` untouched.

### Outcome

`integration` (`3a72161`) is merged into `main` as **`454d2ae` — "Merge pull request #10 from
virlinvh/integration"**. The PR was created and merged on GitHub by the user while this workflow
was running; Claude did not create or merge it, because `gh` has no authenticated host and no
`GH_TOKEN`/`GITHUB_TOKEN` is set.

**Merge integrity verified independently.** The merge commit has two parents, `e65c6e0` and
`3a72161`, so it is a true merge commit rather than a squash. All 26 previously unmerged feature
commits survive — `1e6d821` is an ancestor of `main` — and 29 commits in total were added. Local
`main` was brought up to date with a fast-forward pull only.

### Verification performed by Claude

Before the merge, on `integration`:

- `:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin` + `:app:assembleDebug` —
  **BUILD SUCCESSFUL, 1053 tests, 0 failures**; APK 20.8 MB.
- Independent review of the full PR diff (`main..integration`): 278 files, +54,403 / -2,382;
  194 `.kt`, 64 `.md`, 10 `.json`, 4 `.png`, 4 `.mdc`, 1 `.xml`, 1 `.gitignore`. **No ignored or
  generated path, no golden screenshot, no secret, no conflict.** The only binaries are the four
  verified `drawable-nodpi` app icons.

After the merge, on `main` at `454d2ae`:

- **1053 tests, 0 failures**; Android-test Kotlin compilation successful.
- Schema still **v17** with 17 exported schemas; 24 golden images tracked and on disk;
  `agent_capture_live_launcher.png` still absent.
- `git diff 3a72161 454d2ae` is empty, so the APK built from the integration tip is byte-equivalent
  to merged `main` — the installed build already is the merged application.

### Emulator verification

Installed with `adb install -r` (no uninstall, no data clear) after snapshotting `virlin.db`,
`-wal` and `-shm`. The app launched cleanly: fresh pid after `force-stop`,
`topResumedActivity=com.virlin.app/.MainActivity`, **zero FATAL/ANR lines** and no Room errors.
Database comparison before and after the install: schema v17 unchanged, 21 tables, **no row-count
difference — all user data preserved**.

**Visual capture limitation, reported rather than hidden:** the emulator framebuffer froze and
returned an identical torn frame on repeated `screencap` calls (the renderer hang seen earlier in
this project). Maestro is not installed, and `uiautomator dump` returns an empty hierarchy for this
Compose UI, so the Now-surface assertions in `maestro/now/verify-now.yaml` could **not** be
confirmed visually. Launch health is evidenced by process state and logcat only. A visual pass
needs an emulator cold boot.

### Still open

The 17 Roborazzi differences remain the untouched user-approval gate described in
`docs/GOLDEN_BASELINE_REVIEW.md`, and the PDF/Audio product decision in
`docs/CLAUDE_FINAL_DOCUMENTATION_AUDIT.md` §6 is still required before parallel lanes begin.

---

## 2026-10-01 — Audio v1 (record-only) on `feature/audio-workspace`

**Requested by:** User, with an approved product decision: **Audio v1 is record-only.**
**Status:** Implemented and verified. Branch not merged. No golden recorded or replaced; Room
stays at **v17**; `CODEX_CHANGES.md` untouched.

### What Audio v1 is

The mind-map **Audio** tile opens the **existing Capture Voice workspace** with the selected task
supplied explicitly. **There is no new Audio entity, capture type, table, storage model or editor.**
Imported audio remains `AttachmentKind.AUDIO` through the File flow and is deliberately not offered
in this entry.

### The smallest adapter

- `VoiceEditorViewModel` takes an optional `initialContext: CaptureContext`, applied **only** when
  creating a new recording. An existing capture still hydrates its owner from the persisted row in
  `loadExisting`, so reopening a saved recording can never be re-homed by its route.
- `voiceEditorForTask(taskId)` builds `voice_editor/new/task/{taskId}` and `require`s a non-blank
  id, following the Prompt/Link task-route pattern. Registered in `VirlinApp`.
- Only `taskId` travels; `CaptureActions` derives project and WorkStream from the task, keeping one
  source of truth for ownership.
- `MapAddKind.AUDIO` is now supported and routes from `ProjectMapScreen`. Its existing `MicNone`
  icon was already correct.
- Saving continues to go through the existing `CaptureActions` transaction, which registers the
  `capture.voice` Page block automatically. No separate registration path was added.

### Shared seam correction — now owned by the Audio lane

`openBlock` in `ui/page/TaskPageScreen.kt` had **no branch for `capture.voice` or `capture.file`
and no `else`**, so both block types rendered and were labelled correctly but their taps did
nothing at all. Fixed for both, using each feature's own published builder after verifying the
registered destinations: `voiceEditorRoute(contentId)` → `voice_editor/{captureId}` and
`fileViewerRoute(contentId)` → `file_viewer/{captureId}`. No route was invented. An unknown
`typeKey` now reports through the Page's existing message dialog instead of failing silently.
Labels, row projection, icons, ordering, reconciliation and ownership were **not** touched.
**The PDF lane must reuse the `capture.file` case and not edit that block.**

### Files changed

Production: `ui/voice/VoiceEditorViewModel.kt`, `ui/voice/VoiceEditorScreen.kt`,
`ui/navigation/VirlinApp.kt`, `ui/map/MapAddPalette.kt`, `ui/map/ProjectMapScreen.kt`,
`ui/page/TaskPageScreen.kt`.
Tests: `test/.../AudioWorkspaceTest.kt` (new, 12 tests).
Docs: `AUDIO_FEATURE.md` and `AUDIO_FEATURE_AUDIT.md` (new); `FEATURE_REGISTRY.md`,
`NAVIGATION_AND_FEATURES.md`, `TASK_PAGE_FOUNDATION.md`, `PROJECT_DOCUMENTATION_INDEX.md`,
`VOICE_FEATURE.md` updated.

### Verification

- `AudioWorkspaceTest`: **12 tests, 0 failures**.
- `:app:testDebugUnitTest`: **1065 tests, 0 failures** (1053 baseline + 12 new).
- `:app:compileDebugAndroidTestKotlin` and `:app:assembleDebug`: **BUILD SUCCESSFUL**.
- `:app:verifyRoborazziDebug`: **17 failures — the same 17 documented differences, name for name.
  Zero new or changed visual differences.** No golden was recorded, replaced or deleted.
- No database or schema file changed; no ignored artifact staged.

### Investigated and documented, deliberately NOT implemented

1. **No maximum recording duration or file-size policy** — `MediaRecorder.setMaxDuration` and
   `setMaxFileSize` are never called, so a recording can grow unbounded.
2. **Archiving a voice capture orphans its media** — `VoiceFileStore.deleteClip` and
   `deleteCaptureTree` are called only from the editor; `archiveCapture` does not touch managed
   storage, so `.m4a` files survive indefinitely. Destructive cleanup was not implemented because
   deletion semantics are shared with Attachment and Prompt and should be designed once for all
   capture types.
3. **Missing or corrupt media is already safe** — the player is built with
   `runCatching { ... }.getOrNull()`, so a bad file yields a null player rather than a crash.
4. Recording across process death is untested, and the emulator microphone is synthetic.

---

## 2026-10-01 — Attachment workspace (task-scoped universal file space)

**Requested by:** User, with binding decisions: reuse the existing resolver, imported audio is an
Attachment rather than a Voice capture, PDF keeps its canonical workspace, reuse the existing office
renderers.
**Branch:** `feature/attachment-workspace`, based on Codex's integration commit `e28b521`.
**Status:** Implemented and verified by build and tests. **Emulator walkthrough is BLOCKED by the
environment, not by this code — evidence below.** `CODEX_CHANGES.md` untouched.

### Audit outcome — most of the request already existed

`UniversalFileViewer` (587 lines) already renders all ten kinds, **including DOCX, XLSX and PPTX**
through `OoxmlReaders`, each bounded and disclosing truncation. A centralized
`AttachmentKindResolver` already existed. Building the specified `AttachmentPreviewCapability` would
have created a second resolver, which the brief itself forbids, so the existing one was extended.

**Room was not changed and stays at v17.** `attachment_documents.kind` is a String column decoded
with `runCatching { valueOf }.getOrDefault(UNSUPPORTED)`, so adding enum values is purely additive,
leaves existing rows untouched, and lets an older build read a newer row safely.

### What was built

- `AttachmentKind` gained `MARKDOWN` and `ARCHIVE`; `AttachmentKindResolver` gained their
  extensions/MIMEs, labels, and a `previewOf(kind)` accessor returning `IN_APP`, `PDF_WORKSPACE` or
  `DETAILS_ONLY`. One resolver, not two.
- `domain/attachment/MarkdownPreviewParser` — a conservative parser with **no HTML block type at
  all**, so raw HTML stays literal text and nothing can reach a JavaScript-capable WebView.
- `ui/attachment/` — route contract, ViewModel and screen: library with per-row View/Play/Details,
  multi-select import via `OpenMultipleDocuments`, progress, per-file failure reporting, rename
  preserving the extension, Share and Open-with through `FileProvider`, confirmed removal, and a
  missing-file state.
- `UniversalFileViewer` gained a Markdown viewer with a Preview/Source toggle and a "File stored
  safely" details view for archives and unknown binaries, replacing the blunter
  "cannot render this format" screen.
- Wiring: `attachment_workspace/new/task/{taskId}` registered, `MapAddKind.ATTACHMENT` enabled and
  routed. **PDF and Audio wiring preserved byte-for-byte; the Task Page `openBlock` seam was not
  touched.**

### Imported Audio is not a Voice capture

Imported audio stays `CaptureType.FILE` + `AttachmentDocument(kind = AUDIO)` in
`filesDir/attachments/`, appears as a `capture.file` block, and opens the read-only `AudioViewer`.
Voice recording stays `CaptureType.VOICE` + `VoiceDocument` in `filesDir/voices/` as a
`capture.voice` block opening the Voice workspace. An imported MP3 has no `VoiceDocument`, so
routing it into the recorder would open an empty editor over the user's file.

### One pre-existing test updated, deliberately

`AttachmentCaptureTest.kindResolver_mapsCommonTypes` asserted `a.zip` resolves to `UNSUPPORTED`.
Archives are now their own kind, so that line was updated to `ARCHIVE` **and an assertion added that
`ARCHIVE` still resolves to `DETAILS_ONLY`** — user-facing behaviour is unchanged (stored, described,
never previewed or extracted); the kind is simply labelled honestly. This was not a weakened
assertion.

### Verification

- `AttachmentWorkspaceTest`: **22 tests, 0 failures**.
- `:app:testDebugUnitTest`: **1091 tests, 0 failures** (1065 before this lane + 22 new + 4 from the
  rebased integration base).
- `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, `:app:assembleDebug`: passed.
- `:app:verifyRoborazziDebug`: **17 failures — exactly the documented baseline, no new or changed
  visual difference.** No golden recorded, replaced or deleted.

### Emulator verification BLOCKED — and it is not this code

The app installs and launches (process alive, `MainActivity` top-resumed, **zero exceptions in
logcat**) but renders a black window while the launcher renders normally.

**Decisive test:** the base integration APK built from `e28b521` — the exact build that rendered the
PDF workspace correctly earlier the same session — was installed and produced an **identical 15,845
byte black frame**. The regression is therefore in the emulator's surface for this app, not in the
Attachment changes. A clean shutdown and restart left the emulator stuck `offline` with a 7.5 GB
resident QEMU process, so the walkthrough could not be completed.

**Outstanding:** the import walkthrough (Markdown, TXT, DOCX, image, audio, video, PDF, ZIP), Page
block reopening and restart persistence remain unverified on device. They should be run once the
emulator is healthy, before this branch is merged.

---

## 2026-10-01 — Emulator fixed, and the Attachment walkthrough completed

**Root cause of the black screen: the emulator's host GPU, not any Virlin code.** Starting the
Pixel 8 AVD with `-gpu swiftshader_indirect` (software rendering) restored it immediately: the same
APK that produced a 15,845 byte black frame now renders at ~658 KB. This matches the earlier
decisive test in which the base `e28b521` APK reproduced the black frame identically.

**For future sessions:** if this app renders black while the launcher renders normally, restart the
emulator with `-gpu swiftshader_indirect` before suspecting the code.

### Walkthrough completed on `feature/attachment-workspace` (`fe7eb90`)

Installed with `adb install -r`; app data was never cleared. **Zero crashes throughout.**

1. **Palette** — Attachment is enabled alongside To-do, Note, Prompt, Link, PDF and Audio. Image,
   Sticker and Illustration remain honestly "Not yet".
2. **Empty state** — "Attachments / Attached to · Pixel 8 Validation", the Add-any-file card, and
   "No files yet · Anything you add stays on this device, attached to this task."
3. **Multi-select import** — five files selected in one picker pass (`release-notes.md`,
   `server.log`, `source-bundle.zip`, `unknown.bin`, `virlin-pdf-validation.pdf`) and all five
   imported, giving "Saved files · 5".
4. **Contextual actions resolved by kind** — **View** for Markdown, text and PDF; **Details** for
   the archive and the unknown binary. Badges: MD, LOG, ZIP (amber), BIN, PDF (red).
5. **Markdown preview** — Preview/Source chips, H1/H2 headings, checked and unchecked task
   checkboxes, the fenced `bash` code block rendered monospaced on dark, and the quote line.
6. **Archive details** — "File stored safely / Archives are kept exactly as imported. Virlin does
   not open or extract them." with name and `application/zip`. Nothing was extracted.
7. **PDF handoff** — opening the imported PDF routed **by kind** into Codex's canonical PDF
   workspace, which loaded it as "5 pages · 81.4 KB" with Preview, Annotate, Organize, Crop, OCR
   and Export all enabled. Cross-feature integration confirmed working.
8. **Task Page** — each imported file appears as its own independent block with its resolved kind:
   `release-notes.md · Markdown`, `server.log · Text`, `source-bundle.zip · Archive`,
   `unknown.bin · File`, and `virlin-pdf-validation.pdf` labelled **PDF** by Codex's kind-aware
   projection. Pre-existing Note and Link blocks were untouched.
9. **Persistence** — after a full force-stop and relaunch, all five files and their Page blocks
   were still present.

This supersedes the "emulator verification BLOCKED" note in the previous entry. The Attachment lane
is now verified end to end on device as well as by the 1091-test suite.
## 2026-10-01 — Image workspace emulator audit and crash fix

Branch `feature/image-emulator-fix`, from `codex/image-workspace` @ `b96f998`, in an isolated worktree.

**Files touched**
- `app/src/main/java/com/virlin/app/ui/image/ImageWorkspaceScreen.kt` — removed two
  `DisposableEffect { onDispose { bitmap.recycle() } }` blocks (`EditPreview`, `ManagedBitmap`).
- `app/src/test/java/com/virlin/app/image/ImageBitmapLifecycleTest.kt` — new source-level guard.
- `docs/IMAGE_EMULATOR_AUDIT.md` — new.

**Requested outcome**
Audit-first reproduction of "tapping a file in the picker returns to the launcher", then a minimal
safe fix only if reproducible.

**Behaviour impact**
The reported symptom was a real crash, but not in the picker or the import pipeline — the import
had already succeeded and the record was persisted. The app died in a Compose draw frame with
"Canvas: trying to use a recycled bitmap", because `asImageBitmap()` wraps a Bitmap without
copying and a RenderNode display list can replay after `onDispose`. Removing the recycles fixes
it; `minSdk 26` makes them unnecessary anyway. `ImageRenderEngine`'s recycles were examined and
left alone — those are internal intermediates never handed to Compose.

**Verification actually performed**
Reproduced twice with logcat captured before the triggering tap. Post-fix on the emulator: same
PID before and after (9145), `MainActivity` still resumed, 0 crashes. Unit tests, android-test
compilation and `assembleDebug` pass; Roborazzi reports exactly the 17 known pre-existing diffs
and no golden was recorded; Room stays at v17. The new guard was proven to fail when the recycle
is reintroduced and pass when it is not.

**Not verified** — a substantial part of the acceptance checklist, listed explicitly in section 8
of `docs/IMAGE_EMULATOR_AUDIT.md`. This feature is NOT fully acceptance-tested.

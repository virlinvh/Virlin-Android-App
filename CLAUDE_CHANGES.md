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

## 2026-10-01 — Final content-workspace integration and Image acceptance

Branch `claude/final-content-workspaces`, cherry-picked from `a1a5a44` + `b96f998` + `da60ce8` in a
fresh worktree. Full report: `docs/FINAL_CONTENT_WORKSPACES_INTEGRATION.md`.

**Files touched (beyond the two cherry-picked Image commits)**
- `app/src/main/java/com/virlin/app/ui/image/ImageWorkspaceContract.kt` — added
  `ImageBackAction` / `imageBackAction(...)`.
- `app/src/main/java/com/virlin/app/ui/image/ImageWorkspaceScreen.kt` — `back()` now uses it.
- `app/src/test/java/com/virlin/app/image/ImageBackNavigationTest.kt` — new (5 tests).
- `docs/FINAL_CONTENT_WORKSPACES_INTEGRATION.md` — new.
- `docs/FEATURE_REGISTRY.md`, `docs/NAVIGATION_AND_FEATURES.md`,
  `docs/PROJECT_DOCUMENTATION_INDEX.md`, `docs/TASK_PAGE_FOUNDATION.md`,
  `docs/TESTING_AND_RELEASE.md` — reconciled to the integrated state.

**Behaviour impact**
Image is now integrated and enabled alongside PDF, Audio and Attachment. One new defect was found
during acceptance and fixed: opening an IMAGE block from the Task Page trapped the user in the
editor, because `back()` set the mode to EDIT while already in EDIT, so `popBackStack()` was never
reached and neither the arrow, system Back nor the edge-swipe could leave. Back now exits that
route. Nothing else in production code changed; conflicts were confined to documentation and the
two append-only agent logs, both of which keep every entry verbatim.

**Verification actually performed**
1,105 JVM tests (0 failures) — exactly 1,091 + 7 + 2 + 5, so nothing was lost in the merge;
android-test compilation; `assembleDebug`; connected `ImageRenderEngineTest` 2/2 on the emulator;
Roborazzi at exactly the 17 documented differences with no golden changed; Room still v17. Both new
guards were proven to fail when their defect is reintroduced. A full emulator acceptance matrix was
run in a disposable `ImageAudit` project — no real project was touched and app data was never
cleared.

**Known limitation found:** a corrupt image with a valid MIME type and non-zero size is accepted and
shown as a placeholder rather than refused at import. It fails safely but is not rejected; left
unchanged as a product decision.

## 2026-10-02 — Mind map node previews

Branch `feature/mind-map-node-preview`, from `origin/main` @ `a30d676`. Contract:
`docs/MIND_MAP_NODE_PREVIEW.md`.

**Files touched**
- New: `domain/mappreview/` (MapNodePreview, FormattedTextEditor, MapPreviewCodec),
  `data/mappreview/MapPreviewImageStore.kt`, `domain/action/MapPreviewActions.kt`,
  `ui/map/preview/` (draft, popup, rendering, host), `docs/MIND_MAP_NODE_PREVIEW.md`,
  two JVM test files.
- Modified: `VirlinEntities.kt`, `VirlinDatabase.kt` (v18 + migration + DAO),
  `RoomWorkStreamRepository.kt`, `InMemoryWorkStreamRepository.kt`,
  `BootstrappingWorkStreamRepository.kt`, `WorkStreamRepository.kt`, `VirlinActions.kt`,
  `DefaultVirlinActions.kt`, `ActionResult.kt`, `VirlinMindMap.kt`, `ProjectMapScreen.kt`,
  `VirlinMigrationTest.kt`, `DATABASE_MIGRATION_QUEUE.md`.

**Behaviour impact**
Optional compact previews on mind-map nodes: one formatted description (bold/italic only, 180
chars) and up to seven images, with a popup editor and an expanded in-node carousel. Resting nodes
are unchanged, and the lower Open Task / Move / Page / Add bar is untouched. Previews are stored in
their own `map_node_previews` table keyed by the node's stable entity id and never create a
Capture, an Inbox item or a Task Page block - verified on device. Images live in their own managed
root with relative paths, never Attachment storage.

Room v18 was reserved in the migration queue under the user's explicit instruction in the brief;
the queue otherwise reserves versions to the integration owner, so the attribution is recorded
there.

**Two defects found and fixed during emulator acceptance**
1. `selectedId` was not a `pointerInput` key, so the node-level plus compared against a stale null
   and never opened the editor.
2. The caret and pencil were drawn by the Canvas, which skips the expanded node - so an expanded
   node could not be collapsed. Both controls now live inside the overlay card, where they also get
   real touch targets and semantics.

**Verification actually performed**
1,152 JVM tests (0 failures) = 1,105 + 47 new; android-test compilation; `assembleDebug`;
`VirlinMigrationTest` 17/17 on the emulator including a non-destructive v17->v18 check and a v1->v18
long chain; Roborazzi at exactly the 17 documented differences with no golden changed. Manual
emulator acceptance of the full flow in a disposable project, including a live v17->v18 upgrade of
restored real data.

**Not verified:** no instrumentation UI tests were added for the preview itself; acceptance was
manual. Reflow was exercised only in the tree layouts. Limitations are listed in the contract doc.

**Data note:** running connected instrumentation tests reseeds the emulator's `virlin.db` (Virlin's
instrumented tests share the real database and `DemoStateRule` restores the demo fixture). This
cleared 3 captures / 3 Page blocks / 3 attachments during this work; they were restored from a
snapshot at the user's direction, and the user confirmed connected tests may be run freely.

## 2026-10-04 — Execution/appearance/audit checkpoint reviewed and published

Claude acted as reviewer and publisher for this checkpoint. **The feature and remediation work in
it is Codex's**, recorded in `CODEX_CHANGES.md` and `docs/AUDIT_REMEDIATION_2026-10-04.md`; this
entry records only the review, verification and publication.

**Checkout and branch.** Verified that `C:\tmp\virlin-execution-timing` — a worktree of the main
repository on `codex/now-live-execution-audit` — holds the latest work. The original checkout at
`C:\Users\tre21\Documents\Android Apps\Virlin` is stale (`feature/attachment-workspace`, clean) and
was not modified. The branch was 5 ahead / 0 behind `origin/main` (`a30d676`), so it fast-forwards.

**Reviewed, not blindly staged.** 83 tracked modifications (+2,096 / −575) and 40 untracked files
were inventoried individually. All 40 untracked files are source, exported schema, tests, docs or
resources; no APK, database, log, keystore or SDK download was staged. A secret-shaped scan over all
124 files found nothing, and `.gitignore` already covers APKs, keystores and build outputs.

**Claims traced to implementation rather than taken from documentation.** Leaf-only execution is
enforced in the action layer (`ExecutionEligibility` used from `DefaultVirlinActions` and
`StructureActions`), not only in UI, and a workstream with no tasks correctly reads as a leaf so it
can run itself. `DemoSeed.applyIfEmpty` is gated behind `BuildConfig.DEBUG`, so production durable
startup cannot insert sample data; the unconditional `DemoSeed.build` feeds only the in-memory
fallback repository. Ownerless standalone execution is rejected before any cycle/session write.
Schema is v20 with 19 registered migrations; 18→19 and 19→20 are additive `ADD COLUMN` plus a
backfill, and there is no `fallbackToDestructiveMigration` anywhere in `app/src/main`. Release
signing requires all four `VIRLIN_*` variables or none, so a partial configuration fails clearly and
no credentials exist in the repository. The 24 appearance presets with `Sand` as the 24th are pinned
by `assertEquals(24, projectColorChoices.size)`.

**Verification rerun rather than quoted.** The first run reported 127 of 129 tasks up-to-date, so its
results were Codex's, not a fresh check. A `--rerun-tasks` attempt then failed with an out-of-memory
error in `:app:compileReleaseKotlin` (`org.gradle.jvmargs=-Xmx2048m`) before reaching the tests — an
environment limit, not a code defect. The checks were therefore rerun in stages, each genuinely
executed:

- `testDebugUnitTest`: **1,228 passed, 0 failures / 0 errors / 0 skipped** (task executed, results
  rewritten).
- `lintDebug`: **0 errors, 127 warnings, 12 informational** (reports deleted first and regenerated).
- `assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease`: all succeeded. Release output is
  `app-release-unsigned.apk` (14,102,958 bytes) — correctly unsigned without owner credentials.
- Instrumentation: **40 passed, 0 failures**, pinned with `ANDROID_SERIAL=emulator-5556` to the
  disposable `Virlin_Audit_20261004`.

**Device safety.** The serial-to-AVD mapping was re-verified immediately before any device work:
`emulator-5554` → `Pixel_8` (the user's data) and `emulator-5556` → `Virlin_Audit_20261004`. Nothing
was installed on, tested against, cleared or reset on `emulator-5554`; its `lastUpdateTime` remained
`2026-10-04 16:44:30`, before this session, and its databases were untouched.

**Observations left for follow-up, not changed here.** `gradle/libs.versions.toml` still declares
`agp = "8.6.1"`, which is unreferenced — the build applies 8.9.1 from the root `build.gradle.kts`,
so it is a stale value that misleads a reader rather than a build problem. The remediation report
states `core.autocrlf=false` / `core.whitespace=cr-at-eol`, but this checkout has
`core.autocrlf=true` and no `core.whitespace`; `git diff --check` is clean either way. Neither was
modified, to keep unrelated changes out of the checkpoint.

## 2026-10-10 — Multi-session checkpoint audited and committed

Audit and commit of work accumulated across several sessions in the main checkout
(`C:\Users\tre21\Documents\Android Apps\Virlin`), reviewed from the files and the diff rather than
from chat summaries.

**State found.** The branch `feature/attachment-workspace` sat at `d77d8cd`, identical to
`origin/codex/now-live-execution-audit`, with **no upstream** and **no unpushed commits** — all the
new work was uncommitted: 80 modified, 2 deleted, 109 untracked. All 109 untracked files were
source (31), tests (57) or documentation (21); no APK, database, log, keystore, build output or
machine-specific file was present, nothing exceeded 300 KB, and a secret-shaped scan over all 188
files found nothing.

**Claims checked against the implementation.** `DailySavings` is completion-based estimated effort
minus recorded human focus for leaf tasks completed today, floored at zero — its own comment says
"not external wall-clock time" — and it degrades honestly rather than inventing a figure. There is
no live "external time today" aggregate anywhere, matching the statement that it is not
implemented. `DailyFocus` clamps sessions to the day, de-duplicates and never counts future time,
keeping human and external separate. Schema stays at **v20** with no entity change; the only
database additions are two queries over the existing `meta` table, so no migration is required.
Subtree deletion runs inside one Room transaction against an explicitly computed scope and writes a
recovery receipt to `meta` before deleting, so interrupted file cleanup is resumable. The Orb's
route predicate was separated from the bottom bar's and popups suppress it. The FOCUSING NOW pulse
is gated on `ValueAnimator.areAnimatorsEnabled()`, so it honours reduced-motion.

**The stalled dialog tests — diagnosed, not worked around.** The stall reproduces. A full-suite run
produced one test XML and then nothing for 15 minutes; a thread dump showed the worker parked in
`RobolectricIdlingStrategy.runUntilIdle` → `ComposeIdlingResource.isIdleNow`, with
`com.virlin.app.now.InlineLeaveUiTest.customStaysInLeaveDialogAndSavesExactMinutes` on the stack.
Running that class alone produced no result in 7 minutes. It is **not** environmental: the
pre-existing Compose test `AgentEntryLayoutTest` runs 5 tests in 33 seconds on the same machine.

**Correction to the commit message of `ca3a44b`:** that message attributes the stall to
`SafeDialog`. Further bisection **disproved** this, and the attribution should be read as withdrawn.

The cause is **not** `SafeDialog`, not text input and not the activity-result launcher. Each was
probed in isolation inside a dialog and each idles normally: a plain Compose `Dialog` (31 s), a
`SafeDialog` with static content (63 s), a `SafeDialog` containing a `BasicTextField` driven by
`performTextInput` (46 s), a `SafeDialog` containing `rememberLauncherForActivityResult` (17 s), and
the real `NowChooserDialog` composed and asserted without interaction (16 s). Disabling
`SafeDialog`'s `OnGlobalLayoutListener` outright did **not** stop the hang, so the inset feedback
loop originally suspected is ruled out as well.

What is established is the precise trigger: the hang begins when the dialog's content **changes
size**. A probe that renders the real `NowChooserDialog` and only clicks `chooser_custom` — revealing
the inline `InlineLeaveMinutes` input, with no text entered — hangs reproducibly, while the identical
probe without that click passes in 16 seconds. Every static probe passed; only the one that grows the
dialog's content stalls. The root cause within that growth path is **not yet identified**, so no fix
is claimed.

No fix was attempted beyond diagnosis. The remaining candidate is a measure/idle loop when a
scrolling, constrained dialog's content grows, which sits in `SafeDialog`'s layout contract — a
component carrying documented OEM and Android 15 rationale. Changing it is a product decision rather
than an obvious repair, so it is raised rather than guessed at.

Reproducer for whoever picks this up: render `NowChooserDialog(NowViewModel.Chooser.Leave("task"), …)`
under `createComposeRule()` with `@GraphicsMode(NATIVE)`, click `chooser_custom`, and assert
`chooser_custom_minutes` exists. It never returns.

**Verification actually performed.**

- Non-dialog packages (`domain`, `command`, `agent`, `mappreview`, `map`, `image`, `pdf`, `orb`,
  `pulse`, `project`, `streams`, `ui.components`): **103 classes, 1,115 tests, 0 failures, 0
  errors, 0 skipped**.
- `com.virlin.app.now.*` and `com.virlin.app.screenshot.*`: **hang** — 0 classes completed within a
  15-minute budget (`timeout` exit 124).
- `com.virlin.app.hierarchy.*`: **not run**; it holds the dialog and popup tests and is expected to
  stall for the same reason.
- `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin`: pass (APK 23,417,043 bytes).
- `git diff --check`: clean.

The full suite therefore has **not** been shown green, and no claim is made that it is.

**Not done.** No instrumentation run, no Roborazzi verification (its package is inside the stalling
group), and no push — the destination was ambiguous because the current branch is named for
attachment work, carries the execution-audit checkpoint and has no upstream.

## 2026-11-05 — Android Notes rebuilt ("notes2") and routed (Cursor agent)

> **Superseded the same day:** at the user's request the earlier Compose editor was restored.
> The notes2 UI and web bundles were archived (not compiled or packaged), and every note route
> uses `NotesRoute` again. The data-safety layer described below was kept. See
> `docs/NOTE_EDITOR_RESTORE_2026-11-05.md`.

**Requested by:** User. **Status:** active route switched after its device suite passed. Real
source-app paste is pending the user's manual test. Details, results and limitations are in
`docs/NOTE_FEATURE_ROADMAP.md`. Cleanup candidates are in `docs/NOTE_CLEANUP_INVENTORY.md`
(nothing removed).

- **Added:**
  - `notes-editor/` (Tiptap web editor, `npm test` 28/28), built to `app/src/main/assets/notes-v2/`
  - `ui/notes/rich/RichNotesWebView.kt`, `RichNotesViewModel.kt`, `RichNotesRoute.kt`
  - `domain/notedoc/rich/*` (v2 format, adapter, `resetForDuplicate`)
  - androidTest `notes/RichNotesRouteDeviceTest` (8/8 on `emulator-5556`)
  - JVM `domain/notedoc/rich/*Test`
- **Routed:** `VirlinApp` `NOTES_ROUTE` and `CaptureNotesRoute` (knowledge, workstream and capture
  notes) now call `RichNotesRoute`. The old `NotesRoute` / `NotesReaderRoute` are kept, and only
  `PickerDialog` / `PickerRow` became `internal`.
- **Consumers:**
  - `ProjectDuplicator` (tree reset)
  - `ProjectArchive` (v2 payload plus `damagedRaw`)
  - `ProjectStorageTools` (backup zip includes note images)
  - `MasterDocument` (damaged-note label)
  - `DesktopWorkspaceProtocol` (rejection detail)
- **Build:** debug `versionNameSuffix = "-notes2-20261105"` → `1.0.9-notes2-20261105`. The APK
  SHA-256 is `fb444d0f…f503f6`, installed on the test AVD only.
- **Not touched:**
  - migrations, `virlin_notes`, `virlin_note_chunks`
  - managed images, flashcard files, the old editor, dependencies
  - the user's `emulator-5554`

## 2026-11-05 — Note editor stability, Copy buttons, code colours (Cursor agent)

**Requested by:** User (decisions: no size cap with background parsing; Copy on code, prompt
and equation blocks, bottom Copy only when taller than the screen; display-only highlighting;
fix all audit findings). Details: `docs/NOTE_EDITOR_RESTORE_2026-11-05.md` § Stability pass.

- **Added:** `domain/notedoc/NotePasteSafety.kt`, `ui/notes/CodeHighlighter.kt`; tests
  `NotePasteSafetyTest`, `CodeHighlighterTest`, `NoteEditorStabilityTest`.
- **Changed:** `NotesViewModel` (background large paste, revision-checked saves,
  refused-save/conflict dialog, damaged-note recovery), `NoteBlockEditor` (Copy actions,
  syntax colours, paste pill, recovery panel, read-only while pasting), `NotesRoute` (dialog,
  panel), `NotePasteParser` (mid-paragraph links, bounded link matching).
  `NotePasteParserTest`: two large-paste tests now inject an inline paste dispatcher; their
  assertions are unchanged.
- **Not touched:** schema, codecs, stored notes, dependencies, `emulator-5554`.
- **Follow-ups (same day):** code block text defaults to 14sp (`NotesViewModel.defaultSizeFor`,
  `NoteDocumentLayoutTest` line height). Pasted chat-assistant prompt cards are recognised:
  new `domain/notedoc/ChatPromptCards.kt`, called once at the end of `NotePasteParser.parse`.
  It acts only when a stand-alone "Copy prompt" button label is present; the text blocks
  directly above become one PROMPT block (a short header line becomes its title) and the label
  is dropped. Code, equations, headings, tables and images are never absorbed; pastes without
  the label are returned unchanged. Tests: `ChatPromptCardsTest` (7).
- **Note "+" sheet redesign (same day):** the plain "Add a block" picker is replaced by the
  "Add to note" bottom sheet (new `ui/notes/NoteAddSheet.kt`): Rich blocks (quote, prompt
  callout, code, equation, toggle, divider, table, image) and Text blocks (paragraph, H1–H3,
  lists, checklist) with icon, subtitle and mini preview, plus a "Format & tools" grid that
  mirrors every other toolbar action through the route's existing `handleTool`, honouring the
  toolbar's enabled/active state. Equation is newly reachable here via the existing
  `insertEquation`. `NotesRoute`: the AddBlock branch now hosts the sheet; the tool state is
  shared with the toolbar. Toolbar, editor, actions and persistence unchanged.
  Tests: `NoteAddSheetTest` (3).
- **Build label:** debug `versionNameSuffix` is now `-notes-addsheet-20261105-2225` so this
  build is distinguishable from earlier same-day builds that shared the old suffix.
  APK: `releases/Virlin-Android-1.0.9-notes-addsheet-20261105-2225-debug.apk`.

## 2026-11-05 — Note editor Delete tool (Cursor agent)

**Requested by:** User ("add a delete icon and make it work when select things").

- **Added:** `NoteTool.DELETE` (trash icon after Undo/Redo, also in the "+" sheet's tool grid),
  enabled only while something is selected. `ui/notes/NoteSelectionDelete.kt`
  (`projectedBlocks`, `deleteProjectedRange`). `NotesViewModel.deleteSelection()` removes the
  selected characters of the focused block; `deleteDocumentRange()` handles a "Select across
  blocks" selection: fully selected blocks are removed, partly selected text is trimmed, a
  partly selected table/image/divider is kept whole, and an emptied note keeps one paragraph.
  Each delete is one undo step and saves through the normal path.
- **Changed:** `VirlinNotesScreen` (enum entry, tool spec, label), `NotesRoute` (DELETE in
  document-selection mode, enabled state), `NoteAddSheet` (grid tile).
- **Tests:** `NoteSelectionDeleteTest` (7). Device: trash icon disabled with no selection,
  word deleted after long-press selection, Undo restored it.
- **Build label:** `-notes-delete-20261105-2315`;
  APK `releases/Virlin-Android-1.0.9-notes-delete-20261105-2315-debug.apk`.
- **Not touched:** schema, codecs, stored notes, other tools' behaviour.

## 2026-11-05 — Heading 4/5 and compact paragraph-style popup (Cursor agent)

**Requested by:** User ("add Heading 4 and Heading 5 … keep it really compact … small pop-up").

- **Added:** `NoteDocBlockType.HEADING_4` / `HEADING_5`, appended after the existing entries so
  stored names and ordinals are unchanged, plus `headingLevel` (1–5, 0 otherwise).
  `ui/notes/NoteParagraphStylePopup.kt`: a 196dp popup anchored under the toolbar's
  paragraph chip (window bounds captured in `NoteFormattingRail`, kept on screen, with a
  fallback position). Rows are drawn in their own style: Normal, H1–H5 at decreasing sizes,
  then Quote / Code / Prompt with icons. Tapping outside or pressing Back closes it.
- **Changed:** the rich adapter projects level 4 as H4 and levels 5–6 as H5. A stored level 6
  keeps its level unless the block is restyled. Editor, reader, selection projection, folding,
  default sizes (H4 16, H5 14), the Master Document export (`####` / `#####`) and the "+"
  sheet's Text section now cover H4/H5. `CaptureNoteBridge` preview maps H4/H5 to Heading 3.
  The old "Paragraph style" `PickerDialog` is replaced by the popup.
- **Unchanged on purpose:** `NotePasteParser` still pastes `####` and `<h4>`–`<h6>` as Heading 3.
- **Tests:** `NoteParagraphStylePopupTest` (2), `RichNoteAdapterTest` (+2, level-5 expectation
  updated), `NoteAddSheetTest` (H4/H5 rows). Device (5556): popup under the chip, Heading 4
  applied and saved, Back dismisses.
- **Build label:** `-notes-headings-20261105-2325`;
  APK `releases/Virlin-Android-1.0.9-notes-headings-20261105-2325-debug.apk`.
- **Not touched:** schema version, codecs' stored format, existing notes.

## 2026-11-05 — Note font library (Cursor agent)

**Requested by:** User ("add more fonts … 100+ … formal, hand-drawn, creative, handwritten …
the name should be represented in their actual style").

- **Added:** `androidx.compose.ui:ui-text-google-fonts` (new library, version from the existing
  Compose BOM 2024.06.00; nothing upgraded) and `res/values/font_certs.xml` (the standard Google
  Play services font-provider certificates). Fonts are downloaded and cached by Play services on
  first use. Nothing is bundled: the APK grew about 55 KB.
- **Added:** `ui/notes/NoteFontCatalog.kt`, generated from the live Google Fonts catalog
  (fonts.google.com/metadata/fonts): 1,540 families with a Latin subset and a regular style,
  excluding Noto, colour and icon fonts and families added in the last year. That is 244
  handwriting, 442 display, 296 serif, 513 sans and 45 monospace, most popular first, plus 184
  curated "Featured" picks (handwritten, script & calligraphy, creative, formal serif, clean sans,
  mono) drawn from 2026 font round-ups.
  `ui/notes/NoteFonts.kt`: categories, search, and a cached `familyFor(name)`. The stored names
  `Inter` / `Serif` / `Mono` render exactly as before; unknown names fall back to the default.
  `ui/notes/NoteFontPickerSheet.kt`: a bottom sheet with search, category tabs with counts, and
  rows showing each name in its own font, the current one ticked.
- **Changed:** the editor (`NoteRun.toSpanStyle`) and reader use `NoteFonts.familyFor`. The
  toolbar font chip truncates long names. The "Font" `PickerDialog` is replaced by the sheet.
  While a picker is open, the editor's selection handle is transparent so it no longer draws over
  the picker; focus and selection are untouched.
- **Storage:** unchanged. The family name stays in the run's existing `fontFamily` field (rich
  `textStyle.fontFamily`, legacy `ff`).
- **Tests:** `NoteFontsTest` (5), `NoteFontPickerSheetTest` (3).
  Device (5556): Play services FontLog showed downloads. Caveat applied to a line and was still
  there after reopening. With one word selected, searching "pacifico" and picking it restyled only
  that word. The handle no longer overlaps the sheet.
- **Build label:** `-notes-fonts-20261105-2346`;
  APK `releases/Virlin-Android-1.0.9-notes-fonts-20261105-2346-debug.apk`.
- **Not touched:** schema, codecs, desktop protocol, cross-block selection projection (it maps
  monospace back to the Code mark, so it keeps ignoring families).

## 2026-11-05 — Inline text-size stepper (Cursor agent)

**Requested by:** User ("I don't want to see any pop-ups like this … small up and down arrows …
scroll … click … type the specific size").

- **Added:** `ui/notes/NoteSizeStepper.kt`, a 48×44dp control with ▲, the number, and ▼.
  - Tapping an arrow steps the size by 1; at 8 or 96 that arrow is disabled.
  - Dragging up or down steps every 14dp (a mouse or trackpad wheel also works), with a light haptic tick.
  - Tapping the number opens a 2-digit numeric field; Done or leaving the field applies the size, clamped to 8–96 (the desktop protocol's range).
  - TalkBack gets increase/decrease custom actions.
- **Added in `NotesViewModel`:**
  - `stepFontSize`, and `applyFontSize(sp, target)`, which restores the selection captured by `styleTarget()` before typing began.
  - A run of size changes on one block coalesces into one undo step: `applyAttribute` gained an optional `coalesceWith`.
- **Changed:**
  - The toolbar SIZE chip is now the stepper (`NoteSizeControl` passed through `VirlinNotesScreen` / `NoteFormattingRail`).
  - The "+" sheet's Text size tile embeds the same stepper and the sheet stays open.
  - The old "Text size" `PickerDialog` is only reached if no size control is supplied.
- **Tests:** `NoteSizeStepperTest` (7: arrows, limits, drag, typed + clamp, disabled, view-model
  steps + undo, typed size keeps the earlier selection), `NoteAddSheetTest` (+1). Device (5556):
  arrows 16 → 17, drag up → 21, typed 28 → 28, saved.
- **Build label:** `-notes-size-20261105-2353`;
  APK `releases/Virlin-Android-1.0.9-notes-size-20261105-2353-debug.apk`.
- **Not touched:** storage (`fontSizeSp` as before), other pickers.

## 2026-11-06 - Inline table size entry + growing tables (Cursor agent)

**Requested by:** User ("in the toolbar itself there should be two options - rows and columns -
Done - extend that table column-wise and row-wise - I don't want to see the popup").

- **Removed:** the "Insert table" `PickerDialog` with fixed sizes (2x2, 3x2, 3x3, 4x3). `NoteDialog.Table` now renders nothing.
- **Added:** `ui/notes/NoteTableEntryBar.kt` (`NoteTableEntry`, `NoteTableEntryBar`).
  - While the table tool is open, the formatting rail shows `[table] [ROWS] x [COLUMNS] [Done] [close]` instead of its tools.
  - The fields take numbers only, are clamped as typed to 1-50 rows and 1-12 columns, and default to 3 x 3. Rows is focused first; Next moves to Columns, and Done inserts.
  - Back or the close button cancels.
  - The "+" sheet's Table row opens the same bar.
- **Added (domain):** `NoteDocTable.withRowAt` / `withColumnAt` and `MAX_ROWS` / `MAX_COLS`.
- **Added (`NotesViewModel`):** `insertTable(rows, cols)`, `addTableRow(blockId, atTop)` and `addTableColumn(blockId)`. Each structural change is one undo step and stops at the size limit.
- **Changed (editor):** `NoteTableBlock` has "+" edges above, below and to the right of the grid.
  - They are active while a cell is focused. Otherwise they are faint, and the first tap only reveals them for 5s, so a stray tap never reshapes a table.
  - After adding, the new row or column cell takes focus.
- **Changed (rich storage):** `RichNoteAdapter.patch` now grows a stored table by whole rows (top or bottom) and columns (left or right).
  - Existing row and cell nodes, including their marks, are kept untouched.
  - New cells copy the neighbouring cell type (header or cell) and get unique paragraph ids.
  - Shrinking, ragged rows and merged cells (colspan/rowspan) are still refused, so the save is rejected rather than losing structure.
- **Tests:**
  - `RichNoteAdapterTest`: +3 tests (bottom/right growth keeps a mark and unique ids, top insert, merged cells refused). The old resize-refused case is now a shrink.
  - `RichNoteActionsTest`: its refused-structure example is now a shrink.
  - `NoteTableEntryTest` (4: insert on Done or IME, clamp + disabled + cancel, view-model grow + undo, size limit).
  - Notes + notedoc suites: 106 run, 0 failures. The full unit suite was not completed (that run was stopped).
- **Fix after device check:** the right-edge "Add column" bar had zero width (its box was only as wide as the grid); the box now spans the grid plus the edge.
- **Build label:** `-notes-tables-20261106-0120`;
  APK `releases/Virlin-Android-1.0.9-notes-tables-20261106-0120-debug.apk` (the earlier 0111 build had the zero-width column bar and was removed).
  Verified on 5556: inline bar opens with no popup, 4 x 3 inserted, cell typing, add row below, add column (focus moves to the new column), all saved.
- **Not touched:** desktop protocol, legacy codec, other pickers.

## 2026-11-06 - Notes: colour strips, grouped toolbar, toggle headings, + quick-tools fan

- **Colour strips (new `ui/notes/NoteColorStrip.kt`):** Text colour and Highlight no longer open popups.
  - A 52dp strip opens below the toolbar: Default / None plus ~60 text colours and ~50 highlight colours (many yellows), round swatches, feathered edges, horizontal scroll.
  - "#" entry accepts `#RRGGBB`, `RRGGBB`, `#RGB`, with a preview circle and Done; invalid codes are refused. Dark pasted highlights are softened (still opaque, so storage is unchanged).
  - Custom colours are remembered (SharedPreferences `note_colors`, max 8) and shown at the front.
  - Closes with the tool again, x or Back. Swatch taps keep the editor selection; the hex field captures a `StyleTarget` first.
  - `NotesViewModel.pickColor(argb, highlight, target)` is one undo step per block; old `setTextColor` / `setHighlight` kept.
- **Grouped toolbar (`VirlinNotesScreen`):** `ToolSpec.groupStart`; order Edit | Text style | Format | Colour | Layout | Lists | Insert | Document with 1dp dividers. UI only.
- **Toggle headings:**
  - Domain: `NoteDocBlock.toggleHeading`; `visibleNoteBlocks` hides a collapsed toggle heading's section (until the next heading of the same or higher level) even outside heading-fold mode.
  - Storage: legacy codec `"toggleHeading":true`; rich v2 stores `"toggle":true` on the heading node (older readers and the desktop still see a normal heading); desktop export/import carries the flag.
  - Rich patch: paragraph/heading <-> Toggle block wraps/unwraps (`convertToggle`); a Toggle that still holds blocks cannot be unwrapped and the save is refused, never flattened.
  - `NotesViewModel.setParagraphStyle(type, toggle)`; Enter on a collapsed toggle heading opens it; label "Toggle heading N"; changing type clears the flag.
  - UI: paragraph popup has a ">" button per Normal / Heading 1-5 row (Normal > = Toggle list); toggle headings show a collapse arrow in the editor.
  - The read-only preview shows everything expanded, as it already did for Toggle blocks.
- **+ quick-tools fan (new `ui/notes/NoteAddFan.kt`):** first tap fans Flashcard (left), Select across blocks (up-left), Heading fold (up) on a quarter circle with a spring (snaps when animations are off); + rotates to x. Second tap closes the fan and opens the unchanged Add sheet. Outside tap / Back close the fan. Buttons call the same tool handler as the toolbar and respect enabled state.
- **Tests:** `NoteColorPalettesTest` (3), `RichNoteAdapterTest` +2 (toggle attr round trip, wrap/unwrap/refuse), `VirlinNoteDocTest` +1, `NotesEditorTest` +1. Notes + notedoc + editor + desktop suites: 203 run, 0 failures. `ProjectKnowledgeAddUiTest` passes.
- **Build label:** `-notes-all-20261106-0703`; APK `releases/Virlin-Android-1.0.9-notes-all-20261106-0703-debug.apk`.
  Verified on 5556 only: popup > buttons, toggle heading 2 create / Enter / collapse / reopen / back to plain heading, Toggle list, toolbar dividers, red text on a selection, `FEB` highlight via "#", fan open / outside tap / Back / second tap -> Add sheet, fan Heading fold / Select across blocks / Flashcard.

## 2026-11-06 - Notes: bold toggle arrows

- **New `ui/notes/NoteToggleArrow.kt`:** a drawn solid triangle (rounded corners) replacing the small Unicode arrow glyphs. Right when collapsed, down when open, 160ms rotation (snaps when animations are off).
  - Sized from the line's text size (`NotesViewModel.defaultSizeFor`) and centred on the first line (`NoteDocumentLayout.lineHeightSp`), in the text colour.
- **Used in:** toggle headings and Toggle blocks in the editor (rows now top-aligned so wrapped headings keep the arrow on line one), the Toggle block in the read-only preview, the paragraph popup's toggle buttons and the toolbar Toggle chip.
- Same click handlers, content descriptions and test tags as before; no storage or behaviour change.
- **Tests:** notes UI + editor suites 123 run, 0 failures.
- **Build label:** `-notes-arrow-20261106-0733`; APK `releases/Virlin-Android-1.0.9-notes-arrow-20261106-0733-debug.apk`. Verified on 5556: wrapped toggle heading open/collapsed, Toggle block open/collapsed, popup buttons.

## 2026-11-06 - Notes: Toggle tool wraps the selection

- **Behaviour:** with text selected, the toolbar Toggle tool now puts the selected block(s) inside a new Toggle placed before them (empty title, caret moves to it). With only a caret it still adds a new toggle as before.
  - Single block: selection inside the focused block wraps that whole block.
  - Select across blocks: every block the selection touches is wrapped; select mode then closes.
- **New `ui/notes/NoteToggleWrap.kt`:** `wrapBlocksInToggle(blocks, range, id)` (toggle takes the shallowest indent, wrapped blocks +1, deeper blocks right after the range - e.g. a toggle's hidden children - move with their parent; text/marks/ids untouched; refused at indent 5) and `projectedBlockRange` (cross-block selection -> block indices).
- **`NotesViewModel`:** `wrapInToggle(range)`, `wrapDocumentRangeInToggle(start, end)`; one undo step.
  - Safety: for rich (v2) notes the next save's merge is run first (`RichNoteAdapter.applyBlockEdits` on the stored tree, kept as `baseRich`) and must read back with the same ids/types/indents; otherwise nothing changes and a message explains it (e.g. list items, which the rich format cannot re-indent). Block-only notes always accept.
- **Route:** Toggle in select-across mode uses the cross-block selection.
- **Tests:** `NoteToggleWrapTest` (6: wrap, nesting + hidden children, depth limit, range mapping, rich round trip, rich list refused) and `NotesEditorTest` +1 (wrap, focus, undo, caret still inserts). Notes + notedoc + editor + desktop suites: 210 run, 0 failures.
- **Build label:** `-notes-wrap-20261106-0742`; APK `releases/Virlin-Android-1.0.9-notes-wrap-20261106-0742-debug.apk`. Verified on 5556: single-line wrap + title + collapse + reopen; two-line wrap via Select across blocks.

## 2026-11-06 - Notes: line-spacing chip + dropdown

- **Toolbar:** the bare "↕" tool is now a labelled chip (line-spacing icon + current value + "▾", e.g. "1.5 ▾"), like the paragraph chip. `NoteToolState.spacingLabel` (default "Single") comes from the focused block's `lineSpacing`.
- **New `ui/notes/NoteLineSpacingPopup.kt`:** anchored dropdown under the chip (same position/animation as the paragraph popup) replacing the centred PickerDialog. Rows Single / 1.25 / 1.5 / Double, each with a three-line preview drawn at that spacing, selected row highlighted with a check. Tags `notes_spacing_<label>`.
- Anchor: `onSpacingChipPlaced` passed through `VirlinNotesScreen` / `NoteFormattingRail`; the chip shows as active while the menu is open. `setLineSpacing` unchanged.
- **Tests:** `NotesEditorTest` asserts the chip label (Single / 1.5 / Double). Notes UI + editor suites: 130 run, 0 failures.
- **Build label:** `-notes-spacing-20261106-0752`; APK `releases/Virlin-Android-1.0.9-notes-spacing-20261106-0752-debug.apk`. Verified on 5556: chip, dropdown, pick 1.5, chip updates, saved.

## 2026-11-06 - Notes: bullet and numbering style strip

- **Toolbar:** tapping Bullets or Numbering makes the line that kind of list (if it is not yet) and opens a style strip under the toolbar (like the colour strip). Tapping the same tool again, the strip's X, or Back closes it and keeps the list. The strip's "None" turns the line back into Normal text.
  - Bullet styles: dot, circle, square, open square, diamond, dash, arrow, chevron, triangle, star, tick.
  - Numbering styles: 1. / 1) / 01. / a. / a) / A. / i. / I.
  - Each tile previews three lines with their markers; the current style is highlighted. Taps do not take focus.
- **Model:** new optional `NoteDocBlock.listStyle` (null = default dot / 1. 2. 3., so existing notes are unchanged). New `domain/notedoc/NoteListStyles.kt`: catalogue, `marker()` (letters, Roman numerals), `storedKey()`, `listGroup()`.
- **Storage:** legacy codec writes `"listStyle"` only when set. Rich (v2) notes keep it as a `listStyle` attr on the bulletList/orderedList node; lists split when the style differs; restyling patches only the list node; a stale style on a non-list line is ignored (never refuses a save). Desktop protocol exports/imports `listStyle` (unknown keys dropped). No schema change.
- **`NotesViewModel`:** `setListStyle(type, key)` restyles the whole list the line belongs to (same type + indent, nested lines skipped) as one undo step, pre-checked against the rich document like toggle-wrap; `removeListFromFocused()`. Enter continues the style; a line joining a neighbouring list takes its style; changing to another block type clears it.
- **Rendering:** editor list rows and reader preview use the style's marker. Select-across-blocks text projection still uses the plain "1." / dot prefixes (unchanged on purpose).
- **Behaviour change:** a second tap on Bullets/Numbering now closes the strip instead of removing the list (use "None"). `NotesEditorTest` updated for this.
- **Tests:** new `NoteListStylesTest` (6: markers, defaults, group, codec, rich split, rich patch) and `NotesEditorTest` +2. Notes + notedoc + editor + desktop suites: 218 run, 0 failures.
- **Build label:** `-notes-bullets-20261106-0805`; APK `releases/Virlin-Android-1.0.9-notes-bullets-20261106-0805-debug.apk`. Verified on 5556: numbering strip, "a. b. c." applied, bullets strip, Arrow applied, Back closes strip.

## 2026-11-06 - Notes: change-case tool (Aa)

- **Toolbar:** new "Aa" chip after Strike (also in the + sheet tool grid). Each tap moves the selected text to the next case: ALL CAPS -> Title Case (first letter of each word capital, rest small) -> lowercase -> ALL CAPS. A step that would change nothing is skipped. With only a caret, the word under the caret changes. Works on a Select-across-blocks selection too (the selection stays, so taps keep cycling).
- **Safety:** new `domain/notedoc/NoteTextCase.kt` (`NoteTextCases.nextCase / convert / wordAt`). Only letters change and the text length never changes (a character whose case form would be longer, e.g. German sharp s, is kept), so bold/colour/link runs, the caret and the selection stay exactly where they were. Inline maths `\(...\)` keeps its case; code/maths blocks are skipped (tool disabled there). Locale-independent. One undo step per tap. Rich (v2) notes: text-only edit, same path as typing. No schema change.
- **`NotesViewModel`:** `cycleTextCase()`, `cycleDocumentRangeCase(start, end)`; `NoteTool.CHANGE_CASE` (appended to the enum) enabled for text blocks that contain letters.
- **Tests:** new `NoteTextCaseTest` (5) and `NotesEditorTest` +1 (cycle, marks kept, selection kept, undo, caret word). Notes + notedoc + editor + desktop suites: 224 run, 0 failures.
- **Build label:** `-notes-case-20261106-0821`; APK `releases/Virlin-Android-1.0.9-notes-case-20261106-0821-debug.apk`. Verified on 5556: caret word Group -> group -> GROUP -> Group, Opening cycled, saved.

## 2026-11-06 - Notes: keep the reading position (no jumps to top/bottom)

- **Root causes (reproduced on 5556):**
  1. After the app was reopened (or after process death / a cross-block delete) no line is focused. Toolbar tools that work without focus (Toggle, Table, Divider, Image, + sheet blocks) then appended the new block at the END of the note, and the focus-scroll jumped to the bottom.
  2. Turning Select-across-blocks on/off swaps the editor list for one long text item, so the scroll position was lost (landed at the end / somewhere else).
  3. Nothing remembered where you were when the note was closed and opened again.
- **Fixes:**
  - `NotesViewModel`: `setViewAnchor(blockId)` (the line at the top of the screen, reported by the editor when scrolling stops) and `insertionPoint()`. With a focused line, inserts go after it as before; with nothing focused, the new block goes right after the top visible line (inside an open toggle it is indented as a child); only with no known position does it fall back to the end. Used by `insertBlock` and `insertImage`.
  - `NoteBlockEditor`: the focus-scroll effect runs only when the focused line changes (not on every list change) and never in Select-across mode. Select-across on: scrolls the projection to the same top line; off: scrolls back to the top line seen in the projection.
  - New `ui/notes/NotePositionStore.kt` (SharedPreferences "note_positions", value "blockId|offsetPx" per note owner key, written only when it changes). Opening a note restores the saved top line; missing/deleted lines fall back to the top. `NotesRoute` passes `positionKey = ownerKey`. Not in Room, no schema change.
- **Tests:** `NotePasteUiTest` +3 (reopen keeps the top line, select-across on/off keeps it, Toggle with nothing focused goes under the top line and the view stays). Notes + notedoc + editor + desktop suites: 227 run, 0 failures.
- **Build label:** `-notes-position-20261106-0843`; APK `releases/Virlin-Android-1.0.9-notes-position-20261106-0843-debug.apk`. Verified on 5556: close/reopen returns to Line9; Home + kill + reopen keeps Line9 and Toggle is inserted under Line9 without scrolling; Select-across on/off stays at Line9.

## 2026-11-06 - Notes: pasted diagrams / pictures are no longer lost silently

- **Finding:** a text paste carries only what the source app puts on the clipboard. Our parser never drops a diagram that arrives as text or code (it becomes a code block at worst). A diagram missing between two headings means the source put no picture/diagram data on the clipboard (copy of on-screen text). `Note options > Paste details` now shows this per paste.
- **Real gaps fixed in our pipeline (when the data IS there):**
  - `NotePasteParser`: an `<img>` inside inline markup (`<p><span><img>`) was skipped; it now follows its paragraph as an image block. An inline `<svg>` drawing keeps its words one per line (icons without words add nothing; previously all labels were glued into one paragraph). `<canvas>` is ignored like script.
  - A picture that cannot be stored keeps its description, linked to its web address when it has one.
  - Markdown image lines `![alt](src)` become image blocks (or the linked description).
  - When the HTML copy is used but leaves out a code / prompt / table block that the plain Markdown has (a diagram's source rendered as a picture or widget), that block is put back after the last matching HTML block. Blocks the HTML already has are not duplicated (content-key match).
  - `NoteBlockEditor` paste resolver: embedded `data:image/...;base64` pictures are stored via the existing `NoteImageStore.importDataUri` (it was never called, so they became "Image unavailable").
- **Paste a copied picture:** new `NoteClipboardImages`. When the clipboard holds a picture, the Image tool (rail and + sheet) asks "Paste copied picture" / "Choose from device"; otherwise it opens the picker exactly as before. Checking uses only the clip description.
- **Paste details:** new `domain/notedoc/NotePasteReport` (counts and MIME types only, never content); `NotesViewModel.lastPasteReport`, `pasteStructured(..., clipSummary)`; `NoteDialog.PasteDetails` / `NoteDialog.ImageSource`; `VirlinNotesScreen` `onPasteDetails` menu item. Not persisted.
- No schema change, no new dependency, no rewrite of existing notes.
- **Tests:** `NotePasteParserTest` +8 (inline picture, linked description, svg words, Markdown image, restored missing code, no duplicates, report wording, view model records report). Notes + notedoc + editor + paste + desktop suites: 265 run, 0 failures.
- **Build label:** `-notes-pastediagram-20261106-0904`; APK `releases/Virlin-Android-1.0.9-notes-pastediagram-20261106-0904-debug.apk`. Verified on 5556: menu item, empty report, real copy+paste report ("text/plain ... No picture or diagram was on the clipboard"), Image tool still opens the picker when the clipboard has text. Pasting a clipboard picture was not exercised on the emulator (no way to place an image clip via adb).

## 2026-11-06 - Notes: word count popup

- **Toolbar:** new "#" chip (Word count) at the start of the document group (before Select across); also in the + sheet tool grid. Always enabled, including in Select-across mode. Tapping opens a card: Words and Characters as two large tiles, "Characters without spaces" below, and a Done button. Screen readers hear "43 words", "272 characters", etc.
- **Counting:** new `domain/notedoc/NoteWordCount.kt` (pure). Whole note: every block's text (collapsed toggles and folded sections included), table cells and prompt titles; images and dividers add nothing. Characters are code points without line breaks (an emoji is one). A word is a whitespace-separated run with a letter or digit (a dash alone is not a word). Computed only when the card opens (and if the note changes underneath it), never per keystroke.
- **Wiring:** `NoteTool.WORD_COUNT` (appended), `NoteDialog.WordCount`, `NotesViewModel.wordCount()`, `NoteWordCountPopup.kt`. Read-only: no edit, no save, no schema change.
- **Tests:** new `NoteWordCountTest` (4) and `NotesEditorTest` +1. Notes suites: 270 run, 0 failures.
- **Build label:** `-notes-wordcount-20261106-0933`; APK `releases/Virlin-Android-1.0.9-notes-wordcount-20261106-0933-debug.apk`. Verified on 5556: chip in rail, card shows 43 words / 272 characters / 262 without spaces, Done closes.

## 2026-11-06 - Notes: combined test build

- **Build label:** `-notes-latest-20261106-0938`; APK `releases/Virlin-Android-1.0.9-notes-latest-20261106-0938-debug.apk`. Same source as the word-count build (no code change); contains all notes work of this session. Installed and launched on 5556.

## 2026-11-06 - Notes: + button opens / closes the fan; only a quick triple tap opens the Add sheet

- **Behaviour:** tap 1 opens the quick-tools fan (Flashcard, Select across, Heading folding); tap 2 closes it; tap 3 opens the Add sheet only when the three taps are one continuous run (each within 600 ms of the previous, `ADD_FAN_TAP_RUN_MS`). After a pause the next tap starts a new run and just opens the fan again. Tapping outside the fan, Back, or using a fan button also ends the run. Previously the 2nd tap closed the fan AND opened the sheet.
- **Accessibility:** button reads "Show quick tools" / "Hide quick tools" and offers an "Add block" accessibility action (screen-reader users do not need the triple tap).
- **Code:** `NoteAddFan.kt` only (tap-run counter, optional injectable `now` clock for tests). Fan look, positions and animation unchanged.
- **Tests:** new `NoteAddFanTest` (3: quick run opens sheet, pause restarts, accessibility action). Notes UI suites: 140 run, 0 failures.
- **Build label:** `-notes-plustaps-20261106-1020`; APK `releases/Virlin-Android-1.0.9-notes-plustaps-20261106-1020-debug.apk`. Verified on 5556: slow taps 2 s apart = open, close, open (no sheet); three fast taps (~250 ms apart) = Add sheet.

## 2026-11-06 - Notes: smaller Toggle chip in the toolbar

- **Look only:** the toolbar's Toggle chip drew the disclosure arrow at heading size (22sp) with 12dp side padding, so it read as a big play button. It now uses 16sp with 5dp padding, making the chip the same size as the neighbouring icon chips (Image, Code). `VirlinNotesScreen.kt` rail only; the arrow in the editor, tool behaviour, label and enabled state are unchanged.
- **Tests:** notes UI suites 134 run, 0 failures. No golden covers the notes rail.
- **Build label:** `-notes-toggleicon-20261106-1025`; APK `releases/Virlin-Android-1.0.9-notes-toggleicon-20261106-1025-debug.apk`. Verified on 5556: Toggle chip visually matches the Image chip.

## 2026-11-06 - Notes: paragraph styles as a scrolling strip

- **Behaviour:** the "Normal text" toolbar chip now opens a horizontal strip under the toolbar (same look as the colour and list strips) instead of the dropdown popup. Order: Normal text, Heading 1-5 (each drawn in its own size) | divider | Toggle list, toggle H1-H5 | divider | Quote, Code, Prompt. Picking applies to the focused block and the strip stays open so styles can be compared; the current style is outlined in green and scrolled into view. Close with the X, Back, or by tapping the chip again.
- **Code:** new `NoteParagraphStyleStrip.kt`; `NotesRoute.kt` builds the strip state, marks the chip active, keeps selection colours visible, and the popup case in `NoteDialogHost` is now a no-op; `VirlinNotesScreen.kt` renders the strip next to the colour/list strips; `NotesViewModel.kt` adds optional `keepOpen` to `setBlockType` / `setParagraphStyle` (default unchanged) and makes the PARAGRAPH chip toggle the strip. Style logic (toggle headings, block conversions, undo) is the same code the popup used. `NoteParagraphStylePopup.kt` is kept (its `NoteChipAnchor` is still used; popup composable now unused).
- **Tests:** new `NoteParagraphStyleStripTest` (3: all 15 options call the right action, current toggle heading marked, close) and `NotesEditorTest` +1 (chip toggles, picks keep it open, undo). Notes suites: 277 run, 0 failures.
- **Build label:** `-notes-paragraphstrip-20261106-1031`; APK `releases/Virlin-Android-1.0.9-notes-paragraphstrip-20261106-1031-debug.apk`. Verified on 5556: strip scrolls through all groups with visible dividers; Heading 2 then Heading 4 applied to the same line with the strip open; X and chip both close; undo restored the line.

## 2026-11-06 - Notes: line spacing as a strip

- **Behaviour:** the line-spacing toolbar chip now opens a horizontal strip under the toolbar (same look as the paragraph-style strip) instead of the dropdown popup: Single, 1.25, 1.5, Double, each tile with its three-line spacing preview. Picking applies to the focused line and the strip stays open; the current spacing is outlined in green. Close with the X, Back, or by tapping the chip again.
- **Code:** new `NoteLineSpacingStrip.kt`; `NotesRoute.kt` builds the strip state, keeps selection colours visible, and the popup case in `NoteDialogHost` is now a no-op; `VirlinNotesScreen.kt` renders it with the other strips; `NotesViewModel.kt` adds optional `keepOpen` to `setLineSpacing` (default unchanged) and makes the LINE_SPACING chip toggle the strip. Spacing values and storage unchanged (`NotesViewModel.SPACINGS`). `NoteLineSpacingPopup.kt` kept but now unused.
- **Tests:** new `NoteLineSpacingStripTest` (3) and `NotesEditorTest` +1. Notes suites: 281 run, 0 failures.
- **Build label:** `-notes-spacingstrip-20261106-1040`; APK `releases/Virlin-Android-1.0.9-notes-spacingstrip-20261106-1040-debug.apk`. Verified on 5556: strip opens under the rail, 1.5 then Double applied to the same line with the strip open, chip and X both close, undo restored Single.

## 2026-11-06 - Notes: line spacing as a strip

- **Behaviour:** the line-spacing toolbar chip now opens a horizontal strip under the toolbar (same look as the paragraph-style strip) instead of the dropdown popup: Single, 1.25, 1.5, Double, each tile with its three-line spacing preview. Picking applies to the focused line and the strip stays open; the current spacing is outlined in green. Close with the X, Back, or by tapping the chip again.
- **Code:** new `NoteLineSpacingStrip.kt`; `NotesRoute.kt` builds the strip state, keeps selection colours visible, and the popup case in `NoteDialogHost` is now a no-op; `VirlinNotesScreen.kt` renders it with the other strips; `NotesViewModel.kt` adds optional `keepOpen` to `setLineSpacing` (default unchanged) and makes the LINE_SPACING chip toggle the strip. Spacing values and storage unchanged (`NotesViewModel.SPACINGS`). `NoteLineSpacingPopup.kt` kept but now unused.
- **Tests:** new `NoteLineSpacingStripTest` (3) and `NotesEditorTest` +1. Notes suites: 281 run, 0 failures.
- **Build label:** `-notes-spacingstrip-20261106-1040`; APK `releases/Virlin-Android-1.0.9-notes-spacingstrip-20261106-1040-debug.apk`. Verified on 5556: strip opens under the rail, 1.5 then Double applied to the same line with the strip open, chip and X both close, undo restored Single.

## 2026-11-06 - Notes: selection toolbar stall fix (toolbarfix build)
- Root cause: every note line is its own BasicTextField; Compose 1.6.8 shares one floating Copy/Paste toolbar per window and every unfocused field hides it on recomposition, so the focused field reopened it as a new window 4-7 times per formatting tap (main thread blocked on window/renderer creation; one capture reached a 5 s ANR).
- New app/src/main/java/com/virlin/app/ui/notes/NoteTextToolbar.kt: CoalescingTextToolbar defers hide() to the next main-looper message and cancels it on showMenu (last request still wins); flush() on dispose.
- NotesRoute.kt: provides LocalTextToolbar = rememberCoalescingTextToolbar() alongside LocalNotesPalette. Dialogs/popups re-provide their own toolbar, so they are unaffected.
- New test CoalescingTextToolbarTest (3 cases). Notes suite: 284 tests, 0 failures.
- Measured on emulator-5556 (20-line note, Bold with selection): toolbar recreations 4-7 -> 0; remaining window churn is Compose's selection-handle popup and the framework's own toolbar reposition after the text width changes.
- APK: releases\Virlin-Android-1.0.9-notes-toolbarfix-20261106-1226-debug.apk

## 2026-11-07 - Rich notes: line-style changes no longer refused ("Note not saved")
- Root cause: on notes stored as a rich tree (v2), RichNoteAdapter.applyBlockEdits could not express a same-id type change into/out of a container (paragraph -> bullet etc.), returned null, and every later autosave/preview/close save was refused with RICH_STRUCTURE.
- RichNoteAdapter: convertStep supports paragraph/heading/toggle <-> bullet/numbered/checklist (joins adjacent lists, splits lists), list kind switches, <-> quote, <-> code/prompt (plain text only); new lines typed inside a list are inserted as list items. Nested items, nested containers, formatted text -> code and id-less nodes are still refused (no data loss).
- NotesViewModel: styleChangeRefused pre-check; an unsupported style change now shows "Nothing was changed" and is not applied, so the editor never enters an unsavable state.
- NoteDocActions.RICH_STRUCTURE copy clarified.
- Tests: RichNoteTypeChangeTest (8), RichNoteActionsTest +1. Verified on emulator-5556 with an injected rich test note: bullet x2, Enter new item, preview, close - all saved, tree still v2 with table/bold/toggle.
- APK: releases\Virlin-Android-1.0.9-notes-richlists-20261107-0622-debug.apk

## 2026-11-07 - + fan stays open while heading fold steps through its levels
- NoteAddFan: new keepOpenAfter(tool) parameter; fan buttons close the fan unless it returns true.
- VirlinNotesScreen: keepOpenAfter = HEADING_FOLD while headingFoldStep < headingLevels.size, so show controls -> fold deepest..H1 keep the fan out; the final "Expand all headings" tap ends the cycle and closes it. Flashcard / Select still close at once; outside tap and Back still close.
- Test: NoteAddFanTest.headingFoldKeepsTheFanOpenUntilTheCycleExpandsEverythingAgain. Verified on emulator-5556.
- APK: releases\Virlin-Android-1.0.9-notes-foldfan-20261107-0636-debug.apk

## 2026-11-07 07:10 - Notes tables: cell formatting + table strips (APK releases\Virlin-Android-1.0.9-notes-tables-20261107-0703-debug.apk)
- Bug: table cells never became the style target, so Bold/colour/size hit the previously focused line. Cells now report focus/edits to NotesViewModel (focusedCell / activeCell); every inline style tool targets the focused cell (styleBlock / mutateStyleBlock); toolbar shows "Table cell".
- NoteDocTable gains optional cellRuns, cellFills, colWidths, headerRow, headerColumn, gridLines (codec keys cellRuns/fills/widths/headerRow/headerColumn/gridLines; absent = old behaviour).
- Rich notes: tableHeader cells, cell background/colwidth attrs, table gridLines attr. Simple rich tables are rebuilt from the model (untouched cells keep their nodes/marks); tables with merged or multi-paragraph cells keep the old edge-growth-only path and other layout changes are refused with a message (nothing changed).
- Table tool: opens a 60dp create strip (rows x cols + Create) under the rail; creating focuses cell 1,1 and opens the new NoteTableToolsStrip (row above/below, col left/right, delete row/col/table, header row/col, grid lines, column width -/+, even cols, cell shading). Focusing a cell opens it; focusing a line closes it.
- Preview renders headers, shading, grid lines and cell styles.
- Files: VirlinNoteDoc.kt, VirlinNoteCodec.kt, RichNoteAdapter.kt, NotesViewModel.kt, NoteTableStrip.kt (new), NoteTableEntryBar.kt, VirlinNotesScreen.kt, NotesRoute.kt, NoteBlockEditor.kt; tests NoteTableEntryTest, RichNoteAdapterTest, RichNoteActionsTest, VirlinNoteDocTest.
- Verified: notes/notedoc/desktop unit tests 300/300; emulator-5556 rich note (bold in a cell, row below, header col, lines off, shade, width) saved into the rich tree.

## 2026-11-07 07:35 - Notes: image editing (strip + full-screen editor)
- Tap an image in a note to select it; the image tool then toggles an image strip (Crop / Adjust / Markup / Rotate / Replace / Delete). With no image selected the image tool still opens the picker.
- New ui/notes/NoteImageEdit.kt (pure edit model, crop geometry, colour matrix, Whiten paper estimate), NoteImageRender.kt (bitmap render), NoteImageEditor.kt (strip + full-screen editor: crop ratios, filters Original/Whiten/B&W scan/Grey, brightness/contrast/saturation, pen + highlighter with 8 colours and 4 sizes, undo).
- Save renders a NEW private file (NoteImageStore.saveEdited); the block points at it; old file kept so note undo restores it. Only app-managed images are editable; linked images are refused with a message.
- RichNoteAdapter: an image node may now swap only its stored file name (managed UUID); any other image change is still refused.
- NoteTableStrip: shared NoteToolStripShell / ToolTile / GroupDivider (table strip unchanged visually).
- Whiten estimate uses a trimmed mean of each block's bright samples (fixes colour halos beside text).
- Tests: NoteImageEditTest, NoteImageRenderTest, NoteImageToolsTest, RichNoteAdapterTest image swap. 317 notes tests pass.
- APK: releases\Virlin-Android-1.0.9-notes-imageedit-20261107-0732-debug.apk (verified on emulator-5556).

## 2026-11-07 - Notes: YouTube clip embed (toolbar tool)
- New non-textual block NoteDocBlockType.VIDEO (appended to enum; ordinals unchanged) carrying NoteDocVideo(videoId, startSeconds, endSeconds, title, channel). Stored in documentJson, so no Room migration. Codec key "video"; a damaged clip decodes to video=null and the block is kept.
- Rich tree node "youtube" (attrs id/videoId/start/end/title/channel/indent), projected as Target.Fixed; patch only accepts a valid clip change with same type/indent, anything else is refused.
- domain/notedoc/NoteYouTube.kt: link parsing (youtu.be, watch, shorts, live, embed, nocookie, ?t=/start=), time parse/format, plain-text projection.
- Toolbar tool YOUTUBE after IMAGE opens a strip panel (ui/notes/NoteVideoEntry.kt): paste/type link, oEmbed validation (title/channel, private/embedding-off/not-found/offline), start/end fields, Embed/Update. Start follows the link's ?t= until the user types their own.
- ui/notes/NoteVideoBlock.kt: card (thumbnail, range chip, clip length, menu Edit clip times / Open in YouTube / Remove) and in-place WebView player on the official embed URL; injected clip script keeps playback inside start..end (resets to start and pauses at end, snaps seeks back into range). One player at a time (NotesUiState.playingVideoId, not stored).
- VM: insertVideo (after caret block + new paragraph), updateVideo, deleteVideo, playVideo; all undoable.
- Tests: NoteYouTubeTest, NoteVideoEmbedTest; NoteAddSheetTest tool set updated. 331 notes tests pass.
- Verified on emulator-5556: lookup, embed, DB node, clip 5..12 enforced via WebView devtools, edit, remove, undo, reading view.
- APK: releases\Virlin-Android-1.0.9-notes-youtube-20261107-0800-debug.apk

## 2026-11-07 - Notes: voice recording tool (mic in toolbar)
- New non-textual block NoteDocBlockType.AUDIO (appended after VIDEO; ordinals unchanged) carrying NoteDocAudio(file, durationMs, peaks). Stored in documentJson ("audio":{"file","ms","peaks"}), so no Room migration. Only names matching <uuid>.m4a are valid; a damaged entry decodes to audio=null and the block is kept.
- Rich tree node "audio" (attrs id/file/durationMs/peaks/indent), Target.Fixed; any in-place change is refused (only insert/remove).
- data/noteaudio/NoteAudioStore.kt: private files under filesDir/note_audio. Files are not deleted when a block is removed (undo restores it), same as note images.
- Toolbar tool AUDIO ("Record audio", mic icon) after YOUTUBE opens a strip (NoteDialog.Audio): Record (asks RECORD_AUDIO, already in manifest) -> live waveform + timer + Stop / Discard -> preview with play/pause, seekable waveform, Record again, Insert. Close/Back discards an uninserted recording; leaving the app mid-recording stops it into the preview.
- ui/notes/NoteAudioBlock.kt: recorder strip, NoteAudioCard (bordered card: play, loudness outline with tap/drag seek, time, menu Remove recording), NoteAudioPlayback (single MediaPlayer, one recording at a time), NoteAudioPlaybackHost (pauses on background, releases on leaving editor/reader).
- VM: insertAudio (after caret block + new paragraph), deleteAudio; both undoable. Text projections show "[Audio]" / "[Audio ? m:ss]"; word count skips it.
- Tests: NoteAudioDocTest, NoteAudioInsertTest; NoteAddSheetTest tool set updated. 342 notes tests pass.
- Verified on emulator-5556: permission prompt, recording timer, stop -> preview, play/pause/seek, insert, DB node, reopen + play, remove + undo, discard deletes the file.
- APK: releases\Virlin-Android-1.0.9-notes-audio-20261107-0814-debug.apk

## 2026-11-07 - Notes: Text to audio (Cursor agent)
- New toolbar tool "Text to audio" (RecordVoiceOver icon) right after "Record audio"; opens a popup (NoteTextToAudioDialog in ui/notes/NoteTextToAudio.kt).
- Popup: paste/type text (max 20000 chars), Paste/Clear, speed Slower/Normal/Faster, Generate audio -> progress (part i of n, Cancel) -> preview player, Generate again, Insert.
- Uses Android's built-in TextToSpeech (offline, no library, no download, no network). synthesizeToFile writes WAV parts to cache/note_tts (deleted after), which are encoded to AAC .m4a via MediaCodec + MediaMuxer into files/note_audio (NoteAudioStore).
- Inserts the existing AUDIO block; NoteDocAudio gains optional title (start of the text, <=80 chars shown, <=120 stored). Codec "audio.title" and rich node attr "title" are additive; old notes unchanged. Card shows the title.
- Generated file is deleted if the popup closes, text/speed changes or Generate again is pressed without Insert.
- No speech engine / missing voice -> message + "Speech settings" button (com.android.settings.TTS_SETTINGS).
- Tests: NoteAudioDocTest.spokenTextKeepsItsTitleInBothFormats, NoteAudioInsertTest.textToAudioOpensItsPopupAndInsertsLikeARecording + longTextIsSpokenInSentenceSizedParts; NoteAddSheetTest block-tool set. Notes suite 345/345.
- APK: releases\Virlin-Android-1.0.9-notes-tts-20261107-0833-debug.apk (+~47 KB vs audio build). Verified on emulator-5556 only.

## 2026-11-07 - Notes: Arrange blocks (drag to reorder) (Cursor agent)
- New toolbar tool "Arrange blocks" (DragIndicator six-dot icon) in the document group before "Select across blocks"; also in the + sheet tool grid.
- Arrange mode (route state in NotesRoute, like Select across): editor slot shows NoteBlockArranger (ui/notes/NoteBlockArranger.kt) - each visible block as a bordered read-only card (PreviewBlock, now internal) with a translucent six-dot handle on the left. Drag the handle, or long-press the card, to lift it; other cards slide (animateItemPlacement), auto-scroll near edges, spring settle on drop, haptics. Done / Back / any other tool (except undo/redo) leaves the mode.
- Move rule: domain/notedoc/NoteBlockMove.kt moveNoteBlock(blocks, id, to) - a collapsed toggle / toggle heading carries its hidden lines; dropping right after a collapsed toggle outdents just enough to stay visible; any move that would fold visible blocks away is refused (WouldHide).
- NotesViewModel.moveBlock(id, toIndex): one undo step, debounced save; refused with a message when richDocumentAccepts fails.
- RichNoteAdapter.applyBlockEdits: new reorderTopLevel step - reorders whole top-level nodes only when it is a pure reorder, every kept block has its own id, and the read-back order matches; otherwise the tree is left exactly as before (old behaviour), so splitting a list/quote/toggle is refused, never lossy.
- Tests: NoteBlockMoveTest (6), NoteArrangeTest (3), NoteAddSheetTest grid. Notes suite 354/354.
- APK: releases\Virlin-Android-1.0.9-notes-arrange-20261107-0900-debug.apk. Verified on emulator-5556 only (rich note: handle drag down, long-press drag up with auto-scroll, undo in mode, Back).

## 2026-11-07 - Notes: Arrange blocks (drag to reorder) (Cursor agent)
- New toolbar tool "Arrange blocks" (DragIndicator six-dot icon) in the document group before "Select across blocks"; also in the + sheet tool grid.
- Arrange mode (route state in NotesRoute, like Select across): editor slot shows NoteBlockArranger (ui/notes/NoteBlockArranger.kt) - each visible block as a bordered read-only card (PreviewBlock, now internal) with a translucent six-dot handle on the left. Drag the handle, or long-press the card, to lift it; other cards slide (animateItemPlacement), auto-scroll near edges, spring settle on drop, haptics. Done / Back / any other tool (except undo/redo) leaves the mode.
- Move rule: domain/notedoc/NoteBlockMove.kt moveNoteBlock(blocks, id, to) - a collapsed toggle / toggle heading carries its hidden lines; dropping right after a collapsed toggle outdents just enough to stay visible; any move that would fold visible blocks away is refused (WouldHide).
- NotesViewModel.moveBlock(id, toIndex): one undo step, debounced save; refused with a message when richDocumentAccepts fails.
- RichNoteAdapter.applyBlockEdits: new reorderTopLevel step - reorders whole top-level nodes only when it is a pure reorder, every kept block has its own id, and the read-back order matches; otherwise the tree is left exactly as before (old behaviour), so splitting a list/quote/toggle is refused, never lossy.
- Tests: NoteBlockMoveTest (6), NoteArrangeTest (3), NoteAddSheetTest grid. Notes suite 354/354.
- APK: releases\Virlin-Android-1.0.9-notes-arrange-20261107-0900-debug.apk. Verified on emulator-5556 only (rich note: handle drag down, long-press drag up with auto-scroll, undo in mode, Back).

## 2026-11-07 - Notes: Rename note strip (Cursor agent)
- New toolbar tool "Rename note" (DriveFileRenameOutline pen icon), first in the document group; also "Rename" in the + sheet grid.
- Opens NoteDialog.Rename -> NoteRenameStrip (ui/notes/NoteRenameStrip.kt) under the rail: 60dp white strip, pen badge, single-line field pre-filled with the current name (all selected, keyboard opens), Save button (disabled when blank), close X, Back closes, IME Done saves.
- NotesViewModel.renameNote(name): collapses whitespace, trims, caps at MAX_TITLE=120, refuses blank, saves via the existing title path (onTitleChanged -> debounced saveNoteDoc) and closes the strip.
- Only the note's own stored title changes. Mind-map boxes use project/stream/task titles, so a task note's name is independent of its box. For Inbox (capture) notes the existing save path keeps the capture title equal to the note title, as before.
- Tests: NoteRenameTest (3); NoteAddSheetTest grid picks up the new tool. Notes suite 357/357.
- APK: releases\Virlin-Android-1.0.9-notes-rename-20261107-0938-debug.apk. Verified on emulator-5556 only (Done key save, Save button, X discards); test note renamed back to "Rich test".

## 2026-11-07 10:44 - Note card preview matches the note (task Page / map page)
- ui/notes/NotesRoute.kt: extracted the reading view's block loop into NoteDocumentBlocks (unchanged behaviour); new NoteDocumentPreview(blocks, palette) renders a note exactly like the reader (fonts, runs, colours, alignment, lists, tables, code, equations, media) in the project's note palette.
- ui/hierarchy/MasterDocument.kt: MasterDocumentEntry gains optional noteBlocks (default null, so PDF export and other callers are untouched); previewableNoteBlocks(doc) returns blocks only for readable, non-empty notes.
- ui/hierarchy/MapContainerPageScreen.kt: MasterDocumentItem takes optional notePalette; when the entry has noteBlocks the expanded card shows NoteDocumentPreview (tag map_page_note_preview_<id>) instead of the flat text body. Card shell, header and Open button unchanged. Map page task-note entries pass their blocks.
- ui/page/TaskPageScreen.kt: TaskPageRow gains noteBlocks; Note rows keep the loaded doc's blocks and pass them plus the project palette to the card.
- Empty or unreadable notes keep the previous text preview ("No note content yet").
- Tests: MasterDocumentItemUiTest +2 (preview shown, flat text gone, Open still works; previewable rules). 369/369 focused tests pass.
- APK: releases\Virlin-Android-1.0.9-notes-notepreview-20261107-1036-debug.apk (verified on emulator-5556 only).

## 2026-11-07 10:52 - Note cards show the note's own name
- ui/hierarchy/MasterDocument.kt: noteCardTitle(doc, fallback = "Notes") - the note doc's title (whitespace collapsed), else the fallback.
- ui/page/TaskPageScreen.kt: task Page Note rows are titled noteCardTitle(doc) instead of the fixed "Notes". The same title is passed when opening the note, which the editor only uses when the note has no stored title, so opening is unchanged.
- ui/hierarchy/MapContainerPageScreen.kt: map page task-note entries use noteCardTitle(doc, entry.label) instead of the fixed label.
- Tests: MasterDocumentItemUiTest +1. Verified on emulator-5556: rename shows on the card while the task/map box keeps its own name; test note renamed back to "Natural Language".
- APK: releases\Virlin-Android-1.0.9-notes-notetitle-20261107-1048-debug.apk

## 2026-11-07 11:04 - Unlimited notes per mind-map task (Add > Note always starts a new note)
- domain/model/TaskPageModels.kt: TaskPageTypeKeys.extraNoteOwner(taskId, noteId) = "task-<taskId>#note-<id>" plus isExtraNoteOwner / noteTaskId / canonicalNoteOwner / extraNotePrefix. Canonical "task-<taskId>" keys unchanged; no schema change.
- domain/action/TaskPageActions.kt: newNote(taskId) -> NewTaskNote(ownerKey, title): the canonical note while it has never been written, otherwise a new extra note ("<task> N") with its Page block created up front. loadAndReconcile drops Page blocks of extra notes that were never saved (opened and left empty) and re-densifies order.
- ui/map/ProjectMapScreen.kt: Add > Note calls newNote and opens notesRoute(ownerKey, title). Voice/Prompt/Link/PDF/Attachment/Image already create a new capture each time (unchanged).
- ui/notes/NotesRoute.kt: project palette resolves extra task-note keys; an extra note re-ensures its Page block once saved.
- ui/page/TaskPageScreen.kt + ui/hierarchy/MapContainerPageScreen.kt: Note rows read and open block.contentId (identical for canonical notes); unsaved extra notes are hidden.
- domain/action/ProjectDeletion.kt, RoomWorkStreamRepository, InMemoryWorkStreamRepository: deleting a task/project also deletes its extra notes (prefix match via substr, chunks too); late saves to an extra note of a deleted task are refused like canonical ones.
- domain/project/ProjectDuplicator.kt: extra notes copy to the new task's extra keys.
- Tests: TaskPageActionsTest +4. Wider suite 658 run; 6 failures in ProjectAppearanceUiTest / PopupViewportUiTest / MasterResumeRoomTest, none of which touch this change.
- Verified on emulator-5556: second note created and listed, empty third note left no card, card opens its own note; DB shows only the two notes. A test note "Natural Language 2" remains on the test AVD.
- APK: releases\Virlin-Android-1.0.9-notes-multinote-20261107-1100-debug.apk

## 2026-11-07 - Duplicate note tool (APK notes-duplicate-20261107-1108)
- TaskPageActions.duplicateNote(sourceOwner): one transaction; copies the stored task note (blocks + rich document verbatim, title "<title> (copy)") into a new extra owner task-<id>#note-<id>, lists it on the box Page right after the source. Refuses non-task, unsaved, damaged notes or missing tasks (writes nothing).
- NoteTool.DUPLICATE (ContentCopy, "Duplicate note") after Rename in the toolbar and the + sheet grid; enabled only for task notes that loaded cleanly. NotesViewModel.duplicateNote saves pending edits first, then shows where the copy went; the open note is unchanged.
- Images/audio are shared by reference (stores never GC files). Test: TaskPageActionsTest duplicating_a_note_copies_its_document_and_lists_the_copy_right_after_it.

## 2026-11-07 - Notes YouTube: picture fix, minimal player, digit time entry (APK notes-videoplayer-20261107-1202)
- Root cause of 'audio but no picture': Compose AndroidView gives the WebView WRAP_CONTENT layout params; a wrap-content WebView lays the page out at zero height (html computed height 0px), so YouTube sized its video element to height 0. Verified via WebView devtools (video style height:0px, readyState 4, playing). Fix: WebView layoutParams MATCH_PARENT in NoteVideoPlayer. Hardware layer kept.
- Player: embed URL now controls=0&iv_load_policy=3 (documented params); a control row below the video (never over it): play/pause, clip-relative seek slider, elapsed/clip time; seek off during adverts; YouTube branding and pause overlay untouched. Status polled via evaluateJavascript (no JS bridge). Scripted fullscreen is refused by WebView without a page gesture, so no fullscreen button; Open in YouTube stays in the menu. Card padding 8->5dp for a larger player.
- Time entry: start/end fields take digits filled from the right (130 = 1:30, 10230 = 1:02:30), shown formatted; leaving a field normalises carry-over (90 -> 1:30). Clip summary/hint line under the fields. NoteYouTube.clockDigits/formatClockDigits/clockDigitsSeconds/secondsToClockDigits.
- Tests: NoteYouTubeTest (+2, embed URL updated). 219 notes tests pass. Not changed: ui/link/YouTubeInlinePlayer.kt (same wrap-content pattern there).

## 2026-11-07 - Notes editor: opening space scrolls with the document (APK notes-scrolltop-20261107-1214)
- VirlinNotesScreen: removed the fixed top=28dp padding around the editor slot (it clipped text under a white band below the rail). New NoteDocumentTopSpace = 28.dp.
- NoteBlockEditor LazyColumn: contentPadding(top = NoteDocumentTopSpace), so the space shows at the top of the note and scrolls away; item indices, scroll anchors and saved reading positions unchanged.
- NotesRoute: arrange mode, damaged-note panel and paste indicator keep the 28dp top offset (look unchanged).
- NotePasteUiTest.topBlockIndex now ignores blocks only visible inside the opening space. 274 notes tests pass.

## 2026-11-07 - Notes reading view uses the editor renderer (read-only)
- NotesReaderRoute (ui/notes/NotesRoute.kt) now draws NoteBlockEditor(readOnly = true) with the note's project palette (shared rememberNoteProjectId) instead of the older NoteDocumentBlocks preview. Header: '<- Edit' + 'Reading view'; title as the first scrolling item.
- NoteBlockEditor.kt: readOnly + readerHeader params; NoteRowActions keeps collapse/video play view-local in read-only mode; ReadOnlyBlockText (read-only selectable text, same style/transformations), ReadOnlyTable (same widths/fills, no edge + controls); edit/remove/LaTeX-source/checklist-toggle disabled when read-only; shared blockTextStyle().
- No document is written from the reader. Test: NoteReadingViewUiTest. APK releases/Virlin-Android-1.0.9-notes-readingview-20261107-1222-debug.apk

## 2026-11-07 - Notes header: three-dot menu removed, compact save status
- VirlinNotesScreen.kt: removed the 'Note options' overflow menu (Preview / Export / Paste details) ahead of a planned redesign; onExport/onPasteDetails params dropped (NotesRoute updated). Preview stays on the eye button. VM onExport() and the PasteDetails dialog code remain for reuse.
- Save status is now a compact 13dp check + 11sp label (tag notes_save_status) at the right edge; title gets the freed width.
- APK releases/Virlin-Android-1.0.9-notes-header-20261107-1238-debug.apk

## 2026-11-07 - Notes: Print / Save PDF
- New NoteTool.PRINT (rail, after Duplicate; Add sheet 'Print / PDF') opens NoteDialog.Print -> NotePrintStrip (ui/notes/NotePrintStrip.kt): Paper A4/Letter/Legal/A5, Layout portrait/landscape, Page numbers off/top/bottom + position left/centre/right, Margins narrow/normal/wide, Title show/hide, live page count, PDF and Print buttons.
- ui/notes/NotePrint.kt: NotePrintRenderer draws the note with the editor's sizes/rhythm/project colours/fonts (Google fonts resolved via LocalFontFamilyResolver), runs (bold/italic/underline/strike/colour/highlight/size/font/link), lists, checklists, quote, code (syntax colours), equation (LaTeX source in card), prompt, divider, tables (split between rows), images, video and audio cards; collapsed sections print expanded. NotePrinting.print uses PrintManager + PrintDocumentAdapter (re-lays out for the paper chosen in the system dialog); Save PDF writes via CreateDocument. Uses android.graphics.pdf only; no new dependencies; note data is never modified.
- Tests: NotePrintRendererTest. APK releases/Virlin-Android-1.0.9-notes-print-20261107-1255-debug.apk

## 2026-11-07 - Notes: Export (PDF / Word / Markdown / Image)
- New toolbar + Add-sheet tool EXPORT (NoteTool.EXPORT, NoteDialog.Export) opening NoteExportStrip under the rail: four animated format tiles; each format's options animate in within the same strip.
- NoteExport.kt: NoteExportSettings, CreateExportDocument (SAF, dynamic MIME), NoteExportImages (downscale to display size x DPI; JPEG unless real transparency), NoteExporter (bytes per format, zip, file names, size label).
- NotePdfSlimmer.kt: rewrites PdfDocument's lossless opaque RGB / sRGB-ICC image streams as JPEG and rebuilds xref; returns the original on anything unexpected or when not smaller.
- NoteDocxWriter.kt: hand-written OOXML (.docx) - styles (Title, Heading1-5, Quote, Code, Prompt), real numbering lists, tables, hyperlinks, fonts/colours/highlights, embedded pictures. Opens in Word; Google Docs compatible.
- NoteMarkdownWriter.kt: GFM output (marks, lists, checklists, quotes, fenced code with language, $$ equations, pipe tables); optional .zip with images.
- NotePrint.kt: NotePrintSource.imageDpi, layoutLong(), public drawPageNumber(), SYNTAX_COLORS internal, image decode via NoteExportImages.decodeFor. Print behaviour unchanged.
- No new dependencies. Test: NoteExportTest (7). Notes suite 291/0. APK releases\Virlin-Android-1.0.9-notes-export-20261107-1333-debug.apk (emulator-5556 only).

## 2026-11-07 - Notes: page formatting (Formatting tool, page view, formatted print/export)
- New rail + Add sheet tool NoteTool.FORMATTING ("Page formatting"), handled in NotesRoute (view state, not a VM dialog).
- NoteFormattingStrip.kt: one compact sideways-scrolling strip (paper A4/A3/A5/Letter/Legal, layout, margins, page numbers, position, title) with feathered edges and a one-time peek; shared NoteFeatheredRow + NoteStripChoice.
- NotePageSetupStore.kt: per-note page setup + on/off in SharedPreferences "note_page_setup" (beside the note, never in the document; no Room change).
- NotePageView.kt + NoteBlockEditor(pageView=): editor drawn at paper scale (LocalDensity override) as white sheets on a desk, margins, title on page 1, Page N of M header/footer; breaks from NotePrintRenderer.paginate; page fill from measured block heights. Folded blocks show open; arrange/select-across turn it off.
- NotePrint.kt: A3 paper, NotePaper.offered, keepBlocksWhole, NotePagination/paginate, video card drawn like the editor (16:9 thumbnail, badge, title), Save PDF slimmed.
- Print strip slimmed to its header (summary opens formatting); Export strip slimmed to tiles + one feathered row (Pages chip + format options). NoteExportSettings lost paper/layout/numbers/title; exporter takes the page setup. Word uses its margins and live PAGE/NUMPAGES header or footer.
- Print / Save PDF / Export (PDF, Word, Image) without formatting on asks "Format the pages first?" (Turn on formatting / Continue anyway with A4 defaults). Markdown never asks.
- Tests: NotePrintRendererTest (+3: pagination matches layout, A3, setup round trip), NoteExportTest updated (footer). Notes suite 294/0.
- APK: releases\Virlin-Android-1.0.9-notes-formatting-20261107-1358-debug.apk (installed on emulator-5556 only).

## 2026-11-07 - Notes: Page section note preview drawn by the editor itself
- NoteDocumentPreview (Page / map page cards) now uses NoteReadOnlyBlocks (NoteBlockEditor.kt): the editor's own NoteBlockRow, read only, so prompt/code boxes with Copy, tables, media, fonts and spacing match the editor exactly. Toggles fold and videos play as view-only state.
- NoteBlockRow / BlockTextField / NoteTableBlock accept a null view model, which forces read only (no edit callback can run). List numbering moved to noteListNumbers().
- Removed NoteDocumentBlocks (the simplified preview list); PreviewBlock stays for the arranger.
- Tests: notes + hierarchy + task page suites 465 run; only the pre-existing PopupViewportUiTest / ProjectAppearanceUiTest failures (settings dialogs, unrelated).
- APK: releases\Virlin-Android-1.0.9-notes-pagepreview-20261107-1414-debug.apk (emulator-5556 only).

## 2026-11-07 - Notes: removed the YouTube entry hint
- NoteVideoEntry.kt LookupRow: the 'Copy a video's link in YouTube (Share -> Copy link)...' hint is gone; the status row is skipped while the link is empty (no blank gap). Errors, checking and found-video rows unchanged.
- APK: releases\Virlin-Android-1.0.9-notes-videohint-20261107-1417-debug.apk (emulator-5556 only).

## 2026-11-07 - Notes: rounded YouTube player corners
- NoteVideoBlock.kt NoteVideoPlayer: the WebView clips to a 10dp rounded outline (ViewOutlineProvider + clipToOutline, which also clips the hardware video layer); the black backdrop and load-error panel use the same shape. Matches the thumbnail's 10dp. Player, controls, clip script and fullscreen unchanged; nothing overlays YouTube's player.
- APK: releases\Virlin-Android-1.0.9-notes-videocorners-20261107-1425-debug.apk (emulator-5556 only).

## 2026-11-07 - Notes: Auto-fit pages
- NotePrint.kt: NotePrintSettings.autoFit. With it, blocks flow across pages split between lines (renderer's existing line-slicing; boxes keep rounded corners only at their real ends); images, video, audio and table rows stay whole; a heading moves to the next page unless ~2 lines of what follows fit (KEEP_WITH_HEADING). Without it, unchanged keep-whole behaviour.
- NotePageSetupStore: 7th field F/- ; six-field entries read back with auto-fit off.
- NoteFormattingStrip: Auto-fit toggle chip first in the strip.
- NotePageView.kt NoteFlowPages: read-only page view drawn by the print renderer (page bitmaps on Dispatchers.Default, one at a time via Mutex); tap shows 'Turn Auto-fit off to edit'.
- NotesRoute: outputSettings.keepBlocksWhole = !autoFit; auto-fit view replaces the editor while on; page count from the flowed layout. No document or schema change.
- Tests: NotePrintRendererTest +3. APK releases\Virlin-Android-1.0.9-notes-autofit-20261107-2310-debug.apk (emulator-5556 only).

## 2026-11-07 - Mind map: long pasted names and resize lock
- Cause: rename fields (MapRenameDialog, MapNodePreviewPopup name) were singleLine, so a pasted name with line breaks showed only its first line; the hidden lines were kept on save. The long hidden title also pushed minimumCardWidth to the 560 max, locking resize.
- MapStructureEditor.kt / MapNodePreviewPopup.kt: name fields multi-line (maxLines 6); preview name text ellipsizes.
- VirlinMindMap.kt: minimumCardWidth capped at 160 (was 560); compactCardSize counts typed line breaks for card height.
- Test: ProjectMapTest.aBoxWithALongPastedNameCanStillBeResized. APK releases\Virlin-Android-1.0.9-map-namefix-20261107-2345-debug.apk (emulator-5556 only).

## 2026-11-07 - Mind map organize strip (notes-style tools)
- MapStructureEditor.kt: with Organize on, selecting a box shows a full-width white strip under the top bar (gentle fade/slide in). Tools are notes-style bordered 40dp rounded buttons in groups with 1x24dp dividers: Edit, Into, Add branch | Copy, Duplicate (or Open), Paste into | Resize, Box color, Hide box, Show hidden | Undo, Redo | Delete. Scrolls sideways inside NoteFeatheredRow (feathered edges + one-time peek). Clear selection stays pinned at the right under the three-dot menu. Same callbacks, labels and selected semantics; colour palette still opens below.
- VirlinMindMap.kt: structure bar overlay is full width (inset moved into the bar) and zIndex 5 so branch bubbles/handles do not draw over the strip.
- MapSelectionFlowUiTest: order assertion now Edit < Into < Add branch < Clear; delete test scrolls to Delete first.
- APK: releases\Virlin-map-organizestrip2-*.apk (emulator-5556 only).

## 2026-11-07 - Page Organize: working Duplicate + Delete with typed-name warning
- TaskPageActions: duplicateHere(blockId) copies a note (existing duplicateNote) or a captured item (duplicate into the same task, title + ' (copy)') listed right after the original; checklist refused. delete(blockId) deletes a note document (refuses unreadable/loadError notes) or archives a captured item (restorable from the Inbox), removes the card and keeps a dense order; checklist refused. loadAndReconcile no longer re-adds ARCHIVED captures.
- WorkStreamWriter.deleteNoteDoc(ownerKey) added (Room: deletes chunks + row via existing DAO queries; in-memory staged). No schema change.
- TaskPageScreen: Duplicate copies in place (enabled for notes + captured items); new red Delete button opens DeleteByNameDialog (type the exact name). Clear reason for empty/unreadable notes. Result messages no longer wiped by the refresh (also fixes Move/Duplicate-to-task messages).
- DeleteProjectDialog.kt: DeleteHierarchyDialog now delegates to DeleteByNameDialog(warning) - same text and behaviour for existing callers.
- Tests: TaskPageActionsTest +2. Pre-existing failures confirmed on HEAD e455517 (temp worktree, removed): AttentionExitTest, BulkNamesTest, ExternalTimingTest, MasterResumeRoomTest.
- APK: releases\Virlin-page-dup-delete2-20261107-2329.apk (emulator-5556 only).

## 2026-11-07 - Page Move uses the guided destination sheet; notes can move
- New ui/page/PageMoveDestinationSheet.kt: same look as the task/map 'Choose a destination' sheet (palette accent, path chip, Back one level, folder rows, explore arrows, DESTINATION footer + Move here). Browses project > workstreams > tasks > subtasks; only tasks are choosable; the item's own task shows 'Already here' (disabled, still explorable). Opens beside the current task.
- TaskPageScreen: Move opens the sheet (old AlertDialog list removed); Move disabled for the checklist; page wrapped in a Box so the sheet overlays it. VM.moveTo: captured items via existing move(); notes via new moveNote(); message 'Moved to <task>' or a clear reason (empty note).
- TaskPageActions.moveNote(blockId, destTaskId): same-project only; refuses empty/unreadable notes; saves the whole document under a new extra note owner of the destination, adds its card last, then deletes the source doc + card, in one transaction. Returns the new owner key.
- FlashcardStore.rekey(from, to): flashcards follow a moved note; page setup (NotePageSetupStore) copied to the new key.
- Tests: TaskPageActionsTest +1 (moving a note), FlashcardStoreTest +1 (rekey).
- APK: releases\Virlin-page-move-sheet-20261107-2340.apk (emulator-5556 only).

## 2026-11-08 - Mind map name editor standardized
- MapStructureEditor.kt: MapRenameDialog restyled like the Preview details card (white 26dp card, 'Edit name' header with round close, accent label, filled 1-6 line field, Cancel + thick 48dp accent Save). Tags map_rename_dialog / map_rename_cancel added.
- preview/MapNodePreviewPopup.kt: inline name field removed; the pencil opens the same MapRenameDialog (new nameLabel param). The edited name stays pending and is saved by the popup Save; popup Cancel discards it.
- ProjectMapScreen.kt: passes nameLabel (Task name / Project name).
- MapPreviewNameUiTest: pencil test updated to the shared dialog, cancel test added. map + mappreview unit tests: 148 passed.
- APK: releases\Virlin-map-name-editor-20261108-0850.apk (verified on emulator-5556 only).

## 2026-11-08 - Mind map box colour code (#)
- MapStructureEditor.kt: box colour palette moved into MapBoxColorPalette; a pinned '#' button switches the strip to a colour-code field (# prefix, live preview dot, red border when invalid, green Done). Applied codes are set (not toggled) via new onSetBoxColor and kept as custom swatches at the front (last 8, SharedPreferences map_box_colors). Preset swatches and their tags unchanged.
- MapBoxColor.kt: setBoxColor helper. ProjectMapScreen.kt: wires onSetBoxColor to saveMapAppearance. No storage format change (nodeColors already holds any ARGB).
- Tests: MapBoxColorTest +1, MapSelectionFlowUiTest +1; map + mappreview 150 passed.
- APK: releases\Virlin-map-box-hex-20261108-0857.apk (emulator-5556 only).

## 2026-11-08 - Mind map header: no gap, feathered edge
- VirlinMindMap.kt: header row drops its 4dp bottom padding so the organize strip sits directly under it; the map area (BoxWithConstraints) is clipToBounds so boxes slide under the header instead of painting over the title; a 20dp surface-to-transparent feather (tag mind_map_header_feather, zIndex 4) sits at the top of the map and is covered by the opaque organize strip (zIndex 5) when it is open. Not in focus mode.
- map + mappreview tests: 150 passed. No golden covers the mind map.
- APK: releases\Virlin-map-header-feather-20261108-0904.apk (emulator-5556 only).

## 2026-11-08 - Mind map Organize button + Export strip
- VirlinMindMap.kt: when the host supplies onToggleStructureMode, the header three-dot menu is replaced by MapOrganizeToggle (DesignServices icon; on = accent disc springs in, icon turns into a check; Role.Switch, 'Organize'/'Done organizing', tag mind_map_structure_toggle). Hosts without a toggle keep the old More menu (Export) unchanged.
- MapStructureEditor.kt: Organize strip now shows whenever Organize is on, also with no box selected (box tools disabled, Clear selection hidden; colour palette and resize highlight only with a selected box). New Export tool (exportOptions param, MapExportOption) opens MapExportStrip under the strip: feathered horizontally scrolling chips XMind/VMap/PDF/PNG image/Markdown/JSON, close X, Back closes; choosing a format closes it and runs the same launcher. Export and box colours are mutually exclusive.
- ProjectMapScreen.kt: builds exportOptions from the existing SAF launchers (same file names).
- Organize still pauses/resumes timers exactly as before (same onToggleStructureMode).
- Tests: MapSelectionFlowUiTest updated (toggle button instead of More menu) + new strip/export test; map + mappreview 151 passed.
- APK: releases\Virlin-map-organize-button-20261108-0914.apk (emulator-5556 only).

## 2026-11-08 - Mind map Undo/Redo audit + fixes
- Audit: undo/redo itself worked for appearance + moves, but renames were never recorded, duplicate/paste/delete wiped the whole history, history lived only while the map screen was composed (lost after opening a Page/task), and an unreplayable move stayed on top and blocked every earlier step.
- MapEditHistory.kt: MapEdit.Rename; discard(edit); dropMoves(); MapEditHistories session registry per project (forget on project delete); MapAppearance.applyStep(from,to) re-applies only the fields a step changed (no stale full-snapshot overwrite of later/desktop/cleanup changes); describe() for messages.
- ProjectMapScreen.kt: history from MapEditHistories.of(project.id); renameNode() shared by strip Edit, preview popup name and history; both renames recorded; travelHistory uses applyStep, replays renames only if the title is still the expected one, drops a refused step with a 'Can't undo/redo' notice, and shows 'Undone: x' / 'Redone: x' snackbar; duplicate, paste (copy) and node delete call dropMoves() instead of clear().
- Tests: MapEditHistoryTest +4; map + mappreview 155 passed.
- APK: releases\Virlin-map-undo-redo-20261108-0923.apk (emulator-5556 only). Verified: rename -> Undo -> Redo -> Undo, history kept after opening a Page and returning.

## 2026-11-08 - Duplicate task branch popup restyled
- MapStructureEditor.kt: new MapConfirmDialog (same card as MapRenameDialog: white 26dp, title + round close, muted message, Cancel + thick 48dp accent confirm; tags map_confirm_dialog/_cancel/_ok).
- ProjectMapScreen.kt: 'Duplicate task branch?' uses MapConfirmDialog instead of AlertDialog; same duplicateTask action, history and snackbar.
- Tests: MapSelectionFlowUiTest +1; map + mappreview 156 passed.
- APK: releases\Virlin-map-duplicate-dialog-20261108-0938.apk (emulator-5556 only).

## 2026-11-08 Organize timers: edits never strand paused timers; paused items can take children
- New domain/action/OrganizeTimerPause.kt: pendingTimerPause (last STATE-CHANGE event must be the provenance pause; renames/reorders/new children no longer hide it), isOrganizePaused, endOrganizePauseForChildren (PAUSED -> READY in the caller's transaction, cycle closed, banked time and closed sessions kept, READY event recorded).
- DefaultVirlinActions.resumeTimers: replaced the brittle pause.at == item.updatedAt guard (rename / duplicate sibling shift / move left timers stuck PAUSED after Organize) with pendingTimerPause; provenance strings shared as constants.
- StructureActions: createTaskIn / placeTask / placeBranch / moveTasks accept an Organize-paused owner or destination and end its paused timer after all validation (placeBranch/moveTasks after the sibling writes, which save rows from the pre-move snapshot). Moving a branch that holds Organize-paused timers is allowed only within the same workstream (task timer history is keyed by workstream). Active or manually paused items still refuse children, unchanged.
- ProjectMapScreen: add-subtask snackbar says when the parent's paused timer ended.
- Tests: ProjectTimerControlTest +3. Focused domain run 95 tests, 1 failure = pre-existing MasterResumeRoomTest timing (660 vs 600).
- Verified on emulator-5556: focus task -> Organize pauses it (0 open sessions) and the running external; add subtask under the paused task succeeds; Done organizing resumes the external, the new parent stays READY. Test subtask "Organize draft" left on the test AVD.
- APK: releases\Virlin-organize-timers-20261108-1015.apk

## 2026-11-08 Map selected-task strip always on top
- ui/map/VirlinMindMap.kt: MapSelectedTaskBar (Open Task / Flashcard / Page / Add) gets zIndex(6f). Fold pills (z0, composed after it), resize edge (z1) and Organize drag handle (z2) used to paint over it while panning; now they pass underneath and the opaque strip also takes the touches. Zoom controls (z10) are lifted above the strip already; the Add palette replaces the strip.
- Tests: map + mappreview 156/156.
- Verified on emulator-5556: Organize on, selected node panned under the strip; its handle and fold pill are hidden behind the strip.
- APK: releases\Virlin-map-strip-on-top-20261108-1000.apk

## 2026-11-08 - Map colour studio rebuilt (Cursor agent)
- Fixed: branch colours. Every node used to inherit the root colour, so all branches used palette colour 1. Each workstream now takes its own palette colour (MapColorScheme.kt mapBranchTints). Visible change on existing maps: workstreams are now multicoloured.
- New colour model in MapAppearance (all optional, defaults paint exactly as before): branchMode MULTI/SINGLE/LEVELS, singleBranchColor, levelColors, boxFill SOFT/PASTEL/BOLD/WHITE/CUSTOM + boxFillColor, boxBorder AUTO/BRANCH/CUSTOM/NONE + boxBorderColor, boxTextColor (auto contrast otherwise), backgroundColor, savedPalettes. 13 presets (first 4 keep their old indices).
- Hand-drawn finishes now honour fill/border choices (HandDrawnBox.kt new optional params).
- Persistence: MapColourJson.kt shared by MapAppearanceStore and VirlinMapFile (.vmap stays schema 1, fields additive). Undo/redo steps the new fields (MapEditHistory.applyStep). Canvas and PNG/PDF exports use the map background.
- MapStudioPage.kt rewritten: sticky live preview (real map slice, real workstream colour indices, drawn on the map background), tabs Palette / Branches / Boxes / Items, one Save/Cancel footer. Palette: editable slots (2-12), shuffle, reverse, presets, My palettes, harmonies from one colour, paste import (hex codes, Coolors / Color Hunt links; text only, no network).
- MapColourPicker.kt: one picker dialog (hue ring + saturation/brightness square, HSB sliders, swatch grid, hex, palette and My colours).
- desktop/virlin-connect/src/project-map.js: added the 9 new presets to MAP_PALETTES so indices match. Desktop does not render the new fill/border/background modes.
- Tests: new MapColourStudioTest (10); MapSelectionFlowUiTest updated for the new studio labels. Map suites 166/166. Goldens not re-recorded.
- APK: releases\Virlin-map-colour-studio-20261108-1023e.apk (verified on emulator-5556).

## 2026-11-08 - Flashcard composer redesign (rich, exact note blocks)
- New ui/notes/FlashcardSlice.kt: sliceProjectedRange (exact note blocks under a cross-block selection; types, runs, fonts, links, media kept), flashcardBlocks, flashcardPassageText, flashcardSummaryBlock.
- FlashcardCreateDialog rewritten: floating card with all corners rounded (SafeDialog), fixed header and Create button, scrolling body. Passage selection has the system Copy/Select-all bar suppressed; Front/Back send buttons sit below the passage (no selection = whole passage). Back starts as the whole passage, Front empty. Each side previews with NoteDocumentPreview; edit mode edits block by block (type kept), B/I, remove part, + Line.
- FlashcardStore: optional frontBlocks/backBlocks (additive JSON fields, v=1 kept, legacy fields still written; undecodable face falls back to the summary). Set-based list now includes cards from an owner's own notes (task-1#note-x under task-1) - task-note cards previously never appeared in branch study.
- NotesRoute: only the FLASHCARD tool call site and the save call changed (no editor changes).
- FlashcardsRoute StudyCard: renders rich faces with NoteDocumentPreview; legacy cards unchanged; copy uses full face text.
- Tests: FlashcardCreateDialogTest rewritten, FlashcardRichSidesTest (new), FlashcardStudyScreenTest rich-face test. APK releases\Virlin-flashcard-composer-20261108-1215.apk verified on emulator-5556.

## 2026-11-08 - Task Page header title on one line
- TaskPageScreen top bar: title is one line with ellipsis (20sp, was 23sp unbounded) and the Page caption is one line, so long task names no longer wrap over the 72dp header. No other change. APK releases\Virlin-page-title-20261108-1215.apk verified on emulator-5556.

## 2026-11-08 Map palette now colours the boxes
- MapAppearance: boxPaletteMode (By workstream / By level / One colour / Every box / Match lines), boxStrength (Light/Medium/Strong, default Medium), boxLevelColors, boxSingleColor, boxBranchColors (per-branch, inherits down), branchPalette (optional separate line colours).
- MapColorScheme: mapBoxTints() is the box-colour pipeline; paintMap tints boxes from it, connectors still from mapBranchTints(). Organize highlight (nodeColors) still wins for its one box only. Soft/hand-drawn fills now show the tint by strength; Light keeps the old look.
- MapColourJson / MapEditHistory / MapAppearanceStore.prune carry the new fields. MapStudioPage Palette tab: 'How boxes use this palette', level/workstream rows, Colour strength, 'Colour a branch' search. Branches tab: editable line colours. Boxes tab untouched.
- Tests: MapColourStudioTest new cases; map suite 119/119. APK releases\Virlin-map-box-palette-20261108-1500.apk on emulator-5556. Desktop project-map.js not updated.

## 2026-11-08 29 new map box finishes
- MapModels: MapBoxFinishGroup (Classic / Hand-drawn & handmade / Materials & modern); MapBoxFinish gains a group and 29 entries appended (saved by name, no migration): 16 handmade (Crayon, Charcoal, Watercolour, Brush pen, Felt tip, Scribble fill, Cross hatch, Stipple, Rough sketch, Highlighter, Dry brush, Stitched, Taped note, Sticky note, Torn paper, Paper cut-out) and 13 materials (Kraft card, Canvas, Cork board, Wood grain, Marble, Concrete, Brushed metal, Soft clay, Terrazzo, Dot grid, Graph paper, Notebook, Halftone).
- New BoxFinishes.kt: drawCraftedBox (handmade, deterministic noise, palette-tinted paper + palette pigment, ink overrides as before) and drawMaterialTexture (translucent overlay on the palette fill). Paint state restored after drawing.
- HandDrawnBox: isHandDrawn() = HANDMADE group; new finishes delegate to drawCraftedBox, original four unchanged. paintMap passes paletteTint and draws material textures. MapStudioPage Box finish card grouped by MapBoxFinishGroup; tiles preview with the same painters.
- Tests: MapBoxColorRenderTest (every finish paints, follows box colour, keeps connector), ProjectMapTest (every finish round-trips .vmap). Map suite 122/122. APK releases\Virlin-box-finishes-20261108-1300.apk on emulator-5556.

## 2026-11-08 8 new map layouts (7 hand-drawn)
- MapLayout: handDrawn flag + SKETCH_TREE (Pencil sketch), WHITEBOARD, DOODLE_BUBBLES, STICKY_WALL, BRUSH_INK, DRAFTING (Drafting table), PAPER_CRAFT, METRO_LINES. Saved by name, no migration. All left-to-right logic charts.
- New CraftLayouts.kt: craftNodePath (wobbly/sticky/cut shapes seeded per frame), craftCardHeight (sticky taller, bubbles roomier), drawCraftUnderlay (shadow / second paper sheet), drawCraftFill (layout surface only under the Smooth finish), drawCraftOutline (ink edges, respects border mode, selection always visible), drawCraftConnector (pencil elbows, marker curves, strings, tapered brush, ruled elbows, ribbons, octilinear metro), drawCraftEndpoint (dots, arrowheads, stars, push pins, flicks, ticks, tabs, station rings).
- VirlinMindMap: arrange x/baseStep branches + craftCardHeight; paintMap routes craft layouts to the new painters; picker shows a Hand-drawn tag. BoxFinishes: mapNoise/wobblyPath made internal for reuse.
- Tests: ProjectMapTest layout count 21 + distinct labels; MapBoxColorRenderTest every layout draws connectors and follows box colour (with Crayon / Kraft+no-border variants). Map suite 143/143. APK releases\Virlin-craft-layouts-20261108-1330.apk on emulator-5556.

## 2026-11-08 Notes select-across: real blocks + whole-block multi-select
- NoteBlockEditor: select mode now draws the note's own blocks (NoteBlockRow, read only: same fonts, video/audio cards, equations, tables) under a tap/long-press selection layer instead of the plain-text projection. Pinned bar: count, Select all/Clear, Copy/Copy all. Normal and paged rendering unchanged.
- NoteBlockSelection.kt (new): blockWithFolded (selecting a folded heading/toggle includes what it hides), blockRange, contiguousBlockRange.
- NotesViewModel: deleteBlocks(ids) and cycleBlocksCase(ids), one undo step each; deleteBlocks refuses (message, no change) when the rich document could not hold the result.
- NotesRoute: selection is a set of block ids; Flashcard/Toggle/Change case/Delete act on whole selected blocks; toggle needs neighbouring blocks.
- Tests: NotePasteUiTest + RestoredNotesRouteTest updated to the block selection, new NoteBlockSelectionTest. APK releases\Virlin-note-multiselect-20261108-1620.apk.

## 2026-11-08 Page cards: open button in the card header
- MapContainerPageScreen.MasterDocumentItem (shared by the task Page and the map/project Page): 40dp open button (OpenInNew, testTag map_page_open_top_<id>, label 'Open <kind> <title>') beside the expand chevron, visible collapsed or expanded; opens the same route as the bottom 'Open <kind>' button, which is kept. Card content and expand behaviour unchanged.
- MasterDocumentItemUiTest: headerOpenButtonOpensTheNoteWithoutExpandingTheCard. APK releases\Virlin-page-open-icon-20261108-1520.apk.

## 2026-11-08 Flashcards redesign: deck page + full-height practice page
- FlashcardsRoute.kt: FlashcardStudyScreen is now two pages. Deck: one-line group chips (New/Due/Future/All/Archived with counts), sort menu (Scheduled/Weakest/Newest/Starred first), Practice button, colour grid of cards (tap = practise from it, long-press = Star / Archive / Restore). Practice: slim top bar (back to deck, position, star, archive, menu with copy + sort), thin progress line, the group chips, a card that fills the rest (tap to flip; question side catches taps before rich text fields), one-line rating pills (mark hidden under 330dp width), Previous / round flip button / Next. Swiping and Previous/Next wrap around. Archive shows a 4 s Undo.
- flashcardColor: integer-built pastels, unique for the first 11,340 cards (UNIQUE_FLASHCARD_SHADES), neighbours ~136 deg apart, every channel >= 180.
- FlashcardStore: SavedFlashcard.isArchived (optional JSON field, store version unchanged, old files read as not archived) + setArchived().
- Tests: FlashcardStudyScreenTest (start practice from deck, wrap-around, deck tile + archive + Archived group, card tap on a rich face), FlashcardStudyGroupingTest (archive filtering, starred sort, wrappedIndex, 10,000 distinct shades). APK releases\Virlin-flashcards-redesign-20261108-1700.apk.

## 2026-11-08 Flashcards deck page: gallery redesign
- FlashcardsRoute.kt deck page only (practice page untouched): serif title block, hero card in the project accent (palette.secondary, default 0xFF087848) with count, learned progress, Start practice (flashcard_start) and Shuffle (flashcard_shuffle), accent group chips with count badges, search field (flashcard_search_toggle / flashcard_search; matches question, answer, note title), masonry LazyVerticalStaggeredGrid of tall pastel tiles (serif front, status pill, star toggle flashcard_tile_star_<id>, No. NN, review count; long-press adds Copy question), stacked-cards empty state.
- FlashcardSort.SHUFFLED: order keyed on card id + seed so it holds still while cards are reviewed/archived; also in both sort menus. Undo text uses the accent.
- FlashcardsRoute resolves projectId for project/workstream/task and passes accent; FlashcardStudyScreen gains accent param (default).
- Tests: shuffledOrderHoldsStillWhenACardLeaves, searchMatchesQuestionAnswerAndNoteTitle, deckSearchStarAndShuffleWorkFromTheGallery; deck tests scroll the grid/chips. 20/20 pass. APK releases\Virlin-flashcard-gallery-20261108-1620.apk.

## 2026-11-08 - Flashcard practice deck rewrite (FlashcardsRoute.kt)
- Root cause of 'flip button / Hard-Good-Easy do nothing after the first card': Compose memoized ::localFunction references (flip, rate, settle, archive) with stale captures. All converted to lambda vals; ratings and flip now act on the card showing.
- Practice page rebuilt as a physical card deck: keyed layers (card id) with zIndex, depth scale/lift/tilt, spring fly-out/back, velocity-aware swipe, previous card always fully built so swipe-back never creates WebViews mid-gesture.
- Whole-card 3D flip (rotationY, face swap edge-on, no alpha) so equation/rich cards flip without GPU stalls.
- Ratings always enabled when a card is showing; feedback pill shows interval and destination group (e.g. Good - back in 1 day - moved to Future).
- Project accent (rememberProjectPalette secondary, DefaultDeckAccent fallback) on chips, progress, flip button, Next, sort menu.
- Source-note chip on card + 'Open source note' menu item navigate to notesRoute(ownerKey, noteTitle).
- Group/sort/index/practicing state survives process death (rememberSaveable).
- Test: flipButtonAndRatingsActOnTheCardNowShowing. 21/21 flashcard tests pass.
- APK: releases\Virlin-flashcard-deck-20261108-1715.apk

## 2026-11-08 - Flashcard practice: options strip, Edit, open note at the card's block
- Practice page three-dot button now toggles a horizontal, feathered options strip directly above the New/Due/Future chips (white rounded surface, bordered icon+label buttons, divider between groups, Close at right, Back closes it). Replaces the dropdown menu.
- Strip: Edit, Copy question, Copy answer (button shows 'Copied' briefly), Open in note | Sort: Scheduled, Weakest first, Newest first, Starred first, Shuffled (selected sort tinted with the project accent).
- Edit: FlashcardCreateDialog gains an edit mode (FlashcardEditStart / flashcardEditStart; older plain cards become one block per face). Passage picker and REVIEW AGAIN are hidden; saves via new FlashcardStore.update, which keeps schedule, reviews, star, archive, colour and source.
- Open in note lands on the card's own block: new optional SavedFlashcard.sourceBlockId (JSON field, store stays v1, older cards unaffected) recorded by NotesRoute when a card is made; older cards are matched by text (flashcardSourceBlockId). A block hidden in a collapsed toggle opens at the toggle. Works by pointing the note's existing NotePositionStore reading position at that block before navigating; the editor is unchanged.
- Tests: cardOptionsStripEditsOpensAndSortsTheCardShowing, sourceNoteOpensAtTheCardsOwnBlock, editStartKeepsRichFacesAndTurnsOlderCardsIntoBlocks, editingKeepsScheduleStarAndSourceBlock. 37/37 flashcard + notes-route tests pass.
- Verified on emulator-5556 (temp cards, restored afterwards): strip, edit+save, text-matched jump (block 245 of 352) and saved-block jump (heading at block 308).
- APK: releases\Virlin-flashcard-options-20261108-1950.apk

## 2026-11-08 - Flashcard practice: rating message removed
- Removed the dark pill shown after Again/Hard/Good/Easy ('Good - back in 1 day - moved to Future') with its state, RatingFeedback and ratingMessage. Ratings, scheduling and the move to the next card are unchanged; each button still shows its interval.
- Test updated to assert the pill no longer appears. 35/35 flashcard tests pass. Verified on emulator-5556 (card file backed up and restored).
- APK: releases\Virlin-flashcard-nomessage-20261108-1940.apk

## 2026-11-08 - Flashcard deck page: sort as a strip
- Deck page sort button (SwapVert) now toggles the same feathered options strip as the practice page, under the top bar: Scheduled, Weakest first, Newest first, Starred first, Shuffled, selected one tinted with the project accent; Close / Back / the button close it. Replaces the dropdown (SortMenu -> SortToggle + shared sortOptions; PracticeOptionsStrip gains tag/modifier params).
- Test: deckSortOpensAsAStripAndSelects. 36/36 flashcard tests pass. Verified on emulator-5556 (no data changed).
- APK: releases\Virlin-flashcard-sortstrip-20261108-1950.apk

## Flashcards - front card style switch (2026-11-08)
- FlashcardStore: optional per-card frontCardStyle flag (missing = on, so older cards read as on); add() and update() take it; store stays v1.
- FlashcardCreateDialog: 'Aa' switch in the FRONT header next to the edit pencil (tag flashcard_front_style, Role.Switch). On = front previews and practises in the deck serif (CardSerif, sized by length); off = the note's own formatting. Back side untouched.
- Fronts with code, equations, prompts, tables or media always keep note formatting; the switch rests (disabled) for them so nothing is lost.
- FlashcardsRoute: practice front uses CardStyleFront when on and the front fits; Edit carries the flag through. NotesRoute passes it on create.
- Tests: dialog toggle default/off + code-front rest; store default/round-trip. 41/41 flashcard + notes route tests pass. Verified on emulator-5556 (data restored, hash matched).

## Flashcards - per-branch card design room (2026-11-08)
- New data/flashcard/FlashcardDeckStyleStore.kt: FlashcardDeckStyle (design, colour mode, two colours, gradient direction, typeface, text size, alignment, corners, scattered, details) saved per branch in files/flashcard-deck-styles-v1.json keyed 'kind:id'. Default style = the original look; saving the default removes the entry. Unknown names fall back per setting. Cards file untouched.
- New ui/notes/FlashcardDeckDesign.kt: 14 card styles (Classic, Natural paper, Hand-drawn, Sticky note, Index card, Kraft, Watercolour, Dotted, Polaroid, Outline, Vintage, Bold, Chalkboard, Blueprint) drawn in one cached cardSurface modifier; colour modes Mixed/Fill/Gradient/Project with 18 swatches and 3 directions; 8 typefaces (Fraunces, DM Sans, and Caveat/Permanent Marker/Playfair/Abril Fatface/Nunito/Courier Prime via the existing notes Google Fonts provider with built-in fallbacks); bottom sheet with live preview and strips.
- FlashcardsRoute: Tune button (tag flashcard_style_button) after sort on the deck page; deck tiles, back-of-deck cards and practice cards read LocalDeckLook. Dark styles put the answer (and fronts that keep note formatting) on a light partner design so note content stays readable.
- Tests: FlashcardDeckStyleStoreTest (per-branch isolation, default removes entry, unknown values), sheet + practice UI test. 44/44 flashcard + notes route tests pass. Verified on emulator-5556; test style file removed and cards hash matched.

## Flashcards - Previous / Next answer every tap (2026-11-08)
- Cause: after a move the buttons stayed locked until the spring fully settled (~1.3 s), long after the card had visibly moved, so a quick second tap was dropped.
- Fix (FlashcardsRoute practice page): fly-out spring ends once the card is well off screen (visibilityThreshold 2% of deck width; previous 1.5 px so the swap has no jump), and a Previous/Next tap during a move is queued (one step) and runs as soon as the move settles. Cleared on group/sort change.
- Verified on emulator-5556 with 4 temporary cards: single tap 1->2, two taps 0.3 s apart 2->4, two quick previous 4->2, single previous 2->1. Cards file restored (hash matched). 42/42 flashcard tests pass.

## 2026-11-08 - Flashcard flip: no grey slab, smoother turn
- FlashcardsRoute.kt StudyCard: elevation shadow moved off the 3D-rotated layer (Android drew it as a large grey rectangle mid-flip) onto a flat sibling layer that narrows with |cos| of the angle, lifts slightly and softens. The rotating card keeps its clip and adds a light shade + moving sheen while turning. Face content, semantics and gestures unchanged.
- Build: releases\Virlin-flipshadow-20261108-2017.apk (emulator-5556 only). Flashcard tests 42/42.

## 2026-11-08 - Flashcard swipe: one swipe is enough, more physical motion
- FlashcardsRoute.kt FlashcardPracticePage: release decision now uses momentum projection (offset + velocity*0.18s vs 26% width) or a density-aware flick (420dp/s); flicking back against the drag cancels. A swipe made while a card is still moving is queued and runs right after (reuses queuedStep). Fly-out/slide-in springs faster (340/380), snap-back less bouncy. Top card lifts 3% when grabbed and pivots on the edge opposite the finger (top or bottom grip). No recomposition per frame; states read only in graphics layers.
- Test: FlashcardStudyScreenTest.aShortQuickFlickIsEnoughToMoveACard. Flashcard tests 43/43.
- Verified on emulator-5556 (releases\Virlin-swipe-20261108-2026.apk) with temporary deck copies; user card file restored byte-exact (SHA256 05489C6A...).

## 2026-11-08 - Practice options button: animated sliders glyph
- FlashcardsRoute.kt: the practice header MoreVert icon is replaced by OptionsGlyph, a Canvas-drawn three-slider glyph. Opening the options strip glides each knob to a new stop (staggered springs) and tints it to the deck accent with a soft rounded backdrop; closing reverses it. Same testTag (flashcard_options) and labels (Card options / Hide card options). Progress read only in the draw lambda.
- Flashcard tests 43/43. Verified on emulator-5556 (releases\Virlin-optionsicon-20261108-2032.apk).

## 2026-11-08 - Rating buttons: responsive tap animation
- FlashcardsRoute.kt: ReviewRatings buttons extracted to RatingButton. Press squeezes to 92%; release springs back with overshoot, a wash of the button colour spreads from the touch point, the outline brightens then fades, the mark nods; light haptic tick. No popup, no recomposition per frame (Animatables read in graphicsLayer/draw). Rest look, labels, semantics, testTags and rating logic unchanged.
- Flashcard tests 43/43. Verified on emulator-5556 (releases\Virlin-ratepop-20261108-2038.apk) with temporary deck copies; user card file restored byte-exact (SHA256 05489C6A...).

## 2026-11-08 - Notes multi-select: format all picked blocks
- NotesViewModel.kt: NotesUiState.blockSelection (never stored), set by NotesRoute from the multi-select. While non-empty, toolbar tools act on every picked block as one undo step: font, size (stepper moves each block from its own size; typed size sets one size), bold/italic/underline/strike, text colour, highlight, alignment, line spacing, paragraph style (Normal/Headings), bullets/numbering (+ list style strip, None), checklist, quote. Code keeps its own look; prompts/toggles/tables/media are skipped; type changes are proved against the rich document first (refused with a message, nothing changed). Toolbar shows shared state (e.g. Mixed styles). Empty selection = unchanged single-block behaviour. paragraphLabelFor extracted (same labels).
- NotesRoute.kt: LaunchedEffect passes the multi-select to the VM.
- Test: NotesMultiSelectFormattingTest (4). Notes suites 248/248. Verified on emulator-5556 (releases\Virlin-multiformat2-20261108-2122.apk); emulator DB backed up and restored byte-exact.

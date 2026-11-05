# Status update — 2026-11-05

The user requested reconstruction of the earlier Compose note editor through the single-line
equation fix. The notes2 UI and web bundles described below are archived, not active. See
[the restoration record](NOTE_EDITOR_RESTORE_2026-11-05.md). Historical rebuild results below
are not verification of the restored build.

# Android Notes: reliability roadmap (historical)

Status (2026-11-05, rebuild "notes2"): the rebuilt editor is the active Android Notes route and
passes its automated device suite. **Real source-app paste acceptance (ChatGPT and others) is
pending the user's manual test.** Do not label the feature complete until that test passes.

## Current architecture (rebuild "notes2")

Active route: `NOTES_ROUTE`, the Knowledge "new note" route, the WorkStream "new note" route and
Capture notes all call `ui/notes/rich/RichNotesRoute`.

```
Clipboard / keyboard / IME ──► RichNotesWebView (Android host, paste routing, debug capture)
                                  │  window.virlinNotes.pasteClipboard({html,text,images,types,…})
                                  ▼
                        notes-editor (Tiptap / ProseMirror, bundled to assets/notes-v2)
                          importer.js  HTML first → Markdown when the text is Markdown → literal text
                                  │  onDocument(json)
                                  ▼
RichNotesViewModel ── saveRichNoteDoc(owner, title, json, expectedRevision) ──► VirlinActions ──► Room
```

- **Stored format v2:** `{"v":2,"doc":<ProseMirror tree>,"blocks":<v1 projection>}`. The tree is
  authoritative. The `blocks` projection keeps every v1 consumer working (desktop editor, Master
  Document, flashcards, search, archive). Documents larger than 48,000 characters are split
  across `virlin_note_chunks` (schema v24, no row rewritten by the migration).
- **Old notes:** v1 notes are converted to a tree **in memory only** when opened. Nothing is written
  until the user edits. Literal Markdown left in old notes by earlier failed pastes stays literal.
  There is no automatic repair.
- **Damaged notes:** a note that can't be decoded opens a recovery panel. The panel can copy the
  raw source, save a recovery copy (`note-recovery-…`) or recover the readable text. The stored
  row is never overwritten unless the user chooses to recover it.
- **Stale revisions:** each save carries `expectedRevision`. If another writer (for example the
  desktop editor) saved first, the user gets a conflict dialog: "Load the newer version" keeps a
  recovery copy of their edits, and "Keep my edits" keeps a recovery copy of the stored version.
  Either way, both versions survive.
- **Autosave:** the editor reports a dirty state, and its document is drained to Room through
  one serial save queue. The editor flushes on pause, stop, close and dispose. If a save fails on
  close, the user gets a dialog instead of a silent loss, and the edits can be kept as a recovery
  copy.
- **Paste paths:** all of these reach the single JS importer:
  - the WebView paste event (selection toolbar)
  - hardware Ctrl+V or `KEYCODE_PASTE` (Ctrl+Shift+V pastes plain text)
  - IME `commitText` of clipboard text (keyboard clipboard chips)
  - IME `performContextMenuAction` paste / paste-as-plain-text
  - IME `commitContent` images

  Image clip items, `data:` URIs and `content://` images are copied into managed `note_images`.
  `http(s)` images in pasted HTML are downloaded in the background, accepted only if the server
  returns an image type.
- **Debug clipboard inspector:** in debug builds, every paste writes its raw clipboard payload to
  `files/paste-captures/<timestamp>-<source>.json` and logs a summary under logcat tag
  `VirlinNotesV2`. The payload includes the MIME types, HTML, text, image items and the importer's
  report. Use it when a manual paste looks wrong.

## Feature status

| Feature | Status |
| --- | --- |
| Paste: headings, bold/italic/underline/strike, highlight, links | Implemented; device-tested (HTML and Markdown) |
| Paste: bullets (nested), numbering (start value kept), checklists | Implemented; device-tested |
| Paste: tables (editable, add/remove rows/cols) | Implemented; device-tested paste and save |
| Paste: code (language kept), prompt/message blocks, each with Copy | Implemented; device-tested paste and save |
| Paste: inline and display math (KaTeX annotations, `\( \)`, `\[ \]`, `$$`) | Implemented; device-tested; source stays editable; display math scrolls horizontally |
| Paste: images when the clipboard provides them | Implemented; device-tested with a `data:` image |
| Plain text without Markdown | Stays literal; no invented structure (device-tested) |
| Ambiguous content | Kept as text; Paragraph picker reclassifies selected blocks as code/prompt/message/equation/heading |
| Toolbar: undo/redo, styles, font, size, colour, highlight, alignment, spacing, lists, indent, quote, link, table, divider, image, fold | Wired to editor commands; manual check pending |
| "Add to note" sheet: blocks, equations (inline/display), image picker | Wired; manual check pending |
| Heading fold: individual and by level, persisted in the document | Implemented; JS regression test plus device test |
| Cross-block selection and copy | Browser-native selection inside one document |
| Flashcards from a selection | Implemented on the route (front/back, difficulty and timing via the existing dialog); device-tested |
| Reading view | Full-screen read-only view; device-tested open/close |
| Export | Markdown share sheet |
| Very large notes | No app caps; 1,500 paragraphs + 800 list items saved in chunks and reopened whole (device-tested) |

## Consumers updated

- **Project duplication:** rich notes get new node ids and unchecked checklists. The content is
  kept.
- **Project archive export:** writes the full v2 document. A damaged note is exported verbatim as
  `damagedRaw`.
- **Backup zip:** adds `media/note-images/<name>` with SHA-256 manifest entries. Missing images are
  listed, never silently dropped.
- **Master Document:** reads the projection. Damaged notes show "This note could not be read.
  Open it to recover it."
- **Desktop protocol:** stale or rejected note saves now return the rejection reason.
- **Capture dual-write and flashcard files:** unchanged. `CaptureNoteBridge` and `FlashcardStore`
  are reused as-is.

## Test results (2026-11-05)

- **Web editor (`notes-editor`, `npm test`):** 28/28 pass. Covers the importer, serializer, ids,
  fold, commands and Kotlin fixture compatibility.
- **Android device suite `RichNotesRouteDeviceTest`:** 8/8 pass on the separate test AVD
  `Virlin_Note_Test_20261105` (`emulator-5556`). It uses the real route with a file-backed Room
  database.
  - **HTML paste → edit → save → database restart → reopen:**
    - Observed node types in the editor and in the stored tree: `heading`, `mark:bold`,
      `mark:italic`, `mark:highlight`, `mark:link`, `bulletList`, `orderedList`, `taskList`,
      `table`, `codeBlock` (`"language":"python"`), `promptBlock`, `mathInline` (`x^2`),
      `mathBlock` (`\int_0^1`), `blockquote`, `image`.
    - Saved projection: `HEADING_2` "Study plan (edited)", `TABLE` cell(1,0) = "NPV", a math
      `CODE` block, `PROMPT`, and bold runs.
    - The image file exists in `files/note_images`. No literal `**` or `## ` survived.
    - The reopened editor tree equals the stored tree, with save state "No changes".
  - **Markdown via hardware paste, IME clipboard chip and IME paste menu:** produces the same
    structures. `**important**` doesn't survive as literal text.
  - **Other cases:**
    - Plain text stays literal.
    - A legacy note with literal Markdown opens unchanged and isn't rewritten.
    - A damaged note shows recovery and is never overwritten.
    - A stale revision raises the conflict dialog, and "Keep my edits" keeps both versions.
    - Reading view, flashcard and heading fold work on the route.
    - A very large note is chunked (more than one chunk row) and reopens whole.
- **JVM:**
  - Pass: the `domain.notedoc.rich.*` tests (adapter, fixtures, actions, consumers),
    `ProjectDuplicatorTest`, the project archive tests and the Master Document tests.
  - 17 failures remain outside the active Notes path:
    - `AttentionExitTest`, `ExternalTimingTest`, `MasterResumeRoomTest` (timing values)
    - `BulkNamesTest`
    - `PopupViewportUiTest`, `ProjectAppearanceUiTest` (dialog UI)
    - `ProjectKnowledgeAddUiTest` and `NotePasteUiTest`, which drive the **old** `NotesRoute`
      directly

    None of these tests use the rebuilt files. They aren't proven pre-existing because the dirty
    tree has no clean baseline. `InlineLeaveUiTest` still stalls the full suite, so the full JVM
    suite has not been shown green.
- **Migration and old data:**
  - A copy of the user's v23 database was restored onto the test emulator only. It migrated to
    v24.
  - Both legacy notes, "Task Creation" (352 blocks) and "Natural Language" (37 blocks), stayed
    byte-for-byte unchanged (revisions 7 and 9, format v1).
  - The app launched normally.

## Build identity

- Debug `versionName` **`1.0.9-notes2-20261105`** (`versionNameSuffix` in `app/build.gradle.kts`).
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 28,598,713 bytes, SHA-256
  `fb444d0fc4b8194a36b9d3832a51c8604a4f6278e3182abc0fbcf32f92f503f6`.
- Installed on `emulator-5556` only. The installed `base.apk` SHA-256 matches. The user's
  `emulator-5554` was not touched.

## Limitations and pending items

- **Pending (user manual test):** real copy from ChatGPT and similar apps into the active route.
  Each of these clipboard cases is unverified until then:
  - whole-response copy button
  - code-block Copy
  - prompt/message copy
  - copying a rendered equation
  - long-press → Paste
  - keyboard clipboard chip
  - Paste as plain text
  - hardware Ctrl+V

  The debug inspector records each case's raw payload for diagnosis.
- **Fidelity depends on the clipboard:** when a source app puts only plain text on the clipboard,
  structure is recovered only if the text is Markdown. Anything else is kept literal, never
  guessed. Equations copied as rendered glyphs without a TeX annotation stay as text. Images
  appear only if the clipboard carries them; remote image URLs need network access.
- **Old notes with literal Markdown:** not repaired. Re-paste the content or reformat by hand. A
  previewable, undoable repair flow remains future work (phase 4 below).
- **Cross-block selection:** limited to one note document. Copy produces the browser's HTML/text
  of the selection.
- Requires Android System WebView. The editor works offline; only remote image download needs the
  network.

Cleanup candidates (nothing removed) are listed separately in
[`NOTE_CLEANUP_INVENTORY.md`](NOTE_CLEANUP_INVENTORY.md).

## History: interim editor checkpoint (superseded by "notes2")

- A Tiptap/ProseMirror WebView editor is now the active Android Notes route. It imports semantic clipboard HTML first and Markdown when the clipboard contains Markdown. The Compose toolbar, add-block sheet, formatting dialogs, fold controls, managed images, and flashcard action are wired to this editor; the old per-block editor remains in source for compatibility tests but is not the production edit surface.
- Browser tests cover mixed headings/bold/lists/tables/code/prompt/math, KaTeX clipboard annotations, sanitization, large imports, and legacy document conversion. Connected Android tests cover the active route, native clipboard paste of mixed semantic HTML, cross-block selection, undo/redo, folding, managed images, and a 2,100-block edit. They do not establish real ChatGPT/Claude clipboard compatibility yet.
- Database v24 adds `virlin_note_chunks` without rewriting older note rows. A >2,000-block note round-trips through Room, and a separate-emulator migration test confirms a v23 note row survives unchanged. This removes an app-imposed storage cap, not physical memory or SQLite limits.
- The user's existing emulator database was copied with its WAL/SHM files and integrity-checked before device tests. New app/instrumentation tests run on a separate `Virlin_Note_Test_20261105` AVD.
- Remaining release gates: real source-app paste/save/reopen acceptance, full toolbar interaction coverage, selection/viewport preservation under long editing, old raw-Markdown repair with preview/undo, and full application regression suite. An older Compose editor viewport test currently fails after the route change and needs triage before full-suite signoff.

## Required behavior

1. A single paste into the note editor preserves the recognizable structure of the clipboard: headings, subheadings, lists, tables, inline marks, links, code, prompts, equations, and resolvable images.
2. Editing, selection, undo/redo, folding, flashcard creation, save, and reopen retain the same content and formatting without moving the viewport unexpectedly.
3. Unsupported clipboard content must be preserved as text or reported explicitly; it must never silently turn a structured paste into one giant paragraph.
4. Existing saved notes must not be rewritten destructively by a parser update. Any repair of old notes needs a preview and undo or backup.

## Audit findings and fixes

- The Android editor routes text changes through `NoteBlockEditor` to `NotesViewModel.pasteStructured`; when that returns false, `onTextChanged` treats the insertion as typing. A saved 13 KB user note demonstrated this fallback: 481 newlines and dozens of literal Markdown markers were stored in just two paragraphs.
- Rich clipboard HTML can exceed the import size limit even when the visible text is well within it. The view model now retries the inserted plain text as structured Markdown before giving up.
- Styled HTML paragraphs containing literal `**bold**` were considered rich content, so the Markdown markers stayed visible. The importer now detects that case and uses the Markdown source, while retaining semantic HTML and imported images where available.
- **Primary device root cause:** `NoteMath` initialized regex patterns that the desktop JVM accepted but Android ICU rejected with `PatternSyntaxException`. The paste parser called `NoteMath` on ordinary paragraphs, so its first device use threw `ExceptionInInitializerError`; subsequent attempts threw `NoClassDefFoundError`. Both rich and plain import attempts failed and the editor silently saved raw text. This was confirmed in Android logcat and reproduced in a connected Android test, then fixed by replacing the incompatible regex patterns. The connected test now passes.
- A fresh emulator paste was approximately 26 KB with 1,147 line breaks, 352 blank-line groups, 376 bullet rows, and 58 headings. The previous 500-block ceiling was a second independent obstacle for a response of this shape. The bounded ceiling is raised to 2,048 blocks and 128 KiB of note text, with long-response JVM and connected Android regressions. This does not imply arbitrary-size pastes are supported.
- Legacy paragraphs containing line breaks were split again on any later edit because Enter detection checked for a newline anywhere in the whole field. It now splits only on a newly inserted single newline.
- If a structured paste still exceeds the formatter's limits, the editor now keeps the text and reports that formatting was not imported rather than silently presenting it as success.

## Verification gates

| Area | Current automated coverage | Remaining release gate |
| --- | --- | --- |
| Markdown and HTML import | Parser tests for headings, inline marks, tables, code, prompts, math, images, oversized HTML | Device paste from the actual source apps and clipboard variants |
| Editor insertion | Compose editor paste tests for plain, semantic HTML, styled Markdown, stale clipboard | Test real Android paste action, IME composition, and large responses on device |
| Persistence | Mixed-content paste, save, and reopen regression; editor save tests | Long-note reopen and process restart on device |
| Editing and navigation | Unit tests for formatting, deletion, undo, folding, selection, viewport | Manual accessibility and long-document interaction pass |
| Flashcards | Selection and creation tests | Verify multi-block selection to front/back with real device gestures |

Release gate: run the focused note suite and full app suite, build the APK, then perform device paste/reopen checks using representative ChatGPT/Claude/Cursor content. Record actual source, clipboard MIME types, input length, expected block types, observed block types, and screenshots. Do not mark complete when any item above remains unverified or failing.

## Verification record: 2026-11-04

- Focused Android note/flashcard unit suite: **132 tests in 15 suites, 0 failures, 0 errors**. Includes a mixed Markdown paste → save → reopen regression covering heading, bold, bullets, code, prompt, and equation.
- Debug APK: assembled successfully and installed on the emulator; installed/base APK SHA-256 matched the local APK after the final build.
- Full application unit suite: **not complete**. It stalled in `InlineLeaveUiTest.customStaysInLeaveDialogAndSavesExactMinutes`, outside the note feature. Do not report this as a full-suite pass.
- Connected Android emulator editor/clipboard regression: **4 tests passing** for a >500-block paste; a mixed heading/bold/bullet/code/prompt/equation paste; styled HTML wrapping raw Markdown; and Android's actual paste key command. These tests use the Android text field and system clipboard, but do not yet prove cross-app copying from ChatGPT/Claude/Cursor.
- Actual cross-app clipboard behavior from ChatGPT/Claude/Cursor: **not yet verified** against the user's latest sample. This remains a release gate.
- Root-cause evidence from emulator after the last APK install: `task-t_nl`, `task-t_wsc`, and `task-t_prompt` all held the same long response as two unformatted paragraphs, with 76 literal `**` markers and zero formatting runs. The first was saved at 19:21; the later two were saved at 20:09 and 20:12 local time after the updated APK was installed. Thus the old APK was not the cause.
- Old notes containing literal Markdown markers: **not migrated**. Reformatting them requires a separate previewable, reversible repair flow.

## 2026-11-05 follow-up

- Fixed another paste decision gap: when Android supplies semantic HTML but its plain-text clipboard representation differs from the text inserted by the IME, the editor now accepts the HTML only if its visible text matches the insertion. Bold Markdown is also recognized in the mismatch path. Regression tests pass.
- Removed the remaining 100-row/20-column table import truncation; a 151-row/25-column table test passes. This removes app-imposed table truncation, not finite device memory or SQLite limits.
- The parser no longer imposes its former block/character ceilings. Very large-document editing and persistence still need device-scale testing and eventually paged/chunked storage; a single JSON column is not an unlimited-storage guarantee.
- `assembleDebug` succeeds, but this build has not been installed on the user's phone or emulator in this follow-up. Do not claim the screenshots are fixed until an actual cross-app paste and save/reopen are verified on the new build.

## Phased completion plan

1. **Import safety (in progress):** accept realistic long responses within explicit memory limits; test each source format and ensure failures are visible. Verify imported block counts and marks, not just text presence.
2. **Editor integrity:** test edits, deletion, undo/redo, folding, selection, viewport position, flashcard creation, and save/reopen on long mixed notes. Remove any path that interprets old line breaks as fresh Enter input.
3. **Device acceptance:** install the built APK; paste representative content from ChatGPT and other source apps into a new note; inspect saved block types, marks, code/prompt/math, and images; close/reopen and repeat. Capture clipboard MIME metadata and editor callback sizes if any mismatch remains.
4. **Legacy repair:** provide a previewable, undoable conversion for existing raw-Markdown notes only after the new import path passes device acceptance. Never rewrite saved notes automatically.

Phase 1 verification completed for plain Markdown, styled HTML carrying Markdown, the Android paste key, and long mixed responses. Cross-app source-specific clipboard variants remain in phase 3; the roadmap intentionally does not mark the entire Notes feature complete yet.

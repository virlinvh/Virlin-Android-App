# Note editor reconstruction — 2026-11-05

The user requested the earlier note editor through the single-line equation-layout fix,
including the approved selection-first flashcard composer, and asked to undo the later
editor replacements (including the Cursor notes2 rebuild).

This is a reconstruction, not a byte-for-byte checkout: that point was never committed.
Unrelated working-tree changes have not been reset.

## Restored runtime

- All task/project/global, Knowledge, workstream and Capture note routes use `NotesRoute`.
- `NotesRoute` uses the original Compose `NoteBlockEditor`, ViewModel tool actions,
  paragraph/font/size/colour/highlight/link/table pickers and the original add-block picker.
- Cross-block selection feeds the selection-first Front/Back flashcard composer, preserving
  the passage's available styles. Both flashcard sides can be edited independently.
- Heading folding, note viewport state, clipboard import, images and reading view remain.
- Equations use offline KaTeX, keep adjacent fragments on one horizontally scrollable row,
  and show editable raw source only when requested. Explicit multi-row TeX remains multi-row.
- Explicit LaTeX fences are recognised; programming-language code remains code.

## Archived, not deleted

Before edits, `app/src`, the app Gradle script and the later rebuild documentation were copied
to `.tmp/note-editor-rollback-20261105/`. Later web editor sources, their bundles, JS projects,
replacement add sheet and editor-specific tests were moved into its `archived/` subtree.
They are no longer compiled or packaged. The webkit dependency was removed; KaTeX assets remain.
The backup is local and ignored by Git.

## Deliberately retained for data safety

Room schema/migrations, rich document codecs, adapters and domain save safeguards remain.
Documents already saved by notes2 may require these. Opening notes does not downgrade or
rewrite them. Block edits to rich documents are merged by the adapter or rejected if their
structure cannot safely be represented. Removing the UI does not remove existing note data.
Existing safety fixes such as Android-compatible math recognition are retained.

No application data, database, recovery copy or installed application was changed by this
reconstruction. Newer-only rich structures are not guaranteed to have the old UI's full
editing fidelity; incompatible edits are refused instead of flattening the rich document.

## Build and verification

Debug version: `1.0.9-restored-notes-20261105` (versionCode remains 10).
APK: `app/build/outputs/apk/debug/app-debug.apk`.

Focused verification: 130 passed, 0 failed, 3 skipped (fixture-dependent compatibility
tests). Debug assembly and Android instrumentation-source compilation passed. Verification
covers note paste/import, original editor actions, Knowledge note
creation/persistence, cross-block selection, flashcard composition/storage/study, equation
source visibility and rich-format data compatibility. Android instrumentation sources also
compile. APK inspection confirms the archived web bundles are absent and KaTeX assets remain.
The old viewport test was corrected to scroll the lazy list to an off-screen item
before querying that item's node; its viewport assertion was not relaxed.

A real ChatGPT clipboard paste and visual equation-scroll test have not been repeated on a
physical phone. The full unrelated test suite has not been claimed green.

## Stability pass (same day, user-approved)

No app size limit. Nothing about stored notes changed (no schema, codec or migration edit).

- **Large pastes:** insertions over 20,000 characters (or with clipboard HTML over 200,000
  characters) are parsed off the main thread. A "Formatting paste…" pill shows after 250 ms;
  text fields are read-only meanwhile. Close/Back wait for it before saving. If formatting
  fails, all text is kept as plain paragraphs split at blank lines, and a message says so.
  Paragraphs over 8,000 characters are divided only at their own line breaks. A single line
  is never cut. Edits made while formatting are never merged into: the paste lands after them.
- **Typing with a big clipboard:** a one-character edit no longer reads the clipboard, and
  HTML far larger than the insertion is not parsed.
- **Code blocks and equations take pastes literally:** `# comment` stays code.
- **Parser:** Markdown links in the middle of a paragraph were dropped; fixed. Bounded link
  matching makes a 116k-character line full of `[` 0.2 s instead of 5.5 s.
- **Copy:** code, equation (copies LaTeX) and prompt blocks have Copy in their header. A
  second Copy appears at the bottom when the block is taller than the screen. If the system
  refuses (text beyond the clipboard limit), it says "Too large to copy".
- **Syntax colours (display only):** `ui/notes/CodeHighlighter.kt` guesses the language
  (Python, JS/TS, Kotlin, Java, SQL, Bash, JSON, HTML, CSS, C-like) and shows it in the block
  header. Prose and unknown text stay uncoloured; blocks over 60,000 characters stay
  uncoloured. Colours the user set on runs win over syntax colours.
- **Save safety:**
  - Saves pass the loaded revision. If storage is newer (desktop edit), the newer version is
    never overwritten; a dialog offers "Keep both: save mine as a new note" (Knowledge note in
    the same project), Keep editing, or Discard mine.
  - A newer write identical to the editor's content, or one that changed nothing the editor
    was based on, is not treated as a conflict.
  - Any refused save (rich structure, damaged, failure) shows its reason once, and again on
    Close/Back, instead of the page silently refusing to close.
  - A damaged note shows a recovery panel (recover readable text via `recoverDamagedNote`,
    copy original data) instead of a blank page; the toolbar is disabled meanwhile.

Verification: 22 new JVM tests (1M-character AI-style paste, 250k single line, edit
race, conflict/keep-both, damaged recovery, highlighter fuzz). Unit suite in the PR's scope:
1,534 run, the same 15 unrelated failures as before, 3 skipped. Debug APK and instrumentation
sources build; the APK launched on the test emulator only.

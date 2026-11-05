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

# Superseded by the requested editor reconstruction

The earlier Compose editor is active again. Do not remove the files listed below as
"superseded" on the basis of this historical inventory. See
[the restoration record](NOTE_EDITOR_RESTORE_2026-11-05.md) for the archive and retained
data-compatibility boundaries.

# Notes cleanup inventory (historical)

Prepared 2026-11-05 after the "notes2" rebuild (`ui/notes/rich/` + `notes-editor/`). These items are
**candidates only**. Nothing listed here has been deleted, and none of it should be removed until
the user has accepted the rebuilt editor on a real source-app paste test.

## Superseded, safe to remove after acceptance

| Item | Why it's a candidate | Before removing |
| --- | --- | --- |
| `ui/notes/NotesRoute.kt` (`NotesRoute`, `NotesReaderRoute`, `NOTES_READER_ROUTE`) | No production navigation reaches the old editor. `VirlinApp` still registers `NOTES_READER_ROUTE` and references it for window insets. | Move `PickerDialog` / `PickerRow` (now `internal`) and the `NoteTool` mapping the new route uses. Drop the `NOTES_READER_ROUTE` composable and the two inset checks in `VirlinApp`. |
| `ui/notes/NotesViewModel.kt` (editing logic) | Only the old route edits through it. | Move `FONTS`, `SIZES`, `TEXT_COLORS`, `HIGHLIGHTS`, `SPACINGS` (the new route reads them) into a small constants file first. |
| `ui/notes/NoteBlockEditor.kt` | Old per-block Compose editor | Used only by the old route |
| `ui/notes/NoteRichWebEditor.kt` + `app/src/main/assets/note-editor/` + `note-editor-web/` | Interim WebView editor from the earlier pass | Used only by the old route |
| `ui/notes/NoteSelectionProjection.kt` | No main-source references remain | Its JVM test goes with it |
| `ui/notes/NoteDocumentLayout.kt`, `NoteEquationView.kt`, `NoteImageBlock.kt` | Used only by the old editor | — |
| `domain/notedoc/NotePasteParser.kt` + the `org.jsoup:jsoup` dependency | The paste parser of the old view model. HTML/Markdown import now happens in `notes-editor/src/importer.js`. The only jsoup users are `NotePasteParser` and `NotesViewModel`. | Remove both users first |
| `app/src/main/assets/katex/` | Only `NoteEquationView` (old editor) loads it. The new editor bundles KaTeX from npm into `assets/notes-v2`. | Remove together with `NoteEquationView` |
| `ui/note/NotePdfExporter.kt` + `domain/note/NotePdfModel.kt` | No callers outside themselves | Confirm with the user that PDF export from notes isn't wanted |

## Old tests that target the superseded editor

`ui/notes/NotePasteUiTest`, `ui/notes/NoteDocumentLayoutTest`, `ui/notes/NoteSelectionProjectionTest`,
`hierarchy/ProjectKnowledgeAddUiTest` (old-route case), androidTest `NoteRichRouteDeviceTest`,
`NoteRichWebEditorDeviceTest`, `NoteLongPasteDeviceTest`. `RichNotesRouteDeviceTest` and the
`notes-editor` web tests replace them. `NotePasteUiTest` and `ProjectKnowledgeAddUiTest`
currently fail against the old route.

## Keep: not cleanup candidates

- `domain/note/*`, `domain/notedoc/VirlinNoteDoc.kt`, `VirlinNoteCodec.kt`, `NoteMath.kt`
  (used by `RichNoteAdapter`), `CaptureNoteBridge.kt`, `FlashcardComposer.kt`,
  `FlashcardSelection.kt`, `domain/notedoc/rich/*`
- Room `virlin_notes`, `virlin_note_chunks`, every migration (including 23→24) and
  `VirlinMigrationTest`
- `data/noteimage/NoteImageStore`, managed `note_images`, `data/flashcard/FlashcardStore` and
  flashcard files
- `ui/notes/VirlinNotesScreen.kt`, `NotesPalette.kt`, `NoteAddSheet.kt`,
  `FlashcardCreateDialog.kt`, `FlashcardsRoute.kt`, `NoteRichSelection.kt`
  (`richSelectionForFlashcard`), `CaptureNotesRoute.kt`
- `assets/notes-v2/` (built bundle of `notes-editor/`), `assets/licenses/`, `androidx.webkit`
  (the new editor depends on them)
- The `v1` block projection inside stored v2 documents (desktop editor, Master Document,
  archive and search read it)

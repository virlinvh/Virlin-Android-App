# PDF workspace integration wiring request

Owner branch: `codex/pdf-workspace`

Apply only after the Audio branch is integrated and shared-file conflicts are resolved deliberately.

## Required shared edits

1. `VirlinApp.kt`
   - Register `pdf_workspace/new/task/{taskId}`.
   - Register `pdf_workspace/capture/{captureId}`.
   - Render `PdfWorkspaceScreen` with decoded stable IDs.
2. `MapAddPalette.kt`
   - Mark only `MapAddKind.PDF` supported after both routes compile and tests pass.
3. `ProjectMapScreen.kt`
   - Route `MapAddKind.PDF` to `pdfWorkspaceForTask(selectedTask.id)`.
4. `TaskPageScreen.kt`
   - Do not add a second PDF type key.
   - `capture.file` remains the Page type.
   - Reuse the Audio lane's canonical File viewer/open correction if already present.
   - If product direction chooses the dedicated PDF workspace for PDF-kind rows, row projection must
     know the attachment kind before routing. Do not infer PDF from a filename.

## Collision rule

Audio currently owns active edits to navigation, palette, map routing and the Page `openBlock` seam.
Do not copy either branch's shared files wholesale. Merge Audio first, then apply these small PDF
branches manually with focused route tests.

## Acceptance

- PDF tile opens the task-scoped workspace.
- Saving the original creates one `capture.file` Page block.
- Extracted PDF and PNG outputs appear as additional File blocks.
- Existing generic File captures still open normally.
- Non-PDF files cannot enter the dedicated PDF route.

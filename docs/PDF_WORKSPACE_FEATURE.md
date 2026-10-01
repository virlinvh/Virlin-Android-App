# PDF workspace feature contract

Status: implemented and wired on the combined Attachment/PDF/Audio baseline.

## Product meaning

The mind-map **PDF** item is a task-owned specialization of the existing generic File capture. A
PDF is stored once as `CaptureType.FILE` plus `AttachmentDocument(kind = PDF)`. The workspace must
never create a parallel PDF entity or duplicate the imported original merely to show a dedicated UI.

The task Page continues to reference the capture using `capture.file` and its capture ID. Derived
PDFs and images are independent canonical attachments because they are new user-created outputs.

## Ownership and persistence

- New task route receives the task ID explicitly and constructs `CaptureContext(taskId = taskId)`.
- Imported bytes use `AttachmentFileStore` under `filesDir/attachments/{attachmentId}/original`.
- Saving calls `VirlinActions.createAttachment`; Capture Actions atomically register the Page block.
- Existing PDFs open by capture ID and hydrate ownership from the persisted capture.
- Room remains v17. No table or migration is introduced.
- The original PDF is never modified. Crop, extraction and conversion operate on derived outputs.

## Routes

- `pdf_workspace/new/task/{taskId}`: a new task-owned PDF.
- `pdf_workspace/capture/{captureId}`: an existing PDF capture.

Route arguments are stable IDs. Titles and map positions are never identifiers.

## Implemented module

`ui/pdf` currently provides:

- strict SAF PDF import and validation with partial-import cleanup;
- the non-wizard dashboard approved in the redesign;
- a mobile-first viewer with an on-demand, vertically scrolling page overlay;
- preview visibility control;
- page range parsing and individual page selection;
- normalized, non-destructive crop metadata;
- native page rendering via `PdfRenderer`;
- selected/cropped page export as a new rasterized PDF;
- selected/cropped page conversion to individual PNG attachments;
- Page-owned output creation through existing Attachment actions;
- explicit capability-gated Annotation and OCR surfaces.

Native Android APIs rasterize derived PDFs. The visual page is preserved, but selectable source text,
forms, vector objects, links and original PDF object structure are not. The UI and documentation must
say `Rasterized PDF`; it must not imply lossless structural page copying.

## Capability gates

### Annotation

The viewer layout and tools are present, but mutation remains gated. Durable annotations require an
approved sidecar format with stable page coordinates, tool/color data, compatibility versioning,
export flattening and deletion/duplication rules. Buttons must not claim an annotation was saved until
that contract exists. The imported original remains immutable.

### OCR

The OCR review surface reserves whole-page/area selection, extracted text, Copy and Save-as-Note.
Recognition remains disabled until integration approves a bundled offline OCR dependency and defines
language packs, memory limits, cancellation and confidence/error behavior. No network OCR is allowed.

## Export behavior

- Empty selection means all pages.
- PDF export creates one `AttachmentKind.PDF` capture.
- Image export creates one `AttachmentKind.IMAGE` capture per selected page.
- Each created capture retains the source task context and therefore receives a Page block.
- Crop applies only to outputs and can target one page or all selected pages.
- Partial output files are removed if their Attachment action fails.

## Shared wiring boundary

This feature branch does not edit integration-owned `VirlinApp.kt`, `MapAddPalette.kt`,
`ProjectMapScreen.kt` or `TaskPageScreen.kt`. Integration follows
`docs/handoffs/PDF_WORKSPACE_WIRING_REQUEST.md` after Audio lands. In particular, it reuses the
Audio lane's verified `capture.file` Page-open correction rather than editing that seam twice.

## Testing expectations

- page-range parsing, invalid ranges and crop normalization;
- PDF validation, import cleanup and existing-capture compatibility;
- explicit task context and exactly one Page block for original save;
- derived PDF and PNG Page registration;
- missing/corrupt source behavior and export cleanup;
- renderer lifecycle and large-document memory behavior on device;
- no new Roborazzi difference beyond the known review gate.

Connected file tests must run against disposable test data. They must never clear the user's app.

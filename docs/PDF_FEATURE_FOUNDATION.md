# PDF feature foundation

Date: 2026-09-30

## Current change

The mind-map task Add palette's former **Topic link** placeholder is now **PDF** from its UI intent
root:

- `MapAddKind.TOPIC_LINK` was renamed to `MapAddKind.PDF`.
- Its visible label is `PDF`.
- Its hierarchy/network icon was replaced by Material's outlined PDF document icon.
- Its position remains the left item of the third palette row, opposite Attachment.

This is currently a disabled **Not yet** placeholder. Clicking behavior, storage, import, editing,
Page integration and map rendering were intentionally not introduced in this change.

## Isolated implementation update

The implementation contract and isolated module now live in `PDF_WORKSPACE_FEATURE.md` on the PDF
feature branch. It specializes `CaptureType.FILE + AttachmentKind.PDF`, keeps Room at v17 and leaves
the shared palette/navigation/Page wiring disabled until integration. The capability gates for
durable annotation and offline OCR are explicit; they must not be bypassed with UI-only success.

## Persistence boundary

`MapAddKind` is a UI intent enum and is not persisted. Renaming the enum therefore requires no
database migration and cannot strand existing user data. Existing generic Attachment support for
`AttachmentKind.PDF` is a separate feature and was not repurposed or changed.

## Rules for the future PDF feature

1. Keep `MapAddKind.PDF` as the palette entry; do not restore Topic link.
2. Design the PDF import/edit workflow before enabling the tile.
3. Route writes through the action/repository layer, never directly from Compose UI.
4. Define ownership and Page-block registration before attaching PDFs to tasks.
5. Reuse existing PDF/attachment primitives only after deciding whether a PDF is a specialized
   attachment or its own document contract; do not create duplicate records accidentally.
6. Until storage and navigation are real, `MapAddKind.PDF.isSupported` must remain false and the
   tile must continue to show **Not yet**.

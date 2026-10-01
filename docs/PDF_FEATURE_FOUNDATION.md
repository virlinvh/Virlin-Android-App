# PDF feature foundation

Date: 2026-09-30

## Current change

The mind-map task Add palette's former **Topic link** placeholder is now **PDF** from its UI intent
root:

- `MapAddKind.TOPIC_LINK` was renamed to `MapAddKind.PDF`.
- Its visible label is `PDF`.
- Its hierarchy/network icon was replaced by Material's outlined PDF document icon.
- Its position remains the left item of the third palette row, opposite Attachment.

This section records the original rename-only foundation. The later implemented and integrated
behavior is authoritative in `PDF_WORKSPACE_FEATURE.md`: PDF is now enabled in the palette and opens
the task-scoped workspace.

## Isolated implementation update

The implementation contract lives in `PDF_WORKSPACE_FEATURE.md`. It specializes
`CaptureType.FILE + AttachmentKind.PDF`, keeps Room at v17, and is wired into the palette,
navigation, and kind-based Task Page routing. The capability gates for durable annotation and
offline OCR remain explicit; they must not be bypassed with UI-only success.

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
6. The enabled tile must continue routing by stable task/capture IDs and persisted attachment kind.

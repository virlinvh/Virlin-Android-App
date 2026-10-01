# Image workspace

Status: implemented on `codex/image-workspace` from Attachment baseline `fe7eb90`.

## Purpose and ownership

The Image tile is a task-scoped library and non-destructive raster editor. It is not a new domain
entity. Every original and edited output is an existing `CaptureItem(FILE)` with an
`AttachmentDocument(kind = IMAGE)` under `filesDir/attachments/`. Task ownership therefore uses
the same Capture transaction and automatic `capture.file` Page registration as Attachment, PDF and
PDF-to-image output. Room remains v17.

Routes are ID based:

- `image_workspace/new/task/{taskId}` lists/imports images for one explicit task.
- `image_workspace/capture/{captureId}` opens an existing image capture in the editor.

Page routing uses persisted `AttachmentKind.IMAGE`, never a filename or title.

## Import and library

The workspace uses Android `OpenMultipleDocuments` restricted to `image/*`. Each selection is
copied independently through `AttachmentFileStore.importFromUri`; only files resolved as IMAGE and
with non-zero bytes are committed. Partial failures delete their private attachment directory and
do not discard successful siblings. The library is a projection over the task's existing FILE
captures filtered by attachment kind.

## Edit contract

An edit session exists only in ViewModel memory until **Save copy**. Back or process death cannot
modify the original. The current session supports:

- brightness, contrast, saturation and warmth;
- Original, Vivid, Warm and Mono presets;
- normalized crop;
- 90-degree rotation, horizontal/vertical flip and +/-15 degree straighten;
- pen, translucent highlighter, rectangle, arrow and text markup;
- colour, stroke width and opacity;
- undo, redo, clear/reset;
- light-theme editor surfaces matching the approved mockup.

`ImageRenderEngine` performs bounded decoding (maximum edge 4096), applies crop and transform,
renders colour adjustments, then rasterizes markup. **Save copy** writes a new PNG in a new managed
attachment directory and calls the canonical `VirlinActions.createAttachment` transaction with the
source capture's explicit context. The source bytes and metadata are never changed.

## Safety and limitations

- No cloud, network, generative editing, object removal, face processing or executable content.
- No sidecar is persisted; the saved PNG is the durable edited result.
- Large inputs are sampled before editing, so an edited copy may have a maximum edge of 4096.
- HEIC availability follows the device Bitmap decoder. Unsupported/corrupt images fail safely.
- The eraser currently removes the most recent markup operation through the same undo history; it
  is not a pixel brush or arbitrary-stroke hit tester.
- Markup has one logical layer in v1. Flattening happens only in the new saved copy.
- Straightening rotates the raster and may expand its bounds; perspective correction is out of
  scope.

## Extension rule

Future editing tools must extend `ImageEditSnapshot` and `ImageRenderEngine`; they must not add a
second image persistence model or overwrite managed originals. Any editable sidecar proposal needs
a separately reviewed lifecycle/storage contract before a schema migration.

## Verification contract

Tests cover routes, normalized crop, bounded adjustment/transform values and markup preservation.
Verified on 2026-10-01:

- all 1,098 JVM tests passed in the ordinary suite;
- Android-test Kotlin compilation and debug APK assembly passed;
- both on-device `ImageRenderEngineTest` cases passed on the Pixel 8 emulator, covering crop,
  rotation, source-byte preservation, markup flattening and PNG output;
- the APK installed over existing emulator data and launched without a crash;
- Roborazzi reported exactly the repository's 17 documented pre-existing visual differences. No
  golden was recorded, modified or deleted.

A release/integration walkthrough should still manually exercise system-picker import, every editor
control, Save copy, Page reopen and restart persistence. Goldens must not be recorded without
explicit approval.

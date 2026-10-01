# Audio feature contract

Status: **Audio v1 implemented — record-only.** Approved scope decision, 2026-10-01.
Audit that produced this design: `AUDIO_FEATURE_AUDIT.md`.

## Product meaning

**Audio v1 means a recorded voice note owned by a task.** Choosing **Audio** in the mind-map Add
sheet opens the existing Capture Voice workspace with the selected task supplied explicitly, so the
recording belongs to that task and appears on its Page.

**Imported audio is deliberately not part of this entry.** A pre-existing audio file remains an
attachment under `AttachmentKind.AUDIO`, imported through the File flow. That contract is untouched
and must stay compatible. Offering import inside the Audio workspace is a separate product decision
and is **not** approved.

**There is no new Audio domain model.** No new entity, capture type, table, storage location or
codec was introduced. "Audio" is the user-facing name for recorded Voice.

## Canonical domain and storage model

Unchanged from Capture Voice:

| Concern | Owner |
|---|---|
| Capture row | `CaptureItem(type = VOICE)` |
| Companion document | `VoiceDocument` with an ordered `List<VoiceClip>` |
| Preview text | `VoiceDocumentCodec.preview(doc)` |
| Media bytes | `filesDir/voices/{captureId}/{clipId}.m4a` via `VoiceFileStore` |
| Persisted path | **relative** (`{captureId}/{clipId}.m4a`), so the app directory can move |
| Format | MPEG-4 container, AAC audio, `.m4a`, `mimeType` default `audio/mp4` |
| Mutations | `VoiceActions.createVoice` / `saveVoice`, inside one repository transaction |

**Room stays at version 17. No migration was added, and none is required.**

## Ownership

Ownership travels as an explicit id and is never inferred from the focused stream, a title or a
list position.

- The map passes only `taskId`. `CaptureActions` derives `projectId` and `workStreamId` from that
  task, so ownership has exactly one source of truth.
- A **new** recording takes its owner from `CaptureContext` supplied by the route.
- An **existing** recording ignores any supplied context and hydrates its owner from the persisted
  capture row in `VoiceEditorViewModel.loadExisting`. Reopening a saved recording can therefore
  never be re-homed by whichever route happened to open it.

## Route contract

All routes are ID-based.

| Route | Purpose | Builder |
|---|---|---|
| `voice_editor` | New global/Inbox recording | `voiceEditorRoute(null)` |
| `voice_editor/{captureId}` | Open an existing recording | `voiceEditorRoute(captureId)` |
| `voice_editor/new/task/{taskId}` | **New** — task-owned recording | `voiceEditorForTask(taskId)` |

`voiceEditorForTask` `require`s a non-blank task id, so a task-owned route cannot be built without
an owner. The route shape follows the pattern Prompt (`prompt_editor/new/{taskId}`) and Link
(`link_editor/new/task/{taskId}`) already use.

## Task Page mapping

- `typeKey = capture.voice`, `contentId = captureItem.id`.
- Registration is automatic: `CaptureActions` registers the block in the **same transaction** as
  capture creation whenever `taskId` is present. The Audio lane added no separate registration
  path, so a block cannot exist without its capture.
- The Page label for `capture.voice` is already **"Audio"**.
- Reopening routes by the persisted `contentId` through `voiceEditorRoute`.

### Shared routing correction — owned by the Audio lane

`openBlock` in `ui/page/TaskPageScreen.kt` previously handled only TODO, NOTE, `capture.prompt` and
`capture.link`. It had **no branch for `capture.voice` or `capture.file` and no `else`**, so those
blocks rendered and were labelled correctly but their taps did nothing at all. This lane fixed that
defect for **both** types, using each feature's own published builder:

- `capture.voice` → `com.virlin.app.ui.voice.voiceEditorRoute(contentId)`
- `capture.file` → `com.virlin.app.ui.file.fileViewerRoute(contentId)`

Both builders and their registered destinations were verified before use; no route was invented.
An unknown `typeKey` now reports through the Page's existing message dialog rather than failing
silently, preserving the open-string forward-compatibility contract.

The integrated PDF workspace now reuses this corrected `capture.file` case and dispatches by
persisted attachment kind. This remains a shared integration seam and must not be duplicated
feature-locally. Labels, ordering, reconciliation and ownership remain shared contracts.

## Permission policy

`RECORD_AUDIO` is requested contextually when recording begins, never at launch. The manifest
declares it. There is no background recording service, so no foreground-service obligation.

## Media-file lifecycle

- Created on `startRecording` into `filesDir/voices/{captureId}/{clipId}.m4a`.
- Deleting a clip in the editor removes its file (`VoiceFileStore.deleteClip`).
- Discarding an uncommitted draft removes the whole capture tree (`deleteCaptureTree`).
- Recorder and player resources are released on cancel, on error and in `onCleared`.

## Compatibility guarantees

- Existing Capture Voice records remain fully readable, playable and editable; their routes are
  unchanged.
- Global and project/stream-scoped recordings still create **no** Task Page block.
- `AttachmentKind.AUDIO` imports are untouched.
- No compatibility route, entity, DAO, serializer or stored file was removed.

## Error handling

- A missing or corrupt clip yields a null player via `runCatching { ... }.getOrNull()` rather than a
  crash.
- A recording that produced no usable clip is rejected by `VoiceDocument.hasMeaningfulContent`, so
  cancelling without recording creates neither a capture nor a Page block.
- Save failures surface as `VoiceSaveStatus.Error` with a user-visible message.

## Known limitations — documented, not implemented

These were investigated under this lane and deliberately **not** changed, because each needs its
own ownership and compatibility decision:

1. **No maximum recording duration or file-size policy.** `MediaRecorder.setMaxDuration` and
   `setMaxFileSize` are never called, so a long recording can grow unbounded.
2. **Archiving a voice capture orphans its media.** `VoiceFileStore.deleteClip` and
   `deleteCaptureTree` are called only from the editor (`VoiceEditorViewModel`). `archiveCapture`
   does not touch managed storage, so `.m4a` files for an archived capture remain on disk
   indefinitely. Destructive cleanup was **not** implemented: deletion semantics are shared with
   Attachment and Prompt and must be designed once, for all capture types.
3. **Recording across process death** is not covered by tests.
4. **Emulator microphone** is synthetic, so real recording quality cannot be verified there.

## Extension rules

1. Store content in its own canonical model; never copy payloads into `task_page_blocks`.
2. Pass ownership explicitly through `CaptureContext`; never infer it.
3. Let `CaptureActions` register the Page block transactionally.
4. Use ID-based routes and the feature's own published builder.
5. Enable a palette tile only when its destination is functional.
6. Adding import to the Audio workspace requires a new product decision and must not duplicate a
   record for one imported file.

## Test expectations

`AudioWorkspaceTest` (12 tests) pins: route construction and parsing, blank-task rejection,
existing-route compatibility, reopen-by-persisted-id, task-owned capture creation with derived
project/stream, exactly one `capture.voice` block, no duplicate block across re-saves, no block for
global recordings, no capture or block when cancelling, zero-length clip rejection, read/edit
round-trip preserving ownership, and isolation between two tasks.

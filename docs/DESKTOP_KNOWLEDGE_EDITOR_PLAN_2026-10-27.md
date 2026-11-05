# Desktop knowledge editor plan (27 October 2026)

## Source of truth and compatibility

- Android remains the only data owner. Desktop requests run through the existing authenticated Virlin Link session and canonical domain actions; no database copy or independent Windows notes store.
- Ask the phone for explicit knowledge capabilities before offering writes. An older APK returning `UNSUPPORTED_OPERATION` must show an update-required state, not a generic retry or a seemingly working Save button.
- Keep request sizes bounded, project/capture ownership checked on every operation, and idempotency keys on writes. A failed acknowledgment must not silently retry with a new key.

The current Android sources have three distinct canonical models: `VirlinNoteDoc` for rich notes, `PromptDocument` for prompts and pasted responses, and `VoiceDocument` plus app-managed clip files for recordings. A shared visual shell is appropriate; a single generic text payload is not. Project Knowledge, task-associated captures, and prompt/voice entry points must resolve through the same capture ID and its project/task context. The desktop must never create a parallel copy of any of these records.

## Page flow

1. Project → Knowledge opens the library. A card opens a dedicated knowledge page; Back returns to the same project/library.
2. Add knowledge opens a dedicated page with a deliberate type choice. Note and prompt share layout tokens but expose their own canonical fields. Link/checklist/steps retain their supported phone operations.
3. Text editors support ordinary Windows selection, keyboard shortcuts, copy/paste, and explicit save feedback. Rich block controls must map to the Android document model. Never interpret arbitrary pasted HTML as trusted markup.
4. Media/voice cards open a detail page with metadata and an honest unavailable state until authenticated binary transfer, permission, recording, playback, and cancellation are designed and tested end-to-end.

## Safe delivery sequence

- Phase 1: page navigation, compatibility signaling, text creation through the existing phone actions, and read-only detail for existing knowledge. Preserve all data; no destructive transformations.
- Phase 2: lossless structured note/prompt read and edit, revision conflict checks, formatting and clipboard interchange with the Android models. Test existing rich documents, not just new ones. A write request must carry the captured revision; the phone must reject stale revisions before entering the transaction. Keep block IDs and unsupported block attributes intact. If the desktop cannot represent a block, show it read-only rather than flattening it into a text edit.
- Phase 3: authenticated chunked media transfer and audio recording/playback with permission and lifecycle handling. Do not expose controls before both ends work.
- Phase 4: device verification on current APK and Windows build over Wi-Fi and USB tethering, including reconnect, cancellation, large paste, formatting round-trips, and app navigation.

## Field and interaction contract

| Knowledge type | Windows page | Phone model | First writable scope |
| --- | --- | --- | --- |
| Note | Rich block editor | `VirlinNoteDoc` | Title, text blocks, marks, list and heading types only after revisioned save exists |
| Prompt | Prompt/code editor with description, tags, language, pasted response | `PromptDocument` | Text creation with current authenticated action; existing prompt edits require revisioned update |
| To-do / steps | Line-aware editor | `NoteDocument` / bridged note blocks | Creation through current phone action; preserve checked state when editing is added |
| Link | URL and optional note | `CaptureItem` | Current authenticated creation action |
| Voice/audio | Metadata, clips, recording and playback | `VoiceDocument` and `VoiceFileStore` | Read-only metadata until encrypted chunk transfer, permission and cleanup tests pass |

Clipboard: plain-text paste must preserve line breaks; rich paste should be parsed from an allowlisted HTML/Markdown subset to typed blocks and runs. Never inject clipboard HTML directly into the desktop DOM or persist it raw. Copy should offer plain text, with formatted HTML only when the serialized ranges can round-trip. Keyboard formatting should update model ranges, not issue browser `execCommand` operations. Save feedback must name whether the phone confirmed the write.

## Verification gates

1. Unit tests for block/run serialization (including overlaps, Unicode offsets, tables and unsupported blocks), prompt fields and ownership validation.
2. Revision conflict, idempotent retry, oversized payload, disconnect-during-save, and old-APK capability tests.
3. Windows interaction tests for Back/Cancel with unsaved work, clipboard and keyboard shortcuts, reconnect and focus, and zero false-success messages.
4. Physical-device tests on both transport modes. For audio: record, import, play, pause, delete/cancel and abrupt disconnect; verify phone file and database cleanup and no duplicate clips.

## Release gate

No claim of feature parity or supported audio editing until every phase above passes on a physical phone. Windows installer signing remains a separate release requirement.

## Current implementation status

- Done in source: authenticated capability/read/create operations; dedicated knowledge pages; structured read-only rendering for Android note blocks; revision-checked edits to existing note text blocks with headings/lists/checklists, selected-text bold/italic/underline/strike/code marks, adding text blocks and reordering blocks without dropping originals; prompt creation and revision-checked edits for simple prompt documents; existing-link title/URL/note edits with an optimistic token while preserving Android preview/playback settings; pasted response text; bounded read-only playback chunks for existing phone voice clips; task-page knowledge navigation and phone-backed creation scoped to the selected task; phone-confirmed workstream task sibling ordering with an atomic stale-snapshot check.
- Deliberately preserved read-only: image, table, divider and media blocks. Their data is sent back unchanged when text is edited. Existing rich fonts, colors and links are preserved in run payloads but have no Windows editing controls yet.
- Not yet done: removing note blocks, adding media/table blocks, rich HTML clipboard import/export; editing complex legacy prompt block trees; voice recording/import, audio waveform/editing, playback on a physical device; knowledge entry points from every Android screen; the Now, Momentum and Apps workspaces; signed Windows release and physical-device acceptance tests. Do not describe these as working in a release.

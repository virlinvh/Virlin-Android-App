# Voice feature contract

Verified against `feature/attachment-workspace`: 2026-10-01

Voice is a Capture-backed multi-clip recording workspace. One `CaptureItem(VOICE)` owns one
`VoiceDocument`; clips are AAC audio in M4A containers under
`filesDir/voices/{captureId}/{clipId}.m4a`. The document stores ordered clip metadata and relative
paths. `RECORD_AUDIO` is requested only when recording begins.

The editor supports record/stop, playback/scrub, rename, delete and reorder. An empty draft is not a
meaningful Inbox record; the first valid clip commits the capture. Reopening uses the same capture ID.
Sharing/playback resolves managed paths, and deletion/duplication must handle every clip byte.

Task-scoped Voice captures use `capture.voice` Page blocks (labelled Audio in Page). The enabled
mind-map Audio tile is Audio v1 and reuses this contract without separate persistence:

1. recorded Audio opens the existing multi-clip Voice workspace; and
2. imported `AttachmentKind.AUDIO` remains a read-only file attachment, not a Voice record.

Do not introduce a second incompatible audio table/file lifecycle without explicit approval and a
migration/compatibility plan.

## Audio v1

The mind-map **Audio** entry is this feature, reached with an explicit task owner through
`voice_editor/new/task/{taskId}`. There is no separate Audio model. See `AUDIO_FEATURE.md`.

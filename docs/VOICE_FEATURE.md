# Voice feature contract

Verified against the live workspace: 2026-09-30

Voice is a Capture-backed multi-clip recording workspace. One `CaptureItem(VOICE)` owns one
`VoiceDocument`; clips are AAC audio in M4A containers under
`filesDir/voices/{captureId}/{clipId}.m4a`. The document stores ordered clip metadata and relative
paths. `RECORD_AUDIO` is requested only when recording begins.

The editor supports record/stop, playback/scrub, rename, delete and reorder. An empty draft is not a
meaningful Inbox record; the first valid clip commits the capture. Reopening uses the same capture ID.
Sharing/playback resolves managed paths, and deletion/duplication must handle every clip byte.

Task-scoped Voice captures use `capture.voice` Page blocks (currently labelled Audio in Page). The
disabled mind-map Audio tile has no product contract or separate persistence. Before enabling it,
decide whether it is:

1. an entry into this existing multi-clip Voice workspace, or
2. a documented specialization of the same Capture/Voice record.

Do not introduce a second incompatible audio table/file lifecycle without explicit approval and a
migration/compatibility plan.

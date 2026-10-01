# Non-Image PR readiness audit

Date: 2026-10-01

Branch: `feature/attachment-workspace`

Audited head: `6c92084`

Scope: everything in the combined baseline except the isolated Image feature.

## Verdict

The combined To-do, Notes, Prompt, Link, PDF, Audio/Voice, and Attachment baseline is technically
ready to become the base of the final PR. Image remains an explicit external gate and must be
integrated and reverified before that PR is created.

This audit found documentation drift, not a production-code defect. The authoritative registry,
navigation, storage, testing, Voice, PDF, File, Page, and coordination documents were corrected to
match the committed implementation.

## Confirmed architecture

- Room remains v17 with the complete non-destructive migration chain and exported schema 17.
- To-do owns `task_steps`; redesigned Notes owns `virlin_notes`; Prompt and Link remain Capture
  workspaces; Page contains ordered references only.
- PDF and Attachment reuse `CaptureItem(FILE)`, `AttachmentDocument`, managed attachment storage,
  and `capture.file` Page registration. There is no parallel PDF/Attachment database.
- Audio v1 reuses `CaptureItem(VOICE)`, `VoiceDocument`, and the managed Voice store. Imported audio
  remains an Attachment and is not misrouted into the recorder.
- Task Page opens content by stable IDs. PDF dispatch is based on persisted `AttachmentKind.PDF`,
  never filename; other file blocks use the universal viewer.
- UI code does not own Room/DAO mutations; feature writes continue through action/repository
  boundaries.

## Feature readiness

| Feature | Status | Authoritative contract |
| --- | --- | --- |
| To-do | Implemented | `TASK_PAGE_FOUNDATION.md` |
| Notes | Implemented; legacy Capture Notes retained for compatibility | `NOTE_SYSTEM_CLEANUP.md` |
| Prompt | Implemented | `PROMPT_FEATURE.md` |
| Link | Implemented, including validated previews and YouTube segments | `LINK_FEATURE.md` |
| PDF | Implemented task workspace over Attachment | `PDF_WORKSPACE_FEATURE.md` |
| Audio | Implemented v1 recorded Voice workspace | `AUDIO_FEATURE.md` |
| Attachment | Implemented task-scoped universal file workspace | `ATTACHMENT_WORKSPACE_FEATURE.md` |
| Image | Excluded from this audit; isolated emulator audit in progress | Separate Image branch |

## Verification performed

- `:app:testDebugUnitTest`: 1,091 tests, 0 failures, 0 errors.
- `:app:compileDebugAndroidTestKotlin`: passed.
- `:app:assembleDebug`: passed.
- `:app:verifyRoborazziDebug`: exactly the 17 previously documented visual differences; no new
  failure name and no golden was recorded or modified.
- `git diff --check`: clean after documentation correction.

The branch already records completed emulator walkthroughs for PDF/Audio integration and Attachment
in the agent logs and feature specifications. This documentation audit did not repeat destructive
or data-clearing emulator operations.

## Known non-blocking limitations

- The 17 Roborazzi differences remain a deliberate visual approval gate.
- PDF Annotation and OCR remain honest capability gates; the current implementation does not claim
  durable annotation or bundled OCR.
- Attachment Office previews are bounded, read-only extracts rather than layout-faithful editors.
- Archives are stored and described but not extracted.
- Audio v1 records Voice clips; importing audio remains an Attachment product.
- Legacy Capture Notes remain intentionally present so existing content and Prompt's shared legacy
  block model stay readable.

## Final integration gate

After the Image repair/audit branch is accepted, integrate it onto this baseline, rerun the same
ordinary suite, Android-test compilation, APK assembly, Roborazzi comparison, and the combined
emulator smoke flows. Only then create the final PR. Do not accept screenshot goldens as part of
that merge unless the user explicitly approves each named baseline.

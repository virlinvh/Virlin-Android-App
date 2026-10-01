# Feature registry

Updated: 2026-10-01

| Feature | Canonical implementation/status | Specification | Next ownership |
|---|---|---|---|
| Now / attention | Implemented; approved/frozen surfaces | `CLAUDE.md` | Integration-only changes |
| Project/WorkStream/Task hierarchy | Green hierarchy canonical | `TASK_HIERARCHY_CLEANUP.md` | Shared foundation |
| Mind map | Implemented structure/navigation | `NAVIGATION_AND_FEATURES.md` | Shared foundation |
| Task Page | Implemented extensible blocks | `TASK_PAGE_FOUNDATION.md` | Shared foundation |
| To-do | Implemented task steps | `TASK_PAGE_FOUNDATION.md` | Maintained |
| Notes | Redesigned canonical; legacy compatibility | `NOTE_SYSTEM_CLEANUP.md` | Maintained |
| Prompt | Implemented prompt/code/response workspace | `PROMPT_FEATURE.md` | Maintained |
| Link | Implemented validation, previews and YouTube segments | `LINK_FEATURE.md` | Maintained |
| Generic File | Implemented Capture import/viewer compatibility surface | `FILE_ATTACHMENT_FEATURE.md` | Maintained |
| Voice | Implemented Capture multi-clip recorder/player | `VOICE_FEATURE.md` | Canonical store behind Audio v1 |
| PDF palette feature | **Implemented** task workspace; specialized managed Attachment | `PDF_WORKSPACE_FEATURE.md` | Maintained |
| Audio palette feature | **Implemented (v1, record-only)** — reuses Capture Voice, no new model | `AUDIO_FEATURE.md` | Maintained |
| Attachment palette feature | **Implemented** task-scoped universal local file space | `ATTACHMENT_WORKSPACE_FEATURE.md` | Maintained |
| Image palette feature | Disabled | None yet | Unassigned |
| Sticker | Disabled | None yet | Unassigned |
| Illustration | Disabled | None yet | Unassigned |

PDF, Audio, and Attachment are combined on `feature/attachment-workspace`. Image remains isolated
and is not part of this baseline until its emulator audit is complete.

# Virlin data and storage

Verified against the live workspace: 2026-09-30

## Room database

The current Room database version is **17**. Exported schemas exist through version 17 and are test
assets for `MigrationTestHelper`. Migrations are additive and explicitly registered from 1→2 through
16→17. Destructive migration is not an acceptable development shortcut.

Current tables/entities:

- `projects`
- `workstreams`
- `tasks`
- `external_stages`
- `priority_preferences`
- `captures`
- `cycles`
- `focus_sessions`
- `context_snapshots`
- `events`
- `meta`
- `note_documents` — legacy Capture Note documents
- `prompt_documents`
- `attachment_documents`
- `voice_documents`
- `project_tags`
- `tag_links`
- `virlin_notes` — redesigned owner-scoped Notes
- `task_steps`
- `task_page_blocks`

The canonical persistence interface is `WorkStreamRepository`. Production uses
`RoomWorkStreamRepository`; tests commonly use `InMemoryWorkStreamRepository`. Feature code must
implement both sides when extending the repository contract; the shared Kotlin interface makes an
omitted implementation a compile-time failure.

## Content identities

- Capture content uses a canonical `CaptureItem` plus its type-specific companion document.
- Task Page blocks store `taskId`, opaque `typeKey`, `contentId` and ordering; they reference content.
- Redesigned task Note owner: `TaskPageTypeKeys.noteOwner(taskId)` → `task-<taskId>`.
- Project Note owner: `project-<projectId>`.
- Global Note owner: `global`.
- Owner keys must be produced by centralized helpers, never handwritten in feature UI.

## Managed files

- Attachments: `filesDir/attachments/{attachmentId}/original`.
- Voice clips: `filesDir/voices/{captureId}/{clipId}.m4a`.
- Custom project icons: `filesDir/project-icons/...`.
- Map appearance: `filesDir/map_appearance.json`.
- Exported legacy Note PDFs: temporary/shareable files under `cacheDir/notes/` via `FileProvider`.

Imports must stream through Android `ContentResolver` into managed storage. Persistent records use
relative paths so application directories may move. Deletion and duplication must handle the row and
managed bytes consistently; partial imports must be cleaned up.

Only the declared attachment, voice and cached Note-PDF roots are FileProvider-shareable. Project
icons and map appearance are private application state and must not be exposed through FileProvider.

## Existing PDF distinction

`AttachmentKind.PDF` identifies PDFs inside the generic Capture File system. The enabled mind-map
PDF workspace specializes that same attachment contract and routes by persisted kind; it does not
introduce a parallel PDF table or create two records for one imported file.

## Migration governance

- Reserve versions only in `DATABASE_MIGRATION_QUEUE.md`.
- One integration owner assigns the next version.
- Update entity, DAO, mapper, repository, migration, exported schema and migration tests together.
- Never allow two feature branches to independently claim the same version.
- File-format migrations and Room migrations require explicit rollback/data-preservation analysis.

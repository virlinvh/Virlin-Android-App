# Virlin navigation and feature map

Verified against the live workspace: 2026-09-30

## Root destinations

- `now` — current attention, Needs You, Working For You and When Free.
- `streams` — Projects/WorkStreams entry.
- `pulse` and `pulse_project/{id}` — activity/timeline views.
- `apps` — Apps directory.
- `inbox` — durable Capture Inbox.

The bottom bar and floating Orb are owned by the app scaffold. Feature screens must not add a second
root footer.

Current shell rule: routes listed in `STREAMS_SUB_ROUTES` receive the shared footer and Orb with
Projects selected. This includes project/map/index, green hierarchy, the task compatibility redirect,
To-do and redesigned Notes. `task_page/{taskId}` is intentionally outside that list today and owns
its own full-page `Scaffold`; `focus_clock` is immersive. Any change to this split is a deliberate
navigation decision, not a feature-local styling edit.

## Structure and task routes

- `project_detail/{id}` — project experience.
- `workstream_detail/{id}?path={path}` — canonical green recursive task hierarchy.
- `project_task_index/{id}` — project-scoped task index, including standalone tasks.
- `project_map/{id}` — mind map.
- `task_page/{taskId}` — block-based Page for task content.
- `task_todo/{id}` — canonical task steps.
- `task_detail/{id}` — compatibility entry only; redirects by stable IDs to the green hierarchy or
  project task index. It is not a cream Task Detail screen.
- `stream_detail/{id}` — registered legacy `StreamDetailScreen` route with no production caller.
  It is obsolete/unreachable and retained only pending a deliberate cleanup pass.

## Content routes

- Redesigned Notes: owner/title argument route under `ui/notes`; canonical for every new visual Note.
- Legacy `text_note/{captureId}`: existing Capture Notes only; there is no bare new-draft route.
- Prompt: new and capture/task-scoped prompt editor routes.
- Link: new and capture/task-scoped link editor routes.
- File: new/import and `file_viewer/{captureId}` compatibility/content route.
- Voice/Audio: new/record, `voice_editor/{captureId}`, and `voice_editor/new/task/{taskId}`
  (Audio v1, task-owned recording).
- `focus_clock` — alternate presentation of the same active Focus session.

## Mind-map Add palette

Implemented and enabled:

- To-do → task steps and Page registration.
- Note → redesigned owner-scoped task Note and Page registration.
- Prompt → task-attached Prompt workspace.
- Link → task-attached Link workspace.
- Audio → task-owned voice recording (Audio v1 is recorded voice; imported audio stays with
  `AttachmentKind.AUDIO` and is not offered here).

Visible but disabled (`Not yet`):

- PDF (renamed from Topic link; PDF icon; no storage/navigation yet).
- Attachment.
- Image.
- Sticker.
- Illustration.

Disabled items must not be marked supported until they have real storage, navigation, ownership,
failure handling and tests.

## Canonical versus compatibility boundaries

- Green task hierarchy: canonical. Removed cream Task Detail must not return.
- Owner-scoped Notes: canonical for new visual Notes.
- Legacy Capture Note editor: compatibility for existing capture rows.
- Capture File and Voice systems: existing canonical Capture implementations; future Page-facing
  PDF/Audio features need an explicit integration decision rather than silent replacement.
- `task_detail/{id}`: preserved route contract, redirected presentation.

## Permissions and external surfaces

- Notifications and exact-alarm capability support scheduled attention returns/checks.
- `RECORD_AUDIO` is requested contextually when recording.
- Internet supports link previews and the restricted YouTube iframe player.
- File sharing uses the application `FileProvider`.

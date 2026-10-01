# Audio feature audit — decision gate

Auditor: Claude · Date: 2026-10-01 · Mode: read-only, no production change
Baseline: `main` @ `0380d92`, clean worktree, in sync with `origin/main`. Room **v17**.
Discovery: Graphify first (`explain VoiceActions` resolved with accurate line numbers), then
targeted source reads. **Graphify was not regenerated** — see note at the end.

---

## Verdict

# REUSE WITH A SMALL ADAPTER

The existing Voice implementation is a sound, complete recording pipeline that **already supports
task ownership end to end** and **already registers its Task Page block automatically**. Nothing
about the domain, storage or schema needs to change. What is missing is entry plumbing in the UI
and one genuine defect on the Task Page.

**Room v17 can remain unchanged. No migration is required.**

I stopped at this gate rather than implementing, for one reason stated in the brief: choosing
between record-only, import-only or both is a product decision, and implementing record-only would
silently make it. My recommendation is in §5.

---

## 1. Existing implementation map

| Concern | File | Key symbols |
|---|---|---|
| Domain actions | `domain/action/VoiceActions.kt` | `createVoice` L23, `saveVoice` L58, `getVoiceByCaptureId` L93, `tx` L96 |
| Domain model | `domain/model/VoiceModels.kt` | `VoiceClip` (id, displayName, relativePath, durationMs, sizeBytes, **mimeType**, sortOrder, createdAt), `VoiceDocument` |
| Codec/preview | `domain/voice/VoiceDocumentCodec.kt` | `preview(doc)` |
| Managed storage | `data/voice/VoiceFileStore.kt` | `filesDir/voices/{captureId}/{clipId}.m4a`, `relativePath`, `deleteClip`, `deleteCaptureTree` |
| Recording | `ui/voice/VoiceEditorViewModel.kt` | `MediaRecorder` L148–155, `startRecording` L141, `stopRecording` L194, `onCleared` L388 |
| Playback | `ui/voice/VoiceEditorScreen.kt` | `MediaPlayer` L399, `release()` L409 |
| Permission | `ui/voice/VoiceEditorScreen.kt` | `RECORD_AUDIO` check L129, launcher L131 |
| Routes | `ui/voice/VoiceEditorScreen.kt` | `VoiceEditorRoute = "voice_editor"` L90, `voiceEditorRoute(captureId)` L91 |
| Route registration | `ui/navigation/VirlinApp.kt` | `"voice_editor"` L485, `"voice_editor/{captureId}"` L489 |
| Page auto-registration | `domain/action/CaptureActions.kt` | **L52** `item.taskId?.let { ensureTaskPageBlock(..., TaskPageTypeKeys.capture(item.type), item.id) }` |
| Page seams | `ui/page/TaskPageScreen.kt` | label L44–55, row projection L76–96, `iconFor` L157, `openBlock` L159–164 |
| Attachment (import) | `domain/action/AttachmentActions.kt`, `data/attachment/AttachmentFileStore.kt` | `openInputStream` L43, `mimeType` plumbing |
| Attachment kinds | `domain/model/AttachmentModels.kt` | `enum AttachmentKind { PDF, IMAGE, VIDEO, **AUDIO**, TEXT, CSV, DOCX, XLSX, PPTX, UNSUPPORTED }` L9–11 |
| Tests | `test/.../VoiceCaptureTest.kt` | 4 tests |

---

## 2. What already works

**Recording is real and reasonably safe.** `MediaRecorder` with the API-aware constructor,
`MPEG_4` + `AAC` → `.m4a`. Multi-clip: a `VoiceDocument` holds an ordered `List<VoiceClip>`.
Resources are released on `onCleared` (L388–392) and on the error path (L206), the elapsed-time
ticker is cancelled, and an in-flight recording is stopped before persisting (L285).

**Playback works in-app** via `MediaPlayer`, released at L409.

**Permissions are handled**: `RECORD_AUDIO` is checked and requested contextually, and the manifest
declares it. There is no background recording service, which keeps the lifecycle simple.

**Storage is correct and portable.** Bytes live under `filesDir/voices/{captureId}/{clipId}.m4a`;
the database stores **relative** paths, so the app directory can move. Deletion helpers exist for
both a single clip and a whole capture tree.

**Task ownership already works end to end — this is the important finding.**
`VoiceEditorViewModel` carries `context: CaptureContext` in its state (L43), hydrates it from an
existing capture (L110), exposes a public `setContext(ctx)` (L123), and passes it into
`actions.createVoice(..., context = st.context, ...)` (L328). `CaptureActions` then sees
`item.taskId != null` and **automatically registers the `capture.voice` Page block in the same
transaction** (L52). So a task-owned recording would already appear on that task's Page.

**The Task Page already calls it "Audio."** `TaskPageBlockRegistry.label` maps
`capture(CaptureType.VOICE) -> "Audio"` (L52) and `capture(CaptureType.FILE) -> "File"` (L51).

**Imported audio already has a home.** `AttachmentKind.AUDIO` exists, and `AttachmentFileStore`
streams a picked URI through `ContentResolver.openInputStream` into managed storage — so imported
files are **copied**, not referenced by a fragile picker URI.

---

## 3. What is incomplete or unsafe

### 3.1 DEFECT — a Task Page Audio block cannot be opened

`openBlock` (`TaskPageScreen.kt:159–164`) handles `TODO`, `NOTE`, `capture.prompt` and
`capture.link`. It has **no branch for `capture.voice` or `capture.file`, and no `else`**. A
`capture.voice` block can already exist on a Page today — it is registered automatically and
labelled "Audio" — but **tapping it silently does nothing**. This is a live defect in shipped
behaviour, not merely a gap in the unbuilt Audio feature.

### 3.2 No task-context entry point for Voice

Voice exposes `voice_editor` (new) and `voice_editor/{captureId}` (existing). Neither can carry a
task. Compare the established pattern used by the two features that solved this:

- `prompt_editor/new/{taskId}` with `PromptEditorViewModel.factory(captureId, context: CaptureContext)`
- `link_editor/new/task/{taskId}`

`VoiceEditorViewModel.factory(captureId, context: Context)` takes only an **Android** `Context` for
`MediaRecorder` and the file store — there is no `CaptureContext` parameter, and **nothing in the UI
ever calls the existing `setContext`** for Voice (Note, Prompt and File all have context pickers;
Voice does not). So the capability exists in the ViewModel but is unreachable.

### 3.3 The mind-map Audio tile is inert

`MapAddKind.AUDIO` exists and is correctly excluded from `isSupported`; `ProjectMapScreen` has **no
`MapAddKind.AUDIO` branch**, so it falls through to the honest "not available yet" notice.

### 3.4 Generic icon

`iconFor` (L157) has no audio case, so an Audio block shows `InsertDriveFile`. Cosmetic.

### 3.5 Thin test coverage

`VoiceCaptureTest` has **4 tests**: codec round-trip, `createVoice` commits once, empty rejected,
non-voice rejected. There is **no** coverage of task ownership, Page registration, duplicate
prevention, recorder/player lifecycle, permission denial, missing or corrupt media, or deletion.

### 3.6 Unverified / absent policies

- **No maximum duration or file-size policy** was found. A long recording could grow unbounded.
- **Deletion coordination is unverified**: `VoiceFileStore` has the helpers, but I did not find a
  path that deletes managed audio when a capture is archived or converted. Worth confirming before
  Audio becomes a first-class task attachment — orphaned `.m4a` files would accumulate silently.
- **Process-death behaviour during an active recording** is not covered by tests.
- **Emulator limitation**: the emulator's synthetic microphone makes real recording verification
  weak; and this machine's emulator framebuffer has been returning frozen/torn frames, so visual
  confirmation is currently unreliable.

---

## 4. Is the existing Voice interface suitable for the mind-map Audio entry?

**For recorded audio: yes, almost entirely.** Domain, storage, Page registration and ownership all
work already. The editor is a recorder/player, which is exactly what an Audio workspace needs.

**For imported audio: not as it stands.** Voice has no import path — no `OpenDocument`,
`GetContent` or persistable-URI handling. Import already exists in the **Attachment** system, which
copies bytes into app-owned storage and has `AttachmentKind.AUDIO`.

---

## 5. Recommended first-release scope — **your decision**

### Recommended: record-only (Option A)

Enable the Audio tile for **recording into the selected task**, reusing Voice unchanged, and fix the
Page-open defect. Smallest possible change, no new storage, no schema change, no new editor, and it
makes the existing "Audio" label on the Task Page actually functional.

### The alternative, stated fairly

**Option B — record + import.** Import reuses `AttachmentActions` with `AttachmentKind.AUDIO`. It
is genuinely useful, but it means one Audio tile producing **two different capture types**
(`capture.voice` for recorded, `capture.file` for imported), so the Page would show the same user
concept under two labels ("Audio" and "File") unless the label logic is refined. That is the
composition option (§6 option 4) and it is a real product choice, not a technical detail.

**Option C — import-only.** Not recommended: it leaves the working recorder unreachable from the
map and adds nothing the File tile will not already offer.

---

## 6. Terminology, and which contract Audio should be

| Term | Meaning today |
|---|---|
| Voice note | `CaptureItem(VOICE)` + `VoiceDocument` + clips in `filesDir/voices/` |
| Recorded Audio | The same thing, reached from a task instead of the Inbox |
| Imported Audio | `CaptureItem(FILE)` + `AttachmentDocument(kind = AUDIO)` in `filesDir/attachments/` |
| Audio attachment | Synonym for imported Audio |
| Task Page Audio block | `capture.voice` block, already labelled "Audio" |

Of the five options in the brief, the smallest that preserves data and avoids parallel models is
**(1) a presentation label over the existing `capture.voice` contract** for record-only, escalating
to **(4) a small composition** if import is wanted. **A new domain type is not justified** and
would create exactly the incompatible second audio model `FEATURE_REGISTRY.md` warns against.

---

## 7. Recommended contracts

**Data/storage:** unchanged. `CaptureItem(VOICE)` + `VoiceDocument` + `VoiceClip`, bytes in
`filesDir/voices/{captureId}/{clipId}.m4a`, relative paths persisted. If import is approved:
`CaptureItem(FILE)` + `AttachmentDocument(kind = AUDIO)`, bytes copied into `filesDir/attachments/`.

**Ownership:** `CaptureContext(projectId, workStreamId, taskId)` passed explicitly from the map's
selected task. Never inferred from focus, title or list position. Page keys come only from
`TaskPageTypeKeys.capture(type)`.

**Routes (ID-based, following the Prompt/Link precedent):**
- `voice_editor` — existing, global/Inbox
- `voice_editor/{captureId}` — existing, reopen
- `voice_editor/new/task/{taskId}` — **new**, task-scoped creation

**Task Page mapping:** `typeKey = capture.voice`, `contentId = captureItem.id`, registered
automatically by `CaptureActions` inside the creation transaction. Reopen via
`voiceEditorRoute(row.block.contentId)`.

---

## 8. Exact files likely to change (record-only)

| File | Change | Collision risk |
|---|---|---|
| `ui/voice/VoiceEditorScreen.kt` | add `voiceEditorForTask(taskId)` builder; apply context | Low — Audio-owned |
| `ui/voice/VoiceEditorViewModel.kt` | factory accepts `CaptureContext` | Low — Audio-owned |
| `ui/navigation/VirlinApp.kt` | register `voice_editor/new/task/{taskId}` | **HIGH — shared root** |
| `ui/map/MapAddPalette.kt` | add `AUDIO` to `isSupported`; audio icon | **HIGH — shared root, PDF also edits** |
| `ui/map/ProjectMapScreen.kt` | add `MapAddKind.AUDIO` branch | **HIGH — shared root, PDF also edits** |
| `ui/page/TaskPageScreen.kt` | `openBlock` branch for voice (+file); `iconFor` audio icon | **HIGH — shared root, PDF also edits** |
| `test/.../` | new focused tests | None |
| `docs/AUDIO_FEATURE.md` + registry/navigation docs | contract | Low |

**No change to:** Room entities, DAOs, mappers, migrations, `VirlinActions`, repositories, or any
golden image.

---

## 9. Collision assessment for the PDF lane

PDF and Audio would both touch **four shared roots**: `MapAddPalette.kt` (enum/`isSupported`/`rows`/
`iconFor`), `ProjectMapScreen.kt` (the `addChoice` `when`), `VirlinApp.kt` (route registration), and
`TaskPageScreen.kt` (**four separate seams** — label, row projection, `iconFor`, `openBlock`).

Per `AGENT_COORDINATION.md` these are integration-owner files. Both lanes should raise
`docs/handoffs/<FEATURE>_WIRING_REQUEST.md` rather than editing them directly.

**What the PDF lane may assume:** `AttachmentKind.PDF` exists; `AttachmentFileStore` copies picked
URIs into managed storage; task-scoped captures auto-register `capture.file` blocks.
**What it may not assume:** that `openBlock` routes `capture.file` — it does not today (§3.1). If
Audio fixes that seam for both voice and file, PDF inherits the fix; if PDF ships first, the same
applies in reverse. **This should be fixed once, by whoever goes first, not twice.**

---

## 10. Test plan (record-only)

Domain: task-scoped `createVoice` registers exactly one `capture.voice` block with
`contentId == captureItem.id`; re-saving does not duplicate it; existing global Voice captures still
load and play; rejection paths unchanged.
Routing: `voiceEditorForTask(taskId)` builds and parses; the Page opens the correct capture by ID.
Lifecycle: recorder released on cancel, error and `onCleared`; no double-start; cancel does not
destroy a previously saved recording.
Media: missing or corrupt file renders an error rather than crashing; player released.
Permission: denial and permanent denial surface a clear state.
Then: `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin`, `:app:assembleDebug`, and
`:app:verifyRoborazziDebug` reporting **only** against the documented 17.

---

## 11. UI proposal

**No separate Audio workspace is required for record-only.** The existing Voice editor is already a
recorder/player; it needs a task-context header line consistent with Link's
`Attached to · <project>` treatment. If import is approved later, one Audio workspace can offer
"Record" and "Import" and route each to its canonical store.

---

## Notes on method

**Graphify was used first and was not regenerated.** `graph.json` (2026-09-30 23:13) looks stale by
mtime — 193 Kotlin files are newer — but that is an artifact of the `integration → main` branch
switch rewriting file timestamps, not content drift. Symbol lookups resolve with correct line
numbers (`explain VoiceActions` → `VoiceActions.kt:L16` and its six edges), so the index is
functionally current. A refresh (`graphify update .`) is harmless but was not needed for this audit;
`graphify-out/` remains ignored.

One limitation worth stating: `graphify query "Task Page block registration and extension points"`
returned `VirlinProjectPage.kt` (the *Project* page) rather than the Task Page, because "Page",
"Task" and "Block" are high-collision tokens here. Symbol-name queries (`explain VoiceActions`) are
reliable; natural-language feature queries are not, in this codebase.

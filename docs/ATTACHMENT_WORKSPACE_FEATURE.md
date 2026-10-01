# Attachment workspace contract

Status: **implemented** on `feature/attachment-workspace`, based on integration commit `e28b521`.
Audit that produced this design: `AUDIO_FEATURE_AUDIT.md` sibling for Audio; the Attachment audit is
summarised in `CLAUDE_CHANGES.md`.

## Product purpose

Attachment is Virlin's **universal local file space for one task**. A user may import practically
any file, keep it safely on the device, and open it in the best viewer Virlin actually has — or
hand it to another app when Virlin honestly cannot render it.

It is **local-first**: no upload, no remote conversion, no model, no network requirement. Virlin
never executes imported content.

## Canonical route

| Route | Purpose | Builder |
|---|---|---|
| `attachment_workspace/new/task/{taskId}` | The task's file space | `attachmentWorkspaceForTask(taskId)` |

Ownership travels as an explicit id, matching Prompt, Link, Audio and PDF. `attachmentWorkspaceForTask`
`require`s a non-blank task id. Nothing routes by display name.

## Data and managed-storage contract — no new model, no migration

**Attachment introduced no new entity, table or Room version. The database stays at v17.**

One imported file is exactly:

- one `CaptureItem(type = FILE)` carrying task ownership, and
- one `AttachmentDocument` carrying `displayName`, `mimeType`, `sizeBytes`, `relativePath`, `kind`
  and timestamps, and
- one `capture.file` Task Page block, registered by `CaptureActions` inside the same transaction.

The workspace is a **view over those records**, not a container entity. That is why importing five
files produces five independent records and five independent Page blocks, and why nothing needed to
change in the schema.

Bytes are streamed from the picker's content URI into `filesDir/attachments/{attachmentId}/original`
by the existing `AttachmentFileStore`, in 64 KB chunks with `fd.sync()` — the whole file is never
held in memory, and the app does not depend on a temporary picker permission afterwards. Paths are
persisted **relative**, so the app directory may move.

Display names are sanitised before storage: separators and `..` segments are replaced, control
characters stripped, length capped. Renaming preserves the original extension so the resolved kind
and any external app continue to agree.

## The capability resolver — one, not two

Routing lives in the **existing** `domain/attachment/AttachmentKindResolver`. This feature extended
it rather than adding a parallel capability enum:

- `AttachmentKind` gained **`MARKDOWN`** and **`ARCHIVE`**.
- `AttachmentKindResolver.previewOf(kind)` returns `IN_APP`, `PDF_WORKSPACE` or `DETAILS_ONLY`.

Extending the enum is safe without a migration because `attachment_documents.kind` is a **String**
column decoded with `runCatching { AttachmentKind.valueOf(...) }.getOrDefault(UNSUPPORTED)`: existing
rows are untouched, and an older build reading a newer row degrades to `UNSUPPORTED` rather than
crashing.

MIME and extension are treated as hints, with MIME preferred and extension as fallback. A PDF
mislabelled `application/octet-stream` is still resolved as PDF by extension; a file named `.txt`
served as `application/pdf` is still a PDF.

## Viewer routing

| Kind | Destination |
|---|---|
| `PDF` | **Canonical PDF workspace** via `pdfWorkspaceForCapture(captureId)` |
| `MARKDOWN` | New in-app rendered preview with a Source mode |
| `TEXT`, `CSV` | Existing bounded text and table viewers |
| `IMAGE`, `VIDEO` | Existing image and video viewers |
| `AUDIO` | Existing read-only `AudioViewer` — **not** the Voice workspace |
| `DOCX`, `XLSX`, `PPTX` | Existing `UniversalFileViewer` OOXML renderers |
| `ARCHIVE`, `UNSUPPORTED` | "File stored safely" details with Share / Open with |

Everything except Markdown and the archive/unknown details reuses `UniversalFileViewer` unchanged.
**No second viewer for any format was added.**

## Imported Audio is not a Voice capture

This distinction is deliberate and must be preserved.

| | Imported Audio | Voice capture |
|---|---|---|
| Created by | Importing a file through Attachment | Recording in the Voice/Audio workspace |
| Capture type | `CaptureType.FILE` | `CaptureType.VOICE` |
| Companion | `AttachmentDocument(kind = AUDIO)` | `VoiceDocument` + `VoiceClip` list |
| Storage | `filesDir/attachments/…` | `filesDir/voices/…` |
| Page block | `capture.file` | `capture.voice` (labelled "Audio") |
| Opens | Read-only `AudioViewer` | Voice workspace (recorder/editor) |

An imported MP3 has no `VoiceDocument` row, so routing it into the Voice editor would open an empty
recorder over someone's file. Playback components may be shared; **persistence and product identity
stay separate.**

## Safety

- **No execution.** Imported files are never run. Archives are stored and described, never extracted,
  so there is no path-traversal surface in this phase.
- **No HTML/JS renderer.** `MarkdownPreviewParser` has no HTML block type at all: raw HTML in a
  source file is emitted as literal text. Nothing reaches a JavaScript-capable WebView.
- **Bounded reads.** `TextFileReader` caps at 512,000 characters and reports truncation;
  `CsvTableReader` caps rows and columns; `OoxmlReaders` caps paragraphs, sheets, rows and slides.
  Every truncation is disclosed in the UI, and the stored file is never modified.
- **Defensive decoding.** Invalid UTF-8 decodes to replacement characters instead of throwing.
- **No raw paths leave the app.** Share and Open-with go through the application `FileProvider`
  with `FLAG_GRANT_READ_URI_PERMISSION`.
- **Removal is confirmed**, removes only that attachment's record and its own managed directory, and
  never touches a sibling file.
- **Partial import failure is honest.** Each file is imported independently; successes remain
  visible and each failure is named. A file that fails after its directory was created has that
  directory deleted, so no partial copy is stranded.

## Task Page integration

Registration is **not** performed by UI code. `CaptureActions` registers the `capture.file` block in
the same transaction as capture creation whenever a task id is present, which also prevents
duplicates. Existing `capture.file` blocks continue to open through the routing Codex established in
the integration commit: a PDF-kind row opens the PDF workspace, every other file opens the canonical
File viewer. **That seam was not modified by this feature.**

## Legacy compatibility

- Attachments with no task context behave exactly as before and create no Page block.
- Existing `attachment_documents` rows need no migration.
- The generic File capture flow and its routes are unchanged.

## Known limitations

1. Archives are stored and described only — no extraction, by design.
2. DOCX/XLSX/PPTX previews are read-only text and table extracts, not faithful layout. The viewers
   say so.
3. Video playback depends on the device's codec support; an unsupported codec falls back rather than
   claiming to play.
4. Multi-select import is sequential; a very large batch shows progress but is not parallelised.
5. The Attachment workspace lists files for one task. There is no global file browser.
6. Renaming changes the display name only; the managed filename on disk stays `original`.

## Extending to a new format

1. Add the extension/MIME to `AttachmentKindResolver` — **do not** add a second resolver.
2. If it needs a new kind, add it to `AttachmentKind` (safe: String column with a default) and give
   it a `formatLabel` and a `previewOf` mapping.
3. Add one branch to `UniversalFileViewer`, or route it to a specialised workspace by **kind**.
4. Add tests for resolution, preview routing and bounded reading.

## Test coverage

`AttachmentWorkspaceTest` (22 tests): extension/MIME normalisation, conflicting MIME vs extension,
unknown types, uppercase and multi-dot names, archive classification, kind-based preview routing,
ID-based route construction and blank-task rejection, task ownership, single-block registration,
independent records per file, cross-task isolation, empty-file rejection, legacy global attachment,
removal isolation, text truncation and disclosure, malformed encoding, monospace detection, Markdown
document shape, hostile HTML remaining literal, malformed emphasis, inline spans, and block bounding.

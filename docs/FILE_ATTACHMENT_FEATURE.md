# File and attachment feature contract

Verified against `feature/attachment-workspace`: 2026-10-01

File is a Capture-backed import/viewer workflow. Android SAF supplies a URI; Virlin streams the bytes
into `filesDir/attachments/{attachmentId}/original` and stores a `CaptureItem(FILE)` plus one
`AttachmentDocument` containing display name, MIME type, size, relative path and resolved kind.

Kinds include PDF, image, video, audio, text, CSV, DOCX, XLSX, PPTX and unsupported. The universal
viewer is read-only/best-effort for several office formats. The original imported bytes remain the
source of truth. Sharing uses the declared attachment FileProvider root.

Replacement, archive/delete and duplication must keep metadata and managed bytes consistent and
must clean partial imports. A missing file must produce an honest unavailable state, never a crash or
fabricated preview.

Task-scoped File captures use the generic `capture.file` Page key. The enabled PDF and Attachment
workspaces both specialize this contract rather than creating duplicate file databases. PDF rows
route by persisted `AttachmentKind.PDF`; other Page file rows use the universal viewer.

# Prompt feature contract

Verified against the live workspace: 2026-09-30

Prompt is a Capture-backed full-page workspace. A canonical `CaptureItem(PROMPT)` owns one
`PromptDocument`; task/project attachment is context on the capture, not a map node or Page copy.

The editor supports Prompt and Code modes, preserves the original saved text, and stores responses
as structured blocks so explanatory prose and detected fenced code remain distinct. Language
detection/highlighting is presentation metadata and must not rewrite pasted content. Whole-content
and individual-code-block copy actions read the stored text.

Routes cover new global/contextual creation, existing capture editing and task-scoped creation.
Task-scoped captures register automatically as `capture.prompt` Page blocks through Capture actions.
Opening a Page block must route back to the canonical Prompt editor by capture ID.

Compatibility rules:

- Prompt shares the legacy `NoteBlock` structure; deleting legacy Note model/code without migration
  can break Prompt.
- Save/update/archive mutations go through `VirlinActions`.
- One capture has at most one Prompt companion document.
- Parser/display upgrades must preserve exact stored source and remain backward-readable.

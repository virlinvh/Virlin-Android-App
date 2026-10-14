# Bulk creation v1

Enabled in the project directory's New project popup and a project's New workstream popup,
including the mind map's New workstream action. Existing Single creation is retained.
Bulk mode provides a JSON input, accessible information button, copyable AI conversion prompt,
preview and explicit confirmation. Project-specific controls use the project's accent.

Schema:
```json
{"version":1,"items":[{"name":"First item"},{"name":"Second item"}]}
```

The screen owns the destination. No IDs, unknown fields, duplicate fields, children or metadata
are accepted. Limits: 50,000 input characters, 1–100 items, 1–200 characters per one-line name.
Names are trimmed as in single creation; duplicate names inside a batch are rejected case-insensitively.
Existing same-name entities are not merged or overwritten; this flow explicitly creates new items.

Parsing uses non-lenient JSON. Preview does not write anything. Confirmation reparses the source
and rechecks the project inside a single repository transaction. A deleted/non-active project
is refused. Shared writer-scoped single creation logic preserves defaults, ordering and events.
Any write failure rolls back the batch. Tests inject a failure on the second stream write to verify
the first item is not left behind.

Scope deliberately excludes nested task/subtask bulk creation in this first phase, as permitted
by the request. Their current Add flows are unchanged. Nested input is rejected, never flattened.
No schema migration, dependency installation, external AI request or existing-data rewrite occurs.

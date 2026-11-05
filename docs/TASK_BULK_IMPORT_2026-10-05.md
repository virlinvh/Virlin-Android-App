# Task and subtask bulk import

The Add task/subtask popup exposes Single and Bulk. Bulk uses the existing JSON name-list
schema, bounded parser, preview, info help, and copyable AI prompt. Single keeps its existing
placement and execution controls. Bulk clearly appends at the end with inherited execution;
it does not pretend to honor Single-mode placement settings.

Destinations are explicit: real workstream ID for stream roots, project ID for standalone roots,
and real parent task ID for children of any task/subtask. No virtual standalone stream ID is saved.
The domain derives child ownership from the real parent and applies existing closed/active-parent
rules. It creates every item through writer-scoped canonical task creation in one transaction;
any refusal or write failure rolls back the entire batch. JSON cannot choose IDs or destinations.

Mind-map container creation uses this same dialog for new tasks. Selected task/subtask Add palettes
offer New subtask (Single or Bulk) in addition to their unchanged eight content choices.
All saved tasks appear in live task lists and map projections without separate synchronization.

Tests cover workstream roots, standalone roots, children, grandchildren, invalid parent refusal,
and an injected failure on the second task write proving no partial task batch remains.
Nested JSON is still unsupported; a batch creates immediate siblings beneath its selected base.

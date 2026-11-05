# Workstream hierarchy bulk import

Root cause: BulkNames, its copyable AI prompt, and bulkCreateNames only supported flat version-1 names. Version-2 input was rejected before preview; no descendant creation existed.

The Add workstreams dialog now accepts version 2: `{"version":2,"items":[{"name":"Phase","children":[{"name":"Task","children":[{"name":"Subtask"}]}]}]}`.

Roots become new workstreams in the selected project. Children become canonical tasks, then recursive subtasks. Sibling array order determines persisted order. This is display order, not an execution dependency. The same shared dialog/action used by the mind map receives the enhancement.

Preview shows every item with indentation/type and total count. AI help extracts explicit requirements and relationships, merges repeated source sections and excludes forbidden/future work. Version-1 flat imports remain supported. Project-list and task-level dialogs retain their existing v1 format in this first rollout.

Validation: strict JSON; only version/items and name/children; no IDs; at most 250,000 characters, 500 total nodes (including all descendants), six levels, 200-character single-line names, unique siblings. Preview uses a virtualized list. No schema migration. All new streams, tasks and creation events use a single existing repository transaction; failures roll back. Tests cover ownership, ordered siblings, descendant failure rollback, invalid schemas/limits, flat compatibility and nested preview, and exact 500-node success / 501-node rejection without additional writes. Other flat-only import entry points retain their 100-name limit.

# Hierarchical bulk import at every creation level

All shared BulkCreationDialog entry points now accept v2 trees (and flat v1 compatibility), up to 500 total nodes, 250,000 characters, six imported levels. Limits describe this batch, not the existing parent's depth.

Destination mapping:
- Project list: projects → workstreams → tasks → subtasks.
- Existing project / Add workstreams: workstreams → tasks → subtasks.
- Workstream or standalone task creation: tasks → subtasks.
- Existing task or subtask: child subtasks → deeper subtasks. The existing parent is not recreated.

Preview labels and copyable outline-only AI prompt are destination-specific. Mind-map creation uses these same components/actions. Source array order becomes sibling display order, not execution dependencies. Canonical creation actions resolve ownership and inherit execution settings; no schema migration or changes to single creation. Each import runs in one repository transaction and all writes roll back if any descendant fails.

Tests cover project hierarchy ownership, task/subtask and standalone hierarchy ordering, context-specific prompt/preview, descendant rollback for project and task imports, v1 compatibility, 500-node limits and the shared UI. Supporting note/capture import remains separate; this JSON creates structural nodes only.

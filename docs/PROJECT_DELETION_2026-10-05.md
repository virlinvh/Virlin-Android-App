# Permanent project deletion

## Audit and plan

Project settings previously displayed deletion as unavailable. Room has no cascading foreign keys, so deleting only a project row would leave work and history behind. The implementation first resolves ownership, then deletes database records atomically, then cleans managed files and settings. A receipt in the existing `meta` table survives interruptions and is acknowledged only after cleanup succeeds. Startup retries outstanding receipts.

## User flow

Project settings → Project management → Delete project opens a separate permanent-deletion warning. The user must type the exact project name and press Delete permanently. Opening settings, canceling, or entering a different name does not delete anything. Deletion runs in the process scope, not the dialog scope. Success returns to Projects; cleanup failure after database commit reports that cleanup is pending.

## Ownership and cleanup

- Project, workstreams, standalone tasks, nested task descendants, checklist steps and task-page blocks.
- Project/workstream/task captures and their note, prompt, attachment and voice documents.
- Tags, tag links, priority preferences, rich note documents and mind-map previews.
- Cycles, focus sessions, snapshots, events and external stages, including task-execution history.
- Managed attachment copies, voice clips, custom icons and map-preview images, plus appearance/timer preferences and per-project map appearance.
- Pending alarms and displayed attention notifications. Deleted execution records no longer drive timers.

Other projects, unfiled captures, global notes, external source files and independently shared/exported files are preserved. Exported PDFs in the shared title-keyed cache are not authoritative project storage and are not deleted indiscriminately: legacy exports do not record project ownership.

File deletion accepts only fixed app-private roots and exact validated identifiers, refuses traversal and root escape, and never follows symbolic links. No workspace or broad app-data deletion is used. Process-local fences prevent already-open editors from recreating deleted rich notes/previews through delayed autosaves; map-appearance removal is serialized with saves.

## Recovery and testing

Database failure rolls back the complete tree and creates no cleanup receipt. File/platform cleanup failure leaves a durable receipt for retry rather than claiming complete cleanup. No schema migration is needed. Synthetic Room and in-memory fixtures test removal, unrelated-data preservation, rollback, traversal rejection and retry. A Compose test checks explicit typed confirmation. Existing emulator projects must not be deleted for verification.

This is permanent deletion, not trash/undo, and is not a forensic secure erase of SQLite pages, OS backups, or exports outside the app's ownership.

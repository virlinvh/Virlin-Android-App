# Workstream and recursive task deletion

## Audit and implementation

Permanent project deletion is now reused for workstream and task branches. Existing cancellation remains a separate, history-preserving operation; it is not silently replaced with deletion.

Manage → Delete workstream / Delete task opens a separate exact-name confirmation. Tasks use the same operation at every subtask depth, including completed nodes and standalone project tasks. Project overview workstream controls and standalone task menus are wired too. Cancel and outside-dismiss do not delete anything. The warning explicitly includes descendants, knowledge, history, recorded time and managed media, and states that there is no undo.

Ownership is resolved with an iterative, visited-set traversal, without a fixed nesting-depth limit or recursive stack usage. Existing legacy project receipts remain compatible. The existing transaction and durable cleanup receipt are shared across all three root kinds; startup retries interrupted cleanup.

For a branch deletion, the project and its shared tags, icons, palette, rich project note and timer defaults survive. Parents, siblings and other workstreams survive. Exact owned captures and documents, task pages/checklists, rich notes, map previews, timing/history and managed files are removed. Task events recorded against a surviving parent workstream are removed by decoding exact task identity, not by substring matching. Surviving active-task pointers are cleared; legacy parent tracking tied to removed tasks stops without selecting or starting replacement work. Terminal containers stay terminal.

Deleted map node/branch color overrides are pruned while the project map appearance is retained. Pending reminders and notifications are canceled. The live hierarchy already trims missing IDs from its open breadcrumb, so deleting the current task returns to the surviving level; deleting a workstream returns to the previous page, with a project/directory fallback. Repository publication updates the mind map automatically.

## Verification boundaries

Retained knowledge in another project is not deleted merely because its historical conversion points to removed work. Its `convertedTaskId` is cleared to prevent a dangling reference; the original content and ownership are preserved.

Synthetic tests cover a 5,000-level chain, malformed cycles, rollback, Room restart/receipt recovery, parent/sibling/project preservation and control affordances on both compact workstreams and terminal nested tasks. Existing user projects are never deleted for verification. Unlimited depth means no artificial traversal limit, not infinite device storage or memory.

Project deletion's file-path validation, symlink protections, post-commit recovery, delayed-autosave fences and export boundaries remain in effect. Original files and independently shared/exported PDFs are preserved. This is not forensic erasure of SQLite pages or OS backups.

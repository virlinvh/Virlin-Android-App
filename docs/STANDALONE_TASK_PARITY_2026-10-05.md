# Standalone task parity

Project standalone tasks now use the same TaskRow renderer and task hierarchy screen as workstream tasks. Project rows open the exact task's subtask level; the execution dial and badge open its shared controls. Creation uses the shared task form with placement and execution options.

Standalone tasks retain their IDs, project ownership, null workStreamId, history and nested descendants. No database migration or extra workstream is created. A display-only context supplies the shared screen; mutations target actual task/project IDs. Timing projection uses transient copies scoped to standalone task IDs.

Move/reorder uses the existing validated project branch-placement action, including revision checks. Multiple selected branches are moved sequentially; partial failure is reported rather than presented as an atomic success. No-op placement is accepted.

Existing safe popup hosting and app footer are retained. Physical-device testing remains necessary for Samsung-specific keyboard/navigation behavior.

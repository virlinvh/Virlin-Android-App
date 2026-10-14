# Knowledge organization

The project gallery shows saved knowledge only, not projected executable task records. Existing tasks are unchanged and remain available as attachment destinations.

Each item offers Open knowledge, Move, Duplicate and Delete knowledge. Tags and task-conversion controls are not exposed in this menu. Opening and mutation callbacks use the original capture ID.

Move browses project-wide, workstream, task and arbitrary subtask destinations. Expanding is separate from selecting; selecting does not persist until Move knowledge is pressed. Search retains matching ancestors. Placement reuses attachCapture, preserving content and identity without creating tasks.

Move and delete panels are in the bounded Knowledge viewport rather than modal bottom-sheet windows, so the shared navigation footer is not covered. Delete is confirmed for the named knowledge item and reuses archiveCapture: its file and Activity history remain, and no task/project is deleted. Duplication preserves the existing copy/storage implementation and reports Added to knowledge only after success.

Verification: knowledge domain and Compose tests passed, including exact IDs, task exclusion, deep-subtask search, explicit move confirmation, cancel/delete safety and footer bounds. Debug APK assembled and installed without clearing app data.

# Mind-map project and workstream actions

Selected project and workstream nodes now show a contextual bottom toolbar alongside task nodes.
Projects expose Open Project, Preview, and Add; workstreams also expose Move using the existing
confirmed destination flow. Project movement is deliberately unavailable.

Project Add offers New workstream and New standalone task. Workstream Add offers New task.
All creation uses VirlinActions, not direct repository writes. Names require confirmation;
rejections are surfaced. Container Preview reuses the existing node-preview editor.
The selected container's inline plus also opens its Add menu when no saved preview exists;
an existing preview's pencil continues editing that preview.

Add content asks for an existing task in the selected project or workstream, then shows the
unchanged eight-choice task palette. No implicit task is created, no container id is passed as
a task id, and removed Sticker/Illustration placeholders stay absent. Task owners are re-resolved
before dispatch. An empty branch explains that a task must be created first.

Regression coverage: MapSelectionFlowUiTest exercises project/workstream toolbar and container
callbacks, task/subtask toolbar actions, and all eight palette callbacks with their owner IDs.
Physical device verification is still required for real-device layout and touch behavior.

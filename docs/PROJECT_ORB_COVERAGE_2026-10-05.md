# Project navigation orb coverage

Scope: project directory (All and filtered Projects/Tasks views), project overview, workstream task hierarchy at every task/subtask depth, standalone task hierarchy, project task index, project Knowledge gallery, and project Activity timeline. Mind-map canvas/toolbars and unrelated app screens are intentionally unchanged.

The shell publishes the measured orb slot in root coordinates. Project card surfaces now retain their full outer width, with `circularOrbCutout` removing only a circle around the orb plus an 8dp safety gap. `avoidOrbCircle` protects their inner content bands following the circle's chord. Knowledge uses individual text/control bands so its equal-width grid never shrinks. Rows above or below the circle keep their original layout. An absent orb or the open Agent overlay disables this page reservation. Existing footer insets and bottom Add Task/Subtask clearance remain intact.

Coverage is attached to project directory cards, filtered project groups/task rows, shared hierarchy TaskRow, project index task rows, knowledge gallery pairs, and timeline rows. Overview workstreams, standalone/knowledge section headers and preview actions were already covered. Existing bottom content padding is retained to allow last items to scroll above the orb.

No persisted data, execution logic, navigation callbacks or mind-map layout changes. Geometry and measured header tests cover overlapping, above/below, absent and resized orb cases; physical-device QA is still recommended for large font and narrow-window layouts.

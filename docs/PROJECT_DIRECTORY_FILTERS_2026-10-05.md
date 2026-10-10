# Project directory filters

## Behavior

- All preserves the project directory and project-title search.
- A status selection defaults to Tasks; Projects / Tasks switches only the presentation.
- Tasks includes incomplete executable leaves at any depth and empty workstreams, grouped by project. Projectless work is grouped separately.
- Containers never appear as execution results. Group headings collapse without changing any stored work.
- Names are exact; ancestry is nearest-parent-first, one line with ellipsis. Timing comes from recorded focus sessions or external execution accounting, never estimates.
- Opening a row navigates by stable identity and full ancestor path. No filter, toggle, or collapse action starts execution.
- Status chips occur once, in a finite horizontal list. Stored enum identifiers are retained for compatibility; labels are clearer.

## Filter contract

Needs You uses the domain attention projection (including due checks and due scheduled work). Running includes active external work even when its check is overdue. These two filters can intentionally overlap. Focused uses FOCUS; Paused includes paused human and external work; Ready excludes paused external work. Scheduled uses not-yet-due SNOOZED. Blocked uses BLOCKED. Completed/cancelled leaves are excluded.

## Safety and rollback

Verification: full debug unit/Compose suite passed (1,243 tests, zero failures/errors); lint passed with zero errors and 127 warnings; debug APK assembled. Data-preserving installation and launch on Pixel_8 succeeded. Live UI inspection confirmed All retains five project cards and Running defaults to Tasks, showing four matching items in two project groups, including the exact nested `sb 1` and `Ext` titles, reverse ancestry and external timing. Existing lint warnings remain; this is not a claim of production certification.

This change is a read-only UI projection: no database migration, execution engine modification, seed changes, data clearing, or remote writes. Revert only the directory changes in StreamsScreen/VirlinProjectDirectory and the new DirectoryWorkProjection/FilteredProjectTasks files to roll back, preserving unrelated local edits. Regression tests cover nested executions, deadlines, pause handling, ancestry search, projectless work, view selection, collapse and exact result navigation callbacks.

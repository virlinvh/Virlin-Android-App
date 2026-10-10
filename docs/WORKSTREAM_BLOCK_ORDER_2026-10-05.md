# Project workstream block ordering

Project settings → Organization includes an off-by-default, project-scoped
"Organize workstreams as blocks" switch. This display preference saves immediately.
When enabled, Overview shows six-dot handles with accessible Move up / Move down
actions. Long-press a handle to drag; a floating preview and insertion line mark
the drop location. Edge scrolling supports long lists. Cancelling never writes.

Drops invoke the existing `placeBranch` command with stable workstream IDs and a
fresh hierarchy revision. It validates and commits sibling `sortOrder` changes
transactionally. No tasks, ownership, execution state, or timers are changed.
Overview consumes the stored order, matching the mind map. Invalid or conflicting
drops show an error; project deletion removes the new display preference.

Tests cover opt-in/project isolation, accessible ordering, pointer drop/cancel,
canonical reorder preserving descendants and other projects, header collapsing,
and settings viewport behavior.

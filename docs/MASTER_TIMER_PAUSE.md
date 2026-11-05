# Master timer pause

The Now header keeps the existing date pill unchanged. A separate, background-free
14 × 20dp hourglass sits to its right inside a 48dp accessible touch target.
Natural sand tones indicate any running timer; neutral gray indicates no running
timers. A six-second sand flow ends in a smooth 600ms half-turn, then repeats.
The animation is decorative, lifecycle-aware, and stops when system animations
are disabled; it is not a task-duration counter.

One tap calls `VirlinActions.pauseAllTimers`. One repository transaction and one
clock timestamp close active human focus sessions and bank each active external
segment, preserving task attribution, snapshots, events, and existing totals.
Legacy stream activity and independently stored task executions are both covered.
Active timer checks are cleared and the existing post-commit scheduler cancels
their alarms. Already inactive/ready/completed items are unchanged. Repeated taps
are idempotent. A busy guard prevents duplicate UI submissions.

The confirmation is inline, not a popup. A second tap resumes the master-paused
group using durable pause events; manually paused or subsequently changed items
are excluded. Remaining reminder delay is restored relative to the resume time.
Only one human session can resume; an existing new human focus is not displaced.
Old-version master pause events are supported, but their removed reminder delay
cannot be reconstructed. External pause
only stops Virlin's tracking, not an external service or real-world work.

The project toolbar reuses the same hourglass beside Search. Its running color
comes from the project's primary palette; paused remains gray. Passing a project
id scopes the same atomic actions to that project's streams and tasks, resolving
task ownership through its actual workstream. Other projects' records and timers
are untouched. Project resume can restore that project's globally master-paused
items too; global resume can restore project-paused items. Neither operation
resumes a manually paused item or displaces an existing human focus elsewhere.

Tests cover simultaneous human/external pause, accumulated durations, idempotence,
selective resumption, legacy external CHECK state, alarm cancellation, accessible
clicks, busy protection, and unchanged date-pill dimensions/right-hand placement.

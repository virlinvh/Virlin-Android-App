# Virlin product context

Virlin is a native Android **external working-memory and human-attention orchestration system**
for people managing many simultaneous work streams. It is NOT a to-do app, project-management
app, or timer app.

## Core principle

External tools/processes can work in parallel; human attention works serially. Virlin decides
what needs human attention now while preserving the exact context of everything else.

## What the system preserves

- what the user is currently focusing on (with the deepest active Task)
- what external agents/tools are processing (and since when)
- when something should be checked again / when the user should return
- where the user left each WorkStream (ContextSnapshot: last action · waiting for · next action)
- what should receive attention next (Needs You vs Working For You vs When You're Free)
- focused human time, external processing time, waiting utilization

## The core object: WorkStream

A WorkStream is a **persistent chain of work** that is repeatedly entered, left, resumed, or
handed off — not a one-off task. Two modes, explicit domain data:

- `HUMAN` — the user does the work; it consumes Focus while active.
- `EXTERNAL` — an external process does the work; it consumes **no** human attention while
  PROCESSING; a `checkAt` schedule brings it back for review.

Many streams may be PROCESSING at once; normally one stream holds human FOCUS.

## The two fundamental movements

- **LEAVE** — the human stops paying attention. Position is preserved. Without a return time →
  READY; with one → SNOOZED (`HUMAN_RETURN`). LEAVE never means PROCESSING.
- **HAND OFF** — give the work to an external process. FOCUS → PROCESSING, optionally with a
  `checkAt`. Used only for external streams.

For external work, a due check offers STILL RUNNING / RESULT READY (Focus now, or remind
later → `EXTERNAL_RESULT_READY`) / BLOCKED. A due human return offers RESUME. Neither steals
Focus by itself.

## Root navigation (permanent)

**Now · Streams · Pulse · Inbox** — bottom-attached bar.

- **Now** — the primary attention surface: Current Focus (split-flap timer), Needs You,
  Working For You, When You're Free, floating Orb.
- **Streams** — Projects → WorkStreams → recursive Tasks (Projects live inside this tab).
- **Pulse** — timeline/activity view.
- **Inbox** — the durable Capture Inbox (with a live INBOX-count badge). Note: Agent CAPTURE
  (quick save) and Inbox (review/manage captured items) are different surfaces.

Settings/profile stay outside the four tabs.

## The Agent

One in-window sheet opened by the Orb. Entry step ("How can I help?") → one of exactly three
deterministic modes, then only that mode's workspace:

- **CONTROL** — act on existing work (focus, leave, hand off, check, block, complete…)
- **CREATE** — create Project / WorkStream / Task
- **CAPTURE** — instantly preserve raw notes/prompts/links; organize later

The Orb is the signature living entry object (small liquid-glass sphere, root overlay, never
scrolls with content). The Agent is never a chat page, a fourth tab, or a transcript.

## Key UX principles

- Visual hierarchy: Current Focus → Needs You → Agent → Working For You → Ready work.
- Motion communicates state; no decorative animation. High information density, low perceived
  complexity. No Trello/Notion look, no neon, no gamification.
- Design sources (Stitch / screenshots / HTML) are visual contracts when supplied — translate,
  don't "improve" (see `.glm/workflows/UI_TRANSLATION.md`).

Full detail: `CLAUDE.md` and the `virlin-project` skill.

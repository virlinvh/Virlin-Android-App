# Frozen contracts — what GLM must not change casually

"Frozen" means **no accidental change** — not permanently immutable. If the user explicitly
asks to change one of these, it changes through a deliberate implementation pass with tests
and a status-doc update, never as a drive-by edit or a "small cleanup".

1. **Now UI structure** — Header · Current Focus · Needs You · Working For You · When You're
   Free · Orb · bottom nav (Now/Streams/Pulse/Inbox). No redesign without explicit request.
2. **Virlin Orb** — Stitch liquid-glass rendering, 52dp, bottom-right overlay owned by the
   root `OrbTravelLayout` (never screen content), existing interaction state machine. Never a
   mic/chatbot/sparkle/brain icon.
3. **Fullscreen Focus Clock** — grouped two-card flip, black canvas, shares the SAME
   FocusSession; `FocusClockScreen` owns no timing state; Now keeps its per-digit animation.
4. **Split-flap timer** — signature interaction; never replaced with a normal digital timer.
5. **Hierarchy** — optional Project / WorkStream / recursive Task. No Stage, Step or Subtask
   entity; "subtask" is language for a child Task. Projectless WorkStreams supported.
6. **Task semantics** — `TODO/IN_PROGRESS/DONE/CANCELLED`; DONE ≠ CANCELLED (cancelled is
   never successful completion); progress from executable leaves only; count vs effort modes
   never mixed; parent status never auto-mutated.
7. **WorkStream states & transition table** — the 8 accepted states; `WorkStreamTransitions`
   is the only place state changes; single-Focus invariant; many PROCESSING; PROCESSING
   consumes no attention and is never SNOOZED.
8. **LEAVE ≠ HAND OFF** — leave preserves position (READY or SNOOZED/HUMAN_RETURN, never
   PROCESSING); hand off is the external intent (FOCUS → PROCESSING, mode EXTERNAL from data).
9. **Check flow semantics** — STILL RUNNING / RESULT READY (focus now · remind later →
   EXTERNAL_RESULT_READY) / BLOCKED; HUMAN_RETURN and EXTERNAL_* reasons stay distinct; a due
   check never steals Focus.
10. **Time model** — timestamps are truth; elapsed derived; no per-second DB writes; no UI
    counter as authoritative time.
11. **Room source of truth** — schema v2 with real migrations only (never a destructive
    fallback); UI never touches DAOs; every action in one transaction; publish-on-commit.
12. **Scheduling architecture** — Virlin action → Room commit → scheduler; alarms only wake
    and validate from Room; scheduling arises solely from `SchedulingWorkStreamRepository`.
13. **Notification behaviour** — actions call the `VirlinActions`/domain path; no direct Room
    mutation from notification or UI code; stale actions dismiss without mutating.
14. **Control V1** — deterministic `[ACTION] + [TARGET] [+ TIME]` semantics, kind-less
    targets, action-filtered resolution, typed clarification continuation, Leave/Hand-off/
    Check/Remind/Block semantics, no awareness/query layer (Now is the awareness surface).
15. **Create V1** — entity word required; WorkStream mode never inferred from title; owner
    kinds Project/WorkStream/Task; preview before create; multi-step clarification continues
    the same pending command.
16. **Capture V1 / raw boundary** — CAPTURE mode never interprets; explicit prefixes keep
    payload raw; captures have zero side effects; conversion to Task is explicit and atomic.
17. **Deterministic-only language** — no LLM/model/network behind interpretation
    (`DeterministicOnlyTest` guards this); `TextCommandInterpreter`, `DurationParser`,
    `TimeExpressionParser` are authoritative temporal/grammar meaning.
18. **Command safety** — resolution order exact id → exact title → unique case-insensitive →
    unique prefix/word → clarification; never select arbitrary first matches; pending command
    state survives clarification; destructive commands stay confirmation-gated.
19. **Root navigation** — Now / Streams / Pulse / Inbox, bottom-attached bar; Inbox = the
    capture Inbox with badge; Projects are a section inside Streams, never a fifth tab.
20. **Agent entry sheet** — Orb → "How can I help?" → one mode workspace; mode tabs do not
    exist; the Orb fades out inside the workspace.

Also protected by rule, not marked frozen: `app/schemas/**`, Roborazzi goldens (re-record
only with explicit user approval of the diff), `docs/DEVELOPMENT_STATUS.md` accuracy.

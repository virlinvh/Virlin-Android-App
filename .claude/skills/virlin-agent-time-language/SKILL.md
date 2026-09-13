---
name: virlin-agent-time-language
description: Virlin's deterministic calendar/time language (Pass 13) — TimeExpressionParser on an injected clock + ZoneId, TemporalIntent (Relative vs Absolute), TimeLanguagePolicy daypart defaults, temporal clarifications (time required, AM/PM, already passed), no past scheduling, relative time evaluated at execution and absolute time revalidated. Load ONLY when changing time phrases, the policy or how commands carry times. Do not load for Orb, Now, hierarchy, persistence or the command contract itself.
---

# Virlin — Agent Time Language

Load on demand. Contract: `virlin-agent-command`. Grammar: `virlin-agent-text-command`.

## Model

- Files: `domain/command/time/TemporalIntent.kt` (`TemporalIntent.Relative(Duration)` /
  `Absolute(Instant, source)`, `TimeLanguagePolicy`, `TimeFormatter`), `TimeExpressionParser.kt`
  (`TimeParse.Resolved / Clarify(TemporalClarification) / Unsupported`).
- Commands carry `TemporalIntent` (`LeaveCurrent.returnAt`, `HandOffCurrent.checkAt`,
  `StillRunning.checkAt`, `ResultReadyLater.returnAt`); `Duration` constructors remain for the
  relative forms. The domain still receives plain instants.

## Rules (hard)

- **Fixed clock + ZoneId, injected.** The parser takes a `VirlinClock` and a `ZoneId`
  (`VirlinGraph.zone` = device default). Never `Instant.now()`/`ZonedDateTime.now()` inside the
  `time` package, never a hardcoded offset or IST; all arithmetic is java.time (guarded by a
  unit test). Tests use `FakeClock` + `Asia/Kolkata` / `America/New_York`.
- **Relative ≠ absolute.** "for 10 minutes", "in half an hour", "in an hour and 30 minutes"
  stay `Relative` and are turned into an instant by the EXECUTOR's clock (a preview left open
  does not go stale). "at 5 PM", "tomorrow morning", "Monday at 9 AM" become `Absolute` at
  interpretation and are **re-validated at execution** (past → `Rejected(stale)`, never scheduled).
- **Daypart defaults live only in `TimeLanguagePolicy`:** morning 09:00 · afternoon 15:00 ·
  evening 19:00 · night/tonight 20:00; relative ≤ 24 h; absolute ≤ 8 days. Every preview and
  feedback shows the exact resolved local time ("Tomorrow · 9:00 AM"), never a raw instant.
- **Never schedule in the past, never roll forward silently.** A passed clock time or daypart
  is a clarification with a concrete "tomorrow …" suggestion; the user chooses.
- **Ambiguity asks:** bare "tomorrow" / "today" / weekday → TIME_REQUIRED (morning / afternoon /
  evening suggestions); "at 9" (no AM/PM, hour ≤ 12) → AM_PM_REQUIRED; ≥ 13 is 24-hour.
- **Weekday rule:** "monday" = first Monday strictly after today (today's weekday → next week);
  "next monday" = the Monday of the NEXT Mon–Sun week (may coincide with the bare form).
- DST gaps (nonexistent local times) → INVALID_LOCAL_TIME clarification; overlaps follow java.time.
- **Unsupported, not guessed:** after lunch, sometime, in a bit, when …, next weekend, end of the
  day, ASAP, noon/midnight, every …, daily, weekdays, next week/month.
- **CAPTURE mode never interprets time language** (raw text is saved); prompts/notes containing
  time phrases are data. Queries stay read-only.
- No recurring reminders, no generic reminder engine, no Task due-date parsing, no LLM/voice.

## Where the time tail comes from (Control 2)

Named commands (`leave X until T`, `check X in D`, `X is still running, check at T`, `remind me
about X at T`, …) split the tail off as TEXT in `TextCommandInterpreter.splitTime` — the first
split point the time layer RESOLVES, else the first it can CLARIFY — and hand it here unchanged.
The typed `TemporalIntent` is stored in the command, so target clarifications continue with the
same instant/duration. This layer stays the only time authority: no second parser, no defaults.

## Grammar (implemented)

`leave [this|current] for|in|until|till <t>` · `hand [this] off for|in|until|till <t>` · `still
running for|in|until <t>` · `check again [in|at] <t>` · `result ready remind me [in|at] <t>` ·
`remind me about the result [in|at] <t>`, where `<t>` is: `N min|minutes|m|h|hour(s)`, `half an
hour`, `an hour`, `an hour and N minutes`, `N hours and M minutes` · `[at] 3 pm | 3:30 PM | 15:30`
· `[later] today at <clock>` · `this afternoon|evening` · `tonight` · `tomorrow [at <clock> |
morning|afternoon|evening|night]` · `[on|next] <weekday> [at <clock> | <daypart>]`.

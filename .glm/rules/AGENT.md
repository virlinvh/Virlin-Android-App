# Agent rules (Control · Create · Capture)

The Virlin Agent is ONE in-window sheet opened by the Orb: entry step → exactly one of three
deterministic modes. Details: `virlin-agent-control`, `virlin-agent-create`,
`virlin-agent-capture`, `virlin-agent-command`, `virlin-agent-text-command`,
`virlin-agent-time-language` skills. All three modes are **frozen V1**.

## CONTROL

Acts on existing work: focus/switch/resume, leave (optionally timed → HUMAN_RETURN), hand off
(external only, optional check), check / still running / result ready (focus now · remind
later → EXTERNAL_RESULT_READY) / block, complete task or workstream (confirmation-gated),
cancel task, set current task, open/show (navigation). Grammar is `[ACTION] + [TARGET]
[+ TIME]`; targets are kind-less `TargetRef.Named` resolved through the action's allowed
kinds; Tasks locate their owning WorkStream for attention actions; Block is WorkStream-only;
Projects are not attention targets. No awareness/query layer — Now is the awareness surface.

## CREATE

Creates structure: Project · WorkStream (Human/External — mode **never inferred** from the
title; Project optional) · Task (owner Project/WorkStream/Task, recursive nesting, no depth
limit). "Subtask" is language for a child Task. The entity word is required; the title is the
complete remaining text (command words inside are data); every Create is previewed before
execution; multi-step clarification continues the same pending command.

## CAPTURE

Stores information — DATA, never a command. CAPTURE-mode submissions go
`AgentCaptureViewModel.save` → `createCapture` → Room and are never interpreted.
NOTE / PROMPT / LINK types; INBOX / ORGANIZED / ARCHIVED statuses (archive, no hard delete);
optional explicit context (Project/WorkStream/Task, validated, never inferred from content);
`convertCaptureToTask` is the single explicit atomic conversion. In CONTROL/CREATE, explicit
prefixes (`remember | save note | save prompt | save link`) recognize capture intent, then
the payload stays raw.

## Deterministic pipeline (the only path)

```
text → TextCommandInterpreter → TimeExpressionParser/DurationParser → VirlinCommand
     → CommandResolver (clarification / confirmation / preview values)
     → CommandExecutor → VirlinActions → Room (+ AlarmManager via the repository decorator)
```

Resolution order: exact id → exact normalized title → unique case-insensitive → unique
prefix/word → typed clarification with candidates (never an arbitrary first match). Pending
command state survives clarification. `CompleteStream`/`CancelTask` cannot bypass
confirmation. `Unsupported` input stops with feedback — there is no second interpreter,
provider, model or network behind the parser (`DeterministicOnlyTest` guards this).

## Stress

**GLM is NOT a runtime Agent provider.** Do not integrate GLM (or any model/API) into the
application runtime merely because GLM is used for development. Virlin V1 is deliberately
deterministic: no LLM, no local/cloud model, no API key, no network interpretation.

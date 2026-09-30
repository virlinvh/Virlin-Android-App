# GLM task template

Compact reusable shape for instructing a GLM session. Fill only what is needed; keep it short.

```markdown
TASK:
<one or two sentences — the exact delta requested>

ALLOWED SCOPE:
<files/packages the task may touch>

DO NOT TOUCH:
<everything else, e.g. Now UI, Orb, TextCommandInterpreter grammar, Gradle, goldens>

SOURCE OF TRUTH:
<e.g. docs/DEVELOPMENT_STATUS.md §Pass 12, .claude/skills/virlin-agent-text-command>

RELEVANT SKILL:
<one existing skill from .glm/SKILL_MAP.md>

GRAPHIFY:
query first (`graphify query "..."`), then read minimal source.

ACCEPTANCE:
<observable, checkable outcomes>

TEST:
<focused test(s) to run, e.g. gradlew.bat test --tests "com.virlin.app.command.X"; build if UI>

REPORT + STOP.
```

Rules of use:

- ACCEPTANCE and TEST are the two most valuable fields — never omit them.
- If the task touches a frozen contract, say so explicitly in TASK.
- Keep DO NOT TOUCH short but explicit; frozen surfaces default to it.

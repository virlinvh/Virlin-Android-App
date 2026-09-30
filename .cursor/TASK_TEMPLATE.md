# Cursor task template

Copy into a task prompt when useful. Keep fields short.

```
TASK:
…

GOAL:
…

ALLOWED SCOPE:
…

DO NOT TOUCH:
… (default: CLAUDE.md, .claude/**, GLM.md, .glm/**, unrelated surfaces, goldens without approval)

RELEVANT SKILL:
… (path under .claude/skills/…/SKILL.md — see .cursor/SKILL_MAP.md)

GRAPHIFY:
query first → explain / path / affected as needed
then inspect minimal source only

FROZEN CONTRACTS:
… (see .cursor/context/FROZEN_CONTRACTS.md)

ACCEPTANCE:
…

TEST:
smallest relevant → focused regression
(classify WAC / emulator failures as environment unless proven product)

GRAPHIFY UPDATE:
only if structural implementation changed → `graphify update .`
never full extract after tiny edits

REPORT:
files changed · why · verification · STOP

STOP.
```

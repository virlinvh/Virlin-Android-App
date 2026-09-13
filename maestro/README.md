# Virlin Maestro flows

E2E flows for the Virlin Android app, run against the Pixel 8 API 35 emulator.

## Status: flows authored, CLI NOT YET INSTALLED

Maestro's CLI has **no native Windows build and no winget package** — it requires WSL2
with a real Linux distribution. This machine has WSL2 enabled but only Docker Desktop's
internal utility distro, which is not a usable environment for Maestro.

These flows are therefore committed but **unverified**. See
`docs/DEVELOPMENT_TOOLCHAIN.md` for the exact manual steps to enable Maestro.

In the meantime, live device inspection and assertion is available today through the
official Android CLI, which works natively on Windows:

```bash
android layout
```

## Flows

| Flow | Purpose |
|---|---|
| `smoke/launch-app.yaml` | App launches and the Now screen renders |
| `now/verify-now.yaml` | Approved Now structure + bottom navigation + Orb are present |
| `agent/open-close-agent.yaml` | Orb opens the Agent (CONTROL/CREATE/CAPTURE); Back returns to Now |

> The Orb tappability defect that once blocked the Agent flow was resolved by the Living Orb
> Interaction System (2026-09-11). The same behaviour is covered today by the instrumented
> `VirlinUiTest` on Pixel 8, so this flow is expected to pass once Maestro is available.

## Running (once Maestro is installed)

```bash
maestro test maestro/smoke/launch-app.yaml
```

```bash
maestro test maestro/
```

## Conventions

- Match on semantic text or accessibility labels — never coordinate taps.
- The Orb is addressed as `"Open Virlin Agent"` (see `VirlinOrbContentDescription`).
- Do not change approved Now visuals to satisfy a flow; add non-visual semantics instead.
